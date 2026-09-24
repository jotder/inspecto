package com.gamma.intelligence.triage;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministic, dependency-free similarity for Triage Run recall (AGT-5 P5). Scores two symptom texts (see
 * {@link TriageRun#symptomText()}) by Jaccard overlap of their normalized token sets — the plan's
 * "deterministic token-overlap first; embeddings only if warranted" (§8 P5). No model, no vectors: a
 * restart-safe, unit-testable baseline that surfaces prior Triage Runs sharing signal type / subject /
 * symptom vocabulary. An embedding upgrade can later replace {@link #score} behind the same call.
 */
public final class TriageRunSimilarity {

    /** Tokens shorter than this are dropped (matches the reflex-layer DocRetriever's noise floor). */
    private static final int MIN_TOKEN = 3;

    private TriageRunSimilarity() {
    }

    /** Normalized token set: lowercased, split on non-alphanumerics, stop-short tokens removed. */
    public static Set<String> tokens(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return out;
        for (String t : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (t.length() >= MIN_TOKEN) out.add(t);
        }
        return out;
    }

    /**
     * Jaccard similarity of two token sets — {@code |A ∩ B| / |A ∪ B|}, in {@code [0,1]}. Two empty
     * fingerprints score 0 (nothing to match on), never 1, so a content-free Triage Run never looks similar.
     */
    public static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        int intersection = 0;
        for (String t : a) if (b.contains(t)) intersection++;
        int union = a.size() + b.size() - intersection;
        return union == 0 ? 0.0 : (double) intersection / union;
    }

    /** Convenience: similarity of two symptom texts. */
    public static double score(String queryText, String candidateText) {
        return jaccard(tokens(queryText), tokens(candidateText));
    }
}
