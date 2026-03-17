package com.tk.protocol.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Order that reached a terminal state. Includes remaining quantity (leaveAmount/leaveVolume).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinishOrder {
    private Long uid;
    private Long orderId;
    private FinishStatus status;
    private BigDecimal leaveAmount;
    private BigDecimal leaveVolume;
    private int shardId;
}
