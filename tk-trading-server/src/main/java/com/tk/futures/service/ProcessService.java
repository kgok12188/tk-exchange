package com.tk.futures.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.tk.futures.generator.TxIdGenerator;
import com.tk.futures.generator.TxIdGeneratorImpl;
import com.tk.futures.model.AsyncMessageItems;
import com.tk.futures.model.DataContext;
import com.tk.futures.model.MarketCachedMapOptions;
import com.tk.futures.model.UserData;
import com.tk.futures.process.BaseProcess;
import com.tk.futures.process.OrderProcess;
import com.tk.futures.statemachine.RequestState;
import com.tx.common.entity.*;
import com.tx.common.message.AsyncMessageItem;
import com.tx.common.service.*;
import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.redisson.api.RLocalCachedMap;
import org.redisson.api.RedissonClient;
import org.redisson.codec.TypedJsonJacksonCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 请求处理服务。与 MessageQueueService 配合：仅消费 REQUEST_MESSAGE，按 uid 槽位队列顺序处理。
 * 注意：ORDER_MATCH、TRADE_PRICE 已不在本服务消费；matchOrder/exceptionOrder/doLiq 保留供其他调用方（如独立消费者或 RPC）使用。
 */
@Service
public class ProcessService implements ApplicationContextAware {

    private final Map<Integer, DataContext> globalDataContext = new ConcurrentHashMap<>();
    private final Map<Integer, TxIdGenerator> txIdGeneratorMap = new ConcurrentHashMap<>();
    public final static Map<Integer, TradePrice> tradePrices = new ConcurrentHashMap<>(2048); // 缓存最新价格
    private static final Logger logger = LoggerFactory.getLogger(ProcessService.class);
    private final int THREAD_NUM = Runtime.getRuntime().availableProcessors();
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

    private final RLocalCachedMap<Integer, MarketConfig> marketConfigs;

    private final AtomicBoolean atomicLoadUserData = new AtomicBoolean(false);

    private boolean startProcess = false;


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
                List<User> users = userService.lambdaQuery().eq(User::getGroupName, groupId)
                        .gt(User::getId, start).orderByAsc(User::getId).last("limit 100").list();
                if (CollectionUtils.isEmpty(users)) {
                    break;
                }
                count += users.size();
                for (User user : users) {
                    start = Math.max(start, user.getId());
                    UserData userData = userDataService.load(user.getId(), groupId);
                    DataContext dataContext = globalDataContext.get(userSlot(user.getId()));
                    dataContext.put(user.getId(), userData);
                }
            } while (true);
            logger.info("loadUserData = {}", count);
        }
    }

    public ProcessService(@Value("${kafka.servers}") String kafkaServers, RedissonClient redissonClient, MarketConfigService marketConfigService) {
        this.servers = kafkaServers;
        marketConfigs = redissonClient.getLocalCachedMap("market_config_redis_and_local_001", new TypedJsonJacksonCodec(Integer.class, MarketConfig.class), MarketCachedMapOptions.defaults());
        this.marketConfigService = marketConfigService;
    }

    /**
     * 启动处理服务：加载用户数据并启动 REQUEST_MESSAGE 消费。
     */
    public synchronized void start(String groupId, KafkaProducer<String, String> kafkaProducer) {
        logger.info("start process, groupId={}", groupId);
        this.groupId = groupId;
        startProcess(groupId, kafkaProducer);
        loadUserData(groupId);
        messageQueueService.toMaster(groupId);
    }

    /**
     * 开启本地工作线程与按 uid 槽位队列
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

    /**
     * 状态机处理：仅处理 REQUEST_MESSAGE 请求，按 uid 落入槽位队列，同一 uid 顺序执行。
     * 状态流转：RECEIVED(在 MQ 消费处) -> QUEUED -> PROCESSING -> COMPLETED | FAILED
     */
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
                if (logger.isDebugEnabled()) {
                    logger.debug("request {} -> {}, reqId={}, uid={}", RequestState.QUEUED, RequestState.PROCESSING, reqId, uid);
                }
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
                        userData = userDataService.load(uid, groupId);
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
                    if (logger.isDebugEnabled()) {
                        logger.debug("request {}, reqId={}, uid={}", RequestState.COMPLETED, reqId, uid);
                    }
                } catch (Exception e) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("request {}, reqId={}, uid={}", RequestState.FAILED, reqId, uid);
                    }
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
        TradePrice oldPrice = tradePrices.get(tradePrice.getMarketId());
        if (newPrice != null && oldPrice != null && newPrice.compareTo(oldPrice.getPrice()) == 0) {
            return;
        }
        tradePrices.put(tradePrice.getMarketId(), tradePrice);
        Map<Integer, TradePrice> prices = new HashMap<>(tradePrices);
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
                    marketConfigs.put(marketConfig.getId(), marketConfig);
                }
            } while (true);
        }
    }

}
