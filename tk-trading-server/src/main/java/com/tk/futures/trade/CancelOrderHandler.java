package com.tk.futures.trade;

import com.alibaba.fastjson2.JSONObject;
import com.tk.futures.model.TradingAccount;
import com.tk.protocol.dto.UserCommandResult;
import com.tx.common.enums.TradingCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 处理非撮合类用户指令（CREATE_USER / NEW_ORDER / CANCEL_ORDER / TRANSFER 等）。
 * 当前版本不再从数据库恢复 UserTradingBook，后续将通过快照机制恢复。
 */
@Service
public class CancelOrderHandler {

    private static final Logger logger = LoggerFactory.getLogger(CancelOrderHandler.class);

    public CancelOrderHandler() {
    }

    /**
     * 处理非撮合类用户指令。
     * <p>
     * 注意：本方法的职责是更新内存中的 UserTradingBook，并构造给上游（open-api）的业务结果。
     * 持久化批次（PersistenceBatchList）在 commit 阶段由当前 Book 的变更集统一构建，而不是作为返回值向上游传递。
     */
    public UserCommandResult handle(TradingCommand command, JSONObject data, TradingAccount tradingBook) {
        // TODO: 根据 command + data 修改 tradingBook，并填充业务数据到 result.data。
        return UserCommandResult.builder()
                .success(true)
                .build();
    }

}


