package com.tk.futures.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.tk.futures.generator.TxIdGenerator;
import com.tk.futures.generator.TxIdGeneratorImpl;
import com.tk.futures.model.AsyncMessageItems;
import com.tk.futures.model.DataContext;
import com.tk.futures.model.UserData;
import com.tk.futures.process.BaseProcess;
import com.tk.futures.process.OrderProcess;
import com.tx.common.entity.*;
import com.tx.common.kafka.KafkaTopic;
import com.tx.common.message.AsyncMessageItem;
import com.tx.common.service.*;
import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.redisson.api.LocalCachedMapOptions;
import org.redisson.api.RLocalCachedMap;
import org.redisson.api.RedissonClient;
import org.redisson.codec.JsonJacksonCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ProcessService implements ApplicationContextAware {

    private final Map<Integer, DataContext> globalDataContext = new ConcurrentHashMap<>();
    private final Map<Integer, TxIdGenerator> txIdGeneratorMap = new ConcurrentHashMap<>();
    public final static Map<Integer, TradePrice> tradePriceMap = new ConcurrentHashMap<>(2048);
    private static final Logger logger = LoggerFactory.getLogger(ProcessService.class);
    private final int THREAD_NUM = Runtime.getRuntime().availableProcessors();
    private final RedissonClient redissonClient;
    private final MarketConfigService marketConfigService;
    private OrderProcess orderProcess;
    private AccountService accountService;
    private UserService userService;
    private ArrayList<LinkedBlockingQueue<Runnable>> taskArray;
    private UserDataService userDataService;
    private final String servers;
    private MessageQueueService messageQueueService;
    private ApplicationContext applicationContext;
    private CountDownLatch countDownLatch;
    private KafkaProducer<String, String> kafkaProducer;
    private TradeOrderService tradeOrderService;
    private PositionService positionService;
    private OrderService orderService;
    private String groupId;

    private final RLocalCachedMap<String, MarketConfig> marketConfigs;

    private final AtomicBoolean atomicLoadUserData = new AtomicBoolean(false);

    private boolean startProcess = false;
    private boolean isSlave = true;

    private CountDownLatch consumerMasterCountDownLatch;


    private void loadUserData(String groupId) {
        if (atomicLoadUserData.compareAndSet(false, true)) {
            // 测试表字段保持一致
            userService.lambdaQuery().last("limit 1");
            tradeOrderService.lambdaQuery().last("limit 1");
            positionService.lambdaQuery().last("limit 1");
            orderService.lambdaQuery().last("limit 1");
            accountService.lambdaQuery().last("limit 1");
            long start = 0;
            int count = 0;
            do {
                List<User> users = userService.lambdaQuery().eq(User::getGroupName, groupId).gt(User::getId, start).orderByAsc(User::getId).last("limit 100").list();
                if (CollectionUtils.isEmpty(users)) {
                    break;
                }
                count += users.size();
                for (User user : users) {
                    start = Math.max(start, user.getId());
                    UserData userData = userDataService.load(user.getId());
                    DataContext dataContext = globalDataContext.get(userSlot(user.getId()));
                    dataContext.put(user.getId(), userData);
                }
            } while (true);
            logger.info("loadUserData = {}", count);
        }
    }

    public ProcessService(@Value("${kafka.servers}") String kafkaServers, RedissonClient redissonClient, MarketConfigService marketConfigService) {
        this.servers = kafkaServers;
        this.redissonClient = redissonClient;
        LocalCachedMapOptions<String, MarketConfig> options = LocalCachedMapOptions.defaults();
        marketConfigs = redissonClient.getLocalCachedMap("market_config_redis_and_local", new JsonJacksonCodec(), options);
        this.marketConfigService = marketConfigService;
    }

    public synchronized void toMaster(String groupId, KafkaProducer<String, String> kafkaProducer) {
        logger.info("toMaster : isSlave = {},\t{}", isSlave, groupId);
        if (isSlave) {
            isSlave = false;
            if (consumerMasterCountDownLatch != null) {
                try {
                    consumerMasterCountDownLatch.await();
                } catch (Exception e) {
                    logger.info("toMaster : " + groupId, e);
                }
            }
            consumerMasterCountDownLatch = null;
            this.groupId = groupId;
            startProcess(groupId, kafkaProducer);
            loadUserData(groupId);
            messageQueueService.toMaster(groupId);
        }
    }

    public synchronized void toSlave(String groupId) {
        logger.info("toSlave : {}", groupId);
        startProcess(groupId, kafkaProducer);
        loadUserData(groupId);
        messageQueueService.stop(); // 停止异步消息处理
        isSlave = true;
        if (consumerMasterCountDownLatch == null) {
            new Thread(() -> syncMasterMemoryData(groupId), "consumerMaster").start();
        }
    }

    /**
     * 同步master内存数据
     *
     * @param groupId 分组名称
     */
    private void syncMasterMemoryData(String groupId) {
        consumerMasterCountDownLatch = new CountDownLatch(1);
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, servers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        // 处理请求
        try (KafkaConsumer<String, String> kafkaConsumer = new KafkaConsumer<>(props)) {
            kafkaConsumer.subscribe(Collections.singletonList(KafkaTopic.SYNC_TO_DB));
            ConsumerRecords<String, String> consumerRecords;
            long lastFreshTime = 0;
            do {
                consumerRecords = kafkaConsumer.poll(Duration.ofMillis(200));
                for (ConsumerRecord<String, String> record : consumerRecords) {
                    processRecord(record);
                    lastFreshTime = System.currentTimeMillis();
                }
            } while (isSlave || ((System.currentTimeMillis()) - lastFreshTime <= 1500)); // 消费master数据，没有数据 超过1.5秒
        } finally {
            consumerMasterCountDownLatch.countDown();
            consumerMasterCountDownLatch = null;
        }
    }

    private void processRecord(ConsumerRecord<String, String> record) {
        try {
            JSONArray array = JSON.parseArray(record.value());
            for (int i = 0; i < array.size(); i++) {
                try {
                    Integer type = array.getJSONObject(i).getInteger("type");
                    AsyncMessageItem.Type t = AsyncMessageItem.Type.fromValue(type);
                    if (t == null) {
                        continue;
                    }
                    JSONArray messages = array.getJSONObject(i).getJSONArray("messages");
                    switch (t) { // 0 account 1 transfer 2 order 3 position
                        case ACCOUNT:
                            for (int j = 0; j < messages.size(); j++) {
                                Account account = messages.getJSONObject(j).toJavaObject(Account.class);
                                Map<Long, UserData> map = globalDataContext.get(userSlot(account.getUid()));
                                UserData userData = map.get(account.getUid());
                                userData = userData == null ? userDataService.load(account.getUid()) : userData;
                                if (userData != null) {
                                    userData.mergerAccount(account);
                                }
                            }
                            break;
                        case ORDER:
                            for (int j = 0; j < messages.size(); j++) {
                                Order order = messages.getJSONObject(j).toJavaObject(Order.class);
                                Map<Long, UserData> map = globalDataContext.get(userSlot(order.getUid()));
                                UserData userData = map.get(order.getUid());
                                userData = userData == null ? userDataService.load(order.getUid()) : userData;
                                if (userData != null) {
                                    userData.mergerOrder(order);
                                }
                            }
                            break;
                        case POSITION:
                            for (int j = 0; j < messages.size(); j++) {
                                Position position = messages.getJSONObject(j).toJavaObject(Position.class);
                                Map<Long, UserData> map = globalDataContext.get(userSlot(position.getUid()));
                                UserData userData = map.get(position.getUid());
                                userData = userData == null ? userDataService.load(position.getUid()) : userData;
                                if (userData != null) {
                                    userData.mergerPosition(position);
                                }
                            }
                            break;
                        default:
                    }
                } catch (Exception e) {
                    logger.warn("数据解析错误_0 : {},\t{}", record.value(), i);
                }
            }
        } catch (Exception e) {
            logger.warn("数据解析错误_1 ： {}", record.value());
        }
    }

    /**
     * 开启本地线程
     *
     * @param groupId       分组id
     * @param kafkaProducer kakfa
     */
    private void startProcess(String groupId, KafkaProducer<String, String> kafkaProducer) {
        if (!startProcess) {
            startProcess = true;
            logger.info("startProcess : {}", groupId);
            this.kafkaProducer = kafkaProducer;
            countDownLatch = new CountDownLatch(THREAD_NUM);
            taskArray = new ArrayList<>(THREAD_NUM);
            for (int i = 0; i < THREAD_NUM; i++) {
                taskArray.add(new LinkedBlockingQueue<>());
            }
            for (int i = 0; i < taskArray.size(); i++) {
                LinkedBlockingQueue<Runnable> queue = taskArray.get(i);
                globalDataContext.put(i, new DataContext());
                txIdGeneratorMap.put(i, new TxIdGeneratorImpl());
                new Thread(() -> {
                    do {
                        try {
                            Runnable task = queue.poll(100, TimeUnit.MILLISECONDS);
                            if (task != null) {
                                task.run();
                            }
                        } catch (Exception e) {
                            logger.warn("stop", e);
                        }
                    } while (startProcess || !queue.isEmpty());
                    countDownLatch.countDown();
                }, "processor-" + i).start();
            }
        }
    }

    public void stop() throws InterruptedException {
        messageQueueService.stop();
        startProcess = false;
        if (countDownLatch != null) {
            countDownLatch.await();
        }
        logger.info("stopped");
    }

    public void run(JSONObject request) {
        String methodName = request.getString("method");
        Long uid = request.getLong("uid");
        String reqId = request.getString("reqId");
        BaseProcess.ExecMethod execMethod = BaseProcess.getExecMethod(methodName);
        if (StringUtils.isBlank(reqId) || execMethod == null) {
            logger.warn("method_not_found : {}", methodName);
            return;
        }
        try {
            int userSlot = userSlot(uid);
            taskArray.get(userSlot).add(() -> {
                try {
                    DataContext dataContext = globalDataContext.get(userSlot);
                    TxIdGenerator txIdGenerator = txIdGeneratorMap.get(userSlot);
                    BaseProcess.setContext(new BaseProcess.Context(dataContext, reqId, applicationContext, txIdGenerator, kafkaProducer, userDataService, marketConfigs));
                    UserData userData = dataContext.get(uid);
                    if (userData == null) {
                        User user = userService.getById(uid);
                        if (!StringUtils.equals(user.getGroupName(), groupId)) {
                            logger.warn("处理请求失败 : {}", uid);
                            return;
                        }
                        userData = userDataService.load(uid);
                        if (userData == null) {
                            logger.warn("userData is null");
                            return;
                        }
                        logger.info("lazy_load_user_data : {}", uid);
                        dataContext.put(uid, userData);
                    }
                    AsyncMessageItems ret;
                    if (execMethod.getParamsClass() != null) {
                        ret = (AsyncMessageItems) execMethod.getMethod().invoke(execMethod.getProcess(), userData, request.getJSONObject("params").toJavaObject(execMethod.getParamsClass()));
                    } else {
                        ret = (AsyncMessageItems) execMethod.getMethod().invoke(execMethod.getProcess(), userData);
                    }
                    userDataService.sendToMq(kafkaProducer, uid, ret);
                } catch (Exception e) {
                    logger.error("run_command error", e);
                } finally {
                    BaseProcess.removeDataContext();
                }
            });
        } catch (IllegalStateException e) {
            if (StringUtils.equals("Queue full", e.getMessage())) {
                logger.warn("Queue full : {}", uid);
            }
        }
    }

    private int userSlot(long uid) {
        return Math.abs((int) (Long.reverseBytes(uid) % THREAD_NUM));
    }

    public void sendClose() {
        logger.info("send_close_message : {}", groupId);
        for (int i = 0; i < taskArray.size(); i++) {
            taskArray.get(i).add(() -> {
                ProducerRecord<String, String> record = new ProducerRecord<>(KafkaTopic.SYNC_TO_DB, JSON.toJSONString(Lists.newArrayList(new AsyncMessageItem(AsyncMessageItem.Type.CLOSE.getValue(), Lists.newArrayList(taskArray.size())))));
                kafkaProducer.send(record, (metadata, exception) -> {
                    if (exception != null) {
                        logger.error("send_close_message_error", exception);
                    } else {
                        logger.info("send_close_message_success");
                    }
                });
            });
        }
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
        this.userService = applicationContext.getBean(UserService.class);
        this.messageQueueService = applicationContext.getBean(MessageQueueService.class);
        this.userDataService = applicationContext.getBean(UserDataService.class);
        this.tradeOrderService = applicationContext.getBean(TradeOrderService.class);
        this.positionService = applicationContext.getBean(PositionService.class);
        this.orderService = applicationContext.getBean(OrderService.class);
        this.orderProcess = applicationContext.getBean(OrderProcess.class);
        this.accountService = applicationContext.getBean(AccountService.class);
    }

    public void matchOrder(List<TradeOrder> matchOrders) throws InterruptedException {
        if (CollectionUtils.isEmpty(matchOrders)) {
            return;
        }
        for (TradeOrder matchOrder : matchOrders) {
            Long uid = matchOrder.getUid();
            int userSlot = userSlot(matchOrder.getUid());
            DataContext dataContext = globalDataContext.get(userSlot);
            UserData userData = dataContext.get(uid);
            if (userData == null) {
                return;
            }
            taskArray.get(userSlot).put(() -> {
                BaseProcess.setContext(new BaseProcess.Context(dataContext, null, applicationContext, txIdGeneratorMap.get(userSlot), kafkaProducer, userDataService, marketConfigs));
                try {
                    AsyncMessageItems asyncMessageItems = orderProcess.marchOrder(userData, matchOrder);
                    userDataService.sendToMq(kafkaProducer, uid, asyncMessageItems);
                } catch (Exception e) {
                    logger.error("marchOrder {}", matchOrder.getOrderId(), e);
                } finally {
                    BaseProcess.removeDataContext();
                }
            });
        }
    }

    public void exceptionOrder(List<Order> orders) throws InterruptedException {
        if (CollectionUtils.isEmpty(orders)) {
            return;
        }
        for (Order cancelOrder : orders) {
            Long uid = cancelOrder.getUid();
            int userSlot = userSlot(cancelOrder.getUid());
            DataContext dataContext = globalDataContext.get(userSlot);
            UserData userData = dataContext.get(uid);
            if (userData == null) {
                return;
            }
            taskArray.get(userSlot).put(() -> {
                BaseProcess.setContext(new BaseProcess.Context(dataContext, null, applicationContext, txIdGeneratorMap.get(userSlot), kafkaProducer, userDataService, marketConfigs));
                try {
                    AsyncMessageItems asyncMessageItems = orderProcess.exceptionOrder(userData, cancelOrder);
                    userDataService.sendToMq(kafkaProducer, uid, asyncMessageItems);
                } catch (Exception e) {
                    logger.error("cancelOrder {},\t{}", cancelOrder.getId(), cancelOrder.getUid(), e);
                } finally {
                    BaseProcess.removeDataContext();
                }
            });
        }
    }

    public void doLiq(TradePrice tradePrice) {
        if (tradePrice.getTime() == null || tradePrice.getTime() < (System.currentTimeMillis() - 1000)) {
            return;
        }
        BigDecimal newPrice = tradePrice.getPrice();
        TradePrice oldPrice = tradePriceMap.get(tradePrice.getMarketId());
        if (newPrice != null && oldPrice != null && newPrice.compareTo(oldPrice.getPrice()) == 0) {
            return;
        }
        tradePriceMap.put(tradePrice.getMarketId(), tradePrice);
        Map<Integer, TradePrice> prices = new HashMap<>(tradePriceMap);
        for (int i = 0; i < taskArray.size(); i++) {
            DataContext dataContext = globalDataContext.get(i);
            int index = i;
            taskArray.get(i).add(() -> {
                BaseProcess.setContext(new BaseProcess.Context(dataContext, null, applicationContext, txIdGeneratorMap.get(index), kafkaProducer, userDataService, marketConfigs));
                try {
                    orderProcess.liquidation(dataContext, prices, liquidation -> userDataService.sendToMq(kafkaProducer, tradePrice.getMarketId(), liquidation));
                } finally {
                    BaseProcess.removeDataContext();
                }
            });
        }
    }

    @PostConstruct
    public void loadCache() {
        int size = marketConfigs.size();
        if (size == 0) {
            long start = 0;
            do {
                List<MarketConfig> list = marketConfigService.lambdaQuery().gt(MarketConfig::getId, start).orderByAsc(MarketConfig::getId).last("limit 100").list();
                if (CollectionUtils.isEmpty(list)) {
                    break;
                }
                for (MarketConfig marketConfig : list) {
                    start = marketConfig.getId();
                    marketConfigs.put(String.valueOf(marketConfig.getId()), marketConfig);
                }
            } while (true);
        }
    }

}
