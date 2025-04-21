package com.tk.futures.service;

import com.alibaba.fastjson.JSON;
import com.google.common.collect.Lists;
import com.tk.futures.model.UserData;
import com.tx.common.entity.*;
import com.tx.common.kafka.KafkaTopic;
import com.tx.common.mapper.*;
import com.tx.common.message.AsyncMessageItem;
import com.tx.common.service.AccountService;
import com.tx.common.service.OrderService;
import com.tx.common.service.PositionService;
import com.tx.common.service.UserService;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.LinkedList;
import java.util.List;

@Service
public class UserDataService {

    private static final Logger logger = LoggerFactory.getLogger(UserDataService.class);
    @Autowired
    private OrderService orderService;
    @Autowired
    private PositionService positionService;
    @Autowired
    private AccountService accountService;
    @Autowired
    private UserService userService;

    @Autowired
    private TransferMapper transferMapper;
    @Autowired
    private AccountMapper accountMapper;
    @Autowired
    private TradeOrderMapper tradeOrderMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private PositionMapper positionMapper;

    public UserData load(Long uid) {
        if (uid == null) {
            return null;
        }
        User user = userService.getById(uid);
        if (user == null) {
            return null;
        }
        // 未完成的订单
        List<Order> orders = orderService.lambdaQuery().eq(Order::getUid, uid).eq(Order::getStatus, Lists.newArrayList(0, 1)).list();
        // 当前持仓
        List<Position> positions = positionService.lambdaQuery().eq(Position::getUid, uid).eq(Position::getStatus, 1).list();
        // 资产信息
        List<Account> accounts = accountService.lambdaQuery().eq(Account::getUid, uid).list();
        return new UserData(uid, new LinkedList<>(orders), new LinkedList<>(positions), new LinkedList<>(accounts));
    }

    public void sendToMq(KafkaProducer<String, String> kafkaProducer, long uid, List<AsyncMessageItem> messageItems) {
        if (CollectionUtils.isEmpty(messageItems)) {
            return;
        }
        ProducerRecord<String, String> record = new ProducerRecord<>(KafkaTopic.SYNC_TO_DB, String.valueOf(uid), JSON.toJSONString(messageItems));
        try {
            kafkaProducer.send(record);
        } catch (Exception e) {
            logger.error("send_mq_messageItems {}", JSON.toJSONString(messageItems), e);
            try {
                persistence(messageItems);
            } catch (Exception ex) {
                logger.error("persistence_messageItems {}", JSON.toJSONString(messageItems), ex);
            }
        }
    }

    /**
     * 数据持久化到数据库
     *
     * @param messageItems 命令消息
     */
    public void persistence(List<AsyncMessageItem> messageItems) {
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
