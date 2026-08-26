package com.gamma.control;

import com.gamma.acquire.IntakeGovernor;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.inspector.ConcurrencyBroker;
import com.sun.net.httpserver.HttpExchange;
import com.gamma.service.SpaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Consignment-concurrency settings — the hot-tunable tiers of the scheduler-system-config plan
 * (Part B), served live and persisted as {@code scheduler.toon}:
 * <pre>
 *   GET /system/scheduler     server-wide cap (value + provenance), the bound space's cap,
 *                             host cores, and the broker's live occupancy snapshot
 *   PUT /system/scheduler     replace the server-wide cap (write-root gated, canOperateRuns)
 *   GET /settings/scheduler   the bound space's cap + cadences (stored values, provenance, and the
 *                             effective cadences on the running timers)
 *   PUT /settings/scheduler   replace the bound space's cap and optionally its poll/acquire cadence
 *                             (absent cadence = keep inheriting the -D bootstrap default)
 * </pre>
 *
 * <p><b>Hot-apply.</b> A PUT persists the document and immediately installs the cap on
 * {@link ConcurrencyBroker#shared()} — no restart. A shrink <b>drains</b>: in-flight Consignments
 * finish and the new ceiling gates the next admissions. {@link #register} also installs both tiers
 * at boot, so a configured file takes effect without any request.
 *
 * <p><b>Precedence (the plan's §3 rule).</b> The file is the source of truth when present;
 * {@code -Dscheduler.max.consignments} is a bootstrap default consulted only when the server-wide
 * file is absent; absent both, the tier is unbounded (0) — today's behaviour. The GET reports each
 * value's provenance ({@code file} | {@code property} | {@code default}) so two declarations can
 * never leave the operator guessing which won. ⛔ No key served here may also be read from {@code -D}
 * at use time — split ownership of one fact is what the 2026-08-15 operational-db decision forbids.
 *
 * <p><b>Gates.</b> PUTs are {@code canOperateRuns} — tuning a live scheduler is runtime operation,
 * not workbench authoring — plus the standard write-root 503 and a 422 bound on the value. No
 * caller-supplied paths (fixed filename at resolved homes), so there is no path jail to apply.
 * The server-wide document home is {@code -Dsystem.config.dir}, else the spaces container root
 * (hosted mode), else the sole space's write root — in single-tenant mode the space <i>is</i> the
 * process. Reads carry no write gate.
 */
final class SchedulerRoutes implements RouteModule {

    private static final Logger log = LoggerFactory.getLogger(SchedulerRoutes.class);
    /** Sanity ceiling for a cap value — far above any real host, small enough to catch unit mistakes
     *  (someone writing bytes or millis into a slot count). */
    private static final int MAX_CAP = 100_000;
    private static final String PROP = "scheduler.max.consignments";
    /** Hard cap on the throttled-pipeline diagnostic list; the true total is reported alongside. */
    private static final int MAX_THROTTLED_ROWS = 50;

    @Override
    public void register(ApiContext api) {
        api.get("/system/scheduler", (e, m) -> ETags.respond(e, systemShape(api)));
        api.put("/system/scheduler", ApiContext.withCapability("canOperateRuns",
                (e, m) -> writeSystem(api, e, api.body(e))));
        api.get("/settings/scheduler", (e, m) -> ETags.respond(e, spaceShape(api)));
        api.put("/settings/scheduler", ApiContext.withCapability("canOperateRuns",
                (e, m) -> writeSpace(api, e, api.body(e))));
        installAtBoot(api);
    }

    // ── boot install: a configured file must take effect without any request ──────

    private void installAtBoot(ApiContext api) {
        try {
            ConcurrencyBroker broker = ConcurrencyBroker.shared();
            broker.setSystemCap(effectiveSystemCap(api));
            SchedulerSettings sys = SchedulerSettings.read(systemDocPath(api));
            IntakeGovernor.shared().setGlobalPolicy(effectiveIntake(sys));
            installResourceCaps(api, sys);
            if (api.spaces() != null) {
                for (SpaceContext s : api.spaces().all()) {
                    Path cfg = s.root().config();
                    if (cfg == null) continue;
                    SchedulerSettings ss = SchedulerSettings.read(cfg.resolve(SchedulerSettings.FILE));
                    if (ss.maxConcurrentConsignments() > 0)
                        broker.setSpaceCap(s.id().value(), ss.maxConcurrentConsignments());
                    if (ss.pollSeconds() != null) s.service().reschedulePoll(ss.pollSeconds());
                    if (ss.acquirePollSeconds() != null) s.service().rescheduleAcquire(ss.acquirePollSeconds());
                }
            }
        } catch (RuntimeException e) {
            log.warn("Scheduler settings boot install skipped: {}", e.getMessage());
        }
    }

    // ── server-wide tier ──────────────────────────────────────────────────────────

    private Object systemShape(ApiContext api) {
        Path doc = systemDocPath(api);
        SchedulerSettings ss = SchedulerSettings.read(doc);
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> system = new LinkedHashMap<>();
        if (SchedulerSettings.present(doc)) {
            system.put("maxConcurrentConsignments", ss.maxConcurrentConsignments());
            system.put("source", "file");
        } else if (Integer.getInteger(PROP) != null) {
            system.put("maxConcurrentConsignments", Math.max(0, Integer.getInteger(PROP)));
            system.put("source", "property");
        } else {
            system.put("maxConcurrentConsignments", 0);
            system.put("source", "default");
        }
        // The IntakeGovernor fleet globals: stored values (null = inherit the -Dingest.* bootstrap
        // default) plus the thresholds actually in force on the running governor.
        system.put("intakeMaxFilesPerCycle", ss.intakeMaxFilesPerCycle());
        system.put("intakeMinFilesPerCycle", ss.intakeMinFilesPerCycle());
        system.put("intakeAdaptive", ss.intakeAdaptive());
        boolean anyStored = ss.intakeMaxFilesPerCycle() != null || ss.intakeMinFilesPerCycle() != null
                || ss.intakeAdaptive() != null;
        system.put("intakeSource", anyStored ? "file"
                : (System.getProperty("ingest.maxFilesPerCycle") != null ? "property" : "default"));
        // BACKLOG D11's resource pair, with the same provenance contract as the cap above: a stored file
        // value wins, else the -D bootstrap default, else the built-in default that now ships on.
        String storedMem = ss.duckdbMemoryLimit();
        String propMem = System.getProperty(com.gamma.util.DuckDbUtil.PROP_MEMORY_LIMIT);
        system.put("duckdbMemoryLimit", storedMem != null ? storedMem
                : (propMem != null && !propMem.isBlank() ? propMem : null));
        system.put("duckdbMemoryLimitSource", storedMem != null ? "file"
                : (propMem != null && !propMem.isBlank() ? "property" : "default"));
        Integer storedRuns = ss.maxConcurrentJobRuns();
        Integer propRuns = Integer.getInteger("jobs.maxConcurrentRuns");
        system.put("maxConcurrentJobRuns", storedRuns != null ? storedRuns
                : (propRuns != null ? propRuns : com.gamma.job.JobService.DEFAULT_MAX_CONCURRENT_RUNS));
        system.put("maxConcurrentJobRunsSource", storedRuns != null ? "file"
                : (propRuns != null ? "property" : "default"));
        IntakeGovernor.Policy live = IntakeGovernor.shared().policy();
        Map<String, Object> intake = new LinkedHashMap<>();
        intake.put("maxFilesPerCycle", live.baseCap());
        intake.put("minFilesPerCycle", live.minCap());
        intake.put("adaptive", live.adaptive());
        intake.put("active", live.active());
        system.put("effectiveIntake", intake);
        m.put("system", system);
        m.put("space", spaceShape(api));
        m.put("cores", Runtime.getRuntime().availableProcessors());
        Map<String, Object> occupancy = ConcurrencyBroker.shared().snapshot();
        occupancy.put("throttled", throttledPipelines(api));
        m.put("live", occupancy);
        return m;
    }

    /**
     * S8 — which pipelines the {@link IntakeGovernor} has throttled, and to what cap. A throttled
     * pipeline is otherwise invisible outside the logs: it keeps running, just admitting fewer files
     * per cycle, so an operator watching throughput drop has nothing to look at. Only pipelines
     * actually BELOW their base cap are listed, so an untouched fleet reports an empty list rather
     * than a wall of "normal".
     *
     * <p>Bounded by construction (one row per registered pipeline, capped) with the true total
     * reported — a diagnostic read must not become an unbounded export.
     */
    private static Map<String, Object> throttledPipelines(ApiContext api) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        int total = 0;
        try {
            IntakeGovernor gov = IntakeGovernor.shared();
            for (var p : api.service().pipelines()) {
                IntakeGovernor.Policy policy = gov.policyFor(p.name());
                if (!policy.active()) continue;                       // admission control off for it
                int cap = gov.capFor(p.name());
                if (cap >= policy.baseCap()) continue;                // at its ceiling — not throttled
                total++;
                if (rows.size() >= MAX_THROTTLED_ROWS) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("pipeline", p.name());
                row.put("cap", cap);
                row.put("baseCap", policy.baseCap());
                row.put("floor", policy.effectiveMinCap());
                rows.add(row);
            }
        } catch (RuntimeException noService) {
            // no space bound (fresh hosted deployment) — nothing to report, never a 500
        }
        out.put("pipelines", rows);
        out.put("total", total);
        out.put("truncated", total > rows.size());
        return out;
    }

    private Object writeSystem(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        requireBoundWriteRoot(api);
        int cap = requireCap(body);
        Path doc = systemDocPath(api);
        if (doc == null)
            throw new ApiException(503, "No home for the server-wide scheduler document "
                    + "(-Dsystem.config.dir, spaces root and write root all unset)");
        // Merge per key with the stored document (the space-tier rule): absent = preserve stored,
        // explicit null = clear (revert to the -Dingest.* bootstrap default, live), stated = gated.
        SchedulerSettings stored = SchedulerSettings.read(doc);
        Integer inMax = body.containsKey("intakeMaxFilesPerCycle")
                ? optIntField(body, "intakeMaxFilesPerCycle", 0) : stored.intakeMaxFilesPerCycle();
        Integer inMin = body.containsKey("intakeMinFilesPerCycle")
                ? optIntField(body, "intakeMinFilesPerCycle", 1) : stored.intakeMinFilesPerCycle();
        Boolean inAdaptive = body.containsKey("intakeAdaptive")
                ? optBoolField(body, "intakeAdaptive") : stored.intakeAdaptive();
        String mem = body.containsKey("duckdbMemoryLimit")
                ? requireMemoryLimit(body) : stored.duckdbMemoryLimit();
        Integer jobRuns = body.containsKey("maxConcurrentJobRuns")
                ? optIntField(body, "maxConcurrentJobRuns", 0) : stored.maxConcurrentJobRuns();
        SchedulerSettings next = new SchedulerSettings(cap, null, null, inMax, inMin, inAdaptive, mem, jobRuns);
        next.write(doc);
        ConcurrencyBroker.shared().setSystemCap(cap);
        IntakeGovernor.shared().setGlobalPolicy(effectiveIntake(next));
        installResourceCaps(api, next);
        journal(api, ex, "server-wide", null, stored, next);
        log.info("Server-wide scheduler settings applied: cap={} intake={}/{}/{} memory_limit={} jobRuns={}",
                cap, inMax, inMin, inAdaptive, mem, jobRuns);
        return systemShape(api);
    }

    /**
     * Install BACKLOG D11's resource pair from a stored document: the DuckDB {@code memory_limit} every
     * config-less scratch connection resolves through ({@link com.gamma.util.DuckDbUtil#memoryLimit}), and
     * the Job-Run concurrency bound. A stated value wins; {@code null} leaves the {@code -D} bootstrap
     * default in force.
     *
     * <p>⚠ The Run bound is <b>per space's Job engine</b>, because {@code JobService} is per space. In
     * single-tenant mode (one space per process — the default) that IS the process-wide bound D11's
     * arithmetic assumes; in hosted multi-space mode the worst case is the bound times the space count.
     * A cross-space shared pool would need its own broker tier and is deliberately not built here.
     */
    private static void installResourceCaps(ApiContext api, SchedulerSettings ss) {
        com.gamma.util.DuckDbUtil.installMemoryLimit(ss.duckdbMemoryLimit());
        // Both halves treat null the same way: CLEAR, revert to the -D bootstrap default. (The first
        // cut returned early on null, so clearing the stored bound left the old value live while the
        // GET reported the default — the settings page stated a fact that was false.) The static
        // install also covers spaces created AFTER this call: a new JobService constructs its bound
        // from effectiveMaxConcurrentRuns(), so it cannot silently revert to the property.
        com.gamma.job.JobService.installMaxConcurrentRuns(ss.maxConcurrentJobRuns());
        if (api.spaces() == null) return;
        int effective = com.gamma.job.JobService.effectiveMaxConcurrentRuns();
        for (SpaceContext s : api.spaces().all())
            s.service().jobService().ifPresent(j -> j.setMaxConcurrentRuns(effective));
    }

    /** A DuckDB size string ({@code 2GB}, {@code 512MB}, {@code 1.5GiB}) — or a 422. Empty/null clears the
     *  stored value, reverting to the {@code -D} bootstrap default. */
    private static String requireMemoryLimit(Map<String, Object> body) {
        Object raw = body.get("duckdbMemoryLimit");
        if (raw == null || raw.toString().isBlank()) return null;
        String v = raw.toString().trim();
        if (!v.matches("(?i)\\d+(\\.\\d+)?\\s*(B|K|KB|KIB|M|MB|MIB|G|GB|GIB|T|TB|TIB)"))
            throw new ApiException(422, "duckdbMemoryLimit must be a DuckDB size string, e.g. 2GB");
        return v;
    }

    /** The IntakeGovernor thresholds a stored document implies: each stated field wins, each unset
     *  field inherits its {@code -Dingest.*} bootstrap default — the same resolution rule as a
     *  pipeline's own {@code processing.intake} override. */
    private static IntakeGovernor.Policy effectiveIntake(SchedulerSettings ss) {
        IntakeGovernor.Policy props = IntakeGovernor.Policy.fromSystemProperties();
        return new IntakeGovernor.Policy(
                ss.intakeMaxFilesPerCycle() != null ? ss.intakeMaxFilesPerCycle() : props.baseCap(),
                ss.intakeMinFilesPerCycle() != null ? ss.intakeMinFilesPerCycle() : props.minCap(),
                ss.intakeAdaptive() != null ? ss.intakeAdaptive() : props.adaptive());
    }

    // ── journalling (BACKLOG §4 (a), operator decision 2026-08-26) ────────────────

    /**
     * Journal a scheduler change into the event store — <b>only when a value actually changed</b>, so
     * a re-save of identical settings does not pollute the one log an investigator trusts.
     *
     * <p>The generic {@link AuditTrail} already records who/when/from-where/status for the request.
     * What a path-classified audit row cannot carry is <em>what the numbers became</em>, which is
     * precisely the question an incident review asks about a live concurrency change ("at 14:32
     * someone dropped the server cap from 16 to 2"). So this is a domain event carrying the deltas —
     * the {@code PIPELINE_RENAMED} precedent — not a second copy of the audit row.
     *
     * <p>Best-effort by construction: journalling must never fail the write that succeeded.
     */
    private static void journal(ApiContext api, HttpExchange ex, String tier, String scope,
                                SchedulerSettings before, SchedulerSettings after) {
        try {
            Map<String, String> changes = diff(before, after);
            if (changes.isEmpty()) return;
            String actor = ApiContext.actor(ex);
            var b = Event.builder(EventType.SCHEDULER_SETTINGS_CHANGED)
                    .source(SchedulerRoutes.class.getName())
                    .message(actor + " changed " + tier + " scheduler settings"
                            + (scope == null ? "" : " for space '" + scope + "'") + ": "
                            + String.join(", ", changes.entrySet().stream()
                                    .map(e -> e.getKey() + " " + e.getValue()).toList()))
                    .actor(actor).actorType(ApiContext.actorType(ex))
                    .attr("tier", tier);
            if (scope != null) b.attr("scope", scope);
            changes.forEach(b::attr);
            // The BOUND SPACE's log, not EventLog.current(): a hosted space has its own EventLog
            // instance, while current() routes by the thread's space MDC and falls back to global —
            // so a bare /system/scheduler call would file the entry in a log the operator's /events
            // view never reads. Same seam PipelineRoutes uses for PIPELINE_RENAMED.
            api.service().eventLog().emit(b);
        } catch (RuntimeException ignore) {
            // best effort — a settings change that succeeded must not fail on its own journal entry
        }
    }

    /** Changed keys only, each rendered {@code "<old> -> <new>"}. An unset value reads as
     *  {@code inherit} rather than {@code null} — that is what it means, and the trail is read by
     *  people. */
    private static Map<String, String> diff(SchedulerSettings before, SchedulerSettings after) {
        Map<String, String> out = new LinkedHashMap<>();
        record Field(String name, Object before, Object after) {}
        for (Field f : List.of(
                new Field("max_concurrent_consignments", before.maxConcurrentConsignments(), after.maxConcurrentConsignments()),
                new Field("poll_seconds", before.pollSeconds(), after.pollSeconds()),
                new Field("acquire_poll_seconds", before.acquirePollSeconds(), after.acquirePollSeconds()),
                new Field("intake_max_files_per_cycle", before.intakeMaxFilesPerCycle(), after.intakeMaxFilesPerCycle()),
                new Field("intake_min_files_per_cycle", before.intakeMinFilesPerCycle(), after.intakeMinFilesPerCycle()),
                new Field("intake_adaptive", before.intakeAdaptive(), after.intakeAdaptive()))) {
            if (java.util.Objects.equals(f.before(), f.after())) continue;
            out.put(f.name(), render(f.before()) + " -> " + render(f.after()));
        }
        return out;
    }

    private static String render(Object v) {
        return v == null ? "inherit" : String.valueOf(v);
    }

    /** A stated optional int field: {@code null} = clear; otherwise an int with the given floor
     *  (and the {@link #MAX_CAP}-scaled sanity ceiling) or the write is refused. */
    private static Integer optIntField(Map<String, Object> body, String key, int floor) {
        Object raw = body.get(key);
        if (raw == null) return null;
        int v;
        try {
            v = Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new ApiException(422, key + " must be an integer, got '" + raw + "'");
        }
        if (v < floor || v > 10_000_000)
            throw new ApiException(422, key + " must be " + floor + "..10000000, got " + v);
        return v;
    }

    /** A stated optional boolean field: {@code null} = clear; otherwise strictly true/false. */
    private static Boolean optBoolField(Map<String, Object> body, String key) {
        Object raw = body.get(key);
        if (raw == null) return null;
        if (raw instanceof Boolean b) return b;
        String s = String.valueOf(raw).trim();
        if ("true".equalsIgnoreCase(s)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(s)) return Boolean.FALSE;
        throw new ApiException(422, key + " must be true or false, got '" + raw + "'");
    }

    /** The server-wide document home: {@code -Dsystem.config.dir} → spaces container root → sole
     *  space's write root; {@code null} when none is available. */
    private static Path systemDocPath(ApiContext api) {
        String dir = System.getProperty("system.config.dir");
        if (dir != null && !dir.isBlank()) return Path.of(dir).resolve(SchedulerSettings.FILE);
        Path container = api.spaces() != null ? api.spaces().containerRoot() : null;
        if (container != null) return container.resolve(SchedulerSettings.FILE);
        Path wr = api.writeRoot();
        return wr == null ? null : wr.resolve(SchedulerSettings.FILE);
    }

    /** The effective server-wide cap under the §3 precedence: file → property → 0 (unbounded). */
    private static int effectiveSystemCap(ApiContext api) {
        Path doc = systemDocPath(api);
        if (SchedulerSettings.present(doc)) return SchedulerSettings.read(doc).maxConcurrentConsignments();
        Integer prop = Integer.getInteger(PROP);
        return prop == null ? 0 : Math.max(0, prop);
    }

    // ── per-space tier (the bound space, via the standard /spaces/{id}/… seam) ────

    private Object spaceShape(ApiContext api) {
        String spaceId = EventLog.currentSpaceId();
        Path root = boundWriteRoot(api);
        Path doc = root == null ? null : root.resolve(SchedulerSettings.FILE);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", spaceId);
        // The cadences in force on this space's running timers (hot-applied; -D-seeded when unstated).
        try {
            m.put("effectivePollSeconds", api.service().pollSeconds());
            m.put("effectiveAcquirePollSeconds", api.service().acquirePollSeconds());
        } catch (RuntimeException noService) {
            // no space bound (fresh hosted deployment) — cadence has no meaning yet
        }
        if (SchedulerSettings.present(doc)) {
            SchedulerSettings ss = SchedulerSettings.read(doc);
            m.put("maxConcurrentConsignments", ss.maxConcurrentConsignments());
            m.put("pollSeconds", ss.pollSeconds());
            m.put("acquirePollSeconds", ss.acquirePollSeconds());
            m.put("source", "file");
        } else {
            m.put("maxConcurrentConsignments", 0);
            m.put("source", "default");
        }
        return m;
    }

    private Object writeSpace(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path root = requireBoundWriteRoot(api);
        Path doc = root.resolve(SchedulerSettings.FILE);
        int cap = requireCap(body);
        // Merge with the stored document — a cap-only PUT must not destroy a stored cadence.
        // Key ABSENT = preserve what is stored; key explicitly null = clear it (revert to the -D
        // bootstrap default, live); key stated = bounds-gated new value.
        SchedulerSettings stored = SchedulerSettings.read(doc);
        Integer poll = body.containsKey("pollSeconds")
                ? optCadence(body, "pollSeconds") : stored.pollSeconds();
        Integer acquire = body.containsKey("acquirePollSeconds")
                ? optCadence(body, "acquirePollSeconds") : stored.acquirePollSeconds();
        SchedulerSettings next = new SchedulerSettings(cap, poll, acquire);
        next.write(doc);
        ConcurrencyBroker.shared().setSpaceCap(EventLog.currentSpaceId(), cap);
        if (body.containsKey("pollSeconds"))
            api.service().reschedulePoll(poll != null ? poll : Long.getLong("service.poll.seconds", 60L));
        if (body.containsKey("acquirePollSeconds"))
            api.service().rescheduleAcquire(acquire != null ? acquire
                    : Long.getLong("acquire.pollSeconds", api.service().pollSeconds()));
        journal(api, ex, "space", EventLog.currentSpaceId(), stored, next);
        log.info("Space '{}' scheduler settings applied: cap={} poll={}s acquire={}s",
                EventLog.currentSpaceId(), cap, poll, acquire);
        return spaceShape(api);
    }

    /** A stated cadence field: {@code null} = clear (revert to the {@code -D} bootstrap default);
     *  otherwise an int in 1..86400 or the write is refused. */
    private static Integer optCadence(Map<String, Object> body, String key) {
        Object raw = body.get(key);
        if (raw == null) return null;
        int v;
        try {
            v = Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new ApiException(422, key + " must be an integer number of seconds, got '" + raw + "'");
        }
        if (v < 1 || v > 86_400)
            throw new ApiException(422, key + " must be 1..86400 seconds, got " + v);
        return v;
    }

    /** The bound space's write root, or {@code null} when writes are disabled OR no space is bound —
     *  a fresh hosted deployment with zero spaces must degrade to defaults, never 500
     *  ({@code SpaceManager.current()} throws {@code IllegalStateException} on an empty manager). */
    private static Path boundWriteRoot(ApiContext api) {
        try {
            return api.writeRoot();
        } catch (IllegalStateException noSpaces) {
            return null;
        }
    }

    /** Gate 1 for the PUTs: {@link WriteGates#requireWriteRoot} semantics, with "no space bound"
     *  reading as writes-disabled (503) rather than a 500. */
    private static Path requireBoundWriteRoot(ApiContext api) {
        Path root = boundWriteRoot(api);
        if (root == null)
            throw new ApiException(503, ErrorCodes.CONTROL_PLANE_READ_ONLY,
                    "scheduler settings write disabled: no writable space is bound");
        return root;
    }

    /** The one payload field, bounds-gated: an int in {@code 0..100000} (0 = unbounded). */
    private static int requireCap(Map<String, Object> body) {
        Object raw = body.get("maxConcurrentConsignments");
        if (raw == null) throw new ApiException(422, "maxConcurrentConsignments is required (0 = unbounded)");
        int cap;
        try {
            cap = Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new ApiException(422, "maxConcurrentConsignments must be an integer, got '" + raw + "'");
        }
        if (cap < 0 || cap > MAX_CAP)
            throw new ApiException(422, "maxConcurrentConsignments must be 0.." + MAX_CAP + ", got " + cap);
        return cap;
    }
}
