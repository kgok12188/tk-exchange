package com.tk.match.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Getter
@Component
@ConfigurationProperties(prefix = "match")
public class MatchEngineConfig {

    @Setter
    private int ringBufferNumbers = Math.min(Runtime.getRuntime().availableProcessors(), 4);

    private List<String> symbols = new ArrayList<>();

    /**
     * 快照目录（共享磁盘路径）；为空或 null 时禁用快照与恢复。
     */
    @Setter
    private String snapshotDir = "";

    /**
     * 是否启用定时打快照。
     */
    @Setter
    private boolean snapshotEnabled = false;

    /**
     * 定时打快照间隔（分钟）。
     */
    @Setter
    private int snapshotIntervalMinutes = 5;

    /**
     * 定时打快照间隔（毫秒），优先于 snapshotIntervalMinutes。
     */
    @Setter
    private long snapshotIntervalMs = 300_000L;

    /**
     * 文件队列根目录（baseDir）。非空时：从节点自身产出的 MatchResponse 写入 baseDir + "slave"（MatchResultSlaveFileQueue）；
     * MatchResultMasterFileQueue 消费的 match_result_* 写入 baseDir + "master"（MatchResultMasterConsumedFileQueue）。每处均为每币一个 Chronicle Queue，格式 orderReqOffset + payload。
     */
    @Setter
    private String fileQueueDir = "/tmp/match-engine/";

    /**
     * 从节点 slave 文件队列保留：超过该小时数未写入的 symbol 目录将被清理（0 表示不按空闲清理）。
     */
    @Setter
    private long fileQueueSlaveRetentionIdleHours = 0L;

    /**
     * 从节点 slave 单 symbol 队列目录最大字节数，超过则清理该 symbol 队列（下次写入会新建；0 表示不按大小清理）。
     */
    @Setter
    private long fileQueueSlaveMaxBytesPerSymbol = 0L;

    /**
     * 从节点 slave 文件队列保留清理定时任务间隔（毫秒）。默认 3600000（1 小时）。
     */
    @Setter
    private long fileQueueRetentionIntervalMs = 3600000L;

    /**
     * Zookeeper 连接串（如 127.0.0.1:2181）；为空或未配置时不启用主从选举，本节点不参与选主。
     */
    @Setter
    private String zookeeperServers = "";

    /**
     * 主从选举在 ZK 上的节点路径；仅当 zookeeperServers 非空时生效。
     */
    @Setter
    private String leaderLatchPath = "/match-engine/leader";

    /**
     * 是否启用主从文件队列状态机一致性抽样比对（定时任务）。仅当 fileQueueDir 非空且本节点为从时有效。
     */
    @Setter
    private boolean consistencyCheckEnabled = false;

    /**
     * 一致性比对定时任务间隔（毫秒）。
     */
    @Setter
    private long consistencyCheckIntervalMs = 60_000L;

    /**
     * 一致性比对时每个 symbol 倒推比对的条数 N（区间 (end-N, end]）。
     */
    @Setter
    private int consistencyCheckSampleSize = 50;

    public void setSymbols(List<String> symbols) {
        this.symbols = symbols != null ? symbols : new ArrayList<>();
    }

}
