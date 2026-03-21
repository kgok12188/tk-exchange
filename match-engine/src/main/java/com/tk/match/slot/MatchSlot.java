package com.tk.match.slot;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.tk.match.compare.DelayedFileDeletionService;
import com.tk.match.compare.LastWrite;
import com.tk.match.compare.MatchResultSlaveFileQueue;
import com.tk.match.engine.BookOrder;
import com.tk.match.engine.MatchEngine;
import com.tk.match.engine.OrderBook;
import com.tk.match.engine.OrderCommandEnvelope;
import com.tk.match.service.MatchResultTailQueryService;
import com.tk.match.slot.event.*;
import com.tk.match.snapshot.SnapshotFileHelper;
import com.tk.match.snapshot.SnapshotLoadResult;
import com.tk.protocol.ProtocolSerde;
import com.tk.protocol.ProtocolVersion;
import com.tk.protocol.dto.MatchResponse;
import com.tk.protocol.dto.OrderCommand;
import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One slot in the match-engine: owns Kafka consumer for its order_req topics, Disruptor RingBuffer (BlockingWaitStrategy), and handler thread.
 * Same symbol always in same slot (hash(symbol)%N); each symbol has its own MatchEngine (order book).
 * Supports pendingSlotEvents (ADD_SYMBOL / BECAME_MASTER), snapshot request enqueue, and startup restore from snapshot.
 */
public class MatchSlot {

    private static final Logger log = LoggerFactory.getLogger(MatchSlot.class);
    private static final String ORDER_REQ_PREFIX = "order_req_";
    private static final int RING_BUFFER_SIZE = 65536;

    private final int index;
    private final KafkaProducer<String, String> producer;
    private final List<String> symbols;
    private final String bootstrapServers;
    private final Path snapshotDir;
    private final MatchResultSlaveFileQueue fileQueueWriter;
    private final MatchResultTailQueryService tailQueryService;
    private final ConcurrentMap<String, MatchEngine> enginesBySymbol;
    private final AtomicBoolean running;
    private final ConcurrentLinkedQueue<SlotEvent> pendingSlotEvents;
    private KafkaConsumer<String, String> consumer;
    private Thread consumerThread;
    private Disruptor<SlotTaskEvent> disruptor;
    private RingBuffer<SlotTaskEvent> ringBuffer;
    /**
     * One-shot latch: countDown when START event has been processed and consumerThread is started.
     */
    private volatile CountDownLatch startLatch;

    private volatile boolean isMaster = false;

    /**
     * 数据面是否为主：与 {@code process} 中写 Kafka / 写 slave 文件队列分支一致；由 HA 事件在补发完成后置位。
     */
    public boolean isMaster() {
        return isMaster;
    }

    private final Object waitSymbolLock = new Object();

    /**
     * @param symbols                    本 slot 负责的币对列表（已归一化，如 BTC_USDT）；内部由 symbol 推导 order_req_(symbol) 作为消费 topic
     * @param fileQueueDir               从节点文件队列根目录；非空时从节点将 MatchResponse 写入该目录下每币一个 Chronicle Queue，null 表示不写文件队列
     * @param tailQueryService           查询 match_result_ 尾部的 Service，切主补发前按需查询 masterOffset
     * @param delayedFileDeletionService 可选；非空时 StoreFileListener 释放文件后延迟 30 分钟删除
     */
    public MatchSlot(int index, KafkaProducer<String, String> producer, List<String> symbols, String bootstrapServers, Path snapshotDir,
                     Path fileQueueDir, MatchResultTailQueryService tailQueryService,
                     DelayedFileDeletionService delayedFileDeletionService) {
        this.index = index;
        this.producer = producer;
        this.symbols = symbols != null ? new ArrayList<>(symbols) : new ArrayList<>();
        this.bootstrapServers = bootstrapServers;
        this.snapshotDir = snapshotDir;
        this.fileQueueWriter = fileQueueDir != null ? new MatchResultSlaveFileQueue(fileQueueDir, delayedFileDeletionService) : null;
        this.tailQueryService = tailQueryService;
        this.enginesBySymbol = new ConcurrentHashMap<>();
        this.running = new AtomicBoolean(false);
        this.pendingSlotEvents = new ConcurrentLinkedQueue<>();
    }

    private static String symbolToTopic(String symbol) {
        return ORDER_REQ_PREFIX + symbol;
    }

    /**
     * 运行时上币：向 pendingSlotEvents 投递 ADD_SYMBOL(symbol, initialMasterOffset)，由 consumeLoop 创建 MatchEngine 并 assign topic。线程安全；重复调用幂等。
     *
     * @param initialMasterOffset 添加币对时查询 match_result_(symbol) 尾部得到的 orderReqOffset，0 表示不预填。
     */
    public void addSymbol(String symbol, long initialMasterOffset) {
        if (symbol == null || symbol.isEmpty()) return;

        if (enginesBySymbol.containsKey(symbol)) return;

        pendingSlotEvents.add(SlotEvent.addSymbol(symbol, initialMasterOffset));
        synchronized (waitSymbolLock) {
            waitSymbolLock.notifyAll();
        }
    }

    /**
     * 切主后由外部（如 ZK 选主回调）调用，向本 slot 投递 BECAME_MASTER，consumeLoop 将执行补发。
     */
    public void becameMaster() {
        pendingSlotEvents.add(SlotEvent.becameMaster());
    }


    /**
     * @param slaveQueueStartIndex slave Chronicle 已对齐末尾索引；无则 -1
     */
    public void updateComparedOffset(String symbol, long comparedOffset, long slaveQueueStartIndex) {
        if (symbol == null || symbol.isEmpty()) return;
        pendingSlotEvents.add(SlotEvent.comparedOffset(symbol, comparedOffset, slaveQueueStartIndex));
        synchronized (waitSymbolLock) {
            waitSymbolLock.notifyAll();
        }
    }

    public void becameSlave() {
        pendingSlotEvents.add(SlotEvent.becameSlave());
    }

    /**
     * Current symbols in this slot (for snapshot trigger).
     */
    public Set<String> getSymbols() {
        return Set.copyOf(enginesBySymbol.keySet());
    }

    /**
     * 该 symbol 在从节点文件队列上最近一次 write 完成后的 orderReqOffset 与 lastIndexAppended；无写入过或未启用文件队列时返回 null。
     */
    public LastWrite getLastWrite(String symbol) {
        return fileQueueWriter != null ? fileQueueWriter.getLastWrite(symbol) : null;
    }

    public void submitTakeSnapshot(String symbol) {
        if (symbol == null || snapshotDir == null) return;
        pendingSlotEvents.add(SlotEvent.snapshot(symbol));
        synchronized (waitSymbolLock) {
            waitSymbolLock.notifyAll();
        }
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        consumer = createConsumer();
        List<TopicPartition> partitions = new ArrayList<>(symbols.size());
        for (String symbol : symbols) {
            partitions.add(new TopicPartition(symbolToTopic(symbol), 0));
        }
        Map<String, Long> seekOffsetBySymbol = new HashMap<>();
        for (String symbol : symbols) {
            log.info("start restore symbol={} from snapshot", symbol);
            MatchEngine engine = new MatchEngine(symbol);
            if (snapshotDir != null) {
                SnapshotLoadResult loaded = SnapshotFileHelper.load(snapshotDir, symbol);
                if (loaded != null) {
                    OrderBook book = getOrderBook(engine, loaded);
                    book.setReqOffset(loaded.offset());
                    seekOffsetBySymbol.put(symbol, loaded.offset());
                    log.info("restored symbol={} from snapshot offset={} orders={}", symbol, loaded.offset(), loaded.orders().size());
                }
            }
            enginesBySymbol.put(symbol, engine);
        }
        consumer.assign(partitions);
        for (String symbol : symbols) {
            TopicPartition tp = new TopicPartition(symbolToTopic(symbol), 0);
            Long seekOffset = seekOffsetBySymbol.get(symbol);
            if (seekOffset != null) {
                consumer.seek(tp, seekOffset + 1);
                log.info("MatchSlot index={} symbol={} seek to offset {}", index, symbol, seekOffset + 1);
            } else {
                consumer.seek(tp, 0L);
                log.info("MatchSlot index={} symbol={} seek to beginning (offset 0)", index, symbol);
            }
        }
        disruptor = new Disruptor<>(
                new SlotTaskEventFactory(),
                RING_BUFFER_SIZE,
                r -> {
                    return new Thread(r, "match-slot-" + index);
                },
                ProducerType.SINGLE,
                new BlockingWaitStrategy());
        disruptor.handleEventsWith((event, sequence, endOfBatch) -> dispatchSlotTaskEvent(event));
        disruptor.start();
        ringBuffer = disruptor.getRingBuffer();
        consumerThread = new Thread(this::consumeLoop, "order-consumer-" + index);
        consumerThread.setDaemon(true);
        consumerThread.start();
    }

    private static OrderBook getOrderBook(MatchEngine engine, SnapshotLoadResult loaded) {
        OrderBook book = engine.getBook();
        for (BookOrder bo : loaded.orders()) {
            if (bo.getPrice() != null && bo.getRemainingVolume() != null) {
                book.restoreOrder(bo.getOrderId(), bo.getUid() != null ? bo.getUid() : 0L, bo.getShardId(), bo.getSide(), bo.getPrice(), bo.getRemainingVolume(), bo.getSeq());
            }
        }
        return book;
    }

    /**
     * KafkaConsumer 非线程安全：须在唯一使用它的 {@link #consumeLoop} 退出后再 {@link KafkaConsumer#close()}，
     * 否则 Spring 关闭线程与 order-consumer 线程并发访问会触发 ConcurrentModificationException。
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            long startTime = System.currentTimeMillis();
            Thread joinTarget = consumerThread;
            if (consumer != null) {
                consumer.wakeup();
            }
            synchronized (waitSymbolLock) {
                waitSymbolLock.notifyAll();
            }
            if (joinTarget != null) {
                joinTarget.interrupt();
                try {
                    joinTarget.join(30_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (joinTarget.isAlive()) {
                    log.warn("MatchSlot order-consumer did not exit within 30s, index={}", index);
                    try {
                        joinTarget.join(10_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            consumerThread = null;
            if (consumer != null) {
                try {
                    consumer.close();
                } catch (Exception e) {
                    log.warn("MatchSlot consumer close error index={}", index, e);
                }
                consumer = null;
            }
            if (disruptor != null) {
                disruptor.shutdown();
                disruptor = null;
                ringBuffer = null;
            }
            if (fileQueueWriter != null) {
                fileQueueWriter.close();
            }
            log.info("MatchSlot stopped index={},time={}", index, System.currentTimeMillis() - startTime);
        }
    }

    private void consumeLoop() {
        startLatch = new CountDownLatch(1);
        long seq = ringBuffer.next();
        try {
            ringBuffer.get(seq).setStart();
        } finally {
            ringBuffer.publish(seq);
        }
        try {
            startLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("MatchSlot start interrupted", e);
        }
        log.info("MatchSlot started index={} symbols={} engines={} disruptor=BlockingWaitStrategy", index, symbols.size(), enginesBySymbol.size());

        while (running.get()) {
            try {
                drainPendingSlotEvents();
                if (consumer.assignment().isEmpty()) {
                    synchronized (waitSymbolLock) {
                        waitSymbolLock.wait(500);
                    }
                    continue;
                }
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(10));
                for (ConsumerRecord<String, String> record : records) {
                    String topic = record.topic();
                    String symbol = topic.startsWith(ORDER_REQ_PREFIX) ? topic.substring(ORDER_REQ_PREFIX.length()) : topic;
                    seq = ringBuffer.next();
                    try {
                        ringBuffer.get(seq).setOrder(symbol, record.value(), record.offset());
                    } finally {
                        ringBuffer.publish(seq);
                    }
                }
            } catch (org.apache.kafka.common.errors.WakeupException e) {
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (running.get()) {
                    log.warn("MatchSlot consumer error slotIndex={}", index, e);
                }
            }
        }
    }

    /**
     * 单生产者：仅 consumeLoop 线程调用，将事件写入 Disruptor。
     */
    private void publishToRingBuffer(SlotTaskEvent.Type type, String symbol, HaEvent haEvent, long comparedOffset) {
        if (ringBuffer == null) return;
        long seq = ringBuffer.next();
        try {
            SlotTaskEvent ev = ringBuffer.get(seq);
            switch (type) {
                case SNAPSHOT -> ev.setSnapshot(symbol);
                case HA -> ev.setHa(haEvent);
                default -> {
                }
            }
        } finally {
            ringBuffer.publish(seq);
        }
    }

    private void drainPendingSlotEvents() throws InterruptedException {
        SlotEvent e;
        List<String> topicsToAdd = new ArrayList<>(4);
        while ((e = pendingSlotEvents.poll()) != null) {
            if (e instanceof AddSymbolEvent add) {
                String symbol = add.getSymbol();
                if (symbol != null && !symbol.isEmpty()) {
                    MatchEngine engine = enginesBySymbol.putIfAbsent(symbol, new MatchEngine(symbol));
                    if (engine == null && add.getInitialMasterOffset() > 0) {
                        enginesBySymbol.get(symbol).getBook().updateMasterReqOffsetIfGreater(add.getInitialMasterOffset());
                    } else if (engine != null && add.getInitialMasterOffset() > 0) {
                        engine.getBook().updateMasterReqOffsetIfGreater(add.getInitialMasterOffset());
                    }
                    topicsToAdd.add(symbolToTopic(symbol));
                }
            } else if (e instanceof HaEvent ha) {
                if (ha == HaEvent.CLOSE) {
                    running.set(false);
                    throw new InterruptedException("MatchSlot HA close");
                } else {
                    publishToRingBuffer(SlotTaskEvent.Type.HA, null, ha, 0);
                }
            } else if (e instanceof SnapshotEvent snapEv) {
                publishToRingBuffer(SlotTaskEvent.Type.SNAPSHOT, snapEv.symbol(), null, 0);
            } else if (e instanceof ComparedEvent compared) {
                if (ringBuffer != null) {
                    long seq = ringBuffer.next();
                    try {
                        ringBuffer.get(seq).setMoveCompareOffset(compared.symbol(), compared.offset(), compared.slaveQueueStartIndex());
                    } finally {
                        ringBuffer.publish(seq);
                    }
                }
            }
        }
        if (!topicsToAdd.isEmpty()) {
            Set<TopicPartition> set = new HashSet<>(consumer.assignment());
            for (String topic : topicsToAdd) {
                set.add(new TopicPartition(topic, 0));
            }
            consumer.assign(set);
            log.info("MatchSlot index={} added {} topics, total assigned={}", index, topicsToAdd.size(), set.size());
        }
    }

    private void replayFromFileQueue() {
        if (fileQueueWriter == null) return;
        java.util.Map<String, Long> tailOffsets = tailQueryService.queryLastOrderReqOffset(enginesBySymbol.keySet());
        for (Map.Entry<String, MatchEngine> entry : enginesBySymbol.entrySet()) {
            String symbol = entry.getKey();
            MatchEngine engine = entry.getValue();
            OrderBook book = engine.getBook();
            long tailOffset = tailOffsets.getOrDefault(symbol, 0L);
            if (tailOffset > 0) {
                book.updateMasterReqOffsetIfGreater(tailOffset);
            }
            long minExclusive = Math.max(book.getMasterReqOffset(), book.getComparedFileOffset());
            long replayHint = book.getComparedFileQueueStartIndex();
            String matchResultTopic = matchResultTopic(symbol);
            long startTime = System.nanoTime();
            log.info("MatchSlot index={} replay file queue symbol={} minExclusive={} ,count = {},replayStartIndexHint={}", index, symbol, minExclusive, book.getReqOffset() - minExclusive, replayHint);
            fileQueueWriter.replay(symbol, minExclusive, replayHint, (payload, orderReqOffset) -> producer.send(matchResultRecord(matchResultTopic, symbol, orderReqOffset, payload), new Callback() {
                @Override
                public void onCompletion(RecordMetadata metadata, Exception exception) {
                    engine.getBook().updateMasterReqOffsetIfGreater(orderReqOffset);
                }
            }));

            log.info("MatchSlot index={} replay file queue symbol={},cost={} completed", index, symbol, (System.nanoTime() - startTime) / 1000);
            fileQueueWriter.clear(symbol);
            log.info("MatchSlot index={} BECAME_MASTER clear file queue symbol={},cost={} ", index, symbol, (System.nanoTime() - startTime) / 1000);
            book.setComparedFileQueueStartIndex(-1L);
        }
        log.info("MatchSlot index={} BECAME_MASTER replay completed", index);
    }

    private KafkaConsumer<String, String> createConsumer() {
        Properties props = new Properties();
        log.info("KafkaConsumer : {}", bootstrapServers);
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "match-engine-" + HOSTNAME + "-" + System.currentTimeMillis());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        // 无已提交 offset 时从分区开头读，避免新启动或新 group 时漏掉已有 order_req 消息
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new KafkaConsumer<>(props);
    }

    /**
     * Disruptor EventHandler: dispatch ORDER / SNAPSHOT / HA from SlotTaskEvent.
     */
    private void dispatchSlotTaskEvent(SlotTaskEvent event) {
        try {
            switch (event.getType()) {
                case ORDER ->
                        process(new OrderCommandEnvelope(event.getSymbol(), event.getRawJson(), event.getOrderReqOffset()));
                case SNAPSHOT -> takeSnapshot(event.getSymbol());
                case HA -> {
                    if (event.getHaEvent() == HaEvent.MASTER) {
                        isMaster = true;
                        replayFromFileQueue();
                    } else {
                        isMaster = false;
                    }
                }
                case MOVE_COMPARE_OFFSET -> {
                    MatchEngine engine = enginesBySymbol.get(event.getSymbol());
                    if (engine != null) {
                        OrderBook book = engine.getBook();
                        book.setComparedFileOffset(event.getCompareOffset());
                        book.setComparedFileQueueStartIndex(event.getCompareQueueIndex());
                    }
                }
                case START -> startLatch.countDown();
                default -> {

                }
            }
        } catch (Exception e) {
            log.warn("MatchSlot disruptor handler error slotIndex={} type={}", index, event.getType(), e);
        }
    }

    private void takeSnapshot(String symbol) {
        if (snapshotDir == null) return;
        MatchEngine engine = enginesBySymbol.get(symbol);
        if (engine == null) return;
        OrderBook book = engine.getBook();
        long offset = book.getReqOffset();

        if (book.getSnapshotOffset() >= offset || offset <= 0) {
            return;
        }

        Collection<com.tk.match.engine.BookOrder> orders = book.exportOrders();
        try {
            SnapshotFileHelper.write(snapshotDir, symbol, offset, orders);
            book.setSnapshotOffset(offset);
            log.info("snapshot completed symbol={} offset={}", symbol, offset);
        } catch (Exception e) {
            log.error("MatchSlot snapshot write failed symbol={} slot={}", symbol, index, e);
        }
    }

    private void process(OrderCommandEnvelope envelope) {
        String symbol = envelope.getSymbol();
        String json = envelope.getRawJson();

        long orderReqOffset = envelope.getOrderReqOffset();

        OrderCommand cmd;
        try {
            cmd = ProtocolSerde.orderCommandFromJson(json);
        } catch (Exception e) {
            log.warn("Invalid OrderCommand json symbol={} slot={}", symbol, index, e);
            return;
        }

        MatchEngine engine = enginesBySymbol.get(symbol);
        if (engine == null) {
            return;
        }

        MatchResponse response = engine.process(cmd, orderReqOffset);

        if (response != null) {
            MatchResponse withOffset = MatchResponse.builder().taker(response.getTaker()).trades(response.getTrades()).finishOrders(response.getFinishOrders()).offset(ProtocolVersion.CURRENT).build();
            String out = ProtocolSerde.toJson(withOffset);
            if (isMaster) {
                String matchResultTopic = matchResultTopic(symbol);
                OrderBook book = engine.getBook();
                if (book.getMasterReqOffset() < orderReqOffset) {
                    producer.send(matchResultRecord(matchResultTopic, symbol, orderReqOffset, out), (m, ex) -> {
                        if (ex != null) {
                            log.error("MatchSlot send error symbol={} slot={}", symbol, index, ex);
                        } else {
                            book.updateMasterReqOffsetIfGreater(orderReqOffset);
                        }
                    });
                }
            } else {
                if (fileQueueWriter != null) {
                    fileQueueWriter.write(symbol, orderReqOffset, out);
                }
            }
        }
    }

    private static String matchResultTopic(String symbol) {
        if (StringUtils.isEmpty(symbol)) {
            throw new IllegalArgumentException("symbol is empty");
        }
        return "match_result_" + symbol;
    }

    private static final String HOSTNAME = hostname();

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }

    /**
     * 构建带 header 的 match_result_ 消息，便于下游按 symbol/orderReqOffset/主机名 路由或追踪。
     * Header: "symbol" (UTF-8), "orderReqOffset" (order_req offset 的字符串), "host" (本机主机名)。
     */
    private static ProducerRecord<String, String> matchResultRecord(String topic, String symbol, long reqOffset, String payload) {
        List<Header> headers = new ArrayList<>(3);
        if (symbol != null) {
            headers.add(new RecordHeader("symbol", symbol.getBytes(StandardCharsets.UTF_8)));
        }
        headers.add(new RecordHeader("orderReqOffset", Long.toString(reqOffset).getBytes(StandardCharsets.UTF_8)));
        headers.add(new RecordHeader("host", HOSTNAME.getBytes(StandardCharsets.UTF_8)));
        return new ProducerRecord<>(topic, 0, "", payload, headers);
    }

    public void notifyClose() {
        pendingSlotEvents.add(SlotEvent.becameClose());
    }
}
