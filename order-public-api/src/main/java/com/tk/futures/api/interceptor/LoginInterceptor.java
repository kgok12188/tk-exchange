package com.tk.futures.api.interceptor;

import com.tx.common.entity.User;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Component
public class LoginInterceptor implements HandlerInterceptor {

    private static final String EXCHANGE_TOKEN = "exchange-token";

    private static final ThreadLocal<User> userThreadLocal = new ThreadLocal<>();
    private static final ThreadLocal<String> sessionThreadLocal = new ThreadLocal<>();
    private static final ThreadLocal<String> tokenThreadLocal = new ThreadLocal<>();
    private static final ThreadLocal<String> signSecretThreadLocal = new ThreadLocal<>();
    private static final ThreadLocal<String> ipThreadLocal = new ThreadLocal<>();

    private RestTemplate restTemplate = new RestTemplate();

    private static final Logger log = LoggerFactory.getLogger(LoginInterceptor.class);


    @Value("${sign_secret:sign_000_000_secret_0000000}")
    private String signSecret;
    @Autowired
    private ApplicationContext applicationContext;


    @Value("${exchangeOpenApiUrl:}")
    private String exchangeOpenApiUrl;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = request.getHeader(EXCHANGE_TOKEN);
        String timezone = request.getHeader("timezone");
        if (StringUtils.isNotBlank(token)) {

        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        userThreadLocal.remove();
        sessionThreadLocal.remove();
        signSecretThreadLocal.remove();
        ipThreadLocal.remove();
    }

    public static void setUser(User user) {
        userThreadLocal.set(user);
    }

    public static User getUser() {
        return userThreadLocal.get();
    }


    public static String getSessionKey() {
        return sessionThreadLocal.get();
    }


    public static String getToken() {
        return tokenThreadLocal.get();
    }

    public static String getSignSecret() {
        return signSecretThreadLocal.get();
    }


    public static void setRealIp(String ip) {
        ipThreadLocal.set(ip);
    }

    public static String getRealIp() {
        return ipThreadLocal.get();
    }

}

