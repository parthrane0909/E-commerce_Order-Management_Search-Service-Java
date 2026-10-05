package com.meridian.backend.common.dto;

public class ErrorResponse {
    private ErrorBody error;

    public ErrorResponse() {}

    public ErrorResponse(ErrorBody error) {
        this.error = error;
    }

    public ErrorBody getError() { return error; }
    public void setError(ErrorBody error) { this.error = error; }

    public static ErrorResponseBuilder builder() {
        return new ErrorResponseBuilder();
    }

    public static class ErrorResponseBuilder {
        private ErrorBody error;

        public ErrorResponseBuilder error(ErrorBody error) { this.error = error; return this; }
        public ErrorResponse build() {
            ErrorResponse response = new ErrorResponse();
            response.error = error;
            return response;
        }
    }
}