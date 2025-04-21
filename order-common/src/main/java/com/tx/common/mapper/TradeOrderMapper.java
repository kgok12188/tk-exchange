package com.tx.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tx.common.entity.TradeOrder;
import org.springframework.stereotype.Service;

@Service
public interface TradeOrderMapper extends BaseMapper<TradeOrder> {
    void upsert(TradeOrder message);
}
