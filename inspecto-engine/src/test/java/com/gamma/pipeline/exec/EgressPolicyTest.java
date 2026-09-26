package com.gamma.pipeline.exec;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** {@link EgressPolicy}: host syntax, the deny-by-default address classes, and what an allowlist entry lifts. */
class EgressPolicyTest {

    @Test
    void userinfoAndNonCanonicalNumericHostsAreRefused() {
        for (String bad : List.of("trusted.example.com@attacker.example", "2130706433", "0177.0.0.1", "0x7f000001",
                "0x7f.0.0.1", "127.1", "127.0.0.01", "256.1.1.1", "host_name.example", "a..b", "-x.example",
                "[fe80::1%eth0]", "[::1]:8443", "host.example:8443", "exa mple.com", " example.com", ""))
            assertThrows(IllegalArgumentException.class, () -> EgressPolicy.checkHost(bad), bad);
    }

    @Test
    void dnsNamesAndCanonicalLiteralsAreAccepted() {
        for (String ok : List.of("tickets.example.com", "pcrf", "LOCALHOST", "10.20.30.40", "0.0.0.0", "8.8.8.8",
                "[::1]", "2001:db8::1", "[2001:db8::1]"))
            assertDoesNotThrow(() -> EgressPolicy.checkHost(ok), ok);
    }

    private static String cls(String literal) {
        return EgressPolicy.deniedClass(InetAddress.ofLiteral(literal));
    }

    @Test
    void theDefaultDeniesEveryInternalClass() {
        assertEquals("loopback", cls("127.0.0.1"));
        assertEquals("loopback", cls("::1"));
        assertEquals("link-local", cls("169.254.169.254"));
        assertEquals("link-local", cls("fe80::1"));
        assertEquals("private", cls("10.1.2.3"));
        assertEquals("private", cls("172.16.0.1"));
        assertEquals("private", cls("172.31.255.255"));
        assertEquals("private", cls("192.168.1.1"));
        assertEquals("private", cls("fd00::1"));
        assertEquals("cgnat", cls("100.64.0.1"));
        assertEquals("multicast", cls("224.0.0.1"));
        assertEquals("unspecified", cls("0.0.0.0"));
        assertEquals("unspecified", cls("::"));
        assertEquals("loopback", cls("::ffff:127.0.0.1"), "IPv4-mapped is classified as the IPv4 it carries");
        assertNull(cls("8.8.8.8"));
        assertNull(cls("172.32.0.1"));
        assertNull(cls("2001:4860:4860::8888"));
    }

    /** Round-2 finding 2: an IPv6 address carrying an IPv4 one is classified by the IPv4 it carries. */
    @Test
    void anEmbeddedIpv4IsClassifiedByTheAddressItCarries() throws Exception {
        assertEquals("loopback", cls("::127.0.0.1"), "IPv4-compatible");
        assertEquals("loopback", cls("2002:7f00:1::"), "6to4");
        assertEquals("loopback", cls("64:ff9b::7f00:1"), "NAT64");
        assertEquals("private", cls("64:ff9b::a00:1"), "NAT64 → 10.0.0.1");
        assertEquals("link-local", cls("64:ff9b::a9fe:a9fe"), "NAT64 → the metadata service");
        assertNotNull(cls("64:ff9b:1:a9fe:a9:fe00::"), "local-use NAT64, /48 position (169.254.169.254) is denied");
        assertEquals("link-local", EgressPolicy.deniedClass(EgressPolicy.embeddedIpv4(InetAddress.ofLiteral("64:ff9b:1:a9fe:a9:fe00::").getAddress()).get(1)));
        assertEquals("loopback", cls("64:ff9b:1::7f00:1"), "local-use NAT64, /96 position");
        for (String v6 : List.of("::127.0.0.1", "2002:7f00:1::", "64:ff9b::7f00:1", "64:ff9b::a00:1", "64:ff9b::a9fe:a9fe"))
            assertThrows(EgressPolicy.Refused.class, () -> EgressPolicy.resolve(v6, EgressPolicy.Allowlist.EMPTY), v6);
        assertEquals("loopback", cls("::1"));
        assertEquals("unspecified", cls("::"));
        assertNull(cls("64:ff9b::808:808"), "NAT64 → 8.8.8.8 is public");
        assertNull(cls("2002:808:808::"), "6to4 of a public address is public");
    }

    @Test
    void aHostEntryLiftsOnlyThePrivateClassesAndACidrLiftsItsRange() {
        EgressPolicy.Allowlist allow = EgressPolicy.Allowlist.of(List.of("pcrf.internal", "127.0.0.1/32", "10.9.0.0/16"));
        InetAddress private10 = InetAddress.ofLiteral("10.1.2.3");
        assertTrue(allow.permits("pcrf.internal", private10, "private"));
        assertFalse(allow.permits("other.internal", private10, "private"));
        assertFalse(allow.permits("pcrf.internal", InetAddress.ofLiteral("169.254.169.254"), "link-local"),
                "a re-pointed allowlisted NAME still cannot reach the metadata service");
        assertTrue(allow.permits("x", InetAddress.ofLiteral("127.0.0.1"), "loopback"));
        assertFalse(allow.permits("x", InetAddress.ofLiteral("127.0.0.2"), "loopback"));
        assertTrue(allow.permits("x", InetAddress.ofLiteral("10.9.200.1"), "private"));
        assertThrows(IllegalArgumentException.class, () -> EgressPolicy.Allowlist.of(List.of("0x0a000000/8")));
    }

    @Test
    void resolveRefusesALoopbackLiteralUnlessItsRangeIsListed() throws Exception {
        assertThrows(EgressPolicy.Refused.class, () -> EgressPolicy.resolve("127.0.0.1", EgressPolicy.Allowlist.EMPTY));
        assertThrows(EgressPolicy.Refused.class, () -> EgressPolicy.resolve("localhost",
                EgressPolicy.Allowlist.of(List.of("localhost"))), "a host entry never lifts loopback");
        assertEquals("127.0.0.1", EgressPolicy.resolve("127.0.0.1",
                EgressPolicy.Allowlist.of(List.of("127.0.0.1"))).getHostAddress());
    }
}
