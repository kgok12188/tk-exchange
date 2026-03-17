package com.tk.protocol.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Identifies who triggered a match/cancel. Null when no incoming order triggered (e.g. price hit makers).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TakerRef {
    private Long uid;
    private Long orderId;
    private int shardId;
}
