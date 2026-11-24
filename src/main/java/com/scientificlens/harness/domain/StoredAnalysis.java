package com.scientificlens.harness.domain;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.util.regex.Pattern;

public record StoredAnalysis(
        int schemaVersion,
        String id,
        String savedAt,
        String updatedAt,
        String title,
        String notes,
        String origin,
        HarnessResult result) {
    private static final Pattern ID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    public StoredAnalysis {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("Unsupported stored report schema version: " + schemaVersion);
        }
        if (id == null || !ID.matcher(id).matches() || !UUID.fromString(id).toString().equals(id)) {
            throw new IllegalArgumentException("Report ID must be a canonical lowercase UUID");
        }
        validateTimestamp(savedAt, "saved_at");
        validateTimestamp(updatedAt, "updated_at");
        if (title == null || title.isBlank() || title.codePointCount(0, title.length()) > 160) {
            throw new IllegalArgumentException("Title must contain 1 to 160 Unicode code points");
        }
        title = title.strip();
        if (notes == null || notes.length() > 64_000) {
            throw new IllegalArgumentException("Notes must contain at most 64000 characters");
        }
        if (!"generated".equals(origin) && !"imported".equals(origin)) {
            throw new IllegalArgumentException("Report origin must be generated or imported");
        }
        if (result == null) {
            throw new IllegalArgumentException("Stored report must contain a complete result");
        }
    }

    private static void validateTimestamp(String value, String field) {
        try {
            if (value == null) {
                throw new IllegalArgumentException(field + " must be an ISO8601 Instant");
            }
            Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " must be an ISO8601 Instant", e);
        }
    }
}
