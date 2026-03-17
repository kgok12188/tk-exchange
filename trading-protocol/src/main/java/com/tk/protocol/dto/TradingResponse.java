package com.tk.protocol.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * trading-server 通过 Kafka 响应 open-api 的统一数据结构。
 *
 * payload 结构：
 * {
 *   "reqId": "...",
 *   "result": { ... UserCommandResult ... }
 * }
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradingResponse {

    /**
     * 对应请求的唯一标识，与 TradingRequest.reqId 一致。
     */
    private String reqId;

    /**
     * 单条指令的业务处理结果。
     */
    private UserCommandResult result;
}

