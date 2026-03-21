package com.tk.futures.slot;

import com.tk.futures.model.TradingAccount;
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
    private final Map<Long, TradingAccount> booksByUid = new ConcurrentHashMap<>();

    @Setter
    @Getter
    private long offset;

    @Setter
    @Getter
    private long pushOffset;

    @Setter
    @Getter
    private long comparedFileOffset;

    public SlotContext(int slotIndex) {
        this.slotIndex = slotIndex;
    }

    public TradingAccount createBook(Long uid) {
        return booksByUid.computeIfAbsent(uid, id -> new TradingAccount(id, new java.util.LinkedList<>(), new java.util.LinkedList<>()));
    }

    public TradingAccount getBook(Long uid) {
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

