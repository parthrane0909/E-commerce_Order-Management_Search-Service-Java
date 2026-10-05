package com.meridian.backend.products.dto;

import java.util.List;

public class ProductListResponse {
    private List<ProductResponse> items;
    private int page;
    private int limit;
    private int total;
    private int pages;
    /** Opaque continuation token for the next batch; {@code null} when there is no next batch. */
    private String nextCursor;
    /** True while another batch exists — the storefront stops requesting when this is false. */
    private boolean hasMore;

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
    public String getNextCursor() { return nextCursor; }
    public void setNextCursor(String nextCursor) { this.nextCursor = nextCursor; }
    public boolean isHasMore() { return hasMore; }
    public void setHasMore(boolean hasMore) { this.hasMore = hasMore; }

    public static ProductListResponseBuilder builder() {
        return new ProductListResponseBuilder();
    }

    /** Kept as a fluent builder so the call site reads the same as before the cursor fields. */
    public static class ProductListResponseBuilder {
        private final ProductListResponse response = new ProductListResponse();

        public ProductListResponseBuilder items(List<ProductResponse> items) { response.items = items; return this; }
        public ProductListResponseBuilder page(int page) { response.page = page; return this; }
        public ProductListResponseBuilder limit(int limit) { response.limit = limit; return this; }
        public ProductListResponseBuilder total(int total) { response.total = total; return this; }
        public ProductListResponseBuilder pages(int pages) { response.pages = pages; return this; }
        public ProductListResponseBuilder nextCursor(String nextCursor) { response.nextCursor = nextCursor; return this; }
        public ProductListResponseBuilder hasMore(boolean hasMore) { response.hasMore = hasMore; return this; }
        public ProductListResponse build() { return response; }
    }
}
