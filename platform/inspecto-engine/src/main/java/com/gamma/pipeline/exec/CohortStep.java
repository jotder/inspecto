package com.gamma.pipeline.exec;

import com.gamma.analytics.cohort.Funnel;
import com.gamma.analytics.cohort.Retention;
import com.gamma.analytics.time.TimeGrain;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.gamma.pipeline.exec.TimeSeriesStep.bad;
import static com.gamma.pipeline.exec.TimeSeriesStep.bound;
import static com.gamma.pipeline.exec.TimeSeriesStep.grain;
import static com.gamma.pipeline.exec.TimeSeriesStep.known;
import static com.gamma.pipeline.exec.TimeSeriesStep.required;
import static com.gamma.pipeline.exec.TimeSeriesStep.wholeNumber;
import static com.gamma.pipeline.exec.TimeSeriesStep.zone;

/**
 * The config grammar of the cohort Steps (ANALYTICS-STEPS-TS-1) — {@code transform.cohort.retention} ({@code steps:} kind
 * {@code retention}; the kernel's {@link Retention}) and {@code transform.cohort.funnel} (kind {@code funnel}; the
 * kernel's {@link Funnel}) — parsed once, so the run ({@link RowShaper}), the recipe compiler and the save-time check
 * refuse the same configs with the same words. Both summarise: the output is the kernel's table, not the inbound rows.
 * The time grammar (grain, timezone, dates) is {@link TimeSeriesStep}'s.
 *
 * <p>🔴 <b>No authored text reaches the SQL as text.</b> Column names are validated identifiers, double-quoted by the
 * kernel; funnel step names are quoted string literals; the as-of, grain, zone and window are parsed values re-printed.
 *
 * <p><b>The as-of is always stated</b> ({@code through}), never "now": a retention re-run over the same rows gives the
 * same cells.
 */
public final class CohortStep {

    private CohortStep() {}

    static final String RETENTION = "transform.cohort.retention";
    static final String FUNNEL = "transform.cohort.funnel";

    /** The highest period a retention reports. */
    public static final int MAX_RETENTION_PERIODS = TimeSeriesStep.MAX_PERIODS;
    /** The retention output, in order: the cohort's bucket start, the period, the cohort size, the active entities, the rate. */
    public static final List<String> RETENTION_OUTPUT = List.of("cohort", "period", "size", "active", "rate");
    /** The funnel output, in order. */
    public static final List<String> FUNNEL_OUTPUT = List.of("step", "name", "entities", "from_previous", "from_first");

    /** The longest funnel window: 100 years, far inside DuckDB's interval range (a longer one fails the run). */
    public static final Duration MAX_WITHIN = Duration.ofDays(36_500);

    private static final Pattern DURATION = Pattern.compile("^(\\d{1,9})\\s*([smhd])$");

    /** A parsed retention config. */
    public record Cohorts(String entityColumn, String timeColumn, Retention.Params params) {

        public List<String> inputColumns() {
            return List.of(entityColumn, timeColumn);
        }

        /** The kernel's retention query over {@code input}. */
        public String select(String input) {
            return Retention.sql(input, entityColumn, timeColumn, params);
        }
    }

    /** A parsed funnel config. */
    public record Steps(String entityColumn, String stepColumn, String timeColumn, Funnel.Params params) {

        public List<String> inputColumns() {
            return List.of(entityColumn, stepColumn, timeColumn);
        }

        /** The kernel's funnel query over {@code input}. */
        public String select(String input) {
            return Funnel.sql(input, entityColumn, stepColumn, timeColumn, params);
        }
    }

    /** Parse and validate a retention config; {@link IllegalArgumentException} names the first problem. */
    public static Cohorts parseRetention(Map<?, ?> cfg) {
        known(cfg, RETENTION, Set.of("entity_column", "time_column", "grain", "timezone", "through", "from", "mode",
                "max_periods"));
        String entity = required(cfg, "entity_column", RETENTION, "the customer, account or other entity followed");
        String time = required(cfg, "time_column", RETENTION, "when each event happened");
        TimeGrain grain = grain(cfg, RETENTION);
        if (grain.compareTo(TimeGrain.HOUR) < 0)
            throw bad(RETENTION, "'grain' must be hour or coarser, not " + grain.wire());
        ZoneId zone = zone(cfg, RETENTION);
        LocalDateTime through = bound(cfg, "through", RETENTION);
        if (through == null)
            throw bad(RETENTION, "needs a 'through' - the as-of date (2026-06-30): later events and periods are not "
                    + "observed yet");
        LocalDateTime from = bound(cfg, "from", RETENTION);
        if (from != null && grain.truncate(from).isAfter(grain.truncate(through)))
            throw bad(RETENTION, "'from' " + cfg.get("from") + " is after 'through' " + cfg.get("through"));
        Retention.Mode mode = Retention.Mode.EXACT;
        Object m = cfg.get("mode");
        if (m != null && !m.toString().isBlank()) {
            String s = m.toString().trim().toLowerCase(Locale.ROOT);
            if (s.equals("unbounded")) mode = Retention.Mode.UNBOUNDED;
            else if (!s.equals("exact"))
                throw bad(RETENTION, "'mode' '" + m + "' is not exact (active in that period) or unbounded (active "
                        + "in that period or later)");
        }
        int maxPeriods = wholeNumber(cfg, "max_periods", RETENTION, 0, MAX_RETENTION_PERIODS,
                Retention.DEFAULT_MAX_PERIODS);
        if (entity.equalsIgnoreCase(time))
            throw bad(RETENTION, "'entity_column' and 'time_column' must differ");
        return new Cohorts(entity, time, new Retention.Params(grain, zone, through, from, mode, maxPeriods));
    }

    /** Parse and validate a funnel config; {@link IllegalArgumentException} names the first problem. */
    public static Steps parseFunnel(Map<?, ?> cfg) {
        known(cfg, FUNNEL, Set.of("entity_column", "step_column", "time_column", "steps", "within"));
        String entity = required(cfg, "entity_column", FUNNEL, "the customer, account or other entity followed");
        String step = required(cfg, "step_column", FUNNEL, "the column naming what each event is (e.g. EVENT_TYPE)");
        String time = required(cfg, "time_column", FUNNEL, "when each event happened");
        List<String> steps = new ArrayList<>();
        Object raw = cfg.get("steps");
        if (raw instanceof List<?> l) {
            for (Object o : l) if (o != null && !o.toString().isBlank()) steps.add(o.toString().trim());
        } else if (raw != null && !raw.toString().isBlank()) {
            throw bad(FUNNEL, "'steps' must be a list of step names, in funnel order");
        }
        Duration within = null;
        Object w = cfg.get("within");
        if (w != null && !w.toString().isBlank()) {
            Matcher d = DURATION.matcher(w.toString().trim().toLowerCase(Locale.ROOT));
            if (!d.matches())
                throw bad(FUNNEL, "'within' '" + w + "' is not a duration (3600s, 90m, 48h, 30d)");
            long n = Long.parseLong(d.group(1));
            within = switch (d.group(2)) {
                case "s" -> Duration.ofSeconds(n);
                case "m" -> Duration.ofMinutes(n);
                case "h" -> Duration.ofHours(n);
                default -> Duration.ofDays(n);
            };
            if (within.compareTo(MAX_WITHIN) > 0)
                throw bad(FUNNEL, "'within' " + w + " is longer than " + MAX_WITHIN.toDays() + "d");
        }
        Funnel.Params params;
        try {
            params = new Funnel.Params(steps, within);
        } catch (IllegalArgumentException e) {
            throw bad(FUNNEL, e.getMessage());
        }
        Set<String> seen = new HashSet<>();
        for (String c : List.of(entity, step, time))
            if (!seen.add(c.toLowerCase(Locale.ROOT)))
                throw bad(FUNNEL, "column '" + c + "' is named twice - the entity, step and time columns must differ");
        return new Steps(entity, step, time, params);
    }

    /** {@code null} when a funnel's time column is a {@code TIMESTAMP} (with or without a zone), else the refusal. */
    public static String funnelTypeRefusal(String timeColumn, String duckType) {
        String t = duckType == null ? "" : duckType.toUpperCase(Locale.ROOT).trim();
        return t.startsWith("TIMESTAMP") ? null
                : FUNNEL + ": '" + timeColumn + "' must be a TIMESTAMP column, not " + duckType
                        + " - a funnel orders events by the instant";
    }
}
