package com.meridian.backend.products.document;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Document(collection = "products")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProductDocument {

    @Id
    private String id;

    @Field("sku")
    @Indexed(unique = true, name = "uniq_products_sku")
    private String sku;

    @Field("title")
    private String title;

    @Field("description")
    private String description;

    @Field("price")
    private Double price;

    @Field("category")
    @Indexed(name = "ix_products_category")
    private String category;

    @Field("tags")
    private List<String> tags = new ArrayList<>();

    @Field("attributes")
    private Map<String, Object> attributes = new HashMap<>();

    @Field("variants")
    private List<Variant> variants = new ArrayList<>();

    @Field("active")
    @Indexed(name = "ix_products_active")
    private Boolean active = true;

    @Field("image_url")
    private String imageUrl;

    @Field("created_at")
    private OffsetDateTime createdAt;

    @Field("updated_at")
    private OffsetDateTime updatedAt;

    // Explicit getters and setters to ensure Lombok generates them correctly
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
    public List<Variant> getVariants() { return variants; }
    public void setVariants(List<Variant> variants) { this.variants = variants; }
    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    public static ProductDocumentBuilder builder() {
        return new ProductDocumentBuilder();
    }

    public static class ProductDocumentBuilder {
        private String id;
        private String sku;
        private String title;
        private String description;
        private Double price;
        private String category;
        private List<String> tags = new ArrayList<>();
        private Map<String, Object> attributes = new HashMap<>();
        private List<Variant> variants = new ArrayList<>();
        private Boolean active = true;
        private String imageUrl;
        private OffsetDateTime createdAt;
        private OffsetDateTime updatedAt;

        public ProductDocumentBuilder id(String id) { this.id = id; return this; }
        public ProductDocumentBuilder sku(String sku) { this.sku = sku; return this; }
        public ProductDocumentBuilder title(String title) { this.title = title; return this; }
        public ProductDocumentBuilder description(String description) { this.description = description; return this; }
        public ProductDocumentBuilder price(Double price) { this.price = price; return this; }
        public ProductDocumentBuilder category(String category) { this.category = category; return this; }
        public ProductDocumentBuilder tags(List<String> tags) { this.tags = tags; return this; }
        public ProductDocumentBuilder attributes(Map<String, Object> attributes) { this.attributes = attributes; return this; }
        public ProductDocumentBuilder variants(List<Variant> variants) { this.variants = variants; return this; }
        public ProductDocumentBuilder active(Boolean active) { this.active = active; return this; }
        public ProductDocumentBuilder imageUrl(String imageUrl) { this.imageUrl = imageUrl; return this; }
        public ProductDocumentBuilder createdAt(OffsetDateTime createdAt) { this.createdAt = createdAt; return this; }
        public ProductDocumentBuilder updatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; return this; }
        public ProductDocument build() {
            ProductDocument doc = new ProductDocument();
            doc.id = id;
            doc.sku = sku;
            doc.title = title;
            doc.description = description;
            doc.price = price;
            doc.category = category;
            doc.tags = tags;
            doc.attributes = attributes;
            doc.variants = variants;
            doc.active = active;
            doc.imageUrl = imageUrl;
            doc.createdAt = createdAt;
            doc.updatedAt = updatedAt;
            return doc;
        }
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Variant {
        private String sku;
        private String color;
        private Integer stock;

        public String getSku() { return sku; }
        public void setSku(String sku) { this.sku = sku; }
        public String getColor() { return color; }
        public void setColor(String color) { this.color = color; }
        public Integer getStock() { return stock; }
        public void setStock(Integer stock) { this.stock = stock; }

        public static VariantBuilder builder() {
            return new VariantBuilder();
        }

        public static class VariantBuilder {
            private String sku;
            private String color;
            private Integer stock;

            public VariantBuilder sku(String sku) { this.sku = sku; return this; }
            public VariantBuilder color(String color) { this.color = color; return this; }
            public VariantBuilder stock(Integer stock) { this.stock = stock; return this; }
            public Variant build() {
                Variant variant = new Variant();
                variant.sku = sku;
                variant.color = color;
                variant.stock = stock;
                return variant;
            }
        }
    }
}