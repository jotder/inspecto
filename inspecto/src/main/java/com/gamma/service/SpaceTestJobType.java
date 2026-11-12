package com.gamma.service;

import com.gamma.job.Job;
import com.gamma.job.JobConfig;
import com.gamma.job.JobContext;
import com.gamma.job.JobResult;
import com.gamma.job.JobTypeDescriptor;
import com.gamma.job.JobTypeProvider;
import com.gamma.job.ParamType;
import com.gamma.job.ParameterDecl;
import com.gamma.pipeline.SpaceConfigRoot;
import com.gamma.signal.Severity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code space.test} (SE-6a) — runs the test cases of its Space ({@link SpaceTestRunner}) on a clock. Pipelines are
 * read from the Space's own files without writing; each case runs into scratch storage. A failed case emits
 * {@value #FAILED} (WARN); every run emits {@value #COMPLETED}. A dry run runs the cases too: running them writes
 * nothing outside a scratch root that is deleted afterwards.
 *
 * <p>Contributed through the {@code ServiceLoader} loop in {@code JobService}, like {@code expectation.evaluate}.
 */
public final class SpaceTestJobType implements JobTypeProvider {

    public static final String TYPE = "space.test";
    public static final String COMPLETED = "space.test.completed";
    public static final String FAILED = "space.test.failed";

    @Override
    public JobTypeDescriptor descriptor() {
        return new JobTypeDescriptor(TYPE, "Run Space tests",
                "Runs this Space's test cases (spaces/<id>/tests/) over their sample files, in scratch storage. "
                        + "A failing case raises a warning Signal.",
                List.of(ParameterDecl.of("cases", ParamType.STRING).label("Only these cases")
                        .description("Comma-separated case names; every case when unset").build()),
                List.of(COMPLETED, FAILED), List.of(), List.of());
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
            Path configRoot = SpaceConfigRoot.requireCurrent(TYPE);
            Path tests = SpaceTestRunner.testsDir(configRoot);
            String only = ctx.params().get("cases");
            List<String> names = only == null || only.isBlank()
                    ? SpaceTestRunner.list(tests)
                    : Arrays.stream(only.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
            var pipelines = SpaceTestRunner.pipelinesUnder(configRoot);
            List<String> failed = new ArrayList<>();
            for (String n : names) {
                SpaceTestRunner.CaseResult r;
                try {
                    r = SpaceTestRunner.run(SpaceTestRunner.load(tests, n), pipelines);
                } catch (RuntimeException e) {
                    r = new SpaceTestRunner.CaseResult(n, null, false, e.getMessage(), List.of());
                }
                if (r.passed()) {
                    ctx.log().info("case passed", "case", n);
                } else {
                    failed.add(n);
                    ctx.log().warn("case failed: " + n + (r.error() == null ? "" : " - " + r.error()));
                    for (SpaceTestRunner.Line l : r.lines())
                        if (!l.passed())
                            ctx.log().warn("  " + l.name() + ": expected " + l.expected() + ", got " + l.actual());
                }
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("job", cfg.name());
            payload.put("total", names.size());
            payload.put("failed", failed.size());
            payload.put("cases", failed);
            if (!failed.isEmpty()) ctx.signals().emit(FAILED, Severity.WARN, payload);
            ctx.signals().emit(COMPLETED, failed.isEmpty() ? Severity.INFO : Severity.WARN, payload);
            String msg = failed.isEmpty()
                    ? TYPE + ": " + names.size() + " case(s), all passed"
                    : TYPE + ": " + failed.size() + " of " + names.size() + " failed - " + String.join(", ", failed);
            return JobResult.ok(msg, (System.nanoTime() - t0) / 1_000_000L);
        }
    }
}
