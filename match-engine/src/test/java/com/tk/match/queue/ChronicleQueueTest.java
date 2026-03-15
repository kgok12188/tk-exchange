package com.tk.match.queue;

import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueue;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import net.openhft.chronicle.wire.DocumentContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chronicle Queue 测试：顺序写入、顺序读取、按“逻辑 offset”（orderReqOffset）回放，
 * 模拟从节点文件队列写入与升主后补发场景。
 */
class ChronicleQueueTest {

    private SingleChronicleQueue queue;

    @AfterEach
    void tearDown() {
        if (queue != null && !queue.isClosed()) {
            queue.close();
        }
    }

    @Test
    void writeThenReadSequential(@TempDir Path dir) {
        Path queuePath = dir.resolve("queue");
        queue = SingleChronicleQueueBuilder.binary(queuePath).build();

        ExcerptAppender appender = queue.acquireAppender();
        writeText(appender, "msg1");
        writeText(appender, "msg2");
        writeText(appender, "msg3");

        List<String> read = new ArrayList<>();
        ExcerptTailer tailer = queue.createTailer();
        while (true) {
            try (DocumentContext dc = tailer.readingDocument()) {
                if (!dc.isPresent()) break;
                read.add(dc.wire().read().text());
            }
        }

        assertEquals(3, read.size());
        assertEquals("msg1", read.get(0));
        assertEquals("msg2", read.get(1));
        assertEquals("msg3", read.get(2));
    }

    @Test
    void replayFromOffsetSimulation(@TempDir Path dir) {
        Path queuePath = dir.resolve("queue");
        queue = SingleChronicleQueueBuilder.binary(queuePath).epoch(System.currentTimeMillis()).build();

        ExcerptAppender appender = queue.acquireAppender();
        writeRecord(appender, 100L, "{\"taker\":1,\"trades\":[]}");
        writeRecord(appender, 101L, "{\"taker\":2,\"trades\":[]}");
        writeRecord(appender, 102L, "{\"taker\":3,\"trades\":[]}");
        writeRecord(appender, 103L, "{\"taker\":4,\"trades\":[]}");

        long masterOffset = 101L;
        List<String> toReplay = new ArrayList<>();
        ExcerptTailer tailer = queue.createTailer();
        for (int i = 0; i < 4; i++) {
            try (DocumentContext dc = tailer.readingDocument()) {
                System.out.println("index ===> " + dc.index());
                if (!dc.isPresent()) break;
                long orderReqOffset = Objects.requireNonNull(dc.wire()).read().int64();
                String payload = Objects.requireNonNull(dc.wire()).read().readString();
                if (orderReqOffset > masterOffset) {
                    toReplay.add(payload);
                }
            }
        }

        assertEquals(2, toReplay.size());
        assertTrue(toReplay.get(0).contains("\"taker\":3"));
        assertTrue(toReplay.get(1).contains("\"taker\":4"));
    }

    private static void writeText(ExcerptAppender appender, String text) {
        try (DocumentContext doc = appender.writingDocument()) {
            Objects.requireNonNull(doc.wire()).write().text(text);
        }
    }

    private static void writeRecord(ExcerptAppender appender, long orderReqOffset, String payload) {
        DocumentContext doc = appender.writingDocument();
        Objects.requireNonNull(doc.wire()).write().int64(orderReqOffset).write().text(payload);
        doc.close();
        System.out.println(appender.lastIndexAppended());
    }

}
