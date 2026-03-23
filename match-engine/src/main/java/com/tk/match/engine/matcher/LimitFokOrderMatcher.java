package com.tk.match.engine.matcher;

import com.tk.match.engine.BookOrder;
import com.tk.match.engine.MatchResult;
import com.tk.match.engine.OrderBook;
import com.tk.match.engine.PriceLevel;
import com.tk.protocol.dto.FinishStatus;
import com.tk.protocol.dto.RejectReason;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/**
 * LIMIT + FOK: fill completely now, otherwise reject without book mutation.
 */
public class LimitFokOrderMatcher extends LimitOrderMatcher {

    public LimitFokOrderMatcher(OrderBook orderBook) {
        super(orderBook);
    }

    @Override
    public MatchResult match(BookOrder takerOrder, long orderReqOffset) {
        if (!canFullyFillNow(takerOrder)) {
            return MatchResult.of(Collections.emptyList(), List.of(
                    MatchSupport.finishOrder(
                            takerOrder,
                            FinishStatus.REJECT,
                            takerOrder.getRemainingVolume(),
                            RejectReason.FOK_NOT_FILLABLE
                    )
            ));
        }
        return super.match(takerOrder, orderReqOffset);
    }

    private boolean canFullyFillNow(BookOrder takerOrder) {
        OppositeSideWalk walk = OppositeSideWalk.forTaker(takerOrder);
        long takerTicks = takerOrder.getPriceTicks();
        BigDecimal remainingToFill = takerOrder.getRemainingVolume();

        Long oppositeTicks = walk.firstPrice(orderBook);
        while (oppositeTicks != null && remainingToFill.compareTo(BigDecimal.ZERO) > 0) {
            if (!walk.canCrossSpread(takerTicks, oppositeTicks)) {
                break;
            }
            PriceLevel level = walk.level(orderBook, oppositeTicks);
            if (level != null) {
                remainingToFill = remainingToFill.subtract(level.totalRemainingVolume());
                if (remainingToFill.compareTo(BigDecimal.ZERO) <= 0) {
                    return true;
                }
            }
            oppositeTicks = walk.nextOppositePrice(orderBook, oppositeTicks);
        }
        return false;
    }
}

