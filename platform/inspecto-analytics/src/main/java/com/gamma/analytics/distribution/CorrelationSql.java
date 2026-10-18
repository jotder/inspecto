package com.gamma.analytics.distribution;

import com.gamma.analytics.Stats;
import com.gamma.util.SqlIdent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pearson correlation and simple linear regression per column pair, in DuckDB SQL (ANALYTICS-CORE-1 S3, Step
 * {@code transform.stats.correlation}) — a summarising statement, one row per group per pair. The figures are
 * DuckDB's {@code corr}, {@code regr_slope}, {@code regr_intercept}, {@code regr_r2} and {@code regr_count}, whose JVM
 * definitions are {@link Stats#correlation} and {@link Stats#linearRegression} ({@code CorrelationDuckDbParityTest}).
 *
 * <p>Output columns: the group columns (their own names and types), {@code x_column}, {@code y_column} (VARCHAR — the
 * pair, as authored), {@code n} (BIGINT, the pairs with both values present), {@code correlation}, {@code slope},
 * {@code intercept}, {@code r2} (DOUBLE). Rows ordered by the group values (NULLs last), then the pairs as given.
 *
 * <p>Rules: a pair counts only when <b>both</b> values are present (NULL and NaN are missing — {@link
 * DistributionSql#numeric}). A figure with no number is NULL, never NaN: DuckDB's NaN for a constant side (one pair
 * included) is read as NULL, as the JVM form is empty there. No pair at all: {@code n} = 0 and every figure NULL.
 * {@code r2} is 1 when y is constant and x is not (DuckDB's rule, {@link Stats#linearRegression}).
 */
public final class CorrelationSql {

    private CorrelationSql() {}

    /** The output columns after the group columns, in order. */
    public static final List<String> OUTPUT = List.of("x_column", "y_column", "n", "correlation", "slope", "intercept", "r2");

    /** The scratch ordinal of a pair; never in the output. */
    public static final String SCRATCH = "__dist_pair";

    /** One pair: {@code y} is regressed on {@code x}. */
    public record Pair(String x, String y) {
        public Pair {
            Objects.requireNonNull(x, "x");
            Objects.requireNonNull(y, "y");
        }
    }

    /**
     * What the pair's figures are computed over: the values ({@code PEARSON}) or their within-group average ranks
     * ({@code SPEARMAN}, ANALYTICS-CORE-1 S3 residual) — Spearman's rho is Pearson's r of the ranks, so the same
     * columns come out: {@code correlation} is rho, and {@code slope} / {@code intercept} / {@code r2} are the line
     * through the ranks. Ties take the average of the ranks they span ({@link Stats#ranks}).
     */
    public enum Method { PEARSON, SPEARMAN }

    /** The Pearson statement over {@code relation} (the caller's already-safe SQL). */
    public static String select(String relation, List<Pair> pairs, List<String> groupBy) {
        return select(relation, pairs, groupBy, Method.PEARSON);
    }

    /** The statement over {@code relation} (the caller's already-safe SQL). */
    public static String select(String relation, List<Pair> pairs, List<String> groupBy, Method method) {
        if (pairs.isEmpty()) throw new IllegalArgumentException("correlation needs at least one pair");
        List<String> groups = groupBy.stream().map(SqlIdent::q).toList();
        List<String> parts = new ArrayList<>();
        for (int p = 0; p < pairs.size(); p++) {
            Pair pair = pairs.get(p);
            String x = DistributionSql.numeric(pair.x()), y = DistributionSql.numeric(pair.y());
            String args = "(" + y + ", " + x + ")";
            String from = relation;
            if (method == Method.SPEARMAN) {
                // a pair counts only when both values are present, then each side is ranked among the group's counted
                // rows; the average rank of a tie is rank() (the lowest) + (ties - 1) / 2
                String part = groups.isEmpty() ? "" : "PARTITION BY " + String.join(", ", groups);
                String partX = groups.isEmpty() ? "PARTITION BY rx0" : part + ", rx0";
                String partY = groups.isEmpty() ? "PARTITION BY ry0" : part + ", ry0";
                String both = "(SELECT " + String.join(", ", concat(groups, List.of(
                        "CASE WHEN " + x + " IS NOT NULL AND " + y + " IS NOT NULL THEN " + x + " END AS rx0",
                        "CASE WHEN " + x + " IS NOT NULL AND " + y + " IS NOT NULL THEN " + y + " END AS ry0")))
                        + " FROM " + relation + ")";
                from = "(SELECT " + String.join(", ", concat(groups, List.of(
                        "CASE WHEN rx0 IS NULL THEN NULL ELSE rank() OVER (" + part + " ORDER BY rx0) + (count(*) OVER ("
                                + partX + ") - 1) / 2.0 END AS rx",
                        "CASE WHEN ry0 IS NULL THEN NULL ELSE rank() OVER (" + part + " ORDER BY ry0) + (count(*) OVER ("
                                + partY + ") - 1) / 2.0 END AS ry"))) + " FROM " + both + ")";
                args = "(ry, rx)";
            }
            List<String> select = new ArrayList<>(groups);
            select.add(SqlIdent.sqlStr(pair.x()) + " AS x_column");
            select.add(SqlIdent.sqlStr(pair.y()) + " AS y_column");
            select.add("CAST(regr_count" + args + " AS BIGINT) AS n");
            select.add(noNaN("corr" + args) + " AS correlation");
            select.add(noNaN("regr_slope" + args) + " AS slope");
            select.add(noNaN("regr_intercept" + args) + " AS intercept");
            select.add(noNaN("regr_r2" + args) + " AS r2");
            select.add(p + " AS " + SqlIdent.q(SCRATCH));
            parts.add("SELECT " + String.join(", ", select) + " FROM " + from
                    + (groups.isEmpty() ? "" : " GROUP BY " + String.join(", ", groups)));
        }
        List<String> order = new ArrayList<>();
        for (String g : groups) order.add(g + " NULLS LAST");
        order.add(SqlIdent.q(SCRATCH));
        return "SELECT * EXCLUDE (" + SqlIdent.q(SCRATCH) + ") FROM (" + String.join(" UNION ALL ", parts) + ") ORDER BY "
                + String.join(", ", order);
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    /** An aggregate's NaN (no number) read as NULL. */
    private static String noNaN(String expr) {
        return "nullif(" + expr + ", CAST('NaN' AS DOUBLE))";
    }
}
