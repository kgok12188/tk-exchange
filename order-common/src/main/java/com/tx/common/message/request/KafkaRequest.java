package com.tx.common.message.request;

import lombok.Data;

@Data
public final class KafkaRequest {

    private String reqId;
    private String method;
    private Object params;
    private Long uid;

    public KafkaRequest() {

    }

    public KafkaRequest(String method, Object params, Long uid) {
        this.method = method;
        this.params = params;
        this.uid = uid;
    }

}
