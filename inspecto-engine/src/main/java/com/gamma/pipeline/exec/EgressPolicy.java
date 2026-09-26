package com.gamma.pipeline.exec;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * <b>The outbound egress policy</b> ({@code ASSURE-ACTION-REQUESTS-1}, verification finding 1): what an outbound
 * call may name as its host, and which addresses it may actually reach.
 *
 * <ol>
 *   <li><b>Host syntax</b> ({@link #checkHost}) — at Connection save, at Action Request create and whenever a
 *       Connection is resolved to a URL: the host must be a DNS name or a CANONICAL IPv4 / IPv6 literal. Refused:
 *       userinfo ({@code trusted.example@attacker.example}, which reads as one host and dials another), and every
 *       non-canonical numeric form an address parser may accept — decimal ({@code 2130706433}), octal
 *       ({@code 0177.0.0.1}), hex ({@code 0x7f000001}), short ({@code 127.1}), leading zeros — plus zone ids,
 *       ports, paths and whitespace.</li>
 *   <li><b>Address classes, deny by default</b> ({@link #deniedClass}) — at DISPATCH every address the host
 *       resolves to is checked: loopback, link-local ({@code 169.254.0.0/16} — the cloud metadata service — and
 *       {@code fe80::/10}), private ({@code 10/8}, {@code 172.16/12}, {@code 192.168/16}, {@code fc00::/7}),
 *       CGNAT {@code 100.64/10}, {@code 0/8}, broadcast, multicast, unspecified, and any address of THIS host (which
 *       covers every address the control plane can bind). A public address passes.</li>
 *   <li><b>A per-Space allowlist</b> ({@link Allowlist}) lifts the default for named targets, because real
 *       targets (a CBS, a PCRF) often live on private networks: a <b>host</b> entry lets that exact name reach the
 *       PRIVATE classes only (RFC 1918, {@code fc00::/7}, CGNAT) — never loopback, link-local or this host, so a
 *       name that is re-pointed (DNS rebinding) still cannot reach the metadata service; a <b>CIDR</b> entry lets
 *       anything inside that range through, except multicast and unspecified.</li>
 * </ol>
 * The caller then CONNECTS TO THE CHECKED ADDRESS ({@link #resolve} returns it), so a second resolution cannot
 * swap it — the transport keeps the original name for the Host header, SNI and certificate verification.
 *
 * <p>⚠ Embedded-IPv4 IPv6 forms other than IPv4-mapped (6to4, NAT64 {@code 64:ff9b::/96}) are classified as
 * IPv6 addresses — i.e. public. The allowlist is the place to be stricter if a network routes them.
 */
public final class EgressPolicy {

    private EgressPolicy() {}

    private static final Pattern LABEL = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");
    private static final String OCTET = "(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])";
    private static final Pattern CANONICAL_V4 = Pattern.compile(OCTET + "(\\." + OCTET + "){3}");

    /** Refused at dispatch: the host resolved to an address the policy does not allow. */
    public static final class Refused extends Exception {
        public Refused(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * Refuse {@code host} ({@link IllegalArgumentException}, naming why) unless it is a DNS name or a canonical
     * IPv4 / IPv6 literal, without userinfo. IPv6 may be bracketed.
     */
    public static void checkHost(String host) {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("the host is empty");
        String h = host.trim();
        if (!h.equals(host)) throw new IllegalArgumentException("the host '" + host + "' carries whitespace");
        if (h.indexOf('@') >= 0)
            throw new IllegalArgumentException("the host '" + host + "' carries userinfo ('@') — it would read as one "
                    + "host and dial another");
        if (h.startsWith("[") || h.indexOf(':') >= 0) {
            String inner = h.startsWith("[") && h.endsWith("]") ? h.substring(1, h.length() - 1) : h;
            if (inner.indexOf('%') >= 0 || inner.indexOf('[') >= 0 || inner.indexOf(']') >= 0 || inner.indexOf(':') < 0)
                throw new IllegalArgumentException("the host '" + host + "' is not a valid IPv6 literal (no zone ids, "
                        + "no port)");
            try {
                if (!(InetAddress.ofLiteral(inner) instanceof Inet6Address) && !inner.startsWith("::ffff:"))
                    throw new IllegalArgumentException("the host '" + host + "' is not an IPv6 literal");
            } catch (IllegalArgumentException bad) {
                throw new IllegalArgumentException("the host '" + host + "' is not a valid IPv6 literal");
            }
            return;
        }
        String lower = h.toLowerCase(Locale.ROOT);
        String[] labels = lower.split("\\.", -1);
        String last = labels[labels.length - 1];
        boolean numeric = last.chars().allMatch(Character::isDigit) || lower.startsWith("0x") || lower.contains(".0x");
        if (numeric) {
            if (!CANONICAL_V4.matcher(lower).matches())
                throw new IllegalArgumentException("the host '" + host + "' is a non-canonical numeric address — write "
                        + "an IPv4 address as four decimal octets (no decimal, octal, hex or short forms)");
            return;
        }
        if (lower.length() > 253) throw new IllegalArgumentException("the host '" + host + "' is longer than 253 chars");
        for (String l : labels)
            if (!LABEL.matcher(l).matches())
                throw new IllegalArgumentException("the host '" + host + "' is not a valid DNS name (label '" + l + "')");
    }

    /** Whether {@code host} is an IP literal (after {@link #checkHost}). */
    public static boolean isIpLiteral(String host) {
        return host.startsWith("[") || host.indexOf(':') >= 0 || CANONICAL_V4.matcher(host).matches();
    }

    /**
     * Why {@code a} is denied by default, or {@code null} for a public address. An IPv6 address that EMBEDS an IPv4
     * one is also classified by the IPv4 it carries (round-2 finding 2): IPv4-compatible {@code ::/96} (but {@code ::}
     * and {@code ::1}), IPv4-mapped {@code ::ffff:0:0/96}, NAT64 {@code 64:ff9b::/96}, local-use NAT64
     * {@code 64:ff9b:1::/48} (both RFC 6052 positions a /48 and a /96 prefix put it at) and 6to4 {@code 2002::/16}
     * (bits 16–47) — so {@code 64:ff9b::a9fe:a9fe} is the metadata service, not a public address.
     */
    public static String deniedClass(InetAddress a) {
        if (a instanceof Inet6Address) {
            for (InetAddress v4 : embeddedIpv4(a.getAddress())) {
                String cls = deniedClass(v4);
                if (cls != null) return cls;
            }
        }
        if (a.isAnyLocalAddress()) return "unspecified";
        if (a.isLoopbackAddress()) return "loopback";
        if (a.isLinkLocalAddress()) return "link-local";
        if (a.isMulticastAddress()) return "multicast";
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int o0 = b[0] & 0xff, o1 = b[1] & 0xff;
            if (o0 == 0) return "unspecified";
            if (o0 == 255 && (b[1] & 0xff) == 255 && (b[2] & 0xff) == 255 && (b[3] & 0xff) == 255) return "broadcast";
            if (o0 == 10 || (o0 == 172 && o1 >= 16 && o1 <= 31) || (o0 == 192 && o1 == 168)) return "private";
            if (o0 == 100 && o1 >= 64 && o1 <= 127) return "cgnat";
        } else if ((b[0] & 0xfe) == 0xfc) {
            return "private";
        } else if ((b[0] & 0xff) == 0xfe && (b[1] & 0xc0) == 0xc0) {
            return "private";   // fec0::/10, the deprecated site-local range
        }
        try {
            if (NetworkInterface.getByInetAddress(a) != null) return "this-host";
        } catch (SocketException ignored) {
            // cannot enumerate interfaces: the classes above still apply
        }
        return null;
    }

    /** The IPv4 addresses an IPv6 address carries, by the embeddings {@link #deniedClass} names; empty for none. */
    static List<InetAddress> embeddedIpv4(byte[] b) {
        List<InetAddress> out = new ArrayList<>();
        if (b.length != 16) return out;
        boolean zero0to9 = true;
        for (int i = 0; i < 10; i++) zero0to9 &= b[i] == 0;
        boolean zero10to11 = b[10] == 0 && b[11] == 0;
        boolean mapped = zero0to9 && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
        boolean compat = zero0to9 && zero10to11
                && !(b[12] == 0 && b[13] == 0 && b[14] == 0 && (b[15] == 0 || b[15] == 1));   // not :: or ::1
        boolean nat64 = b[0] == 0 && (b[1] & 0xff) == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b;
        boolean wellKnown = nat64 && allZero(b, 4, 12);
        boolean localUse = nat64 && b[4] == 0 && b[5] == 1;
        if (mapped || compat || wellKnown || localUse) out.add(v4(b[12], b[13], b[14], b[15]));
        if (localUse) out.add(v4(b[6], b[7], b[9], b[10]));   // RFC 6052 /48: bits 48–63 and 72–87 (u octet skipped)
        if ((b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x02) out.add(v4(b[2], b[3], b[4], b[5]));   // 6to4
        return out;
    }

    private static boolean allZero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) if (b[i] != 0) return false;
        return true;
    }

    private static InetAddress v4(byte a, byte b, byte c, byte d) {
        try {
            return InetAddress.getByAddress(new byte[] {a, b, c, d});
        } catch (UnknownHostException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final Set<String> PRIVATE_CLASSES = Set.of("private", "cgnat");

    /** A CIDR range, parsed from a canonical {@code address/bits}. */
    public record Cidr(byte[] network, int bits, String text) {
        public static Cidr parse(String s) {
            int slash = s.indexOf('/');
            if (slash < 0) throw new IllegalArgumentException("'" + s + "' is not a CIDR (address/bits)");
            String addr = s.substring(0, slash);
            checkHost(addr);
            if (!isIpLiteral(addr)) throw new IllegalArgumentException("'" + s + "': the network must be an IP literal");
            InetAddress a = InetAddress.ofLiteral(addr.startsWith("[") ? addr.substring(1, addr.length() - 1) : addr);
            int max = a.getAddress().length * 8;
            int bits;
            try {
                bits = Integer.parseInt(s.substring(slash + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'" + s + "': the prefix length must be a number");
            }
            if (bits < 1 || bits > max) throw new IllegalArgumentException("'" + s + "': the prefix length must be 1.." + max);
            return new Cidr(a.getAddress(), bits, s);
        }

        public boolean contains(InetAddress a) {
            byte[] x = a.getAddress();
            if (x.length != network.length) return false;
            for (int i = 0; i < bits; i++) {
                int mask = 0x80 >> (i % 8);
                if ((x[i / 8] & mask) != (network[i / 8] & mask)) return false;
            }
            return true;
        }
    }

    /** A per-Space allowlist: exact host names and CIDR ranges. See the class doc for what each lifts. */
    public record Allowlist(Set<String> hosts, List<Cidr> cidrs) {
        public static final Allowlist EMPTY = new Allowlist(Set.of(), List.of());

        /** Parse entries (a host name or an {@code address/bits}); an IP literal without a prefix is its /32 or /128. */
        public static Allowlist of(List<String> entries) {
            Set<String> hosts = new LinkedHashSet<>();
            List<Cidr> cidrs = new ArrayList<>();
            for (String raw : entries) {
                String e = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
                if (e.indexOf('/') >= 0) {
                    cidrs.add(Cidr.parse(e));
                } else {
                    checkHost(e);
                    if (isIpLiteral(e)) cidrs.add(Cidr.parse(e + (e.indexOf(':') >= 0 ? "/128" : "/32")));
                    else hosts.add(e);
                }
            }
            return new Allowlist(Set.copyOf(hosts), List.copyOf(cidrs));
        }

        /** Whether {@code host} is named by a host entry. */
        public boolean namesHost(String host) {
            return host != null && hosts.contains(host.toLowerCase(Locale.ROOT));
        }

        /** Whether this list lets {@code host} reach {@code a}, which is of denied class {@code cls}. */
        public boolean permits(String host, InetAddress a, String cls) {
            if ("multicast".equals(cls) || "unspecified".equals(cls) || "broadcast".equals(cls)) return false;
            for (Cidr c : cidrs) if (c.contains(a)) return true;
            return PRIVATE_CLASSES.contains(cls) && namesHost(host);
        }
    }

    /**
     * Resolve {@code host} ONCE and check EVERY address against the policy; returns the first address, which the
     * caller must connect to. {@link Refused} names the host, the address and its class.
     */
    public static InetAddress resolve(String host, Allowlist allow) throws Refused {
        String bare = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
        InetAddress[] all;
        try {
            all = InetAddress.getAllByName(bare);
        } catch (UnknownHostException e) {
            throw new Refused("the host '" + host + "' does not resolve");
        }
        for (InetAddress a : all) {
            String cls = deniedClass(a);
            if (cls != null && !allow.permits(host, a, cls))
                throw new Refused("the host '" + host + "' resolves to " + a.getHostAddress() + ", a " + cls
                        + " address the egress policy denies — add the host or its range to this Space's egress "
                        + "allowlist if it is a real target");
        }
        return all[0];
    }
}
