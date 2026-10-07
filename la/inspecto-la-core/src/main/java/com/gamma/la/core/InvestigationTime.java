package com.gamma.la.core;

import com.gamma.config.spec.SourceZoneGrammar;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Month;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

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
 * <h2>Calendar exclusions</h2>
 * {@code exclude} is a list of named dates / date ranges (holidays) removed from the window, in addition to the
 * slot and day mask. Each entry is {@code {date}} or {@code {from, to}} (local calendar dates, both INCLUSIVE),
 * optionally with a {@code name}; a bare {@code "YYYY-MM-DD"} string is shorthand for {@code {date}}. Like the day
 * mask they are wall-clock notions: they test the local calendar day ON WHICH THE EVENT FELL in the window's
 * {@code timezone}, which is therefore required. They are canonicalised to {@code {from, to[, name]}} sorted by
 * date, so the same exclusions seal the same bytes. At most {@link #MAX_EXCLUDES} entries.
 *
 * <h2>Recurring exclusion rules</h2>
 * An {@code exclude} entry may instead be a RULE: {@code {rule:"yearly", month, day[, name]}} (every 25 Dec) or
 * {@code {rule:"nthWeekday", month, weekday, nth[, name]}} (the 4th Thursday of November; {@code nth} 1-5, and a
 * 5th that a month lacks simply never matches). The RULE is what is sealed, not its expansion over the window, so
 * the sealed bytes stay canonical and an unbounded window needs no horizon. The rule is applied to the local
 * calendar day in the window's {@code timezone}, exactly like a named date ({@link #matches} for Java readers,
 * the same arithmetic in {@link #predicates} SQL), which is deterministic: no clock, no host zone. A yearly
 * {@code 02-29} matches leap years only. Canonical order: concrete entries (by date) then rules (by month, day or
 * nth/weekday, name); at most {@link #MAX_RULES} rules, on top of {@link #MAX_EXCLUDES} dates.
 */
public final class InvestigationTime {

    private InvestigationTime() {}

    public static final List<String> DAYS = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");
    /** The cap on {@code exclude} entries: a calendar of holidays, not a second Dataset. */
    public static final int MAX_EXCLUDES = 366;
    /** The cap on recurring {@code exclude} rules: a handful of annual holidays, not a calendar. */
    public static final int MAX_RULES = 32;
    private static final Set<String> WINDOW_KEYS = Set.of("from", "to", "slot", "days", "exclude", "timezone");

    /**
     * Validate an authored window and return its canonical form: instants normalised to UTC ({@link Instant#toString}),
     * the slot as {@code HH:mm}, the days in week order. Unknown keys, naive instants, an empty window, an
     * inverted range, an ambiguous slot or a zone-less slot/mask are all 422.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> window(Object raw, String origin) {
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

        List<Map<String, Object>> exclude = excludes(w.get("exclude"), origin + ".exclude");
        out.put("exclude", exclude);

        String zone = w.get("timezone") == null ? null : String.valueOf(w.get("timezone"));
        if (zone != null) {
            String refusal = SourceZoneGrammar.zoneRefusal(zone, origin + ".timezone");
            if (refusal != null) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, refusal);
        }
        if ((slot != null || days != null || exclude != null) && zone == null)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, origin + ": a slot, day mask or calendar exclusion is wall-clock time and needs an explicit "
                    + "'timezone' (an IANA region id) — there is no default, because the default would be the host's");
        out.put("timezone", zone);
        if (from == null && to == null && slot == null && days == null && exclude == null)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, origin + " must set at least one of from, to, slot, days, exclude");
        return out;
    }

    private static List<Map<String, Object>> excludes(Object raw, String origin) {
        if (raw == null) return null;
        if (!(raw instanceof List<?> l) || l.isEmpty() || l.size() > MAX_EXCLUDES + MAX_RULES)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, origin + " must be a non-empty list of at most " + MAX_EXCLUDES
                    + " dates plus " + MAX_RULES + " rules: a 'YYYY-MM-DD' string, {date}, {from, to} (inclusive), "
                    + "{rule:'yearly', month, day} or {rule:'nthWeekday', month, weekday, nth}, each with an optional 'name'");
        TreeMap<String, Map<String, Object>> sorted = new TreeMap<>();
        int i = 0, rules = 0;
        for (Object e : l) {
            String at = origin + "[" + i++ + "]";
            LocalDate from, to;
            String name = null;
            if (e instanceof Map<?, ?> rm && rm.get("rule") != null) {
                if (++rules > MAX_RULES)
                    throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, origin + " holds at most " + MAX_RULES + " recurring rules");
                Map<String, Object> rule = rule(rm, at);
                int month = (Integer) rule.get("month");
                String order = rule.containsKey("day") ? String.format("%02d", (Integer) rule.get("day"))
                        : rule.get("nth") + "/" + DAYS.indexOf(String.valueOf(rule.get("weekday")));
                // "~" sorts after every digit: concrete dates first, then the rules.
                sorted.put("~" + String.format("%02d", month) + "/" + rule.get("rule") + "/" + order + "/"
                        + (rule.get("name") == null ? "" : rule.get("name")), rule);
                continue;
            }
            if (e instanceof String s) {
                from = to = date(s, at);
            } else if (e instanceof Map<?, ?> m) {
                for (Object k : m.keySet())
                    if (!Set.of("date", "from", "to", "name").contains(String.valueOf(k)))
                        throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, at + ": unknown key '" + k + "' (allowed: date, from, to, name)");
                if (m.get("date") != null) {
                    if (m.get("from") != null || m.get("to") != null)
                        throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, at + ": give 'date' OR 'from' and 'to', not both");
                    from = to = date(m.get("date"), at + ".date");
                } else {
                    if (m.get("from") == null || m.get("to") == null)
                        throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, at + " needs 'date', or both 'from' and 'to'");
                    from = date(m.get("from"), at + ".from");
                    to = date(m.get("to"), at + ".to");
                    if (to.isBefore(from))
                        throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, at + ": 'from' must not be after 'to' (both inclusive)");
                }
                if (m.get("name") != null) name = String.valueOf(m.get("name")).trim();
                if (name != null && (name.isEmpty() || name.length() > 80))
                    throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, at + ".name must be 1-80 characters");
            } else {
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, at + " must be a 'YYYY-MM-DD' string, {date} or {from, to}");
            }
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("from", from.toString());
            c.put("to", to.toString());
            if (name != null) c.put("name", name);
            sorted.put(from + "/" + to + "/" + (name == null ? "" : name), c);
        }
        return new ArrayList<>(sorted.values());
    }

    /** One recurring rule, validated and canonicalised ({@code rule}, {@code month}, {@code day} | {@code weekday} + {@code nth}, {@code name}). */
    private static Map<String, Object> rule(Map<?, ?> m, String at) {
        String kind = String.valueOf(m.get("rule"));
        Set<String> allowed = "yearly".equals(kind) ? Set.of("rule", "month", "day", "name")
                : "nthWeekday".equals(kind) ? Set.of("rule", "month", "weekday", "nth", "name") : null;
        if (allowed == null)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, at + ".rule must be 'yearly' or 'nthWeekday', got '" + kind + "'");
        for (Object k : m.keySet())
            if (!allowed.contains(String.valueOf(k)))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, at + ": unknown key '" + k + "' for a '" + kind + "' rule (allowed: " + allowed + ")");
        int month = whole(m.get("month"), 1, 12, at + ".month");
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("rule", kind);
        c.put("month", month);
        if ("yearly".equals(kind)) {
            int day = whole(m.get("day"), 1, 31, at + ".day");
            if (day > Month.of(month).maxLength())
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, at + ": month " + month + " has no day " + day);
            c.put("day", day);
        } else {
            String wd = String.valueOf(m.get("weekday"));
            if (!DAYS.contains(wd)) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, at + ".weekday must be one of " + DAYS);
            c.put("weekday", wd);
            c.put("nth", whole(m.get("nth"), 1, 5, at + ".nth"));
        }
        if (m.get("name") != null) {
            String name = String.valueOf(m.get("name")).trim();
            if (name.isEmpty() || name.length() > 80) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, at + ".name must be 1-80 characters");
            c.put("name", name);
        }
        return c;
    }

    private static int whole(Object raw, int min, int max, String at) {
        if (raw instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue()) && n.intValue() >= min && n.intValue() <= max) return n.intValue();
        throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, at + " must be a whole number " + min + "-" + max);
    }

    /** Does the exclusion entry (a canonical date range or a recurring rule) remove this local calendar day? */
    public static boolean matches(Map<?, ?> entry, LocalDate day) {
        if (entry.get("rule") == null)
            return !day.isBefore(LocalDate.parse(String.valueOf(entry.get("from")))) && !day.isAfter(LocalDate.parse(String.valueOf(entry.get("to"))));
        if (day.getMonthValue() != ((Number) entry.get("month")).intValue()) return false;
        if ("yearly".equals(entry.get("rule"))) return day.getDayOfMonth() == ((Number) entry.get("day")).intValue();
        return day.getDayOfWeek() == DayOfWeek.of(DAYS.indexOf(String.valueOf(entry.get("weekday"))) + 1)
                && (day.getDayOfMonth() - 1) / 7 + 1 == ((Number) entry.get("nth")).intValue();
    }

    private static LocalDate date(Object raw, String origin) {
        String s = raw == null ? "" : String.valueOf(raw);
        try {
            if (s.matches("\\d{4}-\\d{2}-\\d{2}")) return LocalDate.parse(s);
        } catch (DateTimeException ignored) { /* falls through to the refusal */ }
        throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, origin + " must be a calendar date YYYY-MM-DD, got '" + s + "'");
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
    public static String instantExpr(String quotedCol, String naiveZone, List<String> binds) {
        if (naiveZone == null) return quotedCol;
        binds.add(naiveZone);
        return "timezone(?, " + quotedCol + ")";
    }

    /** The zone the wall-clock column {@code lt} is computed in: the window's, or UTC when it names none. */
    public static String localZone(Map<String, Object> w) {
        return w != null && w.get("timezone") != null ? String.valueOf(w.get("timezone")) : "UTC";
    }

    /**
     * Append the window's predicates to {@code where}, binding every value. They read two columns the caller
     * provides: {@code ts}, the event instant ({@code TIMESTAMPTZ}), and {@code lt}, its wall clock computed as
     * {@code timezone(localZone(w), ts)}. Session-independent by construction: the range compares epoch
     * milliseconds and the slot and mask read only {@code lt}.
     */
    public static void predicates(Map<String, Object> w, StringBuilder where, List<String> binds) {
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
        if (w.get("exclude") instanceof List<?> ex && !ex.isEmpty()) {
            // The local calendar day the event fell on, never the host's: lt is already in the window's zone.
            where.append(" AND NOT (");
            boolean first = true;
            for (Object o : ex) {
                Map<?, ?> r = (Map<?, ?>) o;
                where.append(first ? "" : " OR ");
                first = false;
                if (r.get("rule") == null) {
                    where.append("CAST(lt AS DATE) BETWEEN CAST(? AS DATE) AND CAST(? AS DATE)");
                    binds.add(String.valueOf(r.get("from")));
                    binds.add(String.valueOf(r.get("to")));
                } else if ("yearly".equals(r.get("rule"))) {
                    // The recurring rule on the local calendar day — same arithmetic as matches().
                    where.append("(month(lt) = CAST(? AS INTEGER) AND day(lt) = CAST(? AS INTEGER))");
                    binds.add(String.valueOf(r.get("month")));
                    binds.add(String.valueOf(r.get("day")));
                } else {
                    where.append("(month(lt) = CAST(? AS INTEGER) AND isodow(lt) = CAST(? AS INTEGER) AND (day(lt) - 1) // 7 + 1 = CAST(? AS INTEGER))");
                    binds.add(String.valueOf(r.get("month")));
                    binds.add(Integer.toString(DAYS.indexOf(String.valueOf(r.get("weekday"))) + 1));
                    binds.add(String.valueOf(r.get("nth")));
                }
            }
            where.append(")");
        }
    }

    /**
     * The plain-language form of an {@code expand}'s rung as READ ({@code read.query}), shared by the log line and
     * every Dossier rendering so they cannot drift: empty for a default rung, otherwise every non-default field.
     */
    @SuppressWarnings("unchecked")
    public static String rungClause(Map<String, Object> q) {
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
    public static String describe(Map<String, Object> w) {
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
        if (w.get("exclude") instanceof List<?> ex && !ex.isEmpty()) {
            List<String> xs = new ArrayList<>();
            for (Object o : ex) {
                Map<?, ?> r = (Map<?, ?>) o;
                String span = r.get("rule") == null ? (r.get("from").equals(r.get("to")) ? String.valueOf(r.get("from")) : r.get("from") + " to " + r.get("to"))
                        : "yearly".equals(r.get("rule")) ? "every " + String.format("%02d-%02d", ((Number) r.get("month")).intValue(), ((Number) r.get("day")).intValue())
                        : "the " + r.get("nth") + " " + r.get("weekday") + " of month " + r.get("month");
                xs.add(r.get("name") == null ? span : r.get("name") + " (" + span + ")");
            }
            parts.add("excluding " + String.join(", ", xs));
        }
        if (w.get("timezone") != null) parts.add(w.get("slot") != null || w.get("days") != null || w.get("exclude") != null
                ? "wall clock in " + w.get("timezone") : "(" + w.get("timezone") + ")");
        return String.join(", ", parts);
    }
}
