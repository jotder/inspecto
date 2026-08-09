package com.gamma.job;

import com.gamma.notify.Notification;
import com.gamma.notify.NotificationAccess;
import com.gamma.ops.IncidentAccess;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The harness a scaffolded pack tests against (S1-8). What is pinned here is that it is not a
 * convenience mock: it applies the same registration-time {@code requires:} check, the same
 * Parameter resolution and the same grant filtering as a real Run, so a pack that is green here is
 * a pack the engine will accept.
 */
class PackTestHarnessTest {

    // ── fixtures: providers that behave like a pack's would ────────────────────

    /** Declares {@code requires: [notifications]} and reaches the feed only through the grant. */
    private static JobTypeProvider notifier(String id) {
        return provider(id, List.of("notifications"), (cfg, ctx) -> {
            ctx.log().info("notifier ran", "subject", ctx.params().get("subject"));
            boolean sent = ctx.services().get(NotificationAccess.class)
                    .notify(Notification.create("job", "JOB_RUN", ctx.runId(), "hi",
                            "from the pack", "pack:" + cfg.name()))
                    .isPresent();
            return JobResult.ok(sent ? "sent" : "collapsed", 0L);
        });
    }

    private static JobTypeProvider provider(String id, List<String> requires,
                                            java.util.function.BiFunction<JobConfig, JobContext, JobResult> body) {
        return JobTypeProvider.of(
                new JobTypeDescriptor(id, id, "harness fixture",
                        List.of(ParameterDecl.of("subject", ParamType.STRING).defaultValue("world").build()),
                        List.of(), List.of(), requires),
                cfg -> new Job() {
                    @Override public String name() { return cfg.name(); }
                    @Override public String type() { return id; }
                    @Override public JobResult run() { throw new UnsupportedOperationException(); }
                    @Override public JobResult run(JobContext ctx) { return body.apply(cfg, ctx); }
                });
    }

    // ── the grant ─────────────────────────────────────────────────────────────

    @Test
    void firesAJobThroughItsDeclaredGrant() {
        PackTestHarness harness = PackTestHarness.create().load(notifier("acme.notify"));

        PackTestHarness.Outcome run = harness.run("acme.notify", Map.of("subject", "harness"));

        assertEquals("SUCCESS", run.status(), run.message());
        assertEquals(1, run.notifications().size());
        assertEquals("hi", run.notifications().get(0).title());
        assertTrue(run.granted().contains(NotificationAccess.class));
        assertTrue(run.logged("notifier ran"), run.log().toString());
    }

    @Test
    void appliesTheDeclaredDefaultBeforeTheJobRuns() {
        PackTestHarness harness = PackTestHarness.create().load(notifier("acme.notify"));

        assertEquals("world", harness.run("acme.notify", Map.of()).params().get("subject"));
    }

    /** R4: a service that exists but was not declared stays invisible — the whole security story. */
    @Test
    void anUndeclaredServiceIsInvisibleEvenThoughTheHarnessHasIt() {
        JobTypeProvider sneaky = provider("acme.sneaky", List.of(),
                (cfg, ctx) -> JobResult.ok(ctx.services().find(NotificationAccess.class).isPresent()
                        ? "visible" : "invisible", 0L));
        PackTestHarness harness = PackTestHarness.create().load(sneaky);

        PackTestHarness.Outcome run = harness.run("acme.sneaky", Map.of());

        assertEquals("invisible", run.message());
        assertTrue(run.granted().isEmpty());
    }

    /** Fail-closed at registration, not at fire time — the harness must not be softer than the engine. */
    @Test
    void anUnknownServiceIdRefusesTheTypeAtLoad() {
        PackTestHarness harness = PackTestHarness.create();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> harness.load(provider("acme.typo", List.of("notifcations"), (c, x) -> JobResult.ok("", 0L))));

        assertTrue(e.getMessage().contains("notifcations"), e.getMessage());
    }

    @Test
    void anExtraServiceMustBeBoundBeforeTheProvidersLoad() {
        PackTestHarness harness = PackTestHarness.create().load(notifier("acme.notify"));

        assertThrows(IllegalStateException.class,
                () -> harness.service("schema", CharSequence.class, "not-really-a-schema"));
    }

    // ── contracts the recording stand-ins honour ──────────────────────────────

    @Test
    void theFeedCollapsesARepeatOfTheSameDedupeKey() {
        JobTypeProvider twice = provider("acme.twice", List.of("notifications"), (cfg, ctx) -> {
            NotificationAccess feed = ctx.services().get(NotificationAccess.class);
            feed.notify(Notification.create("job", "JOB_RUN", ctx.runId(), "a", "b", "same"));
            boolean second = feed.notify(
                    Notification.create("job", "JOB_RUN", ctx.runId(), "a", "b", "same")).isPresent();
            return JobResult.ok(second ? "stored twice" : "collapsed", 0L);
        });

        PackTestHarness.Outcome run = PackTestHarness.create().load(twice).run("acme.twice", Map.of());

        assertEquals("collapsed", run.message());
        assertEquals(1, run.notifications().size());
    }

    @Test
    void aSecondIncidentForTheSameScopeAndKeyIsSuppressed() {
        JobTypeProvider twice = provider("acme.incident", List.of("incidents"), (cfg, ctx) -> {
            IncidentAccess incidents = ctx.services().get(IncidentAccess.class);
            incidents.openIncident("t", "m", "ERROR", "batch-1", Map.of("rule", "r1"), "rule");
            boolean second = incidents.openIncident("t", "m", "ERROR", "batch-1", Map.of("rule", "r1"), "rule")
                    .isPresent();
            return JobResult.ok(second ? "opened twice" : "suppressed", 0L);
        });

        PackTestHarness.Outcome run = PackTestHarness.create().load(twice).run("acme.incident", Map.of());

        assertEquals("suppressed", run.message());
        assertEquals(1, run.incidents().size());
        assertEquals("batch-1", run.incidents().get(0).scope());
    }

    // ── the dry run ───────────────────────────────────────────────────────────

    @Test
    void aDryRunRecordsInsteadOfActing() {
        PackTestHarness harness = PackTestHarness.create().load(notifier("acme.notify"));

        PackTestHarness.Outcome run = harness.dryRun("acme.notify", Map.of());

        assertEquals("SUCCESS", run.status(), run.message());
        assertTrue(run.notifications().isEmpty(), "a dry run stores nothing");
        assertTrue(run.logged("dry run: would emit notification"), run.log().toString());
    }

    // ── the pre-flight ────────────────────────────────────────────────────────

    @Test
    void aMissingRequiredParameterRejectsTheRunBeforeUserCode() {
        JobTypeProvider needsIt = JobTypeProvider.of(
                new JobTypeDescriptor("acme.needy", "Needy", "requires a parameter",
                        List.of(ParameterDecl.of("target", ParamType.STRING).required().build()),
                        List.of(), List.of(), List.of()),
                cfg -> new Job() {
                    @Override public String name() { return cfg.name(); }
                    @Override public String type() { return "acme.needy"; }
                    @Override public JobResult run() { throw new AssertionError("must not run"); }
                    @Override public JobResult run(JobContext ctx) { throw new AssertionError("must not run"); }
                });

        PackTestHarness.Outcome run = PackTestHarness.create().load(needsIt).run("acme.needy", Map.of());

        assertEquals("REJECTED", run.status());
        assertTrue(run.message().contains("target"), run.message());
    }

    @Test
    void aThrownExceptionBecomesAFailedRun() {
        JobTypeProvider boom = provider("acme.boom", List.of(), (cfg, ctx) -> {
            throw new IllegalStateException("kaboom");
        });

        PackTestHarness.Outcome run = PackTestHarness.create().load(boom).run("acme.boom", Map.of());

        assertEquals("FAILED", run.status());
        assertTrue(run.message().contains("kaboom"), run.message());
    }
}
