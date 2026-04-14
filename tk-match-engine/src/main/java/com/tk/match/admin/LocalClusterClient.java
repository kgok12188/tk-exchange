package com.tk.match.admin;

import com.tk.match.config.ClusterStackConfig;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.driver.MediaDriver;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 内嵌在 match-engine 节点中的 Aeron Cluster 客户端，将 HTTP admin 命令推送到 Raft ingress。
 *
 * <p>Leader 和 Follower 节点均持有此客户端；Aeron Cluster 客户端会自动将消息路由到当前 Leader。
 *
 * <p>Phase 高于 {@link com.tk.match.cluster.MatchClusterNode}，确保集群 MediaDriver
 * 已启动后再连接。
 */
@Component
public class LocalClusterClient implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(LocalClusterClient.class);

    private final ClusterStackConfig clusterConfig;

    private volatile AeronCluster aeronCluster;
    private volatile MediaDriver clientMediaDriver;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public LocalClusterClient(ClusterStackConfig clusterConfig) {
        this.clusterConfig = clusterConfig;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        log.info("Connecting LocalClusterClient to ingress...");

        String ingressEndpoints = buildIngressEndpoints(clusterConfig.getClusterMembers());

        clientMediaDriver = MediaDriver.launchEmbedded();

        aeronCluster = AeronCluster.connect(
                new AeronCluster.Context()
                        .aeronDirectoryName(clientMediaDriver.aeronDirectoryName())
                        .ingressChannel("aeron:udp")
                        .ingressEndpoints(ingressEndpoints)
                        .egressListener((clusterSessionId, timestamp, buffer, offset, length, header) -> {
                            // admin 命令的 MatchResult 通过 onSessionMessage 完成 future，此处无需处理
                        })
                        .idleStrategy(new SleepingMillisIdleStrategy(1)));

        log.info("LocalClusterClient connected (ingressEndpoints={})", ingressEndpoints);
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        log.info("Stopping LocalClusterClient...");
        if (aeronCluster != null) {
            aeronCluster.close();
            aeronCluster = null;
        }
        if (clientMediaDriver != null) {
            clientMediaDriver.close();
            clientMediaDriver = null;
        }
        log.info("LocalClusterClient stopped");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    /**
     * 将 SBE 编码的 admin 命令发送到 Raft ingress。
     * 若集群未就绪，进行有限次重试后抛出异常。
     */
    public void offer(DirectBuffer buffer, int offset, int length) {
        AeronCluster cluster = this.aeronCluster;
        if (cluster == null) {
            throw new IllegalStateException("LocalClusterClient is not started");
        }
        long result;
        int retries = 0;
        do {
            result = cluster.offer(buffer, offset, length);
            if (result > 0) {
                return;
            }
            if (result == io.aeron.Publication.NOT_CONNECTED) {
                throw new IllegalStateException("Cluster ingress not connected");
            }
            retries++;
            try {
                Thread.sleep(1);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while offering to cluster", interruptedException);
            }
        } while (retries < 500);

        throw new IllegalStateException("Failed to offer admin command to cluster after retries, result=" + result);
    }

    /**
     * 从紧凑格式提取 ingress endpoints：{@code "0=host:ingress:..."} → {@code "0=host:ingress"}。
     */
    static String buildIngressEndpoints(String compactMembers) {
        String[] members = compactMembers.trim().split("\\|");
        StringBuilder endpoints = new StringBuilder();
        for (int idx = 0; idx < members.length; idx++) {
            if (idx > 0) {
                endpoints.append(',');
            }
            String member = members[idx].trim();
            int equalsPos = member.indexOf('=');
            String memberId = member.substring(0, equalsPos).trim();
            String hostAndPorts = member.substring(equalsPos + 1).trim();
            String[] parts = hostAndPorts.split(":");
            endpoints.append(memberId).append('=').append(parts[0]).append(':').append(parts[1]);
        }
        return endpoints.toString();
    }
}
