package com.tk.match.engine.matcher;

import com.tk.match.engine.*;
import com.tk.protocol.dto.FinishOrder;
import com.tk.protocol.dto.FinishStatus;
import com.tk.protocol.dto.RejectReason;
import com.tk.protocol.dto.TradeOrder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * LIMIT order: match crossing the spread first, then add remainder to the book.
 * <p>
 * 在单一对手价位上与 FIFO 连续撮合，直到该档耗尽或 taker 无剩余。
 */
public class LimitOrderMatcher implements OrderMatcher {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    protected final OrderBook orderBook;

    public LimitOrderMatcher(OrderBook orderBook) {
        this.orderBook = orderBook;
    }

    @Override
    public MatchResult match(BookOrder takerOrder, long orderReqOffset) {
        List<TradeOrder> trades = new ArrayList<>(4);
        List<FinishOrder> finishes = new ArrayList<>(4);
        int scale = orderBook.getMarketConfig().getPriceScale();

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
            orderBook.addToBook(takerOrder);
        } else {
            finishes.add(MatchSupport.finishOrder(takerOrder, FinishStatus.COMPLETED, ZERO));
        }
        return MatchResult.of(trades, finishes);
    }

    @Override
    public MatchResult validate(BookOrder taker) {
        if (taker.getPrice() == null || taker.getPrice().compareTo(ZERO) <= 0) {
            return MatchResult.of(Collections.emptyList(), Collections.singletonList(MatchSupport.finishOrder(taker, FinishStatus.REJECT, taker.getRemainingVolume(), RejectReason.INVALID_PRICE)));
        }
        BigDecimal vol = taker.getRemainingVolume();
        if (MarketRules.shouldRejectQuantity(vol, orderBook.getMarketConfig())) {
            return MatchResult.of(Collections.emptyList(), Collections.singletonList(MatchSupport.finishOrder(taker, FinishStatus.REJECT, vol, RejectReason.INVALID_QUANTITY)));
        }
        if (!MarketRules.isPriceCompliant(taker.getPrice(), orderBook.getMarketConfig())) {
            return MatchResult.of(Collections.emptyList(), Collections.singletonList(MatchSupport.finishOrder(taker, FinishStatus.REJECT, vol, RejectReason.PRICE_TICK_INVALID)));
        }
        return null;
    }

    /**
     * @return {@code true} 表示 taker 仍有剩余量，外层应继续扫下一对手价位；{@code false} 表示 taker 已全成，无需再穿价撮合。
     */
    protected boolean matchAtPriceLevel(OrderBook book, BookOrder takerOrder, long orderReqOffset, long oppositeTicks,
                                        int scale, PriceLevel level, List<TradeOrder> trades, List<FinishOrder> finishes) {
        while (!level.isEmpty() && takerHasRemainingVolume(takerOrder)) {
            BookOrder makerOrder = level.peekFirst();
            BigDecimal fill = takerOrder.getRemainingVolume().min(makerOrder.getRemainingVolume());
            makerOrder.deductRemainingVolume(fill);
            level.subtractVolume(fill);
            takerOrder.deductRemainingVolume(fill);
            long index = trades.size();
            trades.add(MatchSupport.buildTrade(index, orderReqOffset, oppositeTicks, scale, fill, takerOrder, makerOrder));
            if (makerOrder.getRemainingVolume().compareTo(ZERO) <= 0) {
                book.removeRestingOrder(makerOrder);
                finishes.add(MatchSupport.finishOrder(makerOrder, FinishStatus.COMPLETED, ZERO));
            }
        }
        return takerHasRemainingVolume(takerOrder);
    }

    protected boolean takerHasRemainingVolume(BookOrder takerOrder) {
        return takerOrder.getRemainingVolume().compareTo(ZERO) > 0;
    }

}

