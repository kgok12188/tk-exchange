package com.tk.dispatch;

import com.alibaba.fastjson2.JSONObject;
import com.tk.protocol.ProtocolSerde;
import com.tk.protocol.dto.MatchResponse;
import com.tk.protocol.dto.TradingSettle;
import com.tx.common.enums.TradingCommand;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 使用原生 Kafka API 订阅 match_result_* 主题，解析 MatchResponse，
 * 按 uid 拆成 TradingSettle 后发往 trading_(shard)。
 * 支持监听新 topic 与重新分区：通过 ConsumerRebalanceListener 在分区分配/回收时打点并记录当前订阅。
 */
@Service
public class MatchResponseDispatchService {

    private static final Logger log = LoggerFactory.getLogger(MatchResponseDispatchService.class);

    private static final String SUBSCRIBE_PATTERN = "match_result_*";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String bootstrapServers;
    private final String groupId;
    private KafkaConsumer<String, String> consumer;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread consumerThread;
    private final AssignmentHolder assignmentHolder = new AssignmentHolder();

    public MatchResponseDispatchService(KafkaTemplate<String, String> kafkaTemplate, @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers, @Value("${spring.kafka.consumer.group-id:message-dispatch}") String groupId) {
        this.kafkaTemplate = kafkaTemplate;
        this.bootstrapServers = bootstrapServers;
        this.groupId = groupId;
    }

    @PostConstruct
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        Properties props = getProperties();
        consumer = new KafkaConsumer<>(props);
        Pattern pattern = Pattern.compile(SUBSCRIBE_PATTERN.replace("*", ".*"));
        consumer.subscribe(pattern, new MatchResultReBalanceListener(assignmentHolder));
        consumerThread = new Thread(this::runLoop, "message-dispatch-consumer");
        consumerThread.start();
        log.info("MatchResponseDispatchService started pattern={}", SUBSCRIBE_PATTERN);
    }

    private Properties getProperties() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return props;
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (consumer != null) {
            consumer.wakeup();
        }
        if (consumerThread != null) {
            try {
                consumerThread.join(5000);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        if (consumer != null) {
            try {
                consumer.close();
            } catch (Exception exception) {
                log.warn("Consumer close", exception);
            }
        }
        log.info("MatchResponseDispatchService stopped");
    }

    /** 当前已分配到的 match_result topic 集合（rebalance 后更新）。 */
    public Set<String> getAssignedTopics() {
        return assignmentHolder.getAssignedTopics();
    }

    /** 当前已分配到的 TopicPartition 集合（rebalance 后更新）。 */
    public Set<TopicPartition> getAssignedPartitions() {
        return assignmentHolder.getAssignedPartitions();
    }

    private void runLoop() {
        while (running.get()) {
            try {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(200));
                for (ConsumerRecord<String, String> record : records) {
                    processMatchResponse(record.value());
                }
            } catch (org.apache.kafka.common.errors.WakeupException wakeupException) {
                break;
            } catch (Exception exception) {
                log.warn("message-dispatch consume error", exception);
            }
        }
    }

    private void processMatchResponse(String payload) {
        MatchResponse response;
        try {
            response = ProtocolSerde.matchResponseFromJson(payload);
        } catch (Exception exception) {
            log.warn("Invalid MatchResponse json: {}", payload, exception);
            return;
        }
        Map<Long, TradingSettle> settles = TradingSettleTransformer.fromMatchResponse(response);
        for (Map.Entry<Long, TradingSettle> settleEntry : settles.entrySet()) {
            Long uid = settleEntry.getKey();
            TradingSettle settle = settleEntry.getValue();
            String topic = "trading_" + settle.getShardId();

            JSONObject message = new JSONObject();
            message.put("uid", uid);
            message.put("command", TradingCommand.MATCH.name());
            message.put("data", settle);
            String json = message.toJSONString();
            kafkaTemplate.send(topic, "", json).whenComplete((result, ex) -> {
                if (ex != null) log.error("Send trading_(shard) error uid={} shard={}", uid, settle.getShardId(), ex);
            });
        }
    }

}
