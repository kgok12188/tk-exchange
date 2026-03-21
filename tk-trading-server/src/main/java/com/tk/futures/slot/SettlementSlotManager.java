package com.tk.futures.slot;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.tk.futures.compare.TradingResultTailQueryService;
import com.tk.futures.inbound.CommandMessage;
import com.tk.futures.result.ResponsePublisher;
import com.tk.futures.result.ResultPublisher;
import com.tk.futures.result.TradingResultSlaveFileQueue;
import com.tk.futures.trade.CancelOrderHandler;
import com.tk.futures.trade.MatchResultHandler;
import com.tk.futures.trade.NewOrderHandler;
import com.tx.common.enums.TradingCommand;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
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

    @Getter
    private volatile boolean started = false;

    /**
     * 固定 4 个 slot。
     */
    public static final int SLOTS = 4;

    private final List<Disruptor<CommandMessageEvent>> disrupts = new ArrayList<>(SLOTS);
    private final List<RingBuffer<CommandMessageEvent>> ringBuffers = new ArrayList<>(SLOTS);

    private final MatchResultHandler matchResultHandler;
    private final NewOrderHandler newOrderHandler;
    private final ResultPublisher resultPublisher;
    private final ResponsePublisher responsePublisher;
    private final TradingResultSlaveFileQueue slaveFileQueue;
    private final TradingResultTailQueryService tradingResultTailQueryService;

    private final CancelOrderHandler cancelOrderHandler;

    public SettlementSlotManager(MatchResultHandler matchResultHandler, NewOrderHandler newOrderHandler, ResultPublisher resultPublisher, CancelOrderHandler cancelOrderHandler,
                                 ResponsePublisher responsePublisher, TradingResultSlaveFileQueue slaveFileQueue, TradingResultTailQueryService tradingResultTailQueryService) {
        this.matchResultHandler = matchResultHandler;
        this.newOrderHandler = newOrderHandler;
        this.cancelOrderHandler = cancelOrderHandler;
        this.resultPublisher = resultPublisher;
        this.responsePublisher = responsePublisher;
        this.slaveFileQueue = slaveFileQueue;
        this.tradingResultTailQueryService = tradingResultTailQueryService;
    }

    @PostConstruct
    public void start() {
        for (int i = 0; i < SLOTS; i++) {
            SlotContext state = new SlotContext(i);
            int slotIndex = i;
            int bufferSize = 1024;
            ThreadFactory threadFactory = r -> {
                Thread thread = new Thread(r, "settlement-slot-" + slotIndex);
                thread.setDaemon(true);
                return thread;
            };
            Disruptor<CommandMessageEvent> disruptor = new Disruptor<>(CommandMessageEvent.FACTORY, bufferSize, threadFactory, ProducerType.MULTI, new BlockingWaitStrategy());
            disruptor.handleEventsWith(new SettlementEventHandler(slotIndex, state, matchResultHandler, newOrderHandler, cancelOrderHandler, resultPublisher, responsePublisher, slaveFileQueue));
            disruptor.start();
            disrupts.add(disruptor);
            ringBuffers.add(disruptor.getRingBuffer());
        }
        logger.info("SettlementSlotManager started with {} slots", SLOTS);
        started = true;
    }

    @PreDestroy
    public void stop() {
        for (Disruptor<CommandMessageEvent> disruptor : disrupts) {
            disruptor.shutdown();
        }
        logger.info("SettlementSlotManager stopped");
        started = false;
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
        Long uid = message.uid();
        if (uid == null || uid <= 0) {
            logger.warn("skip user command without valid uid, command={}, uid={}", message.command(), uid);
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

    /**
     * 提交广播指令到所有 slot。
     */
    public void broadcastRole(boolean isMaster) {
        for (int i = 0; i < SLOTS; i++) {
            long offset = 0;
            if (isMaster) {
                // 切主时：查询 trading_result_(shard) 当前 partition（=slotIndex）最后一条 offset
                // 让从节点文件 replay 时只补发 record.offset > lastOffset，避免重复写 Kafka。
                offset = tradingResultTailQueryService.queryLastOffset(i);
            }
            publishToRingBuffer(ringBuffers.get(i), new CommandMessage(null, isMaster ? TradingCommand.MASTER.name() : TradingCommand.SLAVE.name(), null, null, offset));
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


    @Scheduled(fixedDelay = 100, initialDelay = 1000)
    public void check() {
        if (started) {
            resultPublisher.check(i -> publishToRingBuffer(ringBuffers.get(i), new CommandMessage(null, TradingCommand.CHECK.name(), null, null, 0)));
        }
    }

}

