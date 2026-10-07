package com.gamma.job;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** The Expression SPI seam (job-parameter-contract §4): the built-in vocabulary is discoverable, a plugin
 *  can contribute a token, and a colliding token fails the provider's registration loudly. */
class ExpressionRegistryTest {

    private static ExpressionContext ctx() {
        return new ExpressionContext("run-1", Instant.parse("2026-07-08T06:00:00Z"), "cron", ZoneOffset.UTC,
                Optional::empty, (job, name) -> Optional.empty(), Map.of());
    }

    /** A provider contributing one literal token — the shape a Job Pack or optional module ships. */
    private record OneToken(String token, String value) implements ExpressionProvider {
        @Override public List<ExpressionDecl> declarations() {
            return List.of(ExpressionDecl.literal(token, ParamType.STRING, "a plugin token", value));
        }
        @Override public Optional<String> evaluate(String expr, ExpressionContext ctx) {
            return Optional.of(value);
        }
    }

    @Test
    void theBuiltInVocabularyIsDeclaredAndDiscoverable() {
        List<String> tokens = ExpressionRegistry.withBuiltins().declarations().stream()
                .map(ExpressionDecl::token).toList();

        // §2's table, exactly — the catalog is generated from the evaluator, never hand-maintained.
        assertEquals(List.of("$today", "$yesterday", "$tomorrow", "$now", "$now.epoch_seconds",
                "$now.epoch_millis", "$run.id", "$run.fire_time", "$run.actor", "$job.last_success_time",
                "$signal.", "$day(n)", "$month(n)", "$year(n)",
                "$upstream(<job>).artifact(<name>).<attr>"), tokens);
    }

    @Test
    void aPluginTokenResolvesThroughTheRegistry() {
        ExpressionRegistry r = ExpressionRegistry.withBuiltins();
        r.register(new OneToken("$tenant.id", "acme"));

        assertEquals("acme", r.evaluate("$tenant.id", ctx()).orElse(null));
        assertEquals("2026-07-08", r.evaluate("$today", ctx()).orElse(null), "built-ins still resolve");
        assertTrue(r.declarations().stream().anyMatch(d -> d.token().equals("$tenant.id")));
    }

    @Test
    void aCollidingTokenFailsTheProvidersRegistration() {
        ExpressionRegistry r = ExpressionRegistry.withBuiltins();

        var boom = assertThrows(IllegalStateException.class, () -> r.register(new OneToken("$today", "nope")));
        assertTrue(boom.getMessage().contains("$today"));
        assertEquals("2026-07-08", r.evaluate("$today", ctx()).orElse(null),
                "the rejected provider left the registry untouched");
    }

    @Test
    void anUnknownTokenIsUndeclaredAndUnresolvable() {
        ExpressionRegistry r = ExpressionRegistry.withBuiltins();

        assertFalse(r.declares("$Yesterdy"), "the fail-closed gate of §6.3");
        assertTrue(r.evaluate("$Yesterdy", ctx()).isEmpty());
        assertTrue(r.declares("$job.last_success_time"),
                "declared-but-valueless is a different case from unknown — it still falls through");
        assertTrue(r.evaluate("$job.last_success_time", ctx()).isEmpty());
    }

    @Test
    void theGrammarSeparatesExpressionsFromEscapedLiterals() {
        assertTrue(ExpressionRegistry.isExpression("$today"));
        assertFalse(ExpressionRegistry.isExpression("$$today"), "$$ is the literal escape (§6.2)");
        assertFalse(ExpressionRegistry.isExpression("100.00"), "a value with no leading $ names no token");

        assertEquals("$today", ExpressionRegistry.unescape("$$today"));
        assertEquals("$today", ExpressionRegistry.unescape("$today"), "unescaping a real token is a no-op");
        assertEquals("plain", ExpressionRegistry.unescape("plain"));
    }

    @Test
    void theLongerOfTwoOverlappingLiteralsWins() {
        // $now and $now.epoch_seconds are declared by the same provider today, but the routing rule is
        // what lets a plugin declare a longer token under an existing prefix without being captured.
        ExpressionRegistry r = ExpressionRegistry.withBuiltins();
        r.register(new OneToken("$signal.tenant", "acme"));

        assertEquals("acme", r.evaluate("$signal.tenant", ctx()).orElse(null));
        assertTrue(r.evaluate("$signal.other", ctx()).isEmpty(), "the built-in prefix still owns the rest");
    }
}
