package com.meridian.backend.search.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;

public class SearchRequest {
    @Size(max = 200)
    private String query;

    private List<String> status;

    private LocalDate dateFrom;
    private LocalDate dateTo;

    @Min(0)
    private Double minPrice;

    @Min(0)
    private Double maxPrice;

    @Min(1)
    @Max(10000)
    private int page = 1;

    @Min(1)
    @Max(100)
    private int limit = 20;

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public List<String> getStatus() { return status; }
    public void setStatus(List<String> status) { this.status = status; }
    public LocalDate getDateFrom() { return dateFrom; }
    public void setDateFrom(LocalDate dateFrom) { this.dateFrom = dateFrom; }
    public LocalDate getDateTo() { return dateTo; }
    public void setDateTo(LocalDate dateTo) { this.dateTo = dateTo; }
    public Double getMinPrice() { return minPrice; }
    public void setMinPrice(Double minPrice) { this.minPrice = minPrice; }
    public Double getMaxPrice() { return maxPrice; }
    public void setMaxPrice(Double maxPrice) { this.maxPrice = maxPrice; }
    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }
    public int getLimit() { return limit; }
    public void setLimit(int limit) { this.limit = limit; }
}