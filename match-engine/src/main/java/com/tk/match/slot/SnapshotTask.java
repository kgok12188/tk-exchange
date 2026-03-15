package com.tk.match.slot;

public class SnapshotTask implements SlotTask {

    private final String symbol;

    public SnapshotTask(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }
}
