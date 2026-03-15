package com.tk.match.snapshot;

import com.tk.match.engine.BookOrder;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of loading a snapshot file: offset and sorted orders.
 */
public final class SnapshotLoadResult {

    private final long offset;
    private final List<BookOrder> orders;

    public SnapshotLoadResult(long offset, List<BookOrder> orders) {
        this.offset = offset;
        this.orders = orders != null ? orders : new ArrayList<>();
    }

    public long offset() {
        return offset;
    }

    public List<BookOrder> orders() {
        return orders;
    }
}
