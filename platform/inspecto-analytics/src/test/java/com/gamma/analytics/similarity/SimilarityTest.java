package com.gamma.analytics.similarity;

import static org.junit.jupiter.api.Assertions.*;

import com.gamma.analytics.similarity.JaroWinkler.Transpositions;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Hand-computed figures for every similarity definition (ANALYTICS-CORE-1 S1). */
class SimilarityTest {

    @Test
    void jaroAndJaroWinklerMatchThePublishedReferenceFigures() {
        // Winkler's reference pairs
        assertEquals(17.0 / 18, Similarity.jaro("martha", "marhta"), 1e-15);            // m=6, x=2 → h=1
        assertEquals(0.9611111111111111, Similarity.jaroWinkler("martha", "marhta"), 1e-15);
        assertEquals(0.84, Similarity.jaroWinkler("dwayne", "duane"), 1e-12);
        assertEquals(0.8133333333333332, Similarity.jaroWinkler("dixon", "dicksonx"), 1e-12);
        assertEquals(0.0, Similarity.jaroWinkler("abc", "xyz"));
        assertEquals(1.0, Similarity.jaroWinkler("same", "same"));
    }

    @Test
    void anEmptySideScoresZeroEvenWhenBothAreEmpty() {
        assertEquals(0.0, Similarity.jaro("", ""));
        assertEquals(0.0, Similarity.jaroWinkler("", "a"));
        assertEquals(0.0, Similarity.levenshteinSimilarity("", ""));
        assertEquals(0.0, Similarity.levenshteinSimilarity("a", ""));
        assertEquals(0.0, Similarity.jaccard(Set.of(), Set.of()));
        assertEquals(0.0, Similarity.tokenJaccard("  ", "a"));
        assertEquals(0, Similarity.levenshtein("", ""));
    }

    @Test
    void oddTranspositionCountsAreHalvedExactlyOrFlooredByMode() {
        // abcdef / bcadef: window 2, m = 6, the matched orders abcdef vs bcadef differ in x = 3 positions
        assertEquals((2 + (6 - 1.5) / 6) / 3, Similarity.jaro("abcdef", "bcadef"), 1e-15);   // 0.91666…
        assertEquals((2 + (6 - 1.0) / 6) / 3, JaroWinkler.DUCKDB.jaro("abcdef", "bcadef"), 1e-15); // 0.94444…
    }

    @Test
    void thePrefixBoostAppliesOnlyAboveTheThreshold() {
        double jaro = 7.0 / 12;   // abcxyzqq / abcdefgh: m = 3 of 8 each → (3/8 + 3/8 + 1) / 3
        assertEquals(jaro, Similarity.jaro("abcxyzqq", "abcdefgh"), 1e-15);
        assertEquals(jaro, Similarity.jaroWinkler("abcxyzqq", "abcdefgh"), 1e-15, "0.583 ≤ 0.7: no boost");
        JaroWinkler always = new JaroWinkler(TextUnit.CODE_POINT, Transpositions.HALF, 0.1, 4, 0.0);
        assertEquals(jaro + 3 * 0.1 * (1 - jaro), always.similarity("abcxyzqq", "abcdefgh"), 1e-15);
        JaroWinkler shortPrefix = new JaroWinkler(TextUnit.CODE_POINT, Transpositions.HALF, 0.2, 2, 0.7);
        double j = Similarity.jaro("martha", "marhta");
        assertEquals(j + 2 * 0.2 * (1 - j), shortPrefix.similarity("martha", "marhta"), 1e-15);
    }

    @Test
    void theUnitDecidesWhatOneCharacterIs() {
        String e = "é", emojiA = "😀x", emojiB = "😁x";
        assertEquals(1, Similarity.levenshtein(e, "e"));
        assertEquals(1, Similarity.levenshtein(e, "e", TextUnit.UTF16_CHAR));
        assertEquals(2, Similarity.levenshtein(e, "e", TextUnit.UTF8_BYTE));   // C3 A9 vs 65
        assertEquals(1, Similarity.levenshtein(emojiA, emojiB));                 // one code point differs
        assertEquals(1, Similarity.levenshtein(emojiA, emojiB, TextUnit.UTF16_CHAR)); // the low surrogate differs
        assertEquals(1, Similarity.levenshtein(emojiA, emojiB, TextUnit.UTF8_BYTE));  // F0 9F 98 80 vs …81
        assertEquals(0.5, Similarity.levenshteinSimilarity(emojiA, emojiB), 1e-15);
        assertEquals(1 - 1.0 / 3, Similarity.levenshteinSimilarity(emojiA, emojiB, TextUnit.UTF16_CHAR), 1e-15);
        assertEquals(0.8, Similarity.levenshteinSimilarity(emojiA, emojiB, TextUnit.UTF8_BYTE), 1e-15);
    }

    @Test
    void levenshteinCountsInsertionsDeletionsAndSubstitutions() {
        assertEquals(3, Similarity.levenshtein("kitten", "sitting"));
        assertEquals(1 - 3.0 / 7, Similarity.levenshteinSimilarity("kitten", "sitting"), 1e-15);
        assertEquals(4, Similarity.levenshtein("", "abcd"));
        assertEquals(2, Similarity.levenshtein("ab", "ba"));
        assertEquals(1.0, Similarity.levenshteinSimilarity("same", "same"));
    }

    @Test
    void jaccardIsIntersectionOverUnionOfDistinctTokens() {
        assertEquals(0.5, Similarity.jaccard(Set.of("aaa", "bbb", "ccc"), Set.of("bbb", "ccc", "ddd")), 1e-15);
        assertEquals(1.0, Similarity.tokenJaccard("a b a", "b  a"), 1e-15);
        assertEquals(1.0 / 3, Similarity.tokenJaccard("acme trading", "acme\tholding"), 1e-15);
        assertEquals(0.0, Similarity.tokenJaccard("Acme", "acme"), "no case folding inside the metric");
        assertEquals(List.of("x", "y"), List.copyOf(Similarity.tokens("\n x \u000B y x ")));
        assertEquals(List.of("a b"), List.copyOf(Similarity.tokens("a b")), "no-break space is not whitespace");
    }

    @Test
    void normalizeStripsAccentsFoldsCaseAndCollapsesWhitespaceExactlyAsStated() {
        assertEquals("creme brulee", Similarity.normalize("  Crème\tBRÛLÉE \n"));
        assertEquals("angstrom a", Similarity.normalize("Ångstrom Å"));   // Å and the Ångström sign → a
        assertEquals("istanbul", Similarity.normalize("İstanbul"));                 // dotted capital I → i, no stray mark
        assertEquals("σασ", Similarity.normalize("ΣΑΣ"), "simple mapping: never the final sigma");
        assertEquals("иосиф", Similarity.normalize("Йосиф"), "the breve is a mark");
        assertEquals("straße ø ł æ", Similarity.normalize("Straße Ø Ł Æ"), "letters without a decomposition stay");
        assertEquals("ﬁx a b", Similarity.normalize("ﬁX A B"), "compatibility forms stay");
        assertEquals("", Similarity.normalize(" \t\r\n\f\u000B "));
        assertThrows(NullPointerException.class, () -> Similarity.normalize(null));
    }

    @Test
    void instancesRefuseOutOfRangeKnobs() {
        assertThrows(IllegalArgumentException.class, () -> new JaroWinkler(TextUnit.CODE_POINT, Transpositions.HALF, 0.3, 4, 0.7));
        assertThrows(IllegalArgumentException.class, () -> new JaroWinkler(TextUnit.CODE_POINT, Transpositions.HALF, 0.1, 5, 0.7));
        assertThrows(IllegalArgumentException.class, () -> new JaroWinkler(TextUnit.CODE_POINT, Transpositions.HALF, 0.1, 4, 1.5));
        assertThrows(NullPointerException.class, () -> new JaroWinkler(null, Transpositions.HALF, 0.1, 4, 0.7));
    }

    @Test
    void methodsParseFromTheirConfigNames() {
        assertEquals(SimilaritySql.Method.JARO_WINKLER, SimilaritySql.Method.parse("jaro_winkler"));
        assertEquals(SimilaritySql.Method.TOKEN_JACCARD, SimilaritySql.Method.parse(" Token_Jaccard "));
        assertNull(SimilaritySql.Method.parse("soundex"));
        assertNull(SimilaritySql.Method.parse(null));
    }
}
