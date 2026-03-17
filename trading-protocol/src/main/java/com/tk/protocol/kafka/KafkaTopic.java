package com.tk.protocol.kafka;

/**
 * 统一管理所有 Kafka topic 名称。
 * <p>
 * 约定：
 * - 业务文档中使用下划线命名（如 trading_message_(分区)），
 * 代码中统一使用这里的常量，避免各模块自行拼接字符串。
 */
public class KafkaTopic {

    /**
     * trading_result_(分区)
     * 示例：trading_result_
     */
    public static final String TRADING_RESULT = "trading_result_";

    /**
     * trading_(分区)
     * 示例：trading_
     */
    public static final String TRADING = "trading_";

    /**
     * 响应消息 topic：response_message
     */
    public static final String RESPONSE = "response";


    public static final String MARK_PRICE = "mark-price";

    public static final String INDEX_PRICE = "index-price";

    public static final String LAST_PRICE = "last-price";

}

