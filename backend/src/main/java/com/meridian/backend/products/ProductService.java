package com.meridian.backend.products;

import com.meridian.backend.common.util.DateUtils;
import com.meridian.backend.exception.ConflictException;
import com.meridian.backend.exception.NotFoundException;
import com.meridian.backend.exception.UnprocessableException;
import com.meridian.backend.products.document.ProductDocument;
import com.meridian.backend.products.dto.*;
import com.meridian.backend.products.dto.ProductFacetsResponse.FacetBucket;
import com.meridian.backend.products.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final MongoTemplate mongoTemplate;

    private static final Set<String> VALID_CATEGORIES = Set.of("peripherals", "audio", "cables", "office");
    private static final Set<String> VALID_SORTS = Set.of("newest", "title_asc", "price_asc", "price_desc");

    public ProductListResponse listProducts(
            String category,
            List<String> tags,
            String q,
            String visibility,
            int page,
            int limit,
            String sort) {

        if (page < 1) page = 1;
        if (limit < 1) limit = 1;
        if (limit > 100) limit = 100;
        if (!VALID_SORTS.contains(sort)) sort = "newest";

        Criteria criteria = new Criteria();

        if ("active".equals(visibility)) {
            criteria.and("active").is(true);
        } else if ("inactive".equals(visibility)) {
            criteria.and("active").is(false);
        }

        if (category != null && !category.isEmpty()) {
            criteria.and("category").is(category.toLowerCase().trim());
        }

        if (tags != null && !tags.isEmpty()) {
            List<String> cleanTags = tags.stream()
                .map(t -> t.trim().toLowerCase())
                .filter(t -> !t.isEmpty())
                .distinct()
                .collect(Collectors.toList());
            if (!cleanTags.isEmpty()) {
                criteria.and("tags").all(cleanTags);
            }
        }

        if (q != null && !q.trim().isEmpty()) {
            String searchTerm = q.trim();
            Criteria textCriteria = new Criteria().orOperator(
                Criteria.where("title").regex(searchTerm, "i"),
                Criteria.where("description").regex(searchTerm, "i"),
                Criteria.where("sku").regex(searchTerm, "i"),
                Criteria.where("tags").regex(searchTerm, "i")
            );
            criteria.andOperator(textCriteria);
        }

        Sort.Direction direction = Sort.Direction.DESC;
        String sortField = "createdAt";
        switch (sort) {
            case "title_asc":
                direction = Sort.Direction.ASC;
                sortField = "title";
                break;
            case "price_asc":
                direction = Sort.Direction.ASC;
                sortField = "price";
                break;
            case "price_desc":
                direction = Sort.Direction.DESC;
                sortField = "price";
                break;
            default:
                direction = Sort.Direction.DESC;
                sortField = "createdAt";
        }

        Pageable pageable = PageRequest.of(page - 1, limit, Sort.by(direction, sortField).and(Sort.by(Sort.Direction.DESC, "_id")));
        Query query = Query.query(criteria).with(pageable);

        long total = mongoTemplate.count(query.limit(-1).skip(-1), ProductDocument.class);
        List<ProductDocument> documents = mongoTemplate.find(query, ProductDocument.class);

        List<ProductResponse> items = documents.stream()
            .map(this::toResponse)
            .collect(Collectors.toList());

        int pages = total == 0 ? 0 : (int) Math.ceil((double) total / limit);

        return ProductListResponse.builder()
            .items(items)
            .page(page)
            .limit(limit)
            .total((int) total)
            .pages(pages)
            .build();
    }

    public ProductFacetsResponse listFacets() {
        Aggregation aggregation = Aggregation.newAggregation(
            Aggregation.match(Criteria.where("active").is(true)),
            Aggregation.facet()
                .and(
                    Aggregation.group("category").count().as("count"),
                    Aggregation.sort(Sort.Direction.DESC, "count"),
                    Aggregation.sort(Sort.Direction.ASC, "_id")
                ).as("categories")
                .and(
                    Aggregation.unwind("tags"),
                    Aggregation.group("tags").count().as("count"),
                    Aggregation.sort(Sort.Direction.DESC, "count"),
                    Aggregation.sort(Sort.Direction.ASC, "_id"),
                    Aggregation.limit(40)
                ).as("tags")
        );

        AggregationResults<Map> results = mongoTemplate.aggregate(aggregation, "products", Map.class);
        Map result = results.getUniqueMappedResult();

        List<FacetBucket> categories = new ArrayList<>();
        List<FacetBucket> tags = new ArrayList<>();

        if (result != null) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> catList = (List<Map<String, Object>>) result.get("categories");
            if (catList != null) {
                categories = catList.stream()
                    .map(m -> FacetBucket.builder()
                        .value((String) m.get("_id"))
                        .count(((Number) m.get("count")).intValue())
                        .build())
                    .collect(Collectors.toList());
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tagList = (List<Map<String, Object>>) result.get("tags");
            if (tagList != null) {
                tags = tagList.stream()
                    .map(m -> FacetBucket.builder()
                        .value((String) m.get("_id"))
                        .count(((Number) m.get("count")).intValue())
                        .build())
                    .collect(Collectors.toList());
            }
        }

        return ProductFacetsResponse.builder()
            .categories(categories)
            .tags(tags)
            .build();
    }

    public ProductResponse getProduct(String id) {
        ProductDocument document = productRepository.findById(id)
            .orElseThrow(() -> new NotFoundException(
                "Product '" + id + "' was not found.",
                "product_not_found",
                Map.of("product_id", id)
            ));
        return toResponse(document);
    }

    public ProductResponse createProduct(ProductCreate payload) {
        if (productRepository.findBySku(payload.getSku()).isPresent()) {
            throw new ConflictException(
                "A product with SKU '" + payload.getSku() + "' already exists.",
                "duplicate_sku",
                Map.of("field", "sku")
            );
        }

        ProductDocument document = new ProductDocument();
        document.setSku(payload.getSku());
        document.setTitle(payload.getTitle());
        document.setDescription(payload.getDescription());
        document.setPrice(payload.getPrice());
        document.setCategory(payload.getCategory().toLowerCase().trim());
        document.setTags(normalizeTags(payload.getTags()));
        document.setAttributes(payload.getAttributes());
        document.setVariants(convertVariantsToDocument(payload.getVariants()));
        document.setActive(payload.getActive());
        document.setImageUrl(payload.getImageUrl());
        OffsetDateTime now = DateUtils.utcNow();
        document.setCreatedAt(now);
        document.setUpdatedAt(now);

        ProductDocument saved = productRepository.save(document);
        return toResponse(saved);
    }

    public ProductResponse updateProduct(String id, ProductUpdate payload) {
        ProductDocument current = productRepository.findById(id)
            .orElseThrow(() -> new NotFoundException(
                "Product '" + id + "' was not found.",
                "product_not_found",
                Map.of("product_id", id)
            ));

        Update update = new Update();

        if (payload.getSku() != null) {
            String newSku = payload.getSku();
            if (!newSku.equals(current.getSku())) {
                if (productRepository.findBySkuAndIdNot(newSku, id).isPresent()) {
                    throw new ConflictException(
                        "A product with SKU '" + newSku + "' already exists.",
                        "duplicate_sku",
                        Map.of("field", "sku")
                    );
                }
                update.set("sku", newSku);
            }
        }

        if (payload.getTitle() != null) update.set("title", payload.getTitle());
        if (payload.getDescription() != null) update.set("description", payload.getDescription());
        if (payload.getPrice() != null) {
            if (payload.getPrice() <= 0) {
                throw new UnprocessableException("Price must be greater than zero.", "invalid_price", Map.of("field", "price"));
            }
            update.set("price", payload.getPrice());
        }
        if (payload.getCategory() != null) update.set("category", payload.getCategory().toLowerCase().trim());
        if (payload.getTags() != null) update.set("tags", normalizeTags(payload.getTags()));
        if (payload.getAttributes() != null) update.set("attributes", payload.getAttributes());
        if (payload.getVariants() != null) update.set("variants", convertVariantsToDocument(payload.getVariants()));
        if (payload.getActive() != null) update.set("active", payload.getActive());
        if (payload.getImageUrl() != null) update.set("imageUrl", payload.getImageUrl());

        update.set("updatedAt", DateUtils.utcNow());

        Query query = Query.query(Criteria.where("_id").is(id));
        mongoTemplate.updateFirst(query, update, ProductDocument.class);

        return getProduct(id);
    }

    public ProductResponse deleteProduct(String id) {
        ProductDocument current = productRepository.findById(id)
            .orElseThrow(() -> new NotFoundException(
                "Product '" + id + "' was not found.",
                "product_not_found",
                Map.of("product_id", id)
            ));

        Update update = new Update().set("active", false).set("updatedAt", DateUtils.utcNow());
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(id)), update, ProductDocument.class);

        current.setActive(false);
        return toResponse(current);
    }

    public ProductResponse updateImageUrl(String id, String imageUrl) {
        ProductDocument current = productRepository.findById(id)
            .orElseThrow(() -> new NotFoundException(
                "Product '" + id + "' was not found.",
                "product_not_found",
                Map.of("product_id", id)
            ));

        Update update = new Update().set("imageUrl", imageUrl).set("updatedAt", DateUtils.utcNow());
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(id)), update, ProductDocument.class);

        return getProduct(id);
    }

    public void removeImageUrl(String id) {
        ProductDocument current = productRepository.findById(id)
            .orElseThrow(() -> new NotFoundException(
                "Product '" + id + "' was not found.",
                "product_not_found",
                Map.of("product_id", id)
            ));

        Update update = new Update().set("imageUrl", null).set("updatedAt", DateUtils.utcNow());
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(id)), update, ProductDocument.class);
    }

    private List<String> normalizeTags(List<String> tags) {
        if (tags == null) return new ArrayList<>();
        return tags.stream()
            .map(t -> t.trim().toLowerCase())
            .filter(t -> !t.isEmpty())
            .distinct()
            .limit(30)
            .collect(Collectors.toList());
    }

    private ProductResponse toResponse(ProductDocument doc) {
        return ProductResponse.builder()
            .id(doc.getId())
            .sku(doc.getSku())
            .title(doc.getTitle())
            .description(doc.getDescription() != null ? doc.getDescription() : "")
            .price(doc.getPrice())
            .category(doc.getCategory())
            .tags(doc.getTags() != null ? doc.getTags() : new ArrayList<>())
            .attributes(doc.getAttributes() != null ? doc.getAttributes() : new HashMap<>())
            .variants(convertVariantsToDTO(doc.getVariants()))
            .active(doc.getActive() != null ? doc.getActive() : true)
            .imageUrl(doc.getImageUrl())
            .updatedAt(doc.getUpdatedAt())
            .createdAt(doc.getCreatedAt())
            .build();
    }

    private List<ProductDocument.Variant> convertVariantsToDocument(List<VariantDTO> variants) {
        if (variants == null) return new ArrayList<>();
        return variants.stream()
            .map(v -> {
                ProductDocument.Variant docVariant = new ProductDocument.Variant();
                docVariant.setSku(v.getSku());
                docVariant.setColor(v.getColor());
                docVariant.setStock(v.getStock() != null ? v.getStock() : 0);
                return docVariant;
            })
            .collect(Collectors.toList());
    }

    private List<VariantDTO> convertVariantsToDTO(List<ProductDocument.Variant> variants) {
        if (variants == null) return new ArrayList<>();
        return variants.stream()
            .map(v -> VariantDTO.builder()
                .sku(v.getSku())
                .color(v.getColor())
                .stock(v.getStock())
                .build())
            .collect(Collectors.toList());
    }

    public void ensureIndexes() {
        // Indexes are created via @Indexed annotations on the document
    }
}