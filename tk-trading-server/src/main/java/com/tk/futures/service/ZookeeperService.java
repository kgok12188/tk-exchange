package com.tk.futures.service;

import com.tx.common.service.WorkerOrderGroupJvmService;
import com.tx.common.service.WorkerOrderGroupService;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Properties;
import java.util.UUID;

@Service
public class ZookeeperService implements SmartLifecycle {

    private volatile boolean running = false;

    private KafkaProducer<String, String> kafkaProducer;

    private final WorkerOrderGroupJvmService workerOrderGroupJvmService;

    private final Properties kafkaProps = new Properties();

    private final String jvmId;

    public ZookeeperService(WorkerOrderGroupJvmService workerOrderGroupJvmService,
                            @Value("${zookeeper.servers}") String zookeeperUrl, @Value("${kafka.servers}") String kafkaServers,
                            WorkerOrderGroupService workerOrderGroupService) throws UnknownHostException {
        InetAddress addr = InetAddress.getLocalHost();
        jvmId = addr.toString() + ":" + UUID.randomUUID().toString().replaceAll("-", "");
        this.workerOrderGroupJvmService = workerOrderGroupJvmService;
        kafkaProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServers);
        kafkaProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");
        kafkaProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");
        kafkaProps.put(ProducerConfig.ACKS_CONFIG, "all"); // 消息确认机制
        kafkaProps.put(ProducerConfig.RETRIES_CONFIG, 3);  // 失败重试次数
    }

    @Override
    public void start() {
        kafkaProducer = new KafkaProducer<>(kafkaProps);
        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

}
