package com.tk.flush;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.google.common.collect.Lists;
import com.tk.protocol.kafka.KafkaTopic;
import com.tx.common.entity.*;
import com.tx.common.message.PersistenceBatch;
import com.tx.common.service.PersistenceService;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 消费所有分片的 trading_result_(分片) topic，将变更同步到数据库。
 * 使用 topic 通配符订阅所有分区、所有分片；单条失败时回退 offset 并重试（带退避）。
 */
@Service
public class DataSynchronizationService implements SmartLifecycle {

    private static final AtomicInteger threadNumberIndex = new AtomicInteger(0);
    private static final Logger logger = LoggerFactory.getLogger(DataSynchronizationService.class);

    /**
     * 匹配所有分片：trading-result-0, trading-result-1, ...
     */
    private static final Pattern TRADING_RESULT_TOPIC_PATTERN =
            Pattern.compile("^" + Pattern.quote(KafkaTopic.TRADING_RESULT) + ".+");

    private final String servers;
    private final int consumerThreads;
    private final long retryIntervalMs;
    private final int retryBackoffMaxMs;

    private ExecutorService executor;
    private volatile boolean start;

    private final PersistenceService persistenceService;

    public DataSynchronizationService(@Value("${kafka.servers}") String servers,
                                      @Value("${flush.consumer.threads:4}") int consumerThreads,
                                      @Value("${flush.retry.interval-ms:1000}") long retryIntervalMs,
                                      @Value("${flush.retry.backoff-max-ms:30000}") int retryBackoffMaxMs,
                                      PersistenceService persistenceService) {
        this.servers = servers;
        this.consumerThreads = consumerThreads <= 0 ? 4 : consumerThreads;
        this.retryIntervalMs = retryIntervalMs;
        this.retryBackoffMaxMs = Math.max(retryBackoffMaxMs, (int) retryIntervalMs);
        this.persistenceService = persistenceService;
    }

    @Override
    public void start() {
        logger.info("start_consumer: group=flush, pattern={}, threads={}", TRADING_RESULT_TOPIC_PATTERN, consumerThreads);
        start = true;
        executor = new ThreadPoolExecutor(
                consumerThreads, consumerThreads,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                r -> new Thread(r, "flush-sync-" + threadNumberIndex.incrementAndGet())
        );
        for (int i = 0; i < consumerThreads; i++) {
            executor.execute(this::runConsumer);
        }
        logger.info("started_consumer: group=flush, threadCount={}", consumerThreads);
    }

    @Override
    public void stop() {
        if (start) {
            start = false;
            if (executor != null) {
                executor.shutdown();
                try {
                    if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                        executor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    executor.shutdownNow();
                }
            }
            logger.info("stop_sync_to_db_consumer: group=flush");
        }
    }

    @Override
    public boolean isRunning() {
        return start;
    }

    private void runConsumer() {
        Properties props = getProperties();

        Map<String, Map<Integer, Long>> topicOffsetPartitions = new ConcurrentHashMap<>();

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(TRADING_RESULT_TOPIC_PATTERN, new ConsumerRebalanceListener() {
                @Override
                public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                    for (TopicPartition p : partitions) {
                        Map<Integer, Long> map = topicOffsetPartitions.get(p.topic());
                        if (map != null) {
                            map.remove(p.partition());
                        }
                    }
                }

                @Override
                public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                    for (TopicPartition p : partitions) {
                        topicOffsetPartitions.computeIfAbsent(p.topic(), k -> new ConcurrentHashMap<>()).put(p.partition(), null);
                    }
                    logger.info("onPartitionsAssigned: {}", partitions.stream().map(p -> p.topic() + "-" + p.partition()).collect(Collectors.toList()));
                }
            });

            int consecutiveFailures = 0;

            while (start) {
                ConsumerRecords<String, String> records;
                try {
                    records = consumer.poll(Duration.ofMillis(200));
                } catch (Exception e) {
                    logger.warn("poll error", e);
                    sleepRetry(consecutiveFailures);
                    continue;
                }

                if (records.isEmpty()) {
                    consecutiveFailures = 0;
                    continue;
                }

                boolean batchSuccess = true;
                for (ConsumerRecord<String, String> record : records) {
                    Map<Integer, Long> offsetMap = topicOffsetPartitions.computeIfAbsent(record.topic(), k -> new ConcurrentHashMap<>());
                    offsetMap.put(record.partition(), record.offset());

                    try {
                        processRecord(record);
                        consecutiveFailures = 0;
                    } catch (Exception e) {
                        logger.error("processRecord failed, topic={}, partition={}, offset={}, value={}",
                                record.topic(), record.partition(), record.offset(), record.value(), e);
                        batchSuccess = false;
                        consumer.seek(new TopicPartition(record.topic(), record.partition()), record.offset());
                        sleepRetry(++consecutiveFailures);
                        break;
                    }
                }

                if (batchSuccess) {
                    try {
                        consumer.commitSync();
                    } catch (Exception ee) {
                        logger.warn("commitSync failed", ee);
                        for (Map.Entry<String, Map<Integer, Long>> e : topicOffsetPartitions.entrySet()) {
                            for (Map.Entry<Integer, Long> pe : e.getValue().entrySet()) {
                                if (pe.getValue() != null) {
                                    consumer.seek(new TopicPartition(e.getKey(), pe.getKey()), pe.getValue());
                                }
                            }
                        }
                        sleepRetry(++consecutiveFailures);
                    }
                }
            }
            logger.info("stop_consumer: group=flush");
        }
    }

    private Properties getProperties() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, servers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "flush");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return props;
    }

    private void sleepRetry(int consecutiveFailures) {
        long ms = Math.min(retryIntervalMs * (1L << Math.min(consecutiveFailures, 10)), retryBackoffMaxMs);
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            logger.warn("retry sleep interrupted");
        }
    }

    private void processRecord(ConsumerRecord<String, String> record) {
        List<PersistenceBatch> messageItems = new ArrayList<>();
        if (logger.isDebugEnabled()) {
            logger.debug("sync_to_db: topic={}, partition={}, offset={}", record.topic(), record.partition(), record.offset());
        }
        JSONArray array = JSON.parseArray(record.value());
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.size(); i++) {
            PersistenceBatch.Type type = PersistenceBatch.Type.fromValue(array.getJSONObject(i).getInteger("type"));
            if (type == null) {
                continue;
            }
            JSONArray messages = array.getJSONObject(i).getJSONArray("messages");
            if (messages == null) {
                continue;
            }
            switch (type) {
                case ACCOUNT:
                    for (int j = 0; j < messages.size(); j++) {
                        appendMessage(messageItems, type, messages.getJSONObject(j).toJavaObject(Account.class));
                    }
                    break;
                case TRANSFER:
                    for (int j = 0; j < messages.size(); j++) {
                        appendMessage(messageItems, type, messages.getJSONObject(j).toJavaObject(Transfer.class));
                    }
                    break;
                case ORDER:
                    for (int j = 0; j < messages.size(); j++) {
                        appendMessage(messageItems, type, messages.getJSONObject(j).toJavaObject(Order.class));
                    }
                    break;
                // 现货模式下不再处理 POSITION 类型的批次；如收到则忽略。
                case TRADE_ORDER:
                    for (int j = 0; j < messages.size(); j++) {
                        appendMessage(messageItems, type, messages.getJSONObject(j).toJavaObject(TradeOrder.class));
                    }
                    break;
                default:
                    break;
            }
        }
        if (!messageItems.isEmpty()) {
            persistenceService.flush(messageItems);
        }
    }

    private void appendMessage(List<PersistenceBatch> messageItems, PersistenceBatch.Type type, Object entity) {
        if (!messageItems.isEmpty() && Objects.equals(messageItems.get(messageItems.size() - 1).getType(), type.getValue())) {
            messageItems.get(messageItems.size() - 1).getMessages().add(entity);
        } else {
            messageItems.add(new PersistenceBatch(type.getValue(), Lists.newArrayList(entity)));
        }
    }
}
