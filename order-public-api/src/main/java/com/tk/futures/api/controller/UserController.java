package com.tk.futures.api.controller;

import com.alibaba.fastjson.JSONObject;
import com.tk.futures.api.async.AsyncService;
import com.tx.common.entity.Account;
import com.tx.common.entity.Coin;
import com.tx.common.entity.User;
import com.tx.common.service.AccountService;
import com.tx.common.service.CoinService;
import com.tx.common.service.TransferService;
import com.tx.common.service.UserService;
import com.tx.common.vo.R;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;
import java.util.List;

@RestController
@RequestMapping("/user")
public class UserController {

    private final TransferService transferService;
    private final UserService userService;
    private final CoinService coinService;
    private final AccountService accountService;
    private final AsyncService asyncService;

    public UserController(UserService userService, CoinService coinService, AccountService accountService, AsyncService asyncService, TransferService transferService) {
        this.userService = userService;
        this.coinService = coinService;
        this.accountService = accountService;
        this.asyncService = asyncService;
        this.transferService = transferService;
    }


    @PostMapping("/add")
    @Transactional
    public R<Boolean> addUser(@RequestBody JSONObject params) {
        Long uid = params.getLong("uid");
        User dbUser = userService.getById(uid);
        if (dbUser != null) {
            return R.success(true);
        }
        User user = new User();
        user.setId(uid);
        user.setStatus(1);
        userService.save(user);
        List<Coin> list = coinService.lambdaQuery().list();
        for (Coin coin : list) {
            Account account = new Account();
            account.setUid(uid);
            account.setTxid(0L);
            account.setMtime(new Date());
            account.setCtime(new Date());
            account.setCoinId(coin.getId());
            account.setCoinName(coin.getName());
            accountService.save(account);
        }
        return R.success(true);
    }

}
