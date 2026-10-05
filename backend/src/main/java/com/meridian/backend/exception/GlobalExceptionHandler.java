package com.meridian.backend.exception;

import com.meridian.backend.common.dto.ErrorBody;
import com.meridian.backend.common.dto.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ErrorResponse> handleAppException(AppException ex) {
        if (ex.getStatusCode() >= 500) {
            logger.error("Application error [{}]: {}", ex.getCode(), ex.getMessage());
        } else {
            logger.info("Request rejected [{}]: {}", ex.getCode(), ex.getMessage());
        }

        ErrorBody errorBody = ErrorBody.builder()
            .code(ex.getCode())
            .message(ex.getMessage())
            .details(ex.getDetails())
            .build();

        return ResponseEntity.status(ex.getStatusCode()).body(new ErrorResponse(errorBody));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException ex) {
        List<ErrorBody.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
            .map(error -> ErrorBody.FieldError.builder()
                .field(error.getField())
                .message(error.getDefaultMessage())
                .type(error.getCode())
                .build())
            .collect(Collectors.toList());

        logger.info("Request body validation failed: {}", fieldErrors);

        ErrorBody errorBody = ErrorBody.builder()
            .code("validation_error")
            .message("Request validation failed.")
            .details(Map.of("fields", fieldErrors))
            .build();

        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(new ErrorResponse(errorBody));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        List<ErrorBody.FieldError> fieldErrors = ex.getConstraintViolations().stream()
            .map(violation -> ErrorBody.FieldError.builder()
                .field(violation.getPropertyPath().toString())
                .message(violation.getMessage())
                .type("constraint_violation")
                .build())
            .collect(Collectors.toList());

        ErrorBody errorBody = ErrorBody.builder()
            .code("validation_error")
            .message("Request validation failed.")
            .details(Map.of("fields", fieldErrors))
            .build();

        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(new ErrorResponse(errorBody));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        logger.info("Malformed request body: {}", ex.getMessage());
        
        ErrorBody errorBody = ErrorBody.builder()
            .code("validation_error")
            .message("Malformed request body.")
            .details(null)
            .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(errorBody));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        ErrorBody errorBody = ErrorBody.builder()
            .code("validation_error")
            .message("Invalid parameter type for '" + ex.getName() + "'.")
            .details(null)
            .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(errorBody));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnhandledException(Exception ex) {
        logger.error("Unhandled error: {}", ex.getMessage(), ex);

        ErrorBody errorBody = ErrorBody.builder()
            .code("internal_error")
            .message("An unexpected server error occurred. Check the API logs.")
            .details(null)
            .build();

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse(errorBody));
    }
}