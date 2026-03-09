package com.tx.common.vo;

public class R<T> {

    private T data;
    private String message;
    private int code;


    public static <T> R<T> success() {
        return new R<T>().setData(null).setCode(200);
    }

    public static <T> R<T> success(T data) {
        return new R<T>().setData(null).setCode(200).setData(data);
    }

    public static <T> R<T> fail(int code, String message) {
        return new R<T>().setData(null).setCode(code).setMessage(message);
    }

    public T getData() {
        return data;
    }

    public R<T> setData(T data) {
        this.data = data;
        return this;
    }

    public String getMessage() {
        return message;
    }

    public R<T> setMessage(String message) {
        this.message = message;
        return this;
    }

    public int getCode() {
        return code;
    }

    public R<T> setCode(int code) {
        this.code = code;
        return this;
    }

}
