package com.tx.common.config;

import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.writer.ObjectWriter;

import java.lang.reflect.Type;
import java.math.BigDecimal;

public class BigDecimalToStringSerializer implements ObjectWriter<BigDecimal> {
    @Override
    public void write(JSONWriter jsonWriter, Object object, Object fieldName, Type fieldType, long features) {
        if (object == null) {
            jsonWriter.writeNull();
            return;
        }
        BigDecimal value = (BigDecimal) object;
        jsonWriter.writeString(value.stripTrailingZeros().toPlainString());
    }

}