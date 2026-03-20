package com.tk.futures.slot;

import com.alibaba.fastjson2.JSON;
import com.lmax.disruptor.EventHandler;
import com.tk.futures.inbound.CommandMessage;
import com.tk.futures.model.UserTradingBook;
import com.tk.futures.result.ResponsePublisher;
import com.tk.futures.result.ResultPublisher;
import com.tk.futures.result.TradingResultSlaveFileQueue;
import com.tk.futures.trade.SettlementService;
import com.tk.futures.trade.UserCommandHandler;
import com.tk.protocol.dto.TradingSettle;
import com.tk.protocol.dto.UserCommandResult;
import com.tx.common.enums.TradingCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单个结算槽位的 Disruptor 事件处理器。
 */
public class SettlementEventHandler implements EventHandler<CommandMessageEvent> {

    private static final Logger log = LoggerFactory.getLogger(SettlementEventHandler.class);

    private final int slotIndex;
    private final SlotContext slotContext;
    private final SettlementService settlementService;
    private final UserCommandHandler userCommandHandler;
    private final ResultPublisher resultPublisher;
    private final ResponsePublisher responsePublisher;
    private final TradingResultSlaveFileQueue slaveFileQueue;

    private boolean isMaster = false;

    public SettlementEventHandler(int slotIndex,
                                  SlotContext slotContext,
                                  SettlementService settlementService,
                                  UserCommandHandler userCommandHandler,
                                  ResultPublisher resultPublisher,
                                  ResponsePublisher responsePublisher,
                                  TradingResultSlaveFileQueue slaveFileQueue) {
        this.slotIndex = slotIndex;
        this.slotContext = slotContext;
        this.settlementService = settlementService;
        this.userCommandHandler = userCommandHandler;
        this.resultPublisher = resultPublisher;
        this.responsePublisher = responsePublisher;
        this.slaveFileQueue = slaveFileQueue;
    }

    @Override
    public void onEvent(CommandMessageEvent event, long sequence, boolean endOfBatch) {
        if (event == null || event.getMessage() == null) {
            return;
        }
        CommandMessage message = event.getMessage();
        if (message.getCommand() == null) {
            return;
        }
        TradingCommand command = TradingCommand.ofValue(message.getCommand());
        if (command == TradingCommand.MASTER) {
            boolean old = isMaster;
            isMaster = true;
            if (!old) {
                long minOffsetExclusive = message.getOffset(); // last kafka offset
                // 先把进度推进到 lastOffset：如果没有需要补发的记录，不会卡在 0。
                slotContext.setPushOffset(Math.max(slotContext.getPushOffset(), minOffsetExclusive));
                // 切主后，把从节点累计的文件队列补发到 Kafka
                slaveFileQueue.replayAndClear(slotIndex, minOffsetExclusive, record ->
                        resultPublisher.publishRaw(slotIndex, record.uid, record.offset, record.payload, e -> {
                            if (e == null) {
                                slotContext.setPushOffset(Math.max(slotContext.getPushOffset(), record.offset));
                            }
                        }));
            }
        } else if (command == TradingCommand.SLAVE) {
            isMaster = false;
        } else if (command == TradingCommand.CHECK) {
            if (isMaster) {
                resultPublisher.flush(slotIndex);
            }
        } else {
            long offset = event.getMessage().getOffset();
            if (slotContext.offsetIfGreaterThanCurrent(offset)) {
                Long uid = message.getUid();
                if (uid == null || uid <= 0) {
                    return;
                }
                UserTradingBook tradingBook = slotContext.getBook(uid);
                if (tradingBook == null) {
                    return;
                }
                try {
                    if (command == TradingCommand.MATCH) {
                        // 结算结果：在 Book 上应用变更，并将需要持久化的记录追加到 events（通过 commit 的 consumer 回传）
                        settlementService.handle(uid, message.getData().toJavaObject(TradingSettle.class), tradingBook);
                    } else {
                        // 非撮合指令：更新内存状态 + 产生给 open-api 的业务响应
                        UserCommandResult result = userCommandHandler.handle(command, message.getData(), tradingBook);
                        if (result != null) {
                            responsePublisher.publish(message.getReqId(), result);
                        }
                    }
                    if (isMaster) {
                        // 内存变更成功，统一提交，并通过 consumer 将本次变更的持久化批次交给 ResultPublisher
                        tradingBook.commit(events -> resultPublisher.publish(slotIndex, uid, offset, events, e -> {
                            if (e == null) {
                                slotContext.setPushOffset(Math.max(slotContext.getPushOffset(), offset));
                            }
                        }));
                    } else {
                        // slave：提交内存变更，同时把将要输出的内容写入文件队列（用于切主后补发）
                        tradingBook.commit(events -> {
                            String payloadJson = JSON.toJSONString(events);
                            slaveFileQueue.append(slotIndex, offset, uid, payloadJson);
                            slotContext.setPushOffset(Math.max(slotContext.getPushOffset(), offset));
                        });
                    }
                } catch (Exception e) {
                    // 任意异常都回滚本次内存变更
                    log.error("handle command error", e);
                    tradingBook.rollback();
                    if (command == TradingCommand.MATCH) {
                        tradingBook.addTradingSettle(slotContext.getOffset(), message.getData().toJavaObject(TradingSettle.class));
                    }
                }
            }
        }
    }

}

