package com.tx.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tx.common.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
