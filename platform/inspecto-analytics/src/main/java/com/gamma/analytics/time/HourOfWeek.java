package com.gamma.analytics.time;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Objects;

/**
 * The calendar slot of a timestamp for hourly seasonality (ANALYTICS-CORE-1 C5): hour of day, weekday, or the joint
 * weekday×hour ("hour of week"). One JVM function and one DuckDB SQL builder per form, held equal by
 * {@code HourOfWeekDuckDbParityTest}.
 *
 * <p><b>The clock is always stated.</b> A slot is read off a wall clock: the value's own ({@link #slot(LocalDateTime)},
 * {@link #slotSql(String)}) or an explicit zone's ({@link #slot(Instant, ZoneId)}, {@link #slotSql(String, ZoneId)}).
 * ⛔ Never DuckDB's session zone — it is the host's. Weekdays are ISO: Monday = 0 … Sunday = 6 (matching
 * {@link TimeGrain#WEEK}, which starts on Monday). In a repeated (fall-back) hour both occurrences have the same
 * wall clock and so the same slot; a skipped (spring-forward) hour has no instants and so no observations.
 *
 * <p>Slot numbering: {@code HOUR_OF_DAY} 0–23; {@code WEEKDAY} 0–6; {@code HOUR_OF_WEEK} {@code weekday·24 + hour},
 * 0–167 (Monday 00h = 0). 168 is the hourly candidate period of {@link SeasonalityDetector#candidatesFor}.
 */
public enum HourOfWeek {
    HOUR_OF_DAY(24), WEEKDAY(7), HOUR_OF_WEEK(168);

    private final int slots;

    HourOfWeek(int slots) {
        this.slots = slots;
    }

    /** How many slots exist: a slot is in {@code [0, slots())}. */
    public int slots() {
        return slots;
    }

    /** The wire name: {@code hour_of_day}, {@code weekday}, {@code hour_of_week}. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The kind named {@code s} (case-insensitive); {@link IllegalArgumentException} listing the kinds otherwise. */
    public static HourOfWeek parse(String s) {
        if (s != null)
            for (HourOfWeek k : values()) if (k.wire().equalsIgnoreCase(s.trim())) return k;
        throw new IllegalArgumentException("unknown seasonal slot '" + s + "' (one of hour_of_day, weekday, hour_of_week)");
    }

    // ── JVM ──────────────────────────────────────────────────────────────────────────────────────────────────────

    /** The slot of {@code t}, read on {@code t}'s own clock. */
    public int slot(LocalDateTime t) {
        Objects.requireNonNull(t, "t");
        int weekday = t.getDayOfWeek().getValue() - 1;
        return switch (this) {
            case HOUR_OF_DAY -> t.getHour();
            case WEEKDAY -> weekday;
            case HOUR_OF_WEEK -> weekday * 24 + t.getHour();
        };
    }

    /** The slot of the instant {@code t}, read on {@code zone}'s wall clock. */
    public int slot(Instant t, ZoneId zone) {
        return slot(LocalDateTime.ofInstant(t, Objects.requireNonNull(zone, "zone")));
    }

    /** Readable label of a slot: {@code 03h}, {@code TUESDAY}, {@code TUESDAY 03h}. */
    public String label(int slot) {
        if (slot < 0 || slot >= slots) throw new IllegalArgumentException(wire() + " slot out of range: " + slot);
        return switch (this) {
            case HOUR_OF_DAY -> "%02dh".formatted(slot);
            case WEEKDAY -> DayOfWeek.of(slot + 1).toString();
            case HOUR_OF_WEEK -> DayOfWeek.of(slot / 24 + 1) + " %02dh".formatted(slot % 24);
        };
    }

    // ── DuckDB SQL ───────────────────────────────────────────────────────────────────────────────────────────────

    /** The slot as an INTEGER expression over a wall-clock {@code TIMESTAMP} expression. */
    public String slotSql(String expr) {
        return switch (this) {
            case HOUR_OF_DAY -> "CAST(hour(" + expr + ") AS INTEGER)";
            case WEEKDAY -> "CAST(isodow(" + expr + ") - 1 AS INTEGER)";
            case HOUR_OF_WEEK -> "CAST((isodow(" + expr + ") - 1) * 24 + hour(" + expr + ") AS INTEGER)";
        };
    }

    /** {@link #slotSql(String)} of a {@code TIMESTAMP WITH TIME ZONE} expression, on {@code zone}'s wall clock. */
    public String slotSql(String expr, ZoneId zone) {
        return slotSql("timezone(" + TimeGrain.zoneSql(zone) + ", " + expr + ")");
    }
}
