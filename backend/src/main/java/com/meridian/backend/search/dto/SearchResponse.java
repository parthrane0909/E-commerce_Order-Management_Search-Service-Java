package com.meridian.backend.search.dto;

import com.meridian.backend.common.dto.SearchAggregations;
import lombok.*;

import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class SearchResponse {
    private List<SearchResult> results;
    private int total;
    private int page;
    private int limit;
    private int pages;
    private int tookMs;
    private SearchAggregations aggregations;

    public void setResults(List<SearchResult> results) { this.results = results; }
    public void setTotal(int total) { this.total = total; }
    public void setPage(int page) { this.page = page; }
    public void setLimit(int limit) { this.limit = limit; }
    public void setPages(int pages) { this.pages = pages; }
    public void setTookMs(int tookMs) { this.tookMs = tookMs; }
    public void setAggregations(SearchAggregations aggregations) { this.aggregations = aggregations; }

    public static SearchResponseBuilder builder() {
        return new SearchResponseBuilder();
    }

    public static class SearchResponseBuilder {
        private List<SearchResult> results;
        private int total;
        private int page;
        private int limit;
        private int pages;
        private int tookMs;
        private SearchAggregations aggregations;

        public SearchResponseBuilder results(List<SearchResult> results) { this.results = results; return this; }
        public SearchResponseBuilder total(int total) { this.total = total; return this; }
        public SearchResponseBuilder page(int page) { this.page = page; return this; }
        public SearchResponseBuilder limit(int limit) { this.limit = limit; return this; }
        public SearchResponseBuilder pages(int pages) { this.pages = pages; return this; }
        public SearchResponseBuilder tookMs(int tookMs) { this.tookMs = tookMs; return this; }
        public SearchResponseBuilder aggregations(SearchAggregations aggregations) { this.aggregations = aggregations; return this; }
        public SearchResponse build() {
            SearchResponse response = new SearchResponse();
            response.results = results;
            response.total = total;
            response.page = page;
            response.limit = limit;
            response.pages = pages;
            response.tookMs = tookMs;
            response.aggregations = aggregations;
            return response;
        }
    }
}