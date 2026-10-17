package com.gamma.analytics.similarity;

import java.util.Locale;
import java.util.Objects;

/**
 * The DuckDB form of the similarity kit (ANALYTICS-CORE-1 S1): SQL expressions built on DuckDB 1.5.6's built-in
 * string functions, for a caller that scores two columns in a query (the {@code quality.match.fuzzy} Step). Every
 * argument is caller-built, already-safe SQL (a quoted column, a literal); this class adds no identifier of its own.
 *
 * <p>Each {@link Method} has a JVM twin, {@link Method#score}, that returns the same value — bit for bit on the
 * parity corpus — so a figure seen in a Dataset can be recomputed in the JVM. DuckDB's string built-ins work on
 * <b>UTF-8 bytes</b>, so the twin uses {@link TextUnit#UTF8_BYTE}: an accented letter is two units unless the text is
 * normalised first (accents stripped).
 *
 * <p>NULL on either side gives NULL. An empty side gives 0 (the kit's one empty rule, {@link Similarity}).
 */
public final class SimilaritySql {

    private SimilaritySql() {
    }

    /** The ASCII whitespace class as an RE2 pattern, the same six characters as {@link Similarity#WHITESPACE_CLASS}. */
    private static final String WS = "[ \\t\\n\\x0B\\f\\r]";

    /** A similarity method, its SQL and its JVM twin. Config names are the lower-case enum names. */
    public enum Method {
        /** {@code jaro_similarity(a, b)}; twin {@code JaroWinkler.DUCKDB.jaro}. */
        JARO,
        /** {@code jaro_winkler_similarity(a, b)} — p = 0.1, prefix up to 4, boost above 0.7; twin {@code JaroWinkler.DUCKDB}. */
        JARO_WINKLER,
        /** {@code 1 − levenshtein(a, b) / greatest(strlen(a), strlen(b))}, bytes; twin {@code levenshteinSimilarity(…, UTF8_BYTE)}. */
        LEVENSHTEIN,
        /** Jaccard of the two texts' whitespace-separated token sets; twin {@link Similarity#tokenJaccard}. */
        TOKEN_JACCARD;

        /** The config spelling: {@code jaro}, {@code jaro_winkler}, {@code levenshtein}, {@code token_jaccard}. */
        public String configName() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The method named {@code name} (case-insensitive), or {@code null} when none is. */
        public static Method parse(String name) {
            if (name == null) return null;
            for (Method m : values()) if (m.configName().equalsIgnoreCase(name.trim())) return m;
            return null;
        }

        /** The JVM twin of {@link #sql}: the same score DuckDB computes for {@code a} and {@code b}. */
        public double score(String a, String b) {
            return switch (this) {
                case JARO -> JaroWinkler.DUCKDB.jaro(a, b);
                case JARO_WINKLER -> JaroWinkler.DUCKDB.similarity(a, b);
                case LEVENSHTEIN -> Similarity.levenshteinSimilarity(a, b, TextUnit.UTF8_BYTE);
                case TOKEN_JACCARD -> Similarity.tokenJaccard(a, b);
            };
        }

        /** The DOUBLE score of the two SQL expressions, 0..1; NULL when either is NULL. */
        public String sql(String a, String b) {
            Objects.requireNonNull(a, "a");
            Objects.requireNonNull(b, "b");
            return switch (this) {
                case JARO -> "jaro_similarity(" + a + ", " + b + ")";
                case JARO_WINKLER -> "jaro_winkler_similarity(" + a + ", " + b + ")";
                case LEVENSHTEIN -> "(CASE WHEN " + a + " = '' OR " + b + " = '' THEN 0.0 ELSE 1.0 - levenshtein(" + a + ", " + b
                        + ") / greatest(strlen(" + a + "), strlen(" + b + ")) END)";
                case TOKEN_JACCARD -> {
                    String ta = tokensSql(a), tb = tokensSql(b);
                    yield "(CASE WHEN len(" + ta + ") = 0 OR len(" + tb + ") = 0 THEN 0.0 ELSE len(list_intersect(" + ta + ", " + tb
                            + ")) / len(list_distinct(list_concat(" + ta + ", " + tb + "))) END)";
                }
            };
        }
    }

    /** The distinct whitespace-separated tokens of {@code expr} as a VARCHAR list (order not significant). */
    static String tokensSql(String expr) {
        return "list_distinct(regexp_extract_all(" + expr + ", '[^" + WS.substring(1) + "+'))";
    }

    /**
     * The normalised text, {@link Similarity#normalise}'s DuckDB form: {@code strip_accents}, then {@code lower}, then
     * every run of ASCII whitespace to one space, then the ends trimmed.
     */
    public static String normalise(String expr) {
        Objects.requireNonNull(expr, "expr");
        return "trim(regexp_replace(lower(strip_accents(" + expr + ")), '" + WS + "+', ' ', 'g'), ' ')";
    }
}
