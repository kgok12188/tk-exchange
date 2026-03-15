package com.tk.match.ha;

import com.tk.match.service.MatchManager;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.leader.LeaderLatch;
import org.apache.curator.framework.recipes.leader.LeaderLatchListener;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 基于 Zookeeper 的主从选举：仅当当前主节点宕机（会话断开、节点消失）时触发选举，新主接管。
 * 使用 Curator LeaderLatch，主持有 ephemeral 节点；主宕机后 ZK 删除节点，从节点竞争后唯一获选者成为新主。
 */
public class MatchLeaderElectionService {

    private static final Logger log = LoggerFactory.getLogger(MatchLeaderElectionService.class);

    private final String zookeeperServers;
    private final String latchPath;
    @Autowired
    private MatchManager matchManager;

    private volatile boolean running;
    private CuratorFramework client;
    private LeaderLatch leaderLatch;

    public MatchLeaderElectionService(String zookeeperServers, String latchPath) {
        this.zookeeperServers = zookeeperServers;
        this.latchPath = latchPath;
    }

    @PostConstruct
    public void start() {
        if (zookeeperServers == null || zookeeperServers.isEmpty()) {
            log.warn("match-engine leader election disabled: zookeeper servers not configured");
            return;
        }
        matchManager.notifyBecameSlave();
        try {
            client = CuratorFrameworkFactory.builder()
                    .connectString(zookeeperServers)
                    .retryPolicy(new ExponentialBackoffRetry(1000, 3))
                    .build();
            client.start();
            client.blockUntilConnected();

            leaderLatch = new LeaderLatch(client, latchPath);
            leaderLatch.addListener(new LeaderLatchListener() {
                @Override
                public void isLeader() {
                    HaStatus.setMaster(true);
                    matchManager.notifyBecameMaster();
                    log.info("match-engine became leader (latch path={})", latchPath);
                }

                @Override
                public void notLeader() {
                    HaStatus.setMaster(false);
                    matchManager.notifyBecameSlave();
                    log.info("match-engine lost leadership (latch path={})", latchPath);
                }
            });
            leaderLatch.start();
            running = true;
            log.info("match-engine leader election started zookeeper={} path={}", zookeeperServers, latchPath);
        } catch (Exception e) {
            log.error("match-engine leader election start failed", e);
            throw new RuntimeException("Leader election start failed", e);
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (leaderLatch != null) {
            try {
                leaderLatch.close();
            } catch (Exception e) {
                log.warn("LeaderLatch close error", e);
            }
            leaderLatch = null;
        }
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("CuratorFramework close error", e);
            }
            client = null;
        }
        HaStatus.setMaster(false);
        if (matchManager != null) {
            matchManager.notifyBecameSlave();
        }
        log.info("match-engine leader election stopped");
    }

    /**
     * 当前参与选主的节点数（LeaderLatch 路径下子节点数）。用于快照调度：主节点仅在参与数 ≤1 时打快照，否则由从节点打。
     * 未启用 ZK 或异常时返回 0。
     */
    public int getParticipantCount() {
        if (client == null || !running) return 0;
        try {
            return client.getChildren().forPath(latchPath).size();
        } catch (Exception e) {
            if (log.isTraceEnabled()) {
                log.trace("getParticipantCount failed path={}", latchPath, e);
            }
            return 0;
        }
    }
}
