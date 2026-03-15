package com.tx.common.enums;

import org.apache.commons.lang3.StringUtils;

public enum TradingCommand {

    NEW_ORDER, CANCEL_ORDER, MATCH, TRANSFER, UPDATE_MARK_PRICE, UPDATE_INDEX_PRICE, CREATE_USER, UNKNOWN;

    public static TradingCommand ofValue(String name) {
        if (name == null) {
            return UNKNOWN;
        }
        for (TradingCommand command : values()) {
            if (StringUtils.equalsIgnoreCase(command.name(), name)) {
                return command;
            }
        }
        return UNKNOWN;
    }

}
