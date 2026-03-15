package com.tk.protocol.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Per-user view of one leg of a trade. Used in TradingSettle.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Ticket {
    private long index;
    private long orderReqOffset;
    private BigDecimal price;
    private BigDecimal volume;
    private Long uid;
    private Long orderId;
    private boolean isTaker;
}
