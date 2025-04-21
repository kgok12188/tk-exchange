package com.tx.common.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AsyncMessageItem {

    @Getter
    public enum Type {
        CLOSE(-1),
        ACCOUNT(0),
        TRANSFER(1),
        ORDER(2),
        POSITION(3),
        TRADE_ORDER(4);

        private final int value;

        Type(int value) {
            this.value = value;
        }

        public static Type fromValue(Integer value) {
            for (Type type : Type.values()) {
                if (type.value == value) {
                    return type;
                }
            }
            return null;
        }

    }

    private int type;
    private List<Object> messages;
}
