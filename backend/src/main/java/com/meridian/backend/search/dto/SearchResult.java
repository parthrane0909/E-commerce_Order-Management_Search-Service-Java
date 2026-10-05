package com.meridian.backend.search.dto;

import lombok.*;

import java.time.OffsetDateTime;
import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class SearchResult {
    private int orderId;
    private String orderNumber;
    private SearchCustomer customer;
    private String status;
    private OffsetDateTime orderDate;
    private OffsetDateTime updatedAt;
    private double totalAmount;
    private List<SearchItem> items;

    public void setOrderId(int orderId) { this.orderId = orderId; }
    public void setOrderNumber(String orderNumber) { this.orderNumber = orderNumber; }
    public void setCustomer(SearchCustomer customer) { this.customer = customer; }
    public void setStatus(String status) { this.status = status; }
    public void setOrderDate(OffsetDateTime orderDate) { this.orderDate = orderDate; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public void setTotalAmount(double totalAmount) { this.totalAmount = totalAmount; }
    public void setItems(List<SearchItem> items) { this.items = items; }

    public static SearchResultBuilder builder() {
        return new SearchResultBuilder();
    }

    public static class SearchResultBuilder {
        private int orderId;
        private String orderNumber;
        private SearchCustomer customer;
        private String status;
        private OffsetDateTime orderDate;
        private OffsetDateTime updatedAt;
        private double totalAmount;
        private List<SearchItem> items;

        public SearchResultBuilder orderId(int orderId) { this.orderId = orderId; return this; }
        public SearchResultBuilder orderNumber(String orderNumber) { this.orderNumber = orderNumber; return this; }
        public SearchResultBuilder customer(SearchCustomer customer) { this.customer = customer; return this; }
        public SearchResultBuilder status(String status) { this.status = status; return this; }
        public SearchResultBuilder orderDate(OffsetDateTime orderDate) { this.orderDate = orderDate; return this; }
        public SearchResultBuilder updatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; return this; }
        public SearchResultBuilder totalAmount(double totalAmount) { this.totalAmount = totalAmount; return this; }
        public SearchResultBuilder items(List<SearchItem> items) { this.items = items; return this; }
        public SearchResult build() {
            SearchResult result = new SearchResult();
            result.orderId = orderId;
            result.orderNumber = orderNumber;
            result.customer = customer;
            result.status = status;
            result.orderDate = orderDate;
            result.updatedAt = updatedAt;
            result.totalAmount = totalAmount;
            result.items = items;
            return result;
        }
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchCustomer {
        private int id;
        private String name;
        private String email;

        public void setId(int id) { this.id = id; }
        public void setName(String name) { this.name = name; }
        public void setEmail(String email) { this.email = email; }

        public static SearchCustomerBuilder builder() {
            return new SearchCustomerBuilder();
        }

        public static class SearchCustomerBuilder {
            private int id;
            private String name;
            private String email;

            public SearchCustomerBuilder id(int id) { this.id = id; return this; }
            public SearchCustomerBuilder name(String name) { this.name = name; return this; }
            public SearchCustomerBuilder email(String email) { this.email = email; return this; }
            public SearchCustomer build() {
                SearchCustomer customer = new SearchCustomer();
                customer.id = id;
                customer.name = name;
                customer.email = email;
                return customer;
            }
        }
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchItem {
        private String productId;
        private String title;
        private int quantity;
        private double unitPrice;
        private double lineTotal;

        public void setProductId(String productId) { this.productId = productId; }
        public void setTitle(String title) { this.title = title; }
        public void setQuantity(int quantity) { this.quantity = quantity; }
        public void setUnitPrice(double unitPrice) { this.unitPrice = unitPrice; }
        public void setLineTotal(double lineTotal) { this.lineTotal = lineTotal; }

        public static SearchItemBuilder builder() {
            return new SearchItemBuilder();
        }

        public static class SearchItemBuilder {
            private String productId;
            private String title;
            private int quantity;
            private double unitPrice;
            private double lineTotal;

            public SearchItemBuilder productId(String productId) { this.productId = productId; return this; }
            public SearchItemBuilder title(String title) { this.title = title; return this; }
            public SearchItemBuilder quantity(int quantity) { this.quantity = quantity; return this; }
            public SearchItemBuilder unitPrice(double unitPrice) { this.unitPrice = unitPrice; return this; }
            public SearchItemBuilder lineTotal(double lineTotal) { this.lineTotal = lineTotal; return this; }
            public SearchItem build() {
                SearchItem item = new SearchItem();
                item.productId = productId;
                item.title = title;
                item.quantity = quantity;
                item.unitPrice = unitPrice;
                item.lineTotal = lineTotal;
                return item;
            }
        }
    }
}