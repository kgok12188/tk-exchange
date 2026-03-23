package com.tk.match.compare;

import lombok.NonNull;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.RollCycles;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueue;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import net.openhft.chronicle.wire.DocumentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * 从节点文件队列：每币一个 Chronicle Queue，写入格式与 ChronicleQueueTest#replayFromOffsetSimulation 一致。
 * 记录 = orderReqOffset(int64) + payload(text)。
 */
public class MatchResultSlaveFileQueue implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MatchResultSlaveFileQueue.class);

    private final Path baseDir;
    private final ConcurrentHashMap<String, SingleChronicleQueue> queuesBySymbol = new ConcurrentHashMap<>();
    /**
     * 每个 symbol 写入完成后记录的最新 orderReqOffset 与 ExcerptAppender.lastIndexAppended()
     */
    private final ConcurrentHashMap<String, LastWrite> lastWriteBySymbol = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private final DelayedFileDeletionService delayedFileDeletionService;


    public MatchResultSlaveFileQueue(Path baseDir, @NonNull DelayedFileDeletionService delayedFileDeletionService) {
        this.baseDir = baseDir;
        this.delayedFileDeletionService = delayedFileDeletionService;
    }

    /**
     * 追加一条 MatchResponse 记录；格式：int64(orderReqOffset) + text(payload)。
     */
    public void write(String symbol, long orderReqOffset, String payload) {
        if (symbol == null || payload == null || closed) return;
        try {
            SingleChronicleQueue queue = queuesBySymbol.computeIfAbsent(symbol, this::createSlaveQueue);
            ExcerptAppender appender = queue.acquireAppender();
            writeRecord(appender, orderReqOffset, payload);
            long lastIndex = appender.lastIndexAppended();
            lastWriteBySymbol.put(symbol, new LastWrite(orderReqOffset, lastIndex));
        } catch (Exception exception) {
            log.error("MatchResultFileQueue write failed symbol={} orderReqOffset={}", symbol, orderReqOffset, exception);
        }
    }

    /**
     * 写入完成后记录的该 symbol 最新 orderReqOffset 与 Chronicle lastIndexAppended；无写入过返回 null。
     */
    public LastWrite getLastWrite(String symbol) {
        return symbol == null ? null : lastWriteBySymbol.get(symbol);
    }

    private SingleChronicleQueue createSlaveQueue(String symbol) {
        try {
            Path dir = baseDir.resolve("slave").resolve(symbol);
            Files.createDirectories(dir);
            return SingleChronicleQueueBuilder.binary(dir).epoch(System.currentTimeMillis()).rollCycle(RollCycles.TEN_MINUTELY)
                    .storeFileListener(delayedFileDeletionService::scheduleDeletion).build();
        } catch (IOException ioException) {
            throw new RuntimeException("Failed to create file queue for symbol " + symbol, ioException);
        }
    }

    private static void writeRecord(ExcerptAppender appender, long orderReqOffset, String payload) {
        try (DocumentContext doc = appender.writingDocument()) {
            Objects.requireNonNull(doc.wire()).write().int64(orderReqOffset).write().text(payload);
        }
    }


    /**
     * 避免每次切主都从队列物理头开始扫描（起点由 ComparedEvent 解析的 {@code comparedFileQueueStartIndex} 提供）。
     */
    public void replay(String symbol, long minOrderReqOffsetExclusive, long startIndexHint, BiConsumer<String, Long> consumer) {
        if (symbol == null || consumer == null || closed) return;
        SingleChronicleQueue queue = queuesBySymbol.get(symbol);
        if (queue == null) return;
        try {
            ExcerptTailer tail = queue.createTailer();
            if (startIndexHint >= 0) {
                if (!tail.moveToIndex(startIndexHint)) {
                    log.warn("MatchResultSlaveFileQueue replay moveToIndex({}) failed symbol={} fallback toStart", startIndexHint, symbol);
                    tail.toStart();
                }
            } else {
                tail.toStart();
            }
            while (true) {
                try (DocumentContext dc = tail.readingDocument()) {
                    if (!dc.isPresent()) break;
                    long orderReqOffset = Objects.requireNonNull(dc.wire()).read().int64();
                    String payload = Objects.requireNonNull(dc.wire()).read().readString();
                    if (orderReqOffset > minOrderReqOffsetExclusive) {
                        consumer.accept(payload, orderReqOffset);
                    }
                }
            }
        } catch (Exception exception) {
            log.error("MatchResultFileQueue replay failed symbol={}", symbol, exception);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        queuesBySymbol.values().forEach(queue -> {
            try {
                if (!queue.isClosed()) queue.close();
            } catch (Exception exception) {
                log.warn("Error closing Chronicle queue", exception);
            }
        });
        queuesBySymbol.clear();
        lastWriteBySymbol.clear();
    }

    /**
     * 补发完成后清空该 symbol 的 slave 队列（Chronicle 5.x 未实现 clear()，改为关闭并删除目录，下次写入会新建）。
     */
    public void clear(String symbol) {
        if (symbol == null) return;
        SingleChronicleQueue queue = queuesBySymbol.remove(symbol);
        lastWriteBySymbol.remove(symbol);
        if (queue == null) return;
        try {
            if (!queue.isClosed()) queue.close();
        } catch (Exception exception) {
            log.warn("MatchResultSlaveFileQueue close queue symbol={}", symbol, exception);
        }
        Path dir = baseDir.resolve("slave").resolve(symbol);
        try {
            if (Files.exists(dir)) {
                Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path directoryPath, IOException ioException) throws IOException {
                        if (ioException != null) throw ioException;
                        Files.delete(directoryPath);
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        } catch (IOException ioException) {
            log.warn("MatchResultSlaveFileQueue delete queue dir symbol={} path={}", symbol, dir, ioException);
        }
    }

}
