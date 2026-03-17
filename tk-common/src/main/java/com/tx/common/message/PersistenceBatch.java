package com.tx.common.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PersistenceBatch {

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

    /**
     * 持久化批次的类型（ACCOUNT / ORDER / POSITION / TRADE_ORDER / TRANSFER 等）。
     */
    private int type;

    /**
     * 需要写入数据库的一批记录（同一批次、同一类型）。
     */
    private List<Object> messages;
}
