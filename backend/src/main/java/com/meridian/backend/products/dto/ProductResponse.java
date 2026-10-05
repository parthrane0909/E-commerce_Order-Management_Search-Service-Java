package com.meridian.backend.products.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ProductResponse {
    @JsonProperty("id")
    private String id;
    private String sku;
    private String title;
    private String description = "";
    private Double price;
    private String category;
    private List<String> tags = new ArrayList<>();
    private Map<String, Object> attributes = new HashMap<>();
    private List<VariantDTO> variants = new ArrayList<>();
    private Boolean active = true;
    private String imageUrl;
    private OffsetDateTime updatedAt;
    private OffsetDateTime createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
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
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public static ProductResponseBuilder builder() {
        return new ProductResponseBuilder();
    }

    public static class ProductResponseBuilder {
        private String id;
        private String sku;
        private String title;
        private String description = "";
        private Double price;
        private String category;
        private List<String> tags = new ArrayList<>();
        private Map<String, Object> attributes = new HashMap<>();
        private List<VariantDTO> variants = new ArrayList<>();
        private Boolean active = true;
        private String imageUrl;
        private OffsetDateTime updatedAt;
        private OffsetDateTime createdAt;

        public ProductResponseBuilder id(String id) { this.id = id; return this; }
        public ProductResponseBuilder sku(String sku) { this.sku = sku; return this; }
        public ProductResponseBuilder title(String title) { this.title = title; return this; }
        public ProductResponseBuilder description(String description) { this.description = description; return this; }
        public ProductResponseBuilder price(Double price) { this.price = price; return this; }
        public ProductResponseBuilder category(String category) { this.category = category; return this; }
        public ProductResponseBuilder tags(List<String> tags) { this.tags = tags; return this; }
        public ProductResponseBuilder attributes(Map<String, Object> attributes) { this.attributes = attributes; return this; }
        public ProductResponseBuilder variants(List<VariantDTO> variants) { this.variants = variants; return this; }
        public ProductResponseBuilder active(Boolean active) { this.active = active; return this; }
        public ProductResponseBuilder imageUrl(String imageUrl) { this.imageUrl = imageUrl; return this; }
        public ProductResponseBuilder updatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; return this; }
        public ProductResponseBuilder createdAt(OffsetDateTime createdAt) { this.createdAt = createdAt; return this; }
        public ProductResponse build() {
            ProductResponse response = new ProductResponse();
            response.id = id;
            response.sku = sku;
            response.title = title;
            response.description = description;
            response.price = price;
            response.category = category;
            response.tags = tags;
            response.attributes = attributes;
            response.variants = variants;
            response.active = active;
            response.imageUrl = imageUrl;
            response.updatedAt = updatedAt;
            response.createdAt = createdAt;
            return response;
        }
    }
}