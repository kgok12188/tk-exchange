package com.tx.common.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tx.common.entity.Coin;
import com.tx.common.mapper.CoinMapper;
import org.springframework.stereotype.Service;

@Service
public class CoinService extends ServiceImpl<CoinMapper, Coin> {

}
