package com.tk.futures.slot;

import com.lmax.disruptor.EventHandler;
import com.tk.futures.model.PersistenceBatchList;
import com.tk.futures.result.ResultPublisher;
import com.tk.futures.settlement.SettlementEngine;
import com.tk.futures.settlement.UserCommandHandler;
import com.tx.common.enums.TradingCommand;

/**
 * 单个结算槽位的 Disruptor 事件处理器。
 */
public class SettlementEventHandler implements EventHandler<CommandMessageEvent> {

    private final int slotIndex;
    private final SlotContext slotContext;
    private final SettlementEngine settlementEngine;
    private final UserCommandHandler userCommandHandler;
    private final ResultPublisher resultPublisher;
    private final String shard;

    public SettlementEventHandler(int slotIndex,
                                  SlotContext slotContext,
                                  SettlementEngine settlementEngine,
                                  UserCommandHandler userCommandHandler,
                                  ResultPublisher resultPublisher,
                                  String shard) {
        this.slotIndex = slotIndex;
        this.slotContext = slotContext;
        this.settlementEngine = settlementEngine;
        this.userCommandHandler = userCommandHandler;
        this.resultPublisher = resultPublisher;
        this.shard = shard;
    }

    @Override
    public void onEvent(CommandMessageEvent event, long sequence, boolean endOfBatch) {
        if (event == null || event.getMessage() == null) {
            return;
        }
        var message = event.getMessage();
        Long uid = message.getUid();
        TradingCommand command = TradingCommand.ofValue(message.getCommand());
        PersistenceBatchList events;
        if (command == TradingCommand.MATCH) {
            events = settlementEngine.apply(uid, message.getData(), slotContext);
        } else {
            events = userCommandHandler.handle(command, uid, message.getData(), slotContext, shard);
        }
        if (uid != null && uid > 0) {
            resultPublisher.publish(slotIndex, uid, events);
        }
    }
}

