package com.tk.match.queue;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.TimeUnit;

/**
 * 延迟删除文件服务：StoreFileListener 释放文件时入队，释放时间 + 30 分钟后执行删除。
 * 应用启动前会清理整个文件队列目录，故无需持久化待删列表；进程内内存队列即可。
 */
@Component
public class DelayedFileDeletionService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DelayedFileDeletionService.class);
    private static final int DELAY_MINUTES = 30;

    private final DelayQueue<DelayedPath> queue = new DelayQueue<>();
    private final Thread consumerThread;
    private volatile boolean closed;

    public DelayedFileDeletionService() {
        this.consumerThread = new Thread(this::run, "delayed-file-deletion");
        this.consumerThread.setDaemon(false);
        this.consumerThread.start();
    }

    /**
     * 由 StoreFileListener.onReleased(cycle, file) 调用：将文件加入延迟队列，30 分钟后删除。
     */
    public void scheduleDeletion(int cycle, File file) {
        if (file == null || closed) return;
        Path path = file.toPath();
        long deleteAtMs = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(DELAY_MINUTES);
        queue.offer(new DelayedPath(path, deleteAtMs));
    }

    private void run() {
        while (!closed && !Thread.currentThread().isInterrupted()) {
            try {
                DelayedPath item = queue.take();
                try {
                    Files.deleteIfExists(item.path);
                    log.debug("DelayedFileDeletionService deleted path={}", item.path);
                } catch (Exception e) {
                    log.warn("DelayedFileDeletionService delete failed path={}", item.path, e);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    @Override
    @PreDestroy
    public void close() {
        if (closed) return;
        closed = true;
        consumerThread.interrupt();
        try {
            consumerThread.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class DelayedPath implements java.util.concurrent.Delayed {
        private final Path path;
        private final long deleteAtMs;

        DelayedPath(Path path, long deleteAtMs) {
            this.path = path;
            this.deleteAtMs = deleteAtMs;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            long remaining = deleteAtMs - System.currentTimeMillis();
            return unit.convert(remaining, TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(java.util.concurrent.Delayed o) {
            if (!(o instanceof DelayedPath other)) return 1;
            return Long.compare(this.deleteAtMs, other.deleteAtMs);
        }
    }
}
