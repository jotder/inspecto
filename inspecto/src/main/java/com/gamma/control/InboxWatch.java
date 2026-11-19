package com.gamma.control;

import com.gamma.etl.PipelineConfig;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.PipelineTrigger;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>Watch this folder</b> ({@code BASIC-ONBOARD-1} P2; {@code BASIC-ELT-PROFILE-1} B1, operator ruling O1 2026-11-19, which
 * reverses DT-17): the locked preset of <i>Keep this up to date</i>. Turning it on rewrites the import's own Pipeline so
 * its {@code local} Collector is polled on the engine's schedule - {@code trigger: {type: schedule, every: 30s}} with the
 * stability gate a folder feed has ({@code stability: {window: 30s, size_checks: 2}}) - on Basic and up, off by default,
 * one interval that is not configurable. The engine's poll cycle runs it like any scheduled Pipeline: the import's
 * pre-run steps judge the waiting files and keep new columns ({@link ImportPreRunSteps}), the run reads the files the
 * stability gate releases, the commit steps compact, follow on and check the Expectations ({@link ImportCommitSteps}).
 * <ul>
 *   <li>{@code PUT /datasets/{id}/watch} {@code {watch: true|false}} - the toggle. On: the preset is written through the
 *       Pipeline write path ({@link DatasetFeed#rewritePipeline}: an approval policy over Pipelines 409, the save gate,
 *       the history) and {@code watchInbox: true} kept on the Dataset entry; off: back to {@code trigger: manual}, the
 *       preset's stability block removed, the entry key removed. Gates as Add a file ({@link DatasetFeed#gate}); a
 *       Reference 422 (a watched inbox adds rows); a Postgres snapshot 422; a Dataset whose Pipeline already runs on
 *       another schedule ({@code Keep this up to date}) 409. Answers the feed read.</li>
 *   <li><b>The feed keeps its shape</b> until the UI's U4-Watch slice: a watched import answers {@code watch: true},
 *       {@code watchable: true} and {@code feed.kind: uploads} ({@link #feedOf}); {@code watchError} after a scheduled run
 *       of it failed ({@link #ran}).</li>
 *   <li><b>No half-written file</b>: the engine's per-file stability gate (O1: per file, no longer the whole inbox at once),
 *       and the pre-run steps judge and take only the files unmodified for the window.</li>
 * </ul>
 * The poller this class used to run (one daemon thread, its own whole-inbox settle rule) is retired.
 */
final class InboxWatch {

    private InboxWatch() {}

    /** The poll interval: fixed, not configurable on Basic (§5 Q1). */
    static final String EVERY = "30s";
    static final long EVERY_MS = 30_000L;
    /** A waiting file is taken once unmodified this long and seen at the same size on {@link #SIZE_CHECKS} polls. */
    static final String SETTLE = "30s";
    static final int SIZE_CHECKS = 2;
    /** The Dataset entry key the toggle writes. */
    static final String WATCH_KEY = "watchInbox";
    /** What Data status says for a failure that is not a refusal: exception text never reaches the wire (ERR-5XX-ROUTE-BODIES). */
    static final String SERVER_FAULT = "the import failed on the server; the server log has the detail";

    /** The last failure of a watched inbox's scheduled run, by {@link #errorKey}; cleared by the next run that succeeds. */
    private static final Map<String, String> LAST_ERROR = new ConcurrentHashMap<>();

    /** Whether {@code cfg} is the preset: a {@code local} Collector on a 30 s interval schedule. */
    static boolean preset(PipelineConfig cfg) {
        PipelineTrigger t = PipelineTrigger.of(cfg.triggerConfig());
        return t.scheduler() == PipelineTrigger.Scheduler.LOOP && t.kind() == PipelineTrigger.Kind.SCHEDULE_INTERVAL
                && t.everyMs() == EVERY_MS && "local".equals(cfg.collector().connector());
    }

    /**
     * Whether the inbox is watched now: the toggle is on, the Pipeline is the preset, and not a Reference (a watched inbox
     * only adds rows) nor a Postgres snapshot (manual only since 2026-11-05: Run now exports the table and moves it in).
     */
    static boolean watched(Map<String, Object> content, PipelineConfig cfg) {
        return Boolean.TRUE.equals(content.get(WATCH_KEY)) && !snapshot(content) && !"reference".equals(content.get("kind"))
                && preset(cfg);
    }

    /** The feed as the answer shows it: a watched inbox says {@code uploads} (with {@code watch: true}), the rest as the Pipeline says. */
    static Map<String, Object> feedOf(PipelineConfig cfg, Map<String, Object> content) {
        if (!watched(content, cfg)) return DatasetFeed.feedOf(cfg);
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("kind", "uploads");
        return f;
    }

    /** A Postgres table snapshot (P3), as the Dataset entry says ({@code importSource: postgres}). */
    static boolean snapshot(Map<String, Object> content) {
        return "postgres".equals(content.get("importSource"));
    }

    private static String errorKey(Path dataRoot, String id) {
        return dataRoot.toAbsolutePath().normalize() + "|" + id;
    }

    static String lastError(Path dataRoot, String id) {
        return LAST_ERROR.get(errorKey(dataRoot, id));
    }

    /** A scheduled run of a watched inbox ended: {@code error} null clears what Data status says, else it is kept. */
    static void ran(Path dataRoot, String id, String error) {
        if (error == null) LAST_ERROR.remove(errorKey(dataRoot, id));
        else LAST_ERROR.put(errorKey(dataRoot, id), error);
    }

    // ── PUT /datasets/{id}/watch ────────────────────────────────────────────────────────────────────────────

    static void registerRoutes(ApiContext api) {
        // ⚠ String LITERAL on purpose (CapabilityManifestTest's scanner).
        api.put("/datasets/([^/]+)/watch", ApiContext.withCapability("canUploadData",
                (e, m) -> toggle(api, e, ApiContext.name(m), api.body(e))));
    }

    static Object toggle(ApiContext api, HttpExchange ex, String id, Map<String, Object> body) throws IOException {
        DatasetFeed.Import imp = DatasetFeed.gate(api, ex, id, "Watch this folder");
        if (body == null || !(body.get("watch") instanceof Boolean on))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "watch must be true or false");
        if (on && (snapshot(imp.content()) || PostgresSnapshot.isSnapshot(imp.writeRoot(), id)))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED,
                    "a Postgres snapshot is not polled; Refresh now copies the table again");
        if (on && "reference".equals(imp.content().get("kind")))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED,
                    "a Reference is replaced with Add a file; a watched inbox only adds rows, so it keeps Events up to date");
        PipelineConfig cfg = imp.service().configFor(id)
                .orElseThrow(() -> new ApiException(409, ErrorCodes.CONFLICT, "the import's Pipeline '" + id + "' is not registered"));
        boolean preset = preset(cfg);
        if (on && !preset && PipelineTrigger.of(cfg.triggerConfig()).scheduler() != PipelineTrigger.Scheduler.MANUAL)
            throw new ApiException(409, ErrorCodes.CONFLICT, "'" + id + "' is already kept up to date on a schedule; "
                    + "stop that first to watch the folder instead");
        PendingChanges.holdRefusing(api, List.of("dataset"), "watching the inbox is a Dataset change");
        DatasetFeed.AfterWrite entry = () -> keep(api, imp, id, on);
        if (on && !preset)
            DatasetFeed.rewritePipeline(api, imp, "Watch this folder rewrites the import's Pipeline", InboxWatch::watch, entry);
        else if (!on && preset)
            DatasetFeed.rewritePipeline(api, imp, "Watch this folder rewrites the import's Pipeline", InboxWatch::unwatch, entry);
        else
            entry.run();
        if (!on) LAST_ERROR.remove(errorKey(imp.dataRoot(), id));
        return DatasetFeed.read(api, ex, id);
    }

    /** The preset onto the Pipeline {@code p}: the {@code local} Collector, its stability gate, the 30 s schedule. */
    @SuppressWarnings("unchecked")
    private static void watch(Map<String, Object> p) {
        Map<String, Object> collector = p.get("collector") instanceof Map<?, ?> c
                ? new LinkedHashMap<>((Map<String, Object>) c) : new LinkedHashMap<>();
        collector.put("discovery", "poll");
        collector.put("connector", "local");
        collector.remove("connection");
        collector.put("accepts_upload", true);
        Map<String, Object> stability = new LinkedHashMap<>();   // a half-written file is never read
        stability.put("window", SETTLE);
        stability.put("size_checks", SIZE_CHECKS);
        collector.put("stability", stability);
        p.put("collector", collector);
        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("type", "schedule");
        trigger.put("every", EVERY);
        p.put("trigger", trigger);
    }

    /** Back to uploads: the preset's stability block goes, the trigger is manual; anything else in the Collector stays. */
    @SuppressWarnings("unchecked")
    private static void unwatch(Map<String, Object> p) {
        Map<String, Object> collector = p.get("collector") instanceof Map<?, ?> c
                ? new LinkedHashMap<>((Map<String, Object>) c) : new LinkedHashMap<>();
        collector.remove("stability");
        p.put("collector", collector);
        p.put("trigger", Map.of("type", "manual"));
    }

    /** {@code watchInbox} on the Dataset entry as the toggle says; nothing written when it already says so. */
    private static void keep(ApiContext api, DatasetFeed.Import imp, String id, boolean on) throws IOException {
        if (on == Boolean.TRUE.equals(imp.content().get(WATCH_KEY))) return;
        Map<String, Object> content = new LinkedHashMap<>(imp.content());
        content.remove("name");
        if (on) content.put(WATCH_KEY, true);
        else content.remove(WATCH_KEY);
        try {
            ComponentRoutes.validateKind(api, "dataset", id, content);
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, bad.getMessage());
        }
        new ComponentStore(imp.writeRoot().resolve("registry")).write("dataset", id, content);
    }
}
