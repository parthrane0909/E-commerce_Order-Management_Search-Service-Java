package com.meridian.backend.products.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public class VariantDTO {
    @Size(min = 1, max = 64)
    private String sku;
    @Size(max = 64)
    private String color;
    @Min(0)
    @Max(100000)
    private Integer stock = 0;

    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public Integer getStock() { return stock; }
    public void setStock(Integer stock) { this.stock = stock; }

    public static VariantDTOBuilder builder() {
        return new VariantDTOBuilder();
    }

    public static class VariantDTOBuilder {
        private String sku;
        private String color;
        private Integer stock;

        public VariantDTOBuilder sku(String sku) { this.sku = sku; return this; }
        public VariantDTOBuilder color(String color) { this.color = color; return this; }
        public VariantDTOBuilder stock(Integer stock) { this.stock = stock; return this; }
        public VariantDTO build() {
            VariantDTO dto = new VariantDTO();
            dto.sku = sku;
            dto.color = color;
            dto.stock = stock;
            return dto;
        }
    }
}