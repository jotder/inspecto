package com.gamma.entitystore;

import com.gamma.control.EntityTypes;

import java.util.HexFormat;
import java.util.Map;

/**
 * Range entries of an Entity List (ASSURE-ENTITY-LISTS-1, WS-12): the entries an equal-key join cannot express.
 * Each is stored as ONE canonical string, so the fold keeps them as a set exactly like exact keys:
 *
 * <ul>
 *   <li>{@code prefix:<key>} ({@code {prefix: "4478"}}): a key that starts with the prefix, both under the list's
 *       sealed normaliser;</li>
 *   <li>{@code range:<lo>..<hi>} ({@code {from, to}}): a key of the SAME length as the bounds with
 *       {@code lo <= key <= hi}. Equal length keeps a number block a number block: "44785" is not inside
 *       "447800".."447899" although it sorts between them;</li>
 *   <li>{@code cidr:<network>/<len>} ({@code {cidr: "10.0.0.0/8"}}): an IPv4 or IPv6 address inside the block. The
 *       network must have no host bits set (a typo guard, 422). An IPv4-mapped IPv6 value ({@code ::ffff:a.b.c.d}) is
 *       read as the IPv4 address, both as a block and as a candidate, so one block matches both spellings. Zone ids
 *       ({@code %eth0}) are not addresses.</li>
 * </ul>
 * CIDR is matched on the raw value (trimmed), never the list's normaliser: an address is not an entity key.
 */
public final class EntityListEntries {

    private EntityListEntries() {}

    /** A parsed range entry; {@code lo}/{@code hi} are the sidecar's bounds (fixed-width hex for CIDR). */
    public record Range(String canonical, String match, String lo, String hi) {}

    /** The canonical entry of one authored range object, or IllegalArgumentException naming what is wrong. */
    public static String canonical(Map<?, ?> spec, String normaliser) {
        if (spec.size() == 1 && spec.get("prefix") instanceof String p) {
            String k = EntityTypes.normalise(normaliser, p);
            if (k.isEmpty()) throw new IllegalArgumentException("prefix is empty after the list's normaliser");
            return "prefix:" + k;
        }
        if (spec.size() == 2 && spec.get("from") instanceof String f && spec.get("to") instanceof String t) {
            String lo = EntityTypes.normalise(normaliser, f), hi = EntityTypes.normalise(normaliser, t);
            if (lo.isEmpty() || hi.isEmpty()) throw new IllegalArgumentException("range bounds are empty after the list's normaliser");
            if (lo.length() != hi.length())
                throw new IllegalArgumentException("range bounds must have the same length after normalising ('" + lo
                        + "' vs '" + hi + "')");
            if (lo.compareTo(hi) > 0) throw new IllegalArgumentException("range 'from' is above 'to'");
            if (lo.contains("..")) throw new IllegalArgumentException("range bounds may not contain '..'");
            return "range:" + lo + ".." + hi;
        }
        if (spec.size() == 1 && spec.get("cidr") instanceof String c) return "cidr:" + cidr(c.trim());
        throw new IllegalArgumentException("a range entry is exactly one of {prefix}, {from, to} or {cidr}");
    }

    /** Parse a stored canonical entry back into its bounds. */
    public static Range parse(String canonical) {
        if (canonical.startsWith("prefix:")) {
            String p = canonical.substring(7);
            return new Range(canonical, "prefix", p, p);
        }
        if (canonical.startsWith("range:")) {
            String body = canonical.substring(6);
            int dots = body.indexOf("..");
            return new Range(canonical, "range", body.substring(0, dots), body.substring(dots + 2));
        }
        if (canonical.startsWith("cidr:")) {
            String body = canonical.substring(5);
            int slash = body.lastIndexOf('/');
            byte[] net = ip(body.substring(0, slash));
            int len = Integer.parseInt(body.substring(slash + 1));
            byte[] hi = net.clone();
            for (int bit = len; bit < net.length * 8; bit++) hi[bit / 8] |= (byte) (0x80 >>> (bit % 8));
            return new Range(canonical, "cidr", HEX.formatHex(net), HEX.formatHex(hi));
        }
        throw new IllegalArgumentException("not a range entry: " + canonical);
    }

    /** Whether {@code range} admits a candidate whose normalised key is {@code key} and whose raw value is {@code raw}. */
    static boolean matches(Range range, String key, String raw) {
        return switch (range.match()) {
            case "prefix" -> !key.isEmpty() && key.startsWith(range.lo());
            case "range" -> key.length() == range.lo().length() && key.compareTo(range.lo()) >= 0
                    && key.compareTo(range.hi()) <= 0;
            case "cidr" -> {
                byte[] a = ipOrNull(raw == null ? null : raw.trim());
                if (a == null) yield false;
                String h = HEX.formatHex(a);
                yield h.length() == range.lo().length() && h.compareTo(range.lo()) >= 0 && h.compareTo(range.hi()) <= 0;
            }
            default -> false;
        };
    }

    // ── IP addresses ──────────────────────────────────────────────────────────────────────────────────────

    private static final HexFormat HEX = HexFormat.of();

    /** {@code a.b.c.d/len} or {@code v6/len}, canonical: network text + '/' + len; host bits must be zero. */
    static String cidr(String text) {
        int slash = text.indexOf('/');
        if (slash < 0 || slash != text.lastIndexOf('/')) throw new IllegalArgumentException("cidr must be <address>/<length>");
        String lenText = text.substring(slash + 1);
        if (!lenText.matches("[0-9]{1,3}")) throw new IllegalArgumentException("cidr length must be a number");
        int len = Integer.parseInt(lenText);
        String addrText = text.substring(0, slash);
        byte[] a = ipOrNull(addrText);
        if (a == null) throw new IllegalArgumentException("'" + addrText + "' is not an IPv4 or IPv6 address");
        if (a.length == 4 && isMapped(addrText)) {
            if (len < 96) throw new IllegalArgumentException("an IPv4-mapped block needs a length of at least 96");
            len -= 96;
        }
        if (len > a.length * 8) throw new IllegalArgumentException("cidr length " + len + " exceeds " + (a.length * 8));
        for (int bit = len; bit < a.length * 8; bit++)
            if ((a[bit / 8] & (0x80 >>> (bit % 8))) != 0)
                throw new IllegalArgumentException("cidr '" + text + "' has host bits set; the network is " + text(mask(a, len)) + "/" + len);
        return text(a) + "/" + len;
    }

    private static boolean isMapped(String addrText) {
        return addrText.contains(":");
    }

    private static byte[] mask(byte[] a, int len) {
        byte[] out = a.clone();
        for (int bit = len; bit < a.length * 8; bit++) out[bit / 8] &= (byte) ~(0x80 >>> (bit % 8));
        return out;
    }

    /** Canonical text: dotted IPv4, or IPv6 as eight lower-case groups without leading zeros or '::'. */
    static String text(byte[] a) {
        StringBuilder sb = new StringBuilder();
        if (a.length == 4) {
            for (int i = 0; i < 4; i++) sb.append(i == 0 ? "" : ".").append(a[i] & 0xff);
            return sb.toString();
        }
        for (int i = 0; i < 16; i += 2) sb.append(i == 0 ? "" : ":").append(Integer.toHexString(((a[i] & 0xff) << 8) | (a[i + 1] & 0xff)));
        return sb.toString();
    }

    private static byte[] ip(String s) {
        byte[] a = ipOrNull(s);
        if (a == null) throw new IllegalArgumentException("not an address: " + s);
        return a;
    }

    /**
     * The address bytes (4 for IPv4 and for an IPv4-mapped IPv6 address, 16 otherwise), or {@code null}. A pure
     * parser: never {@code InetAddress}, which would resolve a host name.
     */
    static byte[] ipOrNull(String s) {
        if (s == null || s.isEmpty() || s.length() > 45) return null;
        if (!s.contains(":")) return v4(s);
        byte[] v6 = v6(s);
        if (v6 == null) return null;
        boolean mapped = true;
        for (int i = 0; i < 10; i++) mapped &= v6[i] == 0;
        mapped &= (v6[10] & 0xff) == 0xff && (v6[11] & 0xff) == 0xff;
        if (mapped) return new byte[]{v6[12], v6[13], v6[14], v6[15]};
        return v6;
    }

    private static byte[] v4(String s) {
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) return null;
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            if (!parts[i].matches("0|[1-9][0-9]{0,2}")) return null;   // no leading zeros: 010 is not 8 nor 10
            int v = Integer.parseInt(parts[i]);
            if (v > 255) return null;
            out[i] = (byte) v;
        }
        return out;
    }

    private static byte[] v6(String s) {
        int dc = s.indexOf("::");
        if (dc >= 0 && s.indexOf("::", dc + 1) >= 0) return null;
        String head = dc >= 0 ? s.substring(0, dc) : s;
        String tail = dc >= 0 ? s.substring(dc + 2) : "";
        java.util.List<Integer> h = groups(head), t = groups(tail);
        if (h == null || t == null) return null;
        int total = h.size() + t.size();
        if (dc < 0 ? total != 8 : total > 7) return null;
        byte[] out = new byte[16];
        int i = 0;
        for (int g : h) { out[i++] = (byte) (g >> 8); out[i++] = (byte) g; }
        i = 16 - t.size() * 2;
        for (int g : t) { out[i++] = (byte) (g >> 8); out[i++] = (byte) g; }
        return out;
    }

    /** Colon-separated 16-bit groups; a trailing dotted IPv4 counts as two. {@code null} when malformed. */
    private static java.util.List<Integer> groups(String s) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        if (s.isEmpty()) return out;
        String[] parts = s.split(":", -1);
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (i == parts.length - 1 && p.contains(".")) {
                byte[] v4 = v4(p);
                if (v4 == null) return null;
                out.add(((v4[0] & 0xff) << 8) | (v4[1] & 0xff));
                out.add(((v4[2] & 0xff) << 8) | (v4[3] & 0xff));
                continue;
            }
            if (!p.matches("[0-9a-fA-F]{1,4}")) return null;
            out.add(Integer.parseInt(p, 16));
        }
        return out;
    }
}
