package com.gamma.analytics.entity;

import com.gamma.analytics.similarity.Similarity;
import com.gamma.analytics.similarity.SimilaritySql;
import java.util.Locale;
import java.util.Objects;

/**
 * How one key column's value is normalised before it is compared for equality (entity grouping, ANALYTICS-CORE-1 S2).
 * Each has a JVM form ({@link #apply}) and a DuckDB form ({@link #sql}) held equal by {@code EntityGroupingParityTest}.
 *
 * <p><b>One empty rule:</b> a value that is NULL, or empty after normalising, is <b>absent</b> — it never equals anything,
 * so blanks and stripped-away placeholders can never pull records together.
 */
public enum Normalization {
    /** The similarity kit's text normalisation ({@link Similarity#normalize}): accents stripped, lower-cased, runs of ASCII whitespace to one space, ends trimmed. The default. */
    TEXT,
    /** ASCII digits only: every other character removed ({@code +44 (20) 7946-0000} → {@code 442079460000}). */
    DIGITS,
    /** Compared as written (only the empty rule applies). */
    NONE;

    /** The config spelling: {@code text}, {@code digits}, {@code none}. */
    public String configName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The normalised value, or {@code null} when absent (NULL in, or empty after normalising). */
    public String apply(String value) {
        if (value == null) return null;
        String out = switch (this) {
            case TEXT -> Similarity.normalize(value);
            case DIGITS -> {
                StringBuilder b = new StringBuilder(value.length());
                for (int i = 0; i < value.length(); i++) {
                    char c = value.charAt(i);
                    if (c >= '0' && c <= '9') b.append(c);
                }
                yield b.toString();
            }
            case NONE -> value;
        };
        return out.isEmpty() ? null : out;
    }

    /** The DuckDB form of {@link #apply} over a VARCHAR SQL expression: NULL when absent. */
    public String sql(String expr) {
        Objects.requireNonNull(expr, "expr");
        String out = switch (this) {
            case TEXT -> SimilaritySql.normalize(expr);
            case DIGITS -> "regexp_replace(" + expr + ", '[^0-9]', '', 'g')";
            case NONE -> expr;
        };
        return "nullif(" + out + ", '')";
    }
}
