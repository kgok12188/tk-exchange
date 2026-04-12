package com.tk.match.cluster;

import com.tk.match.engine.ArrayStackBookOrder;
import com.tk.match.engine.MatchEngine;
import com.tk.match.output.MatchResultEgress;
import com.tk.protocol.sbe.Decimal64Codec;
import com.tk.protocol.sbe.SbeDecoder;
import com.tk.protocol.sbe.SbeEncoder;
import com.tk.protocol.sbe.generated.*;
import com.tk.protocol.dto.*;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link ClusteredService}：共识入口、SBE、订单簿与集群快照。
 * MatchResult 写 MDC、Archive 录制与 dedup 由 {@link MatchResultEgress} 在 {@link #onStart} 经 {@link MatchResultEgress#startMatchResultPipeline(boolean, long)} 完成。
 */
@Component
public class MatchClusteredService implements ClusteredService {

    private static final Logger log = LoggerFactory.getLogger(MatchClusteredService.class);

    private static final int BOOK_ORDER_POOL_SIZE = 4096;
    private static final int ENCODING_BUFFER_CAPACITY = 16 * 1024 * 1024;

    private final Map<Integer, MatchEngine> engines = new HashMap<>();
    private final Map<Integer, String> symbolNames = new HashMap<>();

    private long nextMatchSeq = 0;

    private final MatchResultEgress matchResultEgress;
    private final SbeDecoder sbeDecoder = new SbeDecoder();
    private final SbeEncoder sbeEncoder = new SbeEncoder();
    private final MutableDirectBuffer encodingBuffer = new ExpandableDirectByteBuffer(ENCODING_BUFFER_CAPACITY);

    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    private final SnapshotHeaderDecoder snapshotHeaderDecoder = new SnapshotHeaderDecoder();
    private final SnapshotSymbolHeaderDecoder symbolHeaderDecoder = new SnapshotSymbolHeaderDecoder();
    private final SnapshotBookOrderDecoder bookOrderDecoder = new SnapshotBookOrderDecoder();

    private final IdleStrategy idleStrategy = new YieldingIdleStrategy();

    private final ArrayStackBookOrder arrayStackBookOrder = new ArrayStackBookOrder(BOOK_ORDER_POOL_SIZE);

    public MatchClusteredService(MatchResultEgress matchResultEgress) {
        this.matchResultEgress = matchResultEgress;
    }

    @Override
    public void onStart(Cluster cluster, Image snapshotImage) {
        boolean restoredFromSnapshot = snapshotImage != null;
        if (restoredFromSnapshot) {
            loadSnapshot(snapshotImage);
        }
        nextMatchSeq = matchResultEgress.startMatchResultPipeline(restoredFromSnapshot, nextMatchSeq);

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

    @Override
    public void onSessionMessage(
            ClientSession session, long timestamp,
            DirectBuffer buffer, int offset, int length, Header header) {

        OrderCommand cmd = sbeDecoder.decode(buffer, offset, length);
        int symbolId = sbeDecoder.extractSymbolId(buffer, offset);

        MatchEngine engine = engines.computeIfAbsent(symbolId, this::createDefaultEngine);

        MatchResponse response = (cmd != null) ? engine.process(cmd, nextMatchSeq, timestamp) : null;

        emitMatchResult(symbolId, response);
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
        matchResultEgress.shutdown();
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
            MarketConfig cfg = engine.getBook().getMarketConfig();

            int symbolLen = sbeEncoder.encodeSnapshotSymbolHeader(
                    symbolId,
                    engine.getBook().getOrderCount(),
                    engine.getBook().getAppliedMarketConfigVersion(),
                    cfg.getPriceScale(),
                    cfg.getQtyScale(),
                    cfg.getMinQty(),
                    cfg.getMinTradeQuoteAmount(),
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
                    String name = symbolNames.getOrDefault(symbolId, "symbol-" + symbolId);

                    BigDecimal minQty = Decimal64Codec.decode(symbolHeaderDecoder.minQty());
                    BigDecimal minTradeQuoteAmount = Decimal64Codec.decode(symbolHeaderDecoder.minTradeQuoteAmount());
                    MarketConfig cfg = MarketConfig.builder()
                            .symbol(name)
                            .priceScale(symbolHeaderDecoder.priceScale())
                            .qtyScale(symbolHeaderDecoder.qtyScale())
                            .minQty(minQty)
                            .minTradeQuoteAmount(minTradeQuoteAmount)
                            .build();
                    long configVersion = symbolHeaderDecoder.appliedConfigVersion();
                    MatchEngine engine = engines.computeIfAbsent(symbolId,
                            id -> createEngine(name, cfg));
                    engine.getBook().applyMarketConfig(cfg, configVersion);
                    log.debug("Restored symbol: symbolId={} name={}", symbolId, name);
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

        matchResultEgress.emitEncodedMatchResult(nextMatchSeq, encodingBuffer, 0, encodedLength);
    }

    private void offerToPublication(ExclusivePublication publication, MutableDirectBuffer buffer, int offset, int length) {
        long result;
        do {
            result = publication.offer(buffer, offset, length);
            if (result < 0) {
                idleStrategy.idle(0);
            }
        } while (result < 0);
        idleStrategy.reset();
    }

    private MatchEngine createDefaultEngine(int symbolId) {
        String name = symbolNames.getOrDefault(symbolId, "symbol-" + symbolId);
        log.warn("Creating default engine for unknown symbolId={} name={}; " +
                "send UpdateMarketCommand to configure properly", symbolId, name);
        return createEngine(name, MarketConfig.defaultFor(name));
    }

    private MatchEngine createEngine(String name, MarketConfig config) {
        return new MatchEngine(name, config, arrayStackBookOrder);
    }

    public void registerSymbol(int symbolId, String name) {
        symbolNames.put(symbolId, name);
    }

}
