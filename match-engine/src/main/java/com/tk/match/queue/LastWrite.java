package com.tk.match.queue;

import lombok.Data;
import lombok.ToString;

@Data
@ToString
public class LastWrite {

    private long orderReqOffset;
    private long lastIndexAppended;

    public LastWrite(long orderReqOffset, long lastIndexAppended) {
        this.orderReqOffset = orderReqOffset;
        this.lastIndexAppended = lastIndexAppended;
    }

}