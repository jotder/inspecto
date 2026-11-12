package com.gamma.realtime;

import com.gamma.alert.AlertRule;
import com.gamma.alert.AlertService;
import com.gamma.alert.FiredWindows;
import com.gamma.alert.RealtimeDetection;
import com.gamma.alert.WindowedRuleEvaluator;
import com.gamma.etl.ConsignmentEvent;
import com.gamma.etl.EditionFeatures;
import com.gamma.etl.PipelineConfig;
import com.gamma.metrics.MetricRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.PipelineTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * One Space's Real-time Detection (PA-9d): the commit hook and the clock floor over its windowed and Pattern rules.
 *
 * <p><b>Groups.</b> The rules are grouped by (Dataset, event-time column, {@code distinctOn}); each group is one
 * {@link WindowedRuleEvaluator} over the committed files of the {@code trigger: stream} Pipelines that write the
 * Dataset (its {@code physicalRef} head names the Pipeline or its Stream, as {@code PipelineSchemas.producers} reads
 * it). A rule whose Dataset has no such Pipeline is inert, with its reason in {@link #status}.
 *
 * <p><b>On commit</b> ({@link #onCommit}) of a producer: the Consignment's own committed Parquet files (the event names
 * them, from lineage) are evaluated, then recorded in the {@link EvaluatedLedger}. <b>At build</b> (boot, a rule edit,
 * the Add-on returning): the committed files the ledger holds, and those committed before the group was armed or
 * longer than {@link #REPLAY_HORIZON_MS} ago, are loaded; every other committed file is replayed - evaluated - so a
 * Consignment committed but never evaluated is judged (owed by PA-9b's verification; with {@code distinctOn} a plain
 * load would buffer it and never evaluate it). The fired table stops anything firing twice.
 *
 * <p><b>The clock floor</b> ({@link #onStreamIdle}): the scheduler calls it only while the Pipeline is drained (no
 * backlog, no run in progress, nothing landed in its inbox), at most once a second; it judges absence Windows only.
 *
 * <p><b>Never on the commit's thread.</b> Every evaluation runs on the session's one worker, in arrival order, so a slow
 * evaluation never blocks a commit (D-PA41) and a tick never overtakes the commit queued before it.
 *
 * <p><b>Metrics.</b> {@code inspecto_detection_latency_seconds{space,rule}}: the Alert's raise time minus the event time
 * of the newest event in the Window that fired (for an absence, minus the Window's close, {@code end + lateness}).
 * {@code inspecto_detection_late_events_total{space,rule}}: committed rows that matched a rule but fell only in closed
 * Windows - stored, never alerted on.
 *
 * <p><b>Gating.</b> Each evaluation first asks {@code EditionFeatures.present(realtime)}: the module may be on the
 * classpath while a signed entitlement leaves the Add-on out (or a renewed one brings it back). While absent nothing is
 * evaluated; when it returns the groups are rebuilt, which replays what committed meanwhile.
 */
final class RealtimeSession implements RealtimeDetection.Session {

    private static final Logger log = LoggerFactory.getLogger(RealtimeSession.class);

    /** A committed, unevaluated file older than this is loaded at build, not replayed (its Windows closed long ago). */
    static final long REPLAY_HORIZON_MS = 24L * 3600_000L;

    static final String LATENCY = "inspecto_detection_latency_seconds";
    static final String LATE = "inspecto_detection_late_events_total";

    private final RealtimeDetection.Context ctx;
    private final ExecutorService worker;
    /** Test seam: told each newly fired key after its Alert (and Incident) was raised. */
    private volatile Consumer<Raised> raised = r -> {};

    // ── the worker's state (touched on the worker thread only, except status/close) ──
    private List<AlertRule> builtFrom;
    /** The stream Pipelines (name and database folder) the groups were built over: a change rebuilds them. */
    private List<String> builtOver;
    private List<Group> groups = List.of();
    private final Map<String, String> inert = new LinkedHashMap<>();
    private FiredWindows fired;
    private EvaluatedLedger ledger;
    private boolean wasPresent = true;
    private volatile boolean closed;

    /** One newly fired key: when its newest event happened and when its Alert was raised (epoch ms). */
    record Raised(String rule, String keyId, long eventMs, long raisedMs, boolean replay) {}

    /**
     * One evaluator and what feeds it. {@code buffered}: the files appended since the build (load, replay, commit) and
     * when, so a commit queued behind a build that already replayed its files is not appended twice (without
     * {@code distinctOn} it would count twice); entries older than {@link #BUFFERED_MEMORY_MS} are forgotten.
     */
    private record Group(String key, String dataset, List<AlertRule> rules, Set<String> producers, List<Path> roots,
                         WindowedRuleEvaluator evaluator, Map<Path, Long> buffered) {}

    /** How long a group remembers a buffered file: far longer than a commit waits in the queue. */
    static final long BUFFERED_MEMORY_MS = 3_600_000L;
    /** When this session opened: the groups of its first build are armed then, so what committed since is not history. */
    private final long openedMs = System.currentTimeMillis();
    private boolean everBuilt;

    RealtimeSession(RealtimeDetection.Context ctx) {
        this.ctx = ctx;
        this.worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("realtime-" + ctx.spaceId()).factory());
    }

    void onRaised(Consumer<Raised> listener) {
        this.raised = listener == null ? r -> {} : listener;
    }

    @Override
    public void onCommit(ConsignmentEvent e) {
        if (closed || e.dryRun() || !"SUCCESS".equals(e.status()) || e.outputFiles().isEmpty()) return;
        submit(() -> commit(e));
    }

    @Override
    public void onStreamIdle(String pipeline) {
        if (closed) return;
        submit(() -> tick(pipeline));
    }

    private void submit(Runnable task) {
        try {
            worker.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException ex) {
                    log.error("Real-time Detection in space '{}' failed", ctx.spaceId(), ex);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException closing) {
            log.debug("Real-time Detection in space '{}' is closing; dropped a task", ctx.spaceId());
        }
    }

    private void commit(ConsignmentEvent e) {
        Set<Path> pending = new LinkedHashSet<>();
        for (String f : e.outputFiles()) if (f.endsWith(".parquet")) pending.add(Path.of(f).toAbsolutePath().normalize());
        if (!sync(pending)) return;
        for (Group g : groups) {
            if (!g.producers().contains(e.pipeline().toLowerCase(Locale.ROOT))) continue;
            List<Path> files = new ArrayList<>();
            for (Path p : pending)
                if (g.roots().stream().anyMatch(p::startsWith) && !g.buffered().containsKey(p)) files.add(p);
            if (files.isEmpty()) continue;
            try {
                long now = System.currentTimeMillis();
                WindowedRuleEvaluator.Outcome o = g.evaluator().commit(files);
                ledger.record(g.key(), files, now);
                g.buffered().values().removeIf(at -> at < now - BUFFERED_MEMORY_MS);
                for (Path p : files) g.buffered().put(p, now);
                observe(g, o, false);
            } catch (Exception ex) {
                log.error("Real-time Detection: evaluating consignment {} of '{}' over dataset '{}' failed",
                        e.batchId(), e.pipeline(), g.dataset(), ex);
            }
        }
    }

    private void tick(String pipeline) {
        if (!sync(Set.of())) return;
        long now = System.currentTimeMillis();
        for (Group g : groups) {
            if (!g.producers().contains(pipeline.toLowerCase(Locale.ROOT))) continue;
            if (g.rules().stream().noneMatch(r -> r.eventWindow().isPattern() && r.eventWindow().pattern().isAbsence()))
                continue;
            try {
                observe(g, g.evaluator().tick(now), false);
            } catch (Exception ex) {
                log.error("Real-time Detection: the clock floor of dataset '{}' failed", g.dataset(), ex);
            }
        }
    }

    /** Metrics and the test seam for one evaluation. */
    private void observe(Group g, WindowedRuleEvaluator.Outcome o, boolean replay) {
        long now = System.currentTimeMillis();
        Map<String, Long> newest = new LinkedHashMap<>();   // rule|keyId -> the newest event (or close) among its new windows
        Map<String, AlertRule> byName = new LinkedHashMap<>();
        for (AlertRule r : g.rules()) byName.put(r.name(), r);
        for (WindowedRuleEvaluator.Breach b : o.newlyFired()) {
            AlertRule r = byName.get(b.rule());
            boolean absence = r != null && r.eventWindow().isPattern() && r.eventWindow().pattern().isAbsence();
            long at = absence ? b.windowEndMs() + r.eventWindow().lateness().toMillis() : b.lastEventMs();
            newest.merge(b.rule() + "|" + AlertService.keyId(b.key()), at, Math::max);
        }
        for (Map.Entry<String, Long> n : newest.entrySet()) {
            String rule = n.getKey().substring(0, n.getKey().indexOf('|'));
            String keyId = n.getKey().substring(n.getKey().indexOf('|') + 1);
            if (!replay)
                MetricRegistry.global().observe(LATENCY, "Seconds from the newest event of a fired Window to its Alert",
                        Map.of("space", ctx.spaceId(), "rule", rule), Math.max(0, now - n.getValue()) / 1000.0);
            raised.accept(new Raised(rule, keyId, n.getValue(), now, replay));
        }
        if (!replay)
            o.lateEvents().forEach((rule, late) -> {
                if (late > 0)
                    MetricRegistry.global().inc(LATE, "Committed events that matched a windowed rule but fell only in closed "
                            + "Windows (stored, not alerted on)", Map.of("space", ctx.spaceId(), "rule", rule), late);
            });
    }

    /**
     * Whether the Add-on is present; (re)builds the groups when the windowed rules or the stream Pipelines changed, or
     * the Add-on came back. Called on the worker before every evaluation; {@code pending} are the files of the commit
     * about to be evaluated, which a build neither loads nor replays.
     */
    private boolean sync(Set<Path> pending) {
        boolean present = EditionFeatures.present(EditionFeatures.REALTIME);
        if (!present) {
            if (wasPresent) log.warn("Real-time Detection in space '{}' is inert: {}", ctx.spaceId(),
                    EditionFeatures.refusal(EditionFeatures.REALTIME));
            wasPresent = false;
            return false;
        }
        List<AlertRule> rules = ctx.alerts().windowedRules();
        List<String> over = new ArrayList<>();
        for (PipelineConfig p : ctx.pipelines().get())
            if (isStream(p)) over.add(p.identity().pipelineName() + "|" + p.stream() + "|" + p.dirs().database());
        if (!wasPresent || !rules.equals(builtFrom) || !over.equals(builtOver)) {
            wasPresent = true;
            builtOver = over;
            rebuild(rules, pending);
        }
        return !groups.isEmpty();
    }

    private void rebuild(List<AlertRule> rules, Set<Path> pending) {
        closeGroups();
        inert.clear();
        builtFrom = rules;
        if (rules.isEmpty()) return;
        try {
            if (fired == null) fired = new FiredWindows(ctx.stateDir() == null ? null : ctx.stateDir().resolve("fired.duckdb"));
            if (ledger == null) ledger = new EvaluatedLedger(ctx.stateDir() == null ? null : ctx.stateDir().resolve("evaluated.duckdb"));
        } catch (Exception e) {
            log.error("Real-time Detection in space '{}' cannot open its state under {}; every windowed rule is inert",
                    ctx.spaceId(), ctx.stateDir(), e);
            rules.forEach(r -> inert.put(r.name(), "the detection state cannot be opened: " + e.getMessage()));
            return;
        }
        Map<String, List<AlertRule>> byGroup = new LinkedHashMap<>();
        for (AlertRule r : rules)
            byGroup.computeIfAbsent(r.dataset() + "|" + r.eventWindow().time() + "|" + r.eventWindow().distinctOn(),
                    k -> new ArrayList<>()).add(r);
        List<Group> built = new ArrayList<>();
        for (Map.Entry<String, List<AlertRule>> e : byGroup.entrySet()) {
            Group g = build(e.getKey(), e.getValue(), pending);
            if (g != null) built.add(g);
        }
        groups = List.copyOf(built);
        everBuilt = true;
    }

    /** One group: its producers, its evaluator, and the boot load plus the replay of what was never evaluated. */
    private Group build(String key, List<AlertRule> rules, Set<Path> pending) {
        String dataset = rules.get(0).dataset();
        String store = physicalRefHead(dataset);
        List<PipelineConfig> producers = new ArrayList<>();
        if (store != null)
            for (PipelineConfig p : ctx.pipelines().get())
                if ((store.equalsIgnoreCase(p.identity().pipelineName()) || store.equalsIgnoreCase(p.stream())) && isStream(p))
                    producers.add(p);
        if (producers.isEmpty()) {
            String why = store == null ? "dataset '" + dataset + "' has no physicalRef naming the Pipeline that writes it"
                    : "no stream-triggered Pipeline of this Space writes '" + store + "' (dataset '" + dataset + "')";
            log.warn("Real-time Detection: rule(s) {} are inert: {}", rules.stream().map(AlertRule::name).toList(), why);
            rules.forEach(r -> inert.put(r.name(), why));
            return null;
        }
        Set<String> names = new LinkedHashSet<>();
        List<Path> roots = new ArrayList<>();
        WindowedRuleEvaluator evaluator = null;
        try {
            for (PipelineConfig p : producers) {
                names.add(p.identity().pipelineName().toLowerCase(Locale.ROOT));
                // an object-store dirs.database (s3://...) is not a local folder: the rule is inert, with this reason
                Path root = Path.of(p.dirs().database()).toAbsolutePath().normalize();
                if (!roots.contains(root)) roots.add(root);
            }
            evaluator = new WindowedRuleEvaluator(dataset, rules, roots, null, fired, ctx.alerts());
            long now = System.currentTimeMillis();
            // a group of the session's first build is armed when the session opened (what committed since is no history);
            // a rule authored later arms its group now, so it never fires on what committed before it existed
            long armed = ledger.armedAt(key, everBuilt ? now : Math.min(now, openedMs));
            Set<String> evaluated = ledger.paths(key);
            long floor = Math.max(armed, now - REPLAY_HORIZON_MS);
            List<Path> load = new ArrayList<>(), replay = new ArrayList<>();
            for (Path root : roots)
                for (Path f : WindowedRuleEvaluator.committedFiles(root)) {
                    Path n = f.toAbsolutePath().normalize();
                    if (pending.contains(n)) continue;   // the commit being evaluated: its own evaluation follows
                    if (!evaluated.contains(EvaluatedLedger.key(n)) && modified(n) >= floor) replay.add(n);
                    else load.add(n);
                }
            Map<Path, Long> buffered = new java.util.HashMap<>();
            if (!load.isEmpty()) evaluator.load(load);
            for (Path f : load) buffered.put(f, now);
            if (!replay.isEmpty()) {
                replay.sort(Comparator.comparingLong(RealtimeSession::modified).thenComparing(Comparator.naturalOrder()));
                observe(new Group(key, dataset, rules, names, roots, evaluator, buffered), evaluator.commit(replay), true);
                for (Path f : replay) buffered.put(f, now);
                ledger.record(key, replay, now);
                log.info("Real-time Detection: replayed {} committed file(s) of dataset '{}' that were never evaluated",
                        replay.size(), dataset);
            }
            ledger.prune(key, now - EvaluatedLedger.KEEP_MS);
            log.info("Real-time Detection armed over dataset '{}' ({} rule(s), {} file(s) loaded, Pipelines {})",
                    dataset, rules.size(), load.size(), names);
            return new Group(key, dataset, List.copyOf(rules), Set.copyOf(names), List.copyOf(roots), evaluator, buffered);
        } catch (Exception ex) {
            if (evaluator != null) evaluator.close();
            String why = "the window buffer cannot be built: " + ex.getMessage();
            log.error("Real-time Detection: rule(s) {} are inert: {}", rules.stream().map(AlertRule::name).toList(), why, ex);
            rules.forEach(r -> inert.put(r.name(), why));
            return null;
        }
    }

    private String physicalRefHead(String dataset) {
        Path registry = ctx.registry().get();
        if (registry == null) return null;
        try {
            Object ref = new ComponentStore(registry).get("dataset", dataset)
                    .map(c -> c.content().get("physicalRef")).orElse(null);
            String s = ref == null ? "" : String.valueOf(ref).trim();
            if (s.contains("/")) s = s.substring(0, s.indexOf('/'));
            return s.isEmpty() ? null : s;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isStream(PipelineConfig p) {
        try {
            return p.triggerConfig() != null
                    && PipelineTrigger.of(p.triggerConfig()).kind() == PipelineTrigger.Kind.STREAM;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static long modified(Path f) {
        try {
            return Files.getLastModifiedTime(f).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    @Override
    public Map<String, Object> status() {
        java.util.concurrent.CompletableFuture<Map<String, Object>> f = new java.util.concurrent.CompletableFuture<>();
        submit(() -> {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Group g : groups) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("rules", g.rules().stream().map(AlertRule::name).toList());
                d.put("pipelines", List.copyOf(g.producers()));
                try {
                    d.put("bufferedRows", g.evaluator().bufferedRows());
                } catch (Exception e) {
                    d.put("bufferedRows", -1);
                }
                d.put("watermarkUs", g.evaluator().watermarkUs());
                out.put(g.dataset(), d);
            }
            out.put("inert", Map.copyOf(inert));
            f.complete(out);
        });
        try {
            return f.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return Map.of("error", String.valueOf(e));
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        worker.shutdown();
        try {
            if (!worker.awaitTermination(10, TimeUnit.SECONDS)) worker.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            worker.shutdownNow();
        }
        closeGroups();
        try {
            if (fired != null) fired.close();
            if (ledger != null) ledger.close();
        } catch (Exception e) {
            log.debug("Real-time Detection state of space '{}' did not close cleanly: {}", ctx.spaceId(), e.getMessage());
        }
    }

    private void closeGroups() {
        for (Group g : groups) g.evaluator().close();
        groups = List.of();
    }
}
