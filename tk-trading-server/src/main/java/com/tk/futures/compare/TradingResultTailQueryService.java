package com.tk.futures.compare;

import com.tk.protocol.kafka.KafkaTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Properties;

/**
 * 查询 trading_result_(shard) 每个 partition 的最后一条记录中 header：`offset` 值。
 * <p>
 * 用于切主时：对 slave 本地 ChronicleQueue 的 replay 做 minOffsetExclusive 过滤，避免重复写 Kafka。
 * <p>
 * 若最后一条消息没有 `offset` header，返回 -1（保守：让 slave 全量补发，避免漏数据）。
 */
@Service
public class TradingResultTailQueryService {

    private static final Logger log = LoggerFactory.getLogger(TradingResultTailQueryService.class);

    private final String bootstrapServers;
    private final String topic;

    public TradingResultTailQueryService(@Value("${kafka.servers:localhost:9092}") String bootstrapServers, @Value("${shard.id}") String shard) {
        this.bootstrapServers = bootstrapServers;
        this.topic = KafkaTopic.TRADING_RESULT + shard;
    }

    /**
     * @return header `offset` 的值；如果 topic/partition 没数据或 header 缺失，返回 -1
     */
    public long queryLastOffset(int partition) {
        if (partition < 0) return -1L;
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "trading-result-tail-query-" + System.currentTimeMillis());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        TopicPartition tp = new TopicPartition(topic, partition);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.assign(Collections.singletonList(tp));
            consumer.seekToEnd(Collections.singletonList(tp));
            long end = consumer.position(tp); // next offset
            if (end <= 0) return -1L;
            consumer.seek(tp, Math.max(end - 10, 0));
            var records = consumer.poll(java.time.Duration.ofMillis(100));
            long offset = -1;
            for (var record : records) {
                Header h = record.headers().lastHeader("offset");
                if (h != null && h.value() != null) {
                    try {
                        long v = Long.parseLong(new String(h.value(), StandardCharsets.UTF_8));
                        offset = Math.max(offset, v);
                    } catch (Exception e) {
                        log.warn("0_queryLastOffset failed, topic={}, partition={}, err={}", topic, partition, e.getMessage());
                    }
                }
            }
            return offset;
        }
    }
}

