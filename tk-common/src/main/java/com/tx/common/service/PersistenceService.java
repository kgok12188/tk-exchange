package com.tx.common.service;

import com.tx.common.entity.Account;
import com.tx.common.entity.Order;
import com.tx.common.entity.TradeOrder;
import com.tx.common.entity.Transfer;
import com.tx.common.mapper.AccountMapper;
import com.tx.common.mapper.OrderMapper;
import com.tx.common.mapper.TradeOrderMapper;
import com.tx.common.mapper.TransferMapper;
import com.tx.common.message.PersistenceBatch;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PersistenceService {


    private final TransferMapper transferMapper;

    private final AccountMapper accountMapper;

    private final TradeOrderMapper tradeOrderMapper;

    private final OrderMapper orderMapper;


    public PersistenceService(TransferMapper transferMapper,
                              AccountMapper accountMapper,
                              TradeOrderMapper tradeOrderMapper,
                              OrderMapper orderMapper) {
        this.transferMapper = transferMapper;
        this.accountMapper = accountMapper;
        this.tradeOrderMapper = tradeOrderMapper;
        this.orderMapper = orderMapper;
    }

    /**
     * 数据持久化到数据库
     *
     * @param messageItems 命令消息
     */
    public void flush(List<PersistenceBatch> messageItems) {
        for (PersistenceBatch messageItem : messageItems) {
            PersistenceBatch.Type type = PersistenceBatch.Type.fromValue(messageItem.getType());
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
                case TRADE_ORDER:
                    for (Object message : messageItem.getMessages()) {
                        tradeOrderMapper.upsert((TradeOrder) message);
                    }
                    break;
            }
        }
    }
}
