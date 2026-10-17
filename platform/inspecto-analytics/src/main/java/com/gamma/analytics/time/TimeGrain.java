package com.gamma.analytics.time;

import com.gamma.util.SqlIdent;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.Locale;

/**
 * The one time-grain truncation definition (ANALYTICS-CORE-1 M5): a JVM function and a DuckDB SQL builder per form,
 * held equal by {@code TimeGrainDuckDbParityTest}.
 *
 * <p><b>Wall clock</b> ({@link #truncate(LocalDateTime)}, {@link #truncateSql(String)}): the value's own clock, no zone
 * involved — for a {@code DATE} or a zone-less {@code TIMESTAMP}. {@code WEEK} starts on Monday (ISO, as DuckDB's
 * {@code DATE_TRUNC('week', …)}); {@code QUARTER} starts in January, April, July or October.
 *
 * <p><b>Zoned</b> ({@link #label}, {@link #truncate(Instant, ZoneId)} and their SQL forms): an instant
 * ({@code TIMESTAMP WITH TIME ZONE}) is bucketed by its wall clock in an EXPLICIT zone. ⛔ Never DuckDB's session zone:
 * it is the host's, so {@code DATE_TRUNC} over a {@code TIMESTAMPTZ} changes with the machine. Every SQL form here gives
 * the same answer whatever the session {@code TimeZone} is.
 * <ul>
 *   <li>{@link #label} — the bucket's local start as a wall-clock {@code TIMESTAMP} in the zone. In a repeated
 *       (fall-back) hour both occurrences carry the same label.</li>
 *   <li>{@link #truncate(Instant, ZoneId)} — the bucket's start as an instant. For
 *       {@code SECOND}, {@code MINUTE} and {@code HOUR} it is the input minus its local remainder, so the offset is the
 *       input's own and the two occurrences of a repeated hour are two buckets. For {@code DAY} and coarser it is the
 *       local start resolved in the zone: a start inside a skipped (spring-forward) gap moves later by the gap; a start
 *       that occurs twice takes the LATER offset — DuckDB's (ICU's) rule, kept so the two forms agree. Only a zone whose
 *       clocks fall back across midnight (e.g. America/Havana) meets that case, and there an instant in the first
 *       occurrence of the repeated hour gets a day start AFTER itself; its {@link #label} is still the right day.</li>
 * </ul>
 */
public enum TimeGrain {
    SECOND, MINUTE, HOUR, DAY, WEEK, MONTH, QUARTER, YEAR;

    /** The wire name and DuckDB date part: {@code day}, {@code week}, … */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The grain named {@code s} (case-insensitive); {@link IllegalArgumentException} listing the grains otherwise. */
    public static TimeGrain parse(String s) {
        if (s != null)
            for (TimeGrain g : values()) if (g.wire().equalsIgnoreCase(s.trim())) return g;
        throw new IllegalArgumentException("unknown time grain '" + s + "' (one of "
                + Arrays.stream(values()).map(TimeGrain::wire).toList() + ")");
    }

    /** The grain of a {@link ChronoUnit} step: SECONDS … YEARS (WEEKS → WEEK, QUARTER has no unit). */
    public static TimeGrain of(ChronoUnit unit) {
        return switch (unit) {
            case SECONDS -> SECOND;
            case MINUTES -> MINUTE;
            case HOURS -> HOUR;
            case DAYS -> DAY;
            case WEEKS -> WEEK;
            case MONTHS -> MONTH;
            case YEARS -> YEAR;
            default -> throw new IllegalArgumentException("no time grain for " + unit);
        };
    }

    // ── JVM ──────────────────────────────────────────────────────────────────────────────────────────────────────

    /** The start of the bucket holding {@code t}, on {@code t}'s own clock. */
    public LocalDateTime truncate(LocalDateTime t) {
        return switch (this) {
            case SECOND -> t.truncatedTo(ChronoUnit.SECONDS);
            case MINUTE -> t.truncatedTo(ChronoUnit.MINUTES);
            case HOUR -> t.truncatedTo(ChronoUnit.HOURS);
            case DAY -> t.truncatedTo(ChronoUnit.DAYS);
            default -> truncate(t.toLocalDate()).atStartOfDay();
        };
    }

    /** The first day of the bucket holding {@code d}; a sub-day grain returns {@code d}. */
    public LocalDate truncate(LocalDate d) {
        return switch (this) {
            case SECOND, MINUTE, HOUR, DAY -> d;
            case WEEK -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> d.withDayOfMonth(1);
            case QUARTER -> LocalDate.of(d.getYear(), ((d.getMonthValue() - 1) / 3) * 3 + 1, 1);
            case YEAR -> d.withDayOfYear(1);
        };
    }

    /** The wall-clock start, in {@code zone}, of the bucket holding the instant {@code t}. */
    public LocalDateTime label(Instant t, ZoneId zone) {
        return truncate(LocalDateTime.ofInstant(t, zone));
    }

    /** The instant the bucket holding {@code t} starts, bucketed on {@code zone}'s wall clock (rules in the class note). */
    public Instant truncate(Instant t, ZoneId zone) {
        LocalDateTime local = LocalDateTime.ofInstant(t, zone);
        LocalDateTime start = truncate(local);
        if (compareTo(HOUR) <= 0) return t.minus(Duration.between(start, local));
        return ZonedDateTime.ofLocal(start, zone, null).withLaterOffsetAtOverlap().toInstant();
    }

    /** {@code t} moved by {@code n} buckets (negative = back), on its own clock. */
    public LocalDateTime plus(LocalDateTime t, long n) {
        return switch (this) {
            case SECOND -> t.plusSeconds(n);
            case MINUTE -> t.plusMinutes(n);
            case HOUR -> t.plusHours(n);
            case DAY -> t.plusDays(n);
            case WEEK -> t.plusWeeks(n);
            case MONTH -> t.plusMonths(n);
            case QUARTER -> t.plusMonths(3 * n);
            case YEAR -> t.plusYears(n);
        };
    }

    // ── DuckDB SQL ───────────────────────────────────────────────────────────────────────────────────────────────

    /** {@code DATE_TRUNC('<grain>', expr)} — the wall-clock form, for a {@code DATE} or zone-less {@code TIMESTAMP}. */
    public String truncateSql(String expr) {
        return "DATE_TRUNC(" + SqlIdent.sqlStr(wire()) + ", " + expr + ")";
    }

    /** {@link #label}'s SQL: a wall-clock {@code TIMESTAMP}. {@code expr} must be a {@code TIMESTAMP WITH TIME ZONE}. */
    public String labelSql(String expr, ZoneId zone) {
        return truncateSql("timezone(" + zoneSql(zone) + ", " + expr + ")");
    }

    /**
     * {@link #truncate(Instant, ZoneId)}'s SQL: a {@code TIMESTAMP WITH TIME ZONE}. {@code expr} must be a
     * {@code TIMESTAMP WITH TIME ZONE}; it is written more than once, so pass a column reference, not a computation.
     */
    public String truncateSql(String expr, ZoneId zone) {
        String local = "timezone(" + zoneSql(zone) + ", " + expr + ")";
        if (compareTo(HOUR) <= 0)
            // A remainder under one hour is a pure-microsecond INTERVAL, which TIMESTAMPTZ arithmetic applies
            // exactly, whatever the session zone.
            return "(" + expr + " - (" + local + " - " + truncateSql(local) + "))";
        return "timezone(" + zoneSql(zone) + ", " + truncateSql(local) + ")";
    }

    /** An INTERVAL of {@code n} buckets, for wall-clock arithmetic: {@code INTERVAL (7) DAY} for one week. */
    public String intervalSql(long n) {
        return switch (this) {
            case SECOND -> "INTERVAL (" + n + ") SECOND";
            case MINUTE -> "INTERVAL (" + n + ") MINUTE";
            case HOUR -> "INTERVAL (" + n + ") HOUR";
            case DAY -> "INTERVAL (" + n + ") DAY";
            case WEEK -> "INTERVAL (" + Math.multiplyExact(7, n) + ") DAY";
            case MONTH -> "INTERVAL (" + n + ") MONTH";
            case QUARTER -> "INTERVAL (" + Math.multiplyExact(3, n) + ") MONTH";
            case YEAR -> "INTERVAL (" + n + ") YEAR";
        };
    }

    /**
     * The zone as a SQL string literal. A region id ({@code Europe/Berlin}) or UTC; a fixed offset other than UTC is
     * refused, since DuckDB's ICU zone names do not include {@code +05:30}-style ids.
     */
    public static String zoneSql(ZoneId zone) {
        return SqlIdent.sqlStr(zoneId(zone));
    }

    /** The zone id DuckDB is handed: {@code UTC} for any UTC spelling, the region id otherwise. */
    public static String zoneId(ZoneId zone) {
        if (zone.normalized().equals(ZoneOffset.UTC)) return "UTC";
        if (zone instanceof ZoneOffset)
            throw new IllegalArgumentException("time zone '" + zone + "' is a fixed offset - name a region "
                    + "(e.g. Asia/Kolkata) or UTC");
        return zone.getId();
    }
}
