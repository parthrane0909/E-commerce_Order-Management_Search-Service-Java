package com.meridian.backend.exception;

public class AppException extends RuntimeException {
    private final String code;
    private final int statusCode;
    private final Object details;

    public AppException(String message, String code, int statusCode, Object details) {
        super(message);
        this.code = code;
        this.statusCode = statusCode;
        this.details = details;
    }

    public String getCode() {
        return code;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public Object getDetails() {
        return details;
    }
}