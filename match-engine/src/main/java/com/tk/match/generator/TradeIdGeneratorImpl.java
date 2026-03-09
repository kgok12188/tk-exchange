package com.tk.match.generator;

import org.springframework.stereotype.Service;


public class TradeIdGeneratorImpl implements TradeIdGenerator {


    private final static long START_SECOND = 1735660800L; // 2025-01-01 00:00:00

    // 每一部分占用的位数
    private final static long SEQUENCE_BIT = 15; // 序列号占用的位数
    private final static long MAX_SEQUENCE = ~(-1L << SEQUENCE_BIT);
    private long sequence = 0L; // 序列号
    private long lastTimeSecond;

    @Override
    public long nextId() {
        long currSecond = getSecond();
        if (currSecond < lastTimeSecond) {
            throw new RuntimeException("Clock moved backwards.  Refusing to generate id");
        }

        if (currSecond == lastTimeSecond) {
            // 相同毫秒内，序列号自增
            sequence = (sequence + 1) & MAX_SEQUENCE;
            // 同一毫秒的序列数已经达到最大
            if (sequence == 0L) {
                currSecond = getNextSecond();
            }
        } else {
            // 不同秒内，序列号置为0
            sequence = 0L;
        }
        lastTimeSecond = currSecond;
        return (currSecond - START_SECOND) << SEQUENCE_BIT | sequence;
    }

    private long getSecond() {
        return System.currentTimeMillis() / 1000;
    }

    private long getNextSecond() {
        long second = getSecond();
        while (second <= lastTimeSecond) {
            second = getSecond();
        }
        return second;
    }

}
