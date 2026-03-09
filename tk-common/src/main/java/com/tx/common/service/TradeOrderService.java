package com.tx.common.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tx.common.entity.TradeOrder;
import com.tx.common.mapper.TradeOrderMapper;
import org.springframework.stereotype.Service;

@Service
public class TradeOrderService extends ServiceImpl<TradeOrderMapper, TradeOrder> {
}
