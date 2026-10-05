package com.meridian.backend.images;

import com.meridian.backend.common.dto.ErrorResponse;
import com.meridian.backend.exception.NotFoundException;
import com.meridian.backend.exception.UnprocessableException;
import com.meridian.backend.products.dto.ProductResponse;
import com.meridian.backend.security.CurrentUser;
import com.meridian.backend.orders.entity.User;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ImageController {

    private final ImageService imageService;
    private final com.meridian.backend.products.ProductService productService;

    @PostMapping(value = "/{productId}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProductResponse> uploadProductImage(
            @PathVariable @Size(min = 1, max = 64) String productId,
            @RequestPart("file") MultipartFile file,
            @CurrentUser User currentUser) throws IOException {

        String imageUrl = imageService.uploadImage(productId, file);
        ProductResponse updated = productService.getProduct(productId);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{productId}/image")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProductResponse> removeProductImage(
            @PathVariable @Size(min = 1, max = 64) String productId,
            @CurrentUser User currentUser) throws IOException {

        imageService.removeImage(productId);
        return ResponseEntity.ok(productService.getProduct(productId));
    }
}