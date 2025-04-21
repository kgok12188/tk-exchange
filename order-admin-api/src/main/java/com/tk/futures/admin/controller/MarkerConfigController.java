package com.tk.futures.admin.controller;

import com.alibaba.fastjson.JSONObject;
import com.tx.common.entity.Coin;
import com.tx.common.entity.MarketConfig;
import com.tx.common.service.CoinService;
import com.tx.common.service.MarketConfigService;
import com.tx.common.vo.R;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/market")
public class MarkerConfigController {

    private final MarketConfigService marketConfigService;
    private final CoinService coinService;

    public MarkerConfigController(MarketConfigService marketConfigService, CoinService coinService) {
        this.marketConfigService = marketConfigService;
        this.coinService = coinService;
    }

    @PostMapping("/add")
    public R<Boolean> addMarker(@RequestBody JSONObject params) {
        MarketConfig marketConfig = params.toJavaObject(MarketConfig.class);
        marketConfig.setId(null);
        if (StringUtils.isEmpty(marketConfig.getName())) {
            return R.fail(400, "name is empty");
        }
        if (marketConfigService.lambdaQuery().eq(MarketConfig::getName, marketConfig.getName()).one() != null) {
            return R.fail(400, "交易比对已存在");
        }
        if (marketConfig.getMakerRate() == null || marketConfig.getMakerRate().compareTo(BigDecimal.ZERO) <= 0) {
            marketConfig.setMakerRate(new BigDecimal("0.005"));
        }
        if (marketConfig.getTakerRate() == null || marketConfig.getTakerRate().compareTo(BigDecimal.ZERO) <= 0) {
            marketConfig.setTakerRate(new BigDecimal("0.005"));
        }
        if (marketConfig.getLiqRate() == null || marketConfig.getLiqRate().compareTo(BigDecimal.ZERO) <= 0) {
            marketConfig.setLiqRate(new BigDecimal("0.006"));
        }
        if (marketConfig.getNumScale() == null || marketConfig.getNumScale() < 0) {
            return R.fail(400, "价格小数位错误");
        }
        Coin buyCoin = coinService.getById(marketConfig.getBuyCoinId());
        if (buyCoin == null) {
            return R.fail(400, "币种设置错误");
        }
        Coin sellCoin = coinService.getById(marketConfig.getSellCoinId());
        if (sellCoin == null) {
            return R.fail(400, "币种设置错误");
        }
        marketConfigService.save(marketConfig);
        return R.success(true);
    }

}
