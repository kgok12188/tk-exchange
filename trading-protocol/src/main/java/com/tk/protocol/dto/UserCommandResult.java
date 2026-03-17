package com.tk.protocol.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * trading-server 处理单条用户指令后的业务结果。
 * 该结果会通过响应通道返回给 open-api，用于前端展示本次操作的视图。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserCommandResult {

    /**
     * 本次指令是否处理成功。
     */
    private boolean success;

    /**
     * 业务错误码（可选）。
     */
    private String errorCode;

    /**
     * 业务错误信息（可选）。
     */
    private String errorMessage;

    /**
     * 具体业务数据载荷，例如订单/账户快照等。
     */
    private Object data;
}

