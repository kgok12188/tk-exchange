package com.tk.match.config;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.io.File;

/** {@code match.cluster}：集群 MediaDriver、Archive、Consensus、Container 共用目录与网络参数。 */
@Getter
@Setter
@ToString
@ConfigurationProperties(prefix = "match.cluster")
public class ClusterStackConfig {

    public ClusterStackConfig() {
    }

    private int nodeId = 0;

    /**
     * 成员紧凑串，{@code id=host:ingress:consensus:log:catchup:archive}，多条 {@code |}；
     * {@link #aeronClusterMembers()} 转为 {@link io.aeron.cluster.ClusterMember#parse(String)} 格式。
     */
    private String clusterMembers = "0=localhost:20110:20220:20330:20440:8010";

    private String archiveDir = "/tmp/match-engine/archive";

    private String clusterDir = "/tmp/match-engine/cluster";

    /** 集群 MediaDriver 目录；{@link io.aeron.archive.client.AeronArchive} 与嵌入 Archive 控制均用此路径。 */
    private String aeronDir = "/tmp/aeron-match";

    /** 预留，当前出口未引用。 */
    private int ipcStreamId = 101;

    /** 预留。 */
    private int archiveStreamId = 102;

    private int archiveControlRequestStreamId = 10;

    private int archiveControlResponseStreamId = 11;

    /** Archive 对外控制，Aeron 1.50+ 须 UDP；端口与 {@link #clusterMembers} 中 archive 口一致。 */
    private String archiveControlChannel = "aeron:udp?endpoint=0.0.0.0:8010";

    /** Archive 复制，1.50+ 必填 UDP，端口勿与控制冲突。 */
    private String archiveReplicationChannel = "aeron:udp?endpoint=0.0.0.0:8020";

    /** Consensus 日志/快照复制，1.50+ 必填 UDP。 */
    private String clusterReplicationChannel = "aeron:udp?endpoint=0.0.0.0:8021";

    /** Consensus ingress，1.50+ 必填；端口与 {@link #clusterMembers} 中 ingress 一致。 */
    private String ingressChannel = "aeron:udp?endpoint=0.0.0.0:20110|term-length=128k";

    private int ingressStreamId = 101;

    /** 同 driver 内 IPC publication 别名（与 {@link #SPY_CHANNEL} 配套调试用）。 */
    public static final String IPC_CHANNEL = "aeron:ipc?alias=match-result";

    /** 同 driver 内 spy，仅调试用；跨 driver 归档见出口侧 REMOTE。 */
    public static final String SPY_CHANNEL = "aeron-spy:" + IPC_CHANNEL;

    /** 展开后的 {@link io.aeron.cluster.ConsensusModule.Context#clusterMembers(String)} 入参。 */
    public String aeronClusterMembers() {
        return ClusterMembersCompactFormat.toAeronCanonical(clusterMembers);
    }

    public void ensureDirectories() {
        mkdirs(archiveDir);
        mkdirs(clusterDir);
        mkdirs(aeronDir);
    }

    private static void mkdirs(String path) {
        File directory = new File(path);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Cannot create directory: " + path);
        }
    }
}
