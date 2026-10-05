package com.meridian.backend.products.seed;

import com.meridian.backend.products.document.ProductDocument;
import com.mongodb.client.result.DeleteResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * One-shot, profile-gated catalog seeder.
 *
 * <p>Run it explicitly — it is never active on a normal application start:
 * <pre>
 *   docker compose build seed
 *   docker compose --profile tools run --rm catalog-seed
 * </pre>
 *
 * <p>Safety properties:
 * <ul>
 *   <li><b>Idempotent</b> — it counts how many generated SKUs already exist and only tops the
 *       catalog up to the target. Running it twice inserts nothing the second time.</li>
 *   <li><b>Never overwrites</b> — every SKU already present (demo products, admin products,
 *       earlier catalog rows) is reserved, so the generator steps around it and issues a
 *       different deterministic SKU instead of silently replacing it.</li>
 *   <li><b>Additive</b> — the 27 demo products seeded by {@code SeedRunner} are untouched; the
 *       final catalog is demo products + generated products.</li>
 *   <li><b>Explicit reset only</b> — deleting the generated slice requires
 *       {@code CATALOG_RESET=true}; nothing else deletes data.</li>
 *   <li><b>Valid artwork</b> — the manifest is loaded and validated before a single row is
 *       written, and an unmapped category aborts the run with a clear message.</li>
 * </ul>
 */
@Component
@Profile("catalog")
public class CatalogSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CatalogSeedRunner.class);

    private static final Pattern GENERATED_SKU = Pattern.compile(ProductCatalogGenerator.GENERATED_SKU_REGEX);

    /** Bulk insert size. Large enough to be fast, small enough to stay well under the 16MB doc limit. */
    private static final int BATCH_SIZE = 1000;

    private final MongoTemplate mongoTemplate;

    @Value("${app.catalog.target-products:3000}")
    private int targetProducts;

    @Value("${app.catalog.reset:false}")
    private boolean reset;

    public CatalogSeedRunner(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        int exitCode = 0;
        long startedAt = System.currentTimeMillis();
        try {
            execute();
            log.info("Catalog seed completed in {} ms", System.currentTimeMillis() - startedAt);
        } catch (Exception ex) {
            log.error("Catalog seed FAILED: {}", ex.getMessage(), ex);
            exitCode = 1;
        }
        // One-shot tool: leave no container running behind it. Exit 0 = success, 1 = failure.
        System.exit(exitCode);
    }

    private void execute() {
        // Fail fast on a bad/missing manifest before touching any data.
        ProductImagePool imagePool = ProductImagePool.load();
        log.info("Image pool validated: {} reusable assets across {} categories (default artwork '{}')",
            imagePool.assetCount(), imagePool.categoryCount(), imagePool.getDefaultFile());

        if (reset) {
            DeleteResult removed = mongoTemplate.remove(
                Query.query(Criteria.where("sku").regex(GENERATED_SKU.pattern())),
                ProductDocument.class);
            log.info("CATALOG_RESET=true — removed {} previously generated products", removed.getDeletedCount());
        }

        Set<String> existingSkus = loadExistingSkus();
        // Same idea for names: a generated unit that would read identically to a demo product
        // picks its qualifier variant instead, so the merged catalog has no twin titles.
        Set<String> existingTitles = loadExistingTitles();

        long alreadyGenerated = existingSkus.stream().filter(s -> GENERATED_SKU.matcher(s).matches()).count();
        int needed = targetProducts - (int) alreadyGenerated;

        if (needed <= 0) {
            log.info("Catalog already holds {} generated products (target {}) — nothing to insert.",
                alreadyGenerated, targetProducts);
            report();
            return;
        }

        log.info("Generating {} products ({} already present, target {}) with seed {} ...",
            needed, alreadyGenerated, targetProducts, ProductCatalogGenerator.RANDOM_SEED);

        List<ProductDocument> batch =
            ProductCatalogGenerator.generate(needed, existingSkus, existingTitles, imagePool);

        // generate() already reserved every existing SKU, but re-check so a bug can never
        // turn into an accidental overwrite of a demo or admin product.
        List<ProductDocument> toInsert = batch.stream()
            .filter(doc -> !existingSkus.contains(doc.getSku()))
            .collect(Collectors.toList());
        if (toInsert.size() != batch.size()) {
            log.warn("Skipped {} generated SKU collision(s)", batch.size() - toInsert.size());
        }

        int inserted = 0;
        for (int from = 0; from < toInsert.size(); from += BATCH_SIZE) {
            List<ProductDocument> slice = new ArrayList<>(toInsert.subList(from, Math.min(from + BATCH_SIZE, toInsert.size())));
            mongoTemplate.insert(slice, ProductDocument.class);
            inserted += slice.size();
            log.info("  inserted {} / {}", inserted, toInsert.size());
        }

        log.info("Inserted {} products via MongoTemplate bulk insert", inserted);
        report();
    }

    private Set<String> loadExistingSkus() {
        return mongoTemplate.findDistinct(new Query(), "sku", ProductDocument.class, String.class)
            .stream()
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toCollection(HashSet::new));
    }

    private Set<String> loadExistingTitles() {
        return mongoTemplate.findDistinct(new Query(), "title", ProductDocument.class, String.class)
            .stream()
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toCollection(HashSet::new));
    }

    /** The verification summary required by the seed brief. */
    private void report() {
        long total = mongoTemplate.count(new Query(), ProductDocument.class);
        long active = mongoTemplate.count(
            Query.query(Criteria.where("active").is(true)), ProductDocument.class);
        long inactive = mongoTemplate.count(
            Query.query(Criteria.where("active").ne(true)), ProductDocument.class);

        List<String> categories = mongoTemplate.findDistinct(
            new Query(), "category", ProductDocument.class, String.class);
        List<String> skus = mongoTemplate.findDistinct(
            new Query(), "sku", ProductDocument.class, String.class);
        long withImage = mongoTemplate.count(
            Query.query(Criteria.where("image_url").exists(true).ne(null).ne("")),
            ProductDocument.class);
        long generated = mongoTemplate.count(
            Query.query(Criteria.where("sku").regex(GENERATED_SKU.pattern())),
            ProductDocument.class);

        Set<String> distinctImages = new HashSet<>(
            mongoTemplate.findDistinct(new Query(), "image_url", ProductDocument.class, String.class));

        log.info("--------------------------------------------------");
        log.info(" Catalog verification");
        log.info("   total products     : {}", total);
        log.info("   active             : {}", active);
        log.info("   inactive           : {}", inactive);
        log.info("   generated slice    : {}", generated);
        log.info("   categories         : {} -> {}", categories.size(), categories);
        log.info("   unique SKUs        : {} / {}{}", skus.size(), total,
            skus.size() == total ? "  (no collisions)" : "  (SKU COLLISION!)");
        long distinctTitles = mongoTemplate.findDistinct(
            new Query(), "title", ProductDocument.class, String.class).size();
        log.info("   distinct titles    : {} / {}{}", distinctTitles, total,
            distinctTitles == total ? "  (no twins)" : "  (twin titles — reserved set not applied?)");
        log.info("   image_url coverage : {} / {}{}", withImage, total,
            withImage == total ? "  (100%)" : "  (INCOMPLETE!)");
        log.info("   distinct artwork   : {} URLs in use", distinctImages.size());
        log.info("--------------------------------------------------");

        if (skus.size() != total) {
            throw new IllegalStateException("SKU uniqueness check failed: " + skus.size() + " unique of " + total);
        }
        if (withImage != total) {
            throw new IllegalStateException("image_url coverage check failed: " + withImage + " of " + total);
        }
    }
}
