package com.tk.futures.model;

import lombok.Getter;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 单个 trading-book 的状态：uid -> UserData 的内存表，并维护该槽位已处理到的 Kafka offset。
 */
@Getter
public class RingBufferTradingBook extends HashMap<Long, UserTradingBook> {


    /**
     * 本 book 已处理到的 Kafka 分区 offset（用于提交或恢复）
     */
    private long offset = 0;

    private Map<String, BigDecimal> markPrices = new HashMap<>();
    private Map<String, BigDecimal> indexPrices = new HashMap<>();

    public void updateOffset(long offset) {
        if (offset > this.offset) {
            this.offset = offset;
        }
    }

    public void updateMarkPrice(String symbol, BigDecimal markPrice) {
        markPrices.put(symbol, markPrice);
    }

    public void updateIndexPrice(String symbol, BigDecimal indexPrice) {
        indexPrices.put(symbol, indexPrice);
    }

}
