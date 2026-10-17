package com.gamma.analytics.entity;

import java.util.List;
import java.util.Objects;

/**
 * A <b>deterministic</b> match condition: two records match when <b>every</b> listed column is present on both and equal
 * after its {@link Normalization}. The only thing that can join two records into one resolution group.
 *
 * <p>⛔ <b>Identity is never asserted by similarity (decision D-M1).</b> This type has no method, score or threshold, and
 * nothing in the kernel converts a {@link SimilarityCandidate} into one: a similarity score can only fill the separate
 * candidate columns for review.
 */
public record MatchCondition(List<KeyColumn> columns) {
    public MatchCondition {
        Objects.requireNonNull(columns, "columns");
        if (columns.isEmpty()) throw new IllegalArgumentException("a match condition needs at least one column");
        columns = List.copyOf(columns);
    }

    /** A condition over {@code columns}, all with the same normalisation. */
    public static MatchCondition of(Normalization normalization, String... columns) {
        return new MatchCondition(java.util.Arrays.stream(columns).map(c -> new KeyColumn(c, normalization)).toList());
    }
}
