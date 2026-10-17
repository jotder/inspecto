package com.gamma.analytics.sample;

import com.gamma.analytics.Md5Bucket;
import com.gamma.analytics.split.HoldoutSplit;
import com.gamma.util.SqlIdent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic, seeded, stratified sampling (ANALYTICS-CORE-1 S4) — the same rows from the JVM form and the DuckDB form
 * ({@code SamplerDuckDbParityTest}), and the same rows on every run.
 *
 * <p><b>The order.</b> Each row has a <i>key text</i> ({@link #keyText}: its key values, each length-prefixed, so two
 * different keys never share a text) and a <i>hash</i>: {@code md5_number_lower(seed ':' keyText)}, the holdout split's
 * MD5 reading ({@link Md5Bucket#lower64}), an unsigned 64-bit number. Within each group, rows are ordered by
 * hash, then by key text (UTF-8 bytes), and the first {@link Spec#quota} rows are kept.
 *
 * <ul>
 *   <li><b>Fixed size</b> — {@code quota = min(k, n)}: exactly {@code k} rows per group, or every row of a smaller group
 *       (reservoir semantics).</li>
 *   <li><b>Fraction</b> — {@code quota = ⌊(n · bp + 5000) / 10000⌋}, the share rounded to the nearest row, half up
 *       ({@code bp} = the fraction in basis points), so every group gives its own share: a stratified sample.</li>
 * </ul>
 *
 * <p>⛔ Not DuckDB's {@code USING SAMPLE} or {@code random()}: neither is reproducible across runs, threads or versions,
 * and neither has a JVM twin. A row keeps its place in the order as long as its key does, so adding rows to a group can
 * only push a sampled row out (fixed size) — it never reshuffles the rest. A new seed gives a new sample.
 */
public final class Sampler {

    /** Basis points in a whole. */
    public static final int WHOLE = HoldoutSplit.WHOLE;
    /** The largest fixed size per group. */
    public static final int MAX_SIZE = 1_000_000;

    /** The key text of a NULL key value. A present value starts with a digit, so the two never meet. */
    static final String NULL_PART = "-";

    private Sampler() {}

    /**
     * A sample: exactly one of {@code size} (rows per group) and {@code fractionBp} (basis points per group) is set.
     */
    public record Spec(long seed, Integer size, Integer fractionBp) {
        public Spec {
            if ((size == null) == (fractionBp == null))
                throw new IllegalArgumentException("a sample has exactly one of a size and a fraction");
            if (size != null && (size < 1 || size > MAX_SIZE))
                throw new IllegalArgumentException("sample size must be 1.." + MAX_SIZE + ", got " + size);
            if (fractionBp != null && (fractionBp < 1 || fractionBp > WHOLE))
                throw new IllegalArgumentException("sample fraction must be more than 0 and at most 1 (1.." + WHOLE
                        + " basis points), got " + fractionBp);
        }

        /** {@code k} rows per group (or all of a smaller group). */
        public static Spec ofSize(long seed, int k) {
            return new Spec(seed, k, null);
        }

        /** A share of each group; the fraction is taken to basis points, rounded to the nearest. */
        public static Spec ofFraction(long seed, double fraction) {
            if (!(fraction > 0 && fraction <= 1)) throw new IllegalArgumentException(
                    "sample fraction must be more than 0 and at most 1, got " + fraction);
            int bp = HoldoutSplit.basisPoints(fraction);
            if (bp < 1) throw new IllegalArgumentException("sample fraction " + fraction + " is below one basis point");
            return new Spec(seed, null, bp);
        }

        /** The rows kept from a group of {@code n}. */
        public long quota(long n) {
            if (n < 0) throw new IllegalArgumentException("a group size is >= 0");
            return size != null ? Math.min(size, n) : (n * fractionBp + WHOLE / 2) / WHOLE;
        }
    }

    // ---- JVM form -----------------------------------------------------------------------------------------------

    /**
     * The key text of one row's key values, in key order: each value as {@code <UTF-8 byte length>:<text>}, a NULL as
     * {@code -}, concatenated. The text of a value is how DuckDB reads it as {@code VARCHAR}.
     */
    public static String keyText(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (v == null) sb.append(NULL_PART);
            else sb.append(v.getBytes(StandardCharsets.UTF_8).length).append(':').append(v);
        }
        return sb.toString();
    }

    /** The row's place in the order: {@code md5_number_lower(seed ':' keyText)}, unsigned. */
    public static long hash(long seed, String keyText) {
        return Md5Bucket.lower64(seed + ":" + Objects.requireNonNull(keyText, "keyText"));
    }

    /**
     * The rows kept, as indexes into the input in ascending order.
     *
     * @param groups   each row's group values (an empty list for one group; NULLs form a group of their own)
     * @param keyTexts each row's {@link #keyText}
     */
    public static List<Integer> select(Spec spec, List<List<String>> groups, List<String> keyTexts) {
        if (groups.size() != keyTexts.size()) throw new IllegalArgumentException("one group per key text");
        Map<List<String>, List<Integer>> byGroup = new LinkedHashMap<>();
        for (int i = 0; i < groups.size(); i++) byGroup.computeIfAbsent(groups.get(i), g -> new ArrayList<>()).add(i);
        long[] hashes = new long[keyTexts.size()];
        byte[][] keys = new byte[keyTexts.size()][];
        for (int i = 0; i < keyTexts.size(); i++) {
            hashes[i] = hash(spec.seed(), keyTexts.get(i));
            keys[i] = keyTexts.get(i).getBytes(StandardCharsets.UTF_8);
        }
        Comparator<Integer> order = (a, b) -> {
            int c = Long.compareUnsigned(hashes[a], hashes[b]);
            return c != 0 ? c : Arrays.compareUnsigned(keys[a], keys[b]);
        };
        List<Integer> kept = new ArrayList<>();
        for (List<Integer> rows : byGroup.values()) {
            rows.sort(order);
            kept.addAll(rows.subList(0, (int) spec.quota(rows.size())));
        }
        kept.sort(null);
        return kept;
    }

    // ---- DuckDB form --------------------------------------------------------------------------------------------

    /** {@link #keyText} in SQL over the key columns, each read as {@code VARCHAR}. */
    public static String keyTextSql(List<String> keyColumns) {
        if (keyColumns.isEmpty()) throw new IllegalArgumentException("a sample needs at least one key column");
        List<String> parts = new ArrayList<>();
        for (String c : keyColumns) {
            String text = "CAST(" + SqlIdent.q(c) + " AS VARCHAR)";
            parts.add("CASE WHEN " + SqlIdent.q(c) + " IS NULL THEN " + SqlIdent.sqlStr(NULL_PART)
                    + " ELSE CAST(strlen(" + text + ") AS VARCHAR) || ':' || " + text + " END");
        }
        return "(" + String.join(" || ", parts) + ")";
    }

    /**
     * {@code SELECT * FROM relation} keeping the sampled rows only, columns unchanged.
     *
     * @param groupBy    the strata (empty: the whole relation is one group)
     * @param keyColumns the stable row key the order hashes
     * @param tieColumns ordered after the hash and the key text, ascending, NULLs last — only rows whose key texts are
     *                   equal reach them (pass every column so two rows that still tie are identical)
     * @param scratch    a prefix for the working columns, none of which may be an inbound column's name
     */
    public static String sql(Spec spec, String relation, List<String> groupBy, List<String> keyColumns,
                             List<String> tieColumns, String scratch) {
        String k = SqlIdent.q(scratch + "k"), h = SqlIdent.q(scratch + "h"), rn = SqlIdent.q(scratch + "rn"),
                n = SqlIdent.q(scratch + "n");
        String partition = groupBy.isEmpty() ? ""
                : "PARTITION BY " + String.join(", ", groupBy.stream().map(SqlIdent::q).toList()) + " ";
        StringBuilder order = new StringBuilder(h + ", " + k);
        for (String c : tieColumns) order.append(", ").append(SqlIdent.q(c)).append(" ASC NULLS LAST");
        String quota = spec.size() != null ? String.valueOf(spec.size())
                : "(" + n + " * " + spec.fractionBp() + " + " + (WHOLE / 2) + ") // " + WHOLE;
        return "SELECT * EXCLUDE (" + k + ", " + h + ", " + rn + ", " + n + ") FROM ("
                + "SELECT *, row_number() OVER (" + partition + "ORDER BY " + order + ") AS " + rn
                + ", count(*) OVER (" + partition.trim() + ") AS " + n
                + " FROM (SELECT *, " + Md5Bucket.lower64Sql(SqlIdent.sqlStr(spec.seed() + ":") + " || " + k) + " AS " + h
                + " FROM (SELECT *, " + keyTextSql(keyColumns) + " AS " + k + " FROM " + SqlIdent.q(relation) + ")))"
                + " WHERE " + rn + " <= " + quota;
    }
}
