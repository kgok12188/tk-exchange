package com.tk.futures.generator;

import org.springframework.stereotype.Service;

@Service
public class OrderIdGeneratorImpl implements OrderIdGenerator {
    // 起始的时间戳
    private final static long START_STMP = 1735660800000L; // 2025-01-01 00:00:00

    // 每一部分占用的位数
    private final static long SEQUENCE_BIT = 12; // 序列号占用的位数
    private final static long MACHINE_BIT = 10;   // 机器标识占用的位数
    private final static long TIMESTAMP_BIT = 41; // 时间戳占用的位数

    // 每一部分的最大值
    private final static long MAX_MACHINE_NUM = ~(-1L << MACHINE_BIT);
    private final static long MAX_SEQUENCE = ~(-1L << SEQUENCE_BIT);

    // 每一部分向左的位移
    private final static long MACHINE_LEFT = SEQUENCE_BIT;
    private final static long TIMESTAMP_LEFT = SEQUENCE_BIT + MACHINE_BIT;

    private long machineId;  // 机器标识
    private long sequence = 0L; // 序列号
    private long lastTimeMillis = -1L;// 上一次时间戳


    private volatile boolean isInit = false;

    public OrderIdGeneratorImpl() {

    }

    public synchronized void setSnowflakeIdWorker(long machineId) {
        if (isInit) {
            throw new IllegalArgumentException("machineId is init");
        }
        if (machineId > MAX_MACHINE_NUM || machineId < 0) {
            throw new IllegalArgumentException("machineId can't be greater than MAX_MACHINE_NUM or less than 0");
        }
        isInit = true;
        this.machineId = machineId;
    }

    // 产生下一个ID
    public synchronized long nextId() {
        if (!isInit) {
            throw new IllegalArgumentException("machineId is not init");
        }
        long currTimeMillis = getNew();
        if (currTimeMillis < lastTimeMillis) {
            throw new RuntimeException("Clock moved backwards.  Refusing to generate id");
        }

        if (currTimeMillis == lastTimeMillis) {
            // 相同毫秒内，序列号自增
            sequence = (sequence + 1) & MAX_SEQUENCE;
            // 同一毫秒的序列数已经达到最大
            if (sequence == 0L) {
                currTimeMillis = getNextMill();
            }
        } else {
            // 不同毫秒内，序列号置为0
            sequence = 0L;
        }
        lastTimeMillis = currTimeMillis;
        return (currTimeMillis - START_STMP) << TIMESTAMP_LEFT // 时间戳部分
                | machineId << MACHINE_BIT
                | sequence;            // 机器标识部分
    }

    private long getNextMill() {
        long mill = getNew();
        while (mill <= lastTimeMillis) {
            mill = getNew();
        }
        return mill;
    }

    private long getNew() {
        return System.currentTimeMillis();
    }

}
