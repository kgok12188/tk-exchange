package com.tk.futures.statemachine;

/**
 * 请求在状态机中的生命周期状态。
 * 仅处理 REQUEST_MESSAGE：RECEIVED -> QUEUED -> PROCESSING -> COMPLETED | FAILED
 */
public enum RequestState {
    /** 已从 Kafka 收到 */
    RECEIVED,
    /** 已按 uid 落入对应队列 */
    QUEUED,
    /** 正在被工作线程处理 */
    PROCESSING,
    /** 处理成功结束 */
    COMPLETED,
    /** 处理异常结束 */
    FAILED
}
