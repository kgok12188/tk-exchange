package com.tk.match.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.tk.match.engine.BookOrder;
import com.tk.match.engine.OrderBook;
import com.tk.match.engine.Roaring64NavigableMapWrapper;
import com.tk.protocol.dto.MarketConfig;
import org.roaringbitmap.longlong.Roaring64NavigableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Read/write snapshot files: first line metadata JSON, then one JSON per order line.
 */
public final class SnapshotFileHelper {

    private static final Logger log = LoggerFactory.getLogger(SnapshotFileHelper.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static {
        SimpleModule module = new SimpleModule();
        module.addSerializer(BigDecimal.class, new PlainStringBigDecimalSerializer());
        MAPPER.registerModule(module);
    }

    /**
     * 写入快照，并在首行 metadata 中携带当前 {@link MarketConfig} 与已应用版本（设计 §10）。
     * <p>
     * 通过 {@link OrderBook#visitBookOrder} 逐个遍历挂单写入，避免一次性 {@link OrderBook#exportOrders()} 分配整表集合。
     */
    public static void write(Path snapshotDir, String symbol, long offset, OrderBook book,
                             MarketConfig marketConfig, long appliedMarketConfigVersion) throws IOException {
        if (snapshotDir == null) throw new IOException("snapshotDir is null");
        if (book == null) throw new IOException("book is null");
        Files.createDirectories(snapshotDir);
        String fileName = symbol + "." + String.format("%019d", offset);
        Path file = snapshotDir.resolve(fileName);
        int orderCount = book.getOrderCount();

        SnapshotMetadata meta = buildMetadata(offset, symbol, orderCount, marketConfig, appliedMarketConfigVersion, book);
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(MAPPER.writeValueAsString(meta));
            w.newLine();
            IOException[] writeErr = new IOException[1];
            book.visitBookOrder((id, o) -> {
                if (writeErr[0] != null) {
                    return;
                }
                try {
                    w.write(MAPPER.writeValueAsString(o));
                    w.newLine();
                } catch (IOException e) {
                    writeErr[0] = e;
                }
            });
            if (writeErr[0] != null) {
                throw writeErr[0];
            }
            w.flush();
        }
        log.debug("Snapshot written symbol={} offset={} orders={} path={}", symbol, offset, orderCount, file);
    }

    private static SnapshotMetadata buildMetadata(long offset, String symbol, int orderCount,
                                                  MarketConfig marketConfig, long appliedMarketConfigVersion, OrderBook book) throws IOException {

        SnapshotMetadata.SnapshotMetadataBuilder mb = SnapshotMetadata.builder()
                .offset(offset)
                .orderCount(orderCount)
                .symbol(symbol)
                .ts(System.currentTimeMillis())
                .metadataVersion(1);
        if (marketConfig != null) {
            mb.marketConfig(marketConfig);
        }
        if (appliedMarketConfigVersion >= 0) {
            mb.marketConfigVersion(appliedMarketConfigVersion);
        }
        ArrayDeque<Roaring64NavigableMapWrapper> navigableMapWrappers = book.getNavigableMapWrappers();
        if (!navigableMapWrappers.isEmpty()) {
            LinkedHashMap<String, String> navigable = new LinkedHashMap<>();
            for (Roaring64NavigableMapWrapper navigableMapWrapper : navigableMapWrappers) {
                Roaring64NavigableMap roaring64 = navigableMapWrapper.getRoaring64NavigableMap();
                roaring64.runOptimize();
                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                // 必须 close ObjectOutputStream，否则缓冲区未刷入，反序列化会 EOFException
                try (ObjectOutputStream oos = new ObjectOutputStream(byteArrayOutputStream)) {
                    roaring64.writeExternal(oos);
                }
                String value = Base64.getEncoder().encodeToString(byteArrayOutputStream.toByteArray());
                navigable.put(String.valueOf(navigableMapWrapper.getTimestamp()), value);
            }
            mb.navigable(navigable);
        }
        return mb.build();
    }

    /**
     * Find snapshot file for symbol in dir (name pattern {symbol}.{19 digits}), validate and parse.
     * Candidates are sorted by offset descending (newest first). Tries candidate 0, then 1, ... until one loads successfully.
     * Returns null if no file or all candidates fail validation/parse.
     */
    public static SnapshotLoadResult load(Path snapshotDir, String symbol) {
        if (snapshotDir == null || !Files.isDirectory(snapshotDir)) return null;
        if (symbol == null || symbol.isBlank()) return null;
        String prefix = symbol + ".";
        try {
            List<Path> candidates;
            try (Stream<Path> stream = Files.list(snapshotDir)) {
                candidates = stream
                        .filter(p -> p.getFileName().toString().startsWith(prefix))
                        .filter(p -> p.getFileName().toString().length() == prefix.length() + 19)
                        .filter(p -> p.getFileName().toString().substring(prefix.length()).chars().allMatch(Character::isDigit))
                        .sorted((a, b) -> Long.compare(parseOffsetFromFileName(b.getFileName().toString(), prefix), parseOffsetFromFileName(a.getFileName().toString(), prefix)))
                        .toList();
            }
            if (candidates.isEmpty()) return null;
            for (int idx = 0; idx < candidates.size(); idx++) {
                Path file = candidates.get(idx);
                SnapshotLoadResult result = loadOne(file, symbol, prefix);
                if (result != null) {
                    if (idx > 0) {
                        log.info("Snapshot loaded from fallback candidate symbol={} candidateIndex={} path={} offset={}", symbol, idx, file, result.offset());
                    }
                    return result;
                }
            }
            log.warn("Snapshot load failed for all {} candidate(s) symbol={} dir={}", candidates.size(), symbol, snapshotDir);
            return null;
        } catch (Exception e) {
            log.warn("Snapshot load failed symbol={} dir={}", symbol, snapshotDir, e);
            return null;
        }
    }

    /**
     * Load and validate a single snapshot file. Returns null on any failure.
     */
    private static SnapshotLoadResult loadOne(Path file, String symbol, String prefix) {
        try {
            if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
                log.warn("Snapshot file not valid (not regular or not readable) path={}", file);
                return null;
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                log.warn("Snapshot file empty path={}", file);
                return null;
            }
            SnapshotMetadata meta = MAPPER.readValue(lines.get(0), SnapshotMetadata.class);
            if (!isMetadataValid(meta, symbol, file)) {
                return null;
            }
            int expectedOrders = meta.getOrderCount();
            if (lines.size() - 1 != expectedOrders) {
                log.warn("Snapshot order count mismatch path={} expected={} actual={}", file, expectedOrders, lines.size() - 1);
                return null;
            }
            long offsetInFileName = parseOffsetFromFileName(file.getFileName().toString(), prefix);
            if (meta.getOffset() != offsetInFileName) {
                log.warn("Snapshot offset mismatch path={} metadata.offset={} fileName.offset={}", file, meta.getOffset(), offsetInFileName);
                return null;
            }
            List<BookOrder> orderList = new ArrayList<>(expectedOrders);
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line == null || line.isBlank()) {
                    log.warn("Snapshot blank order line path={} lineIndex={}", file, i + 1);
                    return null;
                }
                BookOrder order = MAPPER.readValue(line, BookOrder.class);
                if (!isOrderValid(order)) {
                    log.warn("Snapshot invalid order path={} lineIndex={} orderId={}", file, i + 1, order != null ? order.getOrderId() : null);
                    return null;
                }
                orderList.add(order);
            }
            orderList.sort(Comparator.comparingLong(BookOrder::getSeq));
            long cfgVer = meta.getMarketConfigVersion() != null ? meta.getMarketConfigVersion() : -1L;
            return new SnapshotLoadResult(meta.getOffset(), orderList, meta, cfgVer);
        } catch (Exception e) {
            log.debug("Snapshot loadOne failed path={}", file, e);
            return null;
        }
    }

    private static boolean isMetadataValid(SnapshotMetadata meta, String expectedSymbol, Path file) {
        if (meta == null) {
            log.warn("Snapshot metadata null path={}", file);
            return false;
        }
        if (meta.getOffset() < 0) {
            log.warn("Snapshot metadata invalid offset path={} offset={}", file, meta.getOffset());
            return false;
        }
        if (meta.getOrderCount() < 0) {
            log.warn("Snapshot metadata invalid orderCount path={} orderCount={}", file, meta.getOrderCount());
            return false;
        }
        if (expectedSymbol != null && (meta.getSymbol() == null || !meta.getSymbol().equals(expectedSymbol))) {
            log.warn("Snapshot metadata symbol mismatch path={} expected={} actual={}", file, expectedSymbol, meta.getSymbol());
            return false;
        }
        return true;
    }

    private static boolean isOrderValid(BookOrder order) {
        if (order == null) return false;
        if (order.getPrice() == null || order.getRemainingVolume() == null) return false;
        if (order.getRemainingVolume().signum() < 0) return false;
        return true;
    }

    private static long parseOffsetFromFileName(String fileName, String prefix) {
        try {
            return Long.parseLong(fileName.substring(prefix.length()));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

}
