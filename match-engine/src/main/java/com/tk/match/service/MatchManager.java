package com.tk.match.service;

import com.tk.match.config.MatchEngineConfig;
import com.tk.match.queue.DelayedFileDeletionService;
import com.tk.match.queue.LastWrite;
import com.tk.match.queue.MatchResultMasterFileQueue;
import com.tk.match.slot.MatchSlot;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 启动时按 slot 划分 order_req topics（hash(symbol)%N），为每个槽位创建 MatchSlot；
 * 每个 MatchSlot 自管 Kafka 消费 + 队列 + 撮合 worker（见 架构 3.6）。
 * 支持运行时上币：{@link #addSymbol(String)} 可在不重启前提下为指定 symbol 开始撮合。
 */
@Component
public class MatchManager {

    private static final Logger log = LoggerFactory.getLogger(MatchManager.class);
    private static final String ORDER_REQ_PREFIX = "order_req_";

    private final String bootstrapServers;
    private final int ringBufferNumbers;
    private final List<String> symbols;

    private KafkaProducer<String, String> producer;
    private List<MatchSlot> slots;
    /**
     * 已上币 symbol（含启动配置 + 运行时 addSymbol），用于上币幂等。
     */
    private final Set<String> symbolsAdded = ConcurrentHashMap.newKeySet();

    @Autowired
    private MatchResultMasterFileQueue matchResultMasterFileQueue;
    @Autowired
    private MatchResultTailQueryService matchResultTailQueryService;
    @Autowired
    private DelayedFileDeletionService delayedFileDeletionService;

    private final String snapshotDir;
    private final String fileQueueDir;

    public MatchManager(MatchEngineConfig matchConfig,
                        @org.springframework.beans.factory.annotation.Value("${kafka.servers:localhost:9092}") String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
        this.ringBufferNumbers = matchConfig.getRingBufferNumbers() > 0 ? matchConfig.getRingBufferNumbers() : 4;
        this.symbols = matchConfig.getSymbols() != null ? matchConfig.getSymbols() : new ArrayList<>();
        String dir = matchConfig.getSnapshotDir();
        this.snapshotDir = (dir != null && !dir.isBlank()) ? dir : null;
        String fqDir = matchConfig.getFileQueueDir();
        this.fileQueueDir = (fqDir != null && !fqDir.isBlank()) ? fqDir : null;
    }

    @PostConstruct
    public void start() {
        if (symbols == null || symbols.isEmpty()) {
            log.warn("match.symbols empty, MatchManager not started");
            return;
        }
        producer = createProducer();
        slots = new ArrayList<>(ringBufferNumbers);
        for (int i = 0; i < ringBufferNumbers; i++) {
            MatchSlot slot = getMatchSlot(i);
            slots.add(slot);
            slot.start();
        }
        symbolsAdded.addAll(symbols);
        log.info("MatchManager started slots={} symbols={}", ringBufferNumbers, symbols);
    }

    private MatchSlot getMatchSlot(int i) {
        List<String> mySymbols = new ArrayList<>();
        for (String symbol : symbols) {
            if (slotIndex(symbol) == i) {
                mySymbols.add(symbol);
            }
        }
        Path snapshotPath = snapshotDir != null ? Path.of(snapshotDir) : null;
        Path fileQueuePath = fileQueueDir != null ? Path.of(fileQueueDir) : null;
        return new MatchSlot(
                i,
                producer,
                mySymbols,
                bootstrapServers,
                snapshotPath,
                fileQueuePath,
                matchResultTailQueryService,
                delayedFileDeletionService);
    }

    public int getSlotCount() {
        return slots == null ? 0 : slots.size();
    }

    /**
     * 返回指定 slot 当前负责的币对集合（用于定时任务下发快照）。
     */
    public Set<String> getSymbolsBySlotIndex(int slotIndex) {
        if (slots == null || slotIndex < 0 || slotIndex >= slots.size()) return Set.of();
        return slots.get(slotIndex).getSymbols();
    }

    /**
     * 该 symbol 在从节点 slave 文件队列上最近一次 write 的 LastWrite；无写入过或未启用文件队列时返回 null。
     */
    public LastWrite getSlaveLastWrite(String symbol) {
        if (symbol == null || symbol.isEmpty() || slots == null) return null;
        int k = slotIndex(symbol);
        if (k < 0 || k >= slots.size()) return null;
        return slots.get(k).getLastWrite(symbol);
    }

    /**
     * 该 symbol 在 master 消费写入文件队列（MatchResultMasterFileQueue）上最近一次的 LastWrite；无消费写入过或未启用时返回 null。
     */
    public LastWrite getMasterLastWrite(String symbol) {
        if (symbol == null || symbol.isEmpty()) return null;
        return matchResultMasterFileQueue != null ? matchResultMasterFileQueue.getLastWrite(symbol) : null;
    }

    /**
     * 向对应 slot 下发打快照请求；快照请求与 order_req 同队，由 worker 按序执行。
     */
    public void submitTakeSnapshot(String symbol) {
        if (symbol == null || symbol.isEmpty() || slots == null) return;
        int k = slotIndex(symbol);
        slots.get(k).submitTakeSnapshot(symbol);
    }

    /**
     * 切主后由 ZK 选主回调（或 HaStatus watcher）在 {@code HaStatus.setMaster(true)} 之后调用，
     * 向每个 MatchSlot 投递 BECAME_MASTER，consumeLoop 将执行文件队列补发。
     */
    public void notifyBecameMaster() {
        if (slots != null) {
            if (matchResultMasterFileQueue != null) {
                matchResultMasterFileQueue.stop();
            }
            for (MatchSlot matchSlot : slots) {
                matchSlot.becameMaster();
            }
            log.info("MatchManager notifyBecameMaster slots={}", slots.size());
        }
    }

    public void notifyBecameSlave() {
        if (slots == null) return;
        for (MatchSlot slot : slots) {
            slot.becameSlave();
        }
        if (matchResultMasterFileQueue != null) {
            matchResultMasterFileQueue.start();
            for (int i = 0; i < slots.size(); i++) {
                for (String symbol : getSymbolsBySlotIndex(i)) {
                    matchResultMasterFileQueue.addSymbol(symbol);
                }
            }
        }
        log.info("MatchManager notifyBecameSlave slots={}", slots.size());
    }


    /**
     * 运行时上币：为指定 symbol 开始消费 order_req_(symbol) 并撮合，无需重启。
     * 幂等：已上币的 symbol 再次调用会直接返回。
     *
     * @param symbol 交易对，如 "BTC-USDT" 或 "BTC_USDT"
     * @return true 表示本次新上币，false 表示已存在（幂等）
     */
    public boolean addSymbol(String symbol) {
        if (symbol == null || symbol.isEmpty()) return false;
        if (slots == null) return false;
        if (!symbolsAdded.add(symbol)) {
            return false;
        }
        int k = slotIndex(symbol);
        long initialMasterOffset = matchResultTailQueryService.queryLastOrderReqOffset(symbol);
        slots.get(k).addSymbol(symbol, initialMasterOffset);
        if (matchResultMasterFileQueue != null) {
            matchResultMasterFileQueue.addSymbol(symbol);
        }
        log.info("MatchManager addSymbol symbol={} topic={} slot={} initialMasterOffset={}", symbol, ORDER_REQ_PREFIX + symbol, k, initialMasterOffset);
        return true;
    }

    @PreDestroy
    public void stop() {
        if (slots != null) {
            for (MatchSlot slot : slots) {
                slot.stop();
            }
        }
        if (producer != null) {
            producer.close();
        }
        log.info("MatchManager stopped");
    }

    private int slotIndex(String symbol) {
        if (symbol == null) return 0;
        int h = symbol.hashCode();
        return (h & 0x720F_F01F) % ringBufferNumbers;
    }

    private KafkaProducer<String, String> createProducer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");
        return new KafkaProducer<>(props);
    }
}
