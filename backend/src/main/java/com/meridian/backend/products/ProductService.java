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
import org.bson.types.ObjectId;
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

    /**
     * Product listing with cursor-based pagination.
     *
     * <p><b>Contract.</b> Every field the API already returned is still returned
     * ({@code items, page, limit, total, pages}) — the two cursor fields are additive:
     * {@code next_cursor} and {@code has_more}.
     *
     * <p><b>How a batch is fetched.</b> The query asks MongoDB for {@code limit + 1} rows with
     * the filter, the cursor predicate and the deterministic sort applied. One extra row means
     * "there is more", so no second query is needed just to decide that; the extra row is
     * dropped before the response is built and its predecessor becomes the next cursor.
     *
     * <p><b>Why count is still separate.</b> {@code total}/{@code pages} describe the whole
     * filtered result set, so they are counted from the filter alone — never from the cursor
     * window — and they are counted on a <em>different</em> {@link Query} object. The previous
     * implementation reused one {@code Query} for both calls and reset its own skip/limit while
     * counting, which made every request return the entire result set regardless of
     * {@code page}/{@code limit}.
     *
     * @param cursor opaque continuation token; {@code null}/blank means "first batch"
     */
    public ProductListResponse listProducts(
            String category,
            List<String> tags,
            String q,
            String visibility,
            int page,
            int limit,
            String sort,
            String cursor) {

        if (page < 1) page = 1;
        if (limit < 1) limit = 1;
        if (limit > 100) limit = 100;
        if (!VALID_SORTS.contains(sort)) sort = "newest";

        String normalisedCategory = (category == null || category.isEmpty()) ? "" : category.toLowerCase().trim();
        String searchTerm = (q == null || q.trim().isEmpty()) ? "" : q.trim();
        List<String> cleanTags = cleanTags(tags);
        SortSpec sortSpec = sortSpec(sort);

        // The cursor is only meaningful for the query it was issued against.
        String fingerprint = ProductCursor.fingerprint(
            sortSpec.key(), visibility, normalisedCategory, cleanTags, searchTerm);

        // ---- count: the whole filtered set, cursor and windowing deliberately absent ----
        List<Criteria> countMust = searchTerm.isEmpty() ? List.of() : List.of(searchCriteria(searchTerm));
        long total = mongoTemplate.count(
            Query.query(combine(filterCriteria(normalisedCategory, cleanTags, visibility), countMust)),
            ProductDocument.class);

        // ---- batch: filter + cursor + deterministic sort + (skip) + limit+1 ----
        ProductCursor decoded = null;
        if (cursor != null && !cursor.isBlank()) {
            decoded = ProductCursor.decode(cursor, fingerprint, sortSpec);
        }

        List<Criteria> must = new ArrayList<>();
        if (!searchTerm.isEmpty()) {
            must.add(searchCriteria(searchTerm));
        }
        Criteria after = decoded == null ? null : continuationCriteria(sortSpec, decoded);
        if (after != null) {
            must.add(after);
        }

        Query findQuery = Query.query(combine(filterCriteria(normalisedCategory, cleanTags, visibility), must))
            .with(Sort.by(sortSpec.direction(), sortSpec.property())
                .and(Sort.by(Sort.Direction.DESC, "_id")))
            .skip(decoded == null ? (long) (page - 1) * limit : 0L)
            .limit(limit + 1);

        List<ProductDocument> documents = mongoTemplate.find(findQuery, ProductDocument.class);

        boolean overflowed = documents.size() > limit;
        if (overflowed) {
            documents = new ArrayList<>(documents.subList(0, limit));
        }

        List<ProductResponse> items = documents.stream()
            .map(this::toResponse)
            .collect(Collectors.toList());

        String nextCursor = overflowed && !documents.isEmpty()
            ? ProductCursor.encode(sortSpec, fingerprint, documents.get(documents.size() - 1))
            : null;
        // has_more is only true when a usable cursor was produced: with has_more=true and a
        // null cursor the client would have nothing to ask for, so a missing timestamp can
        // never leave it polling into an empty window.
        boolean hasMore = nextCursor != null;

        int pages = total == 0 ? 0 : (int) Math.ceil((double) total / limit);

        return ProductListResponse.builder()
            .items(items)
            .page(page)
            .limit(limit)
            .total((int) total)
            .pages(pages)
            .nextCursor(hasMore ? nextCursor : null)
            .hasMore(hasMore)
            .build();
    }

    /** The filters every listing mode shares — never the search term, never the cursor. */
    private Criteria filterCriteria(String normalisedCategory, List<String> cleanTags, String visibility) {
        Criteria criteria = new Criteria();

        if ("active".equals(visibility)) {
            criteria.and("active").is(true);
        } else if ("inactive".equals(visibility)) {
            criteria.and("active").is(false);
        }

        if (normalisedCategory != null && !normalisedCategory.isEmpty()) {
            criteria.and("category").is(normalisedCategory);
        }

        if (cleanTags != null && !cleanTags.isEmpty()) {
            criteria.and("tags").all(cleanTags);
        }

        return criteria;
    }

    private List<String> cleanTags(List<String> tags) {
        if (tags == null) return List.of();
        return tags.stream()
            .map(t -> t == null ? "" : t.trim().toLowerCase())
            .filter(t -> !t.isEmpty())
            .distinct()
            .collect(Collectors.toList());
    }

    /** Case-insensitive regex over the four text fields — unchanged from the original listing. */
    private Criteria searchCriteria(String searchTerm) {
        return new Criteria().orOperator(
            Criteria.where("title").regex(searchTerm, "i"),
            Criteria.where("description").regex(searchTerm, "i"),
            Criteria.where("sku").regex(searchTerm, "i"),
            Criteria.where("tags").regex(searchTerm, "i")
        );
    }

    /**
     * Applies {@code must} to {@code base} with a <b>single</b> {@code $and}. One call keeps the
     * rendered document in the shape the rest of this service already relies on (direct filters
     * plus one {@code $and} array), so search, category, tags, visibility and the cursor all
     * coexist without one overwriting another.
     */
    private Criteria combine(Criteria base, List<Criteria> must) {
        if (!must.isEmpty()) {
            base.andOperator(must.toArray(new Criteria[0]));
        }
        return base;
    }

    /**
     * "Everything strictly after the cursor row, in the exact order being paged."
     *
     * <p>Because every sort appends {@code _id DESC}, the continuation is:
     * primary field past the last value, <em>or</em> primary field equal and {@code _id} lower.
     * Duplicated prices and duplicated titles therefore cannot skip or repeat a row.
     */
    private Criteria continuationCriteria(SortSpec spec, ProductCursor decoded) {
        String field = decoded.getSortField();
        Object value = decoded.getSortValue();

        Criteria tieBreaker = new Criteria().andOperator(
            Criteria.where(field).is(value),
            Criteria.where("_id").lt(new ObjectId(decoded.getId()))
        );

        Criteria primary = decoded.isAscending()
            ? Criteria.where(field).gt(value)
            : Criteria.where(field).lt(value);

        return new Criteria().orOperator(primary, tieBreaker);
    }

    /** Maps the public {@code ?sort=} key onto the property/mongo-field pair it pages by. */
    private SortSpec sortSpec(String sort) {
        return switch (sort == null ? "newest" : sort) {
            case "title_asc" -> new SortSpec("title_asc", "title", "title", Sort.Direction.ASC);
            case "price_asc" -> new SortSpec("price_asc", "price", "price", Sort.Direction.ASC);
            case "price_desc" -> new SortSpec("price_desc", "price", "price", Sort.Direction.DESC);
            default -> new SortSpec("newest", "createdAt", "created_at", Sort.Direction.DESC);
        };
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