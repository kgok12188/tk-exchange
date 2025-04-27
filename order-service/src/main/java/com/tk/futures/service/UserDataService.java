package com.tk.futures.service;

import com.alibaba.fastjson.JSON;
import com.google.common.collect.Lists;
import com.tk.futures.model.UserData;
import com.tx.common.entity.*;
import com.tx.common.kafka.KafkaTopic;
import com.tx.common.message.AsyncMessageItem;
import com.tx.common.service.*;
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
    private PersistenceService persistenceService;

    private String groupId;

    public UserData load(Long uid, String groupId) {
        if (this.groupId == null) {
            this.groupId = groupId;
        }
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
        ProducerRecord<String, String> record = new ProducerRecord<>(KafkaTopic.SYNC_TO_DB + groupId, String.valueOf(uid), JSON.toJSONString(messageItems));
        try {
            kafkaProducer.send(record);
        } catch (Exception e) {
            logger.error("send_mq_messageItems {}", JSON.toJSONString(messageItems), e);
            try {
                persistenceService.flush(messageItems);
            } catch (Exception ex) {
                logger.error("persistence_messageItems {}", JSON.toJSONString(messageItems), ex);
            }
        }
    }

}
