package com.gamma.job;

import com.gamma.alert.Alert;
import com.gamma.alert.AlertAccess;
import com.gamma.signal.Severity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code la.detect} Job Type - Link Analysis standing detection on a schedule (LA-LIVE-DETECTION-1, LD-4,
 * {@code docs/archived-documents/plans-archive/la-live-detection-design.md}).
 *
 * <p>It is a clock over the evaluation that already exists, and nothing more: it asks the Alert engine to evaluate
 * the Alert Rules bound to an Investigation ({@link AlertAccess#evaluateInvestigationRules()}). Each rule is judged
 * by the Investigation probe exactly as in the full sweep, so the owner binding is checked and a value-measure rule
 * reads the live Dataset only after its standing-detection authority has been RE-DECIDED (the sweep acts as
 * {@code sweep:<investigation>}, holds no capability, and stops - recorded - the moment the owner's access, lead
 * role, an access policy or the masking basis says it must). This Job decides, reads and discloses nothing itself.
 *
 * <p>It reaches the engine only through its declared {@code requires: [alerts]} grant, like {@code alert.evaluate},
 * and fails the Run closed when that service is absent (and when the Link Analysis module is not present, where
 * the engine throws): evaluating nothing must never report health. Dry run evaluates nothing and says so.
 *
 * <p>Aggregate-only output: the completion Signal and the Run message carry counts and Alert Rule names, never an
 * entity id or an Investigation id.
 */
final class LaDetectJob implements Job {

    private final JobConfig cfg;

    LaDetectJob(JobConfig cfg) {
        this.cfg = cfg;
    }

    @Override public String name() { return cfg.name(); }
    @Override public String type() { return "la.detect"; }

    @Override public JobResult run() {
        throw new UnsupportedOperationException("la.detect requires a JobContext");
    }

    @Override
    public JobResult run(JobContext ctx) {
        long t0 = System.nanoTime();
        if (ctx.dryRun()) {
            ctx.log().info("dry run: Investigation Alert Rules were NOT evaluated (evaluation fires alerts and "
                    + "may open Incidents, so it has no preview form)");
            return JobResult.ok("dry run: nothing evaluated - trigger for real to run standing detection",
                    (System.nanoTime() - t0) / 1_000_000L);
        }
        AlertAccess alerts = ctx.services().find(AlertAccess.class)
                .orElseThrow(() -> new IllegalStateException("la.detect needs the 'alerts' Platform "
                        + "Service, which is not available in this build"));

        List<Alert> fired = alerts.evaluateInvestigationRules();
        List<String> names = fired.stream().map(Alert::rule).distinct().toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("job", cfg.name());
        payload.put("fired", fired.size());
        payload.put("rules", names);
        ctx.signals().emit(com.gamma.signal.SignalType.LA_DETECT_COMPLETED, fired.isEmpty() ? Severity.INFO : Severity.WARN, payload);
        ctx.log().info("investigation alert rules evaluated", "fired", fired.size(), "rules", names);
        String msg = fired.isEmpty()
                ? "la.detect: no Investigation rule breached"
                : "la.detect: " + fired.size() + " breach(es) - " + String.join(", ", names);
        return JobResult.ok(msg, (System.nanoTime() - t0) / 1_000_000L);
    }
}
