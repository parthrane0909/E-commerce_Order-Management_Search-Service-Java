package com.meridian.backend.products;

import com.meridian.backend.exception.UnprocessableException;
import com.meridian.backend.products.document.ProductDocument;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the opaque product-listing cursor.
 *
 * <p>Every rejection path must surface as {@link UnprocessableException} (HTTP 422), never as an
 * unhandled exception that the catch-all handler would turn into a 500.
 */
class ProductCursorTest {

    private static final SortSpec NEWEST = new SortSpec("newest", "createdAt", "created_at",
        org.springframework.data.domain.Sort.Direction.DESC);
    private static final SortSpec TITLE_ASC = new SortSpec("title_asc", "title", "title",
        org.springframework.data.domain.Sort.Direction.ASC);
    private static final SortSpec PRICE_ASC = new SortSpec("price_asc", "price", "price",
        org.springframework.data.domain.Sort.Direction.ASC);

    private static ProductDocument doc(String title, double price, OffsetDateTime createdAt) {
        ProductDocument d = new ProductDocument();
        d.setId(new ObjectId().toHexString());
        d.setSku("KIT-1001");
        d.setTitle(title);
        d.setPrice(price);
        d.setCreatedAt(createdAt);
        return d;
    }

    private static String fp(String sort, String visibility, String category, String tag, String query) {
        return ProductCursor.fingerprint(sort, visibility, category,
            (tag == null || tag.isBlank()) ? List.of() : List.of(tag), query);
    }

    /* ------------------------------------------------------------------ encode/decode */

    @Test
    void roundTripsTheNewestOrderPosition() {
        OffsetDateTime createdAt = OffsetDateTime.of(2026, 3, 4, 5, 6, 7, 0, ZoneOffset.UTC);
        ProductDocument last = doc("Wireless Mouse", 50.16, createdAt);
        String fingerprint = fp("newest", "active", "kitchen", "cooking", "");

        String token = ProductCursor.encode(NEWEST, fingerprint, last);
        assertNotNull(token);
        assertFalse(token.contains("="), "cursor must be unpadded");
        assertFalse(token.contains("+") || token.contains("/"), "cursor must be base64url");

        ProductCursor decoded = ProductCursor.decode(token, fingerprint, NEWEST);
        assertEquals("created_at", decoded.getSortField());
        assertFalse(decoded.isAscending());
        assertTrue(decoded.idDescending());
        assertEquals(createdAt.toInstant().toEpochMilli(),
            ((java.util.Date) decoded.getSortValue()).getTime(),
            "the sort value must round-trip to the same instant");
        assertEquals(last.getId(), decoded.getId());
    }

    @Test
    void roundTripsTitleAndPriceSortValues() {
        ProductDocument last = doc("Ceramic Coffee Mug", 12.5, OffsetDateTime.now(ZoneOffset.UTC));
        String fingerprint = fp("title_asc", "active", "", "", "");

        ProductCursor decoded = ProductCursor.decode(
            ProductCursor.encode(TITLE_ASC, fingerprint, last), fingerprint, TITLE_ASC);
        assertEquals("Ceramic Coffee Mug", decoded.getSortValue());
        assertTrue(decoded.isAscending(), "title_asc must resume in ascending order");

        ProductCursor price = ProductCursor.decode(
            ProductCursor.encode(PRICE_ASC, fingerprint, last), fingerprint, PRICE_ASC);
        assertEquals(12.5, price.getSortValue());
    }

    @Test
    void encodeRefusesToProduceAnUnusableCursor() {
        ProductDocument withoutId = doc("X", 1.0, OffsetDateTime.now(ZoneOffset.UTC));
        withoutId.setId(null);
        assertNull(ProductCursor.encode(NEWEST, "abc", withoutId));

        ProductDocument withoutTimestamp = doc("X", 1.0, null);
        withoutTimestamp.setId(new ObjectId().toHexString());
        assertNull(ProductCursor.encode(NEWEST, "abc", withoutTimestamp));
    }

    /* ------------------------------------------------------------------ fingerprints */

    @Test
    void fingerprintIsStableAndQuerySpecific() {
        String a = ProductCursor.fingerprint("newest", "active", "kitchen", List.of("cooking"), "");
        String b = ProductCursor.fingerprint("newest", "active", "kitchen", List.of("cooking"), "");
        assertEquals(a, b, "identical queries must fingerprint identically");

        assertNotSameValue(a, ProductCursor.fingerprint("price_asc", "active", "kitchen", List.of("cooking"), ""));
        assertNotSameValue(a, ProductCursor.fingerprint("newest", "all", "kitchen", List.of("cooking"), ""));
        assertNotSameValue(a, ProductCursor.fingerprint("newest", "active", "audio", List.of("cooking"), ""));
        assertNotSameValue(a, ProductCursor.fingerprint("newest", "active", "kitchen", List.of("portable"), ""));
        assertNotSameValue(a, ProductCursor.fingerprint("newest", "active", "kitchen", List.of("cooking"), "mug"));
    }

    private static void assertNotSameValue(String left, String right) {
        assertFalse(left.equals(right), "distinct queries must not share a fingerprint");
        assertEquals(16, right.length(), "fingerprints are 16 hex characters");
    }

    /* ------------------------------------------------------------------ rejection paths */

    private static void assertRejected(String raw, String fingerprint, SortSpec spec) {
        UnprocessableException ex = assertThrows(UnprocessableException.class,
            () -> ProductCursor.decode(raw, fingerprint, spec));
        assertEquals(422, ex.getStatusCode(), "a bad cursor must be a client error, never a 500");
        assertEquals("invalid_cursor", ex.getCode());
        assertNotNull(ex.getMessage());
    }

    @Test
    void rejectsMalformedCursors() {
        String fingerprint = fp("newest", "active", "", "", "");

        assertRejected(null, fingerprint, NEWEST);
        assertRejected("", fingerprint, NEWEST);
        assertRejected("   ", fingerprint, NEWEST);
        assertRejected("not-a-cursor!!", fingerprint, NEWEST);
        assertRejected(base64("this is not json"), fingerprint, NEWEST);
        assertRejected(base64("[1,2,3]"), fingerprint, NEWEST);
        assertRejected("a".repeat(ProductCursor.MAX_LENGTH + 1), fingerprint, NEWEST);
    }

    @Test
    void rejectsForgedOrStalePayloads() {
        String fingerprint = fp("newest", "active", "", "", "");
        ProductDocument last = doc("Wireless Mouse", 50.16, OffsetDateTime.now(ZoneOffset.UTC));
        String token = ProductCursor.encode(NEWEST, fingerprint, last);

        // Wrong query state (search/filter/sort changed since the cursor was issued).
        assertRejected(token, fp("newest", "active", "kitchen", "", ""), NEWEST);

        // Wrong sort for this cursor.
        assertRejected(token, fingerprint, PRICE_ASC);

        // Unknown sort field.
        assertRejected(base64("{\"v\":1,\"f\":\"" + fingerprint + "\",\"sf\":\"__proto__\","
            + "\"sd\":\"desc\",\"idd\":\"desc\",\"sv\":1,\"id\":\"" + last.getId() + "\"}"),
            fingerprint, NEWEST);

        // Unknown sort direction.
        assertRejected(base64("{\"v\":1,\"f\":\"" + fingerprint + "\",\"sf\":\"created_at\","
            + "\"sd\":\"sideways\",\"idd\":\"desc\",\"sv\":1,\"id\":\"" + last.getId() + "\"}"),
            fingerprint, NEWEST);

        // Not a valid ObjectId.
        assertRejected(base64("{\"v\":1,\"f\":\"" + fingerprint + "\",\"sf\":\"created_at\","
            + "\"sd\":\"desc\",\"idd\":\"desc\",\"sv\":1,\"id\":\"nope\"}"),
            fingerprint, NEWEST);

        // Unsupported version.
        assertRejected(base64("{\"v\":99,\"f\":\"" + fingerprint + "\",\"sf\":\"created_at\","
            + "\"sd\":\"desc\",\"idd\":\"desc\",\"sv\":1,\"id\":\"" + last.getId() + "\"}"),
            fingerprint, NEWEST);

        // Wrong payload type for the sort field.
        assertRejected(base64("{\"v\":1,\"f\":\"" + fingerprint + "\",\"sf\":\"created_at\","
            + "\"sd\":\"desc\",\"idd\":\"desc\",\"sv\":\"yesterday\",\"id\":\"" + last.getId() + "\"}"),
            fingerprint, NEWEST);

        // Missing payload.
        assertRejected(base64("{\"v\":1,\"f\":\"" + fingerprint + "\",\"sf\":\"created_at\","
            + "\"sd\":\"desc\",\"idd\":\"desc\",\"id\":\"" + last.getId() + "\"}"),
            fingerprint, NEWEST);
    }

    private static String base64(String json) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
