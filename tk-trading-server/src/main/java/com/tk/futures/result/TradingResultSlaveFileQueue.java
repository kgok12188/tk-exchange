package com.tk.futures.result;

import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.RollCycles;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueue;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import net.openhft.chronicle.wire.DocumentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 从节点文件队列（Chronicle Queue 版）：当实例为 slave 时，把本应写入 Kafka 的 trading_result_(shard) 数据追加到本地 Chronicle Queue。
 * <p>
 * 主节点切换为 master 后，可以从文件中按顺序 replay 到 Kafka，并在 replay 完成后清空文件。
 */
@Service
public class TradingResultSlaveFileQueue {

    private static final Logger logger = LoggerFactory.getLogger(TradingResultSlaveFileQueue.class);

    private final Path baseDir;
    private final String shard;

    private final ConcurrentHashMap<Integer, SingleChronicleQueue> queuesBySlot = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Object> lockBySlot = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public TradingResultSlaveFileQueue(
            @Value("${trading.slave-file-queue-dir:/tmp/trading-server-slave-result-queue/}") String dir,
            @Value("${shard.id}") String shard) {
        this.baseDir = Path.of(dir);
        this.shard = shard;
    }

    private Path dirForSlot(int slotIndex) {
        return baseDir.resolve("slave").resolve(shard).resolve("slot-" + slotIndex);
    }

    private Object lockForSlot(int slotIndex) {
        return lockBySlot.computeIfAbsent(slotIndex, ignore -> new Object());
    }

    /**
     * 追加一条记录到从节点文件队列。
     *
     * @param slotIndex   slotIndex（用于确定 partition）
     * @param offset      trading_(shard) 的 Kafka offset（用于对账/幂等与 replay）
     * @param uid         本批事件所属用户（key）
     * @param payloadJson JSON 字符串（应与 ResultPublisher 写入 Kafka 的 value 完全一致）
     */
    public void append(int slotIndex, long offset, long uid, String payloadJson) {
        Objects.requireNonNull(payloadJson, "payloadJson");
        if (closed) return;

        while (true) {
            try {
                Object lock = lockForSlot(slotIndex);
                synchronized (lock) {
                    SingleChronicleQueue queue = queuesBySlot.computeIfAbsent(slotIndex, this::createSlotQueue);
                    ExcerptAppender appender = queue.acquireAppender();
                    writeRecord(appender, offset, uid, payloadJson);
                }
                return;
            } catch (Exception e) {
                logger.warn("SlaveChronicleQueue append retry failed, slotIndex={}, offset={}, uid={}, msg={}",
                        slotIndex, offset, uid, e.getMessage());
                // “直到写入成功”为目标：无限重试，可按需加退避
            }
        }
    }

    /**
     * replay 并清空：从文件按顺序读出所有记录，交给 handler 逐条发布到 Kafka。
     */
    public void replayAndClear(int slotIndex, java.util.function.Consumer<SlaveRecord> handler) {
        replayAndClear(slotIndex, Long.MIN_VALUE, handler);
    }

    /**
     * replay 并清空：只处理 {@code record.offset > minOffsetExclusive} 的记录，避免重复输出。
     */
    public void replayAndClear(int slotIndex, long minOffsetExclusive, java.util.function.Consumer<SlaveRecord> handler) {
        Objects.requireNonNull(handler, "handler");
        if (closed) return;

        Path dir = dirForSlot(slotIndex);
        if (!Files.exists(dir)) return;

        Object lock = lockForSlot(slotIndex);
        synchronized (lock) {
            // 为避免 replay 过程中仍被写入：replay 在 slot 线程内执行，正常情况下不会并发 append 同一 slot。
            try {
                SingleChronicleQueue queue = queuesBySlot.computeIfAbsent(slotIndex, this::createSlotQueue);
                ExcerptTailer tail = queue.createTailer();
                while (true) {
                    try (DocumentContext dc = tail.readingDocument()) {
                        if (!dc.isPresent()) break;
                        long recordOffset = Objects.requireNonNull(dc.wire()).read().int64();
                        long uid = Objects.requireNonNull(dc.wire()).read().int64();
                        String payload = Objects.requireNonNull(dc.wire()).read().readString();
                        if (recordOffset > minOffsetExclusive) {
                            handler.accept(new SlaveRecord(recordOffset, uid, payload));
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("SlaveChronicleQueue replay failed, dir={}", dir, e);
                return; // replay 失败：不清空队列，避免丢数据
            }

            clearSlot(slotIndex);
        }
    }

    private SingleChronicleQueue createSlotQueue(int slotIndex) {
        try {
            Path dir = dirForSlot(slotIndex);
            Files.createDirectories(dir);
            return SingleChronicleQueueBuilder.binary(dir)
                    .epoch(System.currentTimeMillis())
                    .rollCycle(RollCycles.TEN_MINUTELY)
                    .build();
        } catch (IOException e) {
            throw new RuntimeException("Failed to create chronicle queue for slotIndex=" + slotIndex, e);
        }
    }

    private static void writeRecord(ExcerptAppender appender, long offset, long uid, String payloadJson) {
        try (DocumentContext dc = appender.writingDocument()) {
            Objects.requireNonNull(dc.wire())
                    .write()
                    .int64(offset)
                    .write()
                    .int64(uid)
                    .write()
                    .text(payloadJson);
        }
    }

    private void clearSlot(int slotIndex) {
        SingleChronicleQueue queue = queuesBySlot.remove(slotIndex);
        if (queue != null) {
            try {
                if (!queue.isClosed()) {
                    queue.close();
                }
            } catch (Exception e) {
                logger.warn("SlaveChronicleQueue close queue failed, slotIndex={}", slotIndex, e);
            }
        }

        Path dir = dirForSlot(slotIndex);
        if (!Files.exists(dir)) return;

        try {
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
        } catch (IOException e) {
            logger.warn("SlaveChronicleQueue delete queue dir failed, dir={}", dir, e);
        }
    }

    public static final class SlaveRecord {
        public final long offset;
        public final long uid;
        public final String payload; // same as Kafka value

        public SlaveRecord(long offset, long uid, String payload) {
            this.offset = offset;
            this.uid = uid;
            this.payload = payload;
        }
    }
}

