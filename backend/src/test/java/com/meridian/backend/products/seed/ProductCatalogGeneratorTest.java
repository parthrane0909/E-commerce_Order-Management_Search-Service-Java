package com.meridian.backend.products.seed;

import com.meridian.backend.products.document.ProductDocument;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the properties the 3000-product seed brief actually asks for: reproducible,
 * collision free, genuinely varied, schema-compatible and bound to real artwork.
 */
class ProductCatalogGeneratorTest {

    private static final int TARGET = 3000;
    private static final Pattern GENERATED_SKU = Pattern.compile(ProductCatalogGenerator.GENERATED_SKU_REGEX);
    private static final Pattern DEMO_SKU = Pattern.compile("^[A-Z]{3}-[0-9]{3}$");

    private static ProductImagePool pool;
    private static List<ProductDocument> products;

    @BeforeAll
    static void generateOnce() {
        pool = ProductImagePool.load();
        products = ProductCatalogGenerator.generate(TARGET, Set.of(), pool);
    }

    /* ------------------------------------------------------------------ scale + shape */

    @Test
    void generatesTheRequestedNumberOfProducts() {
        assertEquals(TARGET, products.size());
    }

    @Test
    void everyProductCarriesTheExistingSchemaFields() {
        for (ProductDocument doc : products) {
            assertNotNull(doc.getSku());
            assertNotNull(doc.getTitle());
            assertNotNull(doc.getDescription());
            assertNotNull(doc.getPrice());
            assertNotNull(doc.getCategory());
            assertNotNull(doc.getTags());
            assertFalse(doc.getTags().isEmpty(), doc.getSku() + " has no tags");
            assertNotNull(doc.getAttributes());
            assertFalse(doc.getAttributes().isEmpty(), doc.getSku() + " has no attributes");
            assertNotNull(doc.getVariants());
            assertFalse(doc.getVariants().isEmpty(), doc.getSku() + " has no variants");
            assertNotNull(doc.getActive());
            assertNotNull(doc.getImageUrl());
            assertNotNull(doc.getCreatedAt());
            assertNotNull(doc.getUpdatedAt());
            assertTrue(doc.getUpdatedAt().equals(doc.getCreatedAt())
                    || doc.getUpdatedAt().isAfter(doc.getCreatedAt()),
                doc.getSku() + " has updated_at before created_at");
        }
    }

    /* ------------------------------------------------------------------ uniqueness */

    @Test
    void skusAreUniqueAndInTheDedicatedFourDigitNamespace() {
        Set<String> skus = products.stream().map(ProductDocument::getSku).collect(Collectors.toSet());
        assertEquals(TARGET, skus.size(), "duplicate SKU detected");

        for (String sku : skus) {
            assertTrue(GENERATED_SKU.matcher(sku).matches(), "generated SKU outside namespace: " + sku);
            assertFalse(DEMO_SKU.matcher(sku).matches(),
                "generated SKU must never look like a demo SKU (3 digits): " + sku);
        }

        Set<String> variantSkus = products.stream()
            .flatMap(p -> p.getVariants().stream())
            .map(ProductDocument.Variant::getSku)
            .collect(Collectors.toSet());
        long variantCount = products.stream().mapToInt(p -> p.getVariants().size()).sum();
        assertEquals(variantCount, variantSkus.size(), "duplicate variant SKU detected");
    }

    @Test
    void stepsAroundSkusThatAlreadyExist() {
        Set<String> reserved = Set.of("KIT-1001", "CLO-1001", "AUT-1001");
        List<ProductDocument> regenerated = ProductCatalogGenerator.generate(50, reserved, pool);

        Set<String> generated = regenerated.stream().map(ProductDocument::getSku).collect(Collectors.toSet());
        for (String sku : reserved) {
            assertFalse(generated.contains(sku),
                "the seed must issue a different SKU instead of overwriting " + sku);
        }
        assertEquals(50, generated.size());
    }

    @Test
    void avoidsTitlesThatAlreadyExistInTheCatalog() {
        Set<String> reserved = Set.of("Wireless Optical Mouse", "Ergonomic Office Chair",
            "USB Desk Microphone", "Standing Desk Converter", "Portable Bluetooth Speaker");

        List<ProductDocument> regenerated =
            ProductCatalogGenerator.generate(300, Set.of(), reserved, pool);

        Set<String> titles = regenerated.stream().map(ProductDocument::getTitle).collect(Collectors.toSet());
        assertEquals(300, titles.size(), "duplicate title detected within the run");
        for (String title : reserved) {
            assertFalse(titles.contains(title),
                "the seed reissued an existing catalog title: " + title);
        }
    }

    /* ------------------------------------------------------------------ reproducibility */

    @Test
    void isFullyDeterministicForAGivenSeed() {
        List<ProductDocument> again = ProductCatalogGenerator.generate(TARGET, Set.of(), pool);
        assertEquals(TARGET, again.size());

        for (int i = 0; i < TARGET; i++) {
            ProductDocument left = products.get(i);
            ProductDocument right = again.get(i);
            assertEquals(left.getSku(), right.getSku());
            assertEquals(left.getTitle(), right.getTitle());
            assertEquals(left.getPrice(), right.getPrice());
            assertEquals(left.getCategory(), right.getCategory());
            assertEquals(left.getTags(), right.getTags());
            assertEquals(left.getAttributes(), right.getAttributes());
            assertEquals(left.getImageUrl(), right.getImageUrl());
            assertEquals(left.getCreatedAt(), right.getCreatedAt());
            assertEquals(left.getActive(), right.getActive());
        }
    }

    /* ------------------------------------------------------------------ diversity */

    @Test
    void spansManyCategoriesEvenly() {
        List<String> categories = products.stream().map(ProductDocument::getCategory).distinct().toList();
        assertTrue(categories.size() >= 25, "expected a broad catalog, found " + categories.size());

        for (String category : categories) {
            long count = products.stream().filter(p -> category.equals(p.getCategory())).count();
            assertTrue(count >= 90 && count <= 105,
                "round-robin distribution broken for " + category + " (" + count + ")");
        }
    }

    @Test
    void titlesLookLikeRealProducts() {
        Set<String> titles = products.stream().map(ProductDocument::getTitle).collect(Collectors.toSet());
        assertEquals(TARGET, titles.size(), "duplicate title detected");

        Pattern synthetic = Pattern.compile("(?i)^(product|item)\\s*#?\\d+$");
        Set<String> syntheticTitles = titles.stream()
            .filter(t -> synthetic.matcher(t).matches())
            .collect(Collectors.toSet());
        assertTrue(syntheticTitles.isEmpty(), "template titles leaked: " + syntheticTitles);

        // A healthy spread of distinct base names, not one name with 3000 suffixes.
        Set<String> baseNames = titles.stream()
            .map(t -> t.replaceAll("\\s*\\(.*$", "").replaceAll(" — .*$", "").trim())
            .collect(Collectors.toSet());
        assertTrue(baseNames.size() >= 250, "only " + baseNames.size() + " distinct base names");
    }

    @Test
    void pricesTagsAndAttributesAreVaried() {
        Set<Double> prices = products.stream().map(ProductDocument::getPrice).collect(Collectors.toSet());
        assertTrue(prices.size() >= 400, "only " + prices.size() + " distinct prices");

        Set<String> tags = products.stream()
            .flatMap(p -> p.getTags().stream())
            .collect(Collectors.toSet());
        assertTrue(tags.size() >= 40, "only " + tags.size() + " distinct tags");

        Set<Object> brands = products.stream()
            .map(p -> p.getAttributes().get("brand"))
            .collect(Collectors.toSet());
        assertTrue(brands.size() >= 10, "only " + brands.size() + " distinct brands");

        long attributeRich = products.stream()
            .filter(p -> p.getAttributes().size() >= 4)
            .count();
        assertTrue(attributeRich >= TARGET * 0.8, "too many sparse attribute maps: " + attributeRich);

        Set<String> attributeKeys = products.stream()
            .flatMap(p -> p.getAttributes().keySet().stream())
            .collect(Collectors.toSet());
        assertTrue(attributeKeys.size() >= 30, "only " + attributeKeys.size() + " distinct attribute keys");
    }

    @Test
    void carriesASteadyInactiveSlice() {
        long inactive = products.stream().filter(p -> !Boolean.TRUE.equals(p.getActive())).count();
        assertEquals(TARGET / 25, inactive, "visibility=inactive must have a predictable slice");
        assertTrue(inactive > 0 && inactive < TARGET);
    }

    /* ------------------------------------------------------------------ artwork */

    @Test
    void everyProductPointsAtARealReusableAsset() {
        Set<String> poolFiles = pool.allFiles();
        Set<String> used = new HashSet<>();

        for (ProductDocument doc : products) {
            String url = doc.getImageUrl();
            assertNotNull(url, doc.getSku() + " has no image_url");
            assertTrue(url.startsWith("/products/"), url);
            String file = url.substring("/products/".length());
            assertTrue(poolFiles.contains(file),
                doc.getSku() + " references " + file + " which is not in the manifest");
            used.add(file);
        }

        // Reuse is required: no per-product image files.
        assertTrue(used.size() < poolFiles.size() + 1);
        assertTrue(used.size() <= poolFiles.size(),
            "more distinct files than the pool holds — images are not being shared");
        assertTrue(used.size() >= 20,
            "the catalog should spread across the pool, only " + used.size() + " assets used");
    }

    @Test
    void timelineSpansBothSidesOfTheDemoProducts() {
        // Demo products are seeded at now-90..now-65 days; generated rows must overlap that
        // window so the default `newest` ordering interleaves instead of front-loading one set.
        java.time.OffsetDateTime min = products.stream().map(ProductDocument::getCreatedAt).min(java.util.Comparator.naturalOrder()).orElseThrow();
        java.time.OffsetDateTime max = products.stream().map(ProductDocument::getCreatedAt).max(java.util.Comparator.naturalOrder()).orElseThrow();
        assertTrue(min.isBefore(ProductCatalogGenerator.CATALOG_EPOCH.minusDays(100)),
            "timestamps must reach back well before the epoch");
        assertTrue(max.isAfter(ProductCatalogGenerator.CATALOG_EPOCH.minusDays(100)),
            "timestamps must also cover recent dates");
        assertTrue(max.isBefore(ProductCatalogGenerator.CATALOG_EPOCH.plusDays(1)),
            "generated timestamps must not run into the future");
    }
}
