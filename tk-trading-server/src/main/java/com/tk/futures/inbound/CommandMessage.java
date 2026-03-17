package com.tk.futures.inbound;

import com.alibaba.fastjson2.JSONObject;
import lombok.Getter;

/**
 * 统一封装 trading_(shard) 的输入消息：
 * { "command": "...", "uid": 123456, "data": { ... } }
 */
@Getter
public class CommandMessage {

    private final String command;
    private final Long uid;
    private final JSONObject data;
    private final long offset;

    public CommandMessage(String command, Long uid, JSONObject data, long offset) {
        this.command = command;
        this.uid = uid;
        this.data = data;
        this.offset = offset;
    }

}

