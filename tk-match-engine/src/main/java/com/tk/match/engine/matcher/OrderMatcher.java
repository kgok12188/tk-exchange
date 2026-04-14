package com.tk.match.engine.matcher;

import com.tk.match.engine.BookOrder;
import com.tk.match.engine.MatchResult;

/**
 * Strategy for matching a single order by price type (LIMIT, MARKET, LIMIT_MAKER).
 * Each implementation is responsible for one price type.
 */
public interface OrderMatcher {

    /**
     * Execute match (or post-only reject) for the given order.
     *
     * @param takerOrder     the incoming taker order (resting book state is on {@code book})
     * @param orderReqOffset Kafka offset of the order_req message
     * @return trades and finish orders produced by this match step
     */
    MatchResult match(BookOrder takerOrder, long orderReqOffset);

    /**
     * 纯参数校验：taker 字段是否满足当前撮合策略与 {@link com.tk.protocol.dto.MatchMarketConfig} 的前置条件。
     * <p>
     * <b>只读</b>：不得修改 {@code orderBook} 与 {@code takerOrder}。
     * <p>
     * {@code null} 表示通过，可继续 {@link #match}；非 {@code null} 表示校验失败（通常无成交，仅含对 taker 的
     * {@link com.tk.protocol.dto.FinishOrder}，如 {@link com.tk.protocol.dto.FinishStatus#REJECT}）。
     */
    MatchResult validate(BookOrder takerOrder);
}
