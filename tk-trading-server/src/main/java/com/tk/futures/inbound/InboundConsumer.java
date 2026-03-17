package com.tk.futures.inbound;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.google.common.collect.Lists;
import com.tk.protocol.kafka.KafkaTopic;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Properties;

/**
 * 单线程消费 trading_(shard)（KafkaTopic.TRADING_MESSAGE + shardName），
 * 将 JSON {command, uid, data} 解析为 CommandMessage 并交给 CommandRouter。
 */
@Service
public class InboundConsumer {

    private static final Logger logger = LoggerFactory.getLogger(InboundConsumer.class);

    private final Properties props;
    private final String topic;
    private final CommandRouter commandRouter;

    private volatile boolean running = false;
    private Thread consumerThread;

    public InboundConsumer(@Value("${kafka.servers}") String servers,
                           @Value("${shard.id}") String shard,
                           CommandRouter commandRouter) {
        this.commandRouter = commandRouter;
        this.props = new Properties();
        this.props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, servers);
        this.props.put(ConsumerConfig.GROUP_ID_CONFIG, "trading-server-" + shard);
        this.props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        this.props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        this.props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        this.topic = KafkaTopic.TRADING + shard;
    }

    @PostConstruct
    public void start() {
        if (running) {
            return;
        }
        running = true;
        consumerThread = new Thread(() -> {
            try (KafkaConsumer<String, String> kafkaConsumer = new KafkaConsumer<>(props)) {
                kafkaConsumer.subscribe(Lists.newArrayList(topic));
                while (running) {
                    ConsumerRecords<String, String> records = kafkaConsumer.poll(Duration.ofMillis(100));
                    for (ConsumerRecord<String, String> record : records) {
                        handleRecord(record);
                    }
                }
            } catch (Exception e) {
                logger.error("InboundConsumer loop error", e);
            } finally {
                running = false;
            }
        }, "trading-inbound-consumer");
        consumerThread.start();
        logger.info("InboundConsumer started, topic={}", topic);
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (consumerThread != null) {
            consumerThread.interrupt();
        }
        logger.info("InboundConsumer stopped, topic={}", topic);
    }

    private void handleRecord(ConsumerRecord<String, String> record) {
        try {
            JSONObject json = JSON.parseObject(record.value());
            if (json == null) {
                return;
            }
            String command = json.getString("command");
            Long uid = json.getLong("uid");
            JSONObject data = json.getJSONObject("data");
            if (command == null || command.isEmpty()) {
                logger.warn("skip message without command, offset={}", record.offset());
                return;
            }
            CommandMessage message = new CommandMessage(command, uid, data, record.offset());
            commandRouter.route(message);
        } catch (Exception e) {
            logger.error("failed to handle record, offset={}, value={}", record.offset(), record.value(), e);
        }
    }
}

