package com.meridian.backend.products;

import com.meridian.backend.common.dto.ErrorResponse;
import com.meridian.backend.exception.NotFoundException;
import com.meridian.backend.exception.UnprocessableException;
import com.meridian.backend.products.dto.*;
import com.meridian.backend.security.CurrentUser;
import com.meridian.backend.orders.entity.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping
    public ResponseEntity<ProductListResponse> listProducts(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) List<String> tags,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "active") @Pattern(regexp = "^(active|inactive|all)$") String visibility,
            @RequestParam(defaultValue = "1") @Min(1) @Max(10000) int page,
            @RequestParam(defaultValue = "24") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "newest") @Pattern(regexp = "^(newest|title_asc|price_asc|price_desc)$") String sort) {

        ProductListResponse response = productService.listProducts(category, tags, q, visibility, page, limit, sort);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/facets")
    public ResponseEntity<ProductFacetsResponse> productFacets() {
        return ResponseEntity.ok(productService.listFacets());
    }

    @GetMapping("/{productId}")
    public ResponseEntity<ProductResponse> getProduct(
            @PathVariable @Size(min = 1, max = 64) String productId) {
        return ResponseEntity.ok(productService.getProduct(productId));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProductResponse> createProduct(@Valid @RequestBody ProductCreate payload) {
        return ResponseEntity.status(201).body(productService.createProduct(payload));
    }

    @PatchMapping("/{productId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProductResponse> updateProduct(
            @PathVariable @Size(min = 1, max = 64) String productId,
            @Valid @RequestBody ProductUpdate payload) {
        return ResponseEntity.ok(productService.updateProduct(productId, payload));
    }

    @DeleteMapping("/{productId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProductResponse> deleteProduct(
            @PathVariable @Size(min = 1, max = 64) String productId) {
        return ResponseEntity.ok(productService.deleteProduct(productId));
    }
}
