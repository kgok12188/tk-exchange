package com.tk.flush;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.google.common.collect.Lists;
import com.tx.common.entity.*;
import com.tx.common.kafka.KafkaTopic;
import com.tx.common.message.AsyncMessageItem;
import com.tx.common.service.PersistenceService;
import com.tx.common.service.WorkerOrderGroupService;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * @author louis
 * 1、将内存中变更的数据同步到数据库
 * 2、只会保留txid 最大的数据
 */
@Service
public class DataSynchronizationService implements SmartLifecycle {

    private static final AtomicInteger threadNumberIndex = new AtomicInteger(0);

    private static final Logger logger = LoggerFactory.getLogger(DataSynchronizationService.class);

    private final String servers;

    private ExecutorService executor;

    private volatile boolean start;

    private int consumerThreadNumber;

    private static final String consumerGroupId = "flush";

    @Autowired
    private PersistenceService persistenceService;
    @Autowired
    private WorkerOrderGroupService workerOrderGroupService;

    public DataSynchronizationService(@Value("${kafka.servers}") String servers) {
        this.servers = servers;
    }

    public void start() {
        List<String> topicList = workerOrderGroupService.lambdaQuery().list().stream().map(item -> KafkaTopic.SYNC_TO_DB + item.getGroupName()).collect(Collectors.toList());
        logger.info("start_consumer : {}", consumerGroupId);
        start = true;
        // 1. 获取主题分区数
        consumerThreadNumber = 2;
        // 2. 创建线程池（线程数=分区数）
        executor = new ThreadPoolExecutor(consumerThreadNumber, consumerThreadNumber, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), r -> new Thread(r, consumerGroupId + "-" + threadNumberIndex.incrementAndGet()));
        // 3. 为每个分区创建消费者
        for (int partition = 0; partition < consumerThreadNumber; partition++) {
            executor.execute(() -> this.createPartitionConsumer(topicList));
        }
        logger.info("started_consumer : groupId = {},\tpartitionCount = {}", consumerGroupId, consumerThreadNumber);
    }

    public void stop() {
        if (start) {
            start = false;
            if (executor != null) {
                executor.shutdown();
            }
            logger.info("stop_sync_to_db_consumer : {},\t{}", "flush", consumerThreadNumber);
        }
    }

    @Override
    public boolean isRunning() {
        return start;
    }

    private void createPartitionConsumer(List<String> groupList) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, servers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "flush");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            HashMap<String, Map<Integer, Long>> topicOffsetPartitions = new HashMap<>();
            for (String topic : groupList) {
                topicOffsetPartitions.put(topic, new HashMap<>());
            }
            consumer.subscribe(groupList, new ConsumerRebalanceListener() {
                @Override
                public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                    for (TopicPartition partition : partitions) {
                        Map<Integer, Long> offsetMap = topicOffsetPartitions.get(partition.topic());
                        offsetMap.remove(partition.partition());
                    }
                }

                @Override
                public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                    logger.info("onPartitionsAssigned : {}", partitions.stream().map(p -> (p.topic() + "-" + p.partition())).collect(Collectors.toList()));
                }

            });
            while (start) {
                ConsumerRecords<String, String> records = null;
                try {
                    records = consumer.poll(Duration.ofMillis(200));
                } catch (Exception e) {
                    logger.warn("poll", e);
                }
                if (records == null) {
                    continue;
                }
                boolean suc = false;
                for (ConsumerRecord<String, String> record : records) {
                    try {
                        Map<Integer, Long> offsetMap = topicOffsetPartitions.get(record.topic());
                        offsetMap.put(record.partition(), record.offset());
                        processRecord(record); // 业务处理
                        suc = true;
                    } catch (Exception e) {
                        logger.error("ConsumerRecords : {}", record.value(), e);
                        suc = false;
                        for (Map.Entry<String, Map<Integer, Long>> topicOffsetPartition : topicOffsetPartitions.entrySet()) {
                            String topic = topicOffsetPartition.getKey();
                            for (Map.Entry<Integer, Long> kv : topicOffsetPartition.getValue().entrySet()) {
                                consumer.seek(new TopicPartition(topic, kv.getKey()), kv.getValue());
                            }
                        }
                        break;
                    }
                }
                if (suc) {
                    consumer.commitSync(); // 手动提交偏移量
                } else {
                    try {
                        Thread.sleep(1000); // 消费失败，间隔1s后重试
                    } catch (Exception ex) {
                        // todo
                    }
                }
            }
            logger.info("stop_consumer : {}", consumerGroupId);
        }
    }

    private void processRecord(ConsumerRecord<String, String> record) {
        List<AsyncMessageItem> messageItems = new ArrayList<>();
        logger.info("sync_to_db : {}", record.value());
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
                        Account account = messages.getJSONObject(j).toJavaObject(Account.class);
                        if (!messageItems.isEmpty() && Objects.equals(messageItems.get(messageItems.size() - 1).getType(), type.getValue())) {
                            messageItems.get(messageItems.size() - 1).getMessages().add(account);
                        } else {
                            messageItems.add(new AsyncMessageItem(type.getValue(), Lists.newArrayList(account)));
                        }
                    }
                    break;
                case TRANSFER:
                    for (int j = 0; j < messages.size(); j++) {
                        Transfer transfer = messages.getJSONObject(j).toJavaObject(Transfer.class);
                        if (!messageItems.isEmpty() && Objects.equals(messageItems.get(messageItems.size() - 1).getType(), type.getValue())) {
                            messageItems.get(messageItems.size() - 1).getMessages().add(transfer);
                        } else {
                            messageItems.add(new AsyncMessageItem(type.getValue(), Lists.newArrayList(transfer)));
                        }
                    }
                    break;
                case ORDER:
                    for (int j = 0; j < messages.size(); j++) {
                        Order order = messages.getJSONObject(j).toJavaObject(Order.class);
                        if (!messageItems.isEmpty() && Objects.equals(messageItems.get(messageItems.size() - 1).getType(), type.getValue())) {
                            messageItems.get(messageItems.size() - 1).getMessages().add(order);
                        } else {
                            messageItems.add(new AsyncMessageItem(type.getValue(), Lists.newArrayList(order)));
                        }
                    }
                    break;
                case POSITION:
                    for (int j = 0; j < messages.size(); j++) {
                        Position position = messages.getJSONObject(j).toJavaObject(Position.class);
                        if (!messageItems.isEmpty() && Objects.equals(messageItems.get(messageItems.size() - 1).getType(), type.getValue())) {
                            messageItems.get(messageItems.size() - 1).getMessages().add(position);
                        } else {
                            messageItems.add(new AsyncMessageItem(type.getValue(), Lists.newArrayList(position)));
                        }
                    }
                    break;
                case TRADE_ORDER:
                    for (int j = 0; j < messages.size(); j++) {
                        TradeOrder tradeOrder = messages.getJSONObject(j).toJavaObject(TradeOrder.class);
                        if (!messageItems.isEmpty() && Objects.equals(messageItems.get(messageItems.size() - 1).getType(), type.getValue())) {
                            messageItems.get(messageItems.size() - 1).getMessages().add(tradeOrder);
                        } else {
                            messageItems.add(new AsyncMessageItem(type.getValue(), Lists.newArrayList(tradeOrder)));
                        }
                    }
                    break;
                default:
            }
            persistenceService.flush(messageItems);
        }
    }

}
