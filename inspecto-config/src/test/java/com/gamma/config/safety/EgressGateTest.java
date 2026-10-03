package com.gamma.config.safety;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Slice S4 of {@code policy-narrowing-design.md}: the verdicts of {@link EgressGate} over an explicit tier. */
class EgressGateTest {

    private static SafetyPolicyTier tier(Boolean network, List<String> allow, List<String> deny, SafetyPolicyTier.Mode mode) {
        return new SafetyPolicyTier(mode, network, null, null, null, null,
                allow == null ? null : allow.stream().map(HostPattern::parse).toList(), null, null, null, null,
                deny == null ? null : deny.stream().map(HostPattern::parse).toList(), null, null, null);
    }

    private static EgressGate gate(Boolean network, List<String> allow, List<String> deny) {
        return EgressGate.of(tier(network, allow, deny, null));
    }

    @Test
    void anAbsentPolicyDialsAnything() {
        assertDoesNotThrow(() -> EgressGate.of(SafetyPolicyTier.NONE).require("any.example", 443, "x"));
    }

    @Test
    void networkOffRefusesEvenAnAllowedHost() {
        EgressGate g = gate(false, List.of("a.example"), null);
        assertThrows(EgressRefusedException.class, () -> g.require("a.example", 22, "sftp"));
    }

    @Test
    void anAllowSetAdmitsItsMembersAndRefusesTheRestIncludingLookalikes() {
        EgressGate g = gate(null, List.of("*.ex.test"), null);
        assertDoesNotThrow(() -> g.require("api.ex.test", 443, "x"));
        assertThrows(EgressRefusedException.class, () -> g.require("evilex.test", 443, "x"));
        assertThrows(EgressRefusedException.class, () -> g.require("ex.test.evil", 443, "x"));
    }

    @Test
    void anEmptyAllowSetRefusesEverything() {
        assertThrows(EgressRefusedException.class, () -> gate(null, List.of(), null).require("a.example", 1, "x"));
    }

    @Test
    void aDenyEntryBeatsAnAllowEntry() {
        EgressGate g = gate(null, List.of("*.ex.test"), List.of("bad.ex.test"));
        assertDoesNotThrow(() -> g.require("good.ex.test", 1, "x"));
        assertThrows(EgressRefusedException.class, () -> g.require("bad.ex.test", 1, "x"));
    }

    @Test
    void aPermittedNameResolvingIntoADeniedCidrIsRefused() {
        EgressGate g = gate(null, null, List.of("127.0.0.0/8"));
        EgressRefusedException e = assertThrows(EgressRefusedException.class, () -> g.require("localhost", 80, "x"));
        assertTrue(e.getMessage().contains("denied address"), e.getMessage());
    }

    @Test
    void auditModeLogsAndLetsTheDialProceed() {
        EgressGate g = EgressGate.of(tier(false, null, null, SafetyPolicyTier.Mode.AUDIT));
        assertDoesNotThrow(() -> g.require("a.example", 1, "x"));
    }

    @Test
    void everyHostOfAMultiHostJdbcUrlIsGated() {
        EgressGate g = gate(null, List.of("db1.test", "db2.test"), null);
        assertDoesNotThrow(() -> g.requireJdbcUrl("jdbc:postgresql://db1.test:5432,db2.test:5432/app?x=1", "db"));
        assertThrows(EgressRefusedException.class,
                () -> g.requireJdbcUrl("jdbc:postgresql://db1.test:5432,evil.test:5432/app", "db"));
        assertThrows(EgressRefusedException.class,
                () -> g.requireJdbcUrl("jdbc:postgresql://u:p@evil.test/app", "db"));
        assertThrows(EgressRefusedException.class, () -> g.requireJdbcUrl("jdbc:oracle:thin:@evil.test:1521:sid", "db"));
        assertDoesNotThrow(() -> g.requireJdbcUrl("jdbc:duckdb:/tmp/x.db", "db"), "an in-process engine dials nothing");
    }

    @Test
    void aJdbcUrlThatHidesItsHostIsRefusedNotWaved() {
        assertThrows(EgressRefusedException.class, () -> gate(null, List.of("a.test"), null).requireJdbcUrl("jdbc:weird:thing", "db"));
    }

    @Test
    void everyBrokerOfABootstrapListIsGated() {
        EgressGate g = gate(null, List.of("k1.test"), null);
        assertDoesNotThrow(() -> g.requireHostList("k1.test:9092", "kafka"));
        assertThrows(EgressRefusedException.class, () -> g.requireHostList("k1.test:9092,k2.test:9092", "kafka"));
    }
}
