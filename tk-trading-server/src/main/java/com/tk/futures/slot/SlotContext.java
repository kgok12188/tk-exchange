package com.tk.futures.slot;

import com.tk.futures.model.UserTradingBook;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单个结算槽位的上下文：维护 uid → UserTradingBook 的映射。
 */
public class SlotContext {

    private final int slotIndex;
    private final Map<Long, UserTradingBook> booksByUid = new ConcurrentHashMap<>();

    public SlotContext(int slotIndex) {
        this.slotIndex = slotIndex;
    }

    public int getSlotIndex() {
        return slotIndex;
    }

    public UserTradingBook getOrCreateBook(Long uid) {
        return booksByUid.computeIfAbsent(uid, id -> new UserTradingBook(id, new java.util.LinkedList<>(), new java.util.LinkedList<>(), new java.util.LinkedList<>()));
    }

    public UserTradingBook getBook(Long uid) {
        return booksByUid.get(uid);
    }
}

