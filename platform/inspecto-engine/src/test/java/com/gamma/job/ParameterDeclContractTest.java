package com.gamma.job;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The declaration's rendering + validation contract (job-parameter-contract §7.2): defaults preserve
 *  today's behaviour, the builder covers what the raw constructor could not express, and the whole
 *  contract reaches the UI through {@code GET /jobs/types/{id}}. */
class ParameterDeclContractTest {

    @Test
    void theSixArgFormLeavesEveryRenderingComponentAtItsDefault() {
        // The guarantee that let 16 existing call sites stay untouched.
        ParameterDecl d = ParameterDecl.required("config", ParamType.STRING, "Path to the .toon");

        assertNull(d.label());
        assertEquals(ParameterDecl.Tier.REQUIRED, d.tier(), "tier defaults from required");
        assertEquals(List.of(), d.options());
        assertNull(d.pattern());
        assertNull(d.min(), "unbounded is null, not 0 — 0 is a meaningful bound");
        assertFalse(d.multi());
        assertFalse(d.secret());
        assertTrue(d.expressions(), "$-tokens are accepted unless a declaration opts out");
        assertEquals(ParameterDecl.Tier.OPTIONAL,
                ParameterDecl.optional("scope", ParamType.STRING, null, "s").tier());
    }

    @Test
    void theBuilderExpressesWhatTheRawConstructorCouldNot() {
        ParameterDecl d = ParameterDecl.of("to", ParamType.EMAIL)
                .label("To").tier(ParameterDecl.Tier.REQUIRED).multi().group("Recipients").build();

        assertEquals("To", d.label());
        assertTrue(d.required(), "Tier.REQUIRED implies required — one fact, not two to keep in sync");
        assertTrue(d.multi());
        assertEquals("Recipients", d.group());

        // The wart it retires: a decl with deduce set previously needed the raw all-args constructor.
        ParameterDecl deduced = ParameterDecl.of("day", ParamType.DATE).deduce("$yesterday").build();
        assertEquals("$yesterday", deduced.deduce());
        assertEquals(ParameterDecl.Tier.OPTIONAL, deduced.tier());
    }

    @Test
    void aMultiSelectIsRejectedRatherThanHalfRendered() {
        // §7.4 v1 constraint: options + multi has no renderer support and no declared consumer. Failing at
        // construction is honest; rendering half of it is not.
        var boom = assertThrows(IllegalArgumentException.class,
                () -> ParameterDecl.of("kinds", ParamType.STRING).multi().options("A", "B").build());
        assertTrue(boom.getMessage().contains("kinds"));
    }

    @Test
    void theContractReachesTheUiThroughTheTypeDescriptor() {
        var descriptor = new JobTypeDescriptor("mail.send", "Send Mail", "Sends mail.",
                List.of(ParameterDecl.of("body", ParamType.TEXT).label("Body").group("Message")
                        .placeholder("Daily report for $yesterday").tier(ParameterDecl.Tier.REQUIRED).build()));

        @SuppressWarnings("unchecked")
        Map<String, Object> p = ((List<Map<String, Object>>) descriptor.toMap().get("parameters")).get(0);

        assertEquals("TEXT", p.get("type"), "multiline is declared, not sniffed from the name 'sql'");
        assertEquals("Body", p.get("label"));
        assertEquals("Message", p.get("group"));
        assertEquals("REQUIRED", p.get("tier"));
        assertEquals(true, p.get("expressions"));
        assertNull(p.get("min"), "an unbounded field stays null over the wire");
        assertEquals("", p.get("pattern"), "unset strings serve as empty, matching the existing keys");
    }

    @Test
    void emailIsEnforcedAsATypeNotJustRenderedAsOne() {
        List<ParameterDecl> decls = List.of(ParameterDecl.of("to", ParamType.EMAIL).required().build());
        var ctx = new ExpressionContext("run-1", java.time.Instant.EPOCH, "cron", java.time.ZoneOffset.UTC,
                java.util.Optional::empty, (j, n) -> java.util.Optional.empty(), Map.of());
        var registry = ExpressionRegistry.withBuiltins();

        var ok = ParameterResolver.resolve(decls, Map.of("to", "ops@example.com"), Map.of(), Map.of(),
                registry, ctx);
        assertEquals("ops@example.com", ok.resolved().get("to"));

        var bad = ParameterResolver.resolve(decls, Map.of("to", "not-an-address"), Map.of(), Map.of(),
                registry, ctx);
        assertEquals(1, bad.invalidType().size(), "REJECTED before the Job's own code runs");
        assertTrue(bad.invalidType().get(0).contains("to"));
    }
}
