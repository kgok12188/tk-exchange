package com.tk.futures.admin.controller;

import com.alibaba.fastjson2.JSONObject;
import com.tx.common.entity.Coin;
import com.tx.common.entity.MarketConfig;
import com.tx.common.service.CoinService;
import com.tx.common.service.MarketConfigService;
import com.tx.common.vo.R;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 交易对管理 REST API。
 * <ul>
 *   <li>{@code POST /market/add}     → 上币（持久化 DB + HTTP 调用 match-engine /admin/openMarket）</li>
 *   <li>{@code POST /market/close}   → 下币（DB 标记 + /admin/closeMarket）</li>
 *   <li>{@code POST /market/update}  → 更新撮合规则（DB 更新 + /admin/updateMarket）</li>
 * </ul>
 */
@RestController
@RequestMapping("/market")
public class MarkerConfigController {

    private static final Logger log = LoggerFactory.getLogger(MarkerConfigController.class);

    private final MarketConfigService marketConfigService;
    private final CoinService coinService;
    private final RestTemplate restTemplate;

    @Value("${match-engine.admin-url:http://localhost:8090}")
    private String matchEngineAdminUrl;

    public MarkerConfigController(MarketConfigService marketConfigService,
                                  CoinService coinService,
                                  RestTemplate restTemplate) {
        this.marketConfigService = marketConfigService;
        this.coinService = coinService;
        this.restTemplate = restTemplate;
    }

    /**
     * 上币：持久化交易对配置，并调用 match-engine /admin/openMarket。
     */
    @PostMapping("/add")
    public R<Boolean> addMarket(@RequestBody JSONObject params) {
        MarketConfig marketConfig = params.toJavaObject(MarketConfig.class);
        marketConfig.setId(null);
        if (StringUtils.isEmpty(marketConfig.getName())) {
            return R.fail(400, "name is empty");
        }
        if (marketConfigService.lambdaQuery().eq(MarketConfig::getName, marketConfig.getName()).one() != null) {
            return R.fail(400, "交易对已存在");
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
            return R.fail(400, "buyCoin 设置错误");
        }
        Coin sellCoin = coinService.getById(marketConfig.getSellCoinId());
        if (sellCoin == null) {
            return R.fail(400, "sellCoin 设置错误");
        }
        marketConfigService.save(marketConfig);

        Map<String, Object> body = new HashMap<>();
        body.put("symbolId", marketConfig.getId());
        body.put("symbolName", marketConfig.getSymbol());
        body.put("priceScale", marketConfig.getPriceScale() != null ? marketConfig.getPriceScale() : 2);
        body.put("qtyScale", marketConfig.getNumScale() != null ? marketConfig.getNumScale().intValue() : 8);
        body.put("minQty", marketConfig.getNumMin());
        body.put("minTradeQuoteAmount", marketConfig.getMinTradeQuoteAmount());
        body.put("configVersion", System.currentTimeMillis());

        return callMatchEngineAdmin("/admin/openMarket", body, "OpenMarketCommand");
    }

    /**
     * 下币：调用 match-engine /admin/closeMarket。
     */
    @PostMapping("/close")
    public R<Boolean> closeMarket(@RequestBody JSONObject params) {
        Integer symbolId = params.getInteger("symbolId");
        Long configVersion = params.getLong("configVersion");
        Boolean force = params.getBoolean("force");
        if (symbolId == null || symbolId <= 0) {
            return R.fail(400, "symbolId 无效");
        }
        if (configVersion == null) {
            configVersion = System.currentTimeMillis();
        }

        Map<String, Object> body = new HashMap<>();
        body.put("symbolId", symbolId);
        body.put("configVersion", configVersion);
        body.put("force", Boolean.TRUE.equals(force));

        return callMatchEngineAdmin("/admin/closeMarket", body, "CloseMarketCommand");
    }

    /**
     * 更新撮合规则：DB 更新后调用 match-engine /admin/updateMarket。
     */
    @PostMapping("/update")
    public R<Boolean> updateMarket(@RequestBody JSONObject params) {
        MarketConfig marketConfig = params.toJavaObject(MarketConfig.class);
        Boolean force = params.getBoolean("force");
        if (marketConfig.getId() == null || marketConfig.getId() <= 0) {
            return R.fail(400, "symbolId (id) 无效");
        }

        MarketConfig existing = marketConfigService.getById(marketConfig.getId());
        if (existing == null) {
            return R.fail(404, "交易对不存在");
        }

        if (marketConfig.getNumScale() != null) {
            existing.setNumScale(marketConfig.getNumScale());
        }
        if (marketConfig.getNumMin() != null) {
            existing.setNumMin(marketConfig.getNumMin());
        }
        if (marketConfig.getMinTradeQuoteAmount() != null) {
            existing.setMinTradeQuoteAmount(marketConfig.getMinTradeQuoteAmount());
        }
        marketConfigService.updateById(existing);

        Map<String, Object> body = new HashMap<>();
        body.put("symbolId", existing.getId());
        body.put("priceScale", existing.getPriceScale() != null ? existing.getPriceScale() : 2);
        body.put("qtyScale", existing.getNumScale() != null ? existing.getNumScale().intValue() : 8);
        body.put("minQty", existing.getNumMin());
        body.put("minTradeQuoteAmount", existing.getMinTradeQuoteAmount());
        body.put("configVersion", System.currentTimeMillis());
        body.put("force", Boolean.TRUE.equals(force));

        return callMatchEngineAdmin("/admin/updateMarket", body, "UpdateMarketCommand");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private R<Boolean> callMatchEngineAdmin(String path, Map<String, Object> body, String commandName) {
        String url = matchEngineAdminUrl + path;
        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(url, body, Map.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                Map<String, Object> responseBody = response.getBody();
                Boolean success = responseBody != null && Boolean.TRUE.equals(responseBody.get("success"));
                if (success) {
                    return R.success(true);
                }
                String message = responseBody != null ? String.valueOf(responseBody.get("message")) : "unknown";
                return R.fail(422, commandName + " rejected: " + message);
            }
            return R.fail(response.getStatusCode().value(), commandName + " failed: HTTP " + response.getStatusCode());
        } catch (RestClientException restClientException) {
            log.error("Failed to call match-engine {} url={}", commandName, url, restClientException);
            return R.fail(503, "match-engine 不可达: " + restClientException.getMessage());
        }
    }
}
