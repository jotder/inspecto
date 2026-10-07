package com.gamma.intelligence.triage;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Unit coverage for the deterministic Jaccard Triage Run-recall scorer (AGT-5 P5). */
class TriageRunSimilarityTest {

    @Test
    void tokensLowercasesSplitsAndDropsShortTokens() {
        // "id"/"42" are < 3 chars and dropped; "gap" survives; casing normalised; duplicates collapsed.
        Set<String> t = TriageRunSimilarity.tokens("ID 42 Sequence-GAP gap");
        assertEquals(Set.of("sequence", "gap"), t);
    }

    @Test
    void tokensOfNullOrEmptyIsEmpty() {
        assertTrue(TriageRunSimilarity.tokens(null).isEmpty());
        assertTrue(TriageRunSimilarity.tokens("  !! 12 ").isEmpty(), "only noise/short tokens ⇒ empty");
    }

    @Test
    void jaccardOfIdenticalSetsIsOne() {
        Set<String> a = TriageRunSimilarity.tokens("orders pipeline failed schema");
        assertEquals(1.0, TriageRunSimilarity.jaccard(a, a), 1e-9);
    }

    @Test
    void jaccardOfDisjointSetsIsZero() {
        assertEquals(0.0,
                TriageRunSimilarity.jaccard(TriageRunSimilarity.tokens("alpha bravo"), TriageRunSimilarity.tokens("charlie delta")),
                1e-9);
    }

    @Test
    void jaccardComputesIntersectionOverUnion() {
        // {aaa,bbb,ccc} vs {bbb,ccc,ddd} → ∩ = 2, ∪ = 4 → 0.5
        double s = TriageRunSimilarity.jaccard(
                TriageRunSimilarity.tokens("aaa bbb ccc"),
                TriageRunSimilarity.tokens("bbb ccc ddd"));
        assertEquals(0.5, s, 1e-9);
    }

    @Test
    void emptyFingerprintScoresZeroNotOne() {
        assertEquals(0.0, TriageRunSimilarity.jaccard(Set.of(), Set.of()), 1e-9,
                "two content-free Triage Runs must not look identical");
    }

    @Test
    void scoreWiresTokensThroughJaccard() {
        double s = TriageRunSimilarity.score("database sequence gap detected", "sequence gap in database");
        assertTrue(s > 0.5, "high symptom-vocabulary overlap ⇒ high score, got " + s);
        assertEquals(0.0, TriageRunSimilarity.score("totally unrelated words here", "nothing common between"), 1e-9);
    }
}
