package com.meridian.backend.auth.dto;

public class UserResponse {
    private Integer id;
    private String name;
    private String email;
    private String role;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public static UserResponseBuilder builder() {
        return new UserResponseBuilder();
    }

    public static class UserResponseBuilder {
        private Integer id;
        private String name;
        private String email;
        private String role;

        public UserResponseBuilder id(Integer id) { this.id = id; return this; }
        public UserResponseBuilder name(String name) { this.name = name; return this; }
        public UserResponseBuilder email(String email) { this.email = email; return this; }
        public UserResponseBuilder role(String role) { this.role = role; return this; }
        public UserResponse build() {
            UserResponse response = new UserResponse();
            response.id = id;
            response.name = name;
            response.email = email;
            response.role = role;
            return response;
        }
    }
}