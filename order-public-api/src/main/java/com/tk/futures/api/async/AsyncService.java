package com.tk.futures.api.async;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.tx.common.kafka.KafkaTopic;
import com.tx.common.message.request.KafkaRequest;
import com.tx.common.service.UserService;
import com.tx.common.vo.AsyncResult;
import com.tx.common.vo.R;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.async.DeferredResult;

import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class AsyncService implements SmartLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(AsyncService.class);

    private static final AtomicInteger threadNumberIndex = new AtomicInteger(0);

    private ThreadPoolExecutor executor;

    private final String servers;

    private final ConcurrentHashMap<String, DeferredResult<R>> deferredResults;

    private KafkaProducer<String, String> kafkaProducer;

    private volatile boolean start = false;

    private final UserService userService;

    public AsyncService(UserService userService, ConcurrentHashMap<String, DeferredResult<R>> deferredResults, @Value("${kafka.servers}") String servers) {
        this.userService = userService;
        this.deferredResults = deferredResults;
        this.servers = servers;
    }


    public DeferredResult<R> send(KafkaRequest kafkaRequest) {
        if (kafkaRequest.getReqId() == null || StringUtils.isEmpty(kafkaRequest.getReqId().trim())) {
            kafkaRequest.setReqId(UUID.randomUUID().toString().replaceAll("-", ""));
        }
        DeferredResult<R> deferredResult = new DeferredResult<>(5000L, R.fail(500, "futures.time_out"));
        deferredResult.onTimeout(() -> deferredResults.remove(kafkaRequest.getReqId()));
        String group = userService.getById(kafkaRequest.getUid()).getGroupName();
        deferredResults.put(kafkaRequest.getReqId(), deferredResult);
        kafkaProducer.send(new ProducerRecord<>(KafkaTopic.REQUEST_MESSAGE + group, String.valueOf(kafkaRequest.getUid()), JSONObject.toJSONString(kafkaRequest)));
        return deferredResult;
    }

    private void startProducer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, servers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");
        props.put(ProducerConfig.ACKS_CONFIG, "all"); // 消息确认机制
        props.put(ProducerConfig.RETRIES_CONFIG, 3);  // 失败重试次数
        kafkaProducer = new KafkaProducer<>(props);
    }


    private void startConsumer() {
        executor = new ThreadPoolExecutor(3, 3, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), r -> new Thread(r, "response-" + threadNumberIndex.incrementAndGet()));
        new Thread(this::consumer, "response-poll").start();
    }

    private void consumer() {
        String groupId = UUID.randomUUID().toString().replaceAll("-", "");
        Properties props = new Properties();
        props.put("bootstrap.servers", servers);
        props.put("group.id", "response-group-" + groupId);
        props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("enable.auto.commit", "true");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList(KafkaTopic.RESPONSE_MESSAGE));
            try {
                while (start) {
                    ConsumerRecords<String, String> consumerRecords = consumer.poll(Duration.ofMillis(500));
                    for (ConsumerRecord<String, String> record : consumerRecords) {
                        executor.execute(() -> {
                            JSONObject jsonObject = JSON.parseObject(record.value());
                            logger.info("message : {},\t{}", record.topic(), record.value());
                            String reqId = jsonObject.getString("reqId");
                            if (!StringUtils.isEmpty(reqId)) {
                                DeferredResult<R> deferredResult = deferredResults.remove(reqId);
                                if (deferredResult != null) {
                                    try {
                                        AsyncResult asyncResult = JSON.parseObject(record.value(), AsyncResult.class);
                                        deferredResult.setResult(asyncResult);
                                    } catch (Exception e) {
                                        logger.error("consumer response error:{}", record.value(), e);
                                    }
                                }
                            }
                        });
                    }
                }
            } catch (Exception e) {
                logger.warn("consumer_error", e);
            }
            logger.info("close_consumer");
        }
    }

    @Override
    public void start() {
        start = true;
        startConsumer();
        startProducer();
    }

    @Override
    public void stop() {
        executor.shutdown();
        start = false;
    }

    @Override
    public boolean isRunning() {
        return start;
    }

}
