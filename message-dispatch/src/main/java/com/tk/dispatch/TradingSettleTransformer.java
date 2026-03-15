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

        for (TradeOrder t : response.getTrades()) {
            if (t.getBuyUid() != null) {
                Ticket buy = Ticket.builder()
                        .index(t.getIndex())
                        .orderReqOffset(t.getOrderReqOffset())
                        .price(t.getPrice())
                        .volume(t.getVolume() != null ? t.getVolume() : BigDecimal.ZERO)
                        .uid(t.getBuyUid())
                        .orderId(t.getBuyOrderId())
                        .isTaker(Objects.equals(t.getTakerUid(), t.getBuyUid()))
                        .build();
                settles.computeIfAbsent(t.getBuyUid(), u -> TradingSettle.builder().uid(u).shardId(t.getBuyShardId()).finishOrders(new ArrayList<>()).tickets(new ArrayList<>()).build())
                        .getTickets().add(buy);
            }
            if (t.getSellUid() != null) {
                Ticket sell = Ticket.builder()
                        .index(t.getIndex())
                        .orderReqOffset(t.getOrderReqOffset())
                        .price(t.getPrice())
                        .volume(t.getVolume() != null ? t.getVolume() : BigDecimal.ZERO)
                        .uid(t.getSellUid())
                        .orderId(t.getSellOrderId())
                        .isTaker(Objects.equals(t.getTakerUid(), t.getSellUid()))
                        .build();
                settles.computeIfAbsent(t.getSellUid(), u -> TradingSettle.builder().uid(u).shardId(t.getSellShardId()).finishOrders(new ArrayList<>()).tickets(new ArrayList<>()).build())
                        .getTickets().add(sell);
            }
        }

        return settles;
    }
}
