package com.tk.futures.queue;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.google.common.collect.Lists;
import com.tx.common.kafka.KafkaTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Properties;

/**
 * 消息队列服务（状态机架构）。
 * 只消费 REQUEST_MESSAGE，按 uid 划分到不同槽位队列处理，保证同一 uid 请求顺序执行。
 */
@Service
public class TradingConsumer {

    private static final Logger logger = LoggerFactory.getLogger(TradingConsumer.class);

    private final Properties props;

    @Autowired
    private TradingHandler tradingHandler;

    private volatile boolean running = false;

    private final String topic;

    /**
     * 当前消费的分组名称（shard name），对应 Kafka topic 后缀
     */

    public TradingConsumer(@Value("${kafka.servers}") String servers, @Value("${shard.name}") String shardName) {
        props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, servers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, shardName + "-" + System.currentTimeMillis());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        topic = KafkaTopic.TRADING_MESSAGE + shardName;
    }

    public void start() {
        running = true;
        new Thread(() -> {
            try (KafkaConsumer<String, String> kafkaConsumer = new KafkaConsumer<>(props)) {
                kafkaConsumer.subscribe(Lists.newArrayList(topic));
                while (running) {
                    ConsumerRecords<String, String> consumerRecords = kafkaConsumer.poll(Duration.ofMillis(100));
                    for (ConsumerRecord<String, String> record : consumerRecords) {
                        JSONObject request = JSON.parseObject(record.value());
                        tradingHandler.handleMessage(request, record.offset());
                    }
                }
            }
        }, "poll-message").start();
    }

    /**
     * 停止消费
     */
    public synchronized void stop() {
        running = false;
    }

}
