package com.tk.futures.process;

import com.alibaba.fastjson.JSON;
import com.tk.futures.model.AsyncMessageItems;
import com.tk.futures.model.UserData;
import com.tk.futures.generator.OrderIdGenerator;
import com.tk.futures.generator.PositionIdGenerator;
import com.tx.common.entity.Account;
import com.tx.common.entity.Order;
import com.tx.common.entity.Position;
import com.tx.common.entity.TradeOrder;
import com.tk.futures.generator.OrderIdGeneratorImpl;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicLong;

public class OrderProcessTest {


    private UserData userData;
    private Account account;
    private OrderProcess orderProcess;

    AtomicLong txidIndex = new AtomicLong(1000000);


    LinkedList<Order> orders = new LinkedList<>();

    private static AtomicLong positionIndex = new AtomicLong(10);
    private static AtomicLong matchIdIndex = new AtomicLong(10000);

    static class PositionIdGenerator0 implements PositionIdGenerator {

        @Override
        public long nextId() {
            return positionIndex.getAndIncrement();
        }
    }

    @Before
    public void setUp() {
        LinkedList<Account> accounts = new LinkedList<>();
        account = new Account();
        account.setCoinId(1L);
        account.setUid(1000L);
        account.setAvailableBalance(new BigDecimal("100000"));
        accounts.add(account);
        userData = new UserData(1000L, new LinkedList<>(), new LinkedList<>(), accounts);

        PositionIdGenerator positionIdGenerator = new PositionIdGenerator0();
        OrderIdGenerator orderIdGenerator = new OrderIdGeneratorImpl();
        orderIdGenerator.setSnowflakeIdWorker(1);
        orderProcess = new OrderProcess(null, positionIdGenerator, orderIdGenerator) {
            public long nextTxId() {
                return txidIndex.getAndIncrement();
            }

            public void sendToMatch(Order order) {
                System.out.println(JSON.toJSONString(order));
                orders.add(order);
            }
        };
    }

    @Test
    /**
     *  做多 市价，按照成交金额开仓
     */
    public void testOpenBuy01() {
        AsyncMessageItems messageItems = orderProcess.createOrder(userData, JSON.parseObject("{\n" +
                "  \"priceType\": 0,\n" +
                "  \"dealType\": 0,\n" +
                "  \"price\": 0,\n" +
                "  \"side\": \"BUY\",\n" +
                "  \"open\": \"OPEN\",\n" +
                "  \"positionType\": 0,\n" +
                "  \"leverageLevel\": 5,\n" +
                "  \"uid\": 1000,\n" +
                "  \"amount\": 5000\n" +
                "}", Order.class));
        System.out.println(JSON.toJSONString(messageItems));
    }

    /***
     * 做多 市价，按照成交数量开仓
     */
    @Test
    public void testOpenBuy02() {
        AsyncMessageItems messageItems = orderProcess.createOrder(userData, JSON.parseObject("{\n" +
                "  \"priceType\": 0,\n" +
                "  \"dealType\": 1,\n" +
                "  \"price\": 80000,\n" +
                "  \"side\": \"BUY\",\n" +
                "  \"open\": \"OPEN\",\n" +
                "  \"positionType\": 0,\n" +
                "  \"leverageLevel\": 5,\n" +
                "  \"uid\": 1000,\n" +
                "  \"volume\": 0.1\n" +
                "}", Order.class));
        System.out.println(JSON.toJSONString(messageItems));
    }

    /**
     * 做多 限价，按照成交数量下单
     */
    @Test
    public void testOpenBuy03() {
        AsyncMessageItems messageItems = orderProcess.createOrder(userData, JSON.parseObject("{\n" +
                "  \"priceType\": 1,\n" +
                "  \"dealType\": 1,\n" +
                "  \"price\": 80000,\n" +
                "  \"side\": \"BUY\",\n" +
                "  \"open\": \"OPEN\",\n" +
                "  \"positionType\": 0,\n" +
                "  \"leverageLevel\": 5,\n" +
                "  \"uid\": 1000,\n" +
                "  \"volume\": 0.11\n" +
                "}", Order.class));
        System.out.println(JSON.toJSONString(messageItems));
    }


    /**
     * 做空 市价，按照成交金额开仓
     */
    @Test
    public void testOpenSell04() {
        AsyncMessageItems messageItems = orderProcess.createOrder(userData, JSON.parseObject("{\n" +
                "  \"priceType\": 0,\n" +
                "  \"dealType\": 0,\n" +
                "  \"price\": 0,\n" +
                "  \"side\": \"SELL\",\n" +
                "  \"open\": \"OPEN\",\n" +
                "  \"positionType\": 0,\n" +
                "  \"leverageLevel\": 5,\n" +
                "  \"uid\": 1000,\n" +
                "  \"amount\": 5000\n" +
                "}", Order.class));
        System.out.println(JSON.toJSONString(messageItems));
    }

    /**
     * 做空 市价，按照成交数量开仓
     */
    @Test
    public void testOpenSell05() {
        AsyncMessageItems messageItems = orderProcess.createOrder(userData, JSON.parseObject("{\n" +
                "  \"priceType\": 0,\n" +
                "  \"dealType\": 1,\n" +
                "  \"price\": 80000,\n" +
                "  \"side\": \"SELL\",\n" +
                "  \"open\": \"OPEN\",\n" +
                "  \"positionType\": 0,\n" +
                "  \"leverageLevel\": 5,\n" +
                "  \"uid\": 1000,\n" +
                "  \"volume\": 0.1\n" +
                "}", Order.class));

        System.out.println(JSON.toJSONString(messageItems));
    }

    /**
     * 做空 市价，按照成交金额
     */
    @Test
    public void testOpenSell06() {
        AsyncMessageItems messageItems = orderProcess.createOrder(userData, JSON.parseObject("{\n" +
                "  \"priceType\": 0,\n" +
                "  \"dealType\": 0,\n" +
                "  \"price\": 0,\n" +
                "  \"side\": \"SELL\",\n" +
                "  \"open\": \"OPEN\",\n" +
                "  \"positionType\": 0,\n" +
                "  \"leverageLevel\": 5,\n" +
                "  \"uid\": 1000,\n" +
                "  \"amount\": 5000\n" +
                "}", Order.class));
        System.out.println(JSON.toJSONString(messageItems));
    }


    /**
     * 1、做多 市价，按照成交金额开仓
     * 2、匹配订单，开仓成功
     */
    @Test
    public void testOpenBuyAndMatch01() {
        testOpenBuy01();
        Order toMatchOrder = orders.pop();
        TradeOrder tradeOrder = new TradeOrder();
        tradeOrder.setMatchId(matchIdIndex.incrementAndGet());
        tradeOrder.setTxid(txidIndex.incrementAndGet());
        tradeOrder.setOrderId(toMatchOrder.getId());
        BigDecimal price = new BigDecimal("80000");
        tradeOrder.setPrice(price);
        BigDecimal volume = toMatchOrder.getAmount().divide(price, 16, RoundingMode.DOWN);
        tradeOrder.setVolume(volume);
        tradeOrder.setFullMatch(true);
        AsyncMessageItems messageItems = orderProcess.marchOrder(userData, tradeOrder);
        System.out.println(JSON.toJSONString(messageItems));
    }


    /**
     * 1、做多 市价，按照成交数量开仓
     * 2、撮合成功，开仓成功
     */
    @Test
    public void testOpenBuyAndMatch02() {
        testOpenBuy02();
        Order toMatchOrder = orders.pop();
        TradeOrder tradeOrder = new TradeOrder();
        tradeOrder.setMatchId(matchIdIndex.incrementAndGet());
        tradeOrder.setTxid(txidIndex.incrementAndGet());
        tradeOrder.setOrderId(toMatchOrder.getId());
        BigDecimal price = new BigDecimal("80000");
        tradeOrder.setPrice(price);
        BigDecimal volume = toMatchOrder.getVolume();
        tradeOrder.setVolume(volume);
        tradeOrder.setFullMatch(true);
        AsyncMessageItems messageItems = orderProcess.marchOrder(userData, tradeOrder);
        Assert.assertEquals(1, userData.getPositions().size());
        Assert.assertEquals(1, userData.getAccounts().size());
        Assert.assertEquals(0, volume.compareTo(userData.getPositions().get(0).getVolume()));
        System.out.println(JSON.toJSONString(messageItems));
    }


    /**
     * 1.做多 限价，按照成交数量下单
     * 2.撮合成功，开仓成功
     */
    @Test
    public void testOpenBuyAndMatch03() {
        testOpenBuy03();
        Order toMatchOrder = orders.pop();
        TradeOrder tradeOrder = new TradeOrder();
        tradeOrder.setMatchId(matchIdIndex.incrementAndGet());
        tradeOrder.setTxid(txidIndex.incrementAndGet());
        tradeOrder.setOrderId(toMatchOrder.getId());
        BigDecimal price = new BigDecimal("80000");
        tradeOrder.setPrice(price);
        BigDecimal volume = toMatchOrder.getVolume();
        tradeOrder.setVolume(volume);
        tradeOrder.setFullMatch(true);
        AsyncMessageItems messageItems = orderProcess.marchOrder(userData, tradeOrder);
        System.out.println(JSON.toJSONString(messageItems));
    }


    /**
     * 1、做空 市价，按照成交金额开仓
     * 2、撮合成功，开仓成功
     */
    @Test
    public void testOpenSellAndMatch04() {
        testOpenSell04();
        Order toMatchOrder = orders.pop();
        TradeOrder tradeOrder = new TradeOrder();
        tradeOrder.setMatchId(matchIdIndex.incrementAndGet());
        tradeOrder.setTxid(txidIndex.incrementAndGet());
        tradeOrder.setOrderId(toMatchOrder.getId());
        BigDecimal price = new BigDecimal("80000");
        tradeOrder.setPrice(price);
        BigDecimal volume = toMatchOrder.getAmount().divide(price, 16, RoundingMode.DOWN);
        tradeOrder.setVolume(volume);
        tradeOrder.setFullMatch(true);
        AsyncMessageItems messageItems = orderProcess.marchOrder(userData, tradeOrder);
        System.out.println(JSON.toJSONString(messageItems));
    }


    /**
     * 1、做空 市价，按照成交数量开仓
     * 2、撮合成功，开仓成功
     */
    @Test
    public void testOpenSellAndMatch05() {
        testOpenSell05();
        Order toMatchOrder = orders.pop();
        TradeOrder tradeOrder = new TradeOrder();
        tradeOrder.setMatchId(matchIdIndex.incrementAndGet());
        tradeOrder.setTxid(txidIndex.incrementAndGet());
        tradeOrder.setOrderId(toMatchOrder.getId());
        BigDecimal price = new BigDecimal("80000");
        tradeOrder.setPrice(price);
        BigDecimal volume = toMatchOrder.getVolume();
        tradeOrder.setVolume(volume);
        tradeOrder.setFullMatch(true);
        tradeOrder.setSide(toMatchOrder.getSide());
        AsyncMessageItems messageItems = orderProcess.marchOrder(userData, tradeOrder);
        System.out.println(JSON.toJSONString(messageItems));
    }

    /**
     * 1、做空 市价，按照成交金额
     * 2、撮合成功，开仓成功
     */
    @Test
    public void testOpenSellAndMatch06() {
        testOpenSell06();
        Order toMatchOrder = orders.pop();
        TradeOrder tradeOrder = new TradeOrder();
        tradeOrder.setMatchId(matchIdIndex.incrementAndGet());
        tradeOrder.setTxid(txidIndex.incrementAndGet());
        tradeOrder.setOrderId(toMatchOrder.getId());
        BigDecimal price = new BigDecimal("80000");
        tradeOrder.setPrice(price);
        BigDecimal volume = toMatchOrder.getAmount().divide(price, 16, RoundingMode.DOWN);
        tradeOrder.setVolume(volume);
        tradeOrder.setFullMatch(true);
        tradeOrder.setSide(toMatchOrder.getSide());
        AsyncMessageItems messageItems = orderProcess.marchOrder(userData, tradeOrder);
        System.out.println(JSON.toJSONString(messageItems));
    }


    /**
     * 1、做多 市价，按照成交金额开仓
     * 2、匹配订单，开仓成功
     * 3、下平仓单子
     */
    @Test
    public void testOpenBuyAndMatch01Close() {
        testOpenBuyAndMatch01();
        Assert.assertEquals(1, userData.getPositions().size());
        Position position = userData.getPositions().get(0);
        BigDecimal volume = position.getVolume();
        // 创建平仓订单
        AsyncMessageItems messageItems = orderProcess.createOrder(userData, JSON.parseObject("{\n" +
                "  \"priceType\": 1,\n" +
                "  \"dealType\": 1,\n" +
                "  \"price\": 81000,\n" +
                "  \"side\": \"SELL\",\n" +
                "  \"open\": \"CLOSE\",\n" +
                "  \"uid\": 1000,\n" +
                "  \"volume\": " + volume.toPlainString() + ",\n" +
                "  \"positionId\": " + position.getId() + "\n" +
                "}", Order.class));

        Assert.assertEquals(0, volume.compareTo(position.getPendingCloseVolume()));
        System.out.println(JSON.toJSONString(messageItems));
    }

    /**
     * 1、做多 市价，按照成交数量开仓
     * 2、撮合成功，开仓成功
     * 3、下平仓单子
     */
    @Test
    public void testOpenBuyAndMatch02Close() {
        testOpenBuyAndMatch02();
        Assert.assertEquals(1, userData.getPositions().size());
        Position position = userData.getPositions().get(0);
        BigDecimal volume = position.getVolume();
        // 创建平仓订单
        AsyncMessageItems messageItems = orderProcess.createOrder(userData, JSON.parseObject("{\n" +
                "  \"priceType\": 1,\n" +
                "  \"dealType\": 1,\n" +
                "  \"price\": 81000,\n" +
                "  \"side\": \"SELL\",\n" +
                "  \"open\": \"CLOSE\",\n" +
                "  \"uid\": 1000,\n" +
                "  \"volume\": " + volume.toPlainString() + ",\n" +
                "  \"positionId\": " + position.getId() + "\n" +
                "}", Order.class));

        Assert.assertEquals(0, volume.compareTo(position.getPendingCloseVolume()));
        System.out.println(JSON.toJSONString(messageItems));
    }


    /**
     * 1.做多 限价，按照成交数量下单
     * 2.撮合成功，开仓成功
     * 3、下平仓单子
     */
    @Test
    public void testOpenBuyAndMatch03Close() {
        testOpenBuyAndMatch03();
        Assert.assertEquals(1, userData.getPositions().size());
        Position position = userData.getPositions().get(0);
        BigDecimal volume = position.getVolume();
        // 创建平仓订单
        AsyncMessageItems messageItems = orderProcess.createOrder(userData, JSON.parseObject("{\n" +
                "  \"priceType\": 1,\n" +
                "  \"dealType\": 1,\n" +
                "  \"price\": 81000,\n" +
                "  \"side\": \"SELL\",\n" +
                "  \"open\": \"CLOSE\",\n" +
                "  \"uid\": 1000,\n" +
                "  \"volume\": " + volume.toPlainString() + ",\n" +
                "  \"positionId\": " + position.getId() + "\n" +
                "}", Order.class));

        Assert.assertEquals(0, volume.compareTo(position.getPendingCloseVolume()));
        System.out.println(JSON.toJSONString(messageItems));
    }


    /**
     * 1、做空 市价，按照成交金额开仓
     * 2、撮合成功，开仓成功
     * 3、下限价平仓单子
     */
    @Test
    public void testOpenSellAndMatch04Close() {
        testOpenSellAndMatch04();
        Assert.assertEquals(1, userData.getPositions().size());
        Position position = userData.getPositions().get(0);
        BigDecimal volume = position.getVolume();
        // 创建平仓订单
        AsyncMessageItems messageItems = orderProcess.createOrder(userData, JSON.parseObject("{\n" +
                "  \"priceType\": 1,\n" +
                "  \"dealType\": 1,\n" +
                "  \"price\": 81000,\n" +
                "  \"side\": \"BUY\",\n" +
                "  \"open\": \"CLOSE\",\n" +
                "  \"uid\": 1000,\n" +
                "  \"volume\": " + volume.toPlainString() + ",\n" +
                "  \"positionId\": " + position.getId() + "\n" +
                "}", Order.class));

        Assert.assertEquals(0, volume.compareTo(position.getPendingCloseVolume()));
        System.out.println(JSON.toJSONString(messageItems));
    }

    /**
     * 1、做空 市价，按照成交数量开仓
     * 2、撮合成功，开仓成功
     * 3、下平仓单子
     */
    @Test
    public void testOpenSellAndMatch05Close() {
        testOpenSellAndMatch05();
        Assert.assertEquals(1, userData.getPositions().size());
        Position position = userData.getPositions().get(0);
        BigDecimal volume = position.getVolume();
        // 创建平仓订单
        AsyncMessageItems messageItems = orderProcess.createOrder(userData, JSON.parseObject("{\n" +
                "  \"priceType\": 1,\n" +
                "  \"dealType\": 1,\n" +
                "  \"price\": 81000,\n" +
                "  \"side\": \"BUY\",\n" +
                "  \"open\": \"CLOSE\",\n" +
                "  \"uid\": 1000,\n" +
                "  \"volume\": " + volume.toPlainString() + ",\n" +
                "  \"positionId\": " + position.getId() + "\n" +
                "}", Order.class));

        Assert.assertEquals(0, volume.compareTo(position.getPendingCloseVolume()));
        System.out.println(JSON.toJSONString(messageItems));
    }


    @Test
    public void testOpenSellAndMatch06Close() {
        testOpenSellAndMatch06();
        Assert.assertEquals(1, userData.getPositions().size());
        Position position = userData.getPositions().get(0);
        BigDecimal volume = position.getVolume();
        // 创建平仓订单
        AsyncMessageItems messageItems = orderProcess.createOrder(userData, JSON.parseObject("{\n" +
                "  \"priceType\": 1,\n" +
                "  \"dealType\": 1,\n" +
                "  \"price\": 81000,\n" +
                "  \"side\": \"BUY\",\n" +
                "  \"open\": \"CLOSE\",\n" +
                "  \"uid\": 1000,\n" +
                "  \"volume\": " + volume.toPlainString() + ",\n" +
                "  \"positionId\": " + position.getId() + "\n" +
                "}", Order.class));

        Assert.assertEquals(0, volume.compareTo(position.getPendingCloseVolume()));
        System.out.println(JSON.toJSONString(messageItems));
    }

    /**
     * 1、做多 市价，按照成交金额开仓
     * 2、匹配订单，开仓成功
     * 3、下平仓单子
     * 4、平仓单子撮合成功
     */
    @Test
    public void testOpenBuyAndMatch01CloseMatch() {
        testOpenBuyAndMatch01Close();
        Order toMatchSellOrder = orders.pop();
        TradeOrder tradeSellOrder = new TradeOrder();
        tradeSellOrder.setMatchId(matchIdIndex.incrementAndGet());
        tradeSellOrder.setTxid(txidIndex.incrementAndGet());
        tradeSellOrder.setOrderId(toMatchSellOrder.getId());
        BigDecimal sellPrice = new BigDecimal("81000");
        tradeSellOrder.setPrice(sellPrice);
        tradeSellOrder.setVolume(toMatchSellOrder.getVolume());
        tradeSellOrder.setFullMatch(true);
        AsyncMessageItems messageItems = orderProcess.marchOrder(userData, tradeSellOrder);
        System.out.println(JSON.toJSONString(messageItems));
    }


    /**
     * 1、做多 市价，按照成交数量开仓
     * 2、撮合成功，开仓成功
     * 3、下平仓单子
     * 4、平仓单子撮合成功
     */
    @Test
    public void testOpenBuyAndMatch02CloseMatch() {
        testOpenBuyAndMatch02Close();
        Order toMatchSellOrder = orders.pop();
        TradeOrder tradeSellOrder = new TradeOrder();
        tradeSellOrder.setMatchId(matchIdIndex.incrementAndGet());
        tradeSellOrder.setTxid(txidIndex.incrementAndGet());
        tradeSellOrder.setOrderId(toMatchSellOrder.getId());
        BigDecimal sellPrice = new BigDecimal("81000");
        tradeSellOrder.setPrice(sellPrice);
        tradeSellOrder.setVolume(toMatchSellOrder.getVolume());
        tradeSellOrder.setFullMatch(true);
        AsyncMessageItems messageItems = orderProcess.marchOrder(userData, tradeSellOrder);
        System.out.println(JSON.toJSONString(messageItems));
    }

    /**
     * 1.做多 限价，按照成交数量下单
     * 2.撮合成功，开仓成功
     * 3、下平仓单子
     * 4、平仓单子撮合成功
     */
    @Test
    public void testOpenBuyAndMatch03CloseMatch() {
        testOpenBuyAndMatch03Close();
        Order toMatchSellOrder = orders.pop();
        TradeOrder tradeSellOrder = new TradeOrder();
        tradeSellOrder.setMatchId(matchIdIndex.incrementAndGet());
        tradeSellOrder.setTxid(txidIndex.incrementAndGet());
        tradeSellOrder.setOrderId(toMatchSellOrder.getId());
        BigDecimal sellPrice = new BigDecimal("81000");
        tradeSellOrder.setPrice(sellPrice);
        tradeSellOrder.setVolume(toMatchSellOrder.getVolume());
        tradeSellOrder.setFullMatch(true);
        AsyncMessageItems messageItems = orderProcess.marchOrder(userData, tradeSellOrder);
        System.out.println(JSON.toJSONString(messageItems));
    }

    /**
     * 1、做空 市价，按照成交金额开仓
     * 2、撮合成功，开仓成功
     * 3、下限价平仓单子
     */
    @Test
    public void testOpenSellAndMatch04CloseMatch() {
        testOpenSellAndMatch04Close();
        Order toMatchSellOrder = orders.pop();
        TradeOrder tradeSellOrder = new TradeOrder();
        tradeSellOrder.setMatchId(matchIdIndex.incrementAndGet());
        tradeSellOrder.setTxid(txidIndex.incrementAndGet());
        tradeSellOrder.setOrderId(toMatchSellOrder.getId());
        BigDecimal sellPrice = new BigDecimal("81000");
        tradeSellOrder.setPrice(sellPrice);
        tradeSellOrder.setVolume(toMatchSellOrder.getVolume());
        tradeSellOrder.setFullMatch(true);
        AsyncMessageItems messageItems = orderProcess.marchOrder(userData, tradeSellOrder);
        System.out.println(JSON.toJSONString(messageItems));
    }

}
