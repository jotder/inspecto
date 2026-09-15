package com.gamma.job;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PARAM-SECRET-LEAK-1 — a {@code secret}-declared parameter must not reach the run log or a rejection
 * message in cleartext, and the rule that decides this must have exactly one definition.
 *
 * <p>⛔ <b>Every negative assertion here is paired with a positive control.</b> "the secret is absent
 * from this string" is satisfiable by an empty string, a message that was never produced, or a
 * parameter that never resolved — so each case is run twice, once with {@code secret()} and once
 * without, and the control asserts the value IS present. A guard that cannot fail proves nothing,
 * which is the failure mode this repo has recorded more than once.
 */
class SecretMaskingTest {

    private static final String SECRET = "hunter2";
    private static final ExpressionRegistry EXPR = ExpressionRegistry.withBuiltins();

    private static ExpressionContext ctx() {
        return new ExpressionContext("run-1", Instant.parse("2026-07-08T06:00:00Z"), "cron",
                ZoneOffset.UTC, Optional::empty, (job, name) -> Optional.empty(), Map.of());
    }

    /** One declaration, identical but for {@code secret}, so a pair of runs isolates that one bit. */
    private static ParameterDecl.Builder token(ParamType type) {
        return ParameterDecl.of("api_token", type);
    }

    private static String rejection(ParameterDecl decl, String value) {
        var r = ParameterResolver.resolve(List.of(decl), Map.of(), Map.of(),
                Map.of(decl.name(), value), EXPR, ctx());
        return String.join("; ", r.invalidType()) + String.join("; ", r.unknownExpression());
    }

    // ------------------------------------------------------------------ the rule itself

    @Test
    void aLiteralIsHiddenAndAnEnvReferenceStaysVisible() {
        // The reference is not itself sensitive, and hiding it would leave an operator unable to see
        // how the secret is wired. House precedent: ConnectionProfile.
        assertTrue(SecretMasking.hides(SECRET));
        assertFalse(SecretMasking.hides("${ENV:API_TOKEN}"));
        assertEquals("***", SecretMasking.shown(SECRET));
        assertEquals("${ENV:API_TOKEN}", SecretMasking.shown("${ENV:API_TOKEN}"));
    }

    @Test
    void onlyAStringCanCarryACredential() {
        // A boolean or a number is not a secret worth hiding, and masking it would make a config view
        // lie about its own shape.
        assertFalse(SecretMasking.hides(Boolean.TRUE));
        assertFalse(SecretMasking.hides(42));
        assertFalse(SecretMasking.hides(null));
        assertEquals(42, SecretMasking.shown(42));
    }

    @Test
    void maskAppliesOnlyToDeclaredSecretKeys() {
        Map<String, String> resolved = new java.util.LinkedHashMap<>();
        resolved.put("audience", "world");
        resolved.put("api_token", SECRET);
        resolved.put("fallback", "${ENV:API_TOKEN}");

        Map<String, Object> masked = SecretMasking.mask(resolved, Set.of("api_token", "fallback"));

        assertEquals("***", masked.get("api_token"));
        assertEquals("${ENV:API_TOKEN}", masked.get("fallback"), "a reference stays readable");
        assertEquals("world", masked.get("audience"), "a non-secret parameter is untouched");
        assertEquals(List.of("audience", "api_token", "fallback"), List.copyOf(masked.keySet()),
                "order is preserved — this map is read by a human in a log line");
    }

    @Test
    void namesExtractsOnlyTheSecretDeclarations() {
        List<ParameterDecl> decls = List.of(
                ParameterDecl.of("audience", ParamType.STRING).build(),
                token(ParamType.STRING).secret().build());
        assertEquals(Set.of("api_token"), SecretMasking.names(decls));
        assertEquals(Set.of(), SecretMasking.names(List.of()));
    }

    // ------------------------------- the resolver's rejection messages (four downstream sinks)

    @Test
    void aTypeViolationHidesASecretValueButNamesTheExpectedType() {
        String masked = rejection(token(ParamType.INTEGER).secret().build(), SECRET);
        String control = rejection(token(ParamType.INTEGER).build(), SECRET);

        assertFalse(masked.contains(SECRET), "the credential must not reach invalidType: " + masked);
        assertTrue(masked.contains("***"), masked);
        assertTrue(masked.contains("INTEGER"), "the DECLARED type is not a secret and must stay: " + masked);
        // The control proves the probe can find the value at all.
        assertTrue(control.contains(SECRET), "control: a non-secret value IS shown: " + control);
    }

    @Test
    void anOptionsViolationHidesASecretValueButKeepsTheAllowedList() {
        String masked = rejection(token(ParamType.STRING).options("a", "b").secret().build(), SECRET);
        String control = rejection(token(ParamType.STRING).options("a", "b").build(), SECRET);

        assertFalse(masked.contains(SECRET), masked);
        assertTrue(masked.contains("[a, b]"), "the author's own options stay visible: " + masked);
        assertTrue(control.contains(SECRET), "control: " + control);
    }

    @Test
    void aPatternViolationHidesASecretValueButKeepsThePattern() {
        String masked = rejection(token(ParamType.STRING).pattern("[0-9]+").secret().build(), SECRET);
        String control = rejection(token(ParamType.STRING).pattern("[0-9]+").build(), SECRET);

        assertFalse(masked.contains(SECRET), masked);
        assertTrue(masked.contains("[0-9]+"), "the declared pattern stays visible: " + masked);
        assertTrue(control.contains(SECRET), "control: " + control);
    }

    @Test
    void aBoundsViolationHidesANonNumericSecret() {
        String masked = rejection(token(ParamType.STRING).min(0).max(5).secret().build(), SECRET);
        String control = rejection(token(ParamType.STRING).min(0).max(5).build(), SECRET);

        assertFalse(masked.contains(SECRET), masked);
        assertTrue(control.contains(SECRET), "control: " + control);
    }

    @Test
    void anEmptyItemInAMultiListDoesNotEchoTheWholeSecretCsv() {
        // violation() echoes the ENTIRE csv, not the offending item, so it leaks on a different path
        // from itemViolation and needs its own cover.
        String csv = SECRET + ",,x";
        String masked = rejection(token(ParamType.STRING).multi().secret().build(), csv);
        String control = rejection(token(ParamType.STRING).multi().build(), csv);

        assertFalse(masked.contains(SECRET), masked);
        assertTrue(control.contains(SECRET), "control: " + control);
    }

    @Test
    void anUnknownExpressionHidesASecretThatMerelyLooksLikeAToken() {
        // A password such as `$ecret1` is read as an expression, so the unknown-token message would
        // otherwise print the credential verbatim. ⚠ This path is `unknownExpression`, not `invalidType`.
        String dollarLed = "$ecret1";
        String masked = rejection(token(ParamType.STRING).secret().build(), dollarLed);
        String control = rejection(token(ParamType.STRING).build(), dollarLed);

        assertFalse(masked.contains(dollarLed), masked);
        assertTrue(masked.contains("***"), masked);
        assertTrue(control.contains(dollarLed), "control: " + control);
    }

    @Test
    void aValidSecretResolvesNormallyAndIsNotMangled() {
        // ⛔ Masking is a presentation concern only: the Job itself must still receive the real value,
        // or the fix would break every Job that uses a credential.
        var r = ParameterResolver.resolve(List.of(token(ParamType.STRING).secret().build()),
                Map.of(), Map.of(), Map.of("api_token", SECRET), EXPR, ctx());

        assertTrue(r.invalidType().isEmpty(), "a valid secret is not a violation");
        assertEquals(SECRET, r.resolved().get("api_token"), "the Run receives the real credential");
    }
}
