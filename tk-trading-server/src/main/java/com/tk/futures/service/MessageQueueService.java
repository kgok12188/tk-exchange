package com.tk.futures.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.google.common.collect.Lists;
import com.tx.common.kafka.KafkaTopic;
import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collection;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 消息队列服务（状态机架构）。
 * 只消费 REQUEST_MESSAGE，按 uid 划分到不同槽位队列处理，保证同一 uid 请求顺序执行。
 */
@Service
public class MessageQueueService implements ApplicationContextAware {

    private static final Logger logger = LoggerFactory.getLogger(MessageQueueService.class);

    private static final AtomicInteger threadNumberIndex = new AtomicInteger(0);

    private boolean start = false;

    private final int messageQueueThreadNumber;

    private CountDownLatch countDownLatch;

    private ThreadPoolExecutor executor;

    private Properties props;

    private ProcessService processService;
    private final String servers;

    private String groupId;

    public MessageQueueService(@Value("${kafka.servers}") String servers, @Value("${messageQueueThreadNumber:1}") Integer messageQueueThreadNumber) {
        this.servers = servers;
        this.messageQueueThreadNumber = messageQueueThreadNumber;
    }

    public synchronized void toMaster(String groupId) {
        start = true;
        this.groupId = groupId;
        props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, servers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        logger.info("start_consumer_request_message only, groupId={}", groupId);
        countDownLatch = new CountDownLatch(messageQueueThreadNumber);
        executor = new ThreadPoolExecutor(
                messageQueueThreadNumber,
                messageQueueThreadNumber,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(100),
                r -> new Thread(r, "request-" + groupId + "-" + threadNumberIndex.incrementAndGet()));
        for (int i = 0; i < messageQueueThreadNumber; i++) {
            executor.execute(() -> consumeRequestByUidQueue(groupId));
        }
        logger.info("started_consumer_request_message: groupId={}, threads={}", groupId, messageQueueThreadNumber);
    }

    /**
     * 停止消费
     */
    public synchronized void stop() {
        if (start) {
            start = false;
            logger.info("stop_consumer_request_message: {}", groupId);
            try {
                if (countDownLatch != null) {
                    countDownLatch.await(10, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("stop_consumer_request_message interrupted", e);
            }
            if (executor != null) {
                executor.shutdown();
            }
            executor = null;
            countDownLatch = null;
            logger.info("stopped_consumer_request_message: {}", groupId);
        }
    }

    /**
     * 只消费 REQUEST_MESSAGE + groupId，按 uid 落入对应槽位队列处理（状态机：RECEIVED -> QUEUED -> PROCESSING -> COMPLETED/FAILED）。
     */
    private void consumeRequestByUidQueue(String groupId) {
        String topic = KafkaTopic.REQUEST_MESSAGE + groupId;
        try (KafkaConsumer<String, String> kafkaConsumer = new KafkaConsumer<>(props)) {
            kafkaConsumer.subscribe(Lists.newArrayList(topic), new ConsumerRebalanceListener() {
                @Override
                public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                    logger.info("onPartitionsRevoked: topic={}, partitions={}", topic, partitions);
                }

                @Override
                public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                    logger.info("onPartitionsAssigned: topic={}, partitions={}", topic, partitions);
                }
            });
            while (start) {
                ConsumerRecords<String, String> consumerRecords = kafkaConsumer.poll(Duration.ofMillis(100));
                for (ConsumerRecord<String, String> record : consumerRecords) {
                    try {
                        if (!StringUtils.equals(record.topic(), topic)) {
                            continue;
                        }
                        JSONObject request = JSON.parseObject(record.value());
                        Long uid = request.getLong("uid");
                        String reqId = request.getString("reqId");
                        if (uid == null) {
                            logger.warn("request without uid, reqId={}", reqId);
                            continue;
                        }
                        if (logger.isDebugEnabled()) {
                            logger.debug("request RECEIVED, reqId={}, uid={}", reqId, uid);
                        }
                        processService.run(request);
                    } catch (Exception e) {
                        logger.warn("consumerRequest error, record={}", record.value(), e);
                    }
                }
            }
            if (countDownLatch != null) {
                countDownLatch.countDown();
            }
        }
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.processService = applicationContext.getBean(ProcessService.class);
    }
}
