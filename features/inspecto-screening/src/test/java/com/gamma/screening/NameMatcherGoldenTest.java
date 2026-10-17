package com.gamma.screening;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;
import java.util.function.ToDoubleBiFunction;
import org.junit.jupiter.api.Test;

/**
 * Golden pin of the Screening name method's Jaro-Winkler and Match Score, written BEFORE the token metric moved to
 * the Analytics Kernel (ANALYTICS-CORE-1 S1). Two proofs that no figure moves:
 *
 * <ul>
 *   <li>the token metric ({@link #SUBJECT}) equals, bit for bit, {@link #frozenJaroWinkler} — a verbatim copy of
 *       {@code NameMatcher.jaroWinkler} as it stood before the switch — over hand pairs and a seeded corpus that
 *       includes accented letters, empty strings and supplementary-plane characters (surrogate pairs);</li>
 *   <li>{@code NameMatcher.score} over a seeded corpus of name pairs hashes to the digest recorded below.</li>
 * </ul>
 *
 * {@link #SUBJECT} is the one line the switch may change (it names where the metric lives).
 */
class NameMatcherGoldenTest {

    /** The metric under test: NameMatcher's own Jaro-Winkler before the switch. */
    private static final ToDoubleBiFunction<String, String> SUBJECT = NameMatcher::jaroWinkler;

    /** Verbatim copy of NameMatcher.jaroWinkler before ANALYTICS-CORE-1 S1 (prefix scale 0.1, prefix up to 4). */
    static double frozenJaroWinkler(String s, String t) {
        if (s.equals(t)) return s.isEmpty() ? 0.0 : 1.0;
        int ls = s.length(), lt = t.length();
        if (ls == 0 || lt == 0) return 0.0;
        int window = Math.max(0, Math.max(ls, lt) / 2 - 1);
        boolean[] ms = new boolean[ls], mt = new boolean[lt];
        int matches = 0;
        for (int i = 0; i < ls; i++) {
            int lo = Math.max(0, i - window), hi = Math.min(lt - 1, i + window);
            for (int j = lo; j <= hi; j++) {
                if (mt[j] || s.charAt(i) != t.charAt(j)) continue;
                ms[i] = mt[j] = true;
                matches++;
                break;
            }
        }
        if (matches == 0) return 0.0;
        int transpositions = 0;
        for (int i = 0, j = 0; i < ls; i++) {
            if (!ms[i]) continue;
            while (!mt[j]) j++;
            if (s.charAt(i) != t.charAt(j)) transpositions++;
            j++;
        }
        double m = matches;
        double jaro = (m / ls + m / lt + (m - transpositions / 2.0) / m) / 3.0;
        int prefix = 0;
        for (int i = 0; i < Math.min(4, Math.min(ls, lt)) && s.charAt(i) == t.charAt(i); i++) prefix++;
        return jaro + prefix * 0.1 * (1.0 - jaro);
    }

    private static final String[][] HAND = {
            {"martha", "marhta"}, {"dwayne", "duane"}, {"dixon", "dicksonx"}, {"abc", "xyz"}, {"", ""}, {"a", ""},
            {"", "b"}, {"a", "a"}, {"ab", "ba"}, {"abcdef", "bcadef"}, {"abcxyzqq", "abcdefgh"}, {"jose", "josé"},
            {"mueller", "müller"}, {"kadafi", "qadhafi"}, {"😀ab", "😁ab"}, {"vladimir", "vladimirovich"},
    };

    /** Characters the seeded corpus draws from: small alphabet (many matches), accents, a surrogate pair. */
    private static final String[] ALPHABET = {"a", "b", "c", "d", "e", "é", "ü", "x", " ", "😀", "ж"};

    private static String randomText(Random r, int maxLen) {
        int n = r.nextInt(maxLen + 1);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(ALPHABET[r.nextInt(ALPHABET.length)]);
        return b.toString();
    }

    @Test
    void theTokenMetricEqualsThePreSwitchJaroWinklerBitForBit() {
        for (String[] p : HAND) {
            assertEquals(frozenJaroWinkler(p[0], p[1]), SUBJECT.applyAsDouble(p[0], p[1]), 0.0, p[0] + " / " + p[1]);
            assertEquals(frozenJaroWinkler(p[1], p[0]), SUBJECT.applyAsDouble(p[1], p[0]), 0.0, p[1] + " / " + p[0]);
        }
        Random r = new Random(20261017);
        for (int k = 0; k < 20_000; k++) {
            String a = randomText(r, 12), b = randomText(r, 12);
            assertEquals(frozenJaroWinkler(a, b), SUBJECT.applyAsDouble(a, b), 0.0, a + " / " + b);
        }
        // the published reference figures stay where they were
        assertEquals(0.9611111111111111, SUBJECT.applyAsDouble("martha", "marhta"), 0.0);
        assertEquals(0.8400000000000001, SUBJECT.applyAsDouble("dwayne", "duane"), 0.0);
        assertEquals(0.8133333333333332, SUBJECT.applyAsDouble("dixon", "dicksonx"), 0.0);
    }

    private static final String[] GIVEN = {"vladimir", "mohammed", "muhammad", "jose", "josé", "anna", "ivan", "sergei",
            "sergey", "ahmed", "ahmad", "osama", "usama", "abdul", "hans", "john", "jon", "dr", "Виктор", "Pаvel"};
    private static final String[] FAMILY = {"putin", "al-rashid", "al rashid", "müller", "mueller", "kowalski",
            "kowalska", "petrov", "petrova", "hassan", "hasan", "bin ladin", "bin laden", "gaddafi", "qadhafi",
            "gruber", "smith", "smyth", "lukashenko", "lukashenka", "rahman"};

    private static String randomName(Random r) {
        StringBuilder b = new StringBuilder(GIVEN[r.nextInt(GIVEN.length)]);
        int parts = 1 + r.nextInt(3);
        for (int i = 0; i < parts; i++) b.append(r.nextBoolean() ? ' ' : ", ").append(FAMILY[r.nextInt(FAMILY.length)]);
        return r.nextInt(5) == 0 ? b.toString().toUpperCase(java.util.Locale.ROOT) : b.toString();
    }

    @Test
    void theMatchScoreOverASeededCorpusIsUnchanged() throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        Random r = new Random(4242);
        for (int k = 0; k < 5_000; k++) {
            String a = randomName(r), b = randomName(r);
            sha.update((a + "|" + b + "|" + Double.doubleToLongBits(NameMatcher.score(a, b)) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(GOLDEN_SCORE_DIGEST, HexFormat.of().formatHex(sha.digest()));
        assertEquals(1.0, NameMatcher.score("Putin, Vladimir", "Vladimir Putin"), 0.0);
        assertEquals(0.95, NameMatcher.score("Muammar Gaddafi", "Moammar Qadhafi"), 0.0);
    }

    private static final String GOLDEN_SCORE_DIGEST = "cf25f5c2cde80f85c2d209eb59bb795d7b838284c594e24273486cf5af2a033a";
}
