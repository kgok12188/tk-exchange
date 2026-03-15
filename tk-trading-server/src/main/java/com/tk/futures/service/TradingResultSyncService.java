package com.tk.futures.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.tx.common.entity.*;
import com.tx.common.kafka.KafkaTopic;
import com.tx.common.message.AsyncMessageItem;
import com.tx.common.service.PersistenceService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地 trading-result 同步组件。
 * 消费当前分组的 TRADING_RESULT topic，将内存变更直接落库（调用 PersistenceService.flush）。
 */
@Service
public class TradingResultSyncService {

    private static final Logger logger = LoggerFactory.getLogger(TradingResultSyncService.class);

    private static final AtomicInteger threadNumberIndex = new AtomicInteger(0);

    private final String servers;

    private final PersistenceService persistenceService;

    private volatile boolean running = false;

    private ExecutorService executor;

    private int consumerThreadNumber = 1;

    // 简单监控指标
    private final AtomicLong totalRecords = new AtomicLong(0);
    private final AtomicLong totalMessages = new AtomicLong(0);
    private final AtomicLong totalFlushBatches = new AtomicLong(0);
    private final AtomicLong totalErrors = new AtomicLong(0);
    private volatile long lastStatLogTime = System.currentTimeMillis();

    public TradingResultSyncService(@Value("${kafka.servers}") String servers,
                                    PersistenceService persistenceService) {
        this.servers = servers;
        this.persistenceService = persistenceService;
    }

    /**
     * 启动当前 group 的 trading-result 同步。
     */
    public synchronized void start(String groupName) {
        if (running) {
            return;
        }
        String topic = KafkaTopic.TRADING_RESULT + groupName;
        logger.info("start local TradingResultSyncService, topic={}", topic);
        running = true;
        executor = new ThreadPoolExecutor(
                consumerThreadNumber,
                consumerThreadNumber,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                r -> new Thread(r, "trading-result-sync-" + threadNumberIndex.incrementAndGet())
        );
        for (int i = 0; i < consumerThreadNumber; i++) {
            executor.execute(() -> consumeTopic(topic));
        }
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (executor != null) {
            executor.shutdown();
        }
        logger.info("stopped local TradingResultSyncService");
    }

    private void consumeTopic(String topic) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, servers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "trading-result-sync-" + topic);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            Map<Integer, Long> partitionOffsets = new HashMap<>();
            consumer.subscribe(Collections.singletonList(topic), new org.apache.kafka.clients.consumer.ConsumerRebalanceListener() {
                @Override
                public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                    partitionOffsets.clear();
                }

                @Override
                public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                    logger.info("TradingResultSync partitions assigned: {}", partitions);
                }
            });
            while (running) {
                ConsumerRecords<String, String> records;
                try {
                    records = consumer.poll(Duration.ofMillis(200));
                } catch (Exception e) {
                    logger.warn("TradingResultSync poll error", e);
                    continue;
                }
                if (records.isEmpty()) {
                    continue;
                }
                boolean success = true;
                long batchRecordCount = records.count();
                for (ConsumerRecord<String, String> record : records) {
                    try {
                        partitionOffsets.put(record.partition(), record.offset());
                        processRecord(record);
                    } catch (Exception e) {
                        logger.error("TradingResultSync process error, value={}", record.value(), e);
                        success = false;
                        // 回退到上一次成功 offset
                        for (Map.Entry<Integer, Long> entry : partitionOffsets.entrySet()) {
                            consumer.seek(new TopicPartition(topic, entry.getKey()), entry.getValue());
                        }
                        break;
                    }
                }
                if (success) {
                    consumer.commitSync();
                    totalRecords.addAndGet(batchRecordCount);
                } else {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            logger.info("TradingResultSync consumer exit, topic={}", topic);
        }
    }

    private void processRecord(ConsumerRecord<String, String> record) {
        List<AsyncMessageItem> messageItems = new ArrayList<>();
        logger.info("TradingResultSync flush to db: {}", record.value());
        JSONArray array = JSON.parseArray(record.value());
        for (int i = 0; i < array.size(); i++) {
            AsyncMessageItem.Type type = AsyncMessageItem.Type.fromValue(array.getJSONObject(i).getInteger("type"));
            if (type == null) {
                continue;
            }
            JSONArray messages = array.getJSONObject(i).getJSONArray("messages");
            switch (type) {
                case ACCOUNT:
                    for (int j = 0; j < messages.size(); j++) {
                        Account account = messages.getJSONObject(j).to(Account.class);
                        appendMessage(messageItems, type, account);
                    }
                    break;
                case TRANSFER:
                    for (int j = 0; j < messages.size(); j++) {
                        Transfer transfer = messages.getJSONObject(j).to(Transfer.class);
                        appendMessage(messageItems, type, transfer);
                    }
                    break;
                case ORDER:
                    for (int j = 0; j < messages.size(); j++) {
                        Order order = messages.getJSONObject(j).to(Order.class);
                        appendMessage(messageItems, type, order);
                    }
                    break;
                case POSITION:
                    for (int j = 0; j < messages.size(); j++) {
                        Position position = messages.getJSONObject(j).to(Position.class);
                        appendMessage(messageItems, type, position);
                    }
                    break;
                case TRADE_ORDER:
                    for (int j = 0; j < messages.size(); j++) {
                        TradeOrder tradeOrder = messages.getJSONObject(j).to(TradeOrder.class);
                        appendMessage(messageItems, type, tradeOrder);
                    }
                    break;
                default:
            }
        }
        if (!messageItems.isEmpty()) {
            long msgCount = messageItems.stream().mapToLong(i -> i.getMessages().size()).sum();
            long start = System.currentTimeMillis();
            try {
                persistenceService.flush(messageItems);
            } catch (Exception e) {
                totalErrors.incrementAndGet();
                throw e;
            } finally {
                long cost = System.currentTimeMillis() - start;
                totalMessages.addAndGet(msgCount);
                totalFlushBatches.incrementAndGet();
                logStatsIfNeeded(cost, msgCount);
            }
        }
    }

    private void appendMessage(List<AsyncMessageItem> messageItems, AsyncMessageItem.Type type, Object entity) {
        if (!messageItems.isEmpty() && Objects.equals(messageItems.get(messageItems.size() - 1).getType(), type.getValue())) {
            messageItems.get(messageItems.size() - 1).getMessages().add(entity);
        } else {
            messageItems.add(new AsyncMessageItem(type.getValue(), new ArrayList<>(Collections.singletonList(entity))));
        }
    }

    private void logStatsIfNeeded(long lastBatchCostMs, long lastBatchMessages) {
        long now = System.currentTimeMillis();
        // 默认 60s 打印一次汇总
        if (now - lastStatLogTime >= 60_000) {
            lastStatLogTime = now;
            logger.info(
                    "TradingResultSync stats: totalRecords={}, totalMessages={}, totalFlushBatches={}, totalErrors={}, lastBatchMessages={}, lastBatchCostMs={}",
                    totalRecords.get(),
                    totalMessages.get(),
                    totalFlushBatches.get(),
                    totalErrors.get(),
                    lastBatchMessages,
                    lastBatchCostMs
            );
        }
    }
}

