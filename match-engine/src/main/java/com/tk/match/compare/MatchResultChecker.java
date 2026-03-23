package com.tk.match.compare;

import com.tk.match.config.MatchEngineConfig;
import com.tk.match.service.MatchManager;
import jakarta.annotation.PreDestroy;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueue;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import net.openhft.chronicle.wire.DocumentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 定时任务：主从文件队列状态机一致性抽样比对。
 * <p>
 * 验证从节点自身产出的 MatchResponse（baseDir/slave）与从 Kafka 消费到的主节点产出（baseDir/master）
 * 在相同 orderReqOffset 下内容一致。以 orderReqOffset 对齐，取最后一条得 lastSlave、lastMaster，
 * end = min(lastSlave, lastMaster)，在 (end - N, end] 内倒推 N 条逐条比较 payload；不一致或缺失打 error 日志。
 * 抽样全部一致时通过 {@link MatchManager#updateComparedProgressFromConsistencyCheck} 更新 OrderBook 对齐进度（不写 Chronicle 文件）。
 * <p>
 * 仅当 match.consistencyCheckEnabled=true、match.fileQueueDir 非空且 {@link MatchManager#anyMaster()} 为 false（即没有任何 slot 处于主）时执行。
 */
@Component
public class MatchResultChecker {

    private static final Logger log = LoggerFactory.getLogger(MatchResultChecker.class);

    private final MatchManager matchManager;
    private final MatchEngineConfig matchEngineConfig;
    private final ConcurrentHashMap<Path, SingleChronicleQueue> queueCache = new ConcurrentHashMap<>();

    public MatchResultChecker(MatchManager matchManager, MatchEngineConfig matchEngineConfig) {
        this.matchManager = matchManager;
        this.matchEngineConfig = matchEngineConfig;
    }

    @Scheduled(fixedDelayString = "${match.consistency-check-interval-ms:5000}", initialDelay = 10000)
    public void run() {
        if (matchEngineConfig.isConsistencyCheckEnabled()) {
            if (matchManager.anyMaster()) return;
            String base = matchEngineConfig.getFileQueueDir();
            if (base == null || base.isBlank()) return;
            Path baseDir = Path.of(base);
            int n = matchEngineConfig.getConsistencyCheckSampleSize();
            if (n <= 0) n = 20;
            int keepSize = Math.min(n * 2, 100);

            List<String> symbols = collectSymbols();
            if (symbols.isEmpty()) return;

            for (String symbol : symbols) {
                try {
                    compareQueuesForSymbol(baseDir, symbol, n, keepSize);
                } catch (Exception exception) {
                    log.warn("check symbol={} error", symbol, exception);
                }
            }
        }
    }

    @PreDestroy
    public void closeAllQueues() {
        queueCache.forEach((path, queue) -> {
            try {
                if (!queue.isClosed()) queue.close();
            } catch (Exception exception) {
                log.warn("checker close queue {} failed", path, exception);
            }
        });
        queueCache.clear();
    }

    private List<String> collectSymbols() {
        List<String> out = new ArrayList<>();
        int slots = matchManager.getSlotCount();
        for (int i = 0; i < slots; i++) {
            out.addAll(matchManager.getSymbolsBySlotIndex(i));
        }
        return out;
    }

    private void compareQueuesForSymbol(Path baseDir, String symbol, int sampleSize, int keepSize) {
        long startTime = System.nanoTime();
        LastWrite slaveLw = matchManager.getSlaveLastWrite(symbol);
        LastWrite masterLw = matchManager.getMasterLastWrite(symbol);
        if (slaveLw == null || masterLw == null) {
            log.warn("checker symbol={} skip (slave or master lw missing) slaveLw={},masterLw={}", symbol, slaveLw, masterLw);
            return;
        }

        long slaveOrd = slaveLw.getOrderReqOffset();
        long masterOrd = masterLw.getOrderReqOffset();
        long end = Math.min(slaveOrd, masterOrd);
        long slaveIdx = slaveLw.getLastIndexAppended();
        long masterIdx = masterLw.getLastIndexAppended();
        long effectiveSlaveIdx = slaveOrd > end ? Math.max(0, slaveIdx - (slaveOrd - end)) : slaveIdx;
        long effectiveMasterIdx = masterOrd > end ? Math.max(0, masterIdx - (masterOrd - end)) : masterIdx;

        Path slaveDir = baseDir.resolve("slave").resolve(symbol);
        Path masterDir = baseDir.resolve("master").resolve(symbol);

        if (!slaveDir.toFile().exists() || !masterDir.toFile().exists()) {
            if (log.isTraceEnabled()) {
                log.trace("checker symbol={} skip (slave or master dir missing)", symbol);
            }
            return;
        }

        List<Record> slaveRecords = readLastRecords(slaveDir, keepSize, effectiveSlaveIdx);
        List<Record> masterRecords = readLastRecords(masterDir, keepSize, effectiveMasterIdx);

        if (slaveRecords.isEmpty() && masterRecords.isEmpty()) return;

        long startExclusive = end - sampleSize;
        Map<Long, String> slaveMap = toMapInRange(slaveRecords, startExclusive, end);
        Map<Long, String> masterMap = toMapInRange(masterRecords, startExclusive, end);

        Set<Long> allOffsets = new HashSet<>();
        allOffsets.addAll(slaveMap.keySet());
        allOffsets.addAll(masterMap.keySet());
        if (allOffsets.isEmpty()) {
            return;
        }
        int count = 0;
        boolean allMatch = true;
        for (Long orderReqOffset : allOffsets) {
            String slavePayload = slaveMap.get(orderReqOffset);
            String masterPayload = masterMap.get(orderReqOffset);
            if (slavePayload == null) {
                continue;
            }
            if (masterPayload == null) {
                continue;
            }
            if (!slavePayload.equals(masterPayload)) {
                String diff = diffSummary(slavePayload, masterPayload);
                log.error("checker symbol={} orderReqOffset={} payload mismatch: {}", symbol, orderReqOffset, diff);
                allMatch = false;
            } else {
                count++;
            }
        }
        log.info("checker symbol={} checked {} records allMatch={},end={},effectiveSlaveIdx={},cost={}", symbol, count, allMatch, end, effectiveSlaveIdx, (System.nanoTime() - startTime) / 1000);
        if (allMatch && count > 0) {
            matchManager.updateComparedProgressFromConsistencyCheck(symbol, end, effectiveSlaveIdx);
        } else {
            log.error("checker symbol={} mismatch,end={},effectiveSlaveIdx={}", symbol, end, effectiveSlaveIdx);
        }
    }

    /**
     * 按目录缓存只读 queue。当 effectiveLastIndexAppended >= 0 时，用 LastWrite 对齐后的有效末尾：从
     * max(0, effectiveLastIndexAppended - keepSize + 1) 读到 effectiveLastIndexAppended（含），不调 lastIndex/toStart。
     * 当 effectiveLastIndexAppended < 0 时，用 lastIndex + toStart + firstIndex 探路后读最后 keepSize 条。
     */
    private List<Record> readLastRecords(Path queueDir, int keepSize, long effectiveLastIndexAppended) {
        if (queueDir == null) return List.of();
        Path key = queueDir.normalize().toAbsolutePath();
        SingleChronicleQueue queue = queueCache.computeIfAbsent(key, dir -> {
            try {
                return SingleChronicleQueueBuilder.binary(dir).readOnly(true).build();
            } catch (Exception exception) {
                log.debug("checker open queue dir={} error: {}", dir, exception.getMessage());
                return null;
            }
        });
        if (queue == null || queue.isClosed()) return List.of();

        List<Record> list = new ArrayList<>();
        try {
            ExcerptTailer tail = queue.createTailer();
            long lastIdx;
            long firstIdx;
            if (effectiveLastIndexAppended >= 0) {
                lastIdx = effectiveLastIndexAppended;
                firstIdx = 0;
            } else {
                lastIdx = queue.lastIndex();
                if (lastIdx < 0) return list;
                tail.toStart();
                firstIdx = -1;
                try (DocumentContext dc = tail.readingDocument()) {
                    if (dc.isPresent()) firstIdx = tail.index();
                }
                if (firstIdx < 0) return list;
            }

            long startFrom = Math.max(firstIdx, lastIdx - keepSize + 1);
            if (!tail.moveToIndex(startFrom)) {
                if (log.isTraceEnabled()) {
                    log.trace("checker moveToIndex({}) failed firstIdx={} lastIdx={} dir={}", startFrom, firstIdx, lastIdx, queueDir);
                }
                return list;
            }
            while (true) {
                try (DocumentContext dc = tail.readingDocument()) {
                    if (!dc.isPresent()) break;
                    long orderReqOffset = Objects.requireNonNull(dc.wire()).read().int64();
                    String payload = Objects.requireNonNull(dc.wire()).read().readString();
                    long idx = tail.index();
                    if (effectiveLastIndexAppended >= 0 && idx > effectiveLastIndexAppended) break;
                    list.add(new Record(orderReqOffset, payload != null ? payload : ""));
                }
            }
        } catch (Exception exception) {
            log.warn("checker read queue dir={} error: {}", queueDir, exception.getMessage());
        }
        return list;
    }

    private static Map<Long, String> toMapInRange(List<Record> records, long startExclusive, long endInclusive) {
        Map<Long, String> map = new HashMap<>();
        for (Record record : records) {
            if (record.orderReqOffset > startExclusive && record.orderReqOffset <= endInclusive) {
                map.put(record.orderReqOffset, record.payload);
            }
        }
        return map;
    }

    private static String diffSummary(String a, String b) {
        int la = a.length();
        int lb = b.length();
        if (la != lb) {
            return "length slave=" + la + " master=" + lb;
        }
        int maxPreview = 120;
        String sa = la <= maxPreview ? a : a.substring(0, maxPreview) + "...";
        String sb = lb <= maxPreview ? b : b.substring(0, maxPreview) + "...";
        return "slave=[" + sa + "] master=[" + sb + "]";
    }

    private static final class Record {
        final long orderReqOffset;
        final String payload;

        Record(long orderReqOffset, String payload) {
            this.orderReqOffset = orderReqOffset;
            this.payload = payload;
        }
    }
}
