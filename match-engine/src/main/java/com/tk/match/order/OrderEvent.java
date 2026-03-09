package com.tk.match.order;

import com.tx.common.entity.Order;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderEvent {

    public enum EventType {
        CANCEL,
        FINISH,
        UPDATE
    }

    private Order order;
    private String eventLog; //  cancel, finish,update
}
