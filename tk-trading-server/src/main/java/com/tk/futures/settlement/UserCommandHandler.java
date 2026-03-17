package com.tk.futures.settlement;

import com.alibaba.fastjson2.JSONObject;
import com.tk.futures.model.PersistenceBatchList;
import com.tk.futures.slot.SlotContext;
import com.tx.common.enums.TradingCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 处理非撮合类用户指令（CREATE_USER / NEW_ORDER / CANCEL_ORDER / TRANSFER 等）。
 * 当前版本不再从数据库恢复 UserTradingBook，后续将通过快照机制恢复。
 */
@Service
public class UserCommandHandler {

    private static final Logger logger = LoggerFactory.getLogger(UserCommandHandler.class);

    public UserCommandHandler() {
    }

    public PersistenceBatchList handle(TradingCommand command, Long uid, JSONObject data, SlotContext slotContext, String shardName) {
        PersistenceBatchList items = new PersistenceBatchList();
        if (uid == null || uid <= 0) {
            return items;
        }
        // 目前不从数据库加载初始 UserTradingBook；仅在已有内存状态时处理。
        if (slotContext.getBook(uid) == null) {
            logger.debug("skip command={} for uid={} without in-memory UserTradingBook", command, uid);
            return items;
        }
        logger.debug("handle user command={}, uid={}", command, uid);
        // 后续在此根据 command + data 更新 book，并填充 AsyncMessageItems。
        return items;
    }
}


