package com.meridian.backend.common.dto;

import java.util.List;

public class ErrorBody {
    private String code;
    private String message;
    private Object details;

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Object getDetails() { return details; }
    public void setDetails(Object details) { this.details = details; }

    public static ErrorBodyBuilder builder() {
        return new ErrorBodyBuilder();
    }

    public static class ErrorBodyBuilder {
        private String code;
        private String message;
        private Object details;

        public ErrorBodyBuilder code(String code) { this.code = code; return this; }
        public ErrorBodyBuilder message(String message) { this.message = message; return this; }
        public ErrorBodyBuilder details(Object details) { this.details = details; return this; }
        public ErrorBody build() {
            ErrorBody body = new ErrorBody();
            body.code = code;
            body.message = message;
            body.details = details;
            return body;
        }
    }

    public static class FieldError {
        private String field;
        private String message;
        private String type;

        public String getField() { return field; }
        public void setField(String field) { this.field = field; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public static FieldErrorBuilder builder() {
            return new FieldErrorBuilder();
        }

        public static class FieldErrorBuilder {
            private String field;
            private String message;
            private String type;

            public FieldErrorBuilder field(String field) { this.field = field; return this; }
            public FieldErrorBuilder message(String message) { this.message = message; return this; }
            public FieldErrorBuilder type(String type) { this.type = type; return this; }
            public FieldError build() {
                FieldError error = new FieldError();
                error.field = field;
                error.message = message;
                error.type = type;
                return error;
            }
        }
    }
}