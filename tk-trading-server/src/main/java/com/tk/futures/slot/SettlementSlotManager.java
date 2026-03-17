package com.tk.futures.slot;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.tk.futures.inbound.CommandMessage;
import com.tk.futures.result.ResultPublisher;
import com.tk.futures.settlement.SettlementEngine;
import com.tk.futures.settlement.UserCommandHandler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadFactory;

/**
 * 结算槽位管理器：固定 4 个 slot，每个 slot 一个队列 + 一个工作线程。
 */
@Service
public class SettlementSlotManager {

    private static final Logger logger = LoggerFactory.getLogger(SettlementSlotManager.class);

    /**
     * 固定 4 个 slot。
     */
    public static final int SLOTS = 4;

    private final List<Disruptor<CommandMessageEvent>> disruptors = new ArrayList<>(SLOTS);
    private final List<RingBuffer<CommandMessageEvent>> ringBuffers = new ArrayList<>(SLOTS);
    private final List<SlotContext> slotStates = new ArrayList<>(SLOTS);

    private final SettlementEngine settlementEngine;
    private final UserCommandHandler userCommandHandler;
    private final String shard;
    private final ResultPublisher resultPublisher;

    public SettlementSlotManager(SettlementEngine settlementEngine,
                                 UserCommandHandler userCommandHandler,
                                 ResultPublisher resultPublisher,
                                 @org.springframework.beans.factory.annotation.Value("${shard.id}") String shard) {
        this.settlementEngine = settlementEngine;
        this.userCommandHandler = userCommandHandler;
        this.resultPublisher = resultPublisher;
        this.shard = shard;
    }

    @PostConstruct
    public void start() {
        for (int i = 0; i < SLOTS; i++) {
            SlotContext state = new SlotContext(i);
            slotStates.add(state);
            int slotIndex = i;
            int bufferSize = 1024;
            ThreadFactory threadFactory = r -> new Thread(r, "settlement-slot-" + slotIndex);
            Disruptor<CommandMessageEvent> disruptor = new Disruptor<>(
                    CommandMessageEvent.FACTORY,
                    bufferSize,
                    threadFactory,
                    ProducerType.MULTI,
                    new BlockingWaitStrategy()
            );
            disruptor.handleEventsWith(
                    new SettlementEventHandler(slotIndex, state, settlementEngine, userCommandHandler, resultPublisher, shard)
            );
            disruptor.start();
            disruptors.add(disruptor);
            ringBuffers.add(disruptor.getRingBuffer());
        }
        logger.info("SettlementSlotManager started with {} slots", SLOTS);
    }

    @PreDestroy
    public void stop() {
        for (Disruptor<CommandMessageEvent> disruptor : disruptors) {
            disruptor.shutdown();
        }
        logger.info("SettlementSlotManager stopped");
    }

    /**
     * 根据 uid 计算 slot 下标。
     */
    public int slot(long uid) {
        int h = Long.hashCode(uid);
        return Math.abs((h & 0x7fffffff) % SLOTS);
    }

    /**
     * 提交带 uid 的用户指令。
     */
    public void submitUserCommand(CommandMessage message) {
        Long uid = message.getUid();
        if (uid == null || uid <= 0) {
            logger.warn("skip user command without valid uid, command={}, uid={}", message.getCommand(), uid);
            return;
        }
        int slotIndex = slot(uid);
        publishToRingBuffer(ringBuffers.get(slotIndex), message);
    }

    /**
     * 提交广播指令到所有 slot。
     */
    public void submitBroadcast(CommandMessage message) {
        for (int i = 0; i < SLOTS; i++) {
            publishToRingBuffer(ringBuffers.get(i), message);
        }
    }

    private void publishToRingBuffer(RingBuffer<CommandMessageEvent> ringBuffer, CommandMessage message) {
        long sequence = ringBuffer.next();
        try {
            CommandMessageEvent event = ringBuffer.get(sequence);
            event.setMessage(message);
        } finally {
            ringBuffer.publish(sequence);
        }
    }
}

