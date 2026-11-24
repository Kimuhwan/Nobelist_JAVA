package com.scientificlens.harness.domain;

import java.util.List;
import java.util.Objects;

/** Validation helpers shared by the immutable domain records (mirrors pydantic min_length rules). */
public final class Checks {
    private Checks() {}

    public static String text(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }

    public static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public static List<String> items(List<String> values, String field) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(field + " must contain at least one item");
        }
        return values.stream().map(v -> text(v, field + "[]")).toList();
    }

    public static <T> List<T> nonEmpty(List<T> values, String field) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(field + " must contain at least one item");
        }
        values.forEach(v -> Objects.requireNonNull(v, field + "[]"));
        return List.copyOf(values);
    }
}
