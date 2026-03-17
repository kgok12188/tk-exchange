package com.tk.protocol.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * open-api 向 trading-server 推送的统一请求结构。
 *
 * 底层通过 Kafka 发送到 trading_(shard) topic，payload 为本对象的 JSON：
 * {
 *   "reqId": "...",          // 请求唯一标识，用于 response 关联
 *   "command": "NEW_ORDER",  // 与 TradingCommand 枚举对应
 *   "uid": 123,              // 用户 ID；用于 shard / slot 路由
 *   "data": { ... }          // 具体命令的业务参数，请求 DTO
 * }
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradingRequest {

    /**
     * 请求唯一标识，用于关联异步响应。
     */
    private String reqId;

    /**
     * 业务指令类型，对齐 com.tx.common.enums.TradingCommand（如 NEW_ORDER / CANCEL_ORDER / MATCH / TRANSFER 等）。
     */
    private String command;

    /**
     * 本次请求所属用户 UID，用于 shard & slot 路由。
     */
    private Long uid;

    /**
     * 指令携带的业务参数（如 NewOrderRequest / CancelOrderRequest / TransferRequest 等），
     * 具体 DTO 在各业务模块或 trading-protocol 中定义。
     */
    private Object data;
}

