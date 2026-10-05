package com.meridian.backend.auth.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LogoutResponse {
    private String message = "Logged out successfully";

    public static LogoutResponseBuilder builder() {
        return new LogoutResponseBuilder();
    }

    public static class LogoutResponseBuilder {
        private String message = "Logged out successfully";

        public LogoutResponseBuilder message(String message) { this.message = message; return this; }
        public LogoutResponse build() {
            LogoutResponse response = new LogoutResponse();
            response.message = message;
            return response;
        }
    }
}