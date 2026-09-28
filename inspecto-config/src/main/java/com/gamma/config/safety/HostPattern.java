package com.gamma.config.safety;

import java.math.BigInteger;
import java.net.IDN;
import java.net.InetAddress;
import java.util.Locale;

/**
 * One entry of a Safety Policy host set ({@code allow.hosts} / {@code deny.hosts}) — the D13 grammar:
 * an <b>exact host</b>, a <b>{@code *.suffix}</b> matched at a dot boundary, or a <b>CIDR</b> (or a bare
 * IP literal, a one-address CIDR). No regex. Hosts compare case-folded, IDNA-normalised ASCII, never as raw
 * strings, so {@code *.ex.com} does not match {@code evilex.com} or {@code ex.com.evil.net}.
 *
 * <p>⚠ Parsing never touches DNS: an IP literal is recognised with {@link InetAddress#ofLiteral}, which
 * refuses host names instead of resolving them. The resolved-address deny check at connect time
 * ({@link SafetyPolicyTier#deniesAddress}) is the only place an address is compared.
 */
public record HostPattern(Kind kind, String host, BigInteger network, int prefixBits, int addrBits) {

    public enum Kind { EXACT, SUFFIX, CIDR }

    /** Parses one entry; throws {@link IllegalArgumentException} on anything outside the grammar. */
    public static HostPattern parse(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("empty host entry");
        String s = raw.trim();
        int slash = s.indexOf('/');
        if (slash >= 0 || looksLiteral(s)) {
            String addr = slash >= 0 ? s.substring(0, slash) : s;
            InetAddress a = literal(addr);
            if (a == null) throw new IllegalArgumentException("not an IP literal: " + raw);
            int bits = a.getAddress().length * 8;
            int prefix = bits;
            if (slash >= 0) {
                try {
                    prefix = Integer.parseInt(s.substring(slash + 1));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("bad CIDR prefix: " + raw);
                }
                if (prefix < 0 || prefix > bits) throw new IllegalArgumentException("bad CIDR prefix: " + raw);
            }
            return new HostPattern(Kind.CIDR, null, mask(new BigInteger(1, a.getAddress()), prefix, bits), prefix, bits);
        }
        if (s.startsWith("*.")) {
            String suffix = normalizeHost(s.substring(2));
            if (suffix.isEmpty() || suffix.contains("*")) throw new IllegalArgumentException("bad host suffix: " + raw);
            return new HostPattern(Kind.SUFFIX, suffix, null, 0, 0);
        }
        String h = normalizeHost(s);
        if (h.isEmpty() || h.contains("*")) throw new IllegalArgumentException("bad host: " + raw);
        return new HostPattern(Kind.EXACT, h, null, 0, 0);
    }

    /** Case-folded, IDNA ASCII, trailing dot removed. Throws on a name IDNA refuses. */
    public static String normalizeHost(String host) {
        String h = host.trim();
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        if (looksLiteral(h)) {
            InetAddress a = literal(h);
            return a == null ? h.toLowerCase(Locale.ROOT) : a.getHostAddress();
        }
        return IDN.toASCII(h, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
    }

    /** Whether this entry matches a concrete host (a name or an IP literal) — never resolves. */
    public boolean matches(String rawHost) {
        String h = normalizeHost(rawHost);
        return switch (kind) {
            case EXACT -> h.equals(host);
            case SUFFIX -> h.endsWith("." + host);
            case CIDR -> {
                InetAddress a = looksLiteral(h) ? literal(h) : null;
                yield a != null && matches(a);
            }
        };
    }

    /** CIDR entries only: whether a resolved address lies in the block. */
    public boolean matches(InetAddress a) {
        if (kind != Kind.CIDR || a.getAddress().length * 8 != addrBits) return false;
        return mask(new BigInteger(1, a.getAddress()), prefixBits, addrBits).equals(network);
    }

    /** {@code this} covers {@code other}: every host {@code other} matches, {@code this} matches too. */
    public boolean covers(HostPattern other) {
        return switch (kind) {
            case EXACT -> other.kind == Kind.EXACT && other.host.equals(host);
            case SUFFIX -> other.kind != Kind.CIDR && other.host.endsWith("." + host);
            case CIDR -> other.kind == Kind.CIDR && other.addrBits == addrBits && other.prefixBits >= prefixBits
                    && mask(other.network, prefixBits, addrBits).equals(network);
        };
    }

    @Override
    public String toString() {
        return switch (kind) {
            case EXACT -> host;
            case SUFFIX -> "*." + host;
            case CIDR -> {
                byte[] b = network.toByteArray();
                byte[] out = new byte[addrBits / 8];
                int n = Math.min(b.length, out.length);
                System.arraycopy(b, b.length - n, out, out.length - n, n);
                try {
                    yield InetAddress.getByAddress(out).getHostAddress() + "/" + prefixBits;
                } catch (java.net.UnknownHostException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    private static boolean looksLiteral(String s) {
        return s.contains(":") || s.matches("[0-9.]+");
    }

    private static InetAddress literal(String s) {
        try {
            return InetAddress.ofLiteral(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static BigInteger mask(BigInteger v, int prefix, int bits) {
        return v.shiftRight(bits - prefix).shiftLeft(bits - prefix);
    }
}
