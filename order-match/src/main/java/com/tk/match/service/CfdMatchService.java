package com.tk.match.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.tk.match.generator.TradeIdGenerator;
import com.tk.match.order.OrderEvent;
import com.tk.match.order.OrderWrapper;
import com.tk.match.price.BinanceSpotClient;
import com.tx.common.entity.MarketConfig;
import com.tx.common.entity.Order;
import com.tx.common.entity.TradeOrder;
import com.tx.common.entity.TradePrice;
import com.tx.common.kafka.KafkaTopic;
import com.tx.common.message.match.MatchOrderMessage;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public class CfdMatchService extends MatchService {

    private final Callback callback = (metadata, exception) -> {
        if (exception != null) {
            logger.error("send message error", exception);
        } else {
            this.offset = metadata.offset();
        }
    };

    private static final Logger logger = LoggerFactory.getLogger(CfdMatchService.class);

    private final PriorityBlockingQueue<Message> messages = new PriorityBlockingQueue<>();

    private final TreeMap<BigDecimal, LinkedList<Order>> buyOrders;

    private final TreeMap<BigDecimal, LinkedList<Order>> sellOrders;


    private class MatchHashMap extends LinkedHashMap<Long, OrderWrapper> {
        @Override
        public OrderWrapper put(Long key, OrderWrapper value) {
            OrderWrapper wrapper = super.put(key, value);
            OrderEvent orderEvent = new OrderEvent(value.getOrder(), OrderEvent.EventType.UPDATE.name());
            kafkaProducer.send(new ProducerRecord<>(KafkaTopic.MATCH_RECOVER + marketConfig.getSymbol(), JSON.toJSONString(orderEvent)), callback);
            return wrapper;
        }

        @Override
        public OrderWrapper remove(Object key) {
            return remove(key, true);
        }

        public OrderWrapper remove(Object key, boolean send) {
            OrderWrapper wrapper = super.remove(key);
            if (wrapper != null && send) {
                OrderEvent orderEvent = new OrderEvent(wrapper.getOrder(), OrderEvent.EventType.FINISH.name());
                kafkaProducer.send(new ProducerRecord<>(KafkaTopic.MATCH_RECOVER + marketConfig.getSymbol(), JSON.toJSONString(orderEvent)), callback);
            }
            return wrapper;
        }

    }

    // 加入到内存的所有订单，记录顺序和recover队列的offset
    private final MatchHashMap orderWrappers = new MatchHashMap();

    private final TradeIdGenerator tradeIdGenerator;

    private BinanceSpotClient binanceSpotClient;

    private Properties props;

    private final String servers;

    private volatile boolean isMaster = false;

    private volatile CountDownLatch masterCountDownLatch;

    private volatile boolean isConsumer; // 是否处理订单

    private volatile BigDecimal curPrice = BigDecimal.ZERO;

    private volatile long offset = 0L;

    private final KafkaManager kafkaManager;

    private static final ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(1);

    public CfdMatchService(KafkaProducer<String, String> kafkaProducer, MarketConfig marketConfig, TradeIdGenerator tradeIdGenerator, String servers, KafkaManager kafkaManager) {
        super(kafkaProducer, marketConfig);
        buyOrders = new TreeMap<>();
        sellOrders = new TreeMap<>(Comparator.reverseOrder());
        this.tradeIdGenerator = tradeIdGenerator;
        this.servers = servers;
        this.kafkaManager = kafkaManager;
    }

    private void openConnect() {
        try {
            binanceSpotClient = new BinanceSpotClient(marketConfig);
            binanceSpotClient.addListener(this::onMessage);
            binanceSpotClient.connect();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void curPriceIsZero(List<Order> orders, List<TradeOrder> tradeOrders, LinkedList<Order> cancelOrders) {
        for (Order order : orders) {
            LinkedList<Order> orderList;
            if (order.isBuy()) {
                orderList = buyOrders.computeIfAbsent(order.getPrice(), k -> new LinkedList<>());
            } else {
                orderList = sellOrders.computeIfAbsent(order.getPrice(), k -> new LinkedList<>());
            }
            boolean add = true;
            for (Order addedOrder : orderList) {
                if (Objects.equals(addedOrder.getId(), order.getId())) {
                    add = false;
                    break;
                }
            }
            if (add) {
                if (order.isLimit()) {
                    orderList.add(order);
                    orderWrappers.put(order.getId(), new OrderWrapper(order, offset, null));
                } else if (order.isMarket()) {
                    // 订单异常
                    order.setStatus(Order.OrderStatus.EXCEPTION.value());
                    cancelOrders.add(order);
                }
            }
        }
    }

    private void matchOrder(List<Order> orders, List<TradeOrder> tradeOrders) {
        if (orders != null) {
            LinkedList<Order> cancelOrders = new LinkedList<>();
            if (curPrice.compareTo(BigDecimal.ZERO) <= 0) { // 没有价格,限价的全部挂单，市价单全部订单异常取消
                curPriceIsZero(orders, tradeOrders, cancelOrders);
            } else {
                for (Order order : orders) {
                    if (order.isSell() && order.isOpenPosition() && !order.isDealTypeAmount()) { // 限价，开空
                        BigDecimal upPercent = new BigDecimal(1).divide(new BigDecimal(order.getLeverageLevel()), 16, RoundingMode.UP);
                        upPercent = upPercent.add(BigDecimal.ONE);
                        BigDecimal upPrice = order.getPrice().multiply(upPercent);
                        if (upPrice.compareTo(curPrice) < 0) { // 价格太低，开仓后无法维持保证金
                            cancelOrders.add(order);
                            continue;
                        }
                    }
                    LinkedList<Order> orderList;
                    if (order.isBuy()) {
                        orderList = buyOrders.get(order.getPrice());
                    } else {
                        orderList = sellOrders.get(order.getPrice());
                    }
                    if (order.cancel()) {
                        if (orderList != null) {
                            Iterator<Order> iterator = orderList.iterator();
                            while (iterator.hasNext()) {
                                Order next = iterator.next();
                                if (Objects.equals(next.getId(), order.getId())) {
                                    cancelOrders.add(next);
                                    orderWrappers.remove(next.getId());
                                    iterator.remove();
                                    break;
                                }
                            }
                        }
                    } else {
                        orderList = orderList == null ? new LinkedList<>() : orderList;
                        if (order.isBuy() && order.isLimit() && order.getPrice().compareTo(curPrice) >= 0) { // 成交: 买单挂单价格大于等于当前价格
                            TradeOrder tradeOrder = new TradeOrder();
                            tradeOrder.setMatchId(tradeIdGenerator.nextId());
                            tradeOrder.setSide(Order.OrderSide.BUY.value);
                            createTraderOrder(tradeOrders, order, tradeOrder, TradeOrder.Role.TAKER, curPrice);
                            tradeOrder.setFullMatch(true);
                        } else if (order.isSell() && order.isLimit() && order.getPrice().compareTo(curPrice) <= 0) { // 成交: 卖单挂单价格小于等于当前价格
                            TradeOrder tradeOrder = new TradeOrder();
                            tradeOrder.setMatchId(tradeIdGenerator.nextId());
                            tradeOrder.setSide(Order.OrderSide.SELL.value);
                            createTraderOrder(tradeOrders, order, tradeOrder, TradeOrder.Role.TAKER, curPrice);
                            tradeOrder.setFullMatch(true);
                        } else if (order.isLimit()) { // 限价单
                            boolean isAdd = true;
                            for (Order next : orderList) {
                                if (Objects.equals(next.getId(), order.getId())) {
                                    isAdd = false;
                                    break;
                                }
                            }

                            if (isAdd) {
                                orderList.add(order);
                                orderWrappers.put(order.getId(), new OrderWrapper(order, offset, null));
                            }

                            if (order.isBuy()) {
                                buyOrders.put(order.getPrice(), orderList);
                            } else {
                                sellOrders.put(order.getPrice(), orderList);
                            }
                        } else if (order.isMarket()) { // 市价单
                            TradeOrder tradeOrder = new TradeOrder();
                            tradeOrder.setUid(order.getUid());
                            tradeOrder.setMatchId(tradeIdGenerator.nextId());
                            tradeOrder.setOrderId(order.getId());
                            tradeOrder.setPrice(curPrice);
                            tradeOrder.setRole(TradeOrder.Role.TAKER.value());
                            tradeOrder.setCtime(new Date());
                            tradeOrder.setStatus(TradeOrder.Status.PROCESSING.value());
                            tradeOrder.setFee(BigDecimal.ZERO);
                            BigDecimal volume;
                            if (order.isBuy()) { // 买单市价单
                                tradeOrder.setSide(Order.OrderSide.BUY.value);
                                if (order.isClosePosition()) { // 平空单
                                    volume = order.getAmount(); // 持仓数量
                                } else {
                                    volume = order.getAmount().divide(curPrice, 16, RoundingMode.DOWN);
                                }
                            } else {
                                tradeOrder.setSide(Order.OrderSide.SELL.value);
                                if (order.isOpenPosition()) { // 开空单
                                    volume = order.getAmount().divide(curPrice, 16, RoundingMode.DOWN);
                                } else {
                                    volume = order.getAmount();
                                }
                            }
                            tradeOrder.setVolume(volume);
                            tradeOrder.setFullMatch(true);
                            tradeOrders.add(tradeOrder);
                        }
                    }
                }
            }

            if (!CollectionUtils.isEmpty(cancelOrders)) {
                MatchOrderMessage matchOrderMessage = new MatchOrderMessage(MatchOrderMessage.MessageType.EXCEPTION_ORDER.value(), cancelOrders);
                String kafkaMessage = JSON.toJSONString(matchOrderMessage);
                kafkaProducer.send(new ProducerRecord<>(KafkaTopic.ORDER_MATCH, kafkaMessage), (metadata, exception) -> {
                    if (exception != null) {
                        logger.error("send_exception_order_error : {}", kafkaMessage, exception);
                    }
                });
            }
        }
    }

    /**
     * 接收价格和订单
     */
    private void match() {
        masterCountDownLatch = new CountDownLatch(1);
        while (isConsumer || !messages.isEmpty()) {
            try {
                Message message = messages.poll(10, TimeUnit.MILLISECONDS);
                if (message != null) {
                    if (message.txid < 0) {
                        doRecoverCheckPoint(message.getBeginOffset(), message.getLatestOffset());
                        return;
                    }
                    List<TradeOrder> tradeOrders = new ArrayList<>();
                    if (message.price.compareTo(BigDecimal.ZERO) > 0) { // 价格推送
                        curPrice = message.price;
                        Set<BigDecimal> bOrders = buyOrders.keySet();
                        for (BigDecimal price : bOrders) {
                            if (message.price.compareTo(price) <= 0) { // 小于或者等于，买单挂单价格
                                LinkedList<Order> matchOrders = buyOrders.remove(price);
                                for (Order buy : matchOrders) {
                                    TradeOrder tradeOrder = new TradeOrder();
                                    tradeOrder.setMatchId(tradeIdGenerator.nextId());
                                    tradeOrder.setSide(Order.OrderSide.BUY.value);
                                    createTraderOrder(tradeOrders, buy, tradeOrder, TradeOrder.Role.MAKER, buy.getPrice());
                                    tradeOrder.setFullMatch(true);
                                    orderWrappers.remove(buy.getId());
                                }
                            } else {
                                break;
                            }
                        }
                        Set<BigDecimal> sOrders = sellOrders.keySet();
                        for (BigDecimal price : sOrders) {
                            if (message.price.compareTo(price) >= 0) { // 大于或等于卖单，挂单价格
                                LinkedList<Order> matchOrders = sellOrders.remove(price);
                                for (Order sell : matchOrders) {
                                    TradeOrder tradeOrder = new TradeOrder();
                                    tradeOrder.setMatchId(tradeIdGenerator.nextId());
                                    tradeOrder.setSide(Order.OrderSide.SELL.value);
                                    createTraderOrder(tradeOrders, sell, tradeOrder, TradeOrder.Role.MAKER, sell.getPrice());
                                    tradeOrder.setFullMatch(true);
                                    orderWrappers.remove(sell.getId());
                                }
                            } else {
                                break;
                            }
                        }
                    } else {// 推送订单
                        matchOrder(message.getOrders(), tradeOrders); // 订单匹配
                    }

                    if (!CollectionUtils.isEmpty(tradeOrders)) {
                        MatchOrderMessage matchOrderMessage = new MatchOrderMessage(MatchOrderMessage.MessageType.MATCH_ORDER.value(), tradeOrders);
                        String kafkaMessage = JSON.toJSONString(matchOrderMessage);
                        kafkaProducer.send(new ProducerRecord<>(KafkaTopic.ORDER_MATCH, kafkaMessage), (metadata, exception) -> {
                            if (exception != null) {
                                logger.error("send_match_order_error : {}", kafkaMessage, exception);
                            } else {
                                logger.info("send_ok {},\t{},\t{}", KafkaTopic.ORDER_MATCH, metadata.partition(), metadata.offset());
                            }
                        });
                    }
                }
            } catch (Exception e) {
                logger.warn("stop", e);
            }
        }
        masterCountDownLatch.countDown();
    }

    private void doRecoverCheckPoint(long beginOffset, long latestOffset) {
        if (orderWrappers.isEmpty()) {
            kafkaManager.deleteBeginOffset(marketConfig, latestOffset);
            return;
        }
        boolean includeDeleteOffset = false;
        for (Long orderId : orderWrappers.keySet()) {
            OrderWrapper orderWrapper = orderWrappers.get(orderId);
            if (orderWrapper.getOffset() <= beginOffset) {
                orderWrappers.remove(orderId, true);
                orderWrapper.setOffset(this.offset);
                orderWrappers.put(orderId, orderWrapper);
                includeDeleteOffset = true;
            }
        }
        if (includeDeleteOffset) {
            return;
        }

        long diff = latestOffset - beginOffset;
        long size = orderWrappers.size();
        int maxCount = Math.min(Math.max(orderWrappers.size() / 5, 1), 200);
        List<Long> orderIds = new ArrayList<>(maxCount);
        if (diff > (size * 3)) {
            Iterator<Long> iterator = orderWrappers.keySet().iterator();
            while (iterator.hasNext() && orderIds.size() < maxCount) {
                orderIds.add(iterator.next());
            }
        }
        long deleteOffset = 0;
        for (Long orderId : orderIds) {
            OrderWrapper orderWrapper = orderWrappers.remove(orderId, false);
            orderWrapper.setOffset(Math.max(this.offset, latestOffset));
            deleteOffset = orderWrapper.getOffset();
            orderWrappers.put(orderId, orderWrapper);
        }
        if (deleteOffset > 0) {
            kafkaManager.deleteBeginOffset(marketConfig, deleteOffset - 1);
        }
    }

    private void createTraderOrder(List<TradeOrder> tradeOrders, Order order, TradeOrder tradeOrder, TradeOrder.Role role, BigDecimal price) {
        tradeOrder.setRole(role.value());
        tradeOrder.setStatus(TradeOrder.Status.PROCESSING.value());
        tradeOrder.setVolume(order.getVolume());
        tradeOrder.setPrice(price);
        tradeOrder.setFee(BigDecimal.ZERO);
        tradeOrder.setCtime(new Date());
        tradeOrder.setOrderId(order.getId());
        tradeOrder.setUid(order.getUid());
        tradeOrder.setTxid(order.getTxid());
        tradeOrders.add(tradeOrder);
    }

    private void consumer() {
        try (KafkaConsumer<String, String> kafkaConsumer = new KafkaConsumer<>(props)) {
            kafkaConsumer.subscribe(Collections.singletonList("market-" + marketConfig.getSymbol()), new ConsumerRebalanceListener() {
                @Override
                public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                    logger.info("consumerMatchOrder_onPartitionsRevoked : {}", partitions);
                }

                @Override
                public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                    logger.info("consumerMatchOrder_onPartitionsAssigned : {}", partitions);
                }
            });
            while (isMaster) {
                try {
                    ConsumerRecords<String, String> consumerRecords = kafkaConsumer.poll(Duration.ofMillis(100));
                    LinkedList<Order> orderLinkedList = new LinkedList<>();
                    long offset = -1;
                    for (ConsumerRecord<String, String> record : consumerRecords) {
                        Order order = JSON.parseObject(record.value(), Order.class);
                        orderLinkedList.add(order);
                        offset = record.offset();
                    }
                    if (offset > 0) {
                        messages.add(new Message(1L, orderLinkedList, BigDecimal.ZERO));
                    }
                } catch (Exception e) {
                    logger.error("match : ", e);
                }
            }
        }
        isConsumer = false;
    }

    public void onMessage(String message) {
        try {
            JSONObject data = JSON.parseObject(message);
            String e = data.getString("e");
            if (StringUtils.equals(e, "trade")) {
                BigDecimal price = data.getBigDecimal("p");
                if (curPrice.compareTo(price) == 0) {
                    return;
                }
                messages.add(new Message(0L, new LinkedList<>(), price));
                TradePrice msg = new TradePrice();
                msg.setMarketId(marketConfig.getId());
                msg.setPrice(price);
                msg.setTime(System.currentTimeMillis());
                kafkaProducer.send(new ProducerRecord<>(KafkaTopic.TRADE_PRICE, JSON.toJSONString(msg)), (metadata, exception) -> {
                    if (exception != null) {
                        logger.error("send_current_price_error : {}", JSON.toJSONString(msg), exception);
                    }
                });
            }
        } catch (Exception e) {
            logger.warn("onMessage : {}", message, e);
        }
    }

    @Override
    public void toMaster() {
        if (!isMaster) {
            logger.info("启动撮合 master ：{}", marketConfig.getName());
            isMaster = true;
            isConsumer = true;
            props = new Properties();
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, servers);
            props.put(ConsumerConfig.GROUP_ID_CONFIG, "match");
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
            props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
            logger.info("consumer-match : market_name = {}", marketConfig.getSymbol());
            new Thread(this::match, "match").start();
            new Thread(this::consumer, "consumer-match").start(); // 消费
            openConnect(); // 读取外部数据
            scheduledExecutorService.schedule(this::checkRecover, 300, TimeUnit.SECONDS); // 十秒检查一次
            long[] offset = kafkaManager.getOffset(KafkaTopic.MATCH_RECOVER + marketConfig.getSymbol(), 0);
            this.offset = offset[1];
        }
    }

    private void checkRecover() {
        if (this.isMaster) {
            logger.info("checkRecover : {}", marketConfig.getSymbol());
            long[] offset = kafkaManager.getOffset(KafkaTopic.MATCH_RECOVER + marketConfig.getSymbol(), 0);
            long begin = offset[0];
            long end = offset[1];
            if (end > begin) {
                messages.put(new Message(begin, end));
            }
            scheduledExecutorService.schedule(this::checkRecover, 300, TimeUnit.SECONDS); // 十秒检查一次
        }
    }

    @Override
    public void toSlave() {
        logger.info("启动撮合 slave ：{}", marketConfig.getName());
        isMaster = false;
        if (binanceSpotClient != null && binanceSpotClient.isOpen()) { // 停止喂价
            binanceSpotClient.close();
        }
        binanceSpotClient = null;
        // 等已经消费的订单撮合完成
        CountDownLatch countDownLatch = masterCountDownLatch;
        if (countDownLatch != null) {
            try {
                countDownLatch.await();
            } catch (Exception e) {
                logger.warn("toSlave", e);
            }
        }
    }

    @Data
    public static class Message implements Comparable<Message> {
        private Long txid;
        private List<Order> orders;
        private BigDecimal price;

        private long beginOffset;
        private long latestOffset;

        @Override
        public int compareTo(Message o) {
            return this.txid.compareTo(o.txid);
        }

        public Message(long beginOffset, long latestOffset) {
            this.txid = -1L;
            this.beginOffset = beginOffset;
            this.latestOffset = latestOffset;
        }

        public Message(Long txid, List<Order> orders, BigDecimal price) {
            this.txid = txid;
            this.orders = orders;
            this.price = price;
        }
    }

}
