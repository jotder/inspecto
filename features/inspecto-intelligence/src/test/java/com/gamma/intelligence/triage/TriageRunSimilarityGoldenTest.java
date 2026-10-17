package com.gamma.intelligence.triage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Random;
import java.util.Set;
import java.util.function.ToDoubleBiFunction;
import org.junit.jupiter.api.Test;

/**
 * Golden pin of Triage Run recall similarity, written BEFORE the Jaccard moved to the Analytics Kernel
 * (ANALYTICS-CORE-1 S1): the set Jaccard ({@link #SUBJECT}) equals {@link #frozenJaccard} — a verbatim copy of
 * {@code TriageRunSimilarity.jaccard} before the switch — bit for bit, and {@code TriageRunSimilarity.score} over a
 * seeded corpus of symptom texts hashes to the digest recorded below. {@link #SUBJECT} is the one line the switch may
 * change.
 */
class TriageRunSimilarityGoldenTest {

    private static final ToDoubleBiFunction<Set<String>, Set<String>> SUBJECT = TriageRunSimilarity::jaccard;

    /** Verbatim copy of TriageRunSimilarity.jaccard before ANALYTICS-CORE-1 S1. */
    static double frozenJaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        int intersection = 0;
        for (String t : a) if (b.contains(t)) intersection++;
        int union = a.size() + b.size() - intersection;
        return union == 0 ? 0.0 : (double) intersection / union;
    }

    private static final String[] WORDS = {"pipeline", "batch", "failed", "orders", "billing", "schema", "drift",
            "sequence", "gap", "database", "late", "file", "missing", "duplicate", "volume", "drop", "id", "42", "ok"};

    private static String randomText(Random r) {
        int n = r.nextInt(8);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            String w = WORDS[r.nextInt(WORDS.length)];
            b.append(r.nextInt(4) == 0 ? w.toUpperCase(java.util.Locale.ROOT) : w).append(r.nextBoolean() ? " " : "-");
        }
        return b.toString();
    }

    @Test
    void theSetJaccardEqualsThePreSwitchCodeBitForBit() {
        Random r = new Random(20261017);
        for (int k = 0; k < 20_000; k++) {
            Set<String> a = new HashSet<>(), b = new HashSet<>();
            for (int i = r.nextInt(6); i > 0; i--) a.add(WORDS[r.nextInt(WORDS.length)]);
            for (int i = r.nextInt(6); i > 0; i--) b.add(WORDS[r.nextInt(WORDS.length)]);
            assertEquals(frozenJaccard(a, b), SUBJECT.applyAsDouble(a, b), 0.0, a + " / " + b);
        }
        assertEquals(0.0, SUBJECT.applyAsDouble(Set.of(), Set.of()), 0.0);
        assertEquals(0.5, SUBJECT.applyAsDouble(Set.of("aaa", "bbb", "ccc"), Set.of("bbb", "ccc", "ddd")), 0.0);
    }

    @Test
    void theRecallScoreOverASeededCorpusIsUnchanged() throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        Random r = new Random(4242);
        for (int k = 0; k < 5_000; k++) {
            String a = randomText(r), b = randomText(r);
            sha.update((a + "|" + b + "|" + Double.doubleToLongBits(TriageRunSimilarity.score(a, b)) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(GOLDEN_SCORE_DIGEST, HexFormat.of().formatHex(sha.digest()));
    }

    private static final String GOLDEN_SCORE_DIGEST = "e0cdfc75fc9c1b9c940508c574ff4fd08f52d9ef3d8ef8e989d5e8879cf18724";
}
