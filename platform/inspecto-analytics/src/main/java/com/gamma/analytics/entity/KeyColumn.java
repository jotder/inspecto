package com.gamma.analytics.entity;

import java.util.Objects;

/**
 * One column of a blocking key or a match condition, with the {@link Normalization} applied before comparing. The column
 * name is the caller's, validated by the caller; the SQL form quotes it.
 */
public record KeyColumn(String column, Normalization normalization) {
    public KeyColumn {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(normalization, "normalization");
        if (column.isBlank()) throw new IllegalArgumentException("a key column needs a name");
    }
}
