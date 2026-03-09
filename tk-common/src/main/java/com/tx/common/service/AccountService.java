package com.tx.common.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tx.common.entity.Account;
import com.tx.common.mapper.AccountMapper;
import org.springframework.stereotype.Service;

@Service
public class AccountService extends ServiceImpl<AccountMapper, Account> {
}
