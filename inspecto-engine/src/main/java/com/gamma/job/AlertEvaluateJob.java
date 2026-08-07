package com.gamma.job;

import com.gamma.alert.AlertService;
import com.gamma.signal.Severity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The {@code alert.evaluate} Job Type — runs this space's authored Alert Rules on a schedule.
 *
 * <p><b>Why this exists.</b> The Alert engine already detects "N failed batches for pipeline X within the
 * last hour" declaratively ({@code metric: failed_batches}, {@code window: 1h}, {@code onPipeline:}) and
 * already opens an ALERT — auto-promoting an {@code error}/{@code critical} rule to a managed INCIDENT,
 * deduped so one breach is not raised twice ({@code AlertService.persistAlertObject}/{@code promoteToIncident}).
 * What it lacked was a clock: {@code evaluateAll()} was reachable only from {@code POST /alerts/evaluate},
 * so detection ran when somebody asked, never on a cron.
 *
 * <p>So this Job adds the missing schedule and nothing else — it computes no thresholds and opens no objects
 * of its own. That is deliberate (job-parameter-contract §0, versatility over built-ins): a bespoke
 * "count file failures" Job Type would have duplicated the window arithmetic, the severity mapping and the
 * dedup that {@link AlertService} already owns, and would have drifted from them.
 *
 * <p>Optional {@code rule} narrows a fire to one rule by name; absent, every rule evaluates. A missing
 * {@link AlertService} fails the Run closed — evaluation is the whole work, so silently succeeding would
 * report health that was never checked.
 */
final class AlertEvaluateJob implements Job {

    private final JobConfig cfg;
    private final Supplier<AlertService> alerts;

    AlertEvaluateJob(JobConfig cfg, Supplier<AlertService> alerts) {
        this.cfg = cfg;
        this.alerts = alerts;
    }

    @Override public String name() { return cfg.name(); }
    @Override public String type() { return "alert.evaluate"; }

    @Override public JobResult run() {
        throw new UnsupportedOperationException("alert.evaluate requires a JobContext");
    }

    @Override
    public JobResult run(JobContext ctx) {
        long t0 = System.nanoTime();
        AlertService svc = alerts == null ? null : alerts.get();
        if (svc == null)
            throw new IllegalStateException(
                    "alert.evaluate needs the space Alert engine (JobService.alerts not wired)");

        String only = ctx.params().get("rule");
        List<Map<String, Object>> fired = svc.evaluateAll();
        if (only != null && !only.isBlank())
            fired = fired.stream().filter(f -> only.equalsIgnoreCase(String.valueOf(f.get("rule")))).toList();

        List<String> names = fired.stream().map(f -> String.valueOf(f.get("rule"))).distinct().toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("job", cfg.name());
        payload.put("fired", fired.size());
        payload.put("rules", names);
        if (only != null && !only.isBlank()) payload.put("scopedTo", only);
        // WARN when something breached, so the Run itself is visible in the feed — the Alert/Incident
        // objects are opened by AlertService, not here.
        ctx.signals().emit("alert.evaluate.completed",
                fired.isEmpty() ? Severity.INFO : Severity.WARN, payload);
        ctx.log().info("alert rules evaluated", "fired", fired.size(), "rules", names);

        String msg = fired.isEmpty()
                ? "alert.evaluate: no rule breached"
                : "alert.evaluate: " + fired.size() + " breach(es) — " + String.join(", ", names)
                        + " (error/critical rules are promoted to Incidents)";
        return JobResult.ok(msg, (System.nanoTime() - t0) / 1_000_000L);
    }
}
