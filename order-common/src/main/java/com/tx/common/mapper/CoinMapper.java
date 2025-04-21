package com.tx.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tx.common.entity.Coin;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

@Mapper
public interface CoinMapper extends BaseMapper<Coin> {

}
