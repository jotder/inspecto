package com.gamma.job;

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

    @Test
    void alertEvaluateFailsClosedWithoutTheAlertEngine() {
        // Evaluating nothing would report health that was never checked, so a missing engine is an error
        // rather than a quiet success.
        JobConfig cfg = JobConfig.fromMap(Map.of("job", Map.of("name", "watch", "type", "alert.evaluate")));
        Job job = new AlertEvaluateJob(cfg, () -> null);

        assertEquals("alert.evaluate", job.type());
        var boom = assertThrows(IllegalStateException.class, () -> job.run(null));
        assertTrue(boom.getMessage().contains("Alert engine"));
    }
}
