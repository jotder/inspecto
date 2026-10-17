package com.gamma.pipeline.exec;

import com.gamma.analytics.similarity.SimilaritySql;
import com.gamma.analytics.similarity.SimilaritySql.Method;
import com.gamma.etl.Identifiers;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.gamma.util.SqlIdent.q;
import static com.gamma.util.SqlIdent.sqlStr;

/**
 * The config grammar of {@code transform.similarity} ({@code steps:} kind {@code similarity}, catalog
 * {@code quality.match.fuzzy}, ANALYTICS-CORE-1 S1), parsed once so the run ({@link RowShaper}), the recipe compiler
 * and the save-time check refuse the same configs with the same words. The score is the Analytics Kernel's
 * ({@link SimilaritySql}): DuckDB's built-in string similarity, with a JVM twin.
 *
 * <p>⛔ <b>A candidate, never an identity</b> (D-M1). The Step adds a score and, with a {@code threshold}, a boolean
 * saying the pair is worth a look. It never merges, links or deduplicates rows, and nothing downstream may read the
 * boolean as "the same entity".
 *
 * <p>🔴 <b>No authored text reaches the SQL as text.</b> Column names are validated against {@link Identifiers} and
 * double-quoted; the method is a fixed word; the threshold is a parsed number re-printed.
 */
public final class SimilarityStep {

    private SimilarityStep() {}

    static final String KIND = "transform.similarity";

    /** The default score column. */
    public static final String DEFAULT_SCORE_COLUMN = "similarity";
    /** The default candidate column, added only with a {@code threshold}. */
    public static final String DEFAULT_CANDIDATE_COLUMN = "similarity_candidate";
    /** The scratch name of the score when {@code keep_score: false}; never left in the output. */
    static final String SCRATCH_SCORE = "__similarity_score";

    /**
     * The longest value, in characters of the raw text (before {@code normalize}), either side of a pair may hold.
     * Levenshtein and Jaro cost grows with the product of the two lengths, so one huge cell can stall a run. A longer
     * value <b>fails the run</b> through DuckDB's {@code error()}, naming the columns and the cap - as the kernel's
     * other caps fail closed (entity cap D-AD10, the resample bucket cap). A NULL score was not chosen: NULL already
     * means "a side is NULL", and a silent NULL would hide the refusal. 1,000 characters is far above any name,
     * address or identifier this Step is meant for.
     */
    public static final int MAX_TEXT_LENGTH = 1_000;

    private static final Set<String> KEYS = Set.of("left", "right", "method", "normalize", "score_column",
            "threshold", "candidate_column", "keep_score");

    /**
     * A parsed config. {@code threshold} is {@code null} when no candidate column is wanted.
     *
     * @param normalize strip accents, fold case and collapse whitespace on both sides first ({@link SimilaritySql#normalize})
     */
    public record Spec(String left, String right, Method method, boolean normalize, String scoreColumn,
                       Double threshold, String candidateColumn, boolean keepScore) {

        /** The columns the inbound relation must carry. */
        public List<String> inputColumns() {
            return List.of(left, right);
        }

        /** The columns the Step adds, in order. */
        public List<String> addedColumns() {
            List<String> out = new ArrayList<>();
            if (keepScore) out.add(scoreColumn);
            if (threshold != null) out.add(candidateColumn);
            return out;
        }

        /**
         * The score expression over the two (quoted, VARCHAR-cast, optionally normalized) columns; a raw value longer
         * than {@link #MAX_TEXT_LENGTH} characters fails the statement.
         */
        String scoreSql() {
            String a = "CAST(" + q(left) + " AS VARCHAR)", b = "CAST(" + q(right) + " AS VARCHAR)";
            String tooLong = "length(" + a + ") > " + MAX_TEXT_LENGTH + " OR length(" + b + ") > " + MAX_TEXT_LENGTH;
            String refusal = KIND + ": a value in '" + left + "' or '" + right + "' is longer than " + MAX_TEXT_LENGTH
                    + " characters - the similarity cap per value";
            if (normalize) {
                a = SimilaritySql.normalize(a);
                b = SimilaritySql.normalize(b);
            }
            return "CASE WHEN " + tooLong + " THEN error(" + sqlStr(refusal) + ") ELSE " + method.sql(a, b) + " END";
        }

        /** Every inbound row with the added columns; NULL on either side gives a NULL score and candidate. */
        public String select(String input) {
            String score = keepScore ? scoreColumn : SCRATCH_SCORE;
            String inner = "SELECT *, " + scoreSql() + " AS " + q(score) + " FROM " + q(input);
            if (threshold == null) return inner;
            return "SELECT *" + (keepScore ? "" : " EXCLUDE (" + q(score) + ")") + ", " + q(score) + " >= CAST("
                    + threshold + " AS DOUBLE) AS " + q(candidateColumn) + " FROM (" + inner + ")";
        }
    }

    /** Parse and validate a config; {@link IllegalArgumentException} names the first problem. */
    public static Spec parse(Map<?, ?> cfg) {
        for (Object k : cfg.keySet())
            if (!KEYS.contains(String.valueOf(k)))
                throw bad("does not take '" + k + "' (keys: " + new java.util.TreeSet<>(KEYS) + ")");
        String left = column(cfg, "left", true, null);
        String right = column(cfg, "right", true, null);
        if (left.equalsIgnoreCase(right)) throw bad("'left' and 'right' name the same column '" + left + "'");
        Method method = Method.JARO_WINKLER;
        Object m = cfg.get("method");
        if (m != null && !m.toString().isBlank()) {
            method = Method.parse(m.toString());
            if (method == null) throw bad("'method' '" + m + "' is not one of " + methods());
        }
        boolean normalize = bool(cfg, "normalize", true);
        boolean keepScore = bool(cfg, "keep_score", true);
        Double threshold = null;
        Object t = cfg.get("threshold");
        if (t != null && !t.toString().isBlank()) {
            try {
                threshold = Double.parseDouble(t.toString().trim());
            } catch (NumberFormatException e) {
                throw bad("'threshold' '" + t + "' is not a number");
            }
            if (!(threshold >= 0 && threshold <= 1)) throw bad("'threshold' must be between 0 and 1, got " + t);
        }
        if (!keepScore && threshold == null)
            throw bad("keep_score: false needs a 'threshold' - otherwise the Step would add nothing");
        String scoreColumn = column(cfg, "score_column", false, DEFAULT_SCORE_COLUMN);
        String candidateColumn = column(cfg, "candidate_column", false, DEFAULT_CANDIDATE_COLUMN);
        Spec spec = new Spec(left, right, method, normalize, scoreColumn, threshold, candidateColumn, keepScore);
        Set<String> seen = new HashSet<>();
        for (String c : spec.addedColumns())
            if (!seen.add(c.toLowerCase(Locale.ROOT)))
                throw bad("'score_column' and 'candidate_column' must differ, both are '" + c + "'");
        return spec;
    }

    /**
     * The first refusal against the inbound columns, or {@code null}: a compared column missing, or an added column
     * colliding with an inbound one.
     */
    public static String columnRefusal(Spec spec, List<String> inbound) {
        for (String c : spec.inputColumns())
            if (inbound.stream().noneMatch(x -> x.equalsIgnoreCase(c)))
                return KIND + ": column '" + c + "' is not in the inbound data (have: " + inbound + ")";
        List<String> added = new ArrayList<>(spec.addedColumns());
        if (!spec.keepScore()) added.add(SCRATCH_SCORE);
        for (String c : added)
            if (inbound.stream().anyMatch(x -> x.equalsIgnoreCase(c)))
                return KIND + ": output column '" + c + "' collides with an inbound column - set 'score_column' or "
                        + "'candidate_column' to another name";
        return null;
    }

    /** The config spellings of the methods, for messages and the form's options. */
    public static List<String> methods() {
        return Arrays.stream(Method.values()).map(Method::configName).toList();
    }

    private static String column(Map<?, ?> cfg, String key, boolean required, String dflt) {
        Object v = cfg.get(key);
        if (v == null || v.toString().isBlank()) {
            if (required) throw bad("needs '" + key + "' - the " + key + " column of the pair to compare");
            return dflt;
        }
        String s = v.toString().trim();
        try {
            Identifiers.validate(s, KIND + " " + key);
        } catch (IllegalArgumentException e) {
            throw bad("'" + key + "' value '" + s + "' is not a plain column name ([A-Za-z_][A-Za-z0-9_]*)");
        }
        return s;
    }

    private static boolean bool(Map<?, ?> cfg, String key, boolean dflt) {
        Object v = cfg.get(key);
        if (v == null || v.toString().isBlank()) return dflt;
        String s = v.toString().trim();
        if ("true".equalsIgnoreCase(s)) return true;
        if ("false".equalsIgnoreCase(s)) return false;
        throw bad("'" + key + "' must be true or false, got '" + s + "'");
    }

    private static IllegalArgumentException bad(String msg) {
        return new IllegalArgumentException(KIND + " " + msg);
    }
}
