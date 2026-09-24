package com.gamma.control;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Which direct peers may speak for the client in {@code X-Forwarded-For} — {@code -Dcontrol.trustedProxies}
 * (SEC review F3, 2026-09-24): a comma-separated list of IP literals and CIDR ranges, IPv4 or IPv6
 * ({@code 10.0.0.0/8, 192.168.1.5, fd00::/8}).
 *
 * <p><b>Unset or blank ⇒ trust no one, and the client IP is the socket peer.</b> Until this existed
 * {@code ApiContext.ip} took the FIRST {@code X-Forwarded-For} entry from any caller, so anyone could choose
 * the IP written to the audit trail and mint a fresh per-IP rate-limit bucket per request.
 *
 * <p>With a list: XFF is read only when the direct peer is in it, and then <b>right to left</b>, skipping
 * trusted hops — the first untrusted hop is the client. The left end of the header is whatever the client
 * sent, so it is never believed merely for being first. A hop that is not an IP literal stops the walk at
 * the nearest address a trusted proxy vouched for. ⚠ Hops are parsed as literals only, never resolved:
 * a header value must not be able to trigger a DNS lookup.
 *
 * <p>An entry that does not parse <b>fails the boot</b> (like {@code -Dcontrol.bind}): a deployment that
 * cannot honour its stated trust list must not come up with a different one.
 */
final class TrustedProxies {

    static final String PROPERTY = "control.trustedProxies";

    /** Syntax gate before {@link InetAddress#getByName}: a strict dotted-quad, or hex-and-colons (which the
     *  JDK parses as an IPv6 literal or rejects — it never falls through to a name lookup for a value
     *  containing ':'). A loose IPv4 pattern would let {@code 999.1.1.1} reach the resolver as a hostname. */
    private static final String OCTET = "(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])";
    private static final Pattern IP_LITERAL = Pattern.compile(
            OCTET + "(\\." + OCTET + "){3}|[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*");

    private record Range(byte[] network, int prefix) {
        boolean contains(byte[] addr) {
            if (addr.length != network.length) return false;
            int full = prefix / 8, rest = prefix % 8;
            for (int i = 0; i < full; i++) if (addr[i] != network[i]) return false;
            if (rest == 0) return true;
            int mask = 0xFF << (8 - rest) & 0xFF;
            return (addr[full] & mask) == (network[full] & mask);
        }
    }

    private final List<Range> ranges;

    private TrustedProxies(List<Range> ranges) { this.ranges = List.copyOf(ranges); }

    /** The configured list; empty when the property is unset. Throws on any unparseable entry. */
    static TrustedProxies fromSystemProperty() {
        return parse(System.getProperty(PROPERTY));
    }

    static TrustedProxies parse(String csv) {
        List<Range> out = new ArrayList<>();
        if (csv != null) {
            for (String raw : csv.split(",")) {
                String entry = raw.trim();
                if (entry.isEmpty()) continue;
                int slash = entry.indexOf('/');
                InetAddress addr = literal(slash < 0 ? entry : entry.substring(0, slash));
                if (addr == null) throw new IllegalArgumentException("not an IP address or CIDR range: '" + entry + "'");
                int bits = addr.getAddress().length * 8, prefix = bits;
                if (slash >= 0) {
                    try {
                        prefix = Integer.parseInt(entry.substring(slash + 1));
                    } catch (NumberFormatException bad) {
                        prefix = -1;
                    }
                    if (prefix < 0 || prefix > bits)
                        throw new IllegalArgumentException("bad CIDR prefix in '" + entry + "' (0.." + bits + ")");
                }
                out.add(new Range(addr.getAddress(), prefix));
            }
        }
        return new TrustedProxies(out);
    }

    boolean isEmpty() { return ranges.isEmpty(); }

    boolean trusts(InetAddress addr) {
        if (addr == null) return false;
        byte[] bytes = addr.getAddress();
        for (Range r : ranges) if (r.contains(bytes)) return true;
        return false;
    }

    /**
     * The originating client for a request from {@code peer} carrying these {@code X-Forwarded-For} header
     * values (all of them, in arrival order — multiple headers concatenate). {@code null} only when the peer
     * address is unavailable.
     */
    String clientIp(InetAddress peer, List<String> forwardedFor) {
        if (peer == null) return null;
        String vouched = peer.getHostAddress();
        if (!trusts(peer) || forwardedFor == null || forwardedFor.isEmpty()) return vouched;
        List<String> hops = new ArrayList<>();
        for (String header : forwardedFor)
            for (String hop : header.split(",")) if (!hop.isBlank()) hops.add(hop.trim());
        for (int i = hops.size() - 1; i >= 0; i--) {
            InetAddress hop = literal(hops.get(i));
            if (hop == null) return vouched;              // garbage in the chain: stop at the last vouched hop
            vouched = hop.getHostAddress();
            if (!trusts(hop)) return vouched;             // right-most untrusted hop = the client
        }
        return vouched;                                   // every hop trusted: the left-most one
    }

    /** {@code s} as an address if it is an IP literal, else {@code null} — never a DNS lookup. */
    private static InetAddress literal(String s) {
        if (s == null || !IP_LITERAL.matcher(s).matches()) return null;
        try {
            return InetAddress.getByName(s);
        } catch (UnknownHostException bad) {
            return null;
        }
    }
}
