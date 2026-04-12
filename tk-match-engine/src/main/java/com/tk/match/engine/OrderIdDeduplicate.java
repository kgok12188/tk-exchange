package com.tk.match.engine;

import exchange.core2.collections.art.LongAdaptiveRadixTreeMap;
import org.roaringbitmap.longlong.Roaring64NavigableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Map;

/**
 * Maintains in-memory duplicate detection for order ids:
 * - active ids in current order book
 * - recent historical ids in time windows
 */
public final class OrderIdDeduplicate {

    private static final Logger log = LoggerFactory.getLogger(OrderIdDeduplicate.class);

    private final int maxWindowCount;
    private final int windowIntervalMillis;
    private final ArrayDeque<Roaring64NavigableMapWrapper> recentOrderIdWindows;

    public OrderIdDeduplicate(int maxWindowCount, int windowIntervalMillis) {
        this.maxWindowCount = maxWindowCount;
        this.windowIntervalMillis = windowIntervalMillis;
        this.recentOrderIdWindows = new ArrayDeque<>(maxWindowCount);
    }

    public boolean isDuplicate(long orderId, LongAdaptiveRadixTreeMap<BookOrder> activeOrdersById, long timestamp) {
        if (activeOrdersById.get(orderId) != null) {
            return true;
        }
        for (Roaring64NavigableMapWrapper timeWindow : recentOrderIdWindows) {
            if (timeWindow.getRoaring64NavigableMap().contains(orderId)) {
                return true;
            }
        }
        record(orderId, timestamp);
        return false;
    }

    private void record(long orderId, long timestamp) {
        long timeWindowIndex = timestamp / windowIntervalMillis;
        if (recentOrderIdWindows.isEmpty()) {
            Roaring64NavigableMapWrapper newWindow = new Roaring64NavigableMapWrapper(new Roaring64NavigableMap(), timeWindowIndex);
            newWindow.getRoaring64NavigableMap().add(orderId);
            recentOrderIdWindows.add(newWindow);
            return;
        }

        Roaring64NavigableMapWrapper latestWindow = recentOrderIdWindows.getLast();
        if (timeWindowIndex == latestWindow.getTimestamp()) {
            latestWindow.getRoaring64NavigableMap().add(orderId);
            return;
        }

        if (recentOrderIdWindows.size() >= maxWindowCount) {
            recentOrderIdWindows.removeFirst();
        }
        Roaring64NavigableMapWrapper newWindow = new Roaring64NavigableMapWrapper(new Roaring64NavigableMap(), timeWindowIndex);
        newWindow.getRoaring64NavigableMap().add(orderId);
        recentOrderIdWindows.add(newWindow);
    }

    public void loadFromSnapshot(Map<String, String> encodedNavigableWindows, String symbol) throws Exception {
        if (encodedNavigableWindows == null || encodedNavigableWindows.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> navigableEntry : encodedNavigableWindows.entrySet()) {
            Long windowTimestamp = Long.valueOf(navigableEntry.getKey());
            byte[] navigableBlob = Base64.getDecoder().decode(navigableEntry.getValue());
            if (navigableBlob.length == 0) {
                log.warn("snapshot navigable blob empty, skip key={} symbol={}", navigableEntry.getKey(), symbol);
                continue;
            }
            Roaring64NavigableMap navigableMap = new Roaring64NavigableMap();
            try (ObjectInputStream objectInputStream = new ObjectInputStream(new ByteArrayInputStream(navigableBlob))) {
                navigableMap.readExternal(objectInputStream);
            }
            navigableMap.runOptimize();
            recentOrderIdWindows.add(new Roaring64NavigableMapWrapper(navigableMap, windowTimestamp));
        }
    }

    public ArrayDeque<Roaring64NavigableMapWrapper> recentOrderIdWindows() {
        return recentOrderIdWindows;
    }
}

