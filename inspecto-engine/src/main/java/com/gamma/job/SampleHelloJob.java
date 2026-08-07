package com.gamma.job;

import com.gamma.ops.ObjectService;
import com.gamma.ops.ObjectType;
import com.gamma.signal.Severity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The {@code sample.hello} Job Type — a deliberately inert reference Job. It does no work: it accepts a
 * parameter of every shape the declaration contract can express (job-parameter-contract §7.2), echoes what
 * it resolved into its Run Log, and opens one {@link ObjectType#ALERT} carrying those values.
 *
 * <p>Its purpose is to be <em>read</em>. An author wiring up their first Job can see, in one place, how a
 * declared {@code tier}/{@code group}/{@code options}/{@code multi}/{@code secret} parameter renders, and
 * can type a {@code $}-Expression into any field and watch it resolve at fire time — the Alert's attributes
 * show the resolved values, so {@code $today} and friends are observable rather than theoretical.
 *
 * <p>Because it changes nothing, it is safe to schedule, trigger by hand, and delete. The one visible
 * effect is the Alert, which is also what makes a successful Run obvious in the UI.
 */
final class SampleHelloJob implements Job {

    private final JobConfig cfg;
    /** Live view of this space's Object Engine (wired post-construction); {@code null} ⇒ no Alert, and the
     *  Run still succeeds — a sample must not fail closed on an optional dependency. */
    private final Supplier<ObjectService> objects;

    SampleHelloJob(JobConfig cfg, Supplier<ObjectService> objects) {
        this.cfg = cfg;
        this.objects = objects;
    }

    @Override public String name() { return cfg.name(); }
    @Override public String type() { return "sample.hello"; }

    @Override public JobResult run() {
        throw new UnsupportedOperationException("sample.hello requires a JobContext");
    }

    @Override
    public JobResult run(JobContext ctx) {
        long t0 = System.nanoTime();
        Map<String, String> p = ctx.params();          // resolved: literals AND evaluated $-Expressions

        // Echo every resolved parameter — the point of the sample is seeing what resolution produced.
        // `secret` params are masked on API reads, so don't defeat that by logging their values here.
        Map<String, Object> shown = new LinkedHashMap<>();
        p.forEach((k, v) -> shown.put(k, "api_token".equals(k) ? "***" : v));
        ctx.log().info("hello — nothing to do", "params", shown);

        String greeting = p.getOrDefault("greeting", "Hello");
        String audience = p.getOrDefault("audience", "world");
        String title = greeting + ", " + audience + "!";

        boolean raised = false;
        ObjectService svc = objects == null ? null : objects.get();
        if (svc != null && !"false".equalsIgnoreCase(p.getOrDefault("raise_alert", "true"))) {
            Map<String, String> attrs = new LinkedHashMap<>();
            shown.forEach((k, v) -> attrs.put(k, String.valueOf(v)));
            attrs.put("job", cfg.name());
            attrs.put("run", ctx.runId());
            // correlationId = the job name, so repeat fires group under one subject in the Alerts feed.
            svc.open(ObjectType.ALERT, title, "Raised by the sample.hello Job — no work was performed.",
                    p.getOrDefault("severity", "INFO"), cfg.name(), attrs);
            raised = true;
        }

        ctx.signals().emit("sample.hello.completed", Severity.INFO,
                Map.of("job", cfg.name(), "run", ctx.runId(), "alert", raised));
        return JobResult.ok(title + (raised ? " (alert raised)" : " (no Object Engine — alert skipped)"),
                (System.nanoTime() - t0) / 1_000_000L);
    }
}
