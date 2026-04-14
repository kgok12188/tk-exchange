package com.tk.match.engine.matcher;

import com.tk.match.engine.BookOrder;
import com.tk.match.engine.MatchResult;
import com.tk.match.engine.OrderBook;
import com.tk.match.engine.PriceLevel;
import com.tk.protocol.dto.FinishOrder;
import com.tk.protocol.dto.FinishStatus;
import com.tk.protocol.dto.TradeOrder;

import java.util.ArrayList;
import java.util.List;

/**
 * LIMIT + IOC: match immediately and cancel remainder, never rest on book.
 */
public class LimitIocOrderMatcher extends LimitOrderMatcher {

    public LimitIocOrderMatcher(OrderBook orderBook) {
        super(orderBook);
    }

    @Override
    public MatchResult match(BookOrder takerOrder, long orderReqOffset) {
        List<TradeOrder> trades = new ArrayList<>(4);
        List<FinishOrder> finishes = new ArrayList<>(4);
        int scale = orderBook.getMatchMarketConfig().getPriceScale();

        OppositeSideWalk walk = OppositeSideWalk.forTaker(takerOrder);
        long takerTicks = takerOrder.getPriceTicks();

        Long oppositeTicks = walk.firstPrice(orderBook);
        while (oppositeTicks != null && takerHasRemainingVolume(takerOrder)) {
            if (!walk.canCrossSpread(takerTicks, oppositeTicks)) {
                break;
            }
            PriceLevel level = walk.level(orderBook, oppositeTicks);
            if (level == null) {
                break;
            }
            boolean continueNextPrice = matchAtPriceLevel(orderBook, takerOrder, orderReqOffset, oppositeTicks, scale, level, trades, finishes);
            walk.removeLevelIfEmpty(orderBook, oppositeTicks);
            if (!continueNextPrice) {
                break;
            }
            oppositeTicks = walk.nextOppositePrice(orderBook, oppositeTicks);
        }

        if (takerHasRemainingVolume(takerOrder)) {
            finishes.add(MatchSupport.finishOrder(takerOrder, FinishStatus.PART_CANCEL, takerOrder.getRemainingVolume()));
        } else {
            finishes.add(MatchSupport.finishOrder(takerOrder, FinishStatus.COMPLETED, java.math.BigDecimal.ZERO));
        }
        return MatchResult.of(trades, finishes);
    }
}

