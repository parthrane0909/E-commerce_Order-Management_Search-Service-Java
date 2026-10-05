package com.meridian.backend.images;

import com.meridian.backend.common.util.DateUtils;
import com.meridian.backend.exception.NotFoundException;
import com.meridian.backend.exception.UnprocessableException;
import com.meridian.backend.products.document.ProductDocument;
import com.meridian.backend.products.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ImageService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ImageService.class);

    private final ProductRepository productRepository;

    @Value("${app.uploads.dir:/app/uploads}")
    private String uploadsDir;

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
        "image/jpeg", "image/png", "image/webp", "image/svg+xml"
    );
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
        ".jpg", ".jpeg", ".png", ".webp", ".svg"
    );
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5 MB

    public String uploadImage(String productId, MultipartFile file) throws IOException {
        ProductDocument product = productRepository.findById(productId)
            .orElseThrow(() -> new NotFoundException(
                "Product '" + productId + "' was not found.",
                "product_not_found",
                java.util.Map.of("product_id", productId)
            ));

        validateImageFile(file);

        String filename = generateSafeFilename(file.getOriginalFilename());
        Path uploadPath = Paths.get(uploadsDir, "products");
        Files.createDirectories(uploadPath);
        Path destination = uploadPath.resolve(filename);

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new UnprocessableException(
                "File too large. Maximum size: 5 MB.",
                "image_too_large",
                java.util.Map.of("field", "image", "max_size_mb", 5)
            );
        }

        Files.copy(file.getInputStream(), destination);

        String oldImageUrl = product.getImageUrl();
        if (oldImageUrl != null && oldImageUrl.startsWith("/uploads/")) {
            try {
                Path oldPath = Paths.get(uploadsDir, oldImageUrl.substring("/uploads/".length()));
                if (Files.exists(oldPath) && !oldPath.equals(destination)) {
                    Files.deleteIfExists(oldPath);
                }
            } catch (IOException ex) {
                log.warn("Failed to delete old image: {}", ex.getMessage());
            }
        }

        String newImageUrl = "/uploads/products/" + filename;
        product.setImageUrl(newImageUrl);
        product.setUpdatedAt(DateUtils.utcNow());
        productRepository.save(product);

        return newImageUrl;
    }

    public void removeImage(String productId) throws IOException {
        ProductDocument product = productRepository.findById(productId)
            .orElseThrow(() -> new NotFoundException(
                "Product '" + productId + "' was not found.",
                "product_not_found",
                java.util.Map.of("product_id", productId)
            ));

        String oldImageUrl = product.getImageUrl();
        if (oldImageUrl != null && oldImageUrl.startsWith("/uploads/")) {
            try {
                Path oldPath = Paths.get(uploadsDir, oldImageUrl.substring("/uploads/".length()));
                if (Files.exists(oldPath)) {
                    Files.deleteIfExists(oldPath);
                }
            } catch (IOException ex) {
                log.warn("Failed to delete old image: {}", ex.getMessage());
            }
        }

        product.setImageUrl(null);
        product.setUpdatedAt(DateUtils.utcNow());
        productRepository.save(product);
    }

    private void validateImageFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new UnprocessableException("No filename provided.", "invalid_image", java.util.Map.of("field", "image"));
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new UnprocessableException(
                "Unsupported image format. Allowed: JPEG, PNG, WebP, SVG.",
                "invalid_image_format",
                java.util.Map.of("field", "image", "allowed", ALLOWED_CONTENT_TYPES)
            );
        }
    }

    private String generateSafeFilename(String originalFilename) {
        String ext = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            ext = originalFilename.substring(originalFilename.lastIndexOf(".")).toLowerCase();
        }
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            ext = ".webp";
        }
        return UUID.randomUUID().toString().replace("-", "") + ext;
    }
}