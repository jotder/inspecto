package com.gamma.expectation;

import com.gamma.job.Job;
import com.gamma.job.JobConfig;
import com.gamma.job.JobContext;
import com.gamma.job.JobResult;
import com.gamma.job.JobTypeDescriptor;
import com.gamma.job.JobTypeProvider;
import com.gamma.job.ParamType;
import com.gamma.job.ParameterDecl;
import com.gamma.objects.ObjectAccess;
import com.gamma.pipeline.SpaceConfigRoot;
import com.gamma.signal.Severity;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code expectation.evaluate} - puts the Expectations on a clock. Runs the very evaluation behind
 * {@code POST /expectations/evaluate} ({@link ExpectationRunner}): every enabled Expectation is checked against
 * its target's at-rest data, {@code lastResult} is persisted, and a failure opens a correlated Incident (deduped
 * while one is open). Without this Job an Expectation only ran when somebody asked.
 *
 * <p>Contributed through the {@code ServiceLoader} loop in {@code JobService}. Resolves its Space's config root
 * and data root at run time, like {@code anomaly.score}. The Incident seam is the optional {@code objects}
 * grant: absent, a breach is still evented but not promoted. A dry run does nothing and says so - evaluating
 * persists results and opens Incidents, so it has no preview form.
 */
public final class ExpectationEvaluateJobType implements JobTypeProvider {

    public static final String TYPE = "expectation.evaluate";
    public static final String COMPLETED = "expectation.evaluate.completed";

    @Override
    public JobTypeDescriptor descriptor() {
        return new JobTypeDescriptor(TYPE, "Evaluate Expectations",
                "Evaluates this space's Expectations on a schedule. A failing Expectation records its result and "
                        + "opens an Incident (one at a time per Expectation).",
                List.of(ParameterDecl.of("expectation", ParamType.STRING).label("Only this Expectation")
                        .description("Evaluate a single Expectation by name; every enabled one when unset").build()),
                List.of(COMPLETED), List.of(), List.of("objects"));
    }

    @Override
    public Job create(JobConfig config) {
        return new Run(config);
    }

    private record Run(JobConfig cfg) implements Job {

        @Override public String name() { return cfg.name(); }
        @Override public String type() { return TYPE; }

        @Override public JobResult run() {
            throw new UnsupportedOperationException(TYPE + " requires a JobContext");
        }

        @Override
        public JobResult run(JobContext ctx) throws Exception {
            long t0 = System.nanoTime();
            if (ctx.dryRun()) {
                ctx.log().info("dry run: Expectations were NOT evaluated (evaluation records results and opens "
                        + "Incidents, so it has no preview form)");
                return JobResult.ok("dry run: nothing evaluated - trigger for real to check the Expectations",
                        (System.nanoTime() - t0) / 1_000_000L);
            }
            Path writeRoot = SpaceConfigRoot.requireCurrent(TYPE);
            Path data = SpaceConfigRoot.currentDataRoot();
            if (data == null) throw new IllegalStateException(TYPE + " needs a data root (space dataDir)");
            ObjectAccess objects = ctx.services().find(ObjectAccess.class).orElse(null);
            ExpectationRunner runner = new ExpectationRunner(writeRoot, data, objects, m -> m);

            String only = ctx.params().get("expectation");
            List<Map<String, Object>> results = only == null || only.isBlank()
                    ? runner.evaluateAll()
                    : List.of(runner.evaluateOne(new com.gamma.pipeline.ComponentStore(writeRoot.resolve("registry"))
                            .get(ExpectationRunner.TYPE, only.trim())
                            .orElseThrow(() -> new IllegalArgumentException("unknown expectation '" + only.trim() + "'"))
                            .content()));

            List<String> failed = results.stream()
                    .filter(r -> r.get("lastResult") instanceof Map<?, ?> l && "FAILED".equals(l.get("status")))
                    .map(r -> String.valueOf(r.get("name"))).toList();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("job", cfg.name());
            payload.put("evaluated", results.size());
            payload.put("failed", failed.size());
            payload.put("expectations", failed);
            ctx.signals().emit(COMPLETED, failed.isEmpty() ? Severity.INFO : Severity.WARN, payload);
            ctx.log().info("expectations evaluated", "evaluated", results.size(), "failed", failed);

            String msg = failed.isEmpty()
                    ? TYPE + ": " + results.size() + " evaluated, none failed"
                    : TYPE + ": " + failed.size() + " of " + results.size() + " failed - " + String.join(", ", failed);
            return JobResult.ok(msg, (System.nanoTime() - t0) / 1_000_000L);
        }
    }
}
