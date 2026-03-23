package com.tk.match.engine.matcher;

import com.tk.match.engine.BookOrder;
import com.tk.match.engine.MatchResult;
import com.tk.match.engine.OrderBook;
import com.tk.protocol.dto.FinishOrder;
import com.tk.protocol.dto.FinishStatus;
import com.tk.protocol.dto.RejectReason;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/**
 * LIMIT_MAKER (post-only) order: must rest on book as maker. If it would cross the spread, reject entire order.
 */
public class LimitMakerOrderMatcher extends LimitOrderMatcher {

    public LimitMakerOrderMatcher(OrderBook orderBook) {
        super(orderBook);
    }

    @Override
    public MatchResult match(BookOrder takerOrder, long orderReqOffset) {
        if (wouldCross(orderBook, takerOrder)) {
            // taker 尚未 addToBook，无需从簿中移除
            BigDecimal leaveVolume = takerOrder.getRemainingVolume() != null ? takerOrder.getRemainingVolume() : BigDecimal.ZERO;
            FinishOrder fo = MatchSupport.finishOrder(takerOrder, FinishStatus.POST_ONLY_REJECT, leaveVolume, RejectReason.POST_ONLY_WOULD_CROSS);
            return MatchResult.of(Collections.emptyList(), List.of(fo));
        }
        orderBook.addToBook(takerOrder);
        return MatchResult.of(Collections.emptyList(), Collections.emptyList());
    }

    /**
     * True if order would immediately match: buy price >= best ask, or sell price <= best bid.
     */
    private boolean wouldCross(OrderBook book, BookOrder order) {
        if (order.isSideBuy()) {
            Long bestAsk = book.firstAskPriceTicks();
            return bestAsk != null && order.getPriceTicks() >= bestAsk;
        }
        Long bestBid = book.firstBidPriceTicks();
        return bestBid != null && order.getPriceTicks() <= bestBid;
    }
}
