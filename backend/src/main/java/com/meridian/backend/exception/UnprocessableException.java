package com.meridian.backend.exception;

public class UnprocessableException extends AppException {
    public UnprocessableException(String message, String code, Object details) {
        super(message, code, 422, details);
    }
}