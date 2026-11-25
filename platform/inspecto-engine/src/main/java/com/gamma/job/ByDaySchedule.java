package com.gamma.job;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-Job {@code schedule:} and {@code inputs:} configuration for {@code schedule.mode: by_day}
 * ({@code DATED-REFERENCE-RUNS-1} P2/P3, decisions D-DR6..D-DR9).
 */
public record ByDaySchedule(
        Mode mode,
        Duration quiet,
        Duration maxHold,
        Order order,
        int maxParallel,
        String deadline,
        OnDeadline onDeadline,
        int rerunWithinDays,
        boolean rerunOnReferenceChange,
        Map<String, InputCondition> inputs) {

    public static final String SCHEDULE_PREFIX = "schedule.";
    public static final String INPUTS_PREFIX = "inputs.";

    public static final Duration DEFAULT_QUIET = Duration.ofMinutes(5);
    public static final Duration DEFAULT_MAX_HOLD = Duration.ofMinutes(30);
    public static final int DEFAULT_MAX_PARALLEL = 2;
    public static final int DEFAULT_RERUN_WITHIN_DAYS = 7;

    public enum Mode {
        DEFAULT,
        BY_DAY;

        public static Mode from(String s) {
            if (s == null || s.isBlank()) return DEFAULT;
            return switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "default", "none" -> DEFAULT;
                case "by_day" -> BY_DAY;
                default -> throw new IllegalArgumentException("schedule.mode must be 'default' or 'by_day', got: '" + s + "'");
            };
        }
    }

    public enum Order {
        OLDEST_FIRST,
        NEWEST_FIRST,
        CURRENT_DAY_FIRST;

        public static Order from(String s) {
            if (s == null || s.isBlank()) return OLDEST_FIRST;
            return switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "oldest_first" -> OLDEST_FIRST;
                case "newest_first" -> NEWEST_FIRST;
                case "current_day_first" -> CURRENT_DAY_FIRST;
                default -> throw new IllegalArgumentException(
                        "schedule.order must be 'oldest_first', 'newest_first', or 'current_day_first', got: '" + s + "'");
            };
        }
    }

    public enum OnDeadline {
        PREVIOUS,
        FAIL,
        SKIP;

        public static OnDeadline from(String s) {
            if (s == null || s.isBlank()) return PREVIOUS;
            return switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "previous" -> PREVIOUS;
                case "fail" -> FAIL;
                case "skip" -> SKIP;
                default -> throw new IllegalArgumentException(
                        "schedule.on_deadline must be 'previous', 'fail', or 'skip', got: '" + s + "'");
            };
        }
    }

    public record InputCondition(String name, String role, boolean notEmpty, String covers, String freshWithin) {}

    public boolean isByDay() { return mode == Mode.BY_DAY; }

    public static ByDaySchedule parse(String jobName, Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return new ByDaySchedule(Mode.DEFAULT, DEFAULT_QUIET, DEFAULT_MAX_HOLD, Order.OLDEST_FIRST,
                    DEFAULT_MAX_PARALLEL, null, OnDeadline.PREVIOUS, DEFAULT_RERUN_WITHIN_DAYS, false, Map.of());
        }
        Mode mode = Mode.from(params.get(SCHEDULE_PREFIX + "mode"));
        Duration quiet = parseDuration(params.get(SCHEDULE_PREFIX + "quiet"), DEFAULT_QUIET, "schedule.quiet");
        Duration maxHold = parseDuration(params.get(SCHEDULE_PREFIX + "max_hold"), DEFAULT_MAX_HOLD, "schedule.max_hold");
        Order order = Order.from(params.get(SCHEDULE_PREFIX + "order"));
        int maxParallel = DEFAULT_MAX_PARALLEL;
        String mp = params.get(SCHEDULE_PREFIX + "max_parallel");
        if (mp != null && !mp.isBlank()) {
            try {
                maxParallel = Integer.parseInt(mp.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Job '" + jobName + "': schedule.max_parallel must be an integer, got: '" + mp + "'");
            }
            if (maxParallel < 1) {
                throw new IllegalArgumentException("Job '" + jobName + "': schedule.max_parallel must be >= 1, got: " + maxParallel);
            }
        }
        String deadline = params.get(SCHEDULE_PREFIX + "deadline");
        if (deadline != null && deadline.isBlank()) deadline = null;
        if (deadline != null) validateDeadline(jobName, deadline);
        OnDeadline onDeadline = OnDeadline.from(params.get(SCHEDULE_PREFIX + "on_deadline"));

        // D-DR9: off by default; when enabled, default window is 7 days
        boolean rerun = false;
        int withinDays = DEFAULT_RERUN_WITHIN_DAYS;
        String rawRerun = params.get(SCHEDULE_PREFIX + "rerun_on_reference_change");
        String rawWithin = params.get(SCHEDULE_PREFIX + "rerun_on_reference_change.within");
        if (rawWithin != null && !rawWithin.isBlank()) {
            rerun = true;
            withinDays = (int) Math.max(1, parseDuration(rawWithin, Duration.ofDays(DEFAULT_RERUN_WITHIN_DAYS),
                    "schedule.rerun_on_reference_change.within").toDays());
        } else if (rawRerun != null && !rawRerun.isBlank()) {
            rerun = !"false".equalsIgnoreCase(rawRerun.trim());
        }

        Map<String, InputCondition> inputs = parseInputs(params);
        return new ByDaySchedule(mode, quiet, maxHold, order, maxParallel, deadline, onDeadline,
                withinDays, rerun, Map.copyOf(inputs));
    }

    private static Map<String, InputCondition> parseInputs(Map<String, String> params) {
        Map<String, Map<String, String>> grouped = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!e.getKey().startsWith(INPUTS_PREFIX)) continue;
            String rest = e.getKey().substring(INPUTS_PREFIX.length());
            int dot = rest.indexOf('.');
            if (dot <= 0) continue;
            String inputName = rest.substring(0, dot);
            String field = rest.substring(dot + 1);
            grouped.computeIfAbsent(inputName, k -> new LinkedHashMap<>()).put(field, e.getValue());
        }
        Map<String, InputCondition> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> e : grouped.entrySet()) {
            Map<String, String> m = e.getValue();
            out.put(e.getKey(), new InputCondition(
                    e.getKey(),
                    m.getOrDefault("role", "reference"),
                    "true".equalsIgnoreCase(m.getOrDefault("not_empty", "false")),
                    m.get("covers"),
                    m.get("fresh_within")));
        }
        return out;
    }

    private static final Pattern SHORT_DUR = Pattern.compile("^(\\d+)\\s*([smhd])$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NEXT_DAY_TIME = Pattern.compile("^(\\d{1,2}:\\d{2})\\s+next\\s+day$", Pattern.CASE_INSENSITIVE);

    public static Duration parseDuration(String raw, Duration fallback, String field) {
        if (raw == null || raw.isBlank()) return fallback;
        String s = raw.trim();
        Matcher m = SHORT_DUR.matcher(s);
        if (m.matches()) {
            long n = Long.parseLong(m.group(1));
            return switch (m.group(2).toLowerCase(Locale.ROOT)) {
                case "s" -> Duration.ofSeconds(n);
                case "m" -> Duration.ofMinutes(n);
                case "h" -> Duration.ofHours(n);
                case "d" -> Duration.ofDays(n);
                default -> fallback;
            };
        }
        try {
            return Duration.parse(s);
        } catch (Exception e) {
            throw new IllegalArgumentException(field + " must be a duration (e.g. '5m', '30m', '7d', 'PT5M'), got: '" + raw + "'");
        }
    }

    private static void validateDeadline(String jobName, String deadline) {
        String s = deadline.trim();
        if (NEXT_DAY_TIME.matcher(s).matches() || SHORT_DUR.matcher(s).matches()) return;
        try {
            LocalTime.parse(s);
        } catch (Exception e) {
            try {
                Duration.parse(s);
            } catch (Exception e2) {
                throw new IllegalArgumentException("Job '" + jobName + "': invalid schedule.deadline '" + deadline
                        + "' (use e.g. '09:00 next day', '09:00', or '2h')");
            }
        }
    }

    /** Compute the deadline {@link Instant} for a business day {@code day} ({@code yyyy-MM-dd}). */
    public Instant deadlineForDay(String day, ZoneId zone) {
        if (deadline == null || day == null) return null;
        LocalDate d;
        try {
            d = LocalDate.parse(day);
        } catch (Exception e) {
            return null;
        }
        ZoneId z = zone == null ? ZoneId.of("UTC") : zone;
        String s = deadline.trim();
        Matcher nd = NEXT_DAY_TIME.matcher(s);
        if (nd.matches()) {
            LocalTime t = LocalTime.parse(nd.group(1).length() == 4 ? "0" + nd.group(1) : nd.group(1));
            return ZonedDateTime.of(d.plusDays(1), t, z).toInstant();
        }
        if (SHORT_DUR.matcher(s).matches() || s.startsWith("PT") || s.startsWith("P")) {
            Duration dur = parseDuration(s, Duration.ZERO, "schedule.deadline");
            return ZonedDateTime.of(d.plusDays(1), LocalTime.MIDNIGHT, z).toInstant().plus(dur);
        }
        LocalTime t = LocalTime.parse(s.length() == 4 ? "0" + s : s);
        return ZonedDateTime.of(d, t, z).toInstant();
    }

    /** Sort comparator for pending days according to {@link #order()}. */
    public List<String> sortDays(List<String> days, String currentDay) {
        return days.stream().sorted((a, b) -> {
            if (a == null && b == null) return 0;
            if (a == null) return -1;
            if (b == null) return 1;
            return switch (order) {
                case OLDEST_FIRST -> a.compareTo(b);
                case NEWEST_FIRST -> b.compareTo(a);
                case CURRENT_DAY_FIRST -> {
                    boolean aCur = a.equals(currentDay);
                    boolean bCur = b.equals(currentDay);
                    if (aCur != bCur) yield aCur ? -1 : 1;
                    yield a.compareTo(b);
                }
            };
        }).toList();
    }
}
