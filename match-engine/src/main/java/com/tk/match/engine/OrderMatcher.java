package com.tk.match.engine;


/**
 * Strategy for matching a single order by price type (LIMIT, MARKET, LIMIT_MAKER).
 * Each implementation is responsible for one price type.
 */
public interface OrderMatcher {

    /**
     * Execute match (or post-only reject) for the given order.
     *
     * @param book    the order book (mutable)
     * @param order   the incoming order (already placed in book.ordersById by OrderBook)
     * @param orderReqOffset Kafka offset of the order_req message
     * @return trades and finish orders produced by this match step
     */
    MatchResult match(OrderBook book, BookOrder order, long orderReqOffset);
}
