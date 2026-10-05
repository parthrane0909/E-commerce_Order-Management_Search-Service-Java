package com.meridian.backend.common.dto;

import java.util.Map;

public class SearchAggregations {
    private Double revenue;
    private Map<String, Integer> statusCounts;

    public Double getRevenue() { return revenue; }
    public void setRevenue(Double revenue) { this.revenue = revenue; }
    public Map<String, Integer> getStatusCounts() { return statusCounts; }
    public void setStatusCounts(Map<String, Integer> statusCounts) { this.statusCounts = statusCounts; }
}