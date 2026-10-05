package com.meridian.backend.common.util;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public final class DateUtils {

    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private DateUtils() {}

    public static OffsetDateTime utcNow() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    public static String toIsoString(OffsetDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.format(ISO_FORMATTER);
    }

    public static OffsetDateTime startOfDay(java.time.LocalDate date) {
        return date.atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    public static OffsetDateTime endOfDay(java.time.LocalDate date) {
        return date.atTime(23, 59, 59, 999_999_999).atOffset(ZoneOffset.UTC);
    }
}