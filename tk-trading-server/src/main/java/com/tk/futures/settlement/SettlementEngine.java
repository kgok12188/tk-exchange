package com.tk.futures.settlement;

import com.alibaba.fastjson2.JSONObject;
import com.tk.futures.model.PersistenceBatchList;
import com.tk.futures.model.UserTradingBook;
import com.tk.futures.slot.SlotContext;
import com.tk.protocol.dto.FinishOrder;
import com.tk.protocol.dto.Ticket;
import com.tk.protocol.dto.TradingSettle;
import com.tx.common.entity.Order;
import com.tx.common.entity.TradeOrder;
import com.tx.common.message.PersistenceBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 结算引擎：以 TradingSettle 为输入，在 UserTradingBook 上应用变更，并生成 AsyncMessageItem 列表。
 * 当前实现仅完成结构与入口，具体业务计算可在后续迭代中补充。
 */
@Service
public class SettlementEngine {

    private static final Logger logger = LoggerFactory.getLogger(SettlementEngine.class);

    public PersistenceBatchList apply(Long uid, JSONObject data, SlotContext slotContext) {
        if (uid == null || uid <= 0) {
            return new PersistenceBatchList();
        }
        if (data == null) {
            logger.warn("SettlementEngine.apply called with null data, uid={}", uid);
            return new PersistenceBatchList();
        }
        TradingSettle settle = data.to(TradingSettle.class);
        if (settle == null) {
            logger.warn("SettlementEngine.apply failed to parse TradingSettle, uid={}", uid);
            return new PersistenceBatchList();
        }
        UserTradingBook book = slotContext.getOrCreateBook(uid);
        PersistenceBatchList items = new PersistenceBatchList();
        try {
            applyFinishOrders(settle, book, items);
            applyTickets(settle, book, items);
            book.commit();
        } catch (Exception e) {
            logger.error("SettlementEngine.apply error, uid={}", uid, e);
            book.rollback();
        }
        logger.debug("apply TradingSettle for uid={}, finishOrders={}, tickets={}, events={}",
                uid,
                settle.getFinishOrders() != null ? settle.getFinishOrders().size() : 0,
                settle.getTickets() != null ? settle.getTickets().size() : 0,
                items.size());
        return items;
    }

    private void applyFinishOrders(TradingSettle settle, UserTradingBook book, PersistenceBatchList items) {
        if (settle.getFinishOrders() == null) {
            return;
        }
        for (FinishOrder fo : settle.getFinishOrders()) {
            Long orderId = fo.getOrderId();
            if (orderId == null) {
                continue;
            }
            Order order = book.getOrderToBuild(orderId);
            if (order == null) {
                continue;
            }
            // 更新订单状态
            switch (fo.getStatus()) {
                case COMPLETED -> order.setStatus(Order.OrderStatus.COMPLETED.value());
                case PART_CANCEL -> order.setStatus(Order.OrderStatus.PART_CANCEL.value());
                case CANCEL -> order.setStatus(Order.OrderStatus.CANCEL.value());
                case EXCEPTION -> order.setStatus(Order.OrderStatus.EXCEPTION.value());
                default -> {
                }
            }
            // 根据 leaveVolume/leaveAmount 推导成交进度（仅现货简化）
            if (fo.getLeaveVolume() != null && order.getVolume() != null) {
                order.setDealVolume(order.getVolume().subtract(fo.getLeaveVolume()));
            }
            if (fo.getLeaveAmount() != null && order.getAmount() != null) {
                order.setDealAmount(order.getAmount().subtract(fo.getLeaveAmount()));
            }
            PersistenceBatch orderItem = new PersistenceBatch();
            orderItem.setType(PersistenceBatch.Type.ORDER.getValue());
            orderItem.setMessages(java.util.List.of(order));
            items.add(orderItem);
        }
    }

    private void applyTickets(TradingSettle settle, UserTradingBook book, PersistenceBatchList items) {
        if (settle.getTickets() == null) {
            return;
        }
        for (Ticket ticket : settle.getTickets()) {
            Long orderId = ticket.getOrderId();
            if (orderId == null) {
                continue;
            }
            Order order = book.getOrderToBuild(orderId);
            if (order == null) {
                continue;
            }
            TradeOrder tradeOrder = new TradeOrder();
            tradeOrder.setUid(ticket.getUid());
            tradeOrder.setMatchId(ticket.getOrderReqOffset());
            tradeOrder.setOrderId(orderId);
            tradeOrder.setSide(order.getSide());
            tradeOrder.setPrice(ticket.getPrice());
            tradeOrder.setVolume(ticket.getVolume());
            tradeOrder.setFee(java.math.BigDecimal.ZERO);
            tradeOrder.setRole(ticket.isTaker() ? TradeOrder.Role.TAKER.value() : TradeOrder.Role.MAKER.value());
            tradeOrder.setStatus(TradeOrder.Status.SUCCESS.value());
            PersistenceBatch tradeItem = new PersistenceBatch();
            tradeItem.setType(PersistenceBatch.Type.TRADE_ORDER.getValue());
            tradeItem.setMessages(java.util.List.of(tradeOrder));
            items.add(tradeItem);
        }
    }
}

