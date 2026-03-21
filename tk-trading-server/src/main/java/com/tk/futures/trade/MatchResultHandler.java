package com.tk.futures.trade;

import com.tk.futures.model.TradingAccount;
import com.tk.protocol.dto.TradingSettle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 结算引擎：以 TradingSettle 为输入，在 UserTradingBook 上应用变更，并生成 AsyncMessageItem 列表。
 * 当前实现仅完成结构与入口，具体业务计算可在后续迭代中补充。
 */
@Service
public class MatchResultHandler {

    private static final Logger logger = LoggerFactory.getLogger(MatchResultHandler.class);

    /**
     * 在给定的 UserTradingBook 上应用撮合结算结果，并将需要持久化的变更追加到 items 中。
     * <p>
     * 注意：本方法不负责 commit/rollback，由上层调用者（例如 SettlementEventHandler）在事务边界统一处理。
     */
    public void handle(Long uid, TradingSettle settle, TradingAccount book) {
        if (settle == null) {
            logger.warn("SettlementEngine.handle called with null TradingSettle, uid={}", uid);
            return;
        }
    }

}

