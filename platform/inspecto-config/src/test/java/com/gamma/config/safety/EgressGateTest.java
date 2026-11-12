package com.gamma.config.safety;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** Item (e) of SCALEOUT-OBJSTORE-DATABASE-DIR-1: each refused spelling beside an accepted twin one character away. */
    private static final Map<String, String> REFUSED_TO_TWIN = new LinkedHashMap<>();
    static {
        REFUSED_TO_TWIN.put("2130706433", "2130706433x");
        REFUSED_TO_TWIN.put("0177.0.0.1", "177.0.0.1");
        REFUSED_TO_TWIN.put("0x7f000001", "ox7f000001");
        REFUSED_TO_TWIN.put("127.1", "127.l");
        REFUSED_TO_TWIN.put("127.0.0.01", "127.0.0.1");
        REFUSED_TO_TWIN.put("127.0.0.1.", "127.0.0.1");
        REFUSED_TO_TWIN.put("fe80::1%1", "fe80::1");
        REFUSED_TO_TWIN.put("[::1]x", "[::1]");
    }

    @Test
    void aNonCanonicalNumericHostIsRefusedUnderEveryTierAndItsTwinIsNot() {
        // A malformed host, not a policy verdict: refused with no policy at all and in audit mode too.
        EgressGate open = EgressGate.of(SafetyPolicyTier.NONE);
        EgressGate audit = EgressGate.of(tier(false, null, null, SafetyPolicyTier.Mode.AUDIT));
        REFUSED_TO_TWIN.forEach((bad, twin) -> {
            assertThrows(EgressRefusedException.class, () -> open.require(bad, 80, "x"), bad);
            assertThrows(EgressRefusedException.class, () -> audit.require(bad, 80, "x"), bad);
            assertDoesNotThrow(() -> open.require(twin, 80, "x"), twin);
        });
        assertThrows(EgressRefusedException.class, () -> open.requireHostList("2130706433:9092", "kafka"));
        assertDoesNotThrow(() -> open.requireHostList("2130706433x:9092", "kafka"));
        assertThrows(EgressRefusedException.class,
                () -> open.requireJdbcUrl("jdbc:postgresql://0x7f.1:5432/app", "db"));
        assertDoesNotThrow(() -> open.requireJdbcUrl("jdbc:postgresql://127.0.0.1:5432/app", "db"));
    }

    @Test
    void aNumericSpellingCannotSlipPastADeniedCidr() {
        // inet_aton reads every one of these as 127.0.0.1; the old gate compared none of them with 127.0.0.0/8.
        EgressGate g = gate(null, null, List.of("127.0.0.0/8"));
        for (String bad : List.of("0x7f.1", "0177.0.0.1", "2130706433", "127.1", "0x7f.0.0.1"))
            assertThrows(EgressRefusedException.class, () -> g.require(bad, 80, "object store"), bad);
    }

    @Test
    void aNumericSpellingHiddenBehindIdnaDotsIsRefusedInAuditModeToo() {
        // IDNA maps U+3002 to '.', so the policy (and an IDNA-aware dialler) reads these as 0177.0.0.1 / 127.1.
        // Audit mode lets every policy miss through, so only the spelling rule can stop them; each twin ends in 'l'.
        EgressGate audit = EgressGate.of(tier(false, null, null, SafetyPolicyTier.Mode.AUDIT));
        assertThrows(EgressRefusedException.class, () -> audit.require("0177。0。0。1", 80, "x"));
        assertDoesNotThrow(() -> audit.require("0177。0。0。l", 80, "x"));
        assertThrows(EgressRefusedException.class, () -> audit.require("127．1", 80, "x"));
        assertDoesNotThrow(() -> audit.require("127．l", 80, "x"));
        // An upper-case IPv4-mapped literal is the same address as its lower-case twin and is accepted alike.
        assertDoesNotThrow(() -> audit.require("::FFFF:127.0.0.1", 80, "x"));
    }

    @Test
    void anIpv6AddressCarryingADeniedIpv4IsRefused() {
        // NAT64 64:ff9b::/96 carries 127.0.0.1; its twin one character away (64:ff9c::) carries nothing.
        EgressGate g = gate(null, null, List.of("127.0.0.0/8"));
        EgressRefusedException e = assertThrows(EgressRefusedException.class,
                () -> g.require("64:ff9b::7f00:1", 80, "x"));
        assertTrue(e.getMessage().contains("carries denied address 127.0.0.1"), e.getMessage());
        assertDoesNotThrow(() -> g.require("64:ff9c::7f00:1", 80, "x"));
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
