package com.meridian.backend.exception;

public class ConflictException extends AppException {
    public ConflictException(String message, String code, Object details) {
        super(message, code, 409, details);
    }
}