package com.tx.common.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tx.common.entity.MarketConfig;
import com.tx.common.mapper.MarkerConfigMapper;
import org.redisson.api.RBucket;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Service
public class MarketConfigService extends ServiceImpl<MarkerConfigMapper, MarketConfig> {

    private final RedissonClient redissonClient;

    public MarketConfigService(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    public BigDecimal getPrice(int contractId) {
        String key = String.format("tag.mark.price.%s", contractId);
        RBucket<String> bucket = redissonClient.getBucket(key);
        String price = bucket.get();
        return new BigDecimal(price);
    }

    public Map<Integer, BigDecimal> getAllPrice() {
        String key = String.format("tag.mark.price.%s", "all");
        RMap<String, String> map = redissonClient.getMap(key);
        Map<Integer, BigDecimal> ret = new HashMap<>();
        for (Map.Entry<String, String> kv : map.entrySet()) {
            ret.put(Integer.valueOf(kv.getKey()), new BigDecimal(kv.getValue()));
        }
        return ret;
    }

}
