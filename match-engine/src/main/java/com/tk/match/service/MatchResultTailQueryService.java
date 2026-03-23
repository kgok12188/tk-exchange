package com.tk.match.service;

import com.google.common.collect.Lists;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;


@Service
public class MatchResultTailQueryService {

    private static final Logger log = LoggerFactory.getLogger(MatchResultTailQueryService.class);
    private static final String TOPIC_PREFIX = "match_result_";

    private final String bootstrapServers;

    public MatchResultTailQueryService(@Value("${kafka.servers:localhost:9092}") String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    /**
     * 查询单个币对 match_result_(symbol) 分区 0 最后一条的 orderReqOffset。topic 不存在或分区无消息时返回 0。
     */
    public long queryLastOrderReqOffset(String symbol) {
        if (bootstrapServers == null || symbol == null || symbol.isEmpty()) {
            return 0L;
        }
        Map<String, Long> map = queryLastOrderReqOffset(Collections.singleton(symbol));
        return map.getOrDefault(symbol, 0L);
    }

    /**
     * 批量查询多个币对对应的 match_result_(symbol) 分区 0 最后一条的 orderReqOffset。
     * 返回 symbol -> orderReqOffset；topic 不存在或分区无消息的 symbol 对应 0。
     */
    public Map<String, Long> queryLastOrderReqOffset(Set<String> symbols) {
        Map<String, Long> result = new HashMap<>();
        if (bootstrapServers == null || symbols == null || symbols.isEmpty()) {
            return result;
        }
        for (String symbol : symbols) {
            result.put(symbol, 0L);
        }
        List<TopicPartition> partitions = symbols.stream()
                .filter(symbol -> symbol != null && !symbol.isEmpty())
                .map(symbol -> new TopicPartition(TOPIC_PREFIX + symbol, 0))
                .collect(Collectors.toList());
        if (partitions.isEmpty()) {
            return result;
        }
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "tail-query-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.assign(partitions);
            consumer.seekToEnd(partitions);
            HashMap<String, Long> endMap = new HashMap<>();
            for (TopicPartition tp : partitions) {
                long end = consumer.position(tp);
                Long begin = consumer.beginningOffsets(Lists.newArrayList(tp)).get(tp);
                if (end > begin) {
                    endMap.put(tp.topic(), end - 1);
                    consumer.seek(tp, Math.max(end - 10, begin));
                }
            }
            while (!endMap.isEmpty()) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(10));
                for (ConsumerRecord<String, String> record : records) {
                    Long offset = endMap.get(record.topic());
                    if (offset != null && record.offset() >= offset) {
                        endMap.remove(record.topic());
                    }
                    String symbol = topicToSymbol(record.topic());
                    if (symbol != null && result.containsKey(symbol)) {
                        long orderReqOffset = orderReqOffsetFromRecord(record);
                        result.put(symbol, orderReqOffset > 0 ? orderReqOffset : result.get(symbol));
                    }
                }
            }
            log.info("MatchResultTailQueryService batch symbols={} result={}", symbols, result);
            return result;
        } catch (Exception exception) {
            log.warn("MatchResultTailQueryService batch symbols={} failed: {}", symbols, exception.getMessage());
            return result;
        }
    }

    private static String topicToSymbol(String topic) {
        if (topic == null || !topic.startsWith(TOPIC_PREFIX)) return null;
        return topic.substring(TOPIC_PREFIX.length());
    }

    private static long orderReqOffsetFromRecord(ConsumerRecord<String, String> record) {
        Header offsetHeader = record.headers().lastHeader("orderReqOffset");
        if (offsetHeader == null || offsetHeader.value() == null) return 0L;
        try {
            return Long.parseLong(new String(offsetHeader.value(), StandardCharsets.UTF_8));
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }
}
