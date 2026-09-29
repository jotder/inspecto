package com.gamma.security;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Writes a tiny, REAL MaxMind DB file (format spec v2.0, IPv4 tree, 24-bit records) at test time, so the
 * resolver is tested against the actual {@code maxmind-db} Reader rather than a mock. MaxMind's writer is not in
 * the offline cache and the approved {@code maxmind-db} jar ships no fixture, so this encodes the few types a
 * GeoLite2-Country record needs by hand.
 */
public final class TestMmdb {

    public static final long BUILD_EPOCH = 1_760_000_000L;

    private TestMmdb() {}

    /** Writes a Country database mapping each IPv4 /prefix (e.g. {@code "198.51.100.0/24"}) to an ISO code. */
    public static Path write(Path file, Map<String, String> prefixToIso) throws IOException {
        final int empty = Integer.MIN_VALUE;
        List<int[]> nodes = new ArrayList<>();
        nodes.add(new int[]{empty, empty});
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (var e : prefixToIso.entrySet()) {
            String[] p = e.getKey().split("/");
            byte[] a = java.net.InetAddress.ofLiteral(p[0]).getAddress();
            int bits = Integer.parseInt(p[1]);
            int offset = data.size();
            // {"country": {"iso_code": "<iso>"}, "city": {"names": {"en": "Nowhere"}}} — the city is there so a
            // test proves the resolver never reads it.
            data.write(0xE2);
            str(data, "country"); data.write(0xE1); str(data, "iso_code"); str(data, e.getValue());
            str(data, "city"); data.write(0xE1); str(data, "names"); data.write(0xE1); str(data, "en"); str(data, "Nowhere");
            int node = 0;
            for (int i = 0; i < bits; i++) {
                int bit = (a[i / 8] >> (7 - i % 8)) & 1;
                if (i == bits - 1) { nodes.get(node)[bit] = -(offset + 1); break; }
                if (nodes.get(node)[bit] == empty) { nodes.add(new int[]{empty, empty}); nodes.get(node)[bit] = nodes.size() - 1; }
                node = nodes.get(node)[bit];
            }
        }
        int n = nodes.size();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int[] rec : nodes) for (int r : rec) {
            int v = r == empty ? n : r < 0 ? n + 16 + (-r - 1) : r;
            out.write(v >> 16); out.write(v >> 8); out.write(v);
        }
        out.write(new byte[16]);
        data.writeTo(out);
        out.write(new byte[]{(byte) 0xAB, (byte) 0xCD, (byte) 0xEF});
        out.write("MaxMind.com".getBytes(StandardCharsets.US_ASCII));
        out.write(0xE9);
        str(out, "node_count"); out.write(0xC4); be(out, n, 4);
        str(out, "record_size"); out.write(0xA1); out.write(24);
        str(out, "ip_version"); out.write(0xA1); out.write(4);
        str(out, "database_type"); str(out, "Test-Country");
        str(out, "languages"); out.write(0x01); out.write(0x04); str(out, "en");
        str(out, "binary_format_major_version"); out.write(0xA1); out.write(2);
        str(out, "binary_format_minor_version"); out.write(0xA0);
        str(out, "build_epoch"); out.write(0x08); out.write(0x02); be(out, BUILD_EPOCH, 8);
        str(out, "description"); out.write(0xE1); str(out, "en"); str(out, "test fixture");
        Files.write(file, out.toByteArray());
        return file;
    }

    private static void str(ByteArrayOutputStream o, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        o.write(0x40 | b.length);   // type 2 (UTF-8 string), length < 29
        o.write(b, 0, b.length);
    }

    private static void be(ByteArrayOutputStream o, long v, int bytes) {
        for (int i = bytes - 1; i >= 0; i--) o.write((int) (v >> (8 * i)));
    }
}
