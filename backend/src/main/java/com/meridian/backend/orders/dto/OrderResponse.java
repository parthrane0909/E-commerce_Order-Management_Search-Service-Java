package com.meridian.backend.orders.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.List;

public class OrderResponse {
    private Integer id;
    @JsonProperty("order_number")
    private String orderNumber;
    @JsonProperty("user_id")
    private Integer userId;
    private UserResponse customer;
    @JsonProperty("order_date")
    private OffsetDateTime orderDate;
    private String status;
    @JsonProperty("total_amount")
    private Double totalAmount;
    @JsonProperty("updated_at")
    private OffsetDateTime updatedAt;
    @JsonProperty("search_indexed_at")
    private OffsetDateTime searchIndexedAt;
    private List<OrderItemResponse> items;

    // Static inner class for UserResponse
    public static class UserResponse {
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

    // Getters and Setters
    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getOrderNumber() { return orderNumber; }
    public void setOrderNumber(String orderNumber) { this.orderNumber = orderNumber; }
    public Integer getUserId() { return userId; }
    public void setUserId(Integer userId) { this.userId = userId; }
    public UserResponse getCustomer() { return customer; }
    public void setCustomer(UserResponse customer) { this.customer = customer; }
    public OffsetDateTime getOrderDate() { return orderDate; }
    public void setOrderDate(OffsetDateTime orderDate) { this.orderDate = orderDate; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Double getTotalAmount() { return totalAmount; }
    public void setTotalAmount(Double totalAmount) { this.totalAmount = totalAmount; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public OffsetDateTime getSearchIndexedAt() { return searchIndexedAt; }
    public void setSearchIndexedAt(OffsetDateTime searchIndexedAt) { this.searchIndexedAt = searchIndexedAt; }
    public List<OrderItemResponse> getItems() { return items; }
    public void setItems(List<OrderItemResponse> items) { this.items = items; }

    // Builder
    public static OrderResponseBuilder builder() {
        return new OrderResponseBuilder();
    }

    public static class OrderResponseBuilder {
        private Integer id;
        private String orderNumber;
        private Integer userId;
        private UserResponse customer;
        private OffsetDateTime orderDate;
        private String status;
        private Double totalAmount;
        private OffsetDateTime updatedAt;
        private OffsetDateTime searchIndexedAt;
        private List<OrderItemResponse> items;

        public OrderResponseBuilder id(Integer id) { this.id = id; return this; }
        public OrderResponseBuilder orderNumber(String orderNumber) { this.orderNumber = orderNumber; return this; }
        public OrderResponseBuilder userId(Integer userId) { this.userId = userId; return this; }
        public OrderResponseBuilder customer(UserResponse customer) { this.customer = customer; return this; }
        public OrderResponseBuilder orderDate(OffsetDateTime orderDate) { this.orderDate = orderDate; return this; }
        public OrderResponseBuilder status(String status) { this.status = status; return this; }
        public OrderResponseBuilder totalAmount(Double totalAmount) { this.totalAmount = totalAmount; return this; }
        public OrderResponseBuilder updatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; return this; }
        public OrderResponseBuilder searchIndexedAt(OffsetDateTime searchIndexedAt) { this.searchIndexedAt = searchIndexedAt; return this; }
        public OrderResponseBuilder items(List<OrderItemResponse> items) { this.items = items; return this; }
        public OrderResponse build() {
            OrderResponse response = new OrderResponse();
            response.id = id;
            response.orderNumber = orderNumber;
            response.userId = userId;
            response.customer = customer;
            response.orderDate = orderDate;
            response.status = status;
            response.totalAmount = totalAmount;
            response.updatedAt = updatedAt;
            response.searchIndexedAt = searchIndexedAt;
            response.items = items;
            return response;
        }
    }
}