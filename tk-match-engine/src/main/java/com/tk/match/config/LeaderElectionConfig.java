package com.tk.match.config;

import com.tk.match.compare.MatchResultMasterFileQueue;
import com.tk.match.ha.MatchLeaderElectionService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/**
 * 注册 ZK 主从选举服务与 MatchResultMasterFileQueue。当 match.zookeeper-servers 未配置或为空时，两者 start 时 no-op。
 */
@Configuration
public class LeaderElectionConfig {

    @Bean
    public MatchLeaderElectionService matchLeaderElectionService(MatchEngineConfig matchEngineConfig) {
        String servers = matchEngineConfig.getZookeeperServers();
        String path = matchEngineConfig.getLeaderLatchPath();
        if (StringUtils.isEmpty(path)) {
            throw new IllegalArgumentException("match.leader-latch-path cannot be empty");
        }
        return new MatchLeaderElectionService(servers, path);
    }

    @Bean
    @DependsOn("matchManager")
    public MatchResultMasterFileQueue matchResultMasterFileQueue(@Value("${kafka.servers:localhost:9092}") String bootstrapServers) {
        return new MatchResultMasterFileQueue(bootstrapServers);
    }

}
