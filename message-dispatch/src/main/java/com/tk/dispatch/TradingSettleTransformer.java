package com.tk.dispatch;

import com.tk.protocol.dto.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Transforms MatchResponse into per-user TradingSettle (FinishOrders + Tickets).
 */
public final class TradingSettleTransformer {

    private TradingSettleTransformer() {
    }

    /**
     * Build one TradingSettle per affected user from a MatchResponse.
     */
    public static Map<Long, TradingSettle> fromMatchResponse(MatchResponse response) {
        Map<Long, TradingSettle> settles = new HashMap<>();

        if (response == null) return settles;

        for (FinishOrder fo : response.getFinishOrders()) {
            if (fo.getUid() == null) continue;
            settles.computeIfAbsent(fo.getUid(), u -> TradingSettle.builder().uid(u).finishOrders(new ArrayList<>()).tickets(new ArrayList<>()).build())
                    .getFinishOrders().add(fo);
        }

        for (TradeOrder tradeOrder : response.getTrades()) {
            if (tradeOrder.getBuyUid() != null) {
                Ticket buy = Ticket.builder()
                        .index(tradeOrder.getIndex())
                        .orderReqOffset(tradeOrder.getOrderReqOffset())
                        .price(tradeOrder.getPrice())
                        .volume(tradeOrder.getVolume() != null ? tradeOrder.getVolume() : BigDecimal.ZERO)
                        .uid(tradeOrder.getBuyUid())
                        .orderId(tradeOrder.getBuyOrderId())
                        .isTaker(Objects.equals(tradeOrder.getTakerUid(), tradeOrder.getBuyUid()))
                        .build();
                settles.computeIfAbsent(tradeOrder.getBuyUid(), uid -> TradingSettle.builder().uid(uid).shardId(tradeOrder.getBuyShardId()).finishOrders(new ArrayList<>()).tickets(new ArrayList<>()).build())
                        .getTickets().add(buy);
            }
            if (tradeOrder.getSellUid() != null) {
                Ticket sell = Ticket.builder()
                        .index(tradeOrder.getIndex())
                        .orderReqOffset(tradeOrder.getOrderReqOffset())
                        .price(tradeOrder.getPrice())
                        .volume(tradeOrder.getVolume() != null ? tradeOrder.getVolume() : BigDecimal.ZERO)
                        .uid(tradeOrder.getSellUid())
                        .orderId(tradeOrder.getSellOrderId())
                        .isTaker(Objects.equals(tradeOrder.getTakerUid(), tradeOrder.getSellUid()))
                        .build();
                settles.computeIfAbsent(tradeOrder.getSellUid(), uid -> TradingSettle.builder().uid(uid).shardId(tradeOrder.getSellShardId()).finishOrders(new ArrayList<>()).tickets(new ArrayList<>()).build())
                        .getTickets().add(sell);
            }
        }

        return settles;
    }
}
