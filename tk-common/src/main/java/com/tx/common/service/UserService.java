package com.tx.common.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tx.common.entity.User;
import com.tx.common.mapper.UserMapper;
import org.springframework.stereotype.Service;

@Service
public class UserService extends ServiceImpl<UserMapper, User> {

}
