package com.tk.protocol.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Single trade record with both sides and taker/maker. One record per match event.
 * <p>
 * <b>orderReqOffset</b>: Kafka partition offset of the {@code order_req_(symbol)} message that triggered this match.
 * All trades produced by the same order_req share the same orderReqOffset (used for idempotency and ordering).
 * <p>
 * <b>index</b>: Monotonic sequence (0-based) of this trade within the same match event. Increments as the taker
 * consumes liquidity (e.g. first fill = 0, second = 1). (orderReqOffset, index) uniquely identifies a trade leg.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeOrder {
    /** Monotonic index for this trade within the same order_req (taker eat order). */
    private long index;
    /** Partition offset of the order_req message that triggered this match. */
    private long orderReqOffset;
    private BigDecimal price;
    private BigDecimal volume;
    private Long buyUid;
    private Long sellUid;
    private Long buyOrderId;
    private int buyShardId;
    private int sellShardId;
    private Long sellOrderId;
    private Long takerOrderId;
    private Long takerUid;
}
