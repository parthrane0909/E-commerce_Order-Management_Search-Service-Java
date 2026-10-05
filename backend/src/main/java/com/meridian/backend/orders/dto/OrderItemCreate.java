package com.meridian.backend.orders.dto;

import jakarta.validation.constraints.*;

public class OrderItemCreate {
    @NotBlank
    @Size(min = 1, max = 64)
    private String productId;

    @NotNull
    @Min(1)
    @Max(100)
    private Integer quantity;

    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
}