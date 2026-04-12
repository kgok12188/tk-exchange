package com.tk.match.slot.event;

/**
 * 主从事件：consumeLoop 处理时 MASTER 执行补发（文件队列中 orderReqOffset &gt; masterOffset 的 payload 发往 Kafka），SLAVE 仅切换 isMaster。
 */
public final class HaEvent implements SlotEvent {

    public static final HaEvent MASTER = new HaEvent();
    public static final HaEvent SLAVE = new HaEvent();
    public static final HaEvent CLOSE = new HaEvent();

    private HaEvent() {
    }
}
