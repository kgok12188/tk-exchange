package com.tk.protocol.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Payload for PUSH_ORDER command. Required: id, uid, symbol/marketId, side, priceType, price, volume or amount.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderPayload {
    private Long id;
    private Long uid;
    private int shardId;
    private String symbol;
    private Long marketId;
    private String side;       // BUY, SELL
    private String priceType;  // LIMIT, MARKET, LIMIT_MAKER (post-only)
    private BigDecimal price;
    private BigDecimal volume;
    private BigDecimal amount; // alternative to volume when order is amount-based
}
