package com.tx.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tx.common.entity.Position;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PositionMapper extends BaseMapper<Position> {

    void upsert(Position position);

}
