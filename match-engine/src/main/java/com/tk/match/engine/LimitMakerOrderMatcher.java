package com.tk.match.engine;

import com.tk.protocol.dto.FinishOrder;
import com.tk.protocol.dto.FinishStatus;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/**
 * LIMIT_MAKER (post-only) order: must rest on book as maker. If it would cross the spread, reject entire order.
 */
public final class LimitMakerOrderMatcher implements OrderMatcher {

    @Override
    public MatchResult match(OrderBook book, BookOrder order, long orderReqOffset) {
        if (wouldCross(book, order)) {
            book.getOrdersById().remove(order.getOrderId());
            BigDecimal leaveVolume = order.getRemainingVolume() != null ? order.getRemainingVolume() : BigDecimal.ZERO;
            FinishOrder fo = OrderBook.finishOrder(order, FinishStatus.POST_ONLY_REJECT, leaveVolume);
            return MatchResult.of(Collections.emptyList(), List.of(fo));
        }
        book.addToBook(order);
        return MatchResult.of(Collections.emptyList(), Collections.emptyList());
    }

    /**
     * True if order would immediately match: buy price >= best ask, or sell price <= best bid.
     */
    private static boolean wouldCross(OrderBook book, BookOrder order) {
        if (order.isSideBuy()) {
            BigDecimal bestAsk = book.getBestAsk();
            return bestAsk != null && order.getPrice().compareTo(bestAsk) >= 0;
        } else {
            BigDecimal bestBid = book.getBestBid();
            return bestBid != null && order.getPrice().compareTo(bestBid) <= 0;
        }
    }
}
