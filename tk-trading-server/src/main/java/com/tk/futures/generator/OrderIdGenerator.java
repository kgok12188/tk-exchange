package com.tk.futures.generator;

public interface OrderIdGenerator {

    void setSnowflakeIdWorker(long machineId);

    long nextId();

}
