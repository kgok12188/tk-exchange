package com.tk.match.config;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.io.File;

/**
 * {@code match.mdc}：MatchResult MDC MediaDriver、本地 Archive（spy 录制）及回放参数。
 * Archive 与 publication 在同一 MDC MediaDriver 上，通过 {@code aeron-spy:} + LOCAL 零拷贝录制。
 */
@Getter
@Setter
@ToString
@ConfigurationProperties(prefix = "match.mdc", ignoreUnknownFields = true)
public class MdcEgressConfig {

    /** MDC Archive 本地 IPC 控制别名；与 cluster Archive 的 IPC 别名隔离。 */
    public static final String ARCHIVE_LOCAL_CONTROL_CHANNEL = "aeron:ipc?alias=mdc-archive-control";

    public MdcEgressConfig() {
    }

    /** MDC MediaDriver 目录，须与 {@code match.cluster.aeron-dir} 不同。 */
    private String aeronDir = "/tmp/aeron-mdc";

    /** MatchResult publication channel（MDC 动态控制）。 */
    private String channel = "aeron:udp?control=0.0.0.0:40000|control-mode=dynamic";

    /** MatchResult publication stream id。 */
    private int streamId = 100;

    /** MDC 侧 Archive 数据目录（spy 录制的撮合结果持久化于此）。 */
    private String archiveDir = "/tmp/match-result";

    /** MDC 侧 Archive 外部控制（Aeron 1.50+ 必须 UDP；仅配置用，in-process 走 IPC）。 */
    private String archiveControlChannel = "aeron:udp?endpoint=0.0.0.0:8030";

    /** MDC 侧 Archive 复制（Aeron 1.50+ 必填 UDP；单节点不使用但必须配置）。 */
    private String archiveReplicationChannel = "aeron:udp?endpoint=0.0.0.0:8031";

    /** MDC 侧 Archive 外部控制 stream id（local control = 此值 + 100）。 */
    private int archiveControlStreamId = 30;

    /** Archive 回放 channel（IPC，MDC MediaDriver 本地）。 */
    private String replayChannel = "aeron:ipc?alias=match-replay";

    private int replayStreamId = 103;

    public void ensureDirectories() {
        mkdirs(aeronDir);
        mkdirs(archiveDir);
    }

    private static void mkdirs(String path) {
        File directory = new File(path);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Cannot create directory: " + path);
        }
    }
}
