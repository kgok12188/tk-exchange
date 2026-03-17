package com.tk.futures.slot;

import com.tk.futures.model.UserTradingBook;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单个结算槽位的上下文：维护 uid → UserTradingBook 的映射。
 */
public class SlotContext {

    @Getter
    private final int slotIndex;
    private final Map<Long, UserTradingBook> booksByUid = new ConcurrentHashMap<>();

    @Setter
    @Getter
    private long offset;

    public SlotContext(int slotIndex) {
        this.slotIndex = slotIndex;
    }

    public UserTradingBook createBook(Long uid) {
        return booksByUid.computeIfAbsent(uid, id -> new UserTradingBook(id, new java.util.LinkedList<>(), new java.util.LinkedList<>()));
    }

    public UserTradingBook getBook(Long uid) {
        return booksByUid.get(uid);
    }


    public void removeBook(Long uid) {
        booksByUid.remove(uid);
    }

    public boolean offsetIfGreaterThanCurrent(long offset) {
        if (offset > this.offset) {
            this.offset = offset;
            return true;
        } else {
            return false;
        }
    }

}

