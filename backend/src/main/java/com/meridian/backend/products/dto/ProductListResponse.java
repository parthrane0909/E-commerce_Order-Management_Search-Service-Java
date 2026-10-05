package com.meridian.backend.products.dto;

import java.util.List;

public class ProductListResponse {
    private List<ProductResponse> items;
    private int page;
    private int limit;
    private int total;
    private int pages;

    public List<ProductResponse> getItems() { return items; }
    public void setItems(List<ProductResponse> items) { this.items = items; }
    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }
    public int getLimit() { return limit; }
    public void setLimit(int limit) { this.limit = limit; }
    public int getTotal() { return total; }
    public void setTotal(int total) { this.total = total; }
    public int getPages() { return pages; }
    public void setPages(int pages) { this.pages = pages; }

    public static ProductListResponseBuilder builder() {
        return new ProductListResponseBuilder();
    }

    public static class ProductListResponseBuilder {
        private List<ProductResponse> items;
        private int page;
        private int limit;
        private int total;
        private int pages;

        public ProductListResponseBuilder items(List<ProductResponse> items) { this.items = items; return this; }
        public ProductListResponseBuilder page(int page) { this.page = page; return this; }
        public ProductListResponseBuilder limit(int limit) { this.limit = limit; return this; }
        public ProductListResponseBuilder total(int total) { this.total = total; return this; }
        public ProductListResponseBuilder pages(int pages) { this.pages = pages; return this; }
        public ProductListResponse build() {
            ProductListResponse response = new ProductListResponse();
            response.items = items;
            response.page = page;
            response.limit = limit;
            response.total = total;
            response.pages = pages;
            return response;
        }
    }
}