package com.gamma.pipeline.exec;

import com.gamma.analytics.entity.EntityGrouping;
import com.gamma.analytics.entity.EntityGroupingSql;
import com.gamma.analytics.entity.KeyColumn;
import com.gamma.analytics.entity.MatchCondition;
import com.gamma.analytics.entity.Normalization;
import com.gamma.analytics.entity.SimilarityCandidate;
import com.gamma.analytics.similarity.SimilaritySql.Method;
import com.gamma.etl.Identifiers;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The config grammar of {@code transform.entity.link} (catalog {@code enrichment.entity.link}, {@code steps:} kind {@code entity_link}, ANALYTICS-CORE-1 S2),
 * parsed once so the run ({@link RowShaper}), the recipe compiler and the save-time check refuse the same configs with
 * the same words. The grouping is the Analytics Kernel's ({@link EntityGrouping}, run in DuckDB by
 * {@link EntityGroupingSql}).
 *
 * <p>Every row is kept and gains its <b>resolution group</b>: rows that share a block and agree exactly (after
 * normalising) on every column of one {@code match} entry are joined, transitively; the group id is the smallest key.
 *
 * <p>⛔ <b>Identity is never asserted by similarity</b> (D-M1). {@code match} entries are exact. {@code candidate_by}
 * scores a text column against the other groups of the block and flags pairs for review; it never joins a group, and
 * a similarity knob offered as part of a match is refused by name.
 */
public final class EntityLinkStep {

    private EntityLinkStep() {}

    static final String KIND = "transform.entity.link";

    /** The default group column. */
    public static final String DEFAULT_GROUP_COLUMN = "resolution_group";

    private static final Set<String> KEYS = Set.of("key", "match", "blocking", "digits", "as_written", "group_column",
            "max_block_size", "max_pairs", "candidate_by", "candidate_method", "candidate_threshold", "candidate_normalize");
    /** Words that would make a match fuzzy; refused with the D-M1 reason rather than as merely unknown. */
    private static final Set<String> FUZZY = Set.of("method", "threshold", "similarity", "fuzzy", "score");

    /** A parsed config: the kernel's spec and the output column names. */
    public record Spec(EntityGrouping.Spec grouping, EntityGroupingSql.Columns columns) {

        /** The columns the inbound relation must carry, in first-use order. */
        public List<String> inputColumns() {
            Set<String> out = new LinkedHashSet<>();
            out.add(grouping.keyColumn());
            grouping.blocking().forEach(k -> out.add(k.column()));
            grouping.conditions().forEach(c -> c.columns().forEach(k -> out.add(k.column())));
            if (grouping.candidate() != null) out.add(grouping.candidate().column());
            return List.copyOf(out);
        }

        /** The columns the Step adds, in order. */
        public List<String> addedColumns() {
            return columns.added(grouping);
        }
    }

    /** Parse and validate a config; {@link IllegalArgumentException} names the first problem. */
    public static Spec parse(Map<?, ?> cfg) {
        for (Object k : cfg.keySet()) {
            String key = String.valueOf(k);
            if (FUZZY.contains(key))
                throw bad("does not take '" + key + "' - a match is exact; similarity never joins records (D-M1). "
                        + "Use 'candidate_by' to flag look-alikes for review");
            if (!KEYS.contains(key)) throw bad("does not take '" + key + "' (keys: " + new TreeSet<>(KEYS) + ")");
        }
        String key = column(cfg.get("key"), "key");
        Set<String> digits = lowerSet(identList(cfg, "digits"));
        Set<String> asWritten = lowerSet(identList(cfg, "as_written"));
        for (String c : digits)
            if (asWritten.contains(c)) throw bad("column '" + c + "' is in both 'digits' and 'as_written'");

        List<KeyColumn> blocking = new ArrayList<>();
        for (String c : identList(cfg, "blocking")) blocking.add(new KeyColumn(c, normalization(c, digits, asWritten)));
        List<MatchCondition> conditions = new ArrayList<>();
        Object m = cfg.get("match");
        List<?> entries = m instanceof List<?> l ? l : m == null || m.toString().isBlank() ? List.of() : List.of(m);
        for (Object e : entries) {
            if (e == null || e.toString().isBlank()) continue;
            List<KeyColumn> cols = new ArrayList<>();
            Set<String> inEntry = new HashSet<>();
            for (String part : e.toString().split("\\+", -1)) {
                String c = column(part, "match");
                if (!inEntry.add(c.toLowerCase(Locale.ROOT))) throw bad("match entry '" + e + "' names '" + c + "' twice");
                cols.add(new KeyColumn(c, normalization(c, digits, asWritten)));
            }
            conditions.add(new MatchCondition(cols));
        }
        if (conditions.isEmpty())
            throw bad("needs 'match' - one entry per way two rows are the same entity, columns joined by '+' "
                    + "(EMAIL, PHONE+LAST_NAME)");

        Set<String> used = new HashSet<>();
        blocking.forEach(k -> used.add(k.column().toLowerCase(Locale.ROOT)));
        conditions.forEach(c -> c.columns().forEach(k -> used.add(k.column().toLowerCase(Locale.ROOT))));
        for (Set<String> listed : List.of(digits, asWritten))
            for (String c : listed)
                if (!used.contains(c)) throw bad("normalises '" + c + "', which no 'blocking' or 'match' entry uses");

        SimilarityCandidate candidate = null;
        Object by = cfg.get("candidate_by");
        if (by != null && !by.toString().isBlank()) {
            if (blocking.isEmpty())
                throw bad("'candidate_by' compares rows within a block - set 'blocking' too");
            Method method = Method.JARO_WINKLER;
            Object mm = cfg.get("candidate_method");
            if (mm != null && !mm.toString().isBlank()) {
                method = Method.parse(mm.toString());
                if (method == null) throw bad("'candidate_method' '" + mm + "' is not one of " + SimilarityStep.methods());
            }
            Object t = cfg.get("candidate_threshold");
            if (t == null || t.toString().isBlank())
                throw bad("'candidate_by' needs 'candidate_threshold' - the score, 0..1, at which a pair is flagged for review");
            double threshold = number(t, "candidate_threshold");
            if (!(threshold >= 0 && threshold <= 1)) throw bad("'candidate_threshold' must be between 0 and 1, got " + t);
            candidate = new SimilarityCandidate(column(by, "candidate_by"), method,
                    bool(cfg, "candidate_normalize", true), threshold);
        } else {
            for (String k : List.of("candidate_method", "candidate_threshold", "candidate_normalize"))
                if (cfg.get(k) != null && !cfg.get(k).toString().isBlank()) throw bad("'" + k + "' needs 'candidate_by'");
        }

        long maxBlock = whole(cfg, "max_block_size", EntityGrouping.DEFAULT_MAX_BLOCK_SIZE, 2, EntityGrouping.MAX_BLOCK_SIZE_CEILING);
        long maxPairs = whole(cfg, "max_pairs", EntityGrouping.DEFAULT_MAX_PAIRS, 1, EntityGrouping.MAX_PAIRS_CEILING);
        Object g = cfg.get("group_column");
        String groupColumn = g == null || g.toString().isBlank() ? DEFAULT_GROUP_COLUMN : column(g, "group_column");
        EntityGroupingSql.Columns d = EntityGroupingSql.Columns.DEFAULT;
        EntityGroupingSql.Columns columns = new EntityGroupingSql.Columns(groupColumn, d.candidateScore(),
                d.candidateGroup(), d.candidateReview());
        EntityGrouping.Spec grouping = new EntityGrouping.Spec(key, blocking, conditions, candidate, (int) maxBlock, maxPairs);
        Set<String> seen = new HashSet<>();
        for (String c : columns.added(grouping))
            if (!seen.add(c.toLowerCase(Locale.ROOT))) throw bad("'group_column' '" + c + "' is one of the candidate columns");
        return new Spec(grouping, columns);
    }

    /**
     * The first refusal against the inbound columns, or {@code null}: a named column missing, or an added column
     * colliding with an inbound one.
     */
    public static String columnRefusal(Spec spec, List<String> inbound) {
        for (String c : spec.inputColumns())
            if (inbound.stream().noneMatch(x -> x.equalsIgnoreCase(c)))
                return KIND + ": column '" + c + "' is not in the inbound data (have: " + inbound + ")";
        for (String c : spec.addedColumns())
            if (inbound.stream().anyMatch(x -> x.equalsIgnoreCase(c)))
                return KIND + ": output column '" + c + "' collides with an inbound column"
                        + (c.equalsIgnoreCase(spec.columns().group()) ? " - set 'group_column' to another name" : "");
        return null;
    }

    private static Normalization normalization(String column, Set<String> digits, Set<String> asWritten) {
        String c = column.toLowerCase(Locale.ROOT);
        return digits.contains(c) ? Normalization.DIGITS : asWritten.contains(c) ? Normalization.NONE : Normalization.TEXT;
    }

    private static List<String> identList(Map<?, ?> cfg, String key) {
        List<String> out = new ArrayList<>();
        Object v = cfg.get(key);
        if (v instanceof List<?> l) {
            for (Object o : l) if (o != null && !o.toString().isBlank()) out.add(column(o, key));
        } else if (v != null && !v.toString().isBlank()) {
            if (!(v instanceof String)) throw bad("'" + key + "' must be a list of column names");
            out.add(column(v, key));
        }
        return out;
    }

    private static Set<String> lowerSet(List<String> columns) {
        Set<String> out = new LinkedHashSet<>();
        for (String c : columns) out.add(c.toLowerCase(Locale.ROOT));
        return out;
    }

    private static String column(Object v, String key) {
        if (v == null || v.toString().isBlank()) throw bad("needs '" + key + "' - a column name");
        String s = v.toString().trim();
        try {
            Identifiers.validate(s, KIND + " " + key);
        } catch (IllegalArgumentException e) {
            throw bad("'" + key + "' value '" + s + "' is not a plain column name ([A-Za-z_][A-Za-z0-9_]*)");
        }
        return s;
    }

    private static double number(Object v, String key) {
        try {
            return Double.parseDouble(v.toString().trim());
        } catch (NumberFormatException e) {
            throw bad("'" + key + "' '" + v + "' is not a number");
        }
    }

    private static long whole(Map<?, ?> cfg, String key, long dflt, long min, long max) {
        Object v = cfg.get(key);
        if (v == null || v.toString().isBlank()) return dflt;
        double d = number(v, key);
        if (d != Math.rint(d)) throw bad("'" + key + "' '" + v + "' is not a whole number");
        if (d < min || d > max) throw bad("'" + key + "' must be between " + min + " and " + max + ", got " + v);
        return (long) d;
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
