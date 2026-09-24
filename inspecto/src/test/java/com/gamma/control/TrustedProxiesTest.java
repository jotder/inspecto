package com.gamma.control;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** SEC review F3: the resolution rules of {@link TrustedProxies}, pinned directly (the HTTP half is
 *  {@code ControlApiClientIpTest}). */
class TrustedProxiesTest {

    private static InetAddress ip(String literal) throws Exception { return InetAddress.getByName(literal); }

    @Test
    void theDefaultTrustsNobodyAndIgnoresTheHeader() throws Exception {
        TrustedProxies none = TrustedProxies.parse(null);
        assertTrue(none.isEmpty());
        assertEquals("198.51.100.9", none.clientIp(ip("198.51.100.9"), List.of("203.0.113.1")));
        assertTrue(TrustedProxies.parse("  , ").isEmpty(), "blank entries are not a trust list");
    }

    @Test
    void anUntrustedPeerIsTheClientWhateverItForwards() throws Exception {
        TrustedProxies t = TrustedProxies.parse("10.0.0.0/8");
        assertEquals("198.51.100.9", t.clientIp(ip("198.51.100.9"), List.of("10.1.1.1, 203.0.113.1")));
    }

    @Test
    void theRightMostUntrustedHopIsTheClientNotTheFirst() throws Exception {
        TrustedProxies t = TrustedProxies.parse("10.0.0.0/8, 192.168.1.5");
        // The client prepended a forged 1.2.3.4; the real client is the hop the edge proxy appended.
        assertEquals("203.0.113.7", t.clientIp(ip("10.0.0.2"),
                List.of("1.2.3.4, 203.0.113.7, 192.168.1.5")));
        assertEquals("203.0.113.7", t.clientIp(ip("10.0.0.2"), List.of("1.2.3.4", "203.0.113.7, 10.9.9.9")),
                "multiple headers concatenate in arrival order");
        assertEquals("10.0.0.3", t.clientIp(ip("10.0.0.2"), List.of("10.0.0.3")),
                "every hop trusted -> the left-most one");
        assertEquals("10.0.0.2", t.clientIp(ip("10.0.0.2"), null), "no header -> the peer");
    }

    @Test
    void aNonLiteralHopStopsAtTheLastVouchedAddressAndIsNeverResolved() throws Exception {
        TrustedProxies t = TrustedProxies.parse("10.0.0.0/8");
        assertEquals("10.0.0.2", t.clientIp(ip("10.0.0.2"), List.of("evil.example.com")));
        assertEquals("10.4.4.4", t.clientIp(ip("10.0.0.2"), List.of("999.1.1.1, 10.4.4.4")),
                "an out-of-range dotted quad is garbage, not a hostname to look up");
    }

    @Test
    void cidrMatchingIsBitExactAndFamilySpecific() throws Exception {
        TrustedProxies t = TrustedProxies.parse("172.16.0.0/12, fd00::/8");
        assertTrue(t.trusts(ip("172.31.255.255")));
        assertFalse(t.trusts(ip("172.32.0.0")));
        assertTrue(t.trusts(ip("fd12:3456::1")));
        assertFalse(t.trusts(ip("fe80::1")));
        assertTrue(TrustedProxies.parse("0.0.0.0/0").trusts(ip("8.8.8.8")));
        assertFalse(TrustedProxies.parse("0.0.0.0/0").trusts(ip("::2")), "an IPv4 range never matches IPv6");
    }

    @Test
    void anUnparseableEntryIsRefused() {
        for (String bad : new String[] {"proxy.internal", "10.0.0.0/33", "10.0.0.0/x", "::1/129", "10.0.0"})
            assertThrows(IllegalArgumentException.class, () -> TrustedProxies.parse(bad), bad);
    }
}
