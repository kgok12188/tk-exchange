package com.tk.match.engine;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single price level in the order book: FIFO queue of orders at the same price.
 * Implemented as LinkedHashMap (key=seq) so insertion order = time priority; remove by seq is O(1).
 */
public final class PriceLevel {

    private final Map<Long, BookOrder> orders = new LinkedHashMap<>(8);

    /** All orders at this level (for snapshot export). */
    public Collection<BookOrder> getOrders() {
        return orders.values();
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
    }

    /**
     * Remove order by seq (e.g. cancel). O(1).
     */
    public void remove(long seq) {
        orders.remove(seq);
    }

}
