package com.tk.match.service;

import com.tx.common.entity.MarketConfig;
import com.tx.common.kafka.KafkaTopic;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.RecordsToDelete;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class KafkaManager {

    private static final Logger logger = LoggerFactory.getLogger(KafkaManager.class);

    private final String servers;

    public KafkaManager(@Value("${kafka.servers}") String servers) {
        this.servers = servers;
    }

    public long[] getOffset(String topic, int partition) {
        Properties props = new Properties();
        props.put("bootstrap.servers", servers);
        props.put("group.id", "response-group-" + UUID.randomUUID().toString().replaceAll("-", ""));
        props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        // 2. 创建 KafkaConsumer 实例（无需订阅主题）
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            TopicPartition topicPartition = new TopicPartition(topic, partition);
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(Collections.singletonList(topicPartition));
            Map<TopicPartition, Long> beginOffsets = consumer.beginningOffsets(Collections.singletonList(topicPartition));
            Long latestOffset = endOffsets.get(topicPartition);
            Long beginOffset = beginOffsets.get(topicPartition);
            logger.info("Latest Offset for partition {},\t{},\t{},\t{}", topic, partition, beginOffset, latestOffset);
            return new long[]{beginOffset, latestOffset};
        } catch (Exception e) {
            logger.warn("getOffset {},\t{}", topic, partition, e);
            return new long[]{0L, 0L};
        }
    }

    public void deleteBeginOffset(MarketConfig marketConfig, long offset) {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, servers);
        try (AdminClient adminClient = AdminClient.create(props)) {
            TopicPartition topicPartition = new TopicPartition(KafkaTopic.MATCH_RECOVER + marketConfig.getSymbol(), 0);
            HashMap<TopicPartition, RecordsToDelete> deleteHashMap = new HashMap<>();
            deleteHashMap.put(topicPartition, RecordsToDelete.beforeOffset(offset));
            adminClient.deleteRecords(deleteHashMap);
        }
    }

}
