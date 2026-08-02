package com.gamma.service;

import com.gamma.etl.BatchEvent;
import com.gamma.etl.BatchEventBus;
import com.gamma.etl.PipelineConfig;
import com.gamma.acquire.CollectorConnectors;
import com.gamma.acquire.IntakeGovernor;
import com.gamma.inspector.CollectorProcessor;
import com.gamma.inspector.MultiCollectorProcessor;
import com.gamma.pipeline.PipelineTrigger;
import com.gamma.pipeline.exec.TriggerCoalescer;
import com.gamma.util.CronExpression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.slf4j.MDC;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * The scheduled + event-driven ingest driver, extracted from {@link CollectorService} (M2 step 2,
 * modularization-optimization-plan §2.2.1a). It owns the poll-cycle body ({@link #dispatchCycle()} /
 * {@link #runCycle()}), the T13 entry-node trigger gating (interval / cron — {@link #dueThisTick}), and
 * the event-trigger hand-off ({@link #onUpstreamCommit}) — the "loop" half of the former god object.
 *
 * <h3>Two cycle entry points, one body</h3>
 * A cycle is <em>selection</em> ({@link #selectDue}, the only part that touches the registry) followed by
 * one independent per-pipeline task ({@link #runOne}) per selected pipeline. The two entry points differ
 * only in whether they wait:
 * <ul>
 *   <li>{@link #dispatchCycle()} — the <b>periodic driver</b>. Submits the tasks and returns, so a tick
 *       never waits on the runs it started and a slow pipeline in tick <i>N</i> cannot delay tick
 *       <i>N+1</i> for every other pipeline. Each task releases its own claim as it finishes.</li>
 *   <li>{@link #runCycle()} — the <b>synchronous</b> path behind {@code runAllOnce} (the {@code POST
 *       /trigger} route and every test that asserts on a {@link MultiCollectorProcessor.RunResult}).
 *       Same tasks, awaited, so the total/failed contract is unchanged.</li>
 * </ul>
 * Because ticks now overlap, two things could no longer be scoped to a cycle and were lifted to the
 * scheduler: the {@linkplain #runPermits run budget} (one process-lifetime semaphore, so overlapping
 * cycles plus a concurrent {@code runAllOnce} still respect {@code maxConcurrentRuns} between them
 * rather than each getting a fresh allowance) and the {@code inspecto_active_runs} gauge (an
 * {@linkplain #activeRuns incrementing counter} — resetting it to 0 at a cycle boundary would zero the
 * runs another cycle still has in flight).
 *
 * <h3>Shared state, not owned</h3>
 * The scheduler does <b>not</b> own the run guard, registry lock, config registry, run budget, event
 * bus, or the pipeline sets — those are shared with {@link CollectorService} and passed in
 * <em>by reference</em>. Two contracts are load-bearing:
 * <ul>
 *   <li><b>One {@link PipelineRunGuard}.</b> {@link #runCycle()} must claim pipelines from the
 *       <em>same</em> guard instance the operator-trigger path ({@code CollectorService.runPipeline})
 *       claims from. A cloned guard compiles fine but lets a manual run overlap a cycle run of the
 *       same pipeline — double-ingesting the inbox. Pinned by {@code CollectorServiceIngestLockTest}.
 *       <p>The guard replaced a single global {@code ingestLock} that was held across the whole cycle:
 *       exclusion is per-pipeline, so a slow pipeline no longer delays the next tick for every other
 *       pipeline. The narrow {@code registryLock} that remains serialises only registry mutation +
 *       {@code ConfigRegistry.rebuild}, never a run.</li>
 *   <li><b>Off-thread event hand-off.</b> {@link BatchEventBus#publish} is synchronous on the
 *       publishing (claim-holding) thread; {@link #onUpstreamCommit} therefore hands the triggered run
 *       to {@link #triggerWorkers} (a virtual thread) instead of running it inline.
 *       <p>⚠ A {@link PipelineRunGuard} claim is deliberately <em>not</em> reentrant, so an inline run of
 *       the <em>same</em> pipeline on the publishing thread would block forever on the claim that thread
 *       already holds. The off-thread hand-off is what makes the claim mean anything. Pinned by
 *       {@code CollectorServiceTriggerTest}.</li>
 * </ul>
 * The caller applies the per-space MDC ({@code CollectorService.underSpace}) around {@link #runCycle()},
 * and the {@code runPipeline} callback re-applies it on the trigger path, so this class needs no MDC of
 * its own.
 */
final class PipelineScheduler {

    private static final Logger log = LoggerFactory.getLogger(PipelineScheduler.class);

    // ── Shared references (owned by CollectorService) ────────────────────────────
    private final List<Path> registry;
    private final ConfigRegistry configRegistry;
    private final Set<String> paused;
    private final Set<String> running;
    /** Per-pipeline ingest exclusion (shared instance — see class doc). */
    private final PipelineRunGuard runGuard;
    /** Serialises registry mutation + {@code ConfigRegistry.rebuild} only — never held across a run. */
    private final ReentrantLock registryLock;
    private final BatchEventBus bus;
    private final ExecutorService triggerWorkers;
    /** The scheduler's fixed poll delay (ms) — the budget one cycle is expected to fit inside, and so the
     *  overrun threshold the T15 admission controller adjusts against ({@link #governCycle}). */
    private final long pollIntervalMs;
    /** Run one registered pipeline by name (stays on {@link CollectorService}; claims the same run guard). */
    private final Consumer<String> runPipeline;
    /** Project the on-disk audit into the DB status store, if DB-backed (stays on {@link CollectorService}). */
    private final Runnable syncStatus;

    // ── Owned by the scheduler ───────────────────────────────────────────────────
    /** T13 / §3.8 — per-pipeline last-run epoch (ms); gates a {@code schedule:{every}}/{@code cron} pipeline
     *  by its own cadence instead of running every active flow each tick. A pipeline with no {@code trigger:}
     *  is {@code DEFAULT_POLL} and still runs every cycle. */
    private final Map<String, Long> lastRunAtMs = new ConcurrentHashMap<>();
    /** Per-pipeline coalescer for {@code event}-triggered flows: an upstream-commit storm collapses to one
     *  non-overlapping run (the in-process run-guard debounce, lifted to the flow grain). */
    private final Map<String, TriggerCoalescer> eventCoalescers = new ConcurrentHashMap<>();
    /** Zone for evaluating {@code cron} triggers (mirrors {@link com.gamma.job.JobService}). */
    private final ZoneId triggerZone = ZoneId.systemDefault();
    /** Epoch the scheduler started — the cron "last fire" baseline before a pipeline has ever run. */
    private final long serviceStartMs = System.currentTimeMillis();
    /** The {@code maxConcurrentRuns} budget, held for the scheduler's lifetime rather than minted per cycle —
     *  see class doc. A dispatched run acquires one permit for the duration of its own ingest. */
    private final Semaphore runPermits;
    /** Runs currently executing across <em>all</em> in-flight cycles, mirrored to {@code inspecto_active_runs}. */
    private final AtomicInteger activeRuns = new AtomicInteger();
    /** Per-pipeline acquisition exclusion — SEPARATE from {@link #runGuard} (B3b): acquisition and ingest of
     *  the <em>same</em> pipeline may overlap (fetch the next files while the last batch commits), while two
     *  acquisitions of it may not (a slow fetch is skipped, not queued). */
    private final PipelineRunGuard acquireGuard = new PipelineRunGuard();
    /** Acquisition's own budget, held for the scheduler's lifetime — deliberately NOT {@link #runPermits}:
     *  network fetch and DuckDB ingest must not compete for one allowance (B3b). */
    private final Semaphore acquirePermits;

    PipelineScheduler(List<Path> registry, ConfigRegistry configRegistry, Set<String> paused,
                      Set<String> running, PipelineRunGuard runGuard, ReentrantLock registryLock,
                      BatchEventBus bus,
                      ExecutorService triggerWorkers, int maxConcurrentRuns, int maxConcurrentAcquisitions,
                      long pollIntervalMs, Consumer<String> runPipeline, Runnable syncStatus) {
        this.registry          = registry;
        this.configRegistry    = configRegistry;
        this.paused            = paused;
        this.running           = running;
        this.runGuard          = runGuard;
        this.registryLock      = registryLock;
        this.bus               = bus;
        this.triggerWorkers    = triggerWorkers;
        this.runPermits        = new Semaphore(Math.max(1, maxConcurrentRuns));
        this.acquirePermits    = new Semaphore(Math.max(1, maxConcurrentAcquisitions));
        this.pollIntervalMs    = pollIntervalMs;
        this.runPipeline       = runPipeline;
        this.syncStatus        = syncStatus;
    }

    /**
     * One pipeline this cycle selected, paired with the claim that keeps a second run of it out. The claim is
     * taken during selection (so a pipeline cannot be picked twice by overlapping ticks) and released by the
     * task that runs it — ownership transfers to {@link #runOne}, which must therefore always be reached or
     * the claim explicitly closed (see {@link #dispatch}).
     */
    private record Due(PipelineConfig cfg, String id, PipelineRunGuard.Claim claim) {}

    /**
     * Start every registered, due pipeline and <b>return without waiting</b> — the periodic driver's entry
     * point. This is what keeps one slow pipeline from delaying the next tick for all the others: the tick
     * only does selection (bounded work under {@link #registryLock}) and hands each run to
     * {@link #triggerWorkers}. A pipeline still running when the next tick fires is simply not selected
     * again, because its claim is still held.
     */
    void dispatchCycle() {
        // Periodic driver: ingest only. A remote collector's files are fetched by the separate acquisition
        // driver ({@link #dispatchAcquireCycle()}) on its own timer, so the poll tick must not re-fetch (B3b).
        dispatch(selectDue(System.currentTimeMillis()), false);
    }

    /**
     * Run every registered, due pipeline once, concurrently (bounded by the {@linkplain #runPermits budget}),
     * feeding committed-batch events to the bus — then <b>wait for them all</b>. Same tasks as
     * {@link #dispatchCycle()}, awaited so {@code runAllOnce} can report an outcome. The caller
     * ({@code CollectorService.runAllOnce}) has already applied this space's MDC, which {@link #dispatch}
     * captures and each worker re-applies, so the runs execute under the right space.
     *
     * @return the run outcome (total / failed source counts)
     */
    MultiCollectorProcessor.RunResult runCycle() {
        // Explicit "run all now" (POST /trigger, tests): a self-contained acquire-then-ingest cycle, so it
        // fetches inline exactly as before B3b rather than waiting on the background acquisition driver.
        List<Future<Integer>> runs = dispatch(selectDue(System.currentTimeMillis()), true);
        int failed = 0;
        for (Future<Integer> f : runs) {
            try {
                failed += f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failed++;
                break;
            } catch (Exception e) {
                failed++;                                   // the task itself threw — runOne already logged what it could
                log.error("Source task error", e);
            }
        }
        if (failed > 0) log.warn("Poll cycle: {} of {} source(s) failed", failed, runs.size());
        return new MultiCollectorProcessor.RunResult(runs.size(), failed);
    }

    /**
     * Pick the pipelines to run on this tick and claim each one. The <em>only</em> part of a cycle that needs
     * {@link #registryLock}, and it is bounded work — the lock is never held across a run, which is what lets
     * ticks overlap at all.
     */
    private List<Due> selectDue(long nowMs) {
        List<Due> due = new ArrayList<>();
        registryLock.lock();
        try {
            // Re-index configs once per cycle — an mtime-cached rebuild, so a steady-state cycle re-parses
            // nothing; an edited pipeline/schema reloads on the next tick. Fires catalog invalidation via
            // the registry callback.
            configRegistry.rebuild(registry);
            // Build the run set from the cached index: skip paused pipelines and any not yet activated
            // (`active: true`). Each runnable config is re-stamped with a fresh run timestamp for this cycle
            // (cheap copy; no re-parse). Iterate the registered paths (not the id-keyed index) so two files
            // are both run even if they declare the same name — matching the prior path-level run semantics.
            for (Path p : registry) {
                PipelineConfig cfg = configRegistry.configForPath(p).orElse(null);
                if (cfg == null) continue;                                   // unloadable — already warned
                String id = cfg.identity().pipelineName();
                if (paused.contains(id) || !cfg.active()) continue;          // paused or not activated
                if (cfg.template()) continue;                                // authoring template — never runs
                if (!dueThisTick(cfg, id, nowMs)) continue;                  // T13: trigger gates the loop
                // Per-pipeline exclusion: a pipeline still running from an earlier tick is SKIPPED, not
                // queued — queueing would pile runs up behind a slow pipeline. Its cadence baseline is
                // left alone so it becomes due again as soon as it is free.
                PipelineRunGuard.Claim claim = runGuard.tryAcquire(id);
                if (claim == null) continue;
                due.add(new Due(cfg.forNewRun(), id, claim));                // fresh per-cycle timestamp
            }
        } finally {
            registryLock.unlock();
        }
        due.forEach(d -> lastRunAtMs.put(d.id(), nowMs));   // stamp cadence baseline (start-to-start)
        return due;
    }

    /**
     * Hand each selected pipeline to {@link #triggerWorkers} as an independent task. One
     * {@code inspecto_poll_cycles_total} tick per non-empty cycle, as before.
     *
     * <p>The status-DB projection stays <b>once per cycle</b> rather than once per run: it re-projects the
     * whole audit for every config, so per-run would multiply that by the pipeline count. The last task to
     * finish fires it, which is the same point in the cycle as before — see {@link #runOne}.
     */
    private List<Future<Integer>> dispatch(List<Due> due, boolean acquireFirst) {
        if (due.isEmpty()) return List.of();
        com.gamma.metrics.MetricRegistry.global().inc("inspecto_poll_cycles_total", "Poll cycles run", Map.of());
        Map<String, String> mdc = MDC.getCopyOfContextMap();   // propagate this space onto each worker
        AtomicInteger pending = new AtomicInteger(due.size());
        List<Future<Integer>> runs = new ArrayList<>(due.size());
        for (Due d : due) {
            try {
                runs.add(triggerWorkers.submit(() -> runOne(d, mdc, pending, acquireFirst)));
            } catch (RejectedExecutionException e) {
                // The service is shutting down. Release the claim we took during selection rather than
                // leaking it, and decrement so a sibling task still fires the final status sync.
                d.claim().close();
                pending.decrementAndGet();
                log.debug("Poll cycle: '{}' not dispatched (service stopping)", d.id());
            }
        }
        return runs;
    }

    /**
     * Ingest one selected pipeline, then release everything it holds. Runs on a {@link #triggerWorkers}
     * virtual thread for both cycle entry points, so nothing here may assume the caller's thread or MDC.
     *
     * <p>Delegates the run itself to {@code runConfigs} with a single config so the failure accounting,
     * per-source logging, and MDC propagation stay in the one tested place; the concurrency bound comes from
     * this scheduler's {@link #runPermits} instead, which is the budget that now spans overlapping cycles.
     *
     * @return {@code 1} when this pipeline's run failed, else {@code 0} — summed by {@link #runCycle()}
     */
    private int runOne(Due due, Map<String, String> mdc, AtomicInteger pending, boolean acquireFirst) {
        if (mdc != null) MDC.setContextMap(mdc);
        com.gamma.metrics.MetricRegistry reg = com.gamma.metrics.MetricRegistry.global();
        try (PipelineRunGuard.Claim claim = due.claim()) {
            runPermits.acquire();
            // Clock starts AFTER the permit: time spent queued for the budget is not this pipeline's run
            // duration, and admitting fewer of its files could not shorten it.
            long startMs = System.currentTimeMillis();
            running.add(due.id());
            reg.setGauge("inspecto_active_runs", "Source runs currently executing", Map.of(),
                    activeRuns.incrementAndGet());
            try {
                MultiCollectorProcessor.RunResult r =
                        MultiCollectorProcessor.runConfigs(List.of(due.cfg()), 1, bus.sink(), acquireFirst);
                if (r.failed() > 0)
                    reg.inc("inspecto_source_run_failures_total", "Source-run failures", Map.of(), r.failed());
                return r.failed();
            } finally {
                running.remove(due.id());
                reg.setGauge("inspecto_active_runs", "Source runs currently executing", Map.of(),
                        activeRuns.decrementAndGet());
                governRun(due.id(), System.currentTimeMillis() - startMs, reg);
                runPermits.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();             // shutting down while queued for a permit
            return 1;
        } finally {
            // Refresh the status DB (if DB-backed) once the cycle's last run is done, so its commits are
            // queryable. (Catalog invalidation already fired from configRegistry.rebuild during selection.)
            if (pending.decrementAndGet() == 0) syncStatus.run();
            MDC.clear();
        }
    }

    /**
     * T15 adaptive back-pressure (§3.5): feed one pipeline's run duration to the {@link IntakeGovernor} so a
     * run that overran the poll interval halves that pipeline's admission cap, and a comfortably-fitting run
     * restores it. Inert unless an operator set {@code -Dingest.maxFilesPerCycle}.
     *
     * <p>The signal is <b>overrun</b>, not inbox lag: admitting fewer files cannot reduce inbox age or
     * pending depth, so throttling on those would be positive feedback that pins a backlogged-but-healthy
     * pipeline at the floor. See {@link IntakeGovernor}'s class doc for the full reasoning.
     *
     * <p>The measurement is <b>per pipeline run</b>, not per cycle. It used to be cycle wall time because
     * {@code runConfigs} reported no per-pipeline timing — which charged every pipeline in an overrunning
     * tick for the slowest one's overrun, and is not even well defined now that ticks overlap. Since the cap
     * itself is per-pipeline ({@link IntakeGovernor#capFor}), attributing the duration to the pipeline that
     * spent it is strictly more correct.
     */
    private void governRun(String id, long runMillis, com.gamma.metrics.MetricRegistry reg) {
        IntakeGovernor gov = IntakeGovernor.shared();
        if (!gov.policy().active()) return;
        gov.observeCycle(List.of(id), runMillis, pollIntervalMs);
        reg.setGauge("inspecto_intake_cap", "Files one poll cycle may admit (T15 admission control)",
                Map.of("pipeline", id), gov.capFor(id));
    }

    /**
     * Whether the loop scheduler should run {@code cfg} on this tick, per its entry-node trigger (T13 / §3.8):
     * <ul>
     *   <li><b>absent / {@code DEFAULT_POLL}</b> — every tick (unchanged from the pre-T13 poll-all behaviour);</li>
     *   <li><b>{@code schedule:{every:N}}</b> — only once {@code N} has elapsed since the last run;</li>
     *   <li><b>{@code schedule:{cron}}</b> — only when a cron fire is due since the last run;</li>
     *   <li><b>{@code event} / {@code manual}</b> — never on the loop; driven by {@link #onUpstreamCommit}
     *       and {@code CollectorService.runPipeline} respectively.</li>
     * </ul>
     */
    private boolean dueThisTick(PipelineConfig cfg, String id, long nowMs) {
        PipelineTrigger t = PipelineTrigger.of(cfg.triggerConfig());
        return switch (t.scheduler()) {
            case EVENT, MANUAL -> false;                       // driven off the poll loop
            case LOOP -> switch (t.kind()) {
                case SCHEDULE_INTERVAL -> {
                    Long last = lastRunAtMs.get(id);
                    yield last == null || (nowMs - last) >= t.everyMs();
                }
                case SCHEDULE_CRON -> cronDue(id, t.cron(), nowMs);
                default -> true;                               // DEFAULT_POLL — every tick (today's behaviour)
            };
        };
    }

    /** A cron trigger is due when its next fire after the last run (or service start) is at/​before now. */
    private boolean cronDue(String id, String cron, long nowMs) {
        try {
            long lastMs = lastRunAtMs.getOrDefault(id, serviceStartMs);
            ZonedDateTime from = Instant.ofEpochMilli(lastMs).atZone(triggerZone);
            ZonedDateTime next = CronExpression.parse(cron).next(from);
            return !next.isAfter(Instant.ofEpochMilli(nowMs).atZone(triggerZone));   // next <= now ⇒ fire due
        } catch (Exception e) {
            log.warn("Pipeline '{}' has an invalid cron trigger '{}' — skipping this cycle: {}",
                    id, cron, e.getMessage());
            return false;
        }
    }

    /**
     * Bus listener (T13 / §3.8): an upstream SUCCESS commit triggers every {@code event}-triggered flow whose
     * {@code from} names that upstream. The run is handed to {@link #triggerWorkers} (the bus delivers on the
     * publishing thread, which holds that pipeline's claim; running inline would deadlock) and coalesced per
     * flow so an upstream storm collapses to one non-overlapping run.
     */
    void onUpstreamCommit(BatchEvent event) {
        if (!"SUCCESS".equals(event.status())) return;
        for (Path p : registry) {
            PipelineConfig cfg = configRegistry.configForPath(p).orElse(null);
            if (cfg == null) continue;
            String id = cfg.identity().pipelineName();
            if (paused.contains(id) || !cfg.active()) continue;
            if (id.equals(event.pipeline())) continue;                       // self-loop guard
            PipelineTrigger t = PipelineTrigger.of(cfg.triggerConfig());
            if (t.scheduler() != PipelineTrigger.Scheduler.EVENT) continue;
            if (!triggerMatches(t.from(), event.pipeline())) continue;
            TriggerCoalescer coalescer = eventCoalescers.computeIfAbsent(id, k -> new TriggerCoalescer());
            triggerWorkers.submit(() -> coalescer.signal(() -> runPipeline.accept(id)));
        }
    }

    /**
     * Whether an event trigger's {@code from} names {@code upstream}. Matches case-insensitively against the
     * emitted pipeline id (lowercased {@code pipelineName}) and tolerates a {@code flows/<id>} prefix, so an
     * operator may write {@code from: orders}, {@code from: ORDERS}, or {@code from: flows/orders}.
     */
    private static boolean triggerMatches(String from, String upstream) {
        if (from == null || from.isBlank() || upstream == null) return false;
        String f = from.trim().toLowerCase();
        String u = upstream.toLowerCase();
        return f.equals(u) || f.endsWith("/" + u);
    }

    /**
     * Reset a pipeline's cadence baseline after an operator-/watch-triggered run (start-to-start), so a
     * {@code schedule:{every}}/{@code cron} pipeline's next loop tick is measured from this run. Called by
     * {@code CollectorService.runPipeline}, which owns the sync trigger path but shares this scheduler's
     * cadence map.
     */
    void recordManualRun(String id, long nowMs) {
        lastRunAtMs.put(id, nowMs);
    }

    /**
     * Drop a pipeline's scheduler bookkeeping when it is unregistered. Without this, the cadence
     * ({@link #lastRunAtMs}) and coalescer ({@link #eventCoalescers}) maps accumulate one orphan entry
     * per deleted pipeline for the lifetime of the space's service — a slow leak under pipeline churn.
     * The {@link TriggerCoalescer} holds only in-heap atomics, so dropping the reference is enough.
     */
    void forget(String id) {
        lastRunAtMs.remove(id);
        eventCoalescers.remove(id);
        acquireGuard.forget(id);              // per-pipeline acquire lock (B3b) — same leak-under-churn reason
        IntakeGovernor.shared().forget(id);   // same leak-under-churn reason, one map further down
    }

    // ── Acquisition driver (B3b) ──────────────────────────────────────────────────
    // A sibling of the ingest cycle: its own timer (registered by CollectorService), its own budget
    // (acquirePermits) and its own per-pipeline guard (acquireGuard), so fetching a remote source runs
    // independently of — and may overlap — ingesting it, while two fetches of the same source cannot.

    /**
     * One acquisition tick: fetch-and-land for every due REMOTE pipeline, then return without waiting —
     * the network-facing mirror of {@link #dispatchCycle()}. Local pipelines have nothing to acquire and
     * are skipped. Cadence is the acquisition timer's own interval, not a pipeline's {@code trigger:} — a
     * cron-gated pipeline still wants its files staged and ready before the cron fires.
     */
    void dispatchAcquireCycle() {
        dispatchAcquire(selectDueForAcquire());
    }

    /**
     * Pick the remote pipelines to acquire this tick and claim each under {@link #acquireGuard}. Shares
     * {@link #registryLock}/{@link #configRegistry} with ingest selection (bounded work, never held across a
     * fetch). A pipeline still fetching from an earlier tick keeps its claim and is skipped, not queued.
     */
    private List<Due> selectDueForAcquire() {
        List<Due> due = new ArrayList<>();
        registryLock.lock();
        try {
            configRegistry.rebuild(registry);
            for (Path p : registry) {
                PipelineConfig cfg = configRegistry.configForPath(p).orElse(null);
                if (cfg == null) continue;
                String id = cfg.identity().pipelineName();
                if (paused.contains(id) || !cfg.active() || cfg.template()) continue;
                if (!CollectorConnectors.isRemote(cfg)) continue;   // local: nothing to acquire
                PipelineRunGuard.Claim claim = acquireGuard.tryAcquire(id);
                if (claim == null) continue;                        // still fetching from an earlier tick
                due.add(new Due(cfg.forNewRun(), id, claim));
            }
        } finally {
            registryLock.unlock();
        }
        return due;
    }

    /** Hand each selected pipeline's fetch to {@link #triggerWorkers}, releasing the claim on a rejected
     *  submit (service stopping) so it cannot leak — mirrors {@link #dispatch}. */
    private void dispatchAcquire(List<Due> due) {
        if (due.isEmpty()) return;
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        for (Due d : due) {
            try {
                triggerWorkers.submit(() -> acquireOne(d, mdc));
            } catch (RejectedExecutionException e) {
                d.claim().close();
                log.debug("Acquire cycle: '{}' not dispatched (service stopping)", d.id());
            }
        }
    }

    /** Fetch-and-land one pipeline under its {@link #acquireGuard} claim and the {@link #acquirePermits}
     *  budget, then release both. Runs on a {@link #triggerWorkers} virtual thread. */
    private void acquireOne(Due due, Map<String, String> mdc) {
        if (mdc != null) MDC.setContextMap(mdc);
        try (PipelineRunGuard.Claim claim = due.claim()) {
            acquirePermits.acquire();
            try {
                int landed = CollectorProcessor.acquire(due.cfg());
                if (landed > 0) log.info("Acquired {} file(s) for '{}'", landed, due.id());
            } catch (Exception e) {
                log.error("Acquisition failed for '{}'", due.id(), e);
            } finally {
                acquirePermits.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();                 // shutting down while queued for a permit
        } finally {
            MDC.clear();
        }
    }
}
