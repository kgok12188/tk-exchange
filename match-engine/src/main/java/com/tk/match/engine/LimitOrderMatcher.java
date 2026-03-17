package com.tk.match.engine;

import com.tk.protocol.dto.FinishOrder;
import com.tk.protocol.dto.FinishStatus;
import com.tk.protocol.dto.TradeOrder;

import java.math.BigDecimal;
import java.util.*;

/**
 * LIMIT order: match crossing the spread first, then add remainder to the book.
 */
public final class LimitOrderMatcher implements OrderMatcher {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    @Override
    public MatchResult match(OrderBook book, BookOrder takerOrder, long orderReqOffset) {
        List<TradeOrder> trades = new ArrayList<>(4);
        List<FinishOrder> finishes = new ArrayList<>(4);
        TreeMap<BigDecimal, PriceLevel> opposite = takerOrder.isSideBuy() ? book.getSellSide() : book.getBuySide();
        Iterator<Map.Entry<BigDecimal, PriceLevel>> it = opposite.entrySet().iterator();

        while (it.hasNext() && takerOrder.getRemainingVolume().compareTo(ZERO) > 0) {
            Map.Entry<BigDecimal, PriceLevel> e = it.next();
            BigDecimal price = e.getKey();
            if (takerOrder.isSideBuy() && price.compareTo(takerOrder.getPrice()) > 0) break;
            if (!takerOrder.isSideBuy() && price.compareTo(takerOrder.getPrice()) < 0) break;

            PriceLevel level = e.getValue();
            while (!level.isEmpty() && takerOrder.getRemainingVolume().compareTo(ZERO) > 0) {
                BookOrder makerOrder = level.peekFirst();
                BigDecimal fill = takerOrder.getRemainingVolume().min(makerOrder.getRemainingVolume());
                takerOrder.setRemainingVolume(takerOrder.getRemainingVolume().subtract(fill));
                makerOrder.setRemainingVolume(makerOrder.getRemainingVolume().subtract(fill));
                level.subtractVolume(fill);
                long index = trades.size();
                trades.add(OrderBook.buildTrade(index, orderReqOffset, price, fill, takerOrder, makerOrder));
                if (makerOrder.getRemainingVolume().compareTo(ZERO) <= 0) {
                    book.getOrdersById().remove(makerOrder.getOrderId());
                    level.remove(makerOrder.getSeq());
                    finishes.add(OrderBook.finishOrder(makerOrder, FinishStatus.COMPLETED, ZERO));
                }
            }
            if (level.isEmpty()) it.remove();
        }

        if (takerOrder.getRemainingVolume().compareTo(ZERO) > 0) {
            book.addToBook(takerOrder);
        } else {
            finishes.add(OrderBook.finishOrder(takerOrder, FinishStatus.COMPLETED, ZERO));
        }
        return MatchResult.of(trades, finishes);
    }

}
