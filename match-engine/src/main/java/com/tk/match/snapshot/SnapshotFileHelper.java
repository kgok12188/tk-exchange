package com.tk.match.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.tk.match.engine.BookOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
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
     * Write snapshot to {snapshotDir}/{symbol}.{19-digit offset}.
     * Write snapshot by appending line-by-line and flushing at end; avoids holding full file in memory.
     */
    public static void write(Path snapshotDir, String symbol, long offset, Collection<BookOrder> orders) throws IOException {
        if (snapshotDir == null) throw new IOException("snapshotDir is null");
        Files.createDirectories(snapshotDir);
        String fileName = symbol + "." + String.format("%019d", offset);
        Path file = snapshotDir.resolve(fileName);
        SnapshotMetadata meta = SnapshotMetadata.builder()
                .offset(offset)
                .orderCount(orders.size())
                .symbol(symbol)
                .ts(System.currentTimeMillis())
                .build();
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(MAPPER.writeValueAsString(meta));
            w.newLine();
            for (BookOrder o : orders) {
                w.write(MAPPER.writeValueAsString(o));
                w.newLine();
            }
            w.flush();
        }
        log.debug("Snapshot written symbol={} offset={} orders={} path={}", symbol, offset, orders.size(), file);
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
            return new SnapshotLoadResult(meta.getOffset(), orderList);
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
