package com.gamma.control;

import com.gamma.etl.MarkerManager;
import com.gamma.etl.PipelineConfig;
import com.gamma.event.EventLog;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.PipelineTrigger;
import com.gamma.service.CollectorService;
import com.gamma.service.SpaceContext;
import com.gamma.service.SpaceId;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * <b>Watch this folder</b> ({@code BASIC-ONBOARD-1} P2, plan {@code basic-onboard-plan.md} §5 Q1): an import Dataset's
 * inbox looked at every 30 seconds, on Basic and up, off by default, one interval that is not configurable.
 * <ul>
 *   <li>{@code PUT /datasets/{id}/watch} {@code {watch: true|false}} - the toggle, kept as {@code watchInbox: true} on the
 *       Dataset entry. Gates as Add a file ({@link DatasetFeed#gate}); a Reference 422 (a watched inbox adds rows); a
 *       Dataset whose Pipeline already runs on a schedule ({@code Keep this up to date}) 409. Answers the feed read.</li>
 *   <li><b>One poller for every inbox</b> in the JVM: a single daemon thread, fixed delay, each {@link PollTarget} polled in
 *       turn under its Space. A watched inbox imports through {@link OnboardImport#importWaiting} - exactly <i>Import
 *       waiting files</i> / Run now: columns checked by name, a refused file set aside with its reason, the run, the
 *       follow-on, the arrivals.</li>
 *   <li><b>No half-written file:</b> a run starts only when every file waiting has <i>settled</i> - unmodified for 30 s
 *       and seen at the same size on two polls, the rule a folder feed writes into its Pipeline
 *       ({@code stability: {window: 30s, size_checks: 2}}, {@link DatasetFeed}). Not the {@code StabilityGate} itself: it
 *       forgets a file once released, and the poll must see the WHOLE inbox settled at once, because the run takes every
 *       file waiting. A file that appears in the instant between this check and the run's own listing is not covered.</li>
 * </ul>
 * The watched list lives in memory: read once from every Space's Dataset entries on the first tick, then kept by the
 * toggle, so an idle tick reads no registry (P0 V3). A poll target that answers false (the Dataset gone, the toggle off, no
 * longer an Add data import, the Space gone) is dropped.
 */
final class InboxWatch {

    private static final Logger log = LoggerFactory.getLogger(InboxWatch.class);

    private InboxWatch() {}

    /** The poll interval: fixed, not configurable on Basic (§5 Q1). */
    static final long EVERY_MS = 30_000L;
    /** A waiting file is settled once unmodified this long ... */
    static final long SETTLE_MS = 30_000L;
    /** ... and seen at the same size on this many polls. */
    static final int SIZE_CHECKS = 2;
    /** The Dataset entry key the toggle writes. */
    static final String WATCH_KEY = "watchInbox";

    /**
     * Something the one poller looks at every {@link #EVERY_MS}: an import inbox (a Postgres snapshot is not
     * polled since 2026-11-05: Run now exports it). {@link #poll} runs on the poll thread only, one poll target at a time, with the Space's
     * MDC set to {@link #space}; it answers false to stop being polled. An exception is logged and the poll target kept.
     */
    interface PollTarget {
        /** Unique in the JVM; registering the same key again replaces the poll target. */
        String key();

        /** The Space id the poll runs under. */
        String space();

        /** The ControlApi that registered it: its poll targets go when it closes. */
        ApiContext owner();

        boolean poll() throws Exception;
    }

    private static final Map<String, PollTarget> TARGETS = new ConcurrentHashMap<>();
    /** Owners whose Spaces were not yet read for watched inboxes (read on the first tick after they register). */
    private static final Map<ApiContext, Boolean> UNREAD = new ConcurrentHashMap<>();
    /** The last failure of a watched inbox's import, by {@link #errorKey}; cleared by the next import that runs. */
    private static final Map<String, String> LAST_ERROR = new ConcurrentHashMap<>();
    private static ScheduledExecutorService poller;
    private static final Object START = new Object();   // not the class monitor: tick() holds that while it imports
    /** Tests drive {@link #tick} themselves; the daemon's own ticks are skipped while this is set. */
    static volatile boolean pausedForTest;
    /** Tests: runs between the poll's settle check and its import (a file created in that instant). */
    static volatile Runnable afterSettleCheckForTest;
    /** What Data status says for a failure that is not a refusal: exception text never reaches the wire (ERR-5XX-ROUTE-BODIES). */
    static final String SERVER_FAULT = "the import failed on the server; the server log has the detail";

    // ── the poller ──────────────────────────────────────────────────────────────────────────────────────────

    /** Called once per ControlApi (from {@link OnboardRoutes#register}): its watched inboxes are read on the first tick. */
    static void attach(ApiContext api) {
        UNREAD.put(api, Boolean.TRUE);
        api.onClose(() -> detach(api));
        ensureStarted();
    }

    static void detach(ApiContext api) {
        UNREAD.remove(api);
        TARGETS.values().removeIf(s -> s.owner() == api);
    }

    /** Add (or replace) a poll target. */
    static void register(PollTarget s) {
        TARGETS.put(s.key(), s);
        ensureStarted();
    }

    static void unregister(String key) {
        TARGETS.remove(key);
    }

    private static void ensureStarted() {
        synchronized (START) {
            if (poller != null) return;
            poller = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "inbox-watch");
                t.setDaemon(true);
                return t;
            });
            // fixed DELAY: a tick that overruns delays the next, never overlaps it
            poller.scheduleWithFixedDelay(() -> {
                if (!pausedForTest) tick();
            }, EVERY_MS, EVERY_MS, TimeUnit.MILLISECONDS);
        }
    }

    /** One tick: read any newly attached ControlApi's watched inboxes, then poll every poll target in turn. */
    static synchronized void tick() {
        for (ApiContext api : List.copyOf(UNREAD.keySet())) {
            if (UNREAD.remove(api) == null) continue;
            try {
                resume(api);
            } catch (RuntimeException e) {
                log.warn("Watched inboxes could not be read: {}", e.toString());
            }
        }
        for (PollTarget s : List.copyOf(TARGETS.values())) {
            if (TARGETS.get(s.key()) != s) continue;   // replaced or removed meanwhile
            String prev = MDC.get(EventLog.SPACE_MDC_KEY);
            MDC.put(EventLog.SPACE_MDC_KEY, s.space());
            try {
                if (!s.poll()) TARGETS.remove(s.key(), s);
            } catch (Exception e) {
                log.warn("Polling {} failed: {}", s.key(), e.toString());
            } finally {
                if (prev == null) MDC.remove(EventLog.SPACE_MDC_KEY);
                else MDC.put(EventLog.SPACE_MDC_KEY, prev);
            }
        }
    }

    /** Every Dataset entry with {@code watchInbox: true} in every Space {@code api} hosts becomes a poll target. */
    private static void resume(ApiContext api) {
        for (SpaceContext ctx : HostContext.of(api).spaces().all()) {
            String space = ctx.id().value();
            String prev = MDC.get(EventLog.SPACE_MDC_KEY);
            MDC.put(EventLog.SPACE_MDC_KEY, space);
            try {
                Path writeRoot = api.writeRoot();
                if (writeRoot == null) continue;
                for (ComponentRegistry.Component c : new ComponentStore(writeRoot.resolve("registry")).list("dataset"))
                    if (Boolean.TRUE.equals(c.content().get(WATCH_KEY))) register(target(api, space, writeRoot, c.name(), c.content()));
            } catch (RuntimeException e) {
                log.warn("Watched inboxes of Space {} could not be read: {}", space, e.toString());
            } finally {
                if (prev == null) MDC.remove(EventLog.SPACE_MDC_KEY);
                else MDC.put(EventLog.SPACE_MDC_KEY, prev);
            }
        }
    }

    // ── one watched inbox ───────────────────────────────────────────────────────────────────────────────────

    static String key(Path writeRoot, String id) {
        return "inbox|" + writeRoot.toAbsolutePath().normalize() + "|" + id;
    }

    /** The poll target for a watched entry: its inbox. A Postgres snapshot has none (it is refreshed with Run now only). */
    private static PollTarget target(ApiContext api, String space, Path writeRoot, String id, Map<String, Object> content) {
        return new Inbox(api, space, writeRoot, id);
    }

    /**
     * Whether the 30 s poll is on now: the toggle is on, no schedule runs the Pipeline, not a Reference (a watched inbox only
     * adds rows) and not a Postgres snapshot (manual only since 2026-11-05: Run now exports the table and moves it in).
     */
    static boolean watched(Map<String, Object> content, PipelineConfig cfg) {
        return Boolean.TRUE.equals(content.get(WATCH_KEY)) && !snapshot(content) && !"reference".equals(content.get("kind"))
                && PipelineTrigger.of(cfg.triggerConfig()).scheduler() == PipelineTrigger.Scheduler.MANUAL;
    }

    private static String errorKey(Path dataRoot, String id) {
        return dataRoot.toAbsolutePath().normalize() + "|" + id;
    }

    /** A Postgres table snapshot (P3), as the Dataset entry says ({@code importSource: postgres}). */
    static boolean snapshot(Map<String, Object> content) {
        return "postgres".equals(content.get("importSource"));
    }

    /**
     * Run one poll's import and keep what Data status says about it: cleared on success; a 409 because a person's Run now or
     * upload holds the same import is skipped (the next poll looks again); a refusal's own words without the server's
     * paths; a fixed phrase for anything else (exception text never reaches the wire, ERR-5XX-ROUTE-BODIES).
     */
    private static void record(Path writeRoot, Path dataRoot, String id, Object pipeline, Runnable work) {
        try {
            work.run();
            LAST_ERROR.remove(errorKey(dataRoot, id));
        } catch (ApiException e) {
            if (e.status == 409 && OnboardImport.IN_FLIGHT.contains(writeRoot + "|" + pipeline)) return;
            LAST_ERROR.put(errorKey(dataRoot, id), e.status >= 500 ? SERVER_FAULT
                    : ServerFaults.withoutServerPaths(e.getMessage(), dataRoot, writeRoot));
            log.warn("The 30 s poll of '{}' could not import: {}", id, e.getMessage());
        } catch (RuntimeException e) {
            LAST_ERROR.put(errorKey(dataRoot, id), SERVER_FAULT);
            log.warn("The 30 s poll of '{}' could not import", id, e);
        }
    }

    static String lastError(Path dataRoot, String id) {
        return LAST_ERROR.get(errorKey(dataRoot, id));
    }

    /** A sighting of one waiting file: its size and modified time, and on how many polls in a row it had that size. */
    private record Sighting(long size, long modified, int count) {}

    static final class Inbox implements PollTarget {
        private final ApiContext api;
        private final String space, id, key;
        private final Path writeRoot;
        private Map<Path, Sighting> seen = new HashMap<>();

        Inbox(ApiContext api, String space, Path writeRoot, String id) {
            this.api = api;
            this.space = space;
            this.writeRoot = writeRoot.toAbsolutePath().normalize();
            this.id = id;
            this.key = InboxWatch.key(writeRoot, id);
        }

        @Override public String key() { return key; }
        @Override public String space() { return space; }
        @Override public ApiContext owner() { return api; }

        @Override
        public boolean poll() throws IOException {
            if (HostContext.of(api).spaces().space(SpaceId.of(space)).isEmpty()) return false;   // the Space is gone
            if (!writeRoot.equals(api.writeRoot() == null ? null : api.writeRoot().toAbsolutePath().normalize())) return false;
            Path dataRoot = api.dataRoot();
            if (dataRoot == null || !DataSettings.forRoot(writeRoot).selfImportInForce()) return true;   // Add data off: wait
            dataRoot = dataRoot.toAbsolutePath().normalize();
            CollectorService service = HostContext.of(api).service();
            Map<String, Object> content = new ComponentStore(writeRoot.resolve("registry")).get("dataset", id)
                    .map(ComponentRegistry.Component::content).orElse(null);
            if (content == null || !Boolean.TRUE.equals(content.get(WATCH_KEY))) return false;
            if ("reference".equals(content.get("kind"))) return false;   // set by another door: a watched inbox only adds rows
            if (snapshot(content) || PostgresSnapshot.isSnapshot(writeRoot, id)) return false;   // a snapshot is never polled
            try {
                OnboardImport.requireOwnImport(writeRoot, id, content, service);
            } catch (ApiException notOurs) {
                return false;
            }
            PipelineConfig cfg = service.configFor(id).orElse(null);
            if (cfg == null) return false;
            if (!watched(content, cfg)) return true;   // a schedule runs it (Keep this up to date): not ours to poll
            Path inbox = OnboardImport.inbox(dataRoot, id).toAbsolutePath().normalize();
            List<Path> waiting = DatasetFeed.waiting(inbox, "." + OnboardImport.extension(String.valueOf(content.get("importFormat"))));
            if (!settled(waiting, cfg, System.currentTimeMillis())) return true;
            // BASIC-ONBOARD-SETTLE-1: the run takes only the files just checked; one created since waits for a later poll
            java.util.Set<Path> checked = new java.util.HashSet<>();
            for (Path p : seen.keySet()) checked.add(p.toAbsolutePath().normalize());
            Runnable hook = afterSettleCheckForTest;
            if (hook != null) hook.run();
            final Path data = dataRoot;
            // PRODUCT-ASSEMBLY-1 PA-7b (D-PA24): read-only imports nothing; the waiting files stay for the first writable poll.
            if (com.gamma.module.Entitlements.readOnly()) return true;
            record(writeRoot, dataRoot, id, content.get("importPipeline"), () ->
                    OnboardImport.importWaiting(new OnboardImport.Space(writeRoot, data, service, "inbox-watch"), id, content, checked));
            seen = new HashMap<>();
            return true;
        }

        /**
         * Whether a run should start: at least one file the run would take (not already imported under its name), and
         * every such file unmodified for {@link #SETTLE_MS} and seen at the same size on {@link #SIZE_CHECKS} polls.
         */
        boolean settled(List<Path> waiting, PipelineConfig cfg, long now) {
            Map<Path, Sighting> next = new HashMap<>();
            boolean any = false, all = true;
            for (Path p : waiting) {
                if (MarkerManager.isAlreadyProcessed(p.toFile(), cfg)) continue;   // the run skips it: never waits on it
                long size, modified;
                try {
                    size = Files.size(p);
                    modified = Files.getLastModifiedTime(p).toMillis();
                } catch (IOException gone) {
                    all = false;
                    continue;
                }
                Sighting prev = seen.get(p);
                int count = prev != null && prev.size() == size && prev.modified() == modified ? prev.count() + 1 : 1;
                next.put(p, new Sighting(size, modified, count));
                any = true;
                if (count < SIZE_CHECKS || now - modified < SETTLE_MS) all = false;
            }
            seen = next;
            return any && all;
        }
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
        if (on && PipelineTrigger.of(cfg.triggerConfig()).scheduler() != PipelineTrigger.Scheduler.MANUAL)
            throw new ApiException(409, ErrorCodes.CONFLICT, "'" + id + "' is already kept up to date on a schedule; "
                    + "stop that first to watch the folder instead");
        PendingChanges.holdRefusing(api, List.of("dataset"), "watching the inbox is a Dataset change");
        String key = key(imp.writeRoot(), id);
        if (on != Boolean.TRUE.equals(imp.content().get(WATCH_KEY))) {
            Map<String, Object> content = new java.util.LinkedHashMap<>(imp.content());
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
        if (on) register(target(api, EventLog.currentSpaceId(), imp.writeRoot(), id, imp.content()));
        else {
            unregister(key);
            LAST_ERROR.remove(errorKey(imp.dataRoot(), id));
        }
        return DatasetFeed.read(api, ex, id);
    }
}
