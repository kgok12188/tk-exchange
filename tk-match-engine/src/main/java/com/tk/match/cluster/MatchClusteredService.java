package com.tk.match.cluster;

import com.tk.match.admin.AdminCommandResult;
import com.tk.match.admin.PendingCommandRegistry;
import com.tk.match.engine.ArrayStackBookOrder;
import com.tk.match.engine.MatchEngine;
import com.tk.match.output.MatchResultSideChannel;
import com.tk.protocol.dto.*;
import com.tk.protocol.sbe.Decimal64Codec;
import com.tk.protocol.sbe.SbeDecoder;
import com.tk.protocol.sbe.SbeEncoder;
import com.tk.protocol.sbe.generated.*;
import io.aeron.ExclusivePublication;
import io.aeron.Image;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableDirectByteBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.YieldingIdleStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Aeron ClusteredService：共识入口，按 SBE templateId 分发订单与 admin 指令。
 * <p>
 * 消息路由（design.md §3 / §13）:
 * <ul>
 *   <li>templateId=1 PushOrderCommand  → engine.process()</li>
 *   <li>templateId=2 CancelOrderCommand → engine.process()</li>
 *   <li>templateId=3 UpdateMarketCommand → engine.applyConfig()</li>
 *   <li>templateId=4 OpenMarketCommand → 创建 MatchEngine（幂等）</li>
 *   <li>templateId=5 CloseMarketCommand → engine.close()</li>
 * </ul>
 * 所有命令统一执行 {@code nextMatchSeq++}，产出 MatchResult（admin 命令产出空结果）。
 */
@Component
public class MatchClusteredService implements ClusteredService {

    private static final Logger log = LoggerFactory.getLogger(MatchClusteredService.class);

    private static final int BOOK_ORDER_POOL_SIZE = 4096;
    private static final int ENCODING_BUFFER_CAPACITY = 16 * 1024 * 1024;

    /**
     * symbolId → MatchEngine；每个 symbol 独立状态机。
     */
    private final Map<Integer, MatchEngine> engines = new HashMap<>();
    /**
     * symbolId → symbolName；由 OpenMarketCommand 写入，快照恢复时重建。
     */
    private final Map<Integer, String> symbolNames = new HashMap<>();

    private long nextMatchSeq = 0;

    /**
     * 零 UUID 表示命令非 HTTP 发起，无需回调。
     */
    private static final UUID NULL_UUID = new UUID(0L, 0L);

    private final MatchResultSideChannel matchResultSideChannel;
    private final PendingCommandRegistry pendingCommandRegistry;
    private final SbeDecoder sbeDecoder = new SbeDecoder();
    private final SbeEncoder sbeEncoder = new SbeEncoder();
    private final MutableDirectBuffer encodingBuffer = new ExpandableDirectByteBuffer(ENCODING_BUFFER_CAPACITY);

    // ── Ingress decoders ─────────────────────────────────────────────────────
    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    private final UpdateMarketCommandDecoder updateMarketDecoder = new UpdateMarketCommandDecoder();
    private final OpenMarketCommandDecoder openMarketDecoder = new OpenMarketCommandDecoder();
    private final CloseMarketCommandDecoder closeMarketDecoder = new CloseMarketCommandDecoder();

    // ── Snapshot decoders ─────────────────────────────────────────────────────
    private final SnapshotHeaderDecoder snapshotHeaderDecoder = new SnapshotHeaderDecoder();
    private final SnapshotSymbolHeaderDecoder symbolHeaderDecoder = new SnapshotSymbolHeaderDecoder();
    private final SnapshotBookOrderDecoder bookOrderDecoder = new SnapshotBookOrderDecoder();

    private final IdleStrategy idleStrategy = new YieldingIdleStrategy();
    private final ArrayStackBookOrder arrayStackBookOrder = new ArrayStackBookOrder(BOOK_ORDER_POOL_SIZE);

    public MatchClusteredService(MatchResultSideChannel matchResultSideChannel,
                                 PendingCommandRegistry pendingCommandRegistry) {
        this.matchResultSideChannel = matchResultSideChannel;
        this.pendingCommandRegistry = pendingCommandRegistry;
    }

    @Override
    public void onStart(Cluster cluster, Image snapshotImage) {
        boolean restoredFromSnapshot = snapshotImage != null;
        if (restoredFromSnapshot) {
            loadSnapshot(snapshotImage);
        }
        nextMatchSeq = matchResultSideChannel.startMatchResultPipeline(restoredFromSnapshot, nextMatchSeq);
        log.info("MatchClusteredService started: nextMatchSeq={}", nextMatchSeq);
    }

    @Override
    public void onSessionOpen(ClientSession session, long timestamp) {
        log.info("Client session opened: sessionId={}", session.id());
    }

    @Override
    public void onSessionClose(ClientSession session, long timestamp, CloseReason closeReason) {
        log.info("Client session closed: sessionId={} reason={}", session.id(), closeReason);
    }

    /**
     * 唯一的 Raft 共识入口，所有节点确定性地执行相同的分发逻辑。
     * 每条消息最终均执行 nextMatchSeq++ 并产出 MatchResult。
     */
    @Override
    public void onSessionMessage(ClientSession session, long timestamp,
                                 DirectBuffer buffer, int offset, int length, Header header) {

        int templateId = sbeDecoder.extractTemplateId(buffer, offset);
        int bodyOffset = offset + headerDecoder.wrap(buffer, offset).encodedLength();
        int blockLength = headerDecoder.blockLength();
        int schemaVersion = headerDecoder.version();

        switch (templateId) {
            case PushOrderCommandDecoder.TEMPLATE_ID:
            case CancelOrderCommandDecoder.TEMPLATE_ID: {
                int symbolId = sbeDecoder.extractSymbolId(buffer, offset);
                MatchEngine engine = engines.get(symbolId);
                MatchResponse response;
                if (engine == null) {
                    response = rejectUnknownSymbol(symbolId);
                } else if (engine.isClosed()) {
                    response = rejectMarketClosed(symbolId);
                } else {
                    OrderCommand cmd = sbeDecoder.decode(buffer, offset, length);
                    response = (cmd != null) ? engine.process(cmd, nextMatchSeq, timestamp) : null;
                }
                emitMatchResult(symbolId, response);
                break;
            }

            case UpdateMarketCommandDecoder.TEMPLATE_ID: {
                updateMarketDecoder.wrap(buffer, bodyOffset, blockLength, schemaVersion);
                int symbolId = (int) updateMarketDecoder.symbolId();
                MatchEngine engine = engines.get(symbolId);
                MatchResponse response = null;
                String updateRejectReason = null;
                if (engine == null) {
                    updateRejectReason = "UNKNOWN_SYMBOL";
                } else if (engine.isClosed()) {
                    updateRejectReason = "MARKET_CLOSED";
                } else {
                    BigDecimal minQty = Decimal64Codec.decode(updateMarketDecoder.minQty());
                    BigDecimal minTradeQuoteAmount = Decimal64Codec.decode(updateMarketDecoder.minTradeQuoteAmount());
                    MatchMarketConfig cfg = MatchMarketConfig.builder()
                            .symbolId(symbolId)
                            .symbolName(symbolNames.getOrDefault(symbolId, ""))
                            .priceScale(updateMarketDecoder.priceScale())
                            .qtyScale(updateMarketDecoder.qtyScale())
                            .minQty(minQty)
                            .minTradeQuoteAmount(minTradeQuoteAmount)
                            .build();
                    boolean force = updateMarketDecoder.force() == BooleanType.T;
                    response = engine.applyConfig(cfg, updateMarketDecoder.configVersion(), force);
                }
                emitMatchResult(symbolId, response);
                notifyPendingCommand(updateMarketDecoder.uuidHigh(), updateMarketDecoder.uuidLow(), updateRejectReason);
                break;
            }

            case OpenMarketCommandDecoder.TEMPLATE_ID: {
                openMarketDecoder.wrap(buffer, bodyOffset, blockLength, schemaVersion);
                int symbolId = (int) openMarketDecoder.symbolId();
                String openRejectReason = null;
                if (!engines.containsKey(symbolId)) {
                    String symbolName = openMarketDecoder.symbolName();
                    BigDecimal minQty = Decimal64Codec.decode(openMarketDecoder.minQty());
                    BigDecimal minTradeQuoteAmount = Decimal64Codec.decode(openMarketDecoder.minTradeQuoteAmount());
                    MatchMarketConfig cfg = MatchMarketConfig.builder()
                            .symbolId(symbolId)
                            .symbolName(symbolName)
                            .priceScale(openMarketDecoder.priceScale())
                            .qtyScale(openMarketDecoder.qtyScale())
                            .minQty(minQty)
                            .minTradeQuoteAmount(minTradeQuoteAmount)
                            .build();
                    engines.put(symbolId, new MatchEngine(symbolName, cfg, arrayStackBookOrder));
                    symbolNames.put(symbolId, symbolName);
                    engines.get(symbolId).getBook().applyMatchMarketConfig(cfg, openMarketDecoder.configVersion());
                    log.info("Market opened: symbolId={} name={}", symbolId, symbolName);
                } else {
                    openRejectReason = "SYMBOL_ALREADY_EXISTS";
                }
                emitMatchResult(symbolId, null);
                notifyPendingCommand(openMarketDecoder.uuidHigh(), openMarketDecoder.uuidLow(), openRejectReason);
                break;
            }

            case CloseMarketCommandDecoder.TEMPLATE_ID: {
                closeMarketDecoder.wrap(buffer, bodyOffset, blockLength, schemaVersion);
                int symbolId = (int) closeMarketDecoder.symbolId();
                MatchEngine engine = engines.get(symbolId);
                MatchResponse response = null;
                String closeRejectReason = null;
                if (engine == null) {
                    closeRejectReason = "UNKNOWN_SYMBOL";
                } else if (engine.isClosed()) {
                    closeRejectReason = "MARKET_CLOSED";
                } else {
                    boolean force = closeMarketDecoder.force() == BooleanType.T;
                    response = engine.close(force);
                    log.info("Market closed: symbolId={} force={}", symbolId, force);
                }
                emitMatchResult(symbolId, response);
                notifyPendingCommand(closeMarketDecoder.uuidHigh(), closeMarketDecoder.uuidLow(), closeRejectReason);
                break;
            }

            default:
                log.warn("Unknown templateId={}, skipping", templateId);
                break;
        }

        nextMatchSeq++;
    }

    @Override
    public void onTimerEvent(long correlationId, long timestamp) {
    }

    @Override
    public void onRoleChange(Cluster.Role newRole) {
        log.info("Cluster role changed to: {}", newRole);
    }

    @Override
    public void onTerminate(Cluster cluster) {
        log.info("MatchClusteredService terminating");
        matchResultSideChannel.shutdown();
    }

    @Override
    public void onTakeSnapshot(ExclusivePublication snapshotPublication) {
        log.info("Taking snapshot: nextMatchSeq={} symbolCount={}", nextMatchSeq, engines.size());

        int headerLen = sbeEncoder.encodeSnapshotHeader(nextMatchSeq, engines.size(),
                encodingBuffer, 0);
        offerToPublication(snapshotPublication, encodingBuffer, 0, headerLen);

        for (Map.Entry<Integer, MatchEngine> entry : engines.entrySet()) {
            int symbolId = entry.getKey();
            MatchEngine engine = entry.getValue();
            MatchMarketConfig cfg = engine.getBook().getMatchMarketConfig();
            String symbolName = symbolNames.getOrDefault(symbolId, cfg.getSymbolName());

            int symbolLen = sbeEncoder.encodeSnapshotSymbolHeader(
                    symbolId,
                    engine.getBook().getOrderCount(),
                    engine.getBook().getAppliedMatchMarketConfigVersion(),
                    cfg.getPriceScale(),
                    cfg.getQtyScale(),
                    cfg.getMinQty(),
                    cfg.getMinTradeQuoteAmount(),
                    engine.isClosed(),
                    symbolName,
                    encodingBuffer, 0);
            offerToPublication(snapshotPublication, encodingBuffer, 0, symbolLen);

            engine.getBook().visitBookOrder((orderId, order) -> {
                int orderLen = sbeEncoder.encodeSnapshotBookOrder(
                        symbolId,
                        order.getOrderId(),
                        order.getUid() != null ? order.getUid() : 0L,
                        order.getShardId(),
                        order.getSide(),
                        order.getPrice(),
                        order.getVolume(),
                        order.getRemainingVolume(),
                        order.getAmount(),
                        order.getRemainingAmount(),
                        order.getSeq(),
                        encodingBuffer, 0);
                offerToPublication(snapshotPublication, encodingBuffer, 0, orderLen);
            });
        }

        log.info("Snapshot complete");
    }

    private void loadSnapshot(Image snapshotImage) {
        log.info("Loading snapshot from image position={}", snapshotImage.position());

        FragmentHandler handler = (buffer, offset, length, header) -> {
            headerDecoder.wrap(buffer, offset);
            int templateId = headerDecoder.templateId();
            int bodyOffset = offset + headerDecoder.encodedLength();
            int blockLength = headerDecoder.blockLength();
            int schemaVersion = headerDecoder.version();

            switch (templateId) {
                case SnapshotHeaderDecoder.TEMPLATE_ID: {
                    snapshotHeaderDecoder.wrap(buffer, bodyOffset, blockLength, schemaVersion);
                    nextMatchSeq = snapshotHeaderDecoder.nextMatchSeq();
                    log.info("Snapshot header: nextMatchSeq={} symbolCount={}",
                            nextMatchSeq, snapshotHeaderDecoder.symbolCount());
                    break;
                }
                case SnapshotSymbolHeaderDecoder.TEMPLATE_ID: {
                    symbolHeaderDecoder.wrap(buffer, bodyOffset, blockLength, schemaVersion);
                    int symbolId = (int) symbolHeaderDecoder.symbolId();
                    String symbolName = symbolHeaderDecoder.symbolName();
                    if (symbolName == null || symbolName.isEmpty()) {
                        symbolName = "symbol-" + symbolId;
                    }
                    boolean closed = symbolHeaderDecoder.closed() == BooleanType.T;

                    BigDecimal minQty = Decimal64Codec.decode(symbolHeaderDecoder.minQty());
                    BigDecimal minTradeQuoteAmount = Decimal64Codec.decode(symbolHeaderDecoder.minTradeQuoteAmount());
                    MatchMarketConfig cfg = MatchMarketConfig.builder()
                            .symbolId(symbolId)
                            .symbolName(symbolName)
                            .priceScale(symbolHeaderDecoder.priceScale())
                            .qtyScale(symbolHeaderDecoder.qtyScale())
                            .minQty(minQty)
                            .minTradeQuoteAmount(minTradeQuoteAmount)
                            .build();
                    long configVersion = symbolHeaderDecoder.appliedConfigVersion();
                    String finalSymbolName = symbolName;
                    MatchEngine engine = engines.computeIfAbsent(symbolId,
                            id -> new MatchEngine(finalSymbolName, cfg, arrayStackBookOrder));
                    engine.getBook().applyMatchMarketConfig(cfg, configVersion);
                    if (closed) {
                        engine.close(false);
                    }
                    symbolNames.put(symbolId, symbolName);
                    log.debug("Restored symbol: symbolId={} name={} closed={}", symbolId, symbolName, closed);
                    break;
                }
                case SnapshotBookOrderDecoder.TEMPLATE_ID: {
                    bookOrderDecoder.wrap(buffer, bodyOffset, blockLength, schemaVersion);
                    int symbolId = (int) bookOrderDecoder.symbolId();
                    MatchEngine engine = engines.get(symbolId);
                    if (engine == null) {
                        log.warn("SnapshotBookOrder for unknown symbolId={}", symbolId);
                        return;
                    }
                    String side = bookOrderDecoder.side() == Side.BUY ? "BUY" : "SELL";
                    BigDecimal price = Decimal64Codec.decode(bookOrderDecoder.price());
                    BigDecimal remainingVolume = Decimal64Codec.decode(bookOrderDecoder.remainingVolume());
                    engine.getBook().restoreOrder(
                            bookOrderDecoder.orderId(),
                            bookOrderDecoder.uid(),
                            bookOrderDecoder.shardId(),
                            side, price, remainingVolume,
                            bookOrderDecoder.seq());
                    break;
                }
                default:
                    break;
            }
        };

        while (!snapshotImage.isEndOfStream()) {
            int fragments = snapshotImage.poll(handler, 10);
            idleStrategy.idle(fragments);
        }
        log.info("Snapshot loaded: nextMatchSeq={} engines={}", nextMatchSeq, engines.size());
    }

    private void emitMatchResult(int symbolId, MatchResponse response) {
        long takerUid = 0L;
        long takerOrderId = 0L;
        int takerShardId = 0;
        List<TradeOrder> trades = Collections.emptyList();
        List<FinishOrder> finishOrders = Collections.emptyList();

        if (response != null) {
            TakerRef taker = response.getTaker();
            if (taker != null) {
                takerUid = taker.getUid() != null ? taker.getUid() : 0L;
                takerOrderId = taker.getOrderId() != null ? taker.getOrderId() : 0L;
                takerShardId = taker.getShardId();
            }
            if (response.getTrades() != null) {
                trades = response.getTrades();
            }
            if (response.getFinishOrders() != null) {
                finishOrders = response.getFinishOrders();
            }
        }

        int encodedLength = sbeEncoder.encodeMatchResult(
                nextMatchSeq, symbolId,
                takerUid, takerOrderId, takerShardId,
                trades, finishOrders,
                encodingBuffer, 0);

        matchResultSideChannel.emitEncodedMatchResult(nextMatchSeq, encodingBuffer, 0, encodedLength);
    }

    /**
     * 若命令携带有效 UUID（非 0,0），则唤醒对应的 HTTP 等待线程。
     * Follower 节点的 pendingCommandRegistry 中没有该 UUID 时静默忽略。
     *
     * @param uuidHigh     UUID 高 64 位
     * @param uuidLow      UUID 低 64 位
     * @param rejectReason 非 null 表示执行失败，null 表示成功
     */
    private void notifyPendingCommand(long uuidHigh, long uuidLow, String rejectReason) {
        if (uuidHigh == 0L && uuidLow == 0L) {
            return;
        }
        UUID uuid = new UUID(uuidHigh, uuidLow);
        if (rejectReason == null) {
            pendingCommandRegistry.tryComplete(uuid.toString(), new AdminCommandResult(true, "OK"));
        } else {
            pendingCommandRegistry.tryComplete(uuid.toString(), new AdminCommandResult(false, rejectReason));
        }
    }

    private static MatchResponse rejectUnknownSymbol(int symbolId) {
        log.warn("Rejected order for unknown symbolId={}", symbolId);
        return MatchResponse.builder()
                .taker(null)
                .trades(Collections.emptyList())
                .finishOrders(Collections.emptyList())
                .build();
    }

    private static MatchResponse rejectMarketClosed(int symbolId) {
        log.warn("Rejected order for closed market symbolId={}", symbolId);
        return MatchResponse.builder()
                .taker(null)
                .trades(Collections.emptyList())
                .finishOrders(Collections.emptyList())
                .build();
    }

    private void offerToPublication(ExclusivePublication publication, MutableDirectBuffer buffer,
                                    int offset, int length) {
        long result;
        do {
            result = publication.offer(buffer, offset, length);
            if (result < 0) {
                idleStrategy.idle(0);
            }
        } while (result < 0);
        idleStrategy.reset();
    }
}
