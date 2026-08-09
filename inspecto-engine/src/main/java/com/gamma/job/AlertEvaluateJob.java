package com.gamma.job;

import com.gamma.alert.Alert;
import com.gamma.alert.AlertAccess;
import com.gamma.signal.Severity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code alert.evaluate} Job Type — runs this space's authored Alert Rules on a schedule.
 *
 * <p><b>Why this exists.</b> The Alert engine already detects "N failed batches for pipeline X within the
 * last hour" declaratively ({@code metric: failed_batches}, {@code window: 1h}, {@code onPipeline:}) and
 * already opens an ALERT — auto-promoting an {@code error}/{@code critical} rule to a managed INCIDENT,
 * deduped so one breach is not raised twice ({@code AlertService.persistAlertObject}/{@code promoteToIncident}).
 * What it lacked was a clock: evaluation was reachable only from {@code POST /alerts/evaluate}, so
 * detection ran when somebody asked, never on a cron.
 *
 * <p>So this Job adds the missing schedule and nothing else — it computes no thresholds and opens no objects
 * of its own. That is deliberate (job-parameter-contract §0, versatility over built-ins): a bespoke
 * "count file failures" Job Type would have duplicated the window arithmetic, the severity mapping and the
 * dedup that the Alert engine already owns, and would have drifted from them.
 *
 * <p>It reaches the evaluator only through its declared {@code requires: [alerts]} grant
 * ({@link AlertAccess}) — no privileged engine object is injected into it, so it is pack-shippable like
 * {@code sample.hello}. An absent service fails the Run closed: evaluation is the whole work, and
 * silently succeeding would report health that was never checked.
 *
 * <p>Optional {@code rule} narrows a fire to one rule by name; absent, every rule evaluates.
 */
final class AlertEvaluateJob implements Job {

    private final JobConfig cfg;

    AlertEvaluateJob(JobConfig cfg) {
        this.cfg = cfg;
    }

    @Override public String name() { return cfg.name(); }
    @Override public String type() { return "alert.evaluate"; }

    @Override public JobResult run() {
        throw new UnsupportedOperationException("alert.evaluate requires a JobContext");
    }

    @Override
    public JobResult run(JobContext ctx) {
        long t0 = System.nanoTime();

        // MNT-1: this Job cannot preview, so it does nothing and says so. Evaluating IS the action —
        // a breach fires an Alert, advances its cooldown and may open an Incident — and reporting the
        // dry run's empty result as "no rule breached" would claim health nobody checked.
        if (ctx.dryRun()) {
            ctx.log().info("dry run: Alert Rules were NOT evaluated (evaluation fires alerts and "
                    + "opens Incidents, so it has no preview form)");
            return JobResult.ok("dry run: nothing evaluated — trigger for real to check the rules",
                    (System.nanoTime() - t0) / 1_000_000L);
        }

        AlertAccess alerts = ctx.services().find(AlertAccess.class)
                .orElseThrow(() -> new IllegalStateException("alert.evaluate needs the 'alerts' Platform "
                        + "Service, which is not available in this build"));

        String only = ctx.params().get("rule");
        List<Alert> fired = alerts.evaluateRules();
        if (only != null && !only.isBlank())
            fired = fired.stream().filter(a -> only.equalsIgnoreCase(a.rule())).toList();

        List<String> names = fired.stream().map(Alert::rule).distinct().toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("job", cfg.name());
        payload.put("fired", fired.size());
        payload.put("rules", names);
        if (only != null && !only.isBlank()) payload.put("scopedTo", only);
        // WARN when something breached, so the Run itself is visible in the feed — the Alert/Incident
        // objects are opened by the Alert engine, not here.
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
