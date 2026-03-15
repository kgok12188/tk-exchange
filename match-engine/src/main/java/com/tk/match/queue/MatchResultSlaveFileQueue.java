package com.tk.match.queue;

import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;
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

    public MatchResultSlaveFileQueue(Path baseDir) {
        this.baseDir = baseDir;
    }

    /**
     * 追加一条 MatchResponse 记录；格式：int64(orderReqOffset) + text(payload)。
     */
    public void write(String symbol, long orderReqOffset, String payload) {
        if (symbol == null || payload == null || closed) return;
        try {
            SingleChronicleQueue queue = queuesBySymbol.computeIfAbsent(symbol, this::createQueue);
            ExcerptAppender appender = queue.acquireAppender();
            writeRecord(appender, orderReqOffset, payload);
            long lastIndex = appender.lastIndexAppended();
            lastWriteBySymbol.put(symbol, new LastWrite(orderReqOffset, lastIndex));
        } catch (Exception e) {
            log.error("MatchResultFileQueue write failed symbol={} orderReqOffset={}", symbol, orderReqOffset, e);
        }
    }

    /**
     * 写入完成后记录的该 symbol 最新 orderReqOffset 与 Chronicle lastIndexAppended；无写入过返回 null。
     */
    public LastWrite getLastWrite(String symbol) {
        return symbol == null ? null : lastWriteBySymbol.get(symbol);
    }

    private SingleChronicleQueue createQueue(String symbol) {
        try {
            Path dir = baseDir.resolve("slave").resolve(symbol);
            Files.createDirectories(dir);
            return SingleChronicleQueueBuilder.binary(dir).epoch(System.currentTimeMillis()).build();
        } catch (IOException e) {
            throw new RuntimeException("Failed to create file queue for symbol " + symbol, e);
        }
    }

    private static void writeRecord(ExcerptAppender appender, long orderReqOffset, String payload) {
        try (DocumentContext doc = appender.writingDocument()) {
            Objects.requireNonNull(doc.wire()).write().int64(orderReqOffset).write().text(payload);
        }
    }

    /**
     * 补发：从该 symbol 的文件队列顺序读取，将 orderReqOffset &gt; minOrderReqOffsetExclusive 的记录交给 consumer（payload, orderReqOffset）。
     * 用于从晋升为主后，将未写入 Kafka 的 MatchResponse 按序发往 Kafka。
     */
    public void replay(String symbol, long minOrderReqOffsetExclusive, BiConsumer<String, Long> consumer) {
        if (symbol == null || consumer == null || closed) return;
        SingleChronicleQueue q = queuesBySymbol.get(symbol);
        if (q == null) return;
        try {
            ExcerptTailer tail = q.createTailer();
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
        } catch (Exception e) {
            log.error("MatchResultFileQueue replay failed symbol={}", symbol, e);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        queuesBySymbol.values().forEach(q -> {
            try {
                if (!q.isClosed()) q.close();
            } catch (Exception e) {
                log.warn("Error closing Chronicle queue", e);
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
        SingleChronicleQueue q = queuesBySymbol.remove(symbol);
        lastWriteBySymbol.remove(symbol);
        if (q == null) return;
        try {
            if (!q.isClosed()) q.close();
        } catch (Exception e) {
            log.warn("MatchResultSlaveFileQueue close queue symbol={}", symbol, e);
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
                    public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                        if (exc != null) throw exc;
                        Files.delete(d);
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        } catch (IOException e) {
            log.warn("MatchResultSlaveFileQueue delete queue dir symbol={} path={}", symbol, dir, e);
        }
    }

}
