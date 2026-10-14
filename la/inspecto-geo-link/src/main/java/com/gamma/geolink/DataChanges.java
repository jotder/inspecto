package com.gamma.geolink;

import com.gamma.signal.DeliveryAnomalySignal;
import com.gamma.signal.Signal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The pure selection behind {@code GET /inv/investigations/{id}/data-changes} (LA-DATA-TRUST-1 S5): which
 * {@code collector.delivery.anomaly} Signals say a day the Investigation reads moved <em>after the analyst last looked</em>.
 *
 * <p>A Signal qualifies when ALL of: its {@code pipeline} delivers into the Investigation's Dataset; its {@code day} lies
 * inside the window's local calendar days (first day = the day of {@code from}, last = the day of {@code to} minus 1 ms, in the
 * window's zone — the same days the coverage route expects; an absent bound is open); and it is STRICTLY newer than
 * {@code lastLook}. The day-of-week mask and calendar exclusions are deliberately not applied: a day the window includes is
 * never silently dropped.
 */
final class DataChanges {

    private DataChanges() {
    }

    /**
     * The day a Signal names. The delivery manifest signals its bucket key as the file template spells it ({@code 20260902},
     * or {@code 2026090214} for an hourly feed), the tests and the SPA use ISO; both place a day, nothing else does.
     */
    static LocalDate parseDay(String text) {
        if (text.length() >= 8 && text.chars().allMatch(Character::isDigit))
            return LocalDate.parse(text.substring(0, 8), java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        return LocalDate.parse(text);
    }

    /** One qualifying anomaly. */
    record Change(String day, String kind, String file, String previousFile, Instant at, String pipeline) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("day", day);
            m.put("kind", kind);
            m.put("file", file);
            if (previousFile != null) m.put("previousFile", previousFile);
            m.put("at", at.toString());
            m.put("pipeline", pipeline);
            return m;
        }
    }

    /**
     * @param window   the evaluated window ({@code from}/{@code to} ISO instants, {@code timezone}) or {@code null} for an open one
     * @param lastLook the newest instant the analyst acted or read at (never null — falls back to the Investigation's creation)
     * @return the qualifying anomalies, oldest first
     */
    static List<Change> select(List<Signal> signals, Set<String> pipelines, Map<String, Object> window, Instant lastLook) {
        ZoneId zone = ZoneId.of(window != null && window.get("timezone") != null ? String.valueOf(window.get("timezone")) : "UTC");
        LocalDate first = window != null && window.get("from") != null
                ? Instant.parse(String.valueOf(window.get("from"))).atZone(zone).toLocalDate() : null;
        LocalDate last = window != null && window.get("to") != null
                ? Instant.parse(String.valueOf(window.get("to"))).minusMillis(1).atZone(zone).toLocalDate() : null;
        List<Change> out = new ArrayList<>();
        for (Signal s : signals) {
            if (!DeliveryAnomalySignal.TYPE.equals(s.type()) || !s.at().isAfter(lastLook)) continue;
            Map<String, Object> p = s.payload();
            String pipeline = str(p.get("pipeline"));
            String dayText = str(p.get("day"));
            if (pipeline == null || !pipelines.contains(pipeline) || dayText == null) continue;
            LocalDate day;
            try {
                day = parseDay(dayText);
            } catch (RuntimeException e) {
                continue;   // an unparseable day can be placed in no window — never guessed into one
            }
            if ((first != null && day.isBefore(first)) || (last != null && day.isAfter(last))) continue;
            out.add(new Change(day.toString(), str(p.get("kind")), str(p.get("file")), str(p.get("previousFile")), s.at(), pipeline));
        }
        out.sort(Comparator.comparing(Change::at).thenComparing(Change::day));
        return out;
    }

    /** The newest of the Investigation's creation, every sealed step's {@code at} and every sealed read's {@code readAt}. */
    static Instant lastLook(Map<String, Object> header, List<Map<String, Object>> log) {
        Instant newest = instant(header.get("createdAt"));
        for (Map<String, Object> entry : log) {
            newest = max(newest, instant(entry.get("at")));
            if (entry.get("read") instanceof Map<?, ?> r) newest = max(newest, instant(r.get("readAt")));
        }
        return newest == null ? Instant.EPOCH : newest;
    }

    private static Instant max(Instant a, Instant b) {
        return a == null ? b : b == null || a.isAfter(b) ? a : b;
    }

    private static Instant instant(Object o) {
        try {
            return o == null ? null : Instant.parse(String.valueOf(o));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o);
    }
}
