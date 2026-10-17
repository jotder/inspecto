package com.gamma.analytics.forecast;

import com.gamma.analytics.Stats;
import com.gamma.analytics.time.TimeGrain;
import com.gamma.util.SqlIdent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * The DuckDB form of {@link Forecaster} (ANALYTICS-CORE-1 S5): one query forecasts every series of a regular bucketed
 * relation at once and returns the {@link ForecastOutput} shape. Holt-Winters runs as a recursive CTE that advances
 * every series one bucket per step, carrying its seasonal terms as a list (the {@code business-assurance} template's
 * technique); the interval's sigma is DuckDB's {@code mad} × {@link Stats#MAD_SCALE}. The z multipliers and the
 * horizon's variance factors are computed in Java ({@link PredictionInterval}) and inlined as literals, so there is one
 * definition of each.
 *
 * <p>The input must be regular — run {@link #checkSql} first and refuse a non-zero problem count: every time value on
 * the grain, one row per key per bucket, no missing bucket between a series' first and last, no empty value, at least
 * {@link Forecaster.Spec#minPoints()} buckets per series. Future buckets are labelled {@code last + h × grain}, cast
 * back to the time column's type.
 *
 * <p>Every identifier is double-quoted through {@link SqlIdent}; numbers are printed from parsed values; the time type
 * is one of {@link #TIME_TYPES}. No author text reaches the SQL.
 */
public final class ForecastSql {
    private ForecastSql() {}

    /** The time column types a forecast accepts: a date or a zone-less timestamp (a resample's bucket column). */
    public static final Set<String> TIME_TYPES = Set.of("DATE", "TIMESTAMP", "TIMESTAMP_S", "TIMESTAMP_MS",
            "TIMESTAMP_NS");

    /**
     * One forecast over {@code relation}.
     *
     * @param timeType the DuckDB type of {@code timeColumn}, one of {@link #TIME_TYPES}
     * @param keys     the series keys (may be empty: one series); a key's NULL is a value of its own
     */
    public record Spec(String relation, String timeColumn, String timeType, TimeGrain grain, List<String> keys,
                       String valueColumn, Forecaster.Spec model) {
        public Spec {
            Objects.requireNonNull(relation, "relation");
            Objects.requireNonNull(timeColumn, "timeColumn");
            Objects.requireNonNull(grain, "grain");
            Objects.requireNonNull(valueColumn, "valueColumn");
            Objects.requireNonNull(model, "model");
            keys = List.copyOf(keys);
            timeType = timeType == null ? "" : timeType.toUpperCase(Locale.ROOT).trim();
            if (!TIME_TYPES.contains(timeType))
                throw new IllegalArgumentException("forecast time column must be a DATE or TIMESTAMP, not " + timeType);
        }
    }

    /**
     * One row: {@code series, off_grain, null_time, null_value, irregular_series, min_points, max_points} — the number
     * of series, of time values not on the grain, of empty times, of empty values, of series with a duplicate or
     * missing bucket, and the shortest and longest series. Empty input gives {@code series = 0}.
     */
    public static String checkSql(Spec s) {
        String time = SqlIdent.q(s.timeColumn());
        return "SELECT count(*) AS series, COALESCE(sum(off_grain), 0) AS off_grain, COALESCE(sum(null_time), 0) AS null_time,"
                + " COALESCE(sum(null_value), 0) AS null_value,"
                + " count(*) FILTER (WHERE n <> span) AS irregular_series, min(n) AS min_points, max(n) AS max_points"
                + " FROM (SELECT count(*) FILTER (WHERE " + time + " IS NOT NULL AND " + s.grain().truncateSql(time)
                + " <> " + time + ") AS off_grain, count(*) FILTER (WHERE " + time + " IS NULL) AS null_time,"
                + " count(*) FILTER (WHERE " + SqlIdent.q(s.valueColumn()) + " IS NULL) AS null_value, count(*) AS n,"
                + " date_diff(" + SqlIdent.sqlStr(s.grain().wire()) + ", min(" + time + "), max(" + time + ")) + 1 AS span"
                + " FROM " + SqlIdent.q(s.relation()) + groupBy("", s.keys()) + ")";
    }

    /** The forecast query: the {@link ForecastOutput} columns, ordered by keys, time, level. */
    public static String select(Spec s) {
        Forecaster.Spec f = s.model();
        int m = f.period();
        String keysCsv = list("", s.keys());
        String partition = s.keys().isEmpty() ? "" : "PARTITION BY " + keysCsv + " ";
        StringBuilder sql = new StringBuilder("WITH RECURSIVE __fc_s AS (SELECT ").append(prefix(keysCsv))
                .append(SqlIdent.q(s.timeColumn())).append(" AS __fc_time, CAST(").append(SqlIdent.q(s.valueColumn()))
                .append(" AS DOUBLE) AS __fc_y, row_number() OVER (").append(partition).append("ORDER BY ")
                .append(SqlIdent.q(s.timeColumn())).append(") AS __fc_t FROM ").append(SqlIdent.q(s.relation()))
                .append(")");
        sql.append(", __fc_end AS (SELECT ").append(prefix(keysCsv))
                .append("max(__fc_t) AS __fc_n, max(__fc_time) AS __fc_last FROM __fc_s").append(groupBy("", s.keys()))
                .append(")");

        if (f.method() == ForecastMethod.HOLT_WINTERS) {
            String a = lit(f.alpha()), b = lit(f.beta()), g = lit(f.gamma()), mm = lit(m);
            String k = "CAST((s.__fc_t - 1) % " + m + " AS BIGINT) + 1";
            sql.append(", __fc_init AS (SELECT ").append(prefix(keysCsv))
                    .append("avg(__fc_y) FILTER (WHERE __fc_t <= ").append(m).append(") AS l0, (avg(__fc_y) FILTER (WHERE __fc_t > ")
                    .append(m).append(" AND __fc_t <= ").append(2 * m).append(") - avg(__fc_y) FILTER (WHERE __fc_t <= ")
                    .append(m).append(")) / ").append(mm).append(" AS b0, list(__fc_y ORDER BY __fc_t) FILTER (WHERE __fc_t <= ")
                    .append(m).append(") AS y0 FROM __fc_s").append(groupBy("", s.keys())).append(")");
            sql.append(", __fc_hw AS (SELECT ").append(prefix(keysCsv)).append("CAST(").append(m)
                    .append(" AS BIGINT) AS __fc_t, l0 AS lvl, b0 AS tr, list_transform(y0, lambda x: x - l0) AS seas,")
                    .append(" CAST(NULL AS DOUBLE) AS f FROM __fc_init UNION ALL SELECT ").append(prefix(keysCsv))
                    .append("__fc_t, lvl, ").append(b).append(" * (lvl - plvl) + (1 - ").append(b).append(") * ptr AS tr,")
                    .append(" list_transform(seas, lambda x, i: CASE WHEN i = k THEN ").append(g).append(" * (y - lvl) + (1 - ")
                    .append(g).append(") * x ELSE x END) AS seas, f FROM (SELECT *, ").append(a).append(" * (y - so) + (1 - ")
                    .append(a).append(") * (plvl + ptr) AS lvl FROM (SELECT ").append(prefix(list("s.", s.keys())))
                    .append("s.__fc_t, s.__fc_y AS y, ").append(k).append(" AS k, h.lvl AS plvl, h.tr AS ptr, h.seas,")
                    .append(" h.seas[").append(k).append("] AS so, h.lvl + h.tr + h.seas[").append(k).append("] AS f")
                    .append(" FROM __fc_hw AS h JOIN __fc_s AS s ON s.__fc_t = h.__fc_t + 1").append(match("s", "h", s.keys()))
                    .append(") AS __fc_x0) AS __fc_x1)");
            sql.append(", __fc_fit AS (SELECT ").append(prefix(keysCsv)).append("__fc_t, f FROM __fc_hw WHERE f IS NOT NULL)");
            sql.append(", __fc_ahead AS (SELECT ").append(prefix(list("e.", s.keys())))
                    .append("x.h, w.lvl + x.h * w.tr + w.seas[CAST((e.__fc_n + x.h - 1) % ").append(m)
                    .append(" AS BIGINT) + 1] AS f FROM __fc_end AS e JOIN __fc_hw AS w ON w.__fc_t = e.__fc_n")
                    .append(match("w", "e", s.keys())).append(" CROSS JOIN __fc_v AS x)");
        } else {
            sql.append(", __fc_fit AS (SELECT ").append(prefix(keysCsv)).append("__fc_t, lag(__fc_y, ").append(m)
                    .append(") OVER (").append(partition).append("ORDER BY __fc_t) AS f FROM __fc_s)");
            sql.append(", __fc_ahead AS (SELECT ").append(prefix(list("e.", s.keys())))
                    .append("x.h, s.__fc_y AS f FROM __fc_end AS e CROSS JOIN __fc_v AS x JOIN __fc_s AS s ON s.__fc_t = e.__fc_n - ")
                    .append(m).append(" + (x.h - 1) % ").append(m).append(" + 1").append(match("s", "e", s.keys())).append(")");
        }
        sql.append(", __fc_sig AS (SELECT ").append(prefix(list("s.", s.keys()))).append(lit(Stats.MAD_SCALE))
                .append(" * mad(s.__fc_y - x.f) AS sigma FROM __fc_s AS s JOIN __fc_fit AS x ON x.__fc_t = s.__fc_t")
                .append(match("x", "s", s.keys())).append(" WHERE x.f IS NOT NULL").append(groupBy("s.", s.keys())).append(")");

        String time = SqlIdent.q(s.timeColumn());
        StringBuilder out = new StringBuilder(" SELECT ").append(prefix(keysCsv)).append(time)
                .append(", horizon, actual, forecast, level, lower, upper FROM (SELECT ").append(prefix(list("s.", s.keys())))
                .append("s.__fc_time AS ").append(time).append(", CAST(0 AS INTEGER) AS horizon, s.__fc_y AS actual,")
                .append(" x.f AS forecast, l.level, x.f - l.z * g.sigma AS lower, x.f + l.z * g.sigma AS upper,")
                .append(" s.__fc_t AS __fc_o FROM __fc_s AS s LEFT JOIN __fc_fit AS x ON x.__fc_t = s.__fc_t")
                .append(match("x", "s", s.keys())).append(" JOIN __fc_sig AS g ON TRUE").append(match("g", "s", s.keys()))
                .append(" CROSS JOIN __fc_l AS l UNION ALL SELECT ").append(prefix(list("e.", s.keys())))
                .append("CAST(e.__fc_last + ").append(s.grain().intervalSql(1)).append(" * x.h AS ").append(s.timeType())
                .append(") AS ").append(time).append(", CAST(x.h AS INTEGER), CAST(NULL AS DOUBLE), a.f, l.level,")
                .append(" a.f - l.z * g.sigma * sqrt(x.v), a.f + l.z * g.sigma * sqrt(x.v), e.__fc_n + x.h")
                .append(" FROM __fc_end AS e CROSS JOIN __fc_v AS x JOIN __fc_ahead AS a ON a.h = x.h")
                .append(match("a", "e", s.keys())).append(" JOIN __fc_sig AS g ON TRUE").append(match("g", "e", s.keys()))
                .append(" CROSS JOIN __fc_l AS l) ORDER BY ").append(prefix(keysCsv)).append("__fc_o, level");

        // Constants first: the levels with their z, the horizon with its variance factor.
        StringBuilder consts = new StringBuilder(", __fc_l AS (SELECT * FROM (VALUES ");
        for (int i = 0; i < f.levels().size(); i++) {
            double level = f.levels().get(i);
            consts.append(i == 0 ? "" : ", ").append("(").append(lit(level)).append(", ")
                    .append(lit(PredictionInterval.z(level))).append(")");
        }
        consts.append(") AS t(level, z)), __fc_v AS (SELECT * FROM (VALUES ");
        double[] v = PredictionInterval.varianceFactors(f);
        for (int h = 1; h <= v.length; h++)
            consts.append(h == 1 ? "" : ", ").append("(").append(h).append(", ").append(lit(v[h - 1])).append(")");
        consts.append(") AS t(h, v))");
        int firstCte = sql.indexOf(", __fc_end AS");
        sql.insert(firstCte, consts);
        return sql.append(out).toString();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────────────

    /** A DOUBLE literal of a parsed value — the decimal text Java prints, cast so no DECIMAL arithmetic creeps in. */
    static String lit(double d) {
        if (!Double.isFinite(d)) throw new IllegalArgumentException("not a finite number: " + d);
        return "CAST(" + Double.toString(d) + " AS DOUBLE)";
    }

    private static String list(String alias, List<String> keys) {
        List<String> out = new ArrayList<>();
        for (String k : keys) out.add(alias + SqlIdent.q(k));
        return String.join(", ", out);
    }

    private static String prefix(String csv) {
        return csv.isEmpty() ? "" : csv + ", ";
    }

    private static String groupBy(String alias, List<String> keys) {
        return keys.isEmpty() ? "" : " GROUP BY " + list(alias, keys);
    }

    private static String match(String left, String right, List<String> keys) {
        StringBuilder sb = new StringBuilder();
        for (String k : keys)
            sb.append(" AND ").append(left).append(".").append(SqlIdent.q(k)).append(" IS NOT DISTINCT FROM ")
                    .append(right).append(".").append(SqlIdent.q(k));
        return sb.toString();
    }
}
