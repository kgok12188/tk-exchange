package com.tk.futures.ha;

import com.tk.futures.slot.SettlementSlotManager;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.leader.LeaderLatch;
import org.apache.curator.framework.recipes.leader.LeaderLatchListener;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * trading-server 基于 Zookeeper 的主从选举（cluster HA）。
 * <p>
 * - leader: 广播 {@code MASTER} 给本实例内所有 slot worker
 * - follower: 广播 {@code SLAVE} 给本实例内所有 slot worker
 * <p>
 * 选主禁用条件：
 * - zookeeper.servers 为空
 * - trading.leader-latch-path 为空
 */
@Service
@DependsOn("settlementSlotManager")
public class TradingLeaderElectionService {

    private static final Logger log = LoggerFactory.getLogger(TradingLeaderElectionService.class);

    private final String zookeeperServers;
    private final String leaderLatchPath;
    private final SettlementSlotManager slotManager;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private CuratorFramework client;
    private LeaderLatch leaderLatch;

    public TradingLeaderElectionService(SettlementSlotManager slotManager, @Value("${zookeeper.servers:}") String zookeeperServers,
                                        @Value("${trading.leader-latch-path:}") String leaderLatchPath) {
        this.slotManager = slotManager;
        this.zookeeperServers = zookeeperServers;
        this.leaderLatchPath = leaderLatchPath;
    }

    @PostConstruct
    public void start() {
        if (zookeeperServers == null || zookeeperServers.isBlank() || leaderLatchPath == null || leaderLatchPath.isBlank()) {
            log.warn("trading-server leader election disabled: zookeeper.servers/leader-latch-path not configured");
            // 默认先作为从节点，不写 Kafka
            waitSlotManagerStartedAndBroadcast(false);
            return;
        }

        waitSlotManagerStartedAndBroadcast(false);

        try {
            client = CuratorFrameworkFactory.builder()
                    .connectString(zookeeperServers)
                    .retryPolicy(new ExponentialBackoffRetry(1000, 3))
                    .build();
            client.start();
            client.blockUntilConnected(10, TimeUnit.SECONDS);

            leaderLatch = new LeaderLatch(client, leaderLatchPath);
            leaderLatch.addListener(new LeaderLatchListener() {
                @Override
                public void isLeader() {
                    slotManager.broadcastRole(true);
                    log.info("trading-server became leader (latch path={})", leaderLatchPath);
                }

                @Override
                public void notLeader() {
                    slotManager.broadcastRole(false);
                    log.info("trading-server lost leadership (latch path={})", leaderLatchPath);
                }
            });

            leaderLatch.start();
            running.set(true);
            log.info("trading-server leader election started, zookeeper={}, path={}", zookeeperServers, leaderLatchPath);
        } catch (Exception e) {
            log.error("trading-server leader election start failed", e);
            throw new RuntimeException("Leader election start failed", e);
        }
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        try {
            if (leaderLatch != null) {
                leaderLatch.close();
            }
        } catch (Exception e) {
            log.warn("LeaderLatch close error", e);
        }
        try {
            if (client != null) {
                client.close();
            }
        } catch (Exception e) {
            log.warn("CuratorFramework close error", e);
        }
        slotManager.broadcastRole(false);
        log.info("trading-server leader election stopped");
    }

    private void waitSlotManagerStartedAndBroadcast(boolean master) {
        long deadlineMs = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadlineMs) {
            if (slotManager.isStarted()) {
                slotManager.broadcastRole(master);
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        // 超时也尝试广播一次，避免卡死；若此时 ringBuffer 尚未初始化，调用方需要保证启动顺序
        slotManager.broadcastRole(master);
    }
}

