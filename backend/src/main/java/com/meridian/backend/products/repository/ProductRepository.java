package com.meridian.backend.products.repository;

import com.meridian.backend.products.document.ProductDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends MongoRepository<ProductDocument, String> {

    Optional<ProductDocument> findBySku(String sku);

    Optional<ProductDocument> findBySkuAndIdNot(String sku, String id);

    List<ProductDocument> findByActiveTrue();

    List<ProductDocument> findByCategoryAndActiveTrue(String category);

    @Query("{}")
    long countAll();

    List<ProductDocument> findByTagsInAndActiveTrue(List<String> tags);

    @Query("{ '_id': { $in: ?0 } }")
    List<ProductDocument> findByIds(List<String> ids);

    /**
     * Deletes only the given SKUs.
     *
     * <p>Used by {@code SeedRunner} instead of {@code deleteAll()}: the demo reset must refresh
     * the 27 demo products without wiping the thousands of rows written by the catalog seeder
     * ({@code CatalogSeedRunner}), so the demo and generated catalogs can coexist and be seeded
     * in either order.
     */
    long deleteBySkuIn(Collection<String> skus);
}
