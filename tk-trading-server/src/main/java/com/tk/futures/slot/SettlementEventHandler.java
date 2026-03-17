package com.tk.futures.slot;

import com.lmax.disruptor.EventHandler;
import com.tk.futures.inbound.CommandMessage;
import com.tk.futures.model.UserTradingBook;
import com.tk.futures.result.ResponsePublisher;
import com.tk.futures.result.ResultPublisher;
import com.tk.futures.settlement.SettlementEngine;
import com.tk.futures.settlement.UserCommandHandler;
import com.tk.protocol.dto.TradingSettle;
import com.tk.protocol.dto.UserCommandResult;
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
    private final ResponsePublisher responsePublisher;
    private final String shard;

    public SettlementEventHandler(int slotIndex,
                                  SlotContext slotContext,
                                  SettlementEngine settlementEngine,
                                  UserCommandHandler userCommandHandler,
                                  ResultPublisher resultPublisher,
                                  ResponsePublisher responsePublisher,
                                  String shard) {
        this.slotIndex = slotIndex;
        this.slotContext = slotContext;
        this.settlementEngine = settlementEngine;
        this.userCommandHandler = userCommandHandler;
        this.resultPublisher = resultPublisher;
        this.responsePublisher = responsePublisher;
        this.shard = shard;
    }

    @Override
    public void onEvent(CommandMessageEvent event, long sequence, boolean endOfBatch) {
        if (event == null || event.getMessage() == null) {
            return;
        }
        if (slotContext.offsetIfGreaterThanCurrent(event.getMessage().getOffset())) {
            CommandMessage message = event.getMessage();
            Long uid = message.getUid();
            if (uid == null || uid <= 0) {
                return;
            }
            UserTradingBook tradingBook = slotContext.getBook(uid);
            if (tradingBook == null) {
                return;
            }
            TradingCommand command = TradingCommand.ofValue(message.getCommand());
            try {
                if (command == TradingCommand.MATCH) {
                    // 结算结果：在 Book 上应用变更，并将需要持久化的记录追加到 events（通过 commit 的 consumer 回传）
                    settlementEngine.handle(uid, message.getData().toJavaObject(TradingSettle.class), tradingBook);
                } else {
                    // 非撮合指令：更新内存状态 + 产生给 open-api 的业务响应
                    UserCommandResult result = userCommandHandler.handle(command, message.getData(), tradingBook);
                    if (result != null) {
                        responsePublisher.publish(message.getReqId(), result);
                    }
                }
                // 内存变更成功，统一提交，并通过 consumer 将本次变更的持久化批次交给 ResultPublisher
                tradingBook.commit(events -> resultPublisher.publish(slotIndex, uid, events));
            } catch (Exception e) {
                // 任意异常都回滚本次内存变更
                tradingBook.rollback();
                if (command == TradingCommand.MATCH) {
                    tradingBook.addTradingSettle(slotContext.getOffset(), message.getData().toJavaObject(TradingSettle.class));
                }
            }
        }
    }

}

