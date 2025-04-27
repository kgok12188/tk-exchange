package com.tk.futures.process;

import com.alibaba.fastjson.JSON;
import com.tk.futures.model.AsyncMessageItems;
import com.tk.futures.model.DataContext;
import com.tk.futures.model.MethodAnnotation;
import com.tk.futures.model.UserData;
import com.tk.futures.generator.TxIdGenerator;
import com.tk.futures.service.UserDataService;
import com.tx.common.entity.MarketConfig;
import com.tx.common.kafka.KafkaTopic;
import com.tx.common.vo.AsyncResult;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

@Setter
public abstract class BaseProcess {


    protected Logger logger = LoggerFactory.getLogger(this.getClass());

    protected static final ThreadLocal<Context> dataContextLocal = new ThreadLocal<>();


    private static final Map<String, ExecMethod> invokedMethods = new HashMap<>();

    public static void setContext(Context context) {
        dataContextLocal.set(context);
    }

    public static void removeDataContext() {
        dataContextLocal.remove();
    }

    public long nextTxId() {
        return dataContextLocal.get().txIdGenerator.nextId();
    }

    public void response(Object data) {
        response(200, "", data);
    }

    public void fail(int code, String message) {
        response(code, message, null);
    }

    public void response(int code, String message, Object data) {
        Context context = dataContextLocal.get();
        if (context == null || context.getKafkaProducer() == null) {
            return;
        }
        AsyncResult asyncResult = new AsyncResult();
        asyncResult.setCode(code);
        asyncResult.setData(data);
        asyncResult.setMessage(message);
        asyncResult.setReqId(dataContextLocal.get().getRequestId());
        String kafkaMessage = JSON.toJSONString(asyncResult);
        dataContextLocal.get().getKafkaProducer().send(new ProducerRecord<>(KafkaTopic.RESPONSE_MESSAGE, kafkaMessage), (metadata, exception) -> {
            if (exception != null) {
                logger.error("send_response_error", exception);
            }
        });
    }

    protected BaseProcess() {
        Method[] methods = this.getClass().getMethods();
        for (Method method : methods) {
            MethodAnnotation methodAnnotation = method.getAnnotation(MethodAnnotation.class);
            if (methodAnnotation != null) {
                if (StringUtils.isNoneBlank(methodAnnotation.value())) {
                    Class<?>[] parameterTypes = method.getParameterTypes();
                    if ((parameterTypes.length == 2 || parameterTypes.length == 1) && parameterTypes[0] == UserData.class && method.getReturnType() == AsyncMessageItems.class) {
                        String methodName = methodAnnotation.value();
                        methodName = StringUtils.isBlank(methodName) ? method.getName() : methodName;
                        ExecMethod old = invokedMethods.put(methodName, new ExecMethod(this, method, parameterTypes.length == 2 ? parameterTypes[1] : null));
                        if (old != null) {
                            logger.error("methodName = {}", methodName);
                            throw new IllegalArgumentException("methodName = " + methodName);
                        }
                        if (parameterTypes.length == 2) {
                            logger.info("methodName = {}, class = {},\tmethod = {},\tparams = {}", methodName, this.getClass().getName(), method.getName(), parameterTypes[1].getName());
                        } else {
                            logger.info("methodName = {}, class = {},\tmethod = {}", methodName, this.getClass().getName(), method.getName());
                        }
                    }
                }
            }
        }
    }

    public static ExecMethod getExecMethod(String methodName) {
        return invokedMethods.get(methodName);
    }

    @Data
    @AllArgsConstructor
    public static class ExecMethod {
        private BaseProcess process;
        private Method method;
        private Class<?> paramsClass;
    }

    @Data
    @AllArgsConstructor
    public static class Context {
        private DataContext dataContext;
        private String requestId;
        private ApplicationContext applicationContext;
        private TxIdGenerator txIdGenerator;
        private KafkaProducer<String, String> kafkaProducer;
        private UserDataService userDataService;
        private Map<Integer, MarketConfig> markerConfigs;
    }

}
