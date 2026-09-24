package com.gamma.geolink;

import com.gamma.config.spec.SourceZoneGrammar;
import com.gamma.control.ApiException;
import com.gamma.control.ErrorCodes;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The time model of an Investigation (LA-13, plan §2.5): the {@code window} shape, its validation, and the SQL that
 * applies it to a Dataset read.
 *
 * <h2>The timezone contract</h2>
 * DuckDB's session {@code TimeZone} is the HOST's, so nothing here ever lets the session zone touch a value:
 * <ol>
 *   <li><b>The event instant.</b> The Investigation binds one time column ({@code timeCol}). A
 *       {@code TIMESTAMP WITH TIME ZONE} column is already an instant. A naive {@code TIMESTAMP} column is read as a
 *       wall clock in the Investigation's declared {@code timeColZone} (an IANA region id; {@code UTC} when not
 *       given, and recorded as such in the header, so the assumption is visible rather than implied) —
 *       {@code timezone(timeColZone, col)}. A {@code TIMESTAMPTZ} column refuses a {@code timeColZone}: it would be
 *       ignored, and an ignored declaration is a lie in the evidence.</li>
 *   <li><b>The absolute range</b> {@code [from, to)} — half-open — is two instants. Each must carry an offset or
 *       {@code Z}; a naive date-time is refused, because it would otherwise mean "the host's wall clock". They are
 *       compared as epoch milliseconds, which no session setting can shift.</li>
 *   <li><b>The intraday slot</b> {@code [start, end)} and the <b>day-of-week mask</b> are wall-clock notions, so
 *       they are evaluated in the window's own {@code timezone} (required whenever a slot or mask is given — there
 *       is no default, because a default is exactly what the host would supply). {@code start > end} crosses
 *       midnight ({@code 22:00–04:00} = 22:00 to 23:59:59.999 and 00:00 to 03:59:59.999); {@code start == end} is
 *       refused as ambiguous. The day mask tests the local calendar day ON WHICH THE EVENT FELL, not the day its
 *       slot began: with {@code days:[FRI]}, a Friday 23:00 event is in and a Saturday 01:00 event is not.</li>
 *   <li><b>Distinct days</b> ({@code minDistinctDays}) count local calendar dates in the window's
 *       {@code timezone}, or UTC when the rung has no zoned window.</li>
 * </ol>
 * Zones are validated by {@link SourceZoneGrammar} — the set measured to be a strict subset of what DuckDB accepts,
 * so a zone that passes here always evaluates (offset forms such as {@code +05:30} are refused there too).
 *
 * <p>⏳ Not built: calendar exclusions, comparison mode (two windows diffed), time-respecting paths.
 */
final class InvestigationTime {

    private InvestigationTime() {}

    static final List<String> DAYS = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");
    private static final Set<String> WINDOW_KEYS = Set.of("from", "to", "slot", "days", "timezone");

    /**
     * Validate an authored window and return its canonical form: instants normalised to UTC ({@link Instant#toString}),
     * the slot as {@code HH:mm}, the days in week order. Unknown keys, naive instants, an empty window, an
     * inverted range, an ambiguous slot or a zone-less slot/mask are all 422.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> window(Object raw, String origin) {
        if (!(raw instanceof Map<?, ?> m)) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, origin + " must be an object");
        Map<String, Object> w = (Map<String, Object>) m;
        for (String k : w.keySet())
            if (!WINDOW_KEYS.contains(k))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, origin + ": unknown key '" + k + "' (allowed: " + WINDOW_KEYS + ")");
        Map<String, Object> out = new LinkedHashMap<>();
        Instant from = instant(w.get("from"), origin + ".from");
        Instant to = instant(w.get("to"), origin + ".to");
        if (from != null && to != null && !from.isBefore(to))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, origin + ": 'from' must be before 'to' (the range is half-open [from, to))");
        out.put("from", from == null ? null : from.toString());
        out.put("to", to == null ? null : to.toString());

        Map<String, Object> slot = null;
        if (w.get("slot") != null) {
            if (!(w.get("slot") instanceof Map<?, ?> s)) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, origin + ".slot must be an object {start, end}");
            LocalTime start = clock(s.get("start"), origin + ".slot.start");
            LocalTime end = clock(s.get("end"), origin + ".slot.end");
            if (start.equals(end))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, origin + ".slot: start equals end — say which you mean with a range or no slot");
            slot = new LinkedHashMap<>();
            slot.put("start", start.toString());
            slot.put("end", end.toString());
        }
        out.put("slot", slot);

        List<String> days = null;
        if (w.get("days") != null) {
            if (!(w.get("days") instanceof List<?> l) || l.isEmpty())
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, origin + ".days must be a non-empty list of " + DAYS);
            days = new ArrayList<>();
            for (String d : DAYS) if (l.contains(d)) days.add(d);
            if (days.size() != l.size())
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, origin + ".days: every entry must be one of " + DAYS + ", once");
        }
        out.put("days", days);

        String zone = w.get("timezone") == null ? null : String.valueOf(w.get("timezone"));
        if (zone != null) {
            String refusal = SourceZoneGrammar.zoneRefusal(zone, origin + ".timezone");
            if (refusal != null) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, refusal);
        }
        if ((slot != null || days != null) && zone == null)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, origin + ": a slot or day mask is wall-clock time and needs an explicit "
                    + "'timezone' (an IANA region id) — there is no default, because the default would be the host's");
        out.put("timezone", zone);
        if (from == null && to == null && slot == null && days == null)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, origin + " must set at least one of from, to, slot, days");
        return out;
    }

    private static Instant instant(Object raw, String origin) {
        if (raw == null) return null;
        String s = String.valueOf(raw);
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException notOffset) {
            try {
                return Instant.parse(s);
            } catch (DateTimeParseException e) {
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, origin + " must be an ISO-8601 instant WITH an offset or Z (e.g. "
                        + "2026-09-01T00:00:00Z), got '" + s + "' — a naive date-time would mean the host's clock");
            }
        }
    }

    private static LocalTime clock(Object raw, String origin) {
        String s = raw == null ? "" : String.valueOf(raw);
        if (!s.matches("\\d{2}:\\d{2}")) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, origin + " must be HH:mm, got '" + s + "'");
        try {
            return LocalTime.parse(s);
        } catch (DateTimeException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, origin + " must be HH:mm, got '" + s + "'");
        }
    }

    /**
     * The event-instant expression for the bound time column (a {@code TIMESTAMPTZ}). {@code naiveZone} is the
     * header's {@code timeColZone} for a naive column, or {@code null} for a column that is already an instant.
     * The zone is bound, never interpolated.
     */
    static String instantExpr(String quotedCol, String naiveZone, List<String> binds) {
        if (naiveZone == null) return quotedCol;
        binds.add(naiveZone);
        return "timezone(?, " + quotedCol + ")";
    }

    /** The zone the wall-clock column {@code lt} is computed in: the window's, or UTC when it names none. */
    static String localZone(Map<String, Object> w) {
        return w != null && w.get("timezone") != null ? String.valueOf(w.get("timezone")) : "UTC";
    }

    /**
     * Append the window's predicates to {@code where}, binding every value. They read two columns the caller
     * provides: {@code ts}, the event instant ({@code TIMESTAMPTZ}), and {@code lt}, its wall clock computed as
     * {@code timezone(localZone(w), ts)}. Session-independent by construction: the range compares epoch
     * milliseconds and the slot and mask read only {@code lt}.
     */
    static void predicates(Map<String, Object> w, StringBuilder where, List<String> binds) {
        if (w.get("from") != null) {
            where.append(" AND epoch_ms(ts) >= CAST(? AS BIGINT)");
            binds.add(Long.toString(Instant.parse(String.valueOf(w.get("from"))).toEpochMilli()));
        }
        if (w.get("to") != null) {
            where.append(" AND epoch_ms(ts) < CAST(? AS BIGINT)");
            binds.add(Long.toString(Instant.parse(String.valueOf(w.get("to"))).toEpochMilli()));
        }
        if (w.get("slot") instanceof Map<?, ?> s) {
            String start = String.valueOf(s.get("start")), end = String.valueOf(s.get("end"));
            // ⚠ start > end crosses midnight: OR, not AND. Half-open at the end in both shapes.
            where.append(" AND (CAST(lt AS TIME) >= CAST(? AS TIME) ")
                 .append(start.compareTo(end) < 0 ? "AND" : "OR").append(" CAST(lt AS TIME) < CAST(? AS TIME))");
            binds.add(start + ":00");
            binds.add(end + ":00");
        }
        if (w.get("days") instanceof List<?> days) {
            where.append(" AND isodow(lt) IN (")
                 .append(String.join(",", Collections.nCopies(days.size(), "CAST(? AS INTEGER)"))).append(")");
            for (Object d : days) binds.add(Integer.toString(DAYS.indexOf(String.valueOf(d)) + 1));
        }
    }

    /**
     * The plain-language form of an {@code expand}'s rung as READ ({@code read.query}), shared by the log line and
     * every Dossier rendering so they cannot drift: empty for a default rung, otherwise every non-default field.
     */
    @SuppressWarnings("unchecked")
    static String rungClause(Map<String, Object> q) {
        if (q == null) return "";
        List<String> parts = new ArrayList<>();
        if (q.get("direction") != null && !"either".equals(q.get("direction"))) parts.add("direction " + q.get("direction"));
        if (q.get("linkKinds") instanceof List<?> k) parts.add("link kinds " + String.join(", ", (List<String>) k));
        if (q.get("window") instanceof Map<?, ?> w) parts.add("window " + describe((Map<String, Object>) w));
        if (q.get("minEvents") instanceof Number n && n.intValue() > 1) parts.add("at least " + n + " events per link");
        if (q.get("minDistinctDays") != null) parts.add("on at least " + q.get("minDistinctDays") + " distinct days");
        if (q.get("candidateDegreeMin") != null || q.get("candidateDegreeMax") != null)
            parts.add("candidate degree " + (q.get("candidateDegreeMin") == null ? "0" : q.get("candidateDegreeMin"))
                    + "–" + (q.get("candidateDegreeMax") == null ? "any" : q.get("candidateDegreeMax"))
                    + (q.get("window") != null ? " within the window" : ""));
        if (q.get("maxFanOut") != null) parts.add("at most " + q.get("maxFanOut") + " links per entity, strongest first");
        return parts.isEmpty() ? "" : " (" + String.join("; ", parts) + ")";
    }

    /** The plain-language form of a window, for the log line, the steps rendering and the method statement. */
    @SuppressWarnings("unchecked")
    static String describe(Map<String, Object> w) {
        if (w == null) return "the full time range";
        List<String> parts = new ArrayList<>();
        if (w.get("from") != null || w.get("to") != null)
            parts.add("from " + (w.get("from") == null ? "the beginning" : w.get("from")) + " up to "
                    + (w.get("to") == null ? "now" : w.get("to") + " (exclusive)"));
        if (w.get("slot") instanceof Map<?, ?> s) {
            boolean crosses = String.valueOf(s.get("start")).compareTo(String.valueOf(s.get("end"))) > 0;
            parts.add("daily " + s.get("start") + "–" + s.get("end") + (crosses ? " (crossing midnight)" : ""));
        }
        if (w.get("days") instanceof List<?> d) parts.add("on " + String.join(", ", (List<String>) d));
        if (w.get("timezone") != null) parts.add(w.get("slot") != null || w.get("days") != null
                ? "wall clock in " + w.get("timezone") : "(" + w.get("timezone") + ")");
        return String.join(", ", parts);
    }
}
