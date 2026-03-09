package com.tx.common.service;

import com.tx.common.entity.*;
import com.tx.common.mapper.*;
import com.tx.common.message.AsyncMessageItem;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PersistenceService {


    private final TransferMapper transferMapper;

    private final AccountMapper accountMapper;

    private final TradeOrderMapper tradeOrderMapper;

    private final OrderMapper orderMapper;

    private final PositionMapper positionMapper;


    public PersistenceService(TransferMapper transferMapper, AccountMapper accountMapper, TradeOrderMapper tradeOrderMapper, OrderMapper orderMapper, PositionMapper positionMapper) {
        this.transferMapper = transferMapper;
        this.accountMapper = accountMapper;
        this.tradeOrderMapper = tradeOrderMapper;
        this.orderMapper = orderMapper;
        this.positionMapper = positionMapper;
    }

    /**
     * 数据持久化到数据库
     *
     * @param messageItems 命令消息
     */
    public void flush(List<AsyncMessageItem> messageItems) {
        for (AsyncMessageItem messageItem : messageItems) {
            AsyncMessageItem.Type type = AsyncMessageItem.Type.fromValue(messageItem.getType());
            if (type == null) {
                continue;
            }
            switch (type) {
                case ACCOUNT:
                    for (Object message : messageItem.getMessages()) {
                        accountMapper.upsert((Account) message);
                    }
                    break;
                case TRANSFER:
                    for (Object message : messageItem.getMessages()) {
                        transferMapper.upsert((Transfer) message);
                    }
                    break;
                case ORDER:
                    for (Object message : messageItem.getMessages()) {
                        orderMapper.upsert((Order) message);
                    }
                    break;
                case POSITION:
                    for (Object message : messageItem.getMessages()) {
                        positionMapper.upsert((Position) message);
                    }
                    break;
                case TRADE_ORDER:
                    for (Object message : messageItem.getMessages()) {
                        tradeOrderMapper.upsert((TradeOrder) message);
                    }
                    break;
            }
        }
    }
}
