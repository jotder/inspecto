package com.gamma.acquire;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-pipeline <b>admission control</b> for the poll cycle (pipeline-graph §3.5 / T15): how many inbox
 * files one cycle may admit, and an adaptive controller that lowers that cap while cycles are overrunning
 * the poll interval.
 *
 * <p>State is process-wide and keyed by pipeline id on the {@link #shared()} singleton — the same
 * cross-cycle-state idiom as {@link CircuitBreaker#shared()} / {@link StabilityGate#shared()}, since each
 * static poll cycle is a fresh run. {@code CollectorProcessor.collect} reads {@link #capFor} on the run
 * path; the scheduler calls {@link #observeCycle} once per <em>pipeline run</em> to feed the controller —
 * since the cap is per-pipeline, the duration is attributed to the pipeline that spent it rather than to
 * every pipeline that shared a tick with it (see {@code PipelineScheduler.governRun}).
 *
 * <h3>Inert unless configured</h3>
 * The whole mechanism is opt-in: with no {@code -Dingest.maxFilesPerCycle} there is no base cap, {@link #capFor}
 * returns {@link #UNBOUNDED} and nothing in the ingest path changes. Setting a base cap enables both the hard
 * cap and (unless {@code -Dingest.backpressure.adaptive=false}) the controller.
 *
 * <h3>Why cycle overrun, not inbox lag</h3>
 * §3.5's sketch halves the cap when {@code oldestInboxAge > 3 × pollInterval} or {@code pending > 10 × cap}.
 * Both are <em>positive</em> feedback: admitting fewer files cannot reduce inbox age or pending depth — it
 * raises them — so a sustained backlog would ratchet the cap to {@link Policy#minCap()} and pin it there,
 * throttling a healthy pipeline and deepening the very backlog it was reacting to. <b>Overrun</b>
 * (run duration vs. the poll interval) is the signal that actually means "this run admitted more than it
 * could process", and it closes the loop <em>negatively</em>: admit less ⇒ the run shortens ⇒ the cap
 * restores. Inbox lag stays an observability/alert surface ({@code inspecto_inbox_oldest_seconds},
 * {@code InboxStatus.oldestInboxAgeSeconds}), not a throttle input.
 */
public final class IntakeGovernor {

    /** {@link #capFor} result meaning "admit every candidate" (the default, and the pre-T15 behaviour). */
    public static final int UNBOUNDED = 0;

    /**
     * Admission-control thresholds, read from system properties so the defaults are never hard-coded into
     * the ingest path (§3.5's "configurable, conservative default" — decided 2026-06-16).
     *
     * @param baseCap  files one cycle may admit when unthrottled; {@code 0} disables admission control entirely
     * @param minCap   floor the controller may halve down to (never below 1, so a capped pipeline always progresses)
     * @param adaptive whether cycle overrun adjusts the cap; {@code false} pins it at {@code baseCap} (a hard cap)
     */
    public record Policy(int baseCap, int minCap, boolean adaptive) {

        /** {@code -Dingest.maxFilesPerCycle} (default 0 = off) · {@code -Dingest.minFilesPerCycle} (1) ·
         *  {@code -Dingest.backpressure.adaptive} (true). A malformed value falls back to the default. */
        public static Policy fromSystemProperties() {
            return new Policy(
                    intProperty("ingest.maxFilesPerCycle", 0),
                    Math.max(1, intProperty("ingest.minFilesPerCycle", 1)),
                    !"false".equalsIgnoreCase(System.getProperty("ingest.backpressure.adaptive")));
        }

        private static int intProperty(String key, int fallback) {
            try {
                String v = System.getProperty(key);
                return (v == null || v.isBlank()) ? fallback : Math.max(0, Integer.parseInt(v.trim()));
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        /** Whether admission control applies at all — i.e. a base cap was configured. */
        public boolean active() {
            return baseCap > 0;
        }

        /** The floor, clamped so it can never exceed the base cap. */
        public int effectiveMinCap() {
            return Math.min(Math.max(1, minCap), Math.max(1, baseCap));
        }
    }

    private static volatile IntakeGovernor shared = new IntakeGovernor(Policy.fromSystemProperties());

    /** The process-wide governor shared by the static poll path. */
    public static IntakeGovernor shared() {
        return shared;
    }

    /**
     * Install a governor for tests — the same escape hatch as {@link AcquisitionLedgers#use}, needed because
     * production reads {@link Policy#fromSystemProperties()} once at class init, so a test cannot set the
     * {@code -D} afterwards. Pass {@code null} to restore the system-property policy.
     */
    public static void use(IntakeGovernor governor) {
        shared = (governor != null) ? governor : new IntakeGovernor(Policy.fromSystemProperties());
    }

    private final Policy policy;
    private final Map<String, Integer> caps = new ConcurrentHashMap<>();

    /** Visible for tests; production code uses {@link #shared()}. */
    public IntakeGovernor(Policy policy) {
        this.policy = policy;
    }

    /** The thresholds in force. */
    public Policy policy() {
        return policy;
    }

    /**
     * How many candidates {@code pipelineId} may admit this cycle — {@link #UNBOUNDED} when admission control
     * is off, otherwise the controller's current cap (the base cap until a cycle overruns).
     */
    public int capFor(String pipelineId) {
        if (!policy.active()) return UNBOUNDED;
        return caps.getOrDefault(pipelineId, policy.baseCap());
    }

    /**
     * Feed the controller one cycle's outcome for every pipeline that ran in it, halving each pipeline's cap
     * while the cycle overruns the poll interval and doubling it back (never past the base cap) once the cycle
     * comfortably fits. The two thresholds differ by 2× — that gap <em>is</em> the hysteresis, so a cycle
     * landing near the interval neither halves nor restores and the cap cannot flap.
     *
     * <p>No-op when admission control is off or {@code adaptive=false} (a hard cap), so the hot path stays
     * exactly as it was unless an operator opted in.
     *
     * @param pipelineIds    the pipelines to charge this observation to (the scheduler passes the single
     *                       pipeline whose run was measured; a multi-element list charges them all alike)
     * @param cycleMillis    the measured wall-clock duration
     * @param pollIntervalMs the scheduler's fixed poll delay — the budget one run is expected to fit inside
     */
    public void observeCycle(Iterable<String> pipelineIds, long cycleMillis, long pollIntervalMs) {
        if (!policy.active() || !policy.adaptive() || pollIntervalMs <= 0) return;
        boolean overran = cycleMillis > pollIntervalMs;
        boolean comfortable = cycleMillis * 2 < pollIntervalMs;
        if (!overran && !comfortable) return;                      // inside the hysteresis band — hold
        int min = policy.effectiveMinCap();
        for (String id : pipelineIds) {
            caps.compute(id, (k, current) -> {
                int cap = (current == null) ? policy.baseCap() : current;
                int next = overran ? Math.max(min, cap / 2)
                                   : Math.min(policy.baseCap(), cap * 2);
                return next == cap ? cap : next;
            });
        }
    }

    /** Drop {@code pipelineId}'s cap state when it is unregistered, so the map cannot leak under churn. */
    public void forget(String pipelineId) {
        caps.remove(pipelineId);
    }

    /** Forget all cap state — for test isolation. */
    public void reset() {
        caps.clear();
    }
}
