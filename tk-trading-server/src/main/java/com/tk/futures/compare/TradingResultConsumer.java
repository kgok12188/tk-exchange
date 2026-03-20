package com.tk.futures.compare;

import com.google.common.collect.Lists;
import com.tk.protocol.kafka.KafkaTopic;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Properties;

/**
 * 仅消费 trading_result_(分区)，拉取并 commit offset，不做任何业务处理。
 * 用于从节点追踪主节点输出或占位消费。
 */
@Service
public class TradingResultConsumer {

    private static final Logger logger = LoggerFactory.getLogger(TradingResultConsumer.class);

    private final Properties props;
    private final String topic;
    private volatile boolean running = false;
    private Thread consumerThread;

    public TradingResultConsumer(@Value("${kafka.servers}") String servers, @Value("${shard.id}") String shard) {
        props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, servers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "trading-result-" + shard);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        topic = KafkaTopic.TRADING_RESULT + shard;
    }

    @PostConstruct
    public void start() {
        if (running) return;
        running = true;
        consumerThread = new Thread(() -> {
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
                consumer.subscribe(Lists.newArrayList(topic));
                while (running) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
                    // 仅拉取 + commit offset，不处理消息内容
                    if (!records.isEmpty()) {
                        logger.trace("trading_result consumed count={}", records.count());
                    }
                }
            }
        }, "trading-result-consumer");
        consumerThread.start();
        logger.info("TradingResultConsumer started topic={}", topic);
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (consumerThread != null) {
            consumerThread.interrupt();
            try {
                consumerThread.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        logger.info("TradingResultConsumer stopped topic={}", topic);
    }
}
