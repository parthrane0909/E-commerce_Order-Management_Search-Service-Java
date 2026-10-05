package com.meridian.backend.products;

import org.springframework.data.domain.Sort;

/**
 * One selectable listing order.
 *
 * <p>Three names for the same thing, because they are used in three different places:
 * <ul>
 *   <li>{@code key}      — the public {@code ?sort=} value and the fingerprint input;</li>
 *   <li>{@code property} — the <em>domain</em> property, which Spring Data maps through
 *                          {@code @Field} when building {@code Sort.by(...)}. Keeping this
 *                          exactly as before preserves the existing sort behaviour;</li>
 *   <li>{@code mongoField} — the literal MongoDB field name, used inside the cursor so the
 *                          continuation predicate addresses the stored field directly
 *                          instead of relying on mapping.</li>
 * </ul>
 * The unique {@code _id} DESC tie-breaker is not part of this record: every order appends it,
 * so duplicate titles/prices can never make paging unstable.
 */
public record SortSpec(String key, String property, String mongoField, Sort.Direction direction) {
}
