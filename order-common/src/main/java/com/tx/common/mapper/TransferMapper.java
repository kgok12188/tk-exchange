package com.tx.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tx.common.entity.Transfer;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TransferMapper extends BaseMapper<Transfer> {


    void upsert(Transfer transfer);

}
