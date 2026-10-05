package com.meridian.backend.auth.dto;

public class LoginResponse {
    private String accessToken;
    private String tokenType = "bearer";
    private UserResponse user;

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
    public String getTokenType() { return tokenType; }
    public void setTokenType(String tokenType) { this.tokenType = tokenType; }
    public UserResponse getUser() { return user; }
    public void setUser(UserResponse user) { this.user = user; }

    public static LoginResponseBuilder builder() {
        return new LoginResponseBuilder();
    }

    public static class LoginResponseBuilder {
        private String accessToken;
        private String tokenType = "bearer";
        private UserResponse user;

        public LoginResponseBuilder accessToken(String accessToken) { this.accessToken = accessToken; return this; }
        public LoginResponseBuilder tokenType(String tokenType) { this.tokenType = tokenType; return this; }
        public LoginResponseBuilder user(UserResponse user) { this.user = user; return this; }
        public LoginResponse build() {
            LoginResponse response = new LoginResponse();
            response.accessToken = accessToken;
            response.tokenType = tokenType;
            response.user = user;
            return response;
        }
    }
}