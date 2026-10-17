package com.gamma.analytics.entity;

import com.gamma.analytics.similarity.SimilaritySql;
import java.util.Objects;

/**
 * The optional similarity <b>review</b> of a grouping: each record's best-scoring record of <b>another</b> resolution
 * group in its block, by the similarity kit, with a review flag at {@code threshold}.
 *
 * <p>⛔ <b>A candidate, never an identity (decision D-M1).</b> It is not a {@link MatchCondition} and cannot become one:
 * the groups are final before any score is computed, and a score only fills the candidate columns a person reviews.
 *
 * @param column    the text column scored (its text form)
 * @param method    the similarity kit's method
 * @param normalize the kit's normalisation on both sides first
 * @param threshold the review flag is {@code score >= threshold}, 0..1
 */
public record SimilarityCandidate(String column, SimilaritySql.Method method, boolean normalize, double threshold) {
    public SimilarityCandidate {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(method, "method");
        if (!(threshold >= 0 && threshold <= 1))
            throw new IllegalArgumentException("a candidate threshold must be between 0 and 1, got " + threshold);
    }
}
