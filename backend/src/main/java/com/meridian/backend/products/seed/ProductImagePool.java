package com.meridian.backend.products.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The curated, reusable product-artwork pool used by {@link ProductCatalogGenerator}.
 *
 * <p>The seed never invents an {@code image_url}: it asks this pool for a file name that is
 * listed in {@code classpath:catalog/image-pool.json}, which is the single manifest shared
 * with the frontend. {@code frontend/tests/image-pool.spec.js} asserts that every file named
 * in the manifest really exists under {@code frontend/public/products/}, so a typo here fails
 * the frontend test suite instead of silently producing broken product images.
 *
 * <p>Loading is fail-fast: a missing group, an empty group, a category that maps to an
 * unknown group, or a file name that is not a plain asset name aborts the seed with an
 * explicit message rather than writing products with broken artwork.
 */
public final class ProductImagePool {

    /** Same relative path style the seeded demo products already use: {@code /products/<file>}. */
    private final String urlPrefix;
    private final String defaultFile;
    private final Map<String, List<String>> groups;
    private final Map<String, String> categoryGroups;

    private ProductImagePool(String urlPrefix,
                             String defaultFile,
                             Map<String, List<String>> groups,
                             Map<String, String> categoryGroups) {
        this.urlPrefix = urlPrefix;
        this.defaultFile = defaultFile;
        this.groups = groups;
        this.categoryGroups = categoryGroups;
    }

    /** Loads and validates {@code classpath:catalog/image-pool.json}. */
    public static ProductImagePool load() {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root;
        try (InputStream in = ProductImagePool.class.getResourceAsStream("/catalog/image-pool.json")) {
            if (in == null) {
                throw new IllegalStateException(
                    "Catalog image pool manifest is missing: classpath:catalog/image-pool.json");
            }
            root = mapper.readTree(in);
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Catalog image pool manifest could not be parsed: " + ex.getMessage(), ex);
        }

        String prefix = root.path("url_prefix").asText("");
        if (!prefix.startsWith("/") || prefix.contains("..")) {
            throw new IllegalStateException("Catalog image pool: url_prefix must be a safe absolute path, was '" + prefix + "'");
        }

        String defaultFile = root.path("default").asText("");
        if (!isPlainAssetName(defaultFile)) {
            throw new IllegalStateException("Catalog image pool: 'default' must be a plain asset file name, was '" + defaultFile + "'");
        }

        Map<String, List<String>> groups = new LinkedHashMap<>();
        JsonNode groupsNode = root.path("groups");
        if (!groupsNode.isObject() || groupsNode.isEmpty()) {
            throw new IllegalStateException("Catalog image pool: 'groups' must define at least one image group");
        }
        groupsNode.fields().forEachRemaining(entry -> {
            List<String> files = new ArrayList<>();
            if (!entry.getValue().isArray() || entry.getValue().isEmpty()) {
                throw new IllegalStateException("Catalog image pool: group '" + entry.getKey() + "' is empty");
            }
            for (JsonNode file : entry.getValue()) {
                String name = file.asText("");
                if (!isPlainAssetName(name)) {
                    throw new IllegalStateException(
                        "Catalog image pool: group '" + entry.getKey() + "' contains an invalid asset name '" + name + "'");
                }
                files.add(name);
            }
            groups.put(entry.getKey(), List.copyOf(files));
        });

        if (!groups.containsKey("default") || !groups.get("default").contains(defaultFile)) {
            throw new IllegalStateException("Catalog image pool: the default group must contain '" + defaultFile + "'");
        }

        Map<String, String> categoryGroups = new LinkedHashMap<>();
        JsonNode mapping = root.path("category_groups");
        if (!mapping.isObject() || mapping.isEmpty()) {
            throw new IllegalStateException("Catalog image pool: 'category_groups' must map every product category to a group");
        }
        mapping.fields().forEachRemaining(entry -> {
            String category = entry.getKey();
            String group = entry.getValue().asText("");
            List<String> files = groups.get(group);
            if (files == null || files.isEmpty()) {
                throw new IllegalStateException(
                    "Catalog image pool: category '" + category + "' maps to unknown or empty group '" + group + "'");
            }
            categoryGroups.put(category, group);
        });

        return new ProductImagePool(prefix, defaultFile,
            Collections.unmodifiableMap(groups),
            Collections.unmodifiableMap(categoryGroups));
    }

    /**
     * Deterministically picks the artwork for a product.
     *
     * @param category product category (must be present in {@code category_groups})
     * @param salt     stable per-product discriminator, so the same product always maps to
     *                 the same image while neighbours in a category spread across the pool
     * @return a relative URL such as {@code /products/KTN-001.svg}
     * @throws IllegalStateException when the category has no image group — the seed must
     *                               fail loudly instead of writing a broken {@code image_url}
     */
    public String pick(String category, int salt) {
        String group = categoryGroups.get(category);
        if (group == null) {
            throw new IllegalStateException(
                "Catalog image pool: no image group is mapped for product category '" + category
                    + "'. Add it to catalog/image-pool.json before seeding.");
        }
        List<String> files = groups.get(group);
        if (files == null || files.isEmpty()) {
            throw new IllegalStateException("Catalog image pool: group '" + group + "' has no artwork for category '" + category + "'");
        }
        int index = Math.floorMod(salt, files.size());
        return urlPrefix + files.get(index);
    }

    /** Every asset referenced anywhere in the manifest (deduplicated, stable order). */
    public Set<String> allFiles() {
        Set<String> files = new LinkedHashSet<>();
        groups.values().forEach(files::addAll);
        return Collections.unmodifiableSet(files);
    }

    public int assetCount() {
        return allFiles().size();
    }

    public int categoryCount() {
        return categoryGroups.size();
    }

    public String getDefaultFile() {
        return defaultFile;
    }

    private static boolean isPlainAssetName(String name) {
        return name != null
            && name.matches("^[A-Za-z0-9][A-Za-z0-9._-]*\\.(svg|png|jpg|jpeg|webp)$")
            && !name.contains("..")
            && !name.contains("/");
    }
}
