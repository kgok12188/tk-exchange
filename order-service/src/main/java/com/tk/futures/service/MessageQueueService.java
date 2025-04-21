package com.tk.futures.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.tx.common.message.match.MatchOrderMessage;
import com.tx.common.entity.Order;
import com.tx.common.entity.TradeOrder;
import com.tx.common.entity.TradePrice;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 消息队列服务
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
        logger.info("start_consumer_async_message_response : {}", groupId);
        countDownLatch = new CountDownLatch(messageQueueThreadNumber + 1);
        // 2. 创建线程池
        executor = new ThreadPoolExecutor(messageQueueThreadNumber + 1, messageQueueThreadNumber + 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(100), r -> new Thread(r, "message-" + groupId + "-" + threadNumberIndex.incrementAndGet()));
        for (int i = 0; i < messageQueueThreadNumber; i++) {
            executor.execute(() -> request(groupId));
        }
        executor.execute(this::matchOrder);
        logger.info("started_consumer_message : groupId = {},\tthread = {}", groupId, messageQueueThreadNumber);
    }

    /**
     * slave 不处理消息队列数据
     */
    public synchronized void stop() {
        if (start) {
            start = false;
            logger.info("stop_consumer_async_message_request : {}", groupId);
            try {
                countDownLatch.await();
            } catch (Exception e) {
                logger.error("stop_consumer_async_message_request_error", e);
            }
            executor.shutdown();
            logger.info("stopped_consumer_async_message_request : {}", groupId);
            executor = null;
            countDownLatch = null;
        }
    }

    /**
     * 处理用户的消息请求
     *
     * @param groupId 用户组id
     */
    private void request(String groupId) {
        // 处理请求 & 订单匹配
        try (KafkaConsumer<String, String> kafkaConsumer = new KafkaConsumer<>(props)) {
            kafkaConsumer.subscribe(Lists.newArrayList(KafkaTopic.REQUEST_MESSAGE + groupId), new ConsumerRebalanceListener() {
                @Override
                public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                    logger.info("onPartitionsRevoked : {}", partitions);
                }

                @Override
                public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                    logger.info("onPartitionsAssigned : {}", partitions);
                }
            });
            while (start) {
                ConsumerRecords<String, String> consumerRecords = kafkaConsumer.poll(Duration.ofMillis(100));
                for (ConsumerRecord<String, String> record : consumerRecords) {
                    try {
                        if (StringUtils.equals(record.topic(), KafkaTopic.REQUEST_MESSAGE + groupId)) {
                            JSONObject request = JSON.parseObject(record.value());
                            processService.run(request);
                        }
                    } catch (Exception e) {
                        logger.info("consumerRequest", e);
                    }
                }
            }
            if (countDownLatch != null) {
                countDownLatch.countDown();
            }
        }
    }


    /**
     * 撮合消息
     */
    private void matchOrder() {
        // 处理请求 & 订单匹配
        try (KafkaConsumer<String, String> kafkaConsumer = new KafkaConsumer<>(props)) {
            kafkaConsumer.subscribe(Lists.newArrayList(KafkaTopic.ORDER_MATCH, KafkaTopic.TRADE_PRICE), new ConsumerRebalanceListener() {
                @Override
                public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                    logger.info("consumerMatchOrder_onPartitionsRevoked : {}", partitions);
                }

                @Override
                public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                    logger.info("consumerMatchOrder_onPartitionsAssigned : {}", partitions);
                }
            });
            while (start) {
                ConsumerRecords<String, String> consumerRecords = null;
                try {
                    consumerRecords = kafkaConsumer.poll(Duration.ofMillis(100));
                } catch (Exception e) {
                    logger.warn("consumerMatchOrder_poll_error", e);
                    continue;
                }
                for (ConsumerRecord<String, String> record : consumerRecords) {
                    try {
                        if (StringUtils.equals(record.topic(), KafkaTopic.ORDER_MATCH)) {
                            MatchOrderMessage matchOrderMessage = JSON.parseObject(record.value(), MatchOrderMessage.class);
                            if (matchOrderMessage.isMatchOrder()) {
                                processService.matchOrder(matchOrderMessage.toJavaObject(TradeOrder.class));
                            } else if (matchOrderMessage.isExceptionOrder()) {
                                processService.exceptionOrder(matchOrderMessage.toJavaObject(Order.class));
                            }
                        } else if (StringUtils.equals(record.topic(), KafkaTopic.TRADE_PRICE)) { // 监听最新价格，检查爆仓
                            TradePrice tradePrice = JSON.parseObject(record.value(), TradePrice.class);
                            processService.doLiq(tradePrice); // 价格变更，检查是否有仓位可以爆仓
                        }
                    } catch (Exception e) {
                        logger.info("matchOrder", e);
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
