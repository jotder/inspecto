package com.gamma.job;

import com.gamma.notify.Notification;
import com.gamma.notify.NotificationAccess;
import com.gamma.signal.Severity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code sample.hello} Job Type — a deliberately inert reference Job. It does no work: it accepts a
 * parameter of every shape the declaration contract can express (job-parameter-contract §7.2), echoes what
 * it resolved into its Run Log, and emits one notification carrying those values.
 *
 * <p>Its purpose is to be <em>read</em>. An author wiring up their first Job can see, in one place, how a
 * declared {@code tier}/{@code group}/{@code options}/{@code multi}/{@code secret} parameter renders, and
 * can type a {@code $}-Expression into any field and watch it resolve at fire time — the notification body
 * shows the resolved values, so {@code $today} and friends are observable rather than theoretical.
 *
 * <p>It is also the reference for <b>using a Platform Service</b> (platform-services S1-7): its Job Type
 * declares {@code requires: [notifications]} and it reaches the feed only through
 * {@link JobContext#services()} — no privileged engine object is injected into it, which is precisely what
 * lets a Job like this ship from a pack jar. An absent grant is not fatal: the Run still succeeds, so the
 * sample stays safe wherever it is dropped.
 *
 * <p>Because it changes nothing, it is safe to schedule, trigger by hand, and delete. The one visible
 * effect is the notification, which is also what makes a successful Run obvious in the UI.
 */
final class SampleHelloJob implements Job {

    private final JobConfig cfg;

    SampleHelloJob(JobConfig cfg) {
        this.cfg = cfg;
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

        // The one visible effect, through the granted `notifications` Platform Service. An ungranted
        // build (or a dry run, where the framework substitutes a recording stand-in) simply emits
        // nothing — a sample must not fail closed on its own demonstration.
        boolean emitted = false;
        if (!"false".equalsIgnoreCase(p.getOrDefault("raise_alert", "true"))) {
            emitted = ctx.services().find(NotificationAccess.class)
                    // dedupeKey = the job name, so repeat fires collapse into one unread entry.
                    .flatMap(feed -> feed.notify(Notification.create("job", "JOB_RUN", ctx.runId(), title,
                            "Emitted by the sample.hello Job — no work was performed. Resolved: " + shown,
                            "sample.hello:" + cfg.name())))
                    .isPresent();
        }

        ctx.signals().emit("sample.hello.completed", Severity.INFO,
                Map.of("job", cfg.name(), "run", ctx.runId(), "notified", emitted));
        return JobResult.ok(title + (emitted ? " (notification emitted)" : " (no notification emitted)"),
                (System.nanoTime() - t0) / 1_000_000L);
    }
}
