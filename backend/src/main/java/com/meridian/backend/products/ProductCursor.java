package com.meridian.backend.products;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.backend.exception.UnprocessableException;
import com.meridian.backend.products.document.ProductDocument;
import org.bson.types.ObjectId;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Opaque, self-describing cursor for the product listing.
 *
 * <p><b>Shape.</b> {@code base64url(JSON)} — the frontend only ever treats it as
 * "give me the next batch"; nothing in it is a page number.
 *
 * <pre>
 * { "v": 1,                 // format version
 *   "f": "9f2c1a…",         // fingerprint of the query the cursor belongs to
 *   "sf": "created_at",     // Mongo field being sorted on
 *   "sd": "desc",           // direction of that sort
 *   "idd": "desc",          // direction of the unique _id tie-breaker
 *   "sv": 1759276800000,    // last row's sort value (epoch millis / number / string)
 *   "id": "6ac32548…" }     // last row's _id — the tie-breaker that makes ordering stable
 * </pre>
 *
 * <p><b>Why these fields.</b> The listing sorts on a primary field <em>plus</em> a unique
 * {@code _id} tie-breaker (duplicate titles/prices are common), so resuming needs both the
 * last sort value and the last id. {@code sf/sd/idd} make the continuation self-describing:
 * the server does not have to trust that the client still asks for the same sort.
 *
 * <p><b>Safety.</b> The fingerprint {@code f} is a short SHA-256 of the full query state
 * (sort, visibility, category, tags, search term). If the user changes any of them the old
 * cursor no longer matches and is rejected with a {@code 422 invalid_cursor} client error
 * instead of silently returning the wrong slice — a defence in depth behind the frontend,
 * which discards cursors on every filter change.
 *
 * <p>Anything malformed (bad Base64, bad JSON, unknown version, unknown sort field, wrong
 * value type, non-ObjectId id, mismatched fingerprint, oversized payload) is rejected the
 * same way. A broken cursor must never surface as a 500.
 */
public final class ProductCursor {

    public static final int VERSION = 1;

    /** Reject absurd payloads early — a real cursor is well under 200 characters. */
    public static final int MAX_LENGTH = 2048;

    /** Sort fields the listing actually supports; anything else is forged or stale. */
    private static final List<String> ALLOWED_SORT_FIELDS = List.of("created_at", "title", "price");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String fingerprint;
    private final String sortField;
    private final String sortDirection;
    private final String idDirection;
    private final Object sortValue;
    private final String id;

    private ProductCursor(String fingerprint, String sortField, String sortDirection,
                          String idDirection, Object sortValue, String id) {
        this.fingerprint = fingerprint;
        this.sortField = sortField;
        this.sortDirection = sortDirection;
        this.idDirection = idDirection;
        this.sortValue = sortValue;
        this.id = id;
    }

    public String getFingerprint() { return fingerprint; }
    public String getSortField() { return sortField; }
    public String getSortDirection() { return sortDirection; }
    public String getIdDirection() { return idDirection; }
    public Object getSortValue() { return sortValue; }
    public String getId() { return id; }

    public boolean isAscending() { return "asc".equalsIgnoreCase(sortDirection); }
    public boolean idDescending() { return "desc".equalsIgnoreCase(idDirection); }

    /**
     * Stable hash of everything that defines "which result set am I paging through".
     * Two requests with the same query state always produce the same value, so a cursor is
     * only ever valid against the query it was issued for.
     */
    public static String fingerprint(String sort,
                                     String visibility,
                                     String category,
                                     List<String> tags,
                                     String query) {
        String joined = String.join("\u0000",
            nullToEmpty(sort),
            nullToEmpty(visibility),
            nullToEmpty(category),
            String.join(",", tags == null ? List.of() : tags),
            nullToEmpty(query));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(joined.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (Exception ex) {
            // SHA-256 is mandated by the JRE; this can only happen if it is stripped.
            throw new IllegalStateException("Unable to fingerprint the product query", ex);
        }
    }

    /** Builds the cursor that resumes <em>after</em> {@code last}, or {@code null} if impossible. */
    public static String encode(SortSpec spec, String fingerprint, ProductDocument last) {
        if (last == null || last.getId() == null || !ObjectId.isValid(last.getId())) {
            return null;
        }
        Object value;
        switch (spec.mongoField()) {
            case "created_at" -> {
                if (last.getCreatedAt() == null) return null;
                value = last.getCreatedAt().toInstant().toEpochMilli();
            }
            case "price" -> {
                if (last.getPrice() == null) return null;
                value = last.getPrice();
            }
            case "title" -> {
                if (last.getTitle() == null) return null;
                value = last.getTitle();
            }
            default -> {
                return null;
            }
        }

        try {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("v", VERSION);
            node.put("f", fingerprint);
            node.put("sf", spec.mongoField());
            node.put("sd", spec.direction().isAscending() ? "asc" : "desc");
            node.put("idd", "desc");
            if (value instanceof Long longValue) {
                node.put("sv", longValue);
            } else if (value instanceof Double doubleValue) {
                node.put("sv", doubleValue);
            } else {
                node.put("sv", (String) value);
            }
            node.put("id", last.getId());
            String json = MAPPER.writeValueAsString(node);
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Decodes and validates a cursor against the query it is being used for.
     *
     * @throws UnprocessableException (HTTP 422) for every malformed or foreign cursor
     */
    public static ProductCursor decode(String raw, String expectedFingerprint, SortSpec expectedSort) {
        if (raw == null || raw.isBlank()) {
            throw invalid("The cursor is empty.");
        }
        if (raw.length() > MAX_LENGTH) {
            throw invalid("The cursor is too long.");
        }

        JsonNode node;
        try {
            byte[] json = Base64.getUrlDecoder().decode(raw.trim());
            node = MAPPER.readTree(json);
        } catch (IllegalArgumentException | java.io.IOException ex) {
            throw invalid("The cursor is not a valid cursor token.");
        }

        if (node == null || !node.isObject()) {
            throw invalid("The cursor is not a valid cursor token.");
        }

        int version = node.path("v").asInt(-1);
        if (version != VERSION) {
            throw invalid("The cursor version is not supported.");
        }

        String fingerprint = node.path("f").asText("");
        String sortField = node.path("sf").asText("");
        String sortDirection = node.path("sd").asText("");
        String idDirection = node.path("idd").asText("");
        String id = node.path("id").asText("");

        if (!ALLOWED_SORT_FIELDS.contains(sortField)) {
            throw invalid("The cursor refers to an unknown sort field.");
        }
        if (!"asc".equals(sortDirection) && !"desc".equals(sortDirection)) {
            throw invalid("The cursor refers to an unknown sort direction.");
        }
        if (!"asc".equals(idDirection) && !"desc".equals(idDirection)) {
            throw invalid("The cursor refers to an unknown tie-break direction.");
        }
        if (!ObjectId.isValid(id)) {
            throw invalid("The cursor refers to an unknown product position.");
        }
        if (!expectedSort.mongoField().equals(sortField)) {
            throw invalid("The cursor does not match the requested sort order.");
        }
        if (!expectedFingerprint.equals(fingerprint)) {
            throw invalid("The cursor belongs to a different search or filter selection.");
        }

        JsonNode valueNode = node.get("sv");
        Object value;
        if (valueNode == null || valueNode.isNull()) {
            throw invalid("The cursor payload is incomplete.");
        }
        switch (sortField) {
            case "created_at" -> {
                if (!valueNode.isNumber()) throw invalid("The cursor payload is malformed.");
                value = new Date(valueNode.asLong());
            }
            case "price" -> {
                if (!valueNode.isNumber()) throw invalid("The cursor payload is malformed.");
                value = valueNode.asDouble();
            }
            default -> {
                if (!valueNode.isTextual() || valueNode.asText().isEmpty()) {
                    throw invalid("The cursor payload is malformed.");
                }
                value = valueNode.asText();
            }
        }

        return new ProductCursor(fingerprint, sortField, sortDirection, idDirection, value, id);
    }

    private static UnprocessableException invalid(String message) {
        return new UnprocessableException(message, "invalid_cursor", Map.of("field", "cursor"));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
