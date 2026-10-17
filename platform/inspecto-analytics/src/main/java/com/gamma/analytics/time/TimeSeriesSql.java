package com.gamma.analytics.time;

import com.gamma.util.SqlIdent;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * DuckDB SQL builders for the time-series primitives (ANALYTICS-CORE-1 M5): resample with gap fill and
 * period-over-period shift. Bucketing is {@link TimeGrain}'s — the one truncation definition — on a stated clock: the
 * column's own (a {@code DATE} or zone-less {@code TIMESTAMP}, {@code zone == null}) or an explicit zone (a
 * {@code TIMESTAMP WITH TIME ZONE}, bucketed by its wall clock there). Nothing here reads DuckDB's session zone.
 *
 * <p>Every identifier is double-quoted through {@link SqlIdent}; the only SQL a caller hands in is an
 * {@link Measure#aggregateSql} built by its own SQL builder (the Measure grammar's {@code MeasureCompiler} in the
 * engine), never author text.
 */
public final class TimeSeriesSql {

    private TimeSeriesSql() {}

    /** How a bucket with no rows is filled (Anomaly D-AD8): {@code ZERO} for counts and sums, {@code NULL} otherwise. */
    public enum Fill {
        ZERO, NULL;

        private static final Set<String> ZERO_FILLED = Set.of("count", "countDistinct", "sum");

        /** The fill for a Measure-grammar aggregate name: zero for {@code count}, {@code countDistinct} and {@code sum}. */
        public static Fill forAggregate(String agg) {
            return ZERO_FILLED.contains(agg) ? ZERO : NULL;
        }
    }

    /** One output measure: a caller-built aggregate expression, its output column, and its empty-bucket fill. */
    public record Measure(String aggregateSql, String as, Fill fill) {
        public Measure {
            Objects.requireNonNull(aggregateSql, "aggregateSql");
            Objects.requireNonNull(as, "as");
            Objects.requireNonNull(fill, "fill");
        }
    }

    /**
     * A resample: one row per key per bucket between the bounds, every bucket present.
     *
     * @param relation     the input table or view name
     * @param timeColumn   the column bucketed; rows where it is NULL are left out
     * @param zone         {@code null} = the column's own clock; else the zone a {@code TIMESTAMPTZ} is bucketed in
     * @param keys         the series keys (may be empty: one series); a key's NULL is a value of its own
     * @param bucketColumn the output column holding each bucket's start — a wall-clock {@code TIMESTAMP} (in
     *                     {@code zone} when one is given)
     * @param from         first bucket (truncated to the grain), or {@code null} for the earliest bucket with data
     * @param to           last bucket (truncated to the grain), or {@code null} for the latest bucket with data
     * @param maxBuckets   the run fails when the series would hold this many buckets or more (per key)
     */
    public record Resample(String relation, String timeColumn, TimeGrain grain, ZoneId zone, List<String> keys,
                           List<Measure> measures, String bucketColumn, LocalDateTime from, LocalDateTime to,
                           long maxBuckets) {
        public Resample {
            Objects.requireNonNull(relation, "relation");
            Objects.requireNonNull(timeColumn, "timeColumn");
            Objects.requireNonNull(grain, "grain");
            Objects.requireNonNull(bucketColumn, "bucketColumn");
            keys = List.copyOf(keys);
            measures = List.copyOf(measures);
            if (measures.isEmpty()) throw new IllegalArgumentException("resample needs at least one measure");
            if (maxBuckets < 1) throw new IllegalArgumentException("maxBuckets must be positive");
            if (from != null && to != null && grain.truncate(from).isAfter(grain.truncate(to)))
                throw new IllegalArgumentException("resample 'from' " + from + " is after 'to' " + to);
        }
    }

    /**
     * {@code SELECT keys…, bucket, measures… } over every bucket from the first to the last (inclusive), ordered by
     * keys then bucket. A bucket with no rows has its {@link Fill#ZERO} measures 0 and the rest NULL; every key seen
     * in the data gets the whole series.
     */
    public static String resample(Resample r) {
        String bucket = SqlIdent.q(r.bucketColumn());
        String time = SqlIdent.q(r.timeColumn());
        String label = r.zone() == null ? r.grain().truncateSql(time) : r.grain().labelSql(time, r.zone());

        StringBuilder agg = new StringBuilder("SELECT ");
        for (String k : r.keys()) agg.append(SqlIdent.q(k)).append(", ");
        agg.append(label).append(" AS ").append(bucket);
        for (Measure m : r.measures()) agg.append(", ").append(m.aggregateSql()).append(" AS ").append(SqlIdent.q(m.as()));
        agg.append(" FROM ").append(SqlIdent.q(r.relation())).append(" WHERE ").append(time).append(" IS NOT NULL")
                .append(" GROUP BY ");
        for (int i = 1; i <= r.keys().size() + 1; i++) agg.append(i == 1 ? "" : ", ").append(i);

        String lo = r.from() == null ? "MIN(" + bucket + ")" : r.grain().truncateSql(timestampLiteral(r.from()));
        String hi = r.to() == null ? "MAX(" + bucket + ")" : r.grain().truncateSql(timestampLiteral(r.to()));
        String bounds = "SELECT " + lo + " AS lo, " + hi + " AS hi FROM __ts_agg";
        String series = "SELECT UNNEST(generate_series(lo, CASE WHEN date_diff(" + SqlIdent.sqlStr(r.grain().wire())
                + ", lo, hi) >= " + r.maxBuckets() + " THEN error(" + SqlIdent.sqlStr("resample: more than "
                + r.maxBuckets() + " " + r.grain().wire() + " buckets between the bounds - narrow 'from'/'to' or "
                + "use a coarser grain") + ") ELSE hi END, " + r.grain().intervalSql(1) + ")) AS " + bucket
                + " FROM __ts_bounds";

        StringBuilder sql = new StringBuilder("WITH __ts_agg AS (").append(agg).append("), __ts_bounds AS (")
                .append(bounds).append("), __ts_series AS (").append(series).append(")");
        String grid;
        if (r.keys().isEmpty()) {
            grid = "__ts_series";
        } else {
            sql.append(", __ts_keys AS (SELECT DISTINCT ").append(columns(r.keys())).append(" FROM __ts_agg)");
            grid = "(SELECT * FROM __ts_keys CROSS JOIN __ts_series)";
        }
        sql.append(" SELECT ");
        for (String k : r.keys()) sql.append("g.").append(SqlIdent.q(k)).append(", ");
        sql.append("g.").append(bucket);
        for (Measure m : r.measures()) {
            String a = "a." + SqlIdent.q(m.as());
            sql.append(", ").append(m.fill() == Fill.ZERO ? "COALESCE(" + a + ", 0)" : a).append(" AS ")
                    .append(SqlIdent.q(m.as()));
        }
        sql.append(" FROM ").append(grid).append(" AS g LEFT JOIN __ts_agg AS a ON a.").append(bucket).append(" = g.")
                .append(bucket);
        for (String k : r.keys())
            sql.append(" AND a.").append(SqlIdent.q(k)).append(" IS NOT DISTINCT FROM g.").append(SqlIdent.q(k));
        sql.append(" ORDER BY ");
        for (String k : r.keys()) sql.append("g.").append(SqlIdent.q(k)).append(", ");
        return sql.append("g.").append(bucket).toString();
    }

    /** One shifted column: {@code column} from {@code periods} buckets earlier, written as {@code as}. */
    public record Shifted(String column, String as) {
        public Shifted {
            Objects.requireNonNull(column, "column");
            Objects.requireNonNull(as, "as");
        }
    }

    /**
     * A period-over-period shift: every input row gains, per {@link Shifted}, the value its key had {@code periods}
     * buckets earlier (a negative {@code periods} looks later). The match is by bucket, not by row position, so a
     * series with missing buckets is still shifted correctly: a bucket with no earlier row gets NULL.
     *
     * @param bucketColumn the time column; each value is truncated to {@code grain} before matching
     * @param zone         {@code null} = the column's own clock; else the zone a {@code TIMESTAMPTZ} is bucketed in
     */
    public record Shift(String relation, String bucketColumn, TimeGrain grain, ZoneId zone, List<String> keys,
                        int periods, List<Shifted> columns) {
        public Shift {
            Objects.requireNonNull(relation, "relation");
            Objects.requireNonNull(bucketColumn, "bucketColumn");
            Objects.requireNonNull(grain, "grain");
            keys = List.copyOf(keys);
            columns = List.copyOf(columns);
            if (columns.isEmpty()) throw new IllegalArgumentException("shift needs at least one column");
            if (periods == 0) throw new IllegalArgumentException("shift periods must not be 0");
        }

        private String label(String alias) {
            String col = alias + "." + SqlIdent.q(bucketColumn);
            return zone == null ? grain.truncateSql(col) : grain.labelSql(col, zone);
        }
    }

    /** {@code SELECT c.*, p.col AS as … FROM relation c LEFT JOIN relation p ON …}, ordered by keys then bucket. */
    public static String shift(Shift s) {
        String rel = SqlIdent.q(s.relation());
        StringBuilder sql = new StringBuilder("SELECT c.*");
        for (Shifted x : s.columns())
            sql.append(", p.").append(SqlIdent.q(x.column())).append(" AS ").append(SqlIdent.q(x.as()));
        sql.append(" FROM ").append(rel).append(" AS c LEFT JOIN ").append(rel).append(" AS p ON ")
                .append(s.label("p")).append(" + ").append(s.grain().intervalSql(s.periods())).append(" = ")
                .append(s.label("c"));
        for (String k : s.keys())
            sql.append(" AND p.").append(SqlIdent.q(k)).append(" IS NOT DISTINCT FROM c.").append(SqlIdent.q(k));
        sql.append(" ORDER BY ");
        for (String k : s.keys()) sql.append("c.").append(SqlIdent.q(k)).append(", ");
        return sql.append("c.").append(SqlIdent.q(s.bucketColumn())).toString();
    }

    /**
     * How many (key, bucket) pairs hold more than one row — a shift needs one row per key per bucket (a resample's
     * output), since a second row in the earlier bucket would duplicate the current one. Run it first; 0 = safe.
     */
    public static String duplicateBucketsSql(Shift s) {
        List<String> by = new ArrayList<>();
        for (String k : s.keys()) by.add("c." + SqlIdent.q(k));
        by.add(s.label("c"));
        return "SELECT COUNT(*) FROM (SELECT 1 FROM " + SqlIdent.q(s.relation()) + " AS c GROUP BY "
                + String.join(", ", by) + " HAVING COUNT(*) > 1)";
    }

    private static String columns(List<String> names) {
        List<String> out = new ArrayList<>();
        for (String n : names) out.add(SqlIdent.q(n));
        return String.join(", ", out);
    }

    private static final DateTimeFormatter LITERAL = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS");

    /** {@code TIMESTAMP '2026-01-31 00:00:00.000000'} — printed from a parsed value, never author text. */
    static String timestampLiteral(LocalDateTime t) {
        return "TIMESTAMP '" + LITERAL.format(t) + "'";
    }
}
