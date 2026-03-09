package com.tk.futures.model;

import com.tx.common.entity.MarketConfig;
import org.redisson.api.LocalCachedMapOptions;

public class MarketCachedMapOptions extends LocalCachedMapOptions<Integer, MarketConfig> {

    public static MarketCachedMapOptions defaults() {
        MarketCachedMapOptions options = new MarketCachedMapOptions();
        options.cacheSize(10000);
        options.cacheProvider(CacheProvider.REDISSON);
        options.storeMode(StoreMode.LOCALCACHE_REDIS);
        options.syncStrategy(SyncStrategy.INVALIDATE);
        options.reconnectionStrategy(ReconnectionStrategy.LOAD);
        options.writeMode(WriteMode.WRITE_THROUGH);
        options.evictionPolicy(EvictionPolicy.LRU);
        return options;
    }

}
