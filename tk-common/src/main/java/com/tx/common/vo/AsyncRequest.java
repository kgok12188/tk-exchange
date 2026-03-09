package com.tx.common.vo;

import com.alibaba.fastjson2.JSONObject;
import lombok.Data;

@Data
public class AsyncRequest {

    private String reqId;

    private String method; // 请求的业务方法


    private JSONObject params;


    public <T> T toJavaObject(Class<T> clazz) {
        return params.to(clazz);
    }


}
