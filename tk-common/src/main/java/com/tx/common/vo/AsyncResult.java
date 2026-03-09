package com.tx.common.vo;

import lombok.Getter;
import lombok.Setter;


@Getter
@Setter
public class AsyncResult extends R<Object> {
    private String reqId;
}
