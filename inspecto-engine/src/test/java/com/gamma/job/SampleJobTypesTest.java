package com.gamma.job;

import com.gamma.util.RunLog;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The two sample Job Types shipped as learning material: {@code sample.hello}'s declaration is a tour of
 *  the parameter contract, and {@code alert.evaluate} deliberately owns no detection logic of its own. */
class SampleJobTypesTest {

    private static ParameterDecl decl(JobTypeDescriptor d, String name) {
        return d.parameters().stream().filter(p -> p.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no '" + name + "' parameter"));
    }

    /** The descriptor exactly as {@code JobService.registerBuiltins} declares it, without booting a service. */
    private static JobTypeDescriptor helloDescriptor() {
        return new JobTypeDescriptor("sample.hello", "Hello World (sample)", "Does no work.",
                List.of(
                        ParameterDecl.of("greeting", ParamType.STRING).label("Greeting").group("Message")
                                .defaultValue("Hello").placeholder("Hello").build(),
                        ParameterDecl.of("run_date", ParamType.DATE).deduce("$today").build(),
                        ParameterDecl.of("severity", ParamType.STRING).group("Alert")
                                .options("INFO", "WARNING", "ERROR").defaultValue("INFO").build(),
                        ParameterDecl.of("notify", ParamType.EMAIL).group("Alert").multi().build(),
                        ParameterDecl.of("retries", ParamType.INTEGER).tier(ParameterDecl.Tier.ADVANCED)
                                .min(0).max(5).defaultValue("0").build(),
                        ParameterDecl.of("api_token", ParamType.STRING).tier(ParameterDecl.Tier.ADVANCED)
                                .secret().build()));
    }

    @Test
    void theHelloSampleDeclaresEveryShapeOfTheContract() {
        JobTypeDescriptor d = helloDescriptor();

        assertEquals("Message", decl(d, "greeting").group(), "grouped fields drive form sections");
        assertEquals(List.of("INFO", "WARNING", "ERROR"), decl(d, "severity").options(), "renders a select");
        assertTrue(decl(d, "notify").multi(), "renders list chips, validated per address");
        assertEquals(ParameterDecl.Tier.ADVANCED, decl(d, "retries").tier(), "tucked behind disclosure");
        assertTrue(decl(d, "api_token").secret(), "masked on read");
        assertEquals("$today", decl(d, "run_date").deduce(), "unset ⇒ the fire date");
        assertTrue(decl(d, "greeting").expressions(), "an author may type a $-token into any field");
    }

    @Test
    void theSamplesAuthoredValuesResolveAgainstTheContract() {
        // What the shipped spaces/demo job file will actually do at fire time: $-values resolve, the
        // multi email list validates per item, and the bounded/choice fields are enforced.
        var ctx = new ExpressionContext("run-1", java.time.Instant.parse("2026-08-07T09:00:00Z"), "cron",
                java.time.ZoneOffset.UTC, java.util.Optional::empty,
                (j, n) -> java.util.Optional.empty(), Map.of());
        Map<String, String> authored = Map.of(
                "greeting", "Good hour", "audience", "$run.actor", "severity", "INFO",
                "notify", "ops@example.com, oncall@example.com", "retries", "0");

        var r = ParameterResolver.resolve(helloDescriptor().parameters(), Map.of(), Map.of(), authored,
                ExpressionRegistry.withBuiltins(), ctx);

        assertTrue(r.invalidType().isEmpty(), () -> "unexpected violations: " + r.invalidType());
        assertTrue(r.unknownExpression().isEmpty());
        assertEquals("2026-08-07", r.resolved().get("run_date"), "deduced from $today");
        assertEquals("Good hour", r.resolved().get("greeting"));
    }

    @Test
    void aBadSampleValueIsRejectedBeforeTheJobRuns() {
        var ctx = new ExpressionContext("run-1", java.time.Instant.EPOCH, "cron", java.time.ZoneOffset.UTC,
                java.util.Optional::empty, (j, n) -> java.util.Optional.empty(), Map.of());

        var r = ParameterResolver.resolve(helloDescriptor().parameters(), Map.of(), Map.of(),
                Map.of("severity", "LOUD", "retries", "9", "notify", "not-an-address"),
                ExpressionRegistry.withBuiltins(), ctx);

        assertEquals(3, r.invalidType().size(), "options, bounds and per-item email all enforced");
    }

    // ── alert.evaluate: owns no detection of its own, reaches the evaluator only through its grant ──

    private static Job alertEvaluate() {
        return new AlertEvaluateJob(
                JobConfig.fromMap(Map.of("job", Map.of("name", "watch", "type", "alert.evaluate"))));
    }

    private static com.gamma.alert.Alert breach(String rule) {
        return new com.gamma.alert.Alert(rule, "error", "orders", "failed_batches", 5, ">", 3, "1h",
                0L, rule + " breached");
    }

    @Test
    void alertEvaluateFailsClosedWithoutTheAlertsService() {
        // Evaluating nothing would report health that was never checked, so an ungranted/absent service
        // is an error rather than a quiet success (D8: a host-less registry leaves the grant empty).
        StubContext ctx = new StubContext(false, PlatformServices.none());

        var boom = assertThrows(IllegalStateException.class, () -> alertEvaluate().run(ctx));

        assertTrue(boom.getMessage().contains("alerts"), boom.getMessage());
    }

    @Test
    void alertEvaluateReportsWhatBreachedThroughItsGrant() throws Exception {
        StubContext ctx = new StubContext(false, grantOf(() -> List.of(breach("error_rate"))));

        JobResult r = alertEvaluate().run(ctx);

        assertTrue(r.success());
        assertTrue(r.message().contains("error_rate"), r.message());
        assertEquals(1, ctx.signals.size(), "one completion Signal");
    }

    /** MNT-1: evaluation IS the action (it fires Alerts and opens Incidents), so it has no preview form. */
    @Test
    void alertEvaluateDoesNotEvaluateUnderADryRun() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        StubContext ctx = new StubContext(true, grantOf(() -> {
            calls.incrementAndGet();
            return List.of(breach("error_rate"));
        }));

        JobResult r = alertEvaluate().run(ctx);

        assertTrue(r.success());
        assertEquals(0, calls.get(), "a dry run must not evaluate");
        assertTrue(r.message().contains("nothing evaluated"), r.message());
        assertTrue(ctx.log.stream().anyMatch(l -> l.contains("NOT evaluated")), ctx.log.toString());
    }

    private static PlatformServices grantOf(com.gamma.alert.AlertAccess alerts) {
        PlatformServiceRegistry registry = new PlatformServiceRegistry();
        registry.register("alerts", com.gamma.alert.AlertAccess.class, alerts);
        return registry.grant(java.util.Set.of("alerts"));
    }

    /** The narrowest {@link JobContext} these three cases need: a grant, a dry-run flag, and capture. */
    private static final class StubContext implements JobContext {
        private final boolean dryRun;
        private final PlatformServices services;
        final List<String> log = new java.util.ArrayList<>();
        final List<String> signals = new java.util.ArrayList<>();

        StubContext(boolean dryRun, PlatformServices services) {
            this.dryRun = dryRun;
            this.services = services;
        }

        @Override public String runId()                 { return "run-1"; }
        @Override public String spaceId()               { return "default"; }
        @Override public TriggerInfo trigger()          { return TriggerInfo.parse("manual"); }
        @Override public Map<String, String> config()   { return Map.of(); }
        @Override public Map<String, String> params()   { return Map.of(); }
        @Override public boolean dryRun()               { return dryRun; }
        @Override public PlatformServices services()    { return services; }
        @Override public com.gamma.signal.SignalEmitter signals() {
            return (type, severity, payload) -> signals.add(type);
        }
        @Override public ArtifactRecorder artifacts() {
            throw new UnsupportedOperationException("alert.evaluate records no artifacts");
        }
        @Override public RunLog log() {
            return new RunLog() {
                @Override public void info(String m, Object... kv)  { log.add(m); }
                @Override public void warn(String m, Object... kv)  { log.add(m); }
                @Override public void error(String m, Throwable t, Object... kv) { log.add(m); }
            };
        }
    }
}
