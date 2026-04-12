package com.tk.match;

import com.tk.match.config.ClusterStackConfig;
import com.tk.match.config.MdcEgressConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 撮合引擎入口：无 Web，Aeron 集群与 MDC 由 {@link com.tk.match.cluster.MatchClusterNode} 在 {@link org.springframework.context.SmartLifecycle} 中拉起。
 * <p>
 * 配置：{@code match.cluster.*} → {@link ClusterStackConfig}；{@code match.mdc.*} → {@link MdcEgressConfig}（含 Archive MediaDriver 目录等出口参数）。
 * <p>
 * 示例：{@code java -jar tk-match-engine.jar --match.cluster.archive-dir=/data/a --match.mdc.aeron-dir=/data/mdc}
 */
@SpringBootApplication
@EnableConfigurationProperties({ClusterStackConfig.class, MdcEgressConfig.class})
public class MatchApplication {

    public static void main(String[] args) {
        SpringApplication.run(MatchApplication.class, args);
    }
}
