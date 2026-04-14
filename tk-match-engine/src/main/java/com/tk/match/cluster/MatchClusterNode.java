package com.tk.match.cluster;

import com.tk.match.output.MatchResultSideChannel;
import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.client.AeronArchive;
import io.aeron.cluster.ConsensusModule;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.ErrorHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.tk.match.config.ClusterStackConfig;
import com.tk.match.config.MdcEgressConfig;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 启动并关闭双 MediaDriver + 双 Archive，完全隔离：
 * <ul>
 *   <li>集群侧：Cluster MediaDriver → Archive-1（共识日志 / 快照）→ Consensus → Container</li>
 *   <li>MDC 侧：MDC MediaDriver → Archive-2（spy 录制撮合结果）→ MatchResultEgress 客户端</li>
 * </ul>
 * {@link #start()} 同步拉起各组件；失败即释放资源并抛错。
 */
@Component
public class MatchClusterNode implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(MatchClusterNode.class);

    /**
     * 集群 Archive 本地 IPC 控制。
     */
    private static final String CLUSTER_ARCHIVE_LOCAL_CONTROL = "aeron:ipc?alias=archive-local-control";

    private final ClusterStackConfig cluster;
    private final MdcEgressConfig mdc;
    private final MatchResultSideChannel matchResultSideChannel;
    private final MatchClusteredService service;

    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile MediaDriver clusterMediaDriver;
    private volatile MediaDriver mdcMediaDriver;
    private volatile Archive clusterArchive;
    /**
     * MDC 侧 Archive：spy 录制撮合结果，与集群 Archive 完全隔离。
     */
    private volatile Archive mdcArchive;
    private volatile ConsensusModule consensusModule;
    private volatile ClusteredServiceContainer container;

    public MatchClusterNode(
            ClusterStackConfig clusterStackConfig,
            MdcEgressConfig mdcEgressConfig,
            MatchResultSideChannel matchResultEgressImpl,
            MatchClusteredService service) {
        this.cluster = clusterStackConfig;
        this.mdc = mdcEgressConfig;
        this.matchResultSideChannel = matchResultEgressImpl;
        this.service = service;
    }

    // ── SmartLifecycle ────────────────────────────────────────────────────────

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        cluster.ensureDirectories();
        mdc.ensureDirectories();
        log.info("Starting Aeron Cluster node: nodeId={} clusterAeronDir={} mdcAeronDir={} mdcArchiveDir={}",
                cluster.getNodeId(), cluster.getAeronDir(), mdc.getAeronDir(), mdc.getArchiveDir());

        try {
            launchAeronStack();
            log.info("Aeron cluster node is operational (nodeId={})", cluster.getNodeId());
        } catch (RuntimeException bootstrapException) {
            running.set(false);
            shutdownAll();
            throw bootstrapException;
        } catch (Exception exception) {
            running.set(false);
            shutdownAll();
            throw new IllegalStateException("Aeron cluster bootstrap failed", exception);
        }
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        log.info("Stopping Aeron Cluster node...");
        shutdownAll();
        log.info("Aeron Cluster node stopped");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    // ── Cluster bootstrap ─────────────────────────────────────────────────────

    private void launchAeronStack() throws Exception {
        ErrorHandler errorHandler = throwable ->
                log.error("Aeron internal error", throwable);

        // ── 1. 双 MediaDriver ──────────────────────────────────────────────
        clusterMediaDriver = MediaDriver.launch(clusterMediaDriverContext(errorHandler));
        mdcMediaDriver = MediaDriver.launch(mdcMediaDriverContext(errorHandler));

        // ── 2. 双 Archive ──────────────────────────────────────────────────
        clusterArchive = Archive.launch(clusterArchiveContext(errorHandler));
        mdcArchive = Archive.launch(mdcArchiveContext(errorHandler));

        // ── 3. Consensus + Container ──────────────────────────────────────
        consensusModule = ConsensusModule.launch(consensusContext(errorHandler));
        container = ClusteredServiceContainer.launch(containerContext(errorHandler));

        // ── 4. Egress 客户端连接 MDC Archive ──────────────────────────────
        matchResultSideChannel.connectAeronClientsOrThrow();
    }

    // ── Context builders ──────────────────────────────────────────────────────

    private MediaDriver.Context clusterMediaDriverContext(ErrorHandler errorHandler) {
        return new MediaDriver.Context()
                .aeronDirectoryName(cluster.getAeronDir())
                .threadingMode(ThreadingMode.SHARED)
                .termBufferSparseFile(false)
                .errorHandler(errorHandler)
                .dirDeleteOnStart(false)
                .dirDeleteOnShutdown(false);
    }

    private MediaDriver.Context mdcMediaDriverContext(ErrorHandler errorHandler) {
        return new MediaDriver.Context()
                .aeronDirectoryName(mdc.getAeronDir())
                .threadingMode(ThreadingMode.SHARED)
                .termBufferSparseFile(false)
                .errorHandler(errorHandler)
                .dirDeleteOnStart(false)
                .dirDeleteOnShutdown(false);
    }

    private Archive.Context clusterArchiveContext(ErrorHandler errorHandler) {
        return new Archive.Context()
                .aeronDirectoryName(cluster.getAeronDir())
                .archiveDir(new File(cluster.getArchiveDir()))
                .controlChannel(cluster.getArchiveControlChannel())
                .controlStreamId(cluster.getArchiveControlRequestStreamId())
                .replicationChannel(cluster.getArchiveReplicationChannel())
                .localControlChannel(CLUSTER_ARCHIVE_LOCAL_CONTROL)
                .localControlStreamId(cluster.getArchiveControlRequestStreamId() + 100)
                .recordingEventsEnabled(false)
                .threadingMode(ArchiveThreadingMode.SHARED)
                .errorHandler(errorHandler)
                .deleteArchiveOnStart(false);
    }

    /**
     * MDC 侧 Archive：挂在 MDC MediaDriver 上，供 spy 录制撮合结果。
     */
    private Archive.Context mdcArchiveContext(ErrorHandler errorHandler) {
        return new Archive.Context()
                .aeronDirectoryName(mdc.getAeronDir())
                .archiveDir(new File(mdc.getArchiveDir()))
                .controlChannel(mdc.getArchiveControlChannel())
                .controlStreamId(mdc.getArchiveControlStreamId())
                .replicationChannel(mdc.getArchiveReplicationChannel())
                .localControlChannel(MdcEgressConfig.ARCHIVE_LOCAL_CONTROL_CHANNEL)
                .localControlStreamId(mdc.getArchiveControlStreamId() + 100)
                .recordingEventsEnabled(false)
                .threadingMode(ArchiveThreadingMode.SHARED)
                .errorHandler(errorHandler)
                .deleteArchiveOnStart(false);
    }

    private ConsensusModule.Context consensusContext(ErrorHandler errorHandler) {
        return new ConsensusModule.Context()
                .aeronDirectoryName(cluster.getAeronDir())
                .clusterDir(new File(cluster.getClusterDir()))
                .clusterMemberId(cluster.getNodeId())
                .clusterMembers(cluster.aeronClusterMembers())
                .ingressChannel(cluster.getIngressChannel())
                .ingressStreamId(cluster.getIngressStreamId())
                .replicationChannel(cluster.getClusterReplicationChannel())
                .archiveContext(clusterEmbeddedArchiveClientContext())
                .errorHandler(errorHandler)
                .deleteDirOnStart(false);
    }

    private ClusteredServiceContainer.Context containerContext(ErrorHandler errorHandler) {
        return new ClusteredServiceContainer.Context()
                .aeronDirectoryName(cluster.getAeronDir())
                .clusterDir(new File(cluster.getClusterDir()))
                .clusteredService(service)
                .archiveContext(clusterEmbeddedArchiveClientContext())
                .errorHandler(errorHandler);
    }

    /**
     * Consensus / Container 嵌入式连 cluster Archive 的 IPC 上下文。
     */
    private AeronArchive.Context clusterEmbeddedArchiveClientContext() {
        int localRequestStreamId = cluster.getArchiveControlRequestStreamId() + 100;
        int localResponseStreamId = localRequestStreamId + 1;
        return new AeronArchive.Context()
                .aeronDirectoryName(cluster.getAeronDir())
                .controlRequestChannel(CLUSTER_ARCHIVE_LOCAL_CONTROL)
                .controlResponseChannel(CLUSTER_ARCHIVE_LOCAL_CONTROL)
                .controlRequestStreamId(localRequestStreamId)
                .controlResponseStreamId(localResponseStreamId);
    }

    // ── Shutdown ──────────────────────────────────────────────────────────────

    private void shutdownAll() {
        matchResultSideChannel.shutdown();
        closeQuietly(container);
        container = null;
        closeQuietly(consensusModule);
        consensusModule = null;
        closeQuietly(mdcArchive);
        mdcArchive = null;
        closeQuietly(clusterArchive);
        clusterArchive = null;
        closeQuietly(mdcMediaDriver);
        mdcMediaDriver = null;
        closeQuietly(clusterMediaDriver);
        clusterMediaDriver = null;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception exception) {
                log.warn("Error closing Aeron component", exception);
            }
        }
    }
}
