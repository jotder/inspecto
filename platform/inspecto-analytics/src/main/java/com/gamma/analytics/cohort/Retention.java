package com.gamma.analytics.cohort;

import com.gamma.analytics.time.TimeGrain;
import com.gamma.util.SqlIdent;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Cohort retention (ANALYTICS-CORE-1 C9): which share of the entities that first appeared in a bucket are still active k
 * buckets later. A JVM function ({@link #compute}) and a DuckDB SQL builder ({@link #sql}) that share one definition, held
 * equal by {@code RetentionDuckDbParityTest}. Buckets are {@link TimeGrain}'s (M5), on a stated clock; the as-of
 * discipline is C1's: {@code through} is always stated, never "now".
 *
 * <p><b>Definition.</b> An <i>event</i> is an (entity, time); an event with a NULL entity or time is ignored. Time is read
 * on a wall clock — the column's own ({@code zone == null}: a {@code DATE} or zone-less {@code TIMESTAMP}) or an explicit
 * zone's (a {@code TIMESTAMP WITH TIME ZONE}); never DuckDB's session zone. A bucket is {@link TimeGrain#label}.
 * <ol>
 *   <li>Events whose bucket is after {@code through}'s bucket are dropped — they are in the future of the as-of date.</li>
 *   <li>An entity's <b>cohort</b> is the bucket of its earliest remaining event.</li>
 *   <li><b>size(c)</b> = the entities of cohort c. A cohort starts before {@code from} is left out of the result (its
 *       entities were still used to find who is new: dropping old events first would make old entities look new).</li>
 *   <li>The <b>period</b> of an event is the number of whole buckets between its cohort's bucket and its own (0 = the
 *       cohort's own bucket); the whole-bucket difference is on the wall-clock labels, so across a DST change an hourly
 *       period counts wall-clock hours.</li>
 *   <li><b>active(c, k)</b> is, in {@link Mode#EXACT} (classic retention), the distinct entities of c with an event in
 *       period k; in {@link Mode#UNBOUNDED} ("returned on or after"), those with an event in period k <i>or later</i>
 *       (never rising with k; active(c, 0) = size(c)). <b>rate</b> = active / size.</li>
 *   <li>A cell (c, k) exists only when it is <i>observable</i>: bucket c + k is not after {@code through}'s bucket, and
 *       k ≤ {@code maxPeriods}. An observable cell with nobody active is a row with active 0 — a cell not yet observable is
 *       absent, never 0.</li>
 * </ol>
 * Entities are compared as text ({@code CAST(entity AS VARCHAR)} in SQL), so 7 and '7' are one entity. Cohort size, active
 * and rate are over distinct entities, so a heavy user is counted once.
 */
public final class Retention {

    /** Default cap on periods reported per cohort. */
    public static final int DEFAULT_MAX_PERIODS = 120;
    /** Longest event list the JVM form accepts (fails closed beyond it). */
    public static final int MAX_EVENTS = 5_000_000;

    private Retention() {}

    /** {@code EXACT}: active in period k itself. {@code UNBOUNDED}: active in period k or any later one. */
    public enum Mode { EXACT, UNBOUNDED }

    /**
     * @param grain      the cohort and period bucket
     * @param zone       {@code null} = the time column's own clock; else the zone an instant is bucketed in
     * @param through    the as-of: events and periods after its bucket are not observed (truncated to the grain)
     * @param from       first cohort bucket reported (truncated to the grain), or {@code null} for all
     * @param maxPeriods the highest period reported, ≥ 0
     */
    public record Params(TimeGrain grain, ZoneId zone, LocalDateTime through, LocalDateTime from, Mode mode, int maxPeriods) {
        public Params {
            Objects.requireNonNull(grain, "grain");
            Objects.requireNonNull(through, "through");
            Objects.requireNonNull(mode, "mode");
            if (maxPeriods < 0) throw new IllegalArgumentException("maxPeriods must be >= 0: " + maxPeriods);
            if (grain.compareTo(TimeGrain.HOUR) < 0)
                throw new IllegalArgumentException("retention buckets are an hour or coarser, not " + grain.wire());
        }
    }

    /** One (entity, instant) event; the instant is read on {@link Params#zone()}. */
    public record Event(String entity, Instant at) {}

    /** One (cohort, period) cell. */
    public record Cell(LocalDateTime cohort, int period, long size, long active) {
        public double rate() {
            return (double) active / size;
        }
    }

    /** The cells, ordered by cohort then period. Events are (entity, instant); the zone is {@code p.zone()} (UTC if null). */
    public static List<Cell> compute(List<Event> events, Params p) {
        Objects.requireNonNull(events, "events");
        if (events.size() > MAX_EVENTS)
            throw new IllegalArgumentException("retention: " + events.size() + " events exceed the cap of " + MAX_EVENTS);
        ZoneId zone = p.zone() == null ? ZoneId.of("UTC") : p.zone();
        LocalDateTime through = p.grain().truncate(p.through());
        // entity -> distinct bucket labels at or before `through`
        Map<String, Set<LocalDateTime>> buckets = new HashMap<>();
        for (Event e : events) {
            if (e.entity() == null || e.at() == null) continue;
            LocalDateTime b = p.grain().label(e.at(), zone);
            if (b.isAfter(through)) continue;
            buckets.computeIfAbsent(e.entity(), k -> new HashSet<>()).add(b);
        }
        LocalDateTime from = p.from() == null ? null : p.grain().truncate(p.from());
        // cohort -> size and per-period counts
        Map<LocalDateTime, long[]> perCohort = new TreeMap<>();   // [size, exact(0..maxPeriods), unbounded(0..maxPeriods)]
        for (Set<LocalDateTime> bs : buckets.values()) {
            LocalDateTime cohort = bs.stream().min(LocalDateTime::compareTo).orElseThrow();
            if (from != null && cohort.isBefore(from)) continue;
            long[] row = perCohort.computeIfAbsent(cohort, c -> new long[1 + 2 * (p.maxPeriods() + 1)]);
            row[0]++;
            int last = -1;
            for (LocalDateTime b : bs) {
                int k = periods(p.grain(), cohort, b);
                if (k <= p.maxPeriods()) row[1 + k]++;
                last = Math.max(last, k);
            }
            for (int k = 0; k <= Math.min(last, p.maxPeriods()); k++) row[1 + (p.maxPeriods() + 1) + k]++;
        }
        List<Cell> out = new ArrayList<>();
        for (Map.Entry<LocalDateTime, long[]> e : perCohort.entrySet()) {
            int observable = Math.min(periods(p.grain(), e.getKey(), through), p.maxPeriods());
            for (int k = 0; k <= observable; k++) {
                long active = p.mode() == Mode.EXACT ? e.getValue()[1 + k] : e.getValue()[1 + (p.maxPeriods() + 1) + k];
                out.add(new Cell(e.getKey(), k, e.getValue()[0], active));
            }
        }
        return out;
    }

    /** Whole buckets from bucket start {@code a} to bucket start {@code b} (both truncated to {@code grain}). */
    static int periods(TimeGrain grain, LocalDateTime a, LocalDateTime b) {
        long n = switch (grain) {
            case HOUR -> ChronoUnit.HOURS.between(a, b);
            case DAY -> ChronoUnit.DAYS.between(a, b);
            case WEEK -> ChronoUnit.DAYS.between(a, b) / 7;
            case MONTH -> ChronoUnit.MONTHS.between(a, b);
            case QUARTER -> ChronoUnit.MONTHS.between(a, b) / 3;
            case YEAR -> ChronoUnit.YEARS.between(a, b);
            default -> throw new IllegalArgumentException("retention buckets are an hour or coarser, not " + grain.wire());
        };
        return Math.toIntExact(n);
    }

    // ── DuckDB SQL ───────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * DuckDB SQL for {@link #compute}: columns {@code cohort} (the bucket start, a wall-clock {@code TIMESTAMP}),
     * {@code period}, {@code size}, {@code active}, {@code rate}, ordered by cohort then period.
     *
     * @param entityColumn the entity key (read as text)
     * @param timeColumn   a {@code DATE} / {@code TIMESTAMP} when {@code p.zone()} is null, else a
     *                     {@code TIMESTAMP WITH TIME ZONE}
     */
    public static String sql(String relation, String entityColumn, String timeColumn, Params p) {
        Objects.requireNonNull(relation, "relation");
        String k = SqlIdent.q(entityColumn), t = SqlIdent.q(timeColumn);
        String label = "CAST(" + (p.zone() == null ? p.grain().truncateSql(t) : p.grain().labelSql(t, p.zone()))
                + " AS TIMESTAMP)";   // DATE_TRUNC of a DATE can stay a DATE
        String through = p.grain().truncateSql(literal(p.through()));
        String unit = SqlIdent.sqlStr(p.grain().wire());
        String fromTerm = p.from() == null ? "" : " WHERE __c >= " + p.grain().truncateSql(literal(p.from()));
        String active = p.mode() == Mode.EXACT
                ? "count(DISTINCT CASE WHEN a.__p = g.period THEN a.__k END)"
                : "count(DISTINCT CASE WHEN a.__last >= g.period THEN a.__k END)";
        return "WITH __ev AS (SELECT CAST(" + k + " AS VARCHAR) AS __k, " + label + " AS __b FROM " + SqlIdent.q(relation)
                + " WHERE " + k + " IS NOT NULL AND " + t + " IS NOT NULL)"
                + ", __ob AS (SELECT DISTINCT __k, __b FROM __ev WHERE __b <= " + through + ")"
                + ", __first AS (SELECT __k, min(__b) AS __c FROM __ob GROUP BY __k)"
                + ", __act AS (SELECT o.__k, f.__c, date_diff(" + unit + ", f.__c, o.__b) AS __p,"
                + " max(date_diff(" + unit + ", f.__c, o.__b)) OVER (PARTITION BY o.__k) AS __last"
                + " FROM __ob o JOIN __first f ON f.__k = o.__k)"
                + ", __cohort AS (SELECT __c, count(*) AS size FROM __first" + fromTerm + " GROUP BY __c)"
                + ", __grid AS (SELECT __c, size, UNNEST(generate_series(0, LEAST(date_diff(" + unit + ", __c, " + through
                + "), " + p.maxPeriods() + "))) AS period FROM __cohort)"
                + " SELECT g.__c AS cohort, CAST(g.period AS INTEGER) AS period, g.size AS size, " + active + " AS active,"
                + " CAST(" + active + " AS DOUBLE) / g.size AS rate FROM __grid g LEFT JOIN __act a ON a.__c = g.__c"
                + " GROUP BY g.__c, g.period, g.size ORDER BY g.__c, g.period";
    }

    private static final java.time.format.DateTimeFormatter LITERAL =
            java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS");

    private static String literal(LocalDateTime t) {
        return "TIMESTAMP '" + LITERAL.format(t) + "'";
    }
}
