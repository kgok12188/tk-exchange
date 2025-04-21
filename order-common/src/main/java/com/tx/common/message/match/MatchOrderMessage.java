package com.tx.common.message.match;

import com.alibaba.fastjson.JSONArray;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class MatchOrderMessage {

    public enum MessageType {
        MATCH_ORDER(0), //  撮合订单
        EXCEPTION_ORDER(1), // 异常订单
        LIQ_ORDER(2); // 爆仓订单

        private int value;

        MessageType(int value) {
            this.value = value;
        }

        public int value() {
            return value;
        }
    }

    private Integer messageType;

    private Object data;

    public <T> List<T> toJavaObject(Class<T> clz) {
        if (data instanceof JSONArray) {
            JSONArray array = (JSONArray) data;
            return array.toJavaList(clz);
        } else {
            return null;
        }
    }

    public boolean isMatchOrder() {
        return messageType == MessageType.MATCH_ORDER.value;
    }

    public boolean isExceptionOrder() {
        return messageType == MessageType.EXCEPTION_ORDER.value;
    }

    public boolean isLiqOrder() {
        return messageType == MessageType.LIQ_ORDER.value;
    }

}
