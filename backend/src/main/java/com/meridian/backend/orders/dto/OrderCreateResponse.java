package com.meridian.backend.orders.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.List;

public class OrderCreateResponse {
    private Integer id;
    @JsonProperty("order_number")
    private String orderNumber;
    @JsonProperty("user_id")
    private Integer userId;
    private OrderResponse.UserResponse customer;
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
    private SyncInfo sync;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getOrderNumber() { return orderNumber; }
    public void setOrderNumber(String orderNumber) { this.orderNumber = orderNumber; }
    public Integer getUserId() { return userId; }
    public void setUserId(Integer userId) { this.userId = userId; }
    public OrderResponse.UserResponse getCustomer() { return customer; }
    public void setCustomer(OrderResponse.UserResponse customer) { this.customer = customer; }
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
    public SyncInfo getSync() { return sync; }
    public void setSync(SyncInfo sync) { this.sync = sync; }

    public static OrderCreateResponseBuilder builder() {
        return new OrderCreateResponseBuilder();
    }

    public static class OrderCreateResponseBuilder {
        private Integer id;
        private String orderNumber;
        private Integer userId;
        private OrderResponse.UserResponse customer;
        private OffsetDateTime orderDate;
        private String status;
        private Double totalAmount;
        private OffsetDateTime updatedAt;
        private OffsetDateTime searchIndexedAt;
        private List<OrderItemResponse> items;
        private SyncInfo sync;

        public OrderCreateResponseBuilder id(Integer id) { this.id = id; return this; }
        public OrderCreateResponseBuilder orderNumber(String orderNumber) { this.orderNumber = orderNumber; return this; }
        public OrderCreateResponseBuilder userId(Integer userId) { this.userId = userId; return this; }
        public OrderCreateResponseBuilder customer(OrderResponse.UserResponse customer) { this.customer = customer; return this; }
        public OrderCreateResponseBuilder orderDate(OffsetDateTime orderDate) { this.orderDate = orderDate; return this; }
        public OrderCreateResponseBuilder status(String status) { this.status = status; return this; }
        public OrderCreateResponseBuilder totalAmount(Double totalAmount) { this.totalAmount = totalAmount; return this; }
        public OrderCreateResponseBuilder updatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; return this; }
        public OrderCreateResponseBuilder searchIndexedAt(OffsetDateTime searchIndexedAt) { this.searchIndexedAt = searchIndexedAt; return this; }
        public OrderCreateResponseBuilder items(List<OrderItemResponse> items) { this.items = items; return this; }
        public OrderCreateResponseBuilder sync(SyncInfo sync) { this.sync = sync; return this; }
        public OrderCreateResponse build() {
            OrderCreateResponse response = new OrderCreateResponse();
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
            response.sync = sync;
            return response;
        }
    }
}