package com.meridian.backend.orders.dto;

public class OrderItemResponse {
    private Integer id;
    private String productId;
    private String title;
    private Integer quantity;
    private Double unitPrice;
    private Double lineTotal;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public Double getUnitPrice() { return unitPrice; }
    public void setUnitPrice(Double unitPrice) { this.unitPrice = unitPrice; }
    public Double getLineTotal() { return lineTotal; }
    public void setLineTotal(Double lineTotal) { this.lineTotal = lineTotal; }

    public static OrderItemResponseBuilder builder() {
        return new OrderItemResponseBuilder();
    }

    public static class OrderItemResponseBuilder {
        private Integer id;
        private String productId;
        private String title;
        private Integer quantity;
        private Double unitPrice;
        private Double lineTotal;

        public OrderItemResponseBuilder id(Integer id) { this.id = id; return this; }
        public OrderItemResponseBuilder productId(String productId) { this.productId = productId; return this; }
        public OrderItemResponseBuilder title(String title) { this.title = title; return this; }
        public OrderItemResponseBuilder quantity(Integer quantity) { this.quantity = quantity; return this; }
        public OrderItemResponseBuilder unitPrice(Double unitPrice) { this.unitPrice = unitPrice; return this; }
        public OrderItemResponseBuilder lineTotal(Double lineTotal) { this.lineTotal = lineTotal; return this; }
        public OrderItemResponse build() {
            OrderItemResponse response = new OrderItemResponse();
            response.id = id;
            response.productId = productId;
            response.title = title;
            response.quantity = quantity;
            response.unitPrice = unitPrice;
            response.lineTotal = lineTotal;
            return response;
        }
    }
}