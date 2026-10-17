package com.gamma.analytics.entity;

import java.util.Map;
import java.util.Objects;

/**
 * One record for the JVM form of {@link EntityGrouping}: its key and the text of each column the spec reads ({@code null}
 * = NULL). The SQL form reads the same values as {@code CAST(column AS VARCHAR)}.
 */
public record EntityRecord(String key, Map<String, String> values) {
    public EntityRecord {
        Objects.requireNonNull(values, "values");
    }

    String value(String column) {
        return values.get(column);
    }
}
