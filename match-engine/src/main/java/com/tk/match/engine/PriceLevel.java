package com.tk.match.engine;

import lombok.Getter;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Single price level in the order book: FIFO queue of orders at the same price.
 * <p>
 * 侵入式双向链表（{@link BookOrder#getNextInPriceLevel()}）实现队头 O(1) {@link #peekFirst()} 无分配；
 * 另维护 {@code seq -> BookOrder} 以支持撤单按 seq O(1) {@link #remove(long)}。
 */
public final class PriceLevel {

    @Getter
    private final long priceTicks;

    private BookOrder head;
    private BookOrder tail;
    private final Map<Long, BookOrder> bySeq = new HashMap<>(8);
    /**
     * Precomputed sum of remainingVolume; updated on addLast / remove / subtractVolume.
     */
    private BigDecimal totalRemaining = BigDecimal.ZERO;

    public PriceLevel(long priceTicks) {
        this.priceTicks = priceTicks;
    }

    /**
     * Sum of {@link BookOrder#getRemainingVolume()} at this level. O(1).
     */
    public BigDecimal totalRemainingVolume() {
        return totalRemaining;
    }

    public boolean isEmpty() {
        return head == null;
    }

    /**
     * First order by time (oldest seq). O(1)，无 Iterator 分配。
     */
    public BookOrder peekFirst() {
        return head;
    }

    /**
     * Add order at tail (FIFO).
     */
    public void addLast(BookOrder order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        if (order.getPrevInPriceLevel() != null || order.getNextInPriceLevel() != null) {
            throw new IllegalStateException("order already linked in a price level queue");
        }
        if (bySeq.putIfAbsent(order.getSeq(), order) != null) {
            throw new IllegalStateException("duplicate seq in price level: " + order.getSeq());
        }
        if (tail == null) {
            head = tail = order;
        } else {
            order.setPrevInPriceLevel(tail);
            tail.setNextInPriceLevel(order);
            tail = order;
        }
        totalRemaining = totalRemaining.add(order.getRemainingVolume());
    }

    /**
     * Remove order by seq (e.g. cancel). O(1).
     */
    public void remove(long seq) {
        BookOrder removed = bySeq.remove(seq);
        if (removed == null) {
            return;
        }
        totalRemaining = totalRemaining.subtract(removed.getRemainingVolume());
        unlink(removed);
    }

    /**
     * Called when a maker order at this level has its remainingVolume reduced by {@code delta} (e.g. after a fill).
     * Must be invoked in sync with {@link BookOrder#deductRemainingVolume(BigDecimal)} / {@link BookOrder#setRemainingVolume(BigDecimal)}.
     */
    public void subtractVolume(BigDecimal delta) {
        if (delta != null && delta.compareTo(BigDecimal.ZERO) > 0) {
            totalRemaining = totalRemaining.subtract(delta);
        }
    }

    private void unlink(BookOrder bookOrder) {
        BookOrder prev = bookOrder.getPrevInPriceLevel();
        BookOrder next = bookOrder.getNextInPriceLevel();
        if (prev != null) {
            prev.setNextInPriceLevel(next);
        } else {
            head = next;
        }
        if (next != null) {
            next.setPrevInPriceLevel(prev);
        } else {
            tail = prev;
        }
        bookOrder.setPrevInPriceLevel(null);
        bookOrder.setNextInPriceLevel(null);
    }
}
