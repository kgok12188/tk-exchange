package com.tk.futures.api.controller;

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
    public R<Boolean> addCoin(@RequestBody JSONObject params) {
        String coinName = params.getString("name");
        if (coinName == null) {
            return R.fail(400, "参数错误");
        }
        if (coinService.lambdaQuery().eq(Coin::getName, coinName).one() != null) {
            return R.fail(400, "该币种已存在");
        }
        Coin coin = new Coin();
        coin.setName(coinName);
        coinService.save(coin);
        return R.success(true);
    }

}
