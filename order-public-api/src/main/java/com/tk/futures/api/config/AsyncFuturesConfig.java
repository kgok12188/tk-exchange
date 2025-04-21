package com.tk.futures.api.config;

import com.tx.common.vo.R;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.concurrent.ConcurrentHashMap;

@Configuration
public class AsyncFuturesConfig {

    private final ConcurrentHashMap<String, DeferredResult<R>> deferredResults = new ConcurrentHashMap<>();

    @Bean
    public ConcurrentHashMap<String, DeferredResult<R>> deferredResults() {
        return deferredResults;
    }

}
