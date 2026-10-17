package com.gamma.analytics.accuracy;

import com.gamma.util.SqlIdent;
import java.util.List;
import java.util.Objects;

/**
 * The DuckDB form of {@link Accuracy}'s definitions (ANALYTICS-CORE-1 S5b): one statement that measures the pairs of a
 * table or view, optionally per group. Every identifier is quoted through {@link SqlIdent}.
 *
 * <p>Output columns, in order: the group columns, then {@code n}, {@code mae}, {@code rmse}, {@code mape},
 * {@code mape_zero_actuals}, {@code smape}, {@code bias}, and — only when a band is named — {@code coverage},
 * {@code mean_interval_width}. A row with a NULL in any used column is skipped (the JVM form refuses NaN, so the caller
 * drops missing pairs there too). {@code mape} is NULL when every actual is zero; with no pairs at all (no groups)
 * {@code n} is 0 and the measures NULL, where the JVM form throws. A band with lower &gt; upper fails the statement
 * through DuckDB's {@code error()}, as the JVM form throws.
 */
public final class AccuracySql {

    private AccuracySql() {}

    /**
     * @param relation  table or view name
     * @param actual    actual-value column
     * @param predicted predicted-value column
     * @param lower     band lower column, or {@code null} for no band (then {@code upper} must be {@code null})
     * @param upper     band upper column, or {@code null}
     * @param groupBy   group columns (may be empty: one row)
     */
    public record Spec(String relation, String actual, String predicted, String lower, String upper, List<String> groupBy) {
        public Spec {
            Objects.requireNonNull(relation, "relation");
            Objects.requireNonNull(actual, "actual");
            Objects.requireNonNull(predicted, "predicted");
            if ((lower == null) != (upper == null)) throw new IllegalArgumentException("a band needs both lower and upper");
            groupBy = List.copyOf(groupBy);
        }
    }

    public static String summary(Spec s) {
        String a = "CAST(" + SqlIdent.q(s.actual()) + " AS DOUBLE)";
        String p = "CAST(" + SqlIdent.q(s.predicted()) + " AS DOUBLE)";
        String e = "(" + p + " - " + a + ")";
        boolean band = s.lower() != null;
        String lo = band ? "CAST(" + SqlIdent.q(s.lower()) + " AS DOUBLE)" : null;
        String hi = band ? "CAST(" + SqlIdent.q(s.upper()) + " AS DOUBLE)" : null;

        StringBuilder sql = new StringBuilder("SELECT ");
        for (String g : s.groupBy()) sql.append(SqlIdent.q(g)).append(", ");
        sql.append("count(*) AS n")
                .append(", avg(abs(").append(e).append(")) AS mae")
                .append(", sqrt(avg(").append(e).append(" * ").append(e).append(")) AS rmse")
                .append(", 100 * avg(abs(").append(e).append(") / abs(").append(a).append(")) FILTER (WHERE ").append(a)
                .append(" <> 0) AS mape")
                .append(", count(*) FILTER (WHERE ").append(a).append(" = 0) AS mape_zero_actuals")
                .append(", avg(CASE WHEN abs(").append(a).append(") + abs(").append(p).append(") = 0 THEN 0 ELSE 200 * abs(")
                .append(e).append(") / (abs(").append(a).append(") + abs(").append(p).append(")) END) AS smape")
                .append(", avg(").append(e).append(") AS bias");
        if (band) {
            sql.append(", avg(CASE WHEN ").append(lo).append(" > ").append(hi).append(" THEN error(")
                    .append(SqlIdent.sqlStr("accuracy: band lower > upper")).append(") WHEN ").append(a).append(" >= ")
                    .append(lo).append(" AND ").append(a).append(" <= ").append(hi).append(" THEN 1.0 ELSE 0.0 END) AS coverage")
                    .append(", avg(").append(hi).append(" - ").append(lo).append(") AS mean_interval_width");
        }
        sql.append(" FROM ").append(SqlIdent.q(s.relation())).append(" WHERE ").append(SqlIdent.q(s.actual()))
                .append(" IS NOT NULL AND ").append(SqlIdent.q(s.predicted())).append(" IS NOT NULL");
        if (band) sql.append(" AND ").append(SqlIdent.q(s.lower())).append(" IS NOT NULL AND ")
                .append(SqlIdent.q(s.upper())).append(" IS NOT NULL");
        if (!s.groupBy().isEmpty()) {
            sql.append(" GROUP BY ");
            for (int i = 1; i <= s.groupBy().size(); i++) sql.append(i == 1 ? "" : ", ").append(i);
            sql.append(" ORDER BY ");
            for (int i = 1; i <= s.groupBy().size(); i++) sql.append(i == 1 ? "" : ", ").append(i);
        }
        return sql.toString();
    }
}
