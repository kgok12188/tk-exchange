package com.tk.futures.service;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.tk.futures.generator.OrderIdGenerator;
import com.tx.common.entity.WorkerOrderGroup;
import com.tx.common.entity.WorkerOrderGroupJvm;
import com.tx.common.service.WorkerOrderGroupJvmService;
import com.tx.common.service.WorkerOrderGroupService;
import lombok.SneakyThrows;
import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class ZookeeperService implements SmartLifecycle {

    private static final ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(1);

    private static final Logger logger = LoggerFactory.getLogger(ZookeeperService.class);

    private final ProcessService processService;

    private final WorkerOrderGroupService workerOrderGroupService;

    private volatile boolean running = false;

    private KafkaProducer<String, String> kafkaProducer;

    private final WorkerOrderGroupJvmService workerOrderGroupJvmService;

    private final Properties kafkaProps = new Properties();

    private final String jvmId;

    private String groupName = "";
    private final OrderIdGenerator orderIdGenerator;

    public ZookeeperService(WorkerOrderGroupJvmService workerOrderGroupJvmService,
                            ProcessService processService,
                            @Value("${zookeeper.servers}") String zookeeperUrl, @Value("${kafka.servers}") String kafkaServers,
                            OrderIdGenerator orderIdGenerator,
                            WorkerOrderGroupService workerOrderGroupService) throws UnknownHostException {
        this.orderIdGenerator = orderIdGenerator;
        InetAddress addr = InetAddress.getLocalHost();
        jvmId = addr.toString() + ":" + UUID.randomUUID().toString().replaceAll("-", "");
        this.workerOrderGroupJvmService = workerOrderGroupJvmService;
        this.processService = processService;
        kafkaProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServers);
        kafkaProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");
        kafkaProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");
        kafkaProps.put(ProducerConfig.ACKS_CONFIG, "all"); // 消息确认机制
        kafkaProps.put(ProducerConfig.RETRIES_CONFIG, 3);  // 失败重试次数
        this.workerOrderGroupService = workerOrderGroupService;
    }


    private String getGroupName() {
        CountDownLatch countDownLatch = new CountDownLatch(1);
        Thread thread;
        thread = new Thread(() -> {
            do {
                removeLostHost();
                List<WorkerOrderGroupJvm> list = workerOrderGroupJvmService.lambdaQuery().eq(WorkerOrderGroupJvm::getJvmId, "").list();
                if (!CollectionUtils.isEmpty(list)) {
                    for (WorkerOrderGroupJvm workerOrderGroupJvm : list) {
                        if (workerOrderGroupJvmService.tryGet(workerOrderGroupJvm, jvmId)) {
                            groupName = workerOrderGroupJvm.getGroupName();
                            countDownLatch.countDown();
                            return;
                        }
                    }
                }
                try {
                    Thread.sleep(10000);
                } catch (Exception e) {
                    //
                }
            } while (!Thread.interrupted());
        });
        thread.setDaemon(true);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            countDownLatch.countDown();
            thread.interrupt();
        }));
        thread.start();
        try {
            countDownLatch.await();
        } catch (Exception e) {
            // todo
        }
        return groupName;
    }

    private void removeLostHost() {
        LambdaQueryChainWrapper<WorkerOrderGroupJvm> lambdaQueryChainWrapper = workerOrderGroupJvmService.lambdaQuery()
                .le(WorkerOrderGroupJvm::getLastUpdateTime, new Date(System.currentTimeMillis() - (600_000)))
                .ne(WorkerOrderGroupJvm::getJvmId, "");
        List<WorkerOrderGroupJvm> list = lambdaQueryChainWrapper.list();
        for (WorkerOrderGroupJvm workerOrderGroupJvm : list) {
            workerOrderGroupJvmService.updateLost(workerOrderGroupJvm);
        }
    }

    @SneakyThrows
    @Override
    public void start() {
        kafkaProducer = new KafkaProducer<>(kafkaProps);
        running = true;
        final String group = getGroupName();


        if (StringUtils.isBlank(group)) {
            logger.info("退出程序");
            stop();
            System.exit(-1);
            return;
        }

        WorkerOrderGroup workerOrderGroup = workerOrderGroupService.lambdaQuery().eq(WorkerOrderGroup::getGroupName, group).last("limit 1").one();
        if (workerOrderGroup == null) {
            logger.info("退出程序 group = {} 不存在 ", group);
            stop();
            System.exit(-1);
            return;
        }

        orderIdGenerator.setSnowflakeIdWorker(workerOrderGroup.getId());

        logger.info("start_group : {}, machineId = {}", group, workerOrderGroup.getId());

        scheduledExecutorService.scheduleAtFixedRate(() -> {
            removeLostHost();
            workerOrderGroupJvmService.updateLastTime(jvmId);
        }, 120, 120, TimeUnit.SECONDS);

        processService.start(group, kafkaProducer);
    }

    @Override
    public void stop() {
        try {
            processService.stop();
        } catch (Exception e) {
            logger.warn("commandService", e);
        }
        logger.info("stop_kafka");
        if (kafkaProducer != null) {
            try {
                kafkaProducer.close();
            } catch (Exception e) {
                logger.warn("close_kafka", e);
            }
        }
        scheduledExecutorService.shutdown();
        workerOrderGroupJvmService.removeJvmId(jvmId);
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

}
