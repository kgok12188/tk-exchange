package com.tk.protocol;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.JSONWriter;
import com.tk.protocol.dto.MatchResponse;
import com.tk.protocol.dto.OrderCommand;
import com.tk.protocol.dto.TradingSettle;

/**
 * JSON serialization for protocol DTOs. All DTOs are JSON-compatible; use this for consistent options and schema versioning.
 */
public final class ProtocolSerde {

    private static final JSONReader.Feature[] READ_FEATURES = {JSONReader.Feature.SupportSmartMatch};
    private static final JSONWriter.Feature[] WRITE_FEATURES = {JSONWriter.Feature.WriteLongAsString};

    private ProtocolSerde() {
    }

    public static String toJson(OrderCommand cmd) {
        return JSON.toJSONString(cmd, WRITE_FEATURES);
    }

    public static OrderCommand orderCommandFromJson(String json) {
        return JSON.parseObject(json, OrderCommand.class, READ_FEATURES);
    }

    public static String toJson(MatchResponse resp) {
        return JSON.toJSONString(resp, WRITE_FEATURES);
    }

    public static MatchResponse matchResponseFromJson(String json) {
        return JSON.parseObject(json, MatchResponse.class, READ_FEATURES);
    }

    public static String toJson(TradingSettle settle) {
        return JSON.toJSONString(settle, WRITE_FEATURES);
    }

    public static TradingSettle tradingSettleFromJson(String json) {
        return JSON.parseObject(json, TradingSettle.class, READ_FEATURES);
    }
}
