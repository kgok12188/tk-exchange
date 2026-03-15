package com.tk.match.queue;

import com.tk.match.config.MatchEngineConfig;
import com.tk.match.service.MatchManager;
import lombok.Setter;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueue;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import net.openhft.chronicle.wire.DocumentContext;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 从 Kafka match_result_* 拉取数据，使用 assign 动态订阅；消费到的每条消息回调 MatchManager.updateMasterOffset，用于从节点同步主的进度。
 * 可选：将消费到的数据写入文件队列（match.file-queue-dir 非空时），写入目录为 baseDir + "master"，每币一个 Chronicle Queue，格式为 orderReqOffset + payload；
 * 从节点自身产出的 MatchResponse 写入 baseDir + "slave"（由 MatchResultSlaveFileQueue 在 MatchSlot 中写入）。仅从节点需要启动消费；主节点不启动。
 */
public class MatchResultMasterFileQueue implements AutoCloseable {


    private final ConcurrentHashMap<String, LastWrite> lastWriteBySymbol = new ConcurrentHashMap<>();

    private static final String HOSTNAME = hostname();

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }

    private static final Logger log = LoggerFactory.getLogger(MatchResultMasterFileQueue.class);
    private static final String TOPIC_PREFIX = "match_result_";
    private final String bootstrapServers;
    @Autowired
    @Setter
    private MatchManager matchManager;
    @Autowired
    @Setter
    private MatchEngineConfig matchEngineConfig;

    /**
     * 专用锁：用于 assign 为空时的 wait，以及 addSymbol 的 notifyAll，避免使用 this 导致 IllegalMonitorStateException
     */
    private final Object assignLock = new Object();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Set<String> symbols = Collections.synchronizedSet(new HashSet<>());
    private final ConcurrentLinkedQueue<SymbolEvent> pendingEvents = new ConcurrentLinkedQueue<>();
    private KafkaConsumer<String, String> consumer;
    private Thread consumerThread;
    /**
     * baseDir + "master" 路径，非 null 时将消费到的 match_result_* 写入该目录下每 symbol 一 Chronicle Queue（orderReqOffset + payload）。
     * 延迟解析：构造时 matchEngineConfig 尚未注入，在首次需要时从 matchEngineConfig.getFileQueueDir() 解析。
     */
    private volatile Path masterConsumedBaseDir;
    private final ConcurrentHashMap<String, SingleChronicleQueue> consumedQueuesBySymbol = new ConcurrentHashMap<>();
    private volatile boolean consumedQueuesClosed;

    public MatchResultMasterFileQueue(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    private Path getMasterConsumedBaseDir() {
        Path path = masterConsumedBaseDir;
        if (path == null && matchEngineConfig != null) {
            String baseDir = matchEngineConfig.getFileQueueDir();
            path = (baseDir != null && !baseDir.isBlank()) ? Path.of(baseDir, "master") : null;
            masterConsumedBaseDir = path;
        }
        return path;
    }

    /**
     * 动态添加币对：订阅 match_result_&lt;symbol&gt;。线程安全；重复调用幂等。
     */
    public void addSymbol(String symbol) {
        if (symbol == null || symbol.isEmpty()) return;
        if (symbols.contains(symbol)) return;
        pendingEvents.add(new SymbolEvent(EventType.ADD, symbol));
        synchronized (assignLock) {
            assignLock.notifyAll();
        }
        log.info("addSymbol: {}", symbol);
    }

    /**
     * 动态移除币对：不再订阅 match_result_&lt;symbol&gt;。线程安全。
     */
    public void removeSymbol(String symbol) {
        if (symbol == null || symbol.isEmpty()) return;
        pendingEvents.add(new SymbolEvent(EventType.REMOVE, symbol));
        synchronized (assignLock) {
            assignLock.notifyAll();
        }
    }

    public void start() {
        ensureStarted();
    }

    /**
     * 从节点时调用：若消费未启动则启动消费线程（不订阅任何 topic，由后续 addSymbol 添加）。
     */
    public void ensureStarted() {
        if (running.get()) return;
        startConsumer(Collections.emptySet());
    }

    /**
     * 主节点时调用：停止消费线程并释放资源，不解除 MatchManager 引用，以便再次变从节点时可 ensureStarted。
     */
    public void stopConsumer() {
        close();
    }

    public void stop() {
        close();
    }

    public boolean isRunning() {
        return running.get();
    }

    private void startConsumer(Collection<String> initialSymbols) {
        if (running.compareAndSet(false, true)) {
            if (initialSymbols != null) {
                symbols.addAll(initialSymbols);
            }
            String groupId = HOSTNAME + "-" + System.currentTimeMillis();
            consumer = createConsumer(groupId);
            // 启动前先处理已入队的 addSymbol；match_result_* 仅单分区，用 partition 0
            drainPendingEventsSync();
            Set<TopicPartition> initial = new HashSet<>();
            for (String s : symbols) {
                initial.add(new TopicPartition(topic(s), 0));
            }
            if (!initial.isEmpty()) {
                consumer.assign(initial);
                consumer.seekToBeginning(initial);
                log.info("MatchResultMasterFileQueue assigned at start (seekToBeginning): {}", initial);
            }
            consumerThread = new Thread(this::runLoop, "match-result-master-consumer");
            consumerThread.setDaemon(true);
            consumerThread.start();
            log.info("MatchResultMasterFileQueue started groupId={} symbols={}", groupId, symbols.size());
        }
    }

    /**
     * 仅在 startConsumer 内、消费线程启动前调用，处理当前 pending 的 ADD/REMOVE，更新 symbols 与 consumer.assign。
     */
    private void drainPendingEventsSync() {
        SymbolEvent e;
        while ((e = pendingEvents.poll()) != null) {
            if (e.type == EventType.ADD) {
                if (e.symbol != null && !e.symbol.isEmpty()) {
                    symbols.add(e.symbol);
                }
            } else if (e.type == EventType.REMOVE) {
                if (e.symbol != null) {
                    symbols.remove(e.symbol);
                }
            }
        }
    }

    private KafkaConsumer<String, String> createConsumer(String groupId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new KafkaConsumer<>(props);
    }

    private static String topic(String symbol) {
        return TOPIC_PREFIX + symbol;
    }

    private void runLoop() {
        while (running.get()) {
            try {
                drainPendingEvents();
                if (consumer.assignment().isEmpty()) {
                    synchronized (assignLock) {
                        assignLock.wait(5000);
                    }
                    continue;
                }
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(2000));
                for (ConsumerRecord<String, String> record : records) {
                    long orderReqOffset = orderReqOffsetFromRecord(record);
                    if (orderReqOffset <= 0) {
                        continue;
                    }
                    String symbol = symbolFromRecord(record);
                    Path baseDir = getMasterConsumedBaseDir();
                    if (baseDir != null && symbol != null) {
                        String payload = record.value();
                        if (payload != null) {
                            writeToConsumedFileQueue(symbol, orderReqOffset, payload);
                        }
                    }
                }
            } catch (org.apache.kafka.common.errors.WakeupException e) {
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (running.get()) {
                    log.warn("MatchResultMasterFileQueue consume error", e);
                }
            }
        }
    }

    private void drainPendingEvents() {
        SymbolEvent e;
        while ((e = pendingEvents.poll()) != null) {
            if (e.type == EventType.ADD) {
                if (e.symbol != null && !e.symbol.isEmpty()) {
                    symbols.add(e.symbol);
                    Set<TopicPartition> set = new HashSet<>(consumer.assignment());
                    TopicPartition tp = new TopicPartition(topic(e.symbol), 0);
                    set.add(tp);
                    consumer.assign(set);
                    consumer.seekToEnd(Collections.singletonList(tp));
                    log.info("MatchResultMasterFileQueue added symbol={} assigned={}", e.symbol, set.size());
                }
            } else if (e.type == EventType.REMOVE) {
                if (e.symbol != null) {
                    symbols.remove(e.symbol);
                    Set<TopicPartition> set = new HashSet<>(consumer.assignment());
                    set.remove(new TopicPartition(topic(e.symbol), 0));
                    consumer.assign(set);
                    log.info("MatchResultMasterFileQueue removed symbol={} assigned={}", e.symbol, set.size());
                }
            }
        }
    }

    private static String symbolFromRecord(ConsumerRecord<String, String> record) {
        Header h = record.headers().lastHeader("symbol");
        if (h != null && h.value() != null) {
            return new String(h.value(), StandardCharsets.UTF_8);
        }
        String t = record.topic();
        if (t != null && t.startsWith(TOPIC_PREFIX)) {
            return t.substring(TOPIC_PREFIX.length());
        }
        return null;
    }

    /**
     * 从 record 解析 orderReqOffset。为支持一致性检查与单机/同宿主机主从，本机产生的消息也解析并写入 master 文件队列，不再因 host 为本机而跳过。
     */
    private static long orderReqOffsetFromRecord(ConsumerRecord<String, String> record) {
        Header orderReqOffsetHeader = record.headers().lastHeader("orderReqOffset");
        if (orderReqOffsetHeader != null && orderReqOffsetHeader.value() != null) {
            try {
                return Long.parseLong(new String(orderReqOffsetHeader.value(), StandardCharsets.UTF_8));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    @Override
    public void close() {
        if (running.compareAndSet(true, false)) {
            if (consumer != null) {
                consumer.wakeup();
            }
            if (consumerThread != null) {
                try {
                    consumerThread.join(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                consumerThread = null;
            }
            if (consumer != null) {
                try {
                    consumer.close();
                } catch (Exception e) {
                    log.warn("MatchResultMasterFileQueue consumer close error", e);
                }
                consumer = null;
            }
            closeConsumedFileQueues();
            log.info("MatchResultMasterFileQueue stopped");
        }
    }

    private void writeToConsumedFileQueue(String symbol, long orderReqOffset, String payload) {
        if (getMasterConsumedBaseDir() == null || symbol == null || payload == null || consumedQueuesClosed) return;

        LastWrite lastWrite = lastWriteBySymbol.computeIfAbsent(symbol, k -> new LastWrite(0, -1));
        if (orderReqOffset <= lastWrite.getOrderReqOffset()) return;

        try {
            SingleChronicleQueue queue = consumedQueuesBySymbol.computeIfAbsent(symbol, this::createConsumedQueue);
            ExcerptAppender appender = queue.acquireAppender();
            try (DocumentContext doc = appender.writingDocument()) {
                Objects.requireNonNull(doc.wire()).write().int64(orderReqOffset).write().text(payload);
            }
            lastWriteBySymbol.put(symbol, new LastWrite(orderReqOffset, appender.lastIndexAppended()));
        } catch (Exception e) {
            log.error("MatchResultMasterFileQueue write to consumed file queue failed symbol={} orderReqOffset={}", symbol, orderReqOffset, e);
        }
    }

    private SingleChronicleQueue createConsumedQueue(String symbol) {
        try {
            Path base = getMasterConsumedBaseDir();
            if (base == null) throw new IllegalStateException("masterConsumedBaseDir not set");
            Path dir = base.resolve(symbol);
            Files.createDirectories(dir);
            return SingleChronicleQueueBuilder.binary(dir).epoch(System.currentTimeMillis()).build();
        } catch (IOException e) {
            throw new RuntimeException("Failed to create consumed file queue for symbol " + symbol, e);
        }
    }

    private void closeConsumedFileQueues() {
        if (consumedQueuesClosed) return;
        consumedQueuesClosed = true;
        consumedQueuesBySymbol.values().forEach(q -> {
            try {
                if (!q.isClosed()) q.close();
            } catch (Exception e) {
                log.warn("MatchResultMasterFileQueue close consumed queue error", e);
            }
        });
        consumedQueuesBySymbol.clear();
    }

    public LastWrite getLastWrite(String symbol) {
        return lastWriteBySymbol.get(symbol);
    }

    private enum EventType {ADD, REMOVE}

    private static final class SymbolEvent {
        final EventType type;
        final String symbol;

        SymbolEvent(EventType type, String symbol) {
            this.type = type;
            this.symbol = symbol;
        }
    }
}
