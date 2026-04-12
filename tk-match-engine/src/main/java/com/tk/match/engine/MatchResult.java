package com.tk.match.engine;

import com.tk.protocol.dto.FinishOrder;
import com.tk.protocol.dto.TradeOrder;
import lombok.Getter;

import java.util.Collections;
import java.util.List;

/**
 * Result of one match/cancel step: trades and finish orders.
 */
@Getter
public final class MatchResult {

    private final List<TradeOrder> trades;
    private final List<FinishOrder> finishOrders;

    MatchResult(List<TradeOrder> trades, List<FinishOrder> finishOrders) {
        this.trades = trades != null ? trades : Collections.emptyList();
        this.finishOrders = finishOrders != null ? finishOrders : Collections.emptyList();
    }

    public static MatchResult of(List<TradeOrder> trades, List<FinishOrder> finishOrders) {
        return new MatchResult(trades, finishOrders);
    }

}
