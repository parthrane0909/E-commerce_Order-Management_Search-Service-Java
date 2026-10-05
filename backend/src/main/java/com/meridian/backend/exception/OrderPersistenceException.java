package com.meridian.backend.exception;

public class OrderPersistenceException extends AppException {
    public OrderPersistenceException(String message) {
        super(message, "order_persistence_failed", 500, null);
    }

    public OrderPersistenceException(String message, Throwable cause) {
        super(message, "order_persistence_failed", 500, null);
        initCause(cause);
    }
}