package com.tk.futures.inbound;

import com.tk.futures.slot.SettlementSlotManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * CommandRouter 的基础实现：根据 command + uid 将消息路由到结算槽位（SettlementSlotManager）。
 */
@Service
public class TradingCommandRouter implements CommandRouter {

    private static final Logger logger = LoggerFactory.getLogger(TradingCommandRouter.class);

    private final SettlementSlotManager slotManager;

    public TradingCommandRouter(SettlementSlotManager slotManager) {
        this.slotManager = slotManager;
    }

    @Override
    public void route(CommandMessage message) {
        String command = message.getCommand();
        if (command == null || command.isEmpty()) {
            logger.warn("skip message without command, uid={}, offset={}", message.getUid(), message.getOffset());
            return;
        }
        switch (command) {
            case "UPDATE_MARK_PRICE":
            case "UPDATE_INDEX_PRICE":
                slotManager.submitBroadcast(message);
                break;
            default:
                // 其它均视为用户维度指令，需带 uid。
                slotManager.submitUserCommand(message);
        }
    }
}

