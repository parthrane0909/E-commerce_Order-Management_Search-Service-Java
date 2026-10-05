package com.meridian.backend.common.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PaginationMeta {
    private int page;
    private int limit;
    private int total;
    private int pages;

    public static PaginationMetaBuilder builder() {
        return new PaginationMetaBuilder();
    }

    public static class PaginationMetaBuilder {
        private int page;
        private int limit;
        private int total;
        private int pages;

        public PaginationMetaBuilder page(int page) { this.page = page; return this; }
        public PaginationMetaBuilder limit(int limit) { this.limit = limit; return this; }
        public PaginationMetaBuilder total(int total) { this.total = total; return this; }
        public PaginationMetaBuilder pages(int pages) { this.pages = pages; return this; }
        public PaginationMeta build() {
            PaginationMeta meta = new PaginationMeta();
            meta.page = page;
            meta.limit = limit;
            meta.total = total;
            meta.pages = pages;
            return meta;
        }
    }
}