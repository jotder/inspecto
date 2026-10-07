package com.gamma.pipeline;

import com.gamma.api.PublicApi;
import com.gamma.util.Values;

import java.util.Map;

/**
 * <b>T13 — the entry-node trigger.</b> A pipeline's entry node (no inbound {@code data} edge — typically
 * {@code acquisition}/{@code adapter}) carries a {@code trigger:}; everything downstream is data-driven
 * (§3.6). This parses that config into a typed trigger and classifies which of the two schedulers (§3.8)
 * drives the pipeline — so the engine can route a pipeline to the loop scheduler vs the custom-function/event
 * scheduler from the graph alone, with no separate mechanism.
 *
 * <p>Trigger forms (§3.6):
 * <ul>
 *   <li>{@code {type: schedule, every: 60s}} — fixed-interval (today's poll) ⇒ {@link Scheduler#LOOP}.</li>
 *   <li>{@code {type: schedule, cron: "0 0/5 * * * *"}} — cron ⇒ {@link Scheduler#LOOP}.</li>
 *   <li>{@code {type: event, on: commit, from: flows/<id>}} / {@code {on: <EVENT_TYPE>}}, optional
 *       {@code coalesce: 30s} ⇒ {@link Scheduler#EVENT} (admitted under the non-overlapping lock, storms
 *       coalesced — see {@code TriggerCoalescer}).</li>
 *   <li>{@code {type: manual}} ⇒ {@link Scheduler#MANUAL} ({@code POST /pipelines/{id}/trigger}).</li>
 *   <li>{@code {type: stream, records: 500, max_wait: 5s}} — the continuous lane (ASSURE-PUSH-INGEST-1,
 *       option B of STREAM-CONSUMER-1): a lane per Pipeline holds its Collector's connector open and drains a
 *       slice once the backlog reaches {@code records} (default 1000) or has waited {@code max_wait} (default
 *       5 s). Still {@link Scheduler#LOOP}: the ordinary ticks keep running as the floor, and the lane only adds
 *       earlier drains through the same acquire + ingest path (the in-flight fence and slice frontier hold).</li>
 *   <li><b>absent</b> ⇒ {@link Kind#DEFAULT_POLL} — the service poll interval, so a lifted legacy
 *       pipeline behaves exactly as today.</li>
 * </ul>
 *
 * <p>Durations accept the engine's {@code Ns}/{@code Nm}/{@code Nh}/{@code Nd} suffix convention (a bare
 * number is seconds). The {@code cron} string is carried verbatim; the loop scheduler validates it via
 * {@code CronExpression} when it arms the schedule.
 */
@PublicApi(since = "4.0.0")
public record PipelineTrigger(Kind kind, long everyMs, String cron, String on, String from, long coalesceMs,
                              long streamRecords) {

    /** The literal trigger shape declared on the entry node. */
    public enum Kind { SCHEDULE_INTERVAL, SCHEDULE_CRON, EVENT, MANUAL, DEFAULT_POLL, STREAM }

    /** {@code trigger: {type: stream}} defaults: drain at 1000 records or after 5 s of waiting. */
    public static final long DEFAULT_STREAM_RECORDS = 1000;
    public static final long DEFAULT_STREAM_MAX_WAIT_MS = 5_000;

    /** Which scheduler (§3.8 two-scheduler split) drives a pipeline carrying this trigger. */
    public enum Scheduler { LOOP, EVENT, MANUAL }

    /** The driving scheduler for this trigger. */
    public Scheduler scheduler() {
        return switch (kind) {
            case EVENT -> Scheduler.EVENT;
            case MANUAL -> Scheduler.MANUAL;
            case SCHEDULE_INTERVAL, SCHEDULE_CRON, DEFAULT_POLL, STREAM -> Scheduler.LOOP;
        };
    }

    /** Whether an event storm should be debounced into one admitted run. */
    public boolean coalesces() {
        return coalesceMs > 0;
    }

    /** The trigger of {@code g}'s first entry node, or {@link Kind#DEFAULT_POLL} if it has no entry node. */
    public static PipelineTrigger of(PipelineGraph g) {
        var entries = g.entryNodes();
        return entries.isEmpty() ? defaultPoll() : of(entries.get(0));
    }

    /** Parse the {@code trigger:} config of an entry node. */
    public static PipelineTrigger of(PipelineNode entry) {
        Object raw = entry.cfg("trigger");
        if (!(raw instanceof Map<?, ?> m)) return defaultPoll();
        try {
            @SuppressWarnings("unchecked")
            PipelineTrigger t = of((Map<String, Object>) m);
            return t;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(e.getMessage() + " (entry node '" + entry.id() + "')", e);
        }
    }

    /**
     * Parse a raw {@code trigger:} block directly (the live {@code CollectorService} path holds the config
     * map, not a {@link PipelineNode}). {@code null}/empty ⇒ {@link Kind#DEFAULT_POLL} so an un-triggered
     * pipeline behaves exactly as today's poll loop.
     */
    public static PipelineTrigger of(Map<String, Object> m) {
        if (m == null || m.isEmpty()) return defaultPoll();

        String type = Values.str(m.get("type"));
        long coalesce = millis(m.get("coalesce"));

        if (type == null || type.isBlank() || "schedule".equalsIgnoreCase(type)) {
            String cron = Values.str(m.get("cron"));
            if (cron != null && !cron.isBlank())
                return new PipelineTrigger(Kind.SCHEDULE_CRON, 0, cron, null, null, coalesce, 0);
            Object every = m.get("every");
            if (every != null)
                return new PipelineTrigger(Kind.SCHEDULE_INTERVAL, millis(every), null, null, null, coalesce, 0);
            return new PipelineTrigger(Kind.DEFAULT_POLL, 0, null, null, null, coalesce, 0);
        }
        if ("event".equalsIgnoreCase(type))
            return new PipelineTrigger(Kind.EVENT, 0, null, Values.str(m.get("on")), Values.str(m.get("from")), coalesce, 0);
        if ("manual".equalsIgnoreCase(type))
            return new PipelineTrigger(Kind.MANUAL, 0, null, null, null, coalesce, 0);
        if ("stream".equalsIgnoreCase(type)) {
            // everyMs carries the lane's max wait (T); streamRecords its backlog threshold (N).
            long wait = m.get("max_wait") == null ? DEFAULT_STREAM_MAX_WAIT_MS : millis(m.get("max_wait"));
            long records = m.get("records") == null ? DEFAULT_STREAM_RECORDS
                    : Long.parseLong(m.get("records").toString().trim());
            if (wait <= 0 || records <= 0)
                throw new IllegalArgumentException("trigger type 'stream' needs a positive records and max_wait");
            return new PipelineTrigger(Kind.STREAM, wait, null, null, null, 0, records);
        }
        throw new IllegalArgumentException("unknown trigger type '" + type + "'");
    }

    private static PipelineTrigger defaultPoll() {
        return new PipelineTrigger(Kind.DEFAULT_POLL, 0, null, null, null, 0, 0);
    }

    /** Parse a duration ({@code 60s}/{@code 5m}/{@code 2h}/{@code 1d}; a bare number is seconds) to millis; null ⇒ 0. */
    static long millis(Object v) {
        if (v == null) return 0;
        String s = v.toString().trim();
        if (s.isEmpty()) return 0;
        char last = s.charAt(s.length() - 1);
        if (Character.isDigit(last)) return Long.parseLong(s) * 1000L;       // bare number = seconds
        long n = Long.parseLong(s.substring(0, s.length() - 1).trim());
        return switch (Character.toLowerCase(last)) {
            case 's' -> n * 1000L;
            case 'm' -> n * 60_000L;
            case 'h' -> n * 3_600_000L;
            case 'd' -> n * 86_400_000L;
            default -> throw new IllegalArgumentException("bad duration '" + s + "'");
        };
    }
}
