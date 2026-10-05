package com.meridian.backend.exception;

public class NotFoundException extends AppException {
    public NotFoundException(String message, String code, Object details) {
        super(message, code, 404, details);
    }
}