package com.tk.futures.admin.controller;

import com.alibaba.fastjson.JSONObject;
import com.tx.common.entity.Coin;
import com.tx.common.service.CoinService;
import com.tx.common.vo.R;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/coin")
public class CoinController {

    @Autowired
    private CoinService coinService;


    @PostMapping("/add")
    public R<Boolean> add(@RequestBody JSONObject params) {
        Coin coin = params.toJavaObject(Coin.class);
        coin.setId(null);
        Coin dbCoin = coinService.lambdaQuery().eq(Coin::getName, coin.getName()).one();
        if (dbCoin != null) {
            return R.fail(400, "该币种已存在");
        }
        coinService.save(coin);
        return R.success(true);
    }

}
