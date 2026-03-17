package com.tk.match.engine;

import com.tk.protocol.dto.FinishOrder;
import com.tk.protocol.dto.FinishStatus;
import com.tk.protocol.dto.TradeOrder;

import java.math.BigDecimal;
import java.util.*;

/**
 * MARKET order: match only, do not add to book. Unfilled remainder → PART_CANCEL.
 */
public final class MarketOrderMatcher implements OrderMatcher {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    @Override
    public MatchResult match(OrderBook book, BookOrder order, long orderReqOffset) {
        List<TradeOrder> trades = new ArrayList<>(4);
        List<FinishOrder> finishes = new ArrayList<>(4);
        TreeMap<BigDecimal, PriceLevel> opposite = order.isSideBuy() ? book.getSellSide() : book.getBuySide();
        Iterator<Map.Entry<BigDecimal, PriceLevel>> it = opposite.entrySet().iterator();

        while (it.hasNext() && order.getRemainingVolume().compareTo(ZERO) > 0) {
            Map.Entry<BigDecimal, PriceLevel> e = it.next();
            BigDecimal price = e.getKey();
            PriceLevel level = e.getValue();
            while (!level.isEmpty() && order.getRemainingVolume().compareTo(ZERO) > 0) {
                BookOrder maker = level.peekFirst();
                BigDecimal fill = order.getRemainingVolume().min(maker.getRemainingVolume());
                order.setRemainingVolume(order.getRemainingVolume().subtract(fill));
                maker.setRemainingVolume(maker.getRemainingVolume().subtract(fill));
                level.subtractVolume(fill);
                long index = trades.size();
                trades.add(OrderBook.buildTrade(index, orderReqOffset, price, fill, order, maker));
                if (maker.getRemainingVolume().compareTo(ZERO) <= 0) {
                    book.getOrdersById().remove(maker.getOrderId());
                    level.remove(maker.getSeq());
                    finishes.add(OrderBook.finishOrder(maker, FinishStatus.COMPLETED, ZERO));
                }
            }
            if (level.isEmpty()) it.remove();
        }

        if (order.getRemainingVolume().compareTo(ZERO) <= 0) {
            finishes.add(OrderBook.finishOrder(order, FinishStatus.COMPLETED, ZERO));
        } else {
            finishes.add(OrderBook.finishOrder(order, FinishStatus.PART_CANCEL, order.getRemainingVolume()));
        }
        return MatchResult.of(trades, finishes);
    }
}
