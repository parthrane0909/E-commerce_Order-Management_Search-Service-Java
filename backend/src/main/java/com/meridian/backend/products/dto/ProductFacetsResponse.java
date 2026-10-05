package com.meridian.backend.products.dto;

import java.util.ArrayList;
import java.util.List;

public class ProductFacetsResponse {
    private List<FacetBucket> categories = new ArrayList<>();
    private List<FacetBucket> tags = new ArrayList<>();

    public List<FacetBucket> getCategories() { return categories; }
    public void setCategories(List<FacetBucket> categories) { this.categories = categories; }
    public List<FacetBucket> getTags() { return tags; }
    public void setTags(List<FacetBucket> tags) { this.tags = tags; }

    public static ProductFacetsResponseBuilder builder() {
        return new ProductFacetsResponseBuilder();
    }

    public static class ProductFacetsResponseBuilder {
        private List<FacetBucket> categories = new ArrayList<>();
        private List<FacetBucket> tags = new ArrayList<>();

        public ProductFacetsResponseBuilder categories(List<FacetBucket> categories) { this.categories = categories; return this; }
        public ProductFacetsResponseBuilder tags(List<FacetBucket> tags) { this.tags = tags; return this; }
        public ProductFacetsResponse build() {
            ProductFacetsResponse response = new ProductFacetsResponse();
            response.categories = categories;
            response.tags = tags;
            return response;
        }
    }

    public static class FacetBucket {
        private String value;
        private int count;

        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
        public int getCount() { return count; }
        public void setCount(int count) { this.count = count; }

        public static FacetBucketBuilder builder() {
            return new FacetBucketBuilder();
        }

        public static class FacetBucketBuilder {
            private String value;
            private int count;

            public FacetBucketBuilder value(String value) { this.value = value; return this; }
            public FacetBucketBuilder count(int count) { this.count = count; return this; }
            public FacetBucket build() {
                FacetBucket bucket = new FacetBucket();
                bucket.value = value;
                bucket.count = count;
                return bucket;
            }
        }
    }
}