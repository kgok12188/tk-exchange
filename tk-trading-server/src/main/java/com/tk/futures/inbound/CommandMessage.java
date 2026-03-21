package com.tk.futures.inbound;

import com.alibaba.fastjson2.JSONObject;

/**
 * 统一封装 trading_(shard) 的输入消息：
 * { "reqId": "...", "command": "...", "uid": 123456, "data": { ... } }
 */
public record CommandMessage(String reqId, String command, Long uid, JSONObject data, long offset) {

}

