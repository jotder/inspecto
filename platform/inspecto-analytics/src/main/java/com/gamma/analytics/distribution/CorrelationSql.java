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

    /** The statement over {@code relation} (the caller's already-safe SQL). */
    public static String select(String relation, List<Pair> pairs, List<String> groupBy) {
        if (pairs.isEmpty()) throw new IllegalArgumentException("correlation needs at least one pair");
        List<String> groups = groupBy.stream().map(SqlIdent::q).toList();
        List<String> parts = new ArrayList<>();
        for (int p = 0; p < pairs.size(); p++) {
            Pair pair = pairs.get(p);
            String x = DistributionSql.numeric(pair.x()), y = DistributionSql.numeric(pair.y());
            String args = "(" + y + ", " + x + ")";
            List<String> select = new ArrayList<>(groups);
            select.add(SqlIdent.sqlStr(pair.x()) + " AS x_column");
            select.add(SqlIdent.sqlStr(pair.y()) + " AS y_column");
            select.add("CAST(regr_count" + args + " AS BIGINT) AS n");
            select.add(noNaN("corr" + args) + " AS correlation");
            select.add(noNaN("regr_slope" + args) + " AS slope");
            select.add(noNaN("regr_intercept" + args) + " AS intercept");
            select.add(noNaN("regr_r2" + args) + " AS r2");
            select.add(p + " AS " + SqlIdent.q(SCRATCH));
            parts.add("SELECT " + String.join(", ", select) + " FROM " + relation
                    + (groups.isEmpty() ? "" : " GROUP BY " + String.join(", ", groups)));
        }
        List<String> order = new ArrayList<>();
        for (String g : groups) order.add(g + " NULLS LAST");
        order.add(SqlIdent.q(SCRATCH));
        return "SELECT * EXCLUDE (" + SqlIdent.q(SCRATCH) + ") FROM (" + String.join(" UNION ALL ", parts) + ") ORDER BY "
                + String.join(", ", order);
    }

    /** An aggregate's NaN (no number) read as NULL. */
    private static String noNaN(String expr) {
        return "nullif(" + expr + ", CAST('NaN' AS DOUBLE))";
    }
}
