package com.gamma.analytics;

import com.gamma.util.SqlIdent;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * The one column profiler of the Analytics Kernel (ANALYTICS-CORE-1 M4): DuckDB SQL that measures columns of a relation —
 * row count, null count and rate, distinct count, min and max, mean, standard deviation, chosen quantiles — plus the
 * top-k values and an equal-width histogram of one column. SQL builders only: the caller runs the statement on its own
 * connection, sandbox or Dataset port. Every identifier is quoted with {@link SqlIdent}; the relation is the caller's
 * already-safe SQL (a quoted table name, or a parenthesised subquery with its alias). Definitions and callers:
 * {@code docs/okf/backend/analytics-kernel.md} § Column profiler.
 *
 * <h2>Two layouts of the same statistics</h2>
 * <ul>
 *   <li>{@link #rowPerColumn} — one row per (group, column): {@code column_name} then each statistic under its
 *       {@link Statistic#column() fixed name}. The {@code transform.profile} Step's shape.</li>
 *   <li>{@link #rowPerGroup} — one row per group: {@code row_count} once, then each column's statistics as
 *       {@code <statistic>_<i>} ({@code i} = the column's position). The shape a caller reading many columns in one
 *       pass wants (the Expectation baseline, Link Analysis).</li>
 * </ul>
 * Group values are reported as {@code VARCHAR} ({@code group_<j>}) so groups of any type share one shape.
 */
public final class ColumnProfiler {

    private ColumnProfiler() {}

    /** A per-column statistic, with the output column it is reported under. */
    public enum Statistic {
        /** {@code count(*)} — every row, NULLs included. */
        ROW_COUNT("row_count"),
        /** {@code count(*) - count(c)}. */
        NULL_COUNT("null_count"),
        /** null count / row count as DOUBLE; NULL for an empty relation (no rows has no rate). */
        NULL_RATE("null_rate"),
        /** Distinct non-NULL values ({@link Distinct}); an all-NULL column has 0. */
        DISTINCT_COUNT("distinct_count"),
        /** The smallest value ({@link Bounds}). */
        MIN("min_value"),
        /** The largest value ({@link Bounds}). */
        MAX("max_value"),
        /** {@code avg} of the numeric reading ({@code TRY_CAST(c AS DOUBLE)}); NULL when no value is a number. */
        MEAN("mean"),
        /** Sample standard deviation ({@code stddev_samp}) of the numeric reading; NULL below two numbers. */
        STDDEV("stddev");

        private final String column;

        Statistic(String column) {
            this.column = column;
        }

        /** The output column name in {@link #rowPerColumn}; the {@code <name>_<i>} prefix in {@link #rowPerGroup}. */
        public String column() {
            return column;
        }
    }

    /** How {@link Statistic#MIN} / {@link Statistic#MAX} read a column. */
    public enum Bounds {
        /** Compared in the column's own type, reported as {@code VARCHAR} so columns of any type share one output column. */
        TEXT,
        /** Compared as numbers ({@code TRY_CAST(c AS DOUBLE)}), so a text or date column has no bound (NULL). */
        NUMERIC
    }

    /** How {@link Statistic#DISTINCT_COUNT} counts. */
    public enum Distinct {
        /** {@code count(DISTINCT x)} — exact. */
        EXACT,
        /** {@code least(approx_count_distinct(x), count(c))} — a HyperLogLog sketch, never more than the non-NULL rows. */
        APPROXIMATE
    }

    /**
     * What to profile.
     *
     * @param columns      the columns, in output order (at least one)
     * @param groupBy      group columns; empty = the whole relation is one group
     * @param statistics   the statistics, in output order (at least one)
     * @param bounds       how min/max read a column
     * @param distinct     exact or approximate distinct count
     * @param distinctOf   maps the QUOTED column to the expression whose distinct values are counted (identity by default)
     * @param quantiles    quantile fractions in [0, 1], reported after the statistics as {@link #quantileColumn}
     * @param quantileMode how quantiles are computed (D-AN3: {@link QuantileMode#CONTINUOUS} unless the caller says)
     */
    public record Spec(List<String> columns, List<String> groupBy, List<Statistic> statistics, Bounds bounds,
                       Distinct distinct, UnaryOperator<String> distinctOf, List<Double> quantiles,
                       QuantileMode quantileMode) {

        public Spec {
            columns = List.copyOf(columns);
            groupBy = List.copyOf(groupBy);
            statistics = List.copyOf(statistics);
            quantiles = List.copyOf(quantiles);
            if (columns.isEmpty()) throw new IllegalArgumentException("a column profile needs at least one column");
            if (statistics.isEmpty() && quantiles.isEmpty())
                throw new IllegalArgumentException("a column profile needs at least one statistic or quantile");
            for (double q : quantiles)
                if (!(q >= 0 && q <= 1)) throw new IllegalArgumentException("quantile fraction must be in [0, 1], got " + q);
            if (bounds == null || distinct == null || distinctOf == null || quantileMode == null)
                throw new NullPointerException("bounds, distinct, distinctOf and quantileMode are required");
        }

        /** Every statistic, text bounds, exact distinct, no groups, no quantiles. */
        public static Spec of(List<String> columns) {
            return new Spec(columns, List.of(), List.of(Statistic.values()), Bounds.TEXT, Distinct.EXACT,
                    UnaryOperator.identity(), List.of(), QuantileMode.CONTINUOUS);
        }

        public Spec withGroupBy(List<String> groups) {
            return new Spec(columns, groups, statistics, bounds, distinct, distinctOf, quantiles, quantileMode);
        }

        public Spec withStatistics(Statistic... chosen) {
            return new Spec(columns, groupBy, List.of(chosen), bounds, distinct, distinctOf, quantiles, quantileMode);
        }

        public Spec withBounds(Bounds b) {
            return new Spec(columns, groupBy, statistics, b, distinct, distinctOf, quantiles, quantileMode);
        }

        /** Count distinct values of {@code of(quotedColumn)} the given way. */
        public Spec withDistinct(Distinct d, UnaryOperator<String> of) {
            return new Spec(columns, groupBy, statistics, bounds, d, of, quantiles, quantileMode);
        }

        public Spec withQuantiles(QuantileMode mode, double... fractions) {
            List<Double> qs = new ArrayList<>();
            for (double f : fractions) qs.add(f);
            return new Spec(columns, groupBy, statistics, bounds, distinct, distinctOf, qs, mode);
        }
    }

    // ---- the profile ---------------------------------------------------------------------------------------------

    /**
     * One row per (group, column): {@code group_<j>…, column_name, <statistic columns…>, <quantile columns…>}, one
     * {@code SELECT} per column joined with {@code UNION ALL}, in column order. {@code column_name} is the column as
     * the caller spelled it.
     */
    public static String rowPerColumn(String relation, Spec spec) {
        List<String> groups = groupExprs(spec);
        StringBuilder sql = new StringBuilder();
        for (String c : spec.columns()) {
            if (!sql.isEmpty()) sql.append(" UNION ALL ");
            List<String> select = new ArrayList<>(aliased(groups, "group_"));
            select.add(SqlIdent.sqlStr(c) + " AS column_name");
            String q = SqlIdent.q(c);
            for (Statistic s : spec.statistics()) select.add(statistic(s, q, spec) + " AS " + s.column());
            for (double f : spec.quantiles()) select.add(quantile(numeric(q), f, spec.quantileMode()) + " AS " + quantileColumn(f));
            sql.append("SELECT ").append(String.join(", ", select)).append(" FROM ").append(relation).append(groupBy(groups));
        }
        return sql.toString();
    }

    /**
     * One row per group: {@code group_<j>…}, then {@code row_count} once if {@link Statistic#ROW_COUNT} is chosen, then
     * per column {@code i} (in column order) every other chosen statistic as {@code <statistic>_<i>} and every quantile as
     * {@code <quantile column>_<i>}.
     */
    public static String rowPerGroup(String relation, Spec spec) {
        List<String> groups = groupExprs(spec);
        List<String> select = new ArrayList<>(aliased(groups, "group_"));
        if (spec.statistics().contains(Statistic.ROW_COUNT)) select.add(statistic(Statistic.ROW_COUNT, null, spec) + " AS row_count");
        for (int i = 0; i < spec.columns().size(); i++) {
            String q = SqlIdent.q(spec.columns().get(i));
            for (Statistic s : spec.statistics())
                if (s != Statistic.ROW_COUNT) select.add(statistic(s, q, spec) + " AS " + s.column() + "_" + i);
            for (double f : spec.quantiles())
                select.add(quantile(numeric(q), f, spec.quantileMode()) + " AS " + quantileColumn(f) + "_" + i);
        }
        return "SELECT " + String.join(", ", select) + " FROM " + relation + groupBy(groups);
    }

    // ---- one column's distribution -------------------------------------------------------------------------------

    /**
     * The {@code k} most frequent non-NULL values of {@code column}: {@code value} (VARCHAR), {@code frequency}; most
     * frequent first, ties by value ascending in the column's own type.
     */
    public static String topValues(String relation, String column, int k) {
        if (k < 1) throw new IllegalArgumentException("top values needs k >= 1, got " + k);
        String q = SqlIdent.q(column);
        return "SELECT CAST(" + q + " AS VARCHAR) AS value, count(*) AS frequency FROM " + relation + " WHERE " + q
                + " IS NOT NULL GROUP BY " + q + " ORDER BY frequency DESC, " + q + " LIMIT " + k;
    }

    /**
     * An equal-width histogram of the numeric reading of {@code column} ({@code TRY_CAST(c AS DOUBLE)}, non-numbers
     * dropped): {@code bins} rows {@code bin} (0-based), {@code lower_bound}, {@code upper_bound}, {@code frequency},
     * empty bins included. Bin {@code i} covers {@code [min + i·w, min + (i+1)·w)} with {@code w = (max − min) / bins};
     * the last bin is closed so the maximum is counted. When every value is equal all of them fall in bin 0. No numeric
     * value at all: no rows.
     */
    public static String histogram(String relation, String column, int bins) {
        if (bins < 1) throw new IllegalArgumentException("a histogram needs at least 1 bin, got " + bins);
        String x = numeric(SqlIdent.q(column));
        String bin = "least(CAST(coalesce(floor((v.x - s.lo) / nullif(s.hi - s.lo, 0) * " + bins + "), 0) AS BIGINT), " + (bins - 1) + ")";
        return "WITH v AS (SELECT " + x + " AS x FROM " + relation + "), s AS (SELECT min(x) AS lo, max(x) AS hi FROM v) "
                + "SELECT r.bin, s.lo + r.bin * (s.hi - s.lo) / " + bins + " AS lower_bound, "
                + "CASE WHEN r.bin = " + (bins - 1) + " THEN s.hi ELSE s.lo + (r.bin + 1) * (s.hi - s.lo) / " + bins + " END AS upper_bound, "
                + "count(v.x) AS frequency FROM s CROSS JOIN range(0, " + bins + ") AS r(bin) "
                + "LEFT JOIN v ON v.x IS NOT NULL AND " + bin + " = r.bin "
                + "WHERE s.lo IS NOT NULL GROUP BY r.bin, s.lo, s.hi ORDER BY r.bin";
    }

    // ---- single-statistic SQL forms ------------------------------------------------------------------------------

    /**
     * The DuckDB form of {@link Stats#quantile(double[], double, QuantileMode)} over an SQL expression:
     * {@link QuantileMode#CONTINUOUS} = {@code quantile_cont}; {@link QuantileMode#DISCRETE} and
     * {@link QuantileMode#NEAREST_RANK} (the same rank) = {@code quantile_disc}.
     */
    public static String quantile(String expr, double q, QuantileMode mode) {
        if (!(q >= 0 && q <= 1)) throw new IllegalArgumentException("quantile fraction must be in [0, 1], got " + q);
        String fn = mode == QuantileMode.CONTINUOUS ? "quantile_cont" : "quantile_disc";
        return fn + "(" + expr + ", " + BigDecimal.valueOf(q).toPlainString() + ")";
    }

    /** The output column of quantile {@code q}: {@code p} + the percentage, a dot as {@code _} (0.5 → p50, 0.995 → p99_5). */
    public static String quantileColumn(double q) {
        return "p" + BigDecimal.valueOf(q).movePointRight(2).stripTrailingZeros().toPlainString().replace('.', '_');
    }

    private static String statistic(Statistic s, String q, Spec spec) {
        return switch (s) {
            case ROW_COUNT -> "count(*)";
            case NULL_COUNT -> "count(*) - count(" + q + ")";
            case NULL_RATE -> "CAST(count(*) - count(" + q + ") AS DOUBLE) / nullif(count(*), 0)";
            case DISTINCT_COUNT -> spec.distinct() == Distinct.EXACT
                    ? "count(DISTINCT " + spec.distinctOf().apply(q) + ")"
                    : "least(approx_count_distinct(" + spec.distinctOf().apply(q) + "), count(" + q + "))";
            case MIN -> spec.bounds() == Bounds.TEXT ? "CAST(min(" + q + ") AS VARCHAR)" : "min(" + numeric(q) + ")";
            case MAX -> spec.bounds() == Bounds.TEXT ? "CAST(max(" + q + ") AS VARCHAR)" : "max(" + numeric(q) + ")";
            case MEAN -> "avg(" + numeric(q) + ")";
            case STDDEV -> "stddev_samp(" + numeric(q) + ")";
        };
    }

    private static String numeric(String q) {
        return "TRY_CAST(" + q + " AS DOUBLE)";
    }

    private static List<String> groupExprs(Spec spec) {
        return spec.groupBy().stream().map(g -> "CAST(" + SqlIdent.q(g) + " AS VARCHAR)").toList();
    }

    private static List<String> aliased(List<String> exprs, String prefix) {
        List<String> out = new ArrayList<>();
        for (int j = 0; j < exprs.size(); j++) out.add(exprs.get(j) + " AS " + prefix + j);
        return out;
    }

    private static String groupBy(List<String> groups) {
        return groups.isEmpty() ? "" : " GROUP BY " + String.join(", ", groups);
    }
}
