package com.meridian.backend.products.dto;

import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;

public class ProductUpdate {
    @Size(min = 2, max = 64)
    @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9\\-_]*$")
    private String sku;

    @Size(min = 2, max = 200)
    private String title;

    @Size(max = 4000)
    private String description;

    @DecimalMin(value = "0.01", inclusive = true)
    @DecimalMax(value = "1000000.00", inclusive = true)
    private Double price;

    @Size(min = 2, max = 64)
    private String category;

    private List<String> tags;

    private Map<String, Object> attributes;

    private List<VariantDTO> variants;

    private Boolean active;

    private String imageUrl;

    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Double getPrice() { return price; }
    public void setPrice(Double price) { this.price = price; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
    public Map<String, Object> getAttributes() { return attributes; }
    public void setAttributes(Map<String, Object> attributes) { this.attributes = attributes; }
    public List<VariantDTO> getVariants() { return variants; }
    public void setVariants(List<VariantDTO> variants) { this.variants = variants; }
    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
}