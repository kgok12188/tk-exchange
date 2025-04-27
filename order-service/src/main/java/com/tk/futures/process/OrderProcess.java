package com.tk.futures.process;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.tk.futures.model.AsyncMessageItems;
import com.tk.futures.model.DataContext;
import com.tk.futures.model.MethodAnnotation;
import com.tk.futures.model.UserData;
import com.tk.futures.generator.OrderIdGenerator;
import com.tk.futures.generator.PositionIdGenerator;
import com.tx.common.entity.*;
import com.tx.common.kafka.KafkaTopic;
import com.tx.common.message.AsyncMessageItem;
import com.tx.common.service.TradeOrderService;
import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.function.Consumer;

@Service
public class OrderProcess extends BaseProcess {

    private final PositionIdGenerator positionIdGenerator;
    private final OrderIdGenerator orderIdGenerator;

    @Autowired
    public OrderProcess(TradeOrderService tradeOrderService, PositionIdGenerator positionIdGenerator, OrderIdGenerator orderIdGenerator) {
        this.positionIdGenerator = positionIdGenerator;
        this.orderIdGenerator = orderIdGenerator;
    }

    /**
     * 开仓
     * 限价 ： 参数必须有 price, volume ,必须按照持仓数量 成交
     * 市价 ： 可以按照持仓数量成交，也可以按照金额成交
     *
     * @param userData
     * @param account
     * @param asyncMessageItems
     * @param order
     */
    private void createOrderOpenPosition(UserData userData, Account account, AsyncMessageItems asyncMessageItems, Order order) {
        if (order.getPositionId() != null && order.getPositionId() > 0) {
            Position position = userData.getPosition(order.getPositionId());
            if (position == null) {
                response(500, "futures.params.error", "position_id_error");
                return;
            }
        } else {
            order.setPositionId(null);
        }
        if (order.isLimit()) { // 限价
            if (order.getPrice() == null || order.getPrice().compareTo(BigDecimal.ZERO) <= 0 || order.getVolume() == null || order.getVolume().compareTo(BigDecimal.ZERO) <= 0) {
                response(500, "futures.params.error", "price_error");
                return;
            }
            if (order.isDealTypeAmount()) {
                response(500, "futures.params.error", "deal_type_error");
                return;
            }
            BigDecimal value = order.getPrice().multiply(order.getVolume()); // 开仓价值
            BigDecimal margin = value.divide(new BigDecimal(order.getLeverageLevel()), 16, RoundingMode.UP); // 保证金
            if (account.getAvailableBalance().compareTo(margin) < 0) {
                response(500, "futures.out_of_balance.error", null);
                return;
            }
            if (order.getPositionId() != null && order.getPositionId() > 0) {
                Position position = userData.getPositions().stream().filter(p -> p.getId().equals(order.getPositionId())).findFirst().orElse(null);
                if (position == null) {
                    response(500, "futures.params.error", "position_id_error");
                    return;
                }
                if (!StringUtils.equals(position.getSide(), order.getSide())) { // 开仓方向必须和当前仓位方向一致
                    response(500, "futures.params.error", "order_side_error");
                    return;
                }
            }
            order.setMargin(margin); // 占用保证金
            long nextedTxId = nextTxId();
            order.setId(orderIdGenerator.nextId());
            account.setOrderFrozen(account.getOrderFrozen().add(margin)); // 下单冻结保证金
            account.setAvailableBalance(account.getAvailableBalance().subtract(margin));

            account.setMtime(new Date());
            account.setTxid(nextedTxId);

            order.setStatus(Order.OrderStatus.INIT.value());
            order.setCtime(new Date());
            order.setMtime(new Date());
            order.setTxid(nextedTxId);

            asyncMessageItems.add(new AsyncMessageItem(AsyncMessageItem.Type.ACCOUNT.getValue(), Lists.newArrayList(account)));
            asyncMessageItems.add(new AsyncMessageItem(AsyncMessageItem.Type.ORDER.getValue(), Lists.newArrayList(order)));

            userData.mergerOrder(order);
            sendToMatch(order);
            response(200, "futures.order.success", order.getId());

        } else { // 市场价格
            if (order.isDealTypeAmount()) { // 按照金额成交
                BigDecimal margin = order.getAmount().divide(new BigDecimal(order.getLeverageLevel()), 16, RoundingMode.UP); // 保证金
                if (account.getAvailableBalance().compareTo(margin) < 0) {
                    // 余额不足
                    response(500, "futures.out_of_balance.error", null);
                } else {
                    // 挂单推送给撮合服务
                    account.setAvailableBalance(account.getAvailableBalance().subtract(margin));
                    account.setOrderFrozen(account.getOrderFrozen().add(margin));
                    long nextedTxId = nextTxId();
                    account.setMtime(new Date());
                    account.setTxid(nextedTxId);
                    order.setId(orderIdGenerator.nextId());
                    order.setTxid(nextedTxId);
                    order.setStatus(Order.OrderStatus.INIT.value());
                    order.setMtime(new Date());
                    order.setMargin(margin);
                    userData.mergerOrder(order);
                    asyncMessageItems.add(new AsyncMessageItem(AsyncMessageItem.Type.ORDER.getValue(), Lists.newArrayList(order)));
                    asyncMessageItems.add(new AsyncMessageItem(AsyncMessageItem.Type.ACCOUNT.getValue(), Lists.newArrayList(account)));
                    sendToMatch(order);
                    response(200, "futures.order.success", order.getId());
                }
            } else { // 市场价格按照币数量成交,price 是前端标记价格，可以从后端获取
                BigDecimal margin = order.getPrice().multiply(order.getVolume()).divide(new BigDecimal(order.getLeverageLevel()), 16, RoundingMode.UP);
                if (account.getAvailableBalance().compareTo(margin) < 0) {
                    response(500, "futures.out_of_balance.error", null);
                    return;
                }
                long nextedTxId = nextTxId();
                order.setId(orderIdGenerator.nextId());
                order.setMargin(margin);
                order.setMtime(new Date());
                order.setTxid(nextedTxId);
                account.setOrderFrozen(account.getOrderFrozen().add(margin));
                account.setAvailableBalance(account.getAvailableBalance().subtract(margin));
                account.setMtime(new Date());
                account.setTxid(nextedTxId);

                sendToMatch(order);
                userData.mergerOrder(order);
                response(200, "futures.order.success", order.getId());

                asyncMessageItems.add(new AsyncMessageItem(AsyncMessageItem.Type.ORDER.getValue(), Lists.newArrayList(order)));
                asyncMessageItems.add(new AsyncMessageItem(AsyncMessageItem.Type.ACCOUNT.getValue(), Lists.newArrayList(account)));
            }
        }
    }

    private void closePosition(UserData userData, Account account, AsyncMessageItems asyncMessageItems, Order order) {
        if (order.getPositionId() == null || order.getPositionId() == 0 || order.getId() != null) {
            response(500, "futures.params.error", "positionId_or_orderId_error");
            return;
        }
        LinkedList<Position> positions = userData.getPositions();
        Iterator<Position> iterator = positions.iterator();
        Position selectedPosition = null;
        while (iterator.hasNext()) {
            Position position = iterator.next();
            if (Objects.equals(position.getId(), order.getPositionId())) {
                selectedPosition = position;
                break;
            }
        }
        if (selectedPosition == null) {
            response(500, "futures.params.error", "position_id_error");
            return;
        }

        if (StringUtils.equals(selectedPosition.getSide(), order.getSide())) { // 平仓单子一定要和持仓方向不一致
            response(500, "futures.params.error", "position_side_error");
            return;
        }

        order.setStatus(Order.OrderStatus.INIT.value());
        order.setMtime(new Date());

        if (order.isDealTypeVolume()) { // 根据持仓数量平仓
            BigDecimal volume = order.getVolume();
            if (volume == null || volume.compareTo(BigDecimal.ZERO) <= 0) {
                volume = selectedPosition.getVolume();
                order.setVolume(volume);
            }
            if (volume.compareTo(selectedPosition.getVolume()) > 0) {
                response(500, "futures.params.error", "volume_error");
                return;
            }
            if (order.isLimit() && (order.getPrice() == null || order.getPrice().compareTo(BigDecimal.ZERO) <= 0)) {
                response(500, "futures.params.error", "price_error");
                return;
            }

            order.setId(orderIdGenerator.nextId());
            selectedPosition.setVolume(selectedPosition.getVolume().subtract(volume));
            selectedPosition.setPendingCloseVolume(selectedPosition.getPendingCloseVolume().add(volume));
            selectedPosition.setMtime(new Date());

            long nextedTxId = nextTxId();
            order.setTxid(nextedTxId);
            selectedPosition.setTxid(nextedTxId);
            sendToMatch(order);
            userData.mergerOrder(order);
            response(200, "futures.order.success", order.getId());
            asyncMessageItems.add(new AsyncMessageItem(AsyncMessageItem.Type.ORDER.getValue(), Lists.newArrayList(order)));
            asyncMessageItems.add(new AsyncMessageItem(AsyncMessageItem.Type.POSITION.getValue(), Lists.newArrayList(selectedPosition)));
        } else {
            response(500, "futures.params.error", "volume_error");
        }
    }

    /**
     * 订单类型
     * priceType 0 市价  1 限价格
     * side BUY 买单  SELL 卖单
     * dealType  0 金额（amount USDT）  1 数量 (volume)
     * open OPEN 开仓 CLOSE 平仓
     * leverageLevel 杠杆倍数
     * 下单情况：
     *
     * @param userData 当前用户
     * @param order    订单
     * @return 异步刷盘数据
     */
    @MethodAnnotation("createOrder")
    public AsyncMessageItems createOrder(UserData userData, Order order) {
        Account account = userData.getUSDTAccount();
        AsyncMessageItems asyncMessageItems = new AsyncMessageItems();
        if (order.isOpenPosition()) { // 开仓
            createOrderOpenPosition(userData, account, asyncMessageItems, order);
        } else if (order.isClosePosition()) {
            closePosition(userData, account, asyncMessageItems, order);
        }
        return asyncMessageItems;
    }

    /**
     * 发送数据到撮合服务
     *
     * @param order 订单
     */
    protected void sendToMatch(Order order) {
        MarketConfig marketConfig = dataContextLocal.get().getMarkerConfigs().get(String.valueOf(order.getMarketId()));
        KafkaProducer<String, String> kafkaProducer = dataContextLocal.get().getKafkaProducer();
        if (kafkaProducer != null) {
            kafkaProducer.send(new ProducerRecord<>(KafkaTopic.MARKET + marketConfig.getSymbol(), String.valueOf(order.getUid()), JSONObject.toJSONString(order)), (metadata, exception) -> {
                if (exception != null) {
                    logger.error("send_match_order_error : {}", JSONObject.toJSONString(order), exception);
                }
            });
        } else {
            logger.warn("not_found_mq : {}", JSONObject.toJSONString(order));
        }
    }

    private void matchBuy(long nextedTxId, Order selectOrder, UserData userData, TradeOrder tradeOrder, AsyncMessageItems messageItems, Account account) {
        if (selectOrder.isOpenPosition()) { // 买单开仓
            matchBuyOpen(nextedTxId, selectOrder, userData, tradeOrder, messageItems, account);
        } else {
            matchBuyClose(nextedTxId, selectOrder, userData, tradeOrder, messageItems, account);
        }
    }

    /**
     * 买入开多
     */
    private void matchBuyOpen(long nextedTxId, Order selectOrder, UserData userData, TradeOrder tradeOrder, AsyncMessageItems messageItems, Account account) {
        Position selectedPosition = userData.getPosition(selectOrder.getPositionId());
        long positionId;
        if (selectedPosition == null) {
            logger.info("newPosition : order_id = {}", selectOrder.getId());
            selectedPosition = newPosition(selectOrder);
            if (selectOrder.getPositionId() == null || selectOrder.getPositionId() <= 0) {
                positionId = positionIdGenerator.nextId();
            } else {
                positionId = selectOrder.getPositionId();
            }
        } else {
            logger.info("position is not null : orderId = {},\tpositionId = {}", selectOrder.getId(), selectedPosition.getId());
            positionId = selectedPosition.getId();
        }
        selectedPosition.setId(positionId);
        selectOrder.setPositionId(positionId);
        selectedPosition.setLeverageLevel(selectOrder.getLeverageLevel());

        if (selectOrder.isDealTypeAmount()) { // 市价开仓
            logger.info("dealTypeAmount orderId = {},\tpositionId = {}", selectOrder.getId(), selectedPosition.getId());
            BigDecimal volume = tradeOrder.getVolume();
            BigDecimal price = tradeOrder.getPrice();
            BigDecimal dealAmount = volume.multiply(price); // 加仓价值

            tradeOrder.setPositionBeforeVolume(selectedPosition.getVolume());
            BigDecimal positionValue = selectedPosition.getOpenPrice().multiply(selectedPosition.getVolume()); // 持仓价值
            BigDecimal curVolume = selectedPosition.getVolume();

            BigDecimal totalPositionValue = positionValue.add(dealAmount); //
            BigDecimal totalVolume = curVolume.add(volume); // 调整后持仓的价值

            selectedPosition.setOpenPrice(totalPositionValue.divide(totalVolume, 16, RoundingMode.DOWN));
            selectedPosition.setVolume(totalVolume);
            //成交后所需保证金
            BigDecimal margin = dealAmount.divide(new BigDecimal(selectOrder.getLeverageLevel()), 16, RoundingMode.DOWN); // 本次成交占用保证金
            selectedPosition.setHoldAmount(selectedPosition.getHoldAmount().add(margin)); // 仓位持仓保证金
            selectOrder.setMargin(selectOrder.getMargin().subtract(margin)); // 订单占用的保证金

            if (selectOrder.getPositionType() == Position.PositionType.CROSS.value()) { // 全仓
                account.setCrossMarginFrozen(account.getCrossMarginFrozen().add(margin));
            } else { // 逐仓
                account.setIsolatedMarginFrozen(account.getIsolatedMarginFrozen().add(margin));
            }
            // 订单成功后，解除占用保证金
            account.setOrderFrozen(account.getOrderFrozen().subtract(margin));

            account.setMtime(new Date());
            account.setTxid(nextedTxId);

            selectOrder.setPositionId(positionId);
            if (tradeOrder.isFullMatch()) {
                selectOrder.setStatus(Order.OrderStatus.COMPLETED.value());
                selectOrder.setCompletedTime(new Date());
            } else {
                selectOrder.setStatus(Order.OrderStatus.PART_DEAL.value()); // 部分成交
            }

            selectOrder.setDealVolume(volume);
            selectOrder.setDealAmount(dealAmount);
            selectOrder.setAvgDealPrice(tradeOrder.getPrice());
            selectOrder.setMtime(new Date());
            selectOrder.setTxid(nextedTxId);

            tradeOrder.setPositionAfterVolume(selectedPosition.getVolume());
            tradeOrder.setTxid(nextedTxId);
            tradeOrder.setMtime(new Date());

            selectedPosition.setTxid(nextedTxId);
            selectedPosition.setMtime(new Date());
            userData.mergerPosition(selectedPosition);

            if (tradeOrder.isFullMatch()) { // 全部成交后，归还下单占用的保证金
                BigDecimal backMargin = selectOrder.getMargin();
                // 归还下单占用的保证金
                account.setAvailableBalance(account.getAvailableBalance().add(backMargin));
                account.setOrderFrozen(account.getOrderFrozen().subtract(backMargin));
                selectOrder.setMargin(BigDecimal.ZERO);
            }

            messageItems.add(new AsyncMessageItem(AsyncMessageItem.Type.ORDER.getValue(), Lists.newArrayList(selectOrder)));
            messageItems.add(new AsyncMessageItem(AsyncMessageItem.Type.POSITION.getValue(), Lists.newArrayList(selectedPosition)));
            messageItems.add(new AsyncMessageItem(AsyncMessageItem.Type.ACCOUNT.getValue(), Lists.newArrayList(account)));
            tradeOrder.setStatus(TradeOrder.Status.SUCCESS.value());

            messageItems.add(new AsyncMessageItem(AsyncMessageItem.Type.TRADE_ORDER.getValue(), Lists.newArrayList(tradeOrder)));

        } else { // 限价 开仓
            logger.info("dealTypeVolume orderId = {},\tpositionId = {}", selectOrder.getId(), selectedPosition.getId());
            if (selectOrder.getVolume().subtract(tradeOrder.getVolume()).compareTo(BigDecimal.ZERO) < 0) {
                // 成交错误
                logger.error("match_error {},\t{}", tradeOrder.getOrderId(), tradeOrder.getMatchId());
                return;
            }

            BigDecimal dealAmount = tradeOrder.getVolume().multiply(tradeOrder.getPrice()); // 成交金额
            BigDecimal margin = dealAmount.divide(new BigDecimal(selectOrder.getLeverageLevel()), 16, RoundingMode.DOWN); // 占用保证金
            Position.PositionType positionType = Position.PositionType.fromValue(selectOrder.getPositionType());
            account.setOrderFrozen(account.getOrderFrozen().subtract(margin));
            if (positionType == Position.PositionType.CROSS) { // 全仓
                account.setCrossMarginFrozen(account.getCrossMarginFrozen().add(margin)); // 修改全仓保证金
            } else {
                account.setIsolatedMarginFrozen(account.getIsolatedMarginFrozen().add(margin)); // 修改逐仓保证金
            }

            selectOrder.setMargin(selectOrder.getMargin().subtract(margin));
            tradeOrder.setPositionBeforeVolume(selectedPosition.getVolume());
            // 总成交金额 / 总成交量 = 持仓均价  平仓后需要重新计算
            BigDecimal newPositionValue = selectedPosition.getOpenPrice().multiply(selectedPosition.getVolume()).add(dealAmount);
            BigDecimal newPositionVolume = selectedPosition.getVolume().add(tradeOrder.getVolume());
            BigDecimal newHoldAmount = selectedPosition.getHoldAmount().add(margin);
            selectedPosition.setOpenPrice(newPositionValue.divide(newPositionVolume, 16, RoundingMode.DOWN));
            selectedPosition.setVolume(newPositionVolume);
            selectedPosition.setHoldAmount(newHoldAmount);

            selectOrder.setDealVolume(selectOrder.getDealVolume().add(tradeOrder.getVolume()));
            selectOrder.setDealAmount(selectOrder.getDealAmount().add(dealAmount));

            selectedPosition.setLeverageLevel(selectOrder.getLeverageLevel());
            tradeOrder.setPositionAfterVolume(selectedPosition.getVolume());
            tradeOrder.setStatus(TradeOrder.Status.SUCCESS.value());
            if (tradeOrder.isFullMatch()) {
                selectOrder.setStatus(Order.OrderStatus.COMPLETED.value());
                selectOrder.setCompletedTime(new Date());
                BigDecimal backMargin = selectOrder.getMargin();
                account.setAvailableBalance(account.getAvailableBalance().add(backMargin)); // 订单全部成交后，归还下单占用的保证金
                account.setOrderFrozen(account.getOrderFrozen().subtract(backMargin)); // 订单全部成交后，解除占用保证金
            } else {
                selectOrder.setStatus(Order.OrderStatus.PART_DEAL.value());
            }

            Date updateTime = new Date();
            tradeOrder.setMtime(updateTime);
            tradeOrder.setTxid(nextedTxId);
            account.setMtime(updateTime);
            account.setTxid(nextedTxId);
            selectOrder.setMtime(updateTime);
            selectOrder.setTxid(nextedTxId);
            selectedPosition.setMtime(updateTime);
            selectedPosition.setTxid(nextedTxId);
            userData.mergerPosition(selectedPosition);
            messageItems.add(new AsyncMessageItem(AsyncMessageItem.Type.ACCOUNT.getValue(), Lists.newArrayList(account)));
            messageItems.add(new AsyncMessageItem(AsyncMessageItem.Type.ORDER.getValue(), Lists.newArrayList(selectOrder)));
            messageItems.add(new AsyncMessageItem(AsyncMessageItem.Type.POSITION.getValue(), Lists.newArrayList(selectedPosition)));
            messageItems.add(new AsyncMessageItem(AsyncMessageItem.Type.TRADE_ORDER.getValue(), Lists.newArrayList(tradeOrder)));
        }
    }

    /**
     * 卖出平多
     */
    private void matchSellClose(long nextedTxId, Order selectOrder, UserData userData, TradeOrder tradeOrder, AsyncMessageItems messageItems, Account account) {
        Position position = userData.getPosition(selectOrder.getPositionId());
        if (position == null) {
            return;
        }

        if (position.getPendingCloseVolume().compareTo(tradeOrder.getVolume()) < 0) {
            return;
        }

        BigDecimal totalVolume = position.getVolume().add(position.getPendingCloseVolume()); // 总持仓
        tradeOrder.setPositionBeforeVolume(totalVolume);
        BigDecimal beforeValue = tradeOrder.getVolume().multiply(position.getOpenPrice());
        BigDecimal afterValue = tradeOrder.getVolume().multiply(tradeOrder.getPrice());
        BigDecimal realizedAmount = afterValue.subtract(beforeValue); // 当前匹配交易的盈亏
        selectOrder.setRealizedAmount(selectOrder.getRealizedAmount().add(realizedAmount)); // 已经实现盈亏
        selectOrder.setMtime(new Date());
        selectOrder.setTxid(nextedTxId);

        position.setPendingCloseVolume(position.getPendingCloseVolume().subtract(tradeOrder.getVolume()));
        account.setAvailableBalance(account.getAvailableBalance().add(realizedAmount));
        position.setRealizedAmount(position.getRealizedAmount().add(realizedAmount));

        BigDecimal closedAmount = position.getCloseVolume().multiply(position.getClosePrice());
        BigDecimal dealCloseAmount = tradeOrder.getVolume().multiply(tradeOrder.getPrice());
        position.setClosePrice(dealCloseAmount.add(closedAmount).divide(position.getCloseVolume().add(tradeOrder.getVolume()), 16, RoundingMode.DOWN));
        position.setCloseVolume(position.getCloseVolume().add(tradeOrder.getVolume()));

        BigDecimal reduceMargin;
        if (position.getVolume().add(position.getPendingCloseVolume()).compareTo(BigDecimal.ZERO) == 0) { // 完全平仓
            reduceMargin = position.getHoldAmount(); // 保证金
            position.setStatus(Position.Status.CLOSE.value());
            userData.removePosition(position);
        } else { // 部分平仓
            // 当前平仓数量 / 平仓前总持仓 = ${减仓保证金} / 持仓保证金
            reduceMargin = position.getHoldAmount().multiply(tradeOrder.getVolume()).divide(totalVolume, 16, RoundingMode.DOWN);
        }
        position.setHoldAmount(position.getHoldAmount().subtract(reduceMargin));
        account.setAvailableBalance(account.getAvailableBalance().add(reduceMargin));
        if (position.getPositionType() == Position.PositionType.CROSS.value()) { // 全仓
            account.setCrossMarginFrozen(account.getCrossMarginFrozen().subtract(reduceMargin));
        } else { // 逐仓
            account.setIsolatedMarginFrozen(account.getIsolatedMarginFrozen().subtract(reduceMargin));
        }
        account.setMtime(new Date());
        account.setTxid(nextedTxId);
        position.setMtime(new Date());
        position.setTxid(nextedTxId);

        selectOrder.setDealVolume(selectOrder.getDealVolume().add(tradeOrder.getVolume()));
        selectOrder.setDealAmount(selectOrder.getDealAmount().add(tradeOrder.getPrice().multiply(tradeOrder.getVolume())));
        selectOrder.setMtime(new Date());
        selectOrder.setTxid(nextedTxId);
        tradeOrder.setMtime(new Date());
        tradeOrder.setStatus(TradeOrder.Status.SUCCESS.value());
        tradeOrder.setTxid(nextedTxId);
        messageItems.add(new AsyncMessageItem(AsyncMessageItem.Type.TRADE_ORDER.getValue(), Lists.newArrayList(tradeOrder)));
        messageItems.add(new AsyncMessageItem(AsyncMessageItem.Type.ACCOUNT.getValue(), Lists.newArrayList(account)));
        messageItems.add(new AsyncMessageItem(AsyncMessageItem.Type.ORDER.getValue(), Lists.newArrayList(selectOrder)));
        messageItems.add(new AsyncMessageItem(AsyncMessageItem.Type.POSITION.getValue(), Lists.newArrayList(position)));
    }

    /**
     * 卖出开空
     */
    private void matchSellOpen(long nextedTxId, Order selectOrder, UserData userData, TradeOrder tradeOrder, AsyncMessageItems messageItems, Account account) {
        Position selectedPosition = userData.getPosition(selectOrder.getPositionId());
        long positionId;
        boolean addPosition = false;
        if (selectedPosition == null) {
            addPosition = true;
            selectedPosition = newPosition(selectOrder);
            if (selectOrder.getPositionId() == null || selectOrder.getPositionId() <= 0) {
                positionId = positionIdGenerator.nextId();
            } else {
                positionId = selectOrder.getPositionId();
            }
        } else {
            if (StringUtils.equals(selectOrder.getSide(), selectedPosition.getSide())) {
                logger.error("position_side error {},\t{}", selectOrder.getId(), selectOrder.getPositionId());
                return;
            }
            positionId = selectedPosition.getId();
        }


        tradeOrder.setPositionBeforeVolume(selectedPosition.getVolume());
        BigDecimal dealAmount = tradeOrder.getVolume().multiply(tradeOrder.getPrice());
        BigDecimal margin = dealAmount.divide(new BigDecimal(selectOrder.getLeverageLevel()), 16, RoundingMode.UP);// 保证金 = 成交额 / 杠杆倍数
        selectOrder.setDealAmount(selectOrder.getDealAmount().add(dealAmount));
        selectOrder.setDealVolume(selectOrder.getDealVolume().add(tradeOrder.getVolume()));

        Position.PositionType positionType = Position.PositionType.fromValue(selectOrder.getPositionType());
        if (positionType == Position.PositionType.CROSS) {
            account.setCrossMarginFrozen(account.getCrossMarginFrozen().add(margin));
        } else {
            account.setIsolatedMarginFrozen(account.getIsolatedMarginFrozen().add(margin));
        }
        account.setOrderFrozen(account.getOrderFrozen().subtract(margin)); // 调整创建订单的保证金
        selectOrder.setMargin(selectOrder.getMargin().subtract(margin));
        if (tradeOrder.isFullMatch()) {
            selectOrder.setStatus(Order.OrderStatus.COMPLETED.value());
            BigDecimal bakMargin = selectOrder.getMargin(); // 归还剩余保证金
            account.setAvailableBalance(account.getAvailableBalance().add(bakMargin));
        } else {
            selectOrder.setStatus(Order.OrderStatus.PART_DEAL.value());
        }
        BigDecimal positionValue = selectedPosition.getVolume().multiply(selectedPosition.getVolume());
        positionValue = positionValue.add(dealAmount);
        BigDecimal openPrice = positionValue.divide(selectedPosition.getVolume().add(tradeOrder.getVolume()), 16, RoundingMode.DOWN);
        selectedPosition.setVolume(selectedPosition.getVolume().add(tradeOrder.getVolume()));
        selectedPosition.setHoldAmount(selectedPosition.getHoldAmount().add(margin));
        selectedPosition.setOpenPrice(openPrice);

        selectedPosition.setLeverageLevel(selectOrder.getLeverageLevel());

        account.setMtime(new Date());
        account.setTxid(nextedTxId);
        selectedPosition.setTxid(nextedTxId);
        selectedPosition.setId(positionId);
        selectOrder.setMtime(new Date());
        selectOrder.setTxid(nextedTxId);
        if (addPosition) {
            userData.mergerPosition(selectedPosition);
        }
        tradeOrder.setPositionAfterVolume(selectedPosition.getVolume());
        tradeOrder.setStatus(TradeOrder.Status.SUCCESS.value());
        messageItems.add(new AsyncMessageItem(AsyncMessageItem.Type.ACCOUNT.getValue(), Lists.newArrayList(account)));
        messageItems.add(new AsyncMessageItem(AsyncMessageItem.Type.POSITION.getValue(), Lists.newArrayList(selectedPosition)));
        messageItems.add(new AsyncMessageItem(AsyncMessageItem.Type.ORDER.getValue(), Lists.newArrayList(selectOrder)));
        messageItems.add(new AsyncMessageItem(AsyncMessageItem.Type.TRADE_ORDER.getValue(), Lists.newArrayList(tradeOrder)));
    }


    private Position newPosition(Order selectOrder) {
        Position selectedPosition = new Position();
        selectedPosition.setUid(selectOrder.getUid());
        selectedPosition.setSymbol("BTC-USDT");
        selectedPosition.setMarketId(1);
        selectedPosition.setSide(selectOrder.getSide());
        selectedPosition.setVolume(BigDecimal.ZERO);
        selectedPosition.setClosePrice(BigDecimal.ZERO);
        selectedPosition.setPendingCloseVolume(BigDecimal.ZERO);
        selectedPosition.setFee(BigDecimal.ZERO);
        selectedPosition.setOpenPrice(BigDecimal.ZERO);
        selectedPosition.setHoldAmount(BigDecimal.ZERO);
        selectedPosition.setRealizedAmount(BigDecimal.ZERO);
        selectedPosition.setStatus(Position.Status.OPEN.value());
        selectedPosition.setCtime(new Date());
        selectedPosition.setPositionType(selectOrder.getPositionType());
        selectedPosition.setSide(selectOrder.getSide());
        return selectedPosition;
    }

    /**
     * 买入平空
     */
    private void matchBuyClose(long nextedTxId, Order selectOrder, UserData userData, TradeOrder tradeOrder, AsyncMessageItems messageItems, Account account) {
        Position position = userData.getPosition(selectOrder.getPositionId());
        if (position == null) {
            return;
        }
        if (position.getPendingCloseVolume().compareTo(tradeOrder.getVolume()) < 0) {
            return;
        }

        tradeOrder.setPositionBeforeVolume(position.getVolume().add(position.getPendingCloseVolume()));

        BigDecimal tradeValue = tradeOrder.getVolume().multiply(tradeOrder.getPrice());
        BigDecimal beforeValue = position.getOpenPrice().multiply(tradeOrder.getVolume());

        BigDecimal closeValue = position.getCloseVolume().multiply(position.getClosePrice());
        BigDecimal closePrice = closeValue.add(tradeValue).divide(tradeOrder.getVolume().add(position.getCloseVolume()), 16, RoundingMode.DOWN);
        position.setClosePrice(closePrice);
        position.setCloseVolume(position.getCloseVolume().add(tradeOrder.getVolume()));
        BigDecimal realizedAmount = beforeValue.subtract(tradeValue);
        // 归还保证金    持仓前保证金  / 持仓前数量  =  持仓后保证金 / 持仓后数量
        BigDecimal beforeVolume = position.getVolume().add(position.getPendingCloseVolume()); // 持仓前数量
        BigDecimal afterVolume = beforeVolume.subtract(tradeOrder.getVolume());  // 持仓后数量
        BigDecimal margin = position.getHoldAmount().multiply(afterVolume).divide(beforeVolume, 16, RoundingMode.DOWN); // 平仓后的保证金
        BigDecimal backMargin = position.getHoldAmount().subtract(margin); // 需要归还的保证金
        position.setHoldAmount(margin);
        account.setAvailableBalance(account.getAvailableBalance().add(backMargin));
        if (position.getPositionType() == Position.PositionType.CROSS.value()) {
            account.setCrossMarginFrozen(account.getCrossMarginFrozen().subtract(backMargin));
        } else {
            account.setIsolatedMarginFrozen(account.getIsolatedMarginFrozen().subtract(backMargin));
        }

        if (position.getVolume().compareTo(BigDecimal.ZERO) == 0) { // 全部平仓后,归还所有保证金
            position.setStatus(Position.Status.CLOSE.value());
            BigDecimal holdAmount = position.getHoldAmount();
            account.setAvailableBalance(account.getAvailableBalance().add(holdAmount));
            if (position.getPositionType() == Position.PositionType.CROSS.value()) {
                account.setCrossMarginFrozen(account.getCrossMarginFrozen().subtract(holdAmount));
            } else {
                account.setIsolatedMarginFrozen(account.getIsolatedMarginFrozen().subtract(holdAmount));
            }
        }
        account.setAvailableBalance(account.getAvailableBalance().add(realizedAmount));
        selectOrder.setRealizedAmount(selectOrder.getRealizedAmount().add(realizedAmount));
        position.setRealizedAmount(position.getRealizedAmount().add(realizedAmount));

        position.setPendingCloseVolume(position.getPendingCloseVolume().subtract(tradeOrder.getVolume()));
        if (tradeOrder.isFullMatch()) {
            selectOrder.setStatus(Order.OrderStatus.COMPLETED.value());
            selectOrder.setCompletedTime(new Date());
        } else {
            selectOrder.setStatus(Order.OrderStatus.PART_DEAL.value());
        }
        if (tradeOrder.isFullMatch()) {
            selectOrder.setStatus(Order.OrderStatus.COMPLETED.value());
            selectOrder.setCompletedTime(new Date());
        } else {
            selectOrder.setStatus(Order.OrderStatus.PART_DEAL.value());
        }

        account.setMtime(new Date());
        account.setTxid(nextedTxId);
        position.setMtime(new Date());
        position.setTxid(nextedTxId);
        selectOrder.setMtime(new Date());
        selectOrder.setTxid(nextedTxId);
        tradeOrder.setMtime(new Date());
        tradeOrder.setTxid(nextedTxId);
        tradeOrder.setPositionAfterVolume(position.getVolume());

        selectOrder.setDealVolume(selectOrder.getDealVolume().add(tradeOrder.getVolume()));
        selectOrder.setDealAmount(selectOrder.getDealAmount().add(tradeValue));

        messageItems.add(new AsyncMessageItem(AsyncMessageItem.Type.ORDER.getValue(), Lists.newArrayList(selectOrder)));
        messageItems.add(new AsyncMessageItem(AsyncMessageItem.Type.TRADE_ORDER.getValue(), Lists.newArrayList(tradeOrder)));
        messageItems.add(new AsyncMessageItem(AsyncMessageItem.Type.ACCOUNT.getValue(), Lists.newArrayList(account)));
        messageItems.add(new AsyncMessageItem(AsyncMessageItem.Type.POSITION.getValue(), Lists.newArrayList(position)));
    }

    /**
     * 卖出
     */
    private void matchSell(long nextedTxId, Order selectOrder, UserData userData, TradeOrder tradeOrder, AsyncMessageItems messageItems, Account account) {
        if (selectOrder.isOpenPosition()) {
            matchSellOpen(nextedTxId, selectOrder, userData, tradeOrder, messageItems, account);
        } else {
            matchSellClose(nextedTxId, selectOrder, userData, tradeOrder, messageItems, account);
        }
    }

    /**
     * 订单成交
     *
     * @param userData   用户数据
     * @param tradeOrder 成交订单
     * @return 需要更新到数据库的数据
     */
    public AsyncMessageItems marchOrder(UserData userData, TradeOrder tradeOrder) {
        logger.info("marchOrder : {}", JSONObject.toJSONString(tradeOrder));
        if (tradeOrder.getVolume() == null || tradeOrder.getVolume().compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        LinkedList<Order> orders = userData.getOrders();
        Account account = userData.getUSDTAccount();
        AsyncMessageItems messageItems = new AsyncMessageItems();
        Iterator<Order> iterator = orders.iterator();
        Order selectOrder = null;
        while (iterator.hasNext()) {
            Order order = iterator.next();
            if (Objects.equals(order.getId(), tradeOrder.getOrderId())) {
                selectOrder = order;
                if (tradeOrder.isFullMatch()) {
                    iterator.remove();
                }
                break;
            }
        }
        if (selectOrder == null) {
            logger.error("marchOrder_order_not_found : {},\t{}", tradeOrder.getOrderId(), tradeOrder.getMatchId());
            return messageItems;
        }
        if (tradeOrder.isProcessing()) {
            long nextedTxId = nextTxId();
            if (selectOrder.isBuy()) {
                matchBuy(nextedTxId, selectOrder, userData, tradeOrder, messageItems, account);
            } else {
                matchSell(nextedTxId, selectOrder, userData, tradeOrder, messageItems, account);
            }
        }

        /**
         * 检查是否有爆仓
         */
        if (selectOrder.getPositionId() != null && selectOrder.getPositionId() > 0) {
            Position position = userData.getPosition(selectOrder.getPositionId());
            if (position != null) {
                checkSendLiqOrder(userData, position);
            }
        }

        return messageItems;
    }


    /**
     * 价格波动，检查是否有爆仓
     *
     * @param dataContext 当前队列的所有用户
     * @return 涉及到的爆仓订单
     */
    public void liquidation(DataContext dataContext, Map<Integer, TradePrice> prices, Consumer<AsyncMessageItems> consumer) {
        long nextedTxId = 0;
        for (Map.Entry<Long, UserData> kv : dataContext.entrySet()) {
            UserData userData = kv.getValue();
            LinkedList<Position> positions = userData.getPositions();
            BigDecimal unRealizedAmount = BigDecimal.ZERO;
            LinkedList<Position> crossPositions = new LinkedList<>();
            for (Position position : positions) {
                TradePrice tradePrice = prices.get(position.getMarketId());
                if (tradePrice == null) {
                    continue;
                }
                if (position.isCross()) { // 全仓
                    BigDecimal volume = position.getVolume().add(position.getPendingCloseVolume());
                    if (position.isBuy()) {
                        unRealizedAmount = unRealizedAmount.add(volume.multiply(tradePrice.getPrice().subtract(position.getOpenPrice())));
                    } else {
                        unRealizedAmount = unRealizedAmount.add(volume.multiply(position.getOpenPrice().subtract(tradePrice.getPrice())));
                    }
                } else { // 逐仓
                    if (position.getLiqOrderId() != null && position.getLiqOrderId() > 0) { // 正在执行爆仓
                        continue;
                    }
                    BigDecimal volume = position.getVolume().add(position.getPendingCloseVolume());
                    BigDecimal positionValue = position.getOpenPrice().multiply(volume);
                    BigDecimal minMargin = positionValue.multiply(tradePrice.getMarginRate()); // 最低保证金
                    BigDecimal isolatedUnRealizedAmount;
                    if (position.isBuy()) {
                        isolatedUnRealizedAmount = volume.multiply(tradePrice.getPrice().subtract(position.getOpenPrice()));
                    } else {
                        isolatedUnRealizedAmount = volume.multiply(position.getOpenPrice().subtract(tradePrice.getPrice()));
                    }
                    // 持仓保证金 + 浮动盈亏 <= 最低保证金
                    if (position.getHoldAmount().add(isolatedUnRealizedAmount).compareTo(minMargin) <= 0) { // 爆仓
                        logger.info("liq_position : {},\t{}", position.getId(), JSON.toJSONString(position));
                        AsyncMessageItems messageItems = new AsyncMessageItems();
                        // 1、取消跟该仓位有关的订单
                        // 2、下订单爆仓单子
                        List<Order> orderList = userData.getOrderByPosition(position);
                        nextedTxId = nextedTxId(nextedTxId);
                        boolean hasCancelOrder = false;
                        for (Order order : orderList) {
                            order.setCancelTime(new Date());
                            order.setCancelOrder(1);
                            order.setTxid(nextedTxId);
                            sendToMatch(order); // 向撮合服务发送订单取消，撮合服务取消成功后，执行 exceptionOrder
                            hasCancelOrder = true;
                            messageItems.add(new AsyncMessageItem(AsyncMessageItem.Type.ORDER.getValue(), Lists.newArrayList(order)));
                        }
                        // 市价平仓
                        Order liqOrder = new Order();
                        liqOrder.setTxid(nextedTxId);
                        liqOrder.setCtime(new Date());
                        liqOrder.setMtime(new Date());
                        liqOrder.setId(orderIdGenerator.nextId());
                        liqOrder.setUid(position.getUid());
                        liqOrder.setPositionId(position.getId());
                        liqOrder.setPriceType(Order.PriceType.MARKET.value());
                        liqOrder.setSide(position.isBuy() ? Order.OrderSide.SELL.value : Order.OrderSide.BUY.value);
                        liqOrder.setOpen(Order.OPEN.CLOSE.name());
                        position.setLiqOrderId(liqOrder.getId());
                        position.setPendingCloseVolume(liqOrder.getVolume());
                        position.setVolume(BigDecimal.ZERO);
                        position.setMtime(new Date());
                        userData.mergerOrder(liqOrder);
                        if (!hasCancelOrder) { // 没有需要取消的订单，直接发送爆仓单
                            liqOrder.setVolume(position.getVolume().add(position.getPendingCloseVolume()));
                            sendToMatch(liqOrder);
                        }
                        messageItems.add(new AsyncMessageItem(AsyncMessageItem.Type.ORDER.getValue(), Lists.newArrayList(liqOrder)));
                        messageItems.add(new AsyncMessageItem(AsyncMessageItem.Type.POSITION.getValue(), Lists.newArrayList(position)));
                        consumer.accept(messageItems);
                    }
                }
            }
        }
    }


    private void checkSendLiqOrder(UserData userData, Position position) {
        if (position.getLiqOrderId() != null && position.getLiqOrderId() > 0) {
            List<Order> orderList = userData.getOrderByPosition(position);
            if (!CollectionUtils.isEmpty(orderList) && orderList.size() == 1 && Objects.equals(position.getLiqOrderId(), orderList.get(0).getId())) {
                sendToMatch(orderList.get(0));
            }
        }
    }

    private long nextedTxId(long nextedTxId) {
        return nextedTxId <= 0 ? nextTxId() : nextedTxId;
    }

    public AsyncMessageItems exceptionOrder(UserData userData, Order cancelOrder) {
        Iterator<Order> iterator = userData.getOrders().iterator();
        Order selectedOrder = null;
        while (iterator.hasNext()) {
            Order next = iterator.next();
            if (Objects.equals(next.getId(), cancelOrder.getId())) {
                selectedOrder = next;
                iterator.remove();
                break;
            }
        }
        if (selectedOrder == null) {
            return null;
        }
        AsyncMessageItems messageItems = new AsyncMessageItems();
        Account account = userData.getUSDTAccount();
        if (selectedOrder.isOpenPosition()) {
            BigDecimal margin = selectedOrder.getMargin();
            Position.PositionType positionType = Position.PositionType.fromValue(selectedOrder.getPositionType());
            account.setAvailableBalance(account.getAvailableBalance().add(margin));
            if (positionType == Position.PositionType.CROSS) {
                account.setCrossMarginFrozen(account.getCrossMarginFrozen().subtract(margin));
            } else {
                account.setIsolatedMarginFrozen(account.getIsolatedMarginFrozen().subtract(margin));
            }
            selectedOrder.setCancelTime(new Date());
            selectedOrder.setCancelOrder(1);
            if (selectedOrder.getDealAmount().compareTo(BigDecimal.ZERO) > 0) {
                selectedOrder.setStatus(Order.OrderStatus.PART_CANCEL.value());// 部分成交取消
            } else {
                selectedOrder.setStatus(Order.OrderStatus.EXCEPTION.value());
            }
            selectedOrder.setCompletedTime(new Date());
            long nextedTxId = nextTxId();
            selectedOrder.setTxid(nextedTxId);
            account.setMtime(new Date());
            account.setTxid(nextedTxId);
            messageItems.add(new AsyncMessageItem(AsyncMessageItem.Type.ACCOUNT.getValue(), Lists.newArrayList(account)));
            messageItems.add(new AsyncMessageItem(AsyncMessageItem.Type.ORDER.getValue(), Lists.newArrayList(selectedOrder)));
        } else { // 平仓单子取消
            long nextedTxId = nextTxId();
            BigDecimal volume = selectedOrder.getVolume();
            Order finalSelectedOrder = selectedOrder;
            Position position = userData.getPositions().stream().filter(p -> p.getId().equals(finalSelectedOrder.getPositionId())).findFirst().orElse(null);
            if (position == null) {
                return messageItems;
            }
            if (position.getPendingCloseVolume().compareTo(volume) < 0) {
                return messageItems;
            }
            position.setVolume(position.getVolume().add(volume));
            position.setPendingCloseVolume(position.getPendingCloseVolume().subtract(volume));
            position.setMtime(new Date());
            selectedOrder.setMtime(new Date());
            position.setTxid(nextedTxId);
            selectedOrder.setTxid(nextedTxId);
            messageItems.add(new AsyncMessageItem(AsyncMessageItem.Type.POSITION.getValue(), Lists.newArrayList(position)));
            messageItems.add(new AsyncMessageItem(AsyncMessageItem.Type.ORDER.getValue(), Lists.newArrayList(selectedOrder)));
        }
        if (selectedOrder.getPositionId() != null && selectedOrder.getPositionId() > 0) {
            Position position = userData.getPosition(selectedOrder.getPositionId());
            if (position != null) {
                checkSendLiqOrder(userData, position);
            }
        }
        return messageItems;
    }

}
