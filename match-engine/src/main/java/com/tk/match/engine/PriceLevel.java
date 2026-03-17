package com.tk.match.engine;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single price level in the order book: FIFO queue of orders at the same price.
 * Implemented as LinkedHashMap (key=seq) so insertion order = time priority; remove by seq is O(1).
 */
public final class PriceLevel {

    private final Map<Long, BookOrder> orders = new LinkedHashMap<>(8);
    /** Precomputed sum of remainingVolume; updated on addLast / remove / subtractVolume. */
    private BigDecimal totalRemaining = BigDecimal.ZERO;

    /**
     * Sum of {@link BookOrder#getRemainingVolume()} at this level. O(1).
     */
    public BigDecimal totalRemainingVolume() {
        return totalRemaining;
    }

    public boolean isEmpty() {
        return orders.isEmpty();
    }

    /**
     * First order by time (oldest seq).
     */
    public BookOrder peekFirst() {
        if (orders.isEmpty()) return null;
        return orders.entrySet().iterator().next().getValue();
    }

    /**
     * Add order at tail (insertion order = seq order).
     */
    public void addLast(BookOrder order) {
        orders.put(order.getSeq(), order);
        totalRemaining = totalRemaining.add(order.getRemainingVolume());
    }

    /**
     * Remove order by seq (e.g. cancel). O(1).
     */
    public void remove(long seq) {
        BookOrder o = orders.remove(seq);
        if (o != null) {
            totalRemaining = totalRemaining.subtract(o.getRemainingVolume());
        }
    }

    /**
     * Called when a maker order at this level has its remainingVolume reduced by {@code delta} (e.g. after a fill).
     * Must be invoked in sync with {@link BookOrder#setRemainingVolume(BigDecimal)}.
     */
    public void subtractVolume(BigDecimal delta) {
        if (delta != null && delta.compareTo(BigDecimal.ZERO) > 0) {
            totalRemaining = totalRemaining.subtract(delta);
        }
    }
}
