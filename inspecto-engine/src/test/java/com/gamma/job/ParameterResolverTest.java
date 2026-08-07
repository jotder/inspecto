package com.gamma.job;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** P3a parameter resolution (§7): layer order (config → deduce → default), the $-context, and fail-closed
 *  REJECTED on a missing required parameter. Deterministic — a fixed fire time + UTC zone. */
class ParameterResolverTest {

    private static final Instant FIRE = Instant.parse("2026-07-08T06:00:00Z");
    private static final ExpressionRegistry EXPR = ExpressionRegistry.withBuiltins();

    private static ExpressionContext ctx(Optional<LocalDateTime> lastSuccess) {
        return ctx(lastSuccess, (job, name) -> Optional.empty());
    }

    private static ExpressionContext ctx(Optional<LocalDateTime> lastSuccess,
            java.util.function.BiFunction<String, String, Optional<RunArtifact>> upstream) {
        return ctx(lastSuccess, upstream, Map.of());
    }

    private static ExpressionContext ctx(Optional<LocalDateTime> lastSuccess,
            java.util.function.BiFunction<String, String, Optional<RunArtifact>> upstream,
            Map<String, Object> signalPayload) {
        return new ExpressionContext("run-1", FIRE, "cron", ZoneOffset.UTC, () -> lastSuccess,
                upstream, signalPayload);
    }

    private static ParameterResolver.Resolution resolve(List<ParameterDecl> decls,
            Map<String, String> config, ExpressionContext ctx) {
        return ParameterResolver.resolve(decls, Map.of(), Map.of(), config, EXPR, ctx);
    }

    private static ParameterDecl decl(String name, boolean required, String deduce, String def) {
        return new ParameterDecl(name, ParamType.STRING, required, deduce, def, name);
    }

    @Test
    void deducesTheBuiltInDollarContext() {
        var c = ctx(Optional.of(LocalDateTime.parse("2026-07-07T06:00:04")));
        assertEquals("2026-07-08", EXPR.evaluate("$today", c).orElse(null));
        assertEquals("2026-07-07", EXPR.evaluate("$yesterday", c).orElse(null));
        assertEquals("2026-07-09", EXPR.evaluate("$tomorrow", c).orElse(null));
        assertEquals("2026-07-07", EXPR.evaluate("$day(-1)", c).orElse(null));
        assertEquals("2026-07-09", EXPR.evaluate("$day(1)", c).orElse(null));
        assertEquals("2026-06-08", EXPR.evaluate("$month(-1)", c).orElse(null));
        assertEquals("2025-07-08", EXPR.evaluate("$year(-1)", c).orElse(null));
        assertEquals("2027-07-08", EXPR.evaluate("$year(1)", c).orElse(null));
        assertEquals("2026-07-08T06:00:00Z", EXPR.evaluate("$now", c).orElse(null));
        assertEquals("1783490400", EXPR.evaluate("$now.epoch_seconds", c).orElse(null));
        assertEquals("1783490400000", EXPR.evaluate("$now.epoch_millis", c).orElse(null));
        assertEquals("run-1", EXPR.evaluate("$run.id", c).orElse(null));
        assertEquals("2026-07-08T06:00:00Z", EXPR.evaluate("$run.fire_time", c).orElse(null));
        assertEquals("cron", EXPR.evaluate("$run.actor", c).orElse(null));
        assertEquals("2026-07-07T06:00:04Z", EXPR.evaluate("$job.last_success_time", c).orElse(null));
        assertNull(EXPR.evaluate("$unknown.token", c).orElse(null), "unknown token is unresolved");
    }

    @Test
    void lastSuccessTimeIsNullWhenTheJobNeverSucceeded() {
        assertTrue(EXPR.evaluate("$job.last_success_time", ctx(Optional.empty())).isEmpty());
    }

    @Test
    void resolvesUpstreamArtifactAttributes() {
        RunArtifact art = new RunArtifact("up-run-9", "loader", 1, "output", "dataset",
                "txn_rollup", null, 4200L, 0L, "2026-07-07T06:00:04Z", "2026-07-01..2026-07-07",
                "2026-07-08T06:00:00Z");
        var c = ctx(Optional.empty(),
                (job, name) -> "loader".equals(job) && "output".equals(name) ? Optional.of(art) : Optional.empty());

        assertEquals("txn_rollup", EXPR.evaluate("$upstream(loader).artifact(output).ref", c).orElse(null));
        assertEquals("4200", EXPR.evaluate("$upstream(loader).artifact(output).rows", c).orElse(null));
        assertEquals("2026-07-07T06:00:04Z",
                EXPR.evaluate("$upstream(loader).artifact(output).watermark", c).orElse(null));
        assertEquals("2026-07-01..2026-07-07",
                EXPR.evaluate("$upstream(loader).artifact(output).time_range", c).orElse(null));
        assertNull(EXPR.evaluate("$upstream(loader).artifact(missing).ref", c).orElse(null),
                "an absent artifact resolves to null (⇒ REJECTED if the param is required)");
        assertNull(EXPR.evaluate("$upstream(loader).artifact(output).bogus_attr", c).orElse(null),
                "an unknown attribute is unresolved");
    }

    @Test
    void layerOrderConfigThenDeduceThenDefault() {
        List<ParameterDecl> decls = List.of(
                decl("event_date", true, "$day(-1)", null),   // deduced (no config value)
                decl("scope", false, null, "status"),          // default
                decl("region", false, null, null));            // unresolved optional ⇒ absent
        var r = resolve(decls, Map.of(), ctx(Optional.empty()));

        assertEquals("2026-07-07", r.resolved().get("event_date"), "deduce fills when config is absent");
        assertEquals("status", r.resolved().get("scope"), "default fills when config + deduce are absent");
        assertFalse(r.resolved().containsKey("region"), "an unresolved optional is simply omitted");
        assertTrue(r.missingRequired().isEmpty());
    }

    @Test
    void authoredConfigWinsOverDeduceAndDefault() {
        List<ParameterDecl> decls = List.of(
                decl("event_date", true, "$day(-1)", null),
                decl("scope", false, null, "status"));
        var r = resolve(decls,
                Map.of("event_date", "2026-01-01", "scope", "batch"), ctx(Optional.empty()));

        assertEquals("2026-01-01", r.resolved().get("event_date"), "authored config beats the deduce");
        assertEquals("batch", r.resolved().get("scope"), "authored config beats the default");
    }

    @Test
    void signalBindResolvesAgainstTheFiringPayload() {
        // P3a-2 (§7.2 layer 2): bind: maps a parameter to a $signal.<field> expression.
        List<ParameterDecl> decls = List.of(
                decl("event_date", true, "$day(-1)", null),   // would deduce yesterday, but bind wins
                decl("findings", true, null, null));
        var c = ctx(Optional.empty(), (j, n) -> Optional.empty(),
                Map.of("event_date", "2026-07-02", "findings", 17));
        var r = ParameterResolver.resolve(decls,
                Map.of(),
                Map.of("event_date", "$signal.event_date", "findings", "$signal.findings"),
                Map.of(), EXPR, c);

        assertEquals("2026-07-02", r.resolved().get("event_date"), "bind beats the declared deduce");
        assertEquals("17", r.resolved().get("findings"), "a non-string payload value is stringified");
        assertTrue(r.missingRequired().isEmpty());
    }

    @Test
    void triggerArgsWinOverBindConfigAndDeduce() {
        // P3a-2 (§7.2 layer 1): explicit trigger args are the highest-precedence source.
        List<ParameterDecl> decls = List.of(decl("event_date", true, "$day(-1)", null));
        var c = ctx(Optional.empty(), (j, n) -> Optional.empty(), Map.of("event_date", "2026-07-02"));
        var r = ParameterResolver.resolve(decls,
                Map.of("event_date", "2026-01-01"),                 // trigger args
                Map.of("event_date", "$signal.event_date"),          // bind
                Map.of("event_date", "2026-05-05"),                  // config
                EXPR, c);

        assertEquals("2026-01-01", r.resolved().get("event_date"), "trigger args beat bind, config and deduce");
    }

    @Test
    void bindToAnAbsentSignalFieldFallsThroughToConfig() {
        List<ParameterDecl> decls = List.of(decl("scope", true, null, null));
        var c = ctx(Optional.empty(), (j, n) -> Optional.empty(), Map.of());   // empty payload
        var r = ParameterResolver.resolve(decls,
                Map.of(), Map.of("scope", "$signal.missing"), Map.of("scope", "fallback"), EXPR, c);

        assertEquals("fallback", r.resolved().get("scope"),
                "a bind whose $signal field is absent falls through to the next layer");
    }

    @Test
    void anUnregisteredTokenFailsTheRunInsteadOfFallingThrough() {
        // §6.3: $Yesterdy must be distinguishable from an unset field — the old switch returned null, so a
        // typo surfaced (if at all) as a confusing "missing required parameter".
        List<ParameterDecl> decls = List.of(decl("event_date", true, "$Yesterdy", "2026-01-01"));
        var r = resolve(decls, Map.of(), ctx(Optional.empty()));

        assertEquals(1, r.unknownExpression().size());
        assertTrue(r.unknownExpression().get(0).contains("$Yesterdy"));
        assertTrue(r.missingRequired().isEmpty(), "the typo is reported as itself, not as a missing value");
        assertFalse(r.resolved().containsKey("event_date"), "and the declared default is NOT silently used");
    }

    @Test
    void anUnregisteredTokenInABindAlsoFailsTheRun() {
        List<ParameterDecl> decls = List.of(decl("scope", true, null, null));
        var c = ctx(Optional.empty(), (j, n) -> Optional.empty(), Map.of("scope", "all"));
        var r = ParameterResolver.resolve(decls,
                Map.of(), Map.of("scope", "$signl.scope"), Map.of("scope", "fallback"), EXPR, c);

        assertEquals(1, r.unknownExpression().size(), "a misspelled $signal prefix is not a silent fallback");
        assertTrue(r.unknownExpression().get(0).contains("$signl.scope"));
    }

    @Test
    void doubleDollarEscapesToALiteralDollar() {
        // §6.2: must ship before authored values are evaluated (step 3), or a config holding a literal $
        // breaks. In an expression position the escape is what makes a literal $ expressible at all.
        List<ParameterDecl> decls = List.of(decl("prefix", true, "$$today", null));
        var r = resolve(decls, Map.of(), ctx(Optional.empty()));

        assertEquals("$today", r.resolved().get("prefix"), "$$today is the literal eight-char string");
        assertTrue(r.unknownExpression().isEmpty(), "an escaped literal is not a token lookup");
    }

    @Test
    void authoredConfigValuesAreEvaluatedAsExpressions() {
        // Step 3: the whole point — a $-token typed into a parameter field in the UI now resolves at fire
        // time. (This test replaced step 2's assertion that layer 3 was still literal; it was written to
        // make exactly this flip a visible edit.)
        List<ParameterDecl> decls = List.of(decl("amount", true, null, null), decl("when", true, null, null));
        var r = resolve(decls, Map.of("amount", "$100", "when", "$today"), ctx(Optional.empty()));

        assertEquals("2026-07-08", r.resolved().get("when"), "an authored $today resolves at fire time");
        assertEquals("$100", r.resolved().get("amount"),
                "$ + digit is not a token shape — a currency amount needs no escape");
    }

    @Test
    void triggerArgsAreEvaluatedToo() {
        // Layer 1: the manual POST /jobs/{name}/trigger body's params.
        List<ParameterDecl> decls = List.of(decl("day", true, null, null));
        var r = ParameterResolver.resolve(decls, Map.of("day", "$yesterday"), Map.of(), Map.of(),
                EXPR, ctx(Optional.empty()));

        assertEquals("2026-07-07", r.resolved().get("day"));
    }

    @Test
    void anAuthoredLiteralDollarSurvivesTheEscapeAndTheOtherDollarConventions() {
        List<ParameterDecl> decls = List.of(decl("token", true, null, null), decl("secret", true, null, null));
        var r = resolve(decls, Map.of("token", "$$today", "secret", "${ENV:PW}"), ctx(Optional.empty()));

        assertEquals("$today", r.resolved().get("token"), "§6.2: the escape makes a literal $ expressible");
        assertEquals("${ENV:PW}", r.resolved().get("secret"),
                "a secret reference is the codebase's other $ convention, not an unknown token");
        assertTrue(r.unknownExpression().isEmpty());
    }

    @Test
    void aDeclarationCanOptOutOfExpressionsEntirely() {
        // §6.1's scoped evaluation, made concrete: this is what keeps a sql.template body verbatim.
        List<ParameterDecl> decls = List.of(
                ParameterDecl.of("sql", ParamType.TEXT).required().noExpressions().build());
        var r = resolve(decls, Map.of("sql", "$today"), ctx(Optional.empty()));

        assertEquals("$today", r.resolved().get("sql"), "taken verbatim, not resolved");
        assertTrue(r.unknownExpression().isEmpty());
    }

    @Test
    void anUnknownTokenInAnAuthoredValueFailsTheRun() {
        List<ParameterDecl> decls = List.of(decl("when", true, null, null));
        var r = resolve(decls, Map.of("when", "$Yesterdy"), ctx(Optional.empty()));

        assertEquals(1, r.unknownExpression().size(),
                "the fail-closed gate now covers the layer authors actually type into");
    }

    @Test
    void missingRequiredParameterIsReported() {
        List<ParameterDecl> decls = List.of(
                decl("must_have", true, null, null),           // no config, no deduce, no default
                decl("ok", false, null, "d"));
        var r = resolve(decls, Map.of(), ctx(Optional.empty()));

        assertEquals(List.of("must_have"), r.missingRequired(), "a required param with no source is REJECTED-worthy");
        assertFalse(r.resolved().containsKey("must_have"));
        assertEquals("d", r.resolved().get("ok"));
    }

    // ── declared-type validation (2026-07-20): a resolved value that doesn't parse as its ParamType
    // ── is REJECTED-worthy, not silently passed through as a raw string for the Job to blow up on ──

    @Test
    void wellFormedValuesOfEveryTypeResolveCleanly() {
        List<ParameterDecl> decls = List.of(
                new ParameterDecl("n", ParamType.INTEGER, true, null, null, "n"),
                new ParameterDecl("f", ParamType.DECIMAL, true, null, null, "f"),
                new ParameterDecl("b", ParamType.BOOLEAN, true, null, null, "b"),
                new ParameterDecl("d", ParamType.DATE, true, null, null, "d"),
                new ParameterDecl("t", ParamType.INSTANT, true, null, null, "t"),
                new ParameterDecl("r", ParamType.DATASET_REF, true, null, null, "r"));
        var r = resolve(decls, Map.of("n", "42", "f", "3.14", "b", "TRUE",
                "d", "2026-07-08", "t", "2026-07-08T06:00:00Z", "r", "orders"), ctx(Optional.empty()));

        assertTrue(r.missingRequired().isEmpty());
        assertTrue(r.invalidType().isEmpty());
        assertEquals("42", r.resolved().get("n"));
        assertEquals("orders", r.resolved().get("r"));
    }

    @Test
    void malformedValueOfEachTypeIsRejectedAsInvalidTypeNotSilentlyPassedThrough() {
        List<ParameterDecl> decls = List.of(
                new ParameterDecl("n", ParamType.INTEGER, true, null, null, "n"),
                new ParameterDecl("f", ParamType.DECIMAL, true, null, null, "f"),
                new ParameterDecl("b", ParamType.BOOLEAN, true, null, null, "b"),
                new ParameterDecl("d", ParamType.DATE, true, null, null, "d"),
                new ParameterDecl("t", ParamType.INSTANT, true, null, null, "t"));
        var r = resolve(decls, Map.of("n", "abc", "f", "abc", "b", "yes",
                "d", "not-a-date", "t", "not-an-instant"), ctx(Optional.empty()));

        assertTrue(r.missingRequired().isEmpty(), "these resolved to a value — the problem is the type, not absence");
        assertEquals(5, r.invalidType().size());
        assertTrue(r.invalidType().stream().anyMatch(s -> s.startsWith("n ")));
        assertTrue(r.resolved().isEmpty(), "no malformed value reaches the map a Job would read from");
    }

    @Test
    void aBindExtractedNonNumericValueForARequiredIntegerIsRejected() {
        // The concrete motivating case: $signal.<field> can carry any JSON value; a required INTEGER
        // parameter must not silently receive "n/a" and blow up deep inside the Job's own parsing.
        List<ParameterDecl> decls = List.of(new ParameterDecl("count", ParamType.INTEGER, true, null, null, "count"));
        var c = ctx(Optional.empty(), (j, n) -> Optional.empty(), Map.of("count", "n/a"));
        var r = ParameterResolver.resolve(decls, Map.of(), Map.of("count", "$signal.count"), Map.of(), EXPR, c);

        assertTrue(r.missingRequired().isEmpty(), "bind DID resolve a value — 'n/a'");
        assertEquals(1, r.invalidType().size());
        assertTrue(r.invalidType().get(0).contains("count"));
        assertFalse(r.resolved().containsKey("count"));
    }
}
