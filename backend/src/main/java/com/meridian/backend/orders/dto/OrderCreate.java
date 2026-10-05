package com.meridian.backend.orders.dto;

import jakarta.validation.constraints.*;
import java.util.List;

public class OrderCreate {
    @Min(1)
    private Integer userId;

    @NotNull
    @Size(min = 1, max = 50)
    private List<OrderItemCreate> items;

    // Getters and Setters
    public Integer getUserId() { return userId; }
    public void setUserId(Integer userId) { this.userId = userId; }

    public List<OrderItemCreate> getItems() { return items; }
    public void setItems(List<OrderItemCreate> items) { this.items = items; }
}