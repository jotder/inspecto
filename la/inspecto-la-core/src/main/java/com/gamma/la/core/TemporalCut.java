package com.gamma.la.core;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The DuckDB half of a temporal pattern scan (DUCKDB-2-LOOP-QUERIES-1 C3): one statement that reads the same capped,
 * ordered {@code (s, t, ts)} rows the scan always read and returns only the rows of the series that CAN hold a finding,
 * plus the two counts the response states. {@link LinkTemporalDetector} then runs unchanged on those series, so the
 * findings are byte-identical to hauling every row - the cut never drops a series the detector would report:
 *
 * <ul>
 *   <li><b>burst</b> - a series is kept when, in time order, some event's {@code (minEvents - 1)}-th successor is at most
 *       {@code w} later ({@code lead}). That is the detector's own test - its window from event {@code i} reaches index
 *       {@code j} with {@code t[j] - t[i] <= w}, and holds {@code minEvents} events exactly when {@code t[i + minEvents - 1]}
 *       is inside it - so the cut is exact. An offset window, not a {@code RANGE} count: a {@code RANGE} frame over one
 *       very hot series (2 000 000 events) ran past the 10 s pattern timeout.</li>
 *   <li><b>periodicity</b> - a series is kept when it has {@code max(minEvents, 3)} events, a positive mean gap and a gap
 *       CV within {@code maxCv} plus a relative tolerance: DuckDB's {@code stddev_pop / avg} may differ from the Analytics
 *       Kernel's in the last bits, so the tolerance only widens the cut and the detector decides exactly.</li>
 * </ul>
 *
 * <p>The input is any SELECT yielding {@code s}, {@code t} (text) and {@code ts} (epoch milliseconds, NULL when the row
 * has no readable time). The cap is the scan's: the first {@code maxRows} rows in {@code s, t, ts} order (NULL times
 * last), and {@code rowCapped} when there were more. In entity mode a row is an event of its source and, unless it is a
 * self-loop, of its target; those rows come back as {@code (entity, entity, ts)}, the self-loop shape the scan already
 * folds into one event of one entity.
 */
public final class TemporalCut {

    private TemporalCut() { }

    /** The scan knobs the cut needs; {@code windowMs} is read for bursts only, {@code maxCv} for periodicity only. */
    public record Spec(boolean burst, boolean entity, long windowMs, int minEvents, double maxCv) { }

    /** One event row of a kept series. */
    public record Row(String s, String t, long ms) { }

    /** The kept rows (series grouped, times ascending), the capped rows with no time, and whether the cap was hit. */
    public record Result(List<Row> rows, int skippedNoTime, boolean rowCapped) { }

    /** Relative slack on the CV test: wide enough for floating-point differences, far below any real threshold. */
    static final double CV_TOLERANCE = 1e-9;

    /** The statement over {@code inner}; its binds are {@code inner}'s, unchanged. */
    public static String sql(String inner, int maxRows, Spec spec) {
        if (maxRows < 1) throw new IllegalArgumentException("maxRows must be >= 1: " + maxRows);
        String events = spec.entity()
                ? "SELECT s AS ks, s AS kt, ts FROM __tc_capped WHERE ts IS NOT NULL"
                + " UNION ALL SELECT t, t, ts FROM __tc_capped WHERE ts IS NOT NULL AND t <> s"
                : "SELECT s AS ks, t AS kt, ts FROM __tc_capped WHERE ts IS NOT NULL";
        String keep = spec.burst()
                ? "SELECT DISTINCT ks, kt FROM (SELECT ks, kt, ts, lead(ts, " + (spec.minEvents() - 1)
                + ") OVER (PARTITION BY ks, kt ORDER BY ts) AS reach FROM __tc_ev) w WHERE reach - ts <= " + spec.windowMs()
                : "SELECT ks, kt FROM (SELECT ks, kt, ts - lag(ts) OVER (PARTITION BY ks, kt ORDER BY ts) AS g FROM __tc_ev) w"
                + " GROUP BY ks, kt HAVING count(*) >= " + Math.max(spec.minEvents(), 3)
                + " AND avg(g) > 0 AND stddev_pop(g) <= avg(g) * " + plain(spec.maxCv() * (1 + CV_TOLERANCE) + 1e-12);
        return "WITH __tc_src AS (" + inner + "),"
                + " __tc_lim AS (SELECT s, t, ts FROM __tc_src ORDER BY s, t, ts NULLS LAST LIMIT " + ((long) maxRows + 1) + "),"
                + " __tc_numbered AS (SELECT s, t, ts, row_number() OVER (ORDER BY s, t, ts NULLS LAST) AS rn FROM __tc_lim),"
                + " __tc_capped AS (SELECT s, t, ts FROM __tc_numbered WHERE rn <= " + maxRows + "),"
                + " __tc_ev AS (" + events + "),"
                + " __tc_cand AS (" + keep + ")"
                + " SELECT 0 AS k, CAST(NULL AS VARCHAR) AS s, CAST(NULL AS VARCHAR) AS t,"
                + " (SELECT count(*) FROM __tc_capped WHERE ts IS NULL) AS ts,"
                + " (SELECT count(*) FROM __tc_lim) > " + maxRows + " AS capped"
                + " UNION ALL SELECT 1, e.ks, e.kt, e.ts, false FROM __tc_ev e JOIN __tc_cand c ON e.ks = c.ks AND e.kt = c.kt"
                + " ORDER BY k, s, t, ts";
    }

    /** The most rows the statement can return: the kept rows (doubled in entity mode) and the one count row. */
    public static long maxOutputRows(int maxRows, Spec spec) {
        return (long) maxRows * (spec.entity() ? 2 : 1) + 1;
    }

    /** Reads the statement's rows as the {@code k, s, t, ts, capped} maps a dataset read returns. */
    public static Result read(List<Map<String, Object>> rows) {
        List<Row> out = new ArrayList<>();
        int skipped = 0;
        boolean capped = false;
        for (Map<String, Object> r : rows) {
            if (((Number) r.get("k")).intValue() == 0) {
                skipped = ((Number) r.get("ts")).intValue();
                capped = Boolean.TRUE.equals(r.get("capped"));
            } else {
                out.add(new Row(String.valueOf(r.get("s")), String.valueOf(r.get("t")), ((Number) r.get("ts")).longValue()));
            }
        }
        return new Result(out, skipped, capped);
    }

    /** Reads the statement's rows from a JDBC result set. */
    public static Result read(ResultSet rs) throws SQLException {
        List<Row> out = new ArrayList<>();
        int skipped = 0;
        boolean capped = false;
        while (rs.next()) {
            if (rs.getInt(1) == 0) {
                skipped = rs.getInt(4);
                capped = rs.getBoolean(5);
            } else {
                out.add(new Row(rs.getString(2), rs.getString(3), rs.getLong(4)));
            }
        }
        return new Result(out, skipped, capped);
    }

    /** A double as a DOUBLE literal (Java's shortest round-trip form; never a DECIMAL, whose width could overflow). */
    private static String plain(double v) {
        return "CAST(" + Double.toString(v) + " AS DOUBLE)";
    }
}
