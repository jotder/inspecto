package com.gamma.analytics.distribution;

import com.gamma.analytics.ColumnProfiler;
import com.gamma.analytics.QuantileMode;
import com.gamma.analytics.Stats;
import com.gamma.util.SqlIdent;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bins over a numeric column (ANALYTICS-CORE-1 S3, Step {@code transform.data.binning}) — a JVM form and its DuckDB
 * form, held equal by {@code BinningDuckDbParityTest}. Bins are numbered {@code 0 … bins−1}; bin {@code i} covers
 * {@code [edge(i), edge(i+1))} and the <b>last bin is closed</b> ({@code [edge(bins−1), edge(bins)]}) so the maximum is
 * counted.
 *
 * <h2>Equal width</h2>
 * Over a range {@code [lo, hi]} — the group's minimum and maximum, or a stated one. {@code w = (hi − lo) / bins};
 * {@code edge(i) = lo + i·(hi − lo) / bins}, {@code edge(bins) = hi}. The <b>index</b> is the definition:
 * {@code min(⌊(x − lo) / (hi − lo) · bins⌋, bins − 1)}, and {@code 0} when {@code hi = lo} (every value equal). This is
 * {@link ColumnProfiler#histogram}'s bin, the one shared expression ({@link #equalWidthIndexSql}).
 * <b>Out of range</b> (a stated range only): a value below {@code lo} or above {@code hi} gets no bin (NULL).
 *
 * <h2>Quantile (equal frequency)</h2>
 * Edges are the quantiles {@code edge(i) = Q(i / bins)} of the group's values ({@link Stats#quantile}, mode
 * {@link QuantileMode#CONTINUOUS} by default — {@code quantile_cont}; or {@code DISCRETE} — {@code quantile_disc}), so
 * {@code edge(0)} is the minimum and {@code edge(bins)} the maximum. The index is <b>the number of interior edges
 * {@code edge(1) … edge(bins−1)} at or below x</b>: a value equal to an edge goes to the higher bin, ties always share a
 * bin, and heavy ties can leave a bin empty (two equal edges). Deterministic — never {@code ntile}, which splits ties.
 *
 * <h2>Both</h2>
 * A missing value (NULL, NaN) gets no bin. Statistics are per group (a NULL key is a group of its own) over the
 * group's non-missing values. The label is {@code [lower, upper)} — {@code ]} for the last bin — with the edges as
 * DuckDB prints a {@code DOUBLE}.
 */
public final class Binning {

    private Binning() {}

    /** The most bins one pass may ask for. */
    public static final int MAX_BINS = 1_000;

    /** Scratch column names the statement uses internally; the inbound relation must not carry them. */
    public static final List<String> SCRATCH = List.of("__dist_v", "__dist_lo", "__dist_hi", "__dist_e", "__dist_i");

    /** How the edges are placed. */
    public enum Method { EQUAL_WIDTH, QUANTILE }

    // ---- JVM form ------------------------------------------------------------------------------------------------

    /**
     * The equal-width bin of {@code x} in {@code [lo, hi]}, or {@code -1} when {@code x} is outside the range (no bin).
     * {@code hi = lo}: bin 0.
     */
    public static int equalWidthIndex(double x, double lo, double hi, int bins) {
        checkBins(bins);
        if (Double.isNaN(x)) throw new IllegalArgumentException("bin of a NaN value: drop missing values first");
        if (x < lo || x > hi) return -1;
        if (hi == lo) return 0;
        return (int) Math.min(Math.floor((x - lo) / (hi - lo) * bins), bins - 1);
    }

    /** The {@code bins + 1} equal-width edges of {@code [lo, hi]}: {@code lo + i·(hi − lo) / bins}, the last {@code hi}. */
    public static double[] equalWidthEdges(double lo, double hi, int bins) {
        checkBins(bins);
        double[] e = new double[bins + 1];
        for (int i = 0; i < bins; i++) e[i] = lo + i * (hi - lo) / bins;
        e[bins] = hi;
        return e;
    }

    /** The {@code bins + 1} quantile edges of {@code values}: {@code Q(i / bins)} in {@code mode}. */
    public static double[] quantileEdges(double[] values, int bins, QuantileMode mode) {
        checkBins(bins);
        double[] e = new double[bins + 1];
        for (int i = 0; i <= bins; i++) e[i] = Stats.quantile(values, fraction(i, bins), mode);
        return e;
    }

    /** The quantile bin of {@code x}: how many of the interior edges {@code edges[1 … bins−1]} are at or below it. */
    public static int quantileIndex(double x, double[] edges) {
        if (Double.isNaN(x)) throw new IllegalArgumentException("bin of a NaN value: drop missing values first");
        int n = 0;
        for (int i = 1; i < edges.length - 1; i++) if (edges[i] <= x) n++;
        return n;
    }

    /** Edge fraction {@code i / bins} — the same double the SQL form parses. */
    static double fraction(int i, int bins) {
        return (double) i / bins;
    }

    // ---- SQL form ------------------------------------------------------------------------------------------------

    /**
     * One binning pass: {@code method}, {@code bins}; {@code quantileMode} for {@link Method#QUANTILE};
     * {@code rangeMin} / {@code rangeMax} (equal width only, each optional — {@code null} = the group's own) state the
     * range. Output names {@code binColumn} (BIGINT) and {@code labelColumn} (VARCHAR).
     */
    public record Spec(String column, List<String> groupBy, Method method, int bins, QuantileMode quantileMode,
                       Double rangeMin, Double rangeMax, String binColumn, String labelColumn) {
        public Spec {
            Objects.requireNonNull(column, "column");
            groupBy = List.copyOf(groupBy);
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(quantileMode, "quantileMode");
            checkBins(bins);
            if (method == Method.QUANTILE && (rangeMin != null || rangeMax != null))
                throw new IllegalArgumentException("a stated range applies to equal-width bins only");
            if (rangeMin != null && !Double.isFinite(rangeMin) || rangeMax != null && !Double.isFinite(rangeMax))
                throw new IllegalArgumentException("a stated range must be finite");
            if (rangeMin != null && rangeMax != null && !(rangeMin < rangeMax))
                throw new IllegalArgumentException("range min must be below range max, got " + rangeMin + " and " + rangeMax);
        }

        /** The columns this pass adds, in output order. */
        public List<String> addedColumns() {
            return List.of(binColumn, labelColumn);
        }
    }

    /**
     * The equal-width index over SQL expressions (non-NULL {@code x} inside {@code [lo, hi]}):
     * {@code least(CAST(coalesce(floor((x − lo) / nullif(hi − lo, 0) · bins), 0) AS BIGINT), bins − 1)}.
     */
    public static String equalWidthIndexSql(String x, String lo, String hi, int bins) {
        checkBins(bins);
        return "least(CAST(coalesce(floor((" + x + " - " + lo + ") / nullif(" + hi + " - " + lo + ", 0) * " + bins
                + "), 0) AS BIGINT), " + (bins - 1) + ")";
    }

    /** Every row of {@code relation} (the caller's already-safe SQL) with its bin and label. */
    public static String select(String relation, Spec spec) {
        String v = SqlIdent.q(SCRATCH.get(0)), lo = SqlIdent.q(SCRATCH.get(1)), hi = SqlIdent.q(SCRATCH.get(2)),
                e = SqlIdent.q(SCRATCH.get(3)), i = SqlIdent.q(SCRATCH.get(4));
        int bins = spec.bins();
        String over = DistributionSql.over(spec.groupBy());
        String read = "SELECT *, " + DistributionSql.numeric(spec.column()) + " AS " + v + " FROM " + relation;
        String windowed, index, lower, upper, scratch;
        if (spec.method() == Method.EQUAL_WIDTH) {
            String loExpr = spec.rangeMin() != null ? DistributionSql.number(spec.rangeMin()) : "min(" + v + ")" + over;
            String hiExpr = spec.rangeMax() != null ? DistributionSql.number(spec.rangeMax()) : "max(" + v + ")" + over;
            windowed = "SELECT *, " + loExpr + " AS " + lo + ", " + hiExpr + " AS " + hi + " FROM (" + read + ")";
            index = "CASE WHEN " + v + " IS NULL OR " + v + " < " + lo + " OR " + v + " > " + hi + " THEN NULL ELSE "
                    + equalWidthIndexSql(v, lo, hi, bins) + " END";
            lower = "(" + lo + " + " + i + " * (" + hi + " - " + lo + ") / " + bins + ")";
            upper = "CASE WHEN " + i + " = " + (bins - 1) + " THEN " + hi + " ELSE (" + lo + " + (" + i + " + 1) * ("
                    + hi + " - " + lo + ") / " + bins + ") END";
            scratch = v + ", " + lo + ", " + hi + ", " + i;
        } else {
            List<String> fractions = new ArrayList<>();
            for (int k = 0; k <= bins; k++)
                fractions.add("CAST('" + BigDecimal.valueOf(fraction(k, bins)).toPlainString() + "' AS DOUBLE)");
            String fn = spec.quantileMode() == QuantileMode.CONTINUOUS ? "quantile_cont" : "quantile_disc";
            windowed = "SELECT *, " + fn + "(" + v + ", [" + String.join(", ", fractions) + "])" + over + " AS " + e
                    + " FROM (" + read + ")";
            index = "CASE WHEN " + v + " IS NULL THEN NULL ELSE CAST(len(list_filter(" + e + "[2:" + bins
                    + "], lambda c: c <= " + v + ")) AS BIGINT) END";
            lower = e + "[" + i + " + 1]";
            upper = e + "[" + i + " + 2]";
            scratch = v + ", " + e + ", " + i;
        }
        String label = "CASE WHEN " + i + " IS NULL THEN NULL ELSE '[' || CAST(" + lower + " AS VARCHAR) || ', ' || CAST("
                + upper + " AS VARCHAR) || CASE WHEN " + i + " = " + (bins - 1) + " THEN ']' ELSE ')' END END";
        String indexed = "SELECT *, " + index + " AS " + i + " FROM (" + windowed + ")";
        return "SELECT * EXCLUDE (" + scratch + "), " + i + " AS " + SqlIdent.q(spec.binColumn()) + ", " + label
                + " AS " + SqlIdent.q(spec.labelColumn()) + " FROM (" + indexed + ")";
    }

    private static void checkBins(int bins) {
        if (bins < 1 || bins > MAX_BINS)
            throw new IllegalArgumentException("bins must be between 1 and " + MAX_BINS + ", got " + bins);
    }
}
