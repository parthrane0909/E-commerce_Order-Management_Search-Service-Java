package com.meridian.backend.exception;

public class DependencyUnavailableException extends AppException {
    public DependencyUnavailableException(String message, String code, Object details) {
        super(message, code, 503, details);
    }
}