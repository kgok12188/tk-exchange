package com.tk.match.snapshot;

import com.tk.match.engine.BookOrder;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of loading a snapshot file: offset, sorted orders, optional market config.
 */
public final class SnapshotLoadResult {

    private final long offset;
    private final List<BookOrder> orders;
    private final SnapshotMetadata snapshotMetadata;
    private final long marketConfigVersion;

    public SnapshotLoadResult(long offset, List<BookOrder> orders) {
        this(offset, orders, null, -1L);
    }

    public SnapshotLoadResult(long offset, List<BookOrder> orders, SnapshotMetadata snapshotMetadata, long marketConfigVersion) {
        this.offset = offset;
        this.orders = orders != null ? orders : new ArrayList<>();
        this.snapshotMetadata = snapshotMetadata;
        this.marketConfigVersion = marketConfigVersion;
    }

    public long offset() {
        return offset;
    }

    public List<BookOrder> orders() {
        return orders;
    }

    public SnapshotMetadata snapshotMetadata() {
        return snapshotMetadata;
    }

    public long marketConfigVersion() {
        return marketConfigVersion;
    }
}
