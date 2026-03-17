package com.tk.futures.inbound;

/**
 * 将统一的 CommandMessage 路由到内部 slot / 状态机。
 * 具体 slot 模型在后续任务中实现，这里只定义接口。
 */
public interface CommandRouter {

    /**
     * 路由一条从 trading_(shard) 拉取到的消息。
     *
     * @param message 解析后的指令
     */
    void route(CommandMessage message);
}

