package com.gamma.job;

import com.gamma.signal.Severity;
import com.gamma.signal.Signals;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code scheduler_audit} maintenance task (MNT-4): read-only hygiene audit of the hosting Job
 * registry. Finding classes: disabled jobs, duplicate job names (last registration wins, hiding the
 * earlier one), identical specs under different names, {@code on_pipeline} triggers naming a
 * pipeline/job the host doesn't know (only when the host wired its pipeline names — never guesses),
 * active pipelines whose authored {@code output_store:} Stage-2 chain no enabled
 * {@code pipeline_config:} job runs (A5-at-rest; only when the host wired its output stores), and
 * {@code on_signal} triggers whose type no registered Job Type declares in {@code emits} and that
 * isn't a framework lifecycle type (reported as "verify", not asserted broken — signal types are
 * open). Cron syntax needs no re-check here: it is validated eagerly at config load. Findings go
 * to the Run Log and, when any exist, one {@code maintenance.scheduler.findings} WARNING signal.
 */
final class SchedulerAuditTask {

    private SchedulerAuditTask() {}

    static JobResult run(JobService host, JobContext ctx) {
        long t0 = System.nanoTime();
        if (host == null) {
            return JobResult.ok("scheduler_audit: no job registry attached — nothing to audit", 0L);
        }
        List<JobConfig> all = host.configSnapshot();
        List<String> findings = new ArrayList<>();
        Map<String, Integer> nameCounts = new LinkedHashMap<>();
        Map<String, List<String>> specs = new LinkedHashMap<>();
        for (JobConfig c : all) {
            if (!c.enabled()) findings.add("disabled job '" + c.name() + "'");
            nameCounts.merge(c.name(), 1, Integer::sum);
            if (c.enabled()) specs.computeIfAbsent(c.type() + "|" + c.cron() + "|" + c.onPipeline()
                    + "|" + c.onSignal() + "|" + c.params(), k -> new ArrayList<>()).add(c.name());
        }
        nameCounts.forEach((n, k) -> {
            if (k > 1) findings.add("duplicate job name '" + n + "' (" + k + " definitions — last wins)");
        });
        for (List<String> names : specs.values()) {
            if (names.size() > 1 && Set.copyOf(names).size() > 1)
                findings.add("duplicate definition: jobs " + names + " share an identical spec");
        }
        Set<String> pipelines = host.knownPipelineNames();   // null = host never wired them — skip, don't guess
        if (pipelines != null) {
            Set<String> jobNames = new HashSet<>();
            for (JobConfig c : all) jobNames.add(c.name().toLowerCase());
            for (JobConfig c : all) {
                if (!c.enabled() || !c.hasEvent()) continue;
                for (String up : c.onPipelines()) {             // on_pipeline may be a comma list
                    String target = up.toLowerCase();           // ConsignmentEvent.pipeline() is lowercased
                    if (!pipelines.contains(target) && !jobNames.contains(target))
                        findings.add("orphan trigger: job '" + c.name() + "' waits on unknown pipeline '"
                                + up + "'");
                }
            }
        }
        // A5-at-rest follow-up: an ACTIVE pipeline that authored a top-level output_store: armed its
        // Stage-2 chain on the promise that a `pipeline_config:` job runs it over the landed store —
        // with no such enabled job the chain quietly never runs (the prepare() silent-skip, deferred).
        Map<String, String> outputStores = host.pipelineOutputStores();   // null = host never wired them — skip, don't guess
        if (outputStores != null) {
            Set<String> shaped = new HashSet<>();
            for (JobConfig c : all) {
                if (!c.enabled()) continue;
                String p = c.params().get("pipeline_config");
                if (p != null && !p.isBlank()) shaped.add(pipelineNameOf(p));
            }
            outputStores.forEach((pipeline, store) -> {
                if (!shaped.contains(pipeline))
                    findings.add("orphan output_store: pipeline '" + pipeline + "' declares output_store '"
                            + store + "' but no enabled pipeline_config job runs its chain");
            });
        }
        Set<String> emitted = new LinkedHashSet<>(List.of("job.run.started", "job.run.completed",
                "job.run.failed", "job.run.rejected", "job.chain.cut", "pipeline.commit"));
        for (JobTypeDescriptor d : host.jobTypes()) emitted.addAll(d.emits());
        for (JobConfig c : all) {
            if (!c.enabled() || !c.hasSignal()) continue;
            boolean anyProducer = emitted.stream().anyMatch(t -> Signals.matchesType(t, c.onSignal()));
            if (!anyProducer)
                findings.add("no declared producer for on_signal '" + c.onSignal() + "' (job '"
                        + c.name() + "') — verify the emitter exists");
        }
        if (ctx != null) {
            for (String f : findings) ctx.log().warn(f);
            if (!findings.isEmpty())
                ctx.signals().emit("maintenance.scheduler.findings", Severity.WARN,
                        Map.of("count", findings.size(), "findings", findings));
        }
        return JobResult.ok("scheduler_audit: " + findings.size() + " finding(s) across " + all.size()
                + " job(s)" + (findings.isEmpty() ? " — healthy" : ""),
                (System.nanoTime() - t0) / 1_000_000L);
    }

    /** The pipeline name a {@code pipeline_config:} path refers to: the file basename, minus the
     *  {@code .toon} extension and the conventional {@code _pipeline} suffix, lowercased. */
    private static String pipelineNameOf(String path) {
        String base = Path.of(path.trim()).getFileName().toString().toLowerCase();
        if (base.endsWith(".toon")) base = base.substring(0, base.length() - ".toon".length());
        if (base.endsWith("_pipeline")) base = base.substring(0, base.length() - "_pipeline".length());
        return base;
    }
}
