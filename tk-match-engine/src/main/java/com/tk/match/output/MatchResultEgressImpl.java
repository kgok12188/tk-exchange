package com.tk.match.output;

import com.tk.match.config.MdcEgressConfig;
import com.tk.protocol.sbe.generated.MatchResultDecoder;
import com.tk.protocol.sbe.generated.MessageHeaderDecoder;
import io.aeron.Aeron;
import io.aeron.ExclusivePublication;
import io.aeron.Image;
import io.aeron.Subscription;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.codecs.SourceLocation;
import io.aeron.logbuffer.FragmentHandler;
import org.agrona.ErrorHandler;
import org.agrona.MutableDirectBuffer;
import org.agrona.collections.MutableLong;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.YieldingIdleStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * MatchResult 出口：仅依赖 {@link MdcEgressConfig}，所有 Aeron 资源均在 MDC MediaDriver 上。
 * 通过 {@code aeron-spy:} + {@link SourceLocation#LOCAL} 零拷贝录制 publication 流。
 */
@Component
public class MatchResultEgressImpl implements MatchResultEgress {

    private static final Logger log = LoggerFactory.getLogger(MatchResultEgressImpl.class);

    private final MdcEgressConfig mdcConfig;
    private final IdleStrategy idleStrategy = new YieldingIdleStrategy();

    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    private final MatchResultDecoder matchResultDecoder = new MatchResultDecoder();

    /** 唯一 Aeron 连接：MDC MediaDriver（publication + Archive 客户端均在此 driver 上）。 */
    private volatile Aeron mdcAeron;
    private volatile AeronArchive aeronArchive;

    private ExclusivePublication matchResultPublication;

    private long recordingId = -1L;
    /** Archive 已覆盖的最大 matchSeq；仅当 {@code matchSeq > lastRecordedUpperBound} 才写 publication。 */
    private long lastRecordedUpperBound = -1L;
    private final TreeMap<Long, Long> matchSeqIndex = new TreeMap<>();

    public MatchResultEgressImpl(MdcEgressConfig mdcEgressConfig) {
        this.mdcConfig = mdcEgressConfig;
    }

    /**
     * 由 {@link com.tk.match.cluster.MatchClusterNode} 在 MDC MediaDriver 与 MDC Archive 就绪后调用。
     */
    public synchronized void connectAeronClientsOrThrow() {
        if (mdcAeron != null && aeronArchive != null) {
            return;
        }
        closeQuietly();
        ErrorHandler errorHandler = throwable ->
                log.error("Aeron internal error (match-result egress)", throwable);
        try {
            Aeron.Context mdcCtx = new Aeron.Context()
                    .aeronDirectoryName(mdcConfig.getAeronDir())
                    .errorHandler(errorHandler);
            mdcAeron = Aeron.connect(mdcCtx);

            aeronArchive = AeronArchive.connect(archiveClientContext());
            log.info("MatchResult egress connected: mdcAeronDir={} channel={} streamId={}",
                    mdcConfig.getAeronDir(), matchResultChannel(), matchResultStreamId());
        } catch (Exception exception) {
            closeQuietly();
            throw new IllegalStateException(
                    "Failed to connect Aeron for match-result egress (mdcDir=" + mdcConfig.getAeronDir() + ")",
                    exception);
        }
    }

    private String matchResultChannel() {
        return mdcConfig.getChannel();
    }

    private int matchResultStreamId() {
        return mdcConfig.getStreamId();
    }

    /** spy 录制 channel：读取同 driver 上 publication 的 log buffer。 */
    private String spyChannel() {
        return "aeron-spy:" + matchResultChannel();
    }

    /** MDC Archive 本地 IPC 控制上下文（stream id 与 Archive 的 localControlStreamId 对齐）。 */
    private AeronArchive.Context archiveClientContext() {
        int localRequestStreamId = mdcConfig.getArchiveControlStreamId() + 100;
        int localResponseStreamId = localRequestStreamId + 1;
        return new AeronArchive.Context()
                .aeronDirectoryName(mdcConfig.getAeronDir())
                .controlRequestChannel(MdcEgressConfig.ARCHIVE_LOCAL_CONTROL_CHANNEL)
                .controlResponseChannel(MdcEgressConfig.ARCHIVE_LOCAL_CONTROL_CHANNEL)
                .controlRequestStreamId(localRequestStreamId)
                .controlResponseStreamId(localResponseStreamId);
    }

    private void closeQuietly() {
        closeResourceQuietly(aeronArchive);
        aeronArchive = null;
        closeResourceQuietly(mdcAeron);
        mdcAeron = null;
    }

    private void closeResourceQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception exception) {
            log.warn("Error closing Aeron resource", exception);
        }
    }

    private void ensureConnected() {
        if (mdcAeron != null && aeronArchive != null) {
            return;
        }
        connectAeronClientsOrThrow();
    }

    /** 快照恢复：比对共识 nextSeq 与 Archive 上界，必要时 purge 录制并重置 dedup。 */
    private void check(long consensusNextMatchSeq) {
        replayForDedup();
        long lastArchivedMatchSeq = lastRecordedUpperBound;
        if (consensusNextMatchSeq > lastArchivedMatchSeq + 1) {
            log.warn("Archive mismatch: consensusNextMatchSeq={} > lastArchivedMatchSeq+1={}; purging",
                    consensusNextMatchSeq, lastArchivedMatchSeq + 1);
            purgeRecording();
            matchSeqIndex.clear();
            lastRecordedUpperBound = consensusNextMatchSeq - 1;
            log.info("After purge: dedup upperBound={} (next emit matchSeq={})",
                    lastRecordedUpperBound, consensusNextMatchSeq);
            return;
        }
        log.info("Archive check OK: consensusNextMatchSeq={} lastArchivedMatchSeq={}",
                consensusNextMatchSeq, lastArchivedMatchSeq);
    }

    private void purgeRecording() {
        if (aeronArchive == null) {
            return;
        }
        if (recordingId >= 0) {
            try {
                aeronArchive.purgeRecording(recordingId);
                aeronArchive.checkForErrorResponse();
            } catch (Exception exception) {
                log.error("purgeRecording failed recordingId={}", recordingId, exception);
                throw new IllegalStateException("Failed to purge MatchResult archive recording", exception);
            }
            log.info("Purged archive recordingId={}", recordingId);
        }
        recordingId = -1L;
    }

    private void replayForDedup() {
        if (mdcAeron == null || aeronArchive == null) {
            throw new IllegalStateException("Aeron not connected; cannot replay for dedup");
        }

        long foundRecordingId = findExistingRecording(aeronArchive);
        if (foundRecordingId < 0) {
            log.info("No existing Archive recording; dedup upperBound=-1");
            recordingId = -1L;
            lastRecordedUpperBound = -1L;
            matchSeqIndex.clear();
            return;
        }

        recordingId = foundRecordingId;
        long stopPosition = aeronArchive.getStopPosition(recordingId);
        if (stopPosition <= 0) {
            log.info("Recording {} exists but empty; dedup upperBound=-1", recordingId);
            lastRecordedUpperBound = -1L;
            matchSeqIndex.clear();
            return;
        }

        log.info("Replaying recordingId={} stopPosition={} for dedup", recordingId, stopPosition);

        TreeMap<Long, Long> index = new TreeMap<>();

        long sessionId = aeronArchive.startReplay(recordingId, 0, stopPosition,
                mdcConfig.getReplayChannel(), mdcConfig.getReplayStreamId());

        try (Subscription replaySub = mdcAeron.addSubscription(
                mdcConfig.getReplayChannel(), mdcConfig.getReplayStreamId())) {

            Image replayImage = null;
            long deadline = System.currentTimeMillis() + 10_000;
            while (replayImage == null && System.currentTimeMillis() < deadline) {
                replayImage = replaySub.imageBySessionId((int) sessionId);
                idleStrategy.idle(0);
            }
            if (replayImage == null) {
                log.error("Timed out waiting for replay image");
                lastRecordedUpperBound = -1L;
                matchSeqIndex.clear();
                return;
            }

            final Image finalImage = replayImage;
            final long[] maxSeqHolder = new long[]{-1L};

            FragmentHandler handler = (buffer, offset, length, unusedHeader) -> {
                headerDecoder.wrap(buffer, offset);
                if (headerDecoder.templateId() != MatchResultDecoder.TEMPLATE_ID) {
                    return;
                }
                int bodyOffset = offset + headerDecoder.encodedLength();
                matchResultDecoder.wrap(buffer, bodyOffset,
                        headerDecoder.blockLength(), headerDecoder.version());
                long seq = matchResultDecoder.matchSeq();
                long pos = finalImage.position();
                index.put(seq, pos);
                if (seq > maxSeqHolder[0]) {
                    maxSeqHolder[0] = seq;
                }
            };

            while (!finalImage.isEndOfStream()) {
                int fragments = finalImage.poll(handler, 20);
                idleStrategy.idle(fragments);
            }

            lastRecordedUpperBound = maxSeqHolder[0];
        }

        matchSeqIndex.clear();
        matchSeqIndex.putAll(index);
        log.info("Cold-start dedup: upperBound={} indexedEntries={}",
                lastRecordedUpperBound, matchSeqIndex.size());
    }

    @Override
    public long startMatchResultPipeline(boolean restoredFromSnapshot, long consensusNextMatchSeq) {
        ensureConnected();
        openPublication();
        if (restoredFromSnapshot) {
            check(consensusNextMatchSeq);
        } else {
            replayForDedup();
        }
        setupRecording();
        return restoredFromSnapshot
                ? consensusNextMatchSeq
                : Math.max(consensusNextMatchSeq, lastRecordedUpperBound + 1);
    }

    @Override
    public void emitEncodedMatchResult(long matchSeq, MutableDirectBuffer buffer, int offset, int length) {
        if (!shouldPublish(matchSeq)) {
            return;
        }
        if (matchResultPublication == null) {
            log.warn("Publication not open; skip matchSeq={}", matchSeq);
            return;
        }
        long position = offerToPublication(matchResultPublication, buffer, offset, length);
        matchSeqIndex.put(matchSeq, position);
    }

    @Override
    public void shutdown() {
        if (matchResultPublication != null) {
            matchResultPublication.close();
            matchResultPublication = null;
            log.info("MatchResult publication closed");
        }
        closeQuietly();
        log.info("MatchResult egress shut down");
    }

    @Override
    public NavigableMap<Long, Long> getMatchSeqIndex() {
        return Collections.unmodifiableNavigableMap(new TreeMap<>(matchSeqIndex));
    }

    private boolean shouldPublish(long matchSeq) {
        return matchSeq > lastRecordedUpperBound;
    }

    private void openPublication() {
        if (matchResultPublication != null) {
            return;
        }
        if (mdcAeron == null) {
            throw new IllegalStateException("mdcAeron not connected");
        }
        matchResultPublication = mdcAeron.addExclusivePublication(
                matchResultChannel(), matchResultStreamId());
        log.info("MatchResult publication opened: channel={} streamId={}",
                matchResultChannel(), matchResultStreamId());
    }

    /** spy + LOCAL：Archive 从同 driver 的 publication log buffer 零拷贝录制。 */
    private void setupRecording() {
        if (aeronArchive == null) {
            throw new IllegalStateException("AeronArchive not connected");
        }
        if (recordingId >= 0) {
            aeronArchive.extendRecording(recordingId, spyChannel(),
                    matchResultStreamId(), SourceLocation.LOCAL);
            log.info("Extended spy recording: recordingId={}", recordingId);
        } else {
            aeronArchive.startRecording(spyChannel(),
                    matchResultStreamId(), SourceLocation.LOCAL);
            recordingId = waitForRecordingId();
            log.info("Started spy recording: recordingId={}", recordingId);
        }
    }

    private long findExistingRecording(AeronArchive archive) {
        MutableLong found = new MutableLong(-1L);
        archive.listRecordingsForUri(0, 1, matchResultChannel(), matchResultStreamId(),
                (controlSessionId, correlationId, recId, startTimestamp, stopTimestamp,
                 startPosition, stopPosition, initialTermId, segmentFileLength,
                 termBufferLength, mtuLength, sessionId, streamId,
                 strippedChannel, originalChannel, sourceIdentity) -> found.set(recId));
        return found.get();
    }

    private long waitForRecordingId() {
        MutableLong found = new MutableLong(-1L);
        long deadline = System.currentTimeMillis() + 10_000;
        while (found.get() < 0 && System.currentTimeMillis() < deadline) {
            aeronArchive.listRecordingsForUri(0, 1, matchResultChannel(), matchResultStreamId(),
                    (controlSessionId, correlationId, recId, startTimestamp, stopTimestamp,
                     startPosition, stopPosition, initialTermId, segmentFileLength,
                     termBufferLength, mtuLength, sessionId, streamId,
                     strippedChannel, originalChannel, sourceIdentity) -> found.set(recId));
            if (found.get() < 0) {
                idleStrategy.idle(0);
            }
        }
        if (found.get() < 0) {
            throw new IllegalStateException("Timed out waiting for spy recording to start");
        }
        return found.get();
    }

    private long offerToPublication(ExclusivePublication publication, MutableDirectBuffer buffer,
                                    int offset, int length) {
        long result;
        do {
            result = publication.offer(buffer, offset, length);
            if (result < 0) {
                idleStrategy.idle(0);
            }
        } while (result < 0);
        idleStrategy.reset();
        return result;
    }
}
