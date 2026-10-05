package com.meridian.backend.orders.dto;

public class StatusUpdateRequest {
    @jakarta.validation.constraints.NotNull
    private String status;

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}