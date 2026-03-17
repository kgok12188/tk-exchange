package com.tk.protocol.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload for CANCEL_ORDER command. orderId required; uid optional for validation/routing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CancelPayload {
    private Long orderId;
    private Long uid;
    private int shardId;
}
