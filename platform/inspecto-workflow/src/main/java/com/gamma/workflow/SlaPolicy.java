package com.gamma.workflow;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * An SLA policy for one {@link ObjectType} (ASSURE-WORKFLOW-SLA-1, the {@code sla-policy} component kind,
 * {@code registry/sla-policies/<type>.toon}): per-priority response and resolution targets, counted in the working
 * time of a {@link Calendar business calendar}. The SLA sweep stamps an object's {@code responseDueAt} /
 * {@code dueAt} from it.
 *
 * <pre>
 * objectType: INCIDENT
 * calendar: { zone: Europe/London, workingDays: [MON, TUE, WED, THU, FRI], start: "09:00", end: "17:00",
 *             holidays: [2026-12-25] }
 * targets:  [ { priority: CRITICAL, responseMinutes: 30, resolutionMinutes: 240 },
 *             { priority: "*", resolutionMinutes: 2400 } ]
 * </pre>
 * {@code priority: "*"} is the fallback for any other (or no) priority.
 */
public record SlaPolicy(ObjectType objectType, Calendar calendar, Map<String, Target> targets) {

    /** Longest target a policy may set — ten years of minutes; beyond it a typo, not a service level. */
    static final long MAX_MINUTES = 10L * 366 * 24 * 60;
    public static final String ANY_PRIORITY = "*";

    /** One priority's targets, in working minutes; either may be {@code null} (not measured). */
    public record Target(Long responseMinutes, Long resolutionMinutes) {}

    /** The target for {@code priority}: its own row, else the {@code "*"} row, else none. */
    public Optional<Target> targetFor(String priority) {
        String p = priority == null ? null : priority.trim().toUpperCase(Locale.ROOT);
        Target t = p == null || p.isEmpty() ? null : targets.get(p);
        return Optional.ofNullable(t != null ? t : targets.get(ANY_PRIORITY));
    }

    /**
     * Working days, working hours, holidays and an explicit IANA zone — never the host's. {@code end == null} means
     * the working day runs to midnight; with every day and a {@code 00:00} start that is 24x7 (holidays still off).
     * Windows are wall-clock in {@link #zone}, so a DST day's 09:00–17:00 is still eight working hours, and a
     * 24-hour day that loses an hour counts 23.
     */
    public record Calendar(ZoneId zone, Set<DayOfWeek> workingDays, LocalTime start, LocalTime end,
                           Set<LocalDate> holidays) {

        /** A calendar with nothing to count in would spin forever; the walk gives up after this many days. */
        static final int MAX_DAYS = 4000;

        /** The instant {@code minutes} working minutes after {@code startMs} (epoch millis). */
        public long addWorkingMinutes(long startMs, long minutes) {
            Instant from = Instant.ofEpochMilli(startMs);
            long remaining = Math.multiplyExact(minutes, 60_000L);
            LocalDate day = from.atZone(zone).toLocalDate();
            for (int i = 0; i < MAX_DAYS; i++, day = day.plusDays(1)) {
                if (!workingDays.contains(day.getDayOfWeek()) || holidays.contains(day)) continue;
                Instant open = ZonedDateTime.of(day, start, zone).toInstant();
                Instant close = end == null ? day.plusDays(1).atStartOfDay(zone).toInstant()
                        : ZonedDateTime.of(day, end, zone).toInstant();
                Instant begin = open.isAfter(from) ? open : from;
                if (!begin.isBefore(close)) continue;
                long available = Duration.between(begin, close).toMillis();
                if (remaining <= available) return begin.plusMillis(remaining).toEpochMilli();
                remaining -= available;
            }
            throw new IllegalStateException("no working time within " + MAX_DAYS + " days of " + from);
        }
    }

    /** Parse + validate an {@code sla-policy} component; fail closed on anything malformed. */
    public static SlaPolicy fromComponent(String id, Map<String, Object> content) {
        if (content == null) throw new IllegalArgumentException("sla-policy content is required");
        ObjectType type = ObjectType.of(str(content.get("objectType")));
        if (type == null) throw new IllegalArgumentException("sla-policy.objectType is required (one of ALERT, INCIDENT, CASE, TASK)");
        if (id != null && !type.name().equalsIgnoreCase(id))
            throw new IllegalArgumentException("sla-policy objectType '" + type + "' must match the component id '" + id
                    + "' (one policy per object type)");
        Calendar cal = calendar(content.get("calendar"));
        if (!(content.get("targets") instanceof List<?> rows) || rows.isEmpty())
            throw new IllegalArgumentException("sla-policy.targets must list at least one priority");
        Map<String, Target> targets = new LinkedHashMap<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> m)) throw new IllegalArgumentException("every sla-policy target must be an object");
            String p = str(m.get("priority"));
            if (p == null) throw new IllegalArgumentException("every sla-policy target needs a priority (or \"*\")");
            p = p.toUpperCase(Locale.ROOT);
            Long response = minutes(m.get("responseMinutes"), "responseMinutes");
            Long resolution = minutes(m.get("resolutionMinutes"), "resolutionMinutes");
            if (response == null && resolution == null)
                throw new IllegalArgumentException("target " + p + " sets neither responseMinutes nor resolutionMinutes");
            if (response != null && resolution != null && response > resolution)
                throw new IllegalArgumentException("target " + p + ": responseMinutes exceeds resolutionMinutes");
            if (targets.put(p, new Target(response, resolution)) != null)
                throw new IllegalArgumentException("priority " + p + " has two targets");
        }
        return new SlaPolicy(type, cal, Map.copyOf(targets));
    }

    private static Calendar calendar(Object raw) {
        if (!(raw instanceof Map<?, ?> m))
            throw new IllegalArgumentException("sla-policy.calendar is required, with at least an explicit IANA zone");
        String z = str(m.get("zone"));
        // An explicit region id only: never the host's zone, never a bare offset that ignores DST.
        if (z == null || !ZoneId.getAvailableZoneIds().contains(z))
            throw new IllegalArgumentException("sla-policy.calendar.zone must be an IANA zone id such as Europe/London (got '" + z + "')");
        ZoneId zone = ZoneId.of(z);
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        if (m.get("workingDays") == null) days = EnumSet.allOf(DayOfWeek.class);
        else if (m.get("workingDays") instanceof List<?> list) {
            for (Object d : list) days.add(day(str(d)));
        } else throw new IllegalArgumentException("sla-policy.calendar.workingDays must be a list");
        if (days.isEmpty()) throw new IllegalArgumentException("sla-policy.calendar.workingDays must name at least one day");
        LocalTime start = time(m.get("start"), "start", LocalTime.MIDNIGHT);
        String rawEnd = str(m.get("end"));
        LocalTime end = rawEnd == null || "24:00".equals(rawEnd) ? null : time(rawEnd, "end", null);
        if (end != null && !end.isAfter(start))
            throw new IllegalArgumentException("sla-policy.calendar.end must be after start (overnight windows are not supported)");
        Set<LocalDate> holidays = new TreeSet<>();
        if (m.get("holidays") instanceof List<?> list) {
            for (Object h : list) {
                try {
                    holidays.add(LocalDate.parse(String.valueOf(h).trim()));
                } catch (DateTimeException e) {
                    throw new IllegalArgumentException("sla-policy.calendar.holidays: '" + h + "' is not an ISO date (yyyy-mm-dd)");
                }
            }
        } else if (m.get("holidays") != null) throw new IllegalArgumentException("sla-policy.calendar.holidays must be a list");
        return new Calendar(zone, Set.copyOf(days), start, end, Set.copyOf(holidays));
    }

    private static DayOfWeek day(String s) {
        if (s != null) {
            String u = s.toUpperCase(Locale.ROOT);
            for (DayOfWeek d : DayOfWeek.values()) if (d.name().equals(u) || d.name().startsWith(u) && u.length() == 3) return d;
        }
        throw new IllegalArgumentException("sla-policy.calendar.workingDays: '" + s + "' is not a day (MON … SUN)");
    }

    private static LocalTime time(Object raw, String key, LocalTime dflt) {
        String s = str(raw);
        if (s == null) return dflt;
        try {
            return LocalTime.parse(s);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("sla-policy.calendar." + key + " must be HH:mm (got '" + s + "')");
        }
    }

    private static Long minutes(Object raw, String key) {
        if (raw == null) return null;
        long v;
        try {
            v = raw instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a whole number of minutes");
        }
        if (v < 1 || v > MAX_MINUTES) throw new IllegalArgumentException(key + " must be between 1 and " + MAX_MINUTES);
        return v;
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}
