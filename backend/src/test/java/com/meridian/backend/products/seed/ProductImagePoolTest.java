package com.meridian.backend.products.seed;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates the reusable-artwork manifest the catalog seed depends on.
 *
 * <p>The backend only ever sees {@code classpath:catalog/image-pool.json}; the files themselves
 * live in {@code frontend/public/products/}. {@code frontend/tests/image-pool.spec.js} closes
 * that loop by asserting every name listed here exists on disk, so neither side can drift.
 */
class ProductImagePoolTest {

    private static ProductImagePool pool;

    @BeforeAll
    static void loadPool() {
        pool = ProductImagePool.load();
    }

    @Test
    void loadsARealisticSharedAssetPool() {
        // Reuse is the whole point: 51 assets for 3000+ products, never one file per product.
        assertTrue(pool.assetCount() >= 40,
            "expected a curated multi-asset pool, found " + pool.assetCount());
        assertTrue(pool.categoryCount() >= 30,
            "every product category must map to artwork, found " + pool.categoryCount());
        assertTrue(pool.allFiles().contains("default.svg"));
    }

    @Test
    void resolvesEveryProductCategoryToAnExistingAsset() {
        for (ProductCatalogGenerator.CategorySpec category : ProductCatalogGenerator.CATEGORIES) {
            String url = pool.pick(category.key(), 7);
            assertNotNull(url, category.key());
            assertTrue(url.startsWith("/products/"), url);
            String file = url.substring("/products/".length());
            assertTrue(pool.allFiles().contains(file),
                category.key() + " resolved to " + file + " which is not in the manifest");
            assertFalse(file.isBlank());
            assertFalse(file.contains(".."), file);
        }
    }

    @Test
    void isDeterministicAndSpreadsAcrossTheGroup() {
        String first = pool.pick("kitchen", 0);
        assertEquals(first, pool.pick("kitchen", 0), "same product must always get the same artwork");

        List<String> files = List.copyOf(pool.allFiles());
        assertFalse(files.isEmpty());
        String[] urls = new String[24];
        for (int i = 0; i < 24; i++) {
            urls[i] = pool.pick("kitchen", i);
        }
        long distinct = List.of(urls).stream().distinct().count();
        assertTrue(distinct > 1, "a category must not collapse onto a single image");
    }

    @Test
    void failsLoudlyForAnUnmappedCategory() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> pool.pick("definitely-not-a-category", 0));
        assertTrue(ex.getMessage().contains("definitely-not-a-category"), ex.getMessage());
        assertTrue(ex.getMessage().contains("image-pool.json"),
            "the failure must point at the manifest to fix");
    }
}
