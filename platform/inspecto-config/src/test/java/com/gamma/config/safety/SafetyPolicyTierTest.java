package com.gamma.config.safety;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Safety Policy fold (design §3): the worked examples E1–E12, the boundary matchers (§3.3), and the
 * monotonicity property (T13). Every negative sits beside a twin that must succeed.
 */
class SafetyPolicyTierTest {

    private static SafetyPolicyTier hosts(List<String> allow, List<String> deny) {
        return new SafetyPolicyTier(null, null, null, null, null, null,
                allow == null ? null : allow.stream().map(HostPattern::parse).toList(), null, null, null, null,
                deny == null ? null : deny.stream().map(HostPattern::parse).toList(), null, null, null);
    }

    private static SafetyPolicyTier roots(List<Path> allow, List<Path> deny) {
        return new SafetyPolicyTier(null, null, null, null, null, allow, null, null, null, null, deny, null,
                null, null, null);
    }

    private static SafetyPolicyTier network(Boolean n, Boolean ext) {
        return new SafetyPolicyTier(null, n, ext, null, null, null, null, null, null, null, null, null,
                null, null, null);
    }

    private static SafetyPolicyTier threads(Integer t) {
        return new SafetyPolicyTier(null, null, null, null, null, null, null, null, null, null, null, null,
                t, null, null);
    }

    @Test
    void e1_aSpaceCannotAddAHost_T1() {
        SafetyPolicyTier e = SafetyPolicyTier.fold(hosts(List.of("127.0.0.1"), null), hosts(List.of("127.0.0.1", "localhost"), null));
        assertFalse(e.permitsHost("localhost"));
        assertTrue(e.permitsHost("127.0.0.1"));
        // twin: the server allows localhost too
        assertTrue(SafetyPolicyTier.fold(hosts(List.of("127.0.0.1", "localhost"), null),
                hosts(List.of("127.0.0.1", "localhost"), null)).permitsHost("localhost"));
    }

    @Test
    void e2_anAbsentServerSetIsTopSoTheSpaceNarrowsFreely() {
        SafetyPolicyTier e = SafetyPolicyTier.fold(SafetyPolicyTier.NONE, hosts(List.of("a.ex"), null));
        assertTrue(e.permitsHost("a.ex"));
        assertFalse(e.permitsHost("b.ex"));
    }

    @Test
    void e3_anEmptyAllowSetMeansNothing_whileAbsentMeansAnything_T3() {
        assertFalse(SafetyPolicyTier.fold(hosts(List.of("a.ex"), null), hosts(List.of(), null)).permitsHost("a.ex"));
        assertTrue(SafetyPolicyTier.fold(hosts(List.of("a.ex"), null), SafetyPolicyTier.NONE).permitsHost("a.ex"));
    }

    @Test
    void e4_denySetsUnion_T2() {
        SafetyPolicyTier e = SafetyPolicyTier.fold(hosts(null, List.of("x.ex")), hosts(null, List.of("127.0.0.1")));
        assertFalse(e.permitsHost("x.ex"));
        assertFalse(e.permitsHost("127.0.0.1"));
        assertTrue(e.permitsHost("z.ex"));
        assertTrue(SafetyPolicyTier.fold(SafetyPolicyTier.NONE, SafetyPolicyTier.NONE).permitsHost("127.0.0.1"));
    }

    @Test
    void e5_e6_permitsFoldByAnd_aSpaceCannotRegrant() {
        assertFalse(SafetyPolicyTier.fold(network(true, null), network(false, null)).permitsNetwork());
        assertFalse(SafetyPolicyTier.fold(network(true, null), network(false, null)).permitsHost("a.ex"));
        assertFalse(SafetyPolicyTier.fold(network(null, false), network(null, true)).permitsInstallExtensions());
        assertTrue(SafetyPolicyTier.fold(network(true, true), network(null, null)).permitsInstallExtensions());
    }

    @Test
    void e7_e8_e9_rootsIntersectComponentWise_T4(@TempDir Path tmp) {
        Path data = tmp.resolve("data"), s1 = data.resolve("s1"), s10 = data.resolve("s10"), other = tmp.resolve("other");
        assertEquals(List.of(s1), SafetyPolicyTier.fold(roots(List.of(data), null), roots(List.of(s1, other), null)).allowRoots());
        assertEquals(List.of(s1), SafetyPolicyTier.fold(roots(List.of(s1), null), roots(List.of(data), null)).allowRoots());
        assertEquals(List.of(), SafetyPolicyTier.fold(roots(List.of(s1), null), roots(List.of(s10), null)).allowRoots());

        SafetyPolicyTier e = roots(List.of(s1), null);
        assertFalse(e.permitsPath(s10.resolve("x")), "/s1 must not contain /s10");
        assertTrue(e.permitsPath(s1.resolve("x")));
        assertFalse(roots(List.of(data), List.of(s1)).permitsPath(s1.resolve("x")), "deny beats allow");
        assertTrue(roots(List.of(data), List.of(s1)).permitsPath(s10.resolve("x")));
    }

    @Test
    void e10_hostSuffixMatchesAtADotBoundaryOnly_T4() {
        SafetyPolicyTier e = SafetyPolicyTier.fold(hosts(List.of("*.ex.test"), null), hosts(List.of("api.ex.test", "evil.net"), null));
        assertEquals("[api.ex.test]", e.allowHosts().toString());
        HostPattern suffix = HostPattern.parse("*.ex.test");
        assertTrue(suffix.matches("api.ex.test"));
        assertTrue(suffix.matches("API.Ex.Test."));
        assertFalse(suffix.matches("evilex.test"));
        assertFalse(suffix.matches("ex.test.evil"));
        assertFalse(suffix.matches("ex.test"));
    }

    @Test
    void e11_capsFoldByMin() {
        assertEquals(8, SafetyPolicyTier.fold(threads(8), threads(32)).capThreads(64));
        assertEquals(4, SafetyPolicyTier.fold(threads(8), threads(4)).capThreads(64));
        assertEquals(2, SafetyPolicyTier.fold(threads(8), threads(32)).capThreads(2), "the validator default stays the ceiling");
        assertEquals(64, SafetyPolicyTier.fold(SafetyPolicyTier.NONE, SafetyPolicyTier.NONE).capThreads(64));
    }

    @Test
    void e12_modeInASpaceTierIsRefused() {
        SafetyPolicyTier spaceWithMode = new SafetyPolicyTier(SafetyPolicyTier.Mode.ENFORCE, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> SafetyPolicyTier.fold(SafetyPolicyTier.NONE, spaceWithMode));
        SafetyPolicyTier serverAudit = new SafetyPolicyTier(SafetyPolicyTier.Mode.AUDIT, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
        assertEquals(SafetyPolicyTier.Mode.AUDIT, SafetyPolicyTier.fold(serverAudit, SafetyPolicyTier.NONE).effectiveMode());
    }

    @Test
    void cidrMatchesLiteralsAndResolvedAddressesButNeverResolvesANameItself() throws Exception {
        HostPattern meta = HostPattern.parse("169.254.0.0/16");
        assertTrue(meta.matches("169.254.169.254"));
        assertFalse(meta.matches("169.253.1.1"));
        assertFalse(meta.matches("localhost"), "a name never matches a CIDR by resolution at parse/match time");
        SafetyPolicyTier deny = hosts(null, List.of("169.254.0.0/16"));
        assertTrue(deny.deniesAddress(InetAddress.ofLiteral("169.254.169.254")));
        assertFalse(deny.deniesAddress(InetAddress.ofLiteral("10.0.0.1")));
        assertTrue(HostPattern.parse("10.0.0.0/8").covers(HostPattern.parse("10.1.0.0/16")));
        assertFalse(HostPattern.parse("10.1.0.0/16").covers(HostPattern.parse("10.0.0.0/8")));
        assertTrue(HostPattern.parse("::1").matches("[::1]"));
        assertThrows(IllegalArgumentException.class, () -> HostPattern.parse("10.0.0.0/33"));
        assertThrows(IllegalArgumentException.class, () -> HostPattern.parse("a.*.ex"));
        assertThrows(IllegalArgumentException.class, () -> HostPattern.parse("999.1.1.1"));
    }

    @Test
    void objectPrefixMatcherIsABoundaryMatch() {
        assertTrue(SafetyPolicyTier.objectPrefixCovers("bucket/in", "bucket/in/a"));
        assertTrue(SafetyPolicyTier.objectPrefixCovers("bucket/in/", "bucket/in"));
        assertFalse(SafetyPolicyTier.objectPrefixCovers("bucket/in", "bucket/inbox"));
        assertFalse(SafetyPolicyTier.objectPrefixCovers("bucket/in", "other/in/a"));
        assertTrue(SafetyPolicyTier.objectPrefixCovers("bucket", "bucket/anything"));
        assertFalse(SafetyPolicyTier.objectPrefixCovers("bucket/in/a", "bucket/in"));
    }

    @Test
    void setsAndFormatsIntersectCaseInsensitively() {
        SafetyPolicyTier s = new SafetyPolicyTier(null, null, null, null, null, null, null, Set.of("SFTP", "s3"),
                Set.of("ducklake"), Set.of("parquet", "csv"), null, null, null, null, null);
        SafetyPolicyTier w = new SafetyPolicyTier(null, null, null, null, null, null, null, Set.of("s3", "https"),
                null, Set.of("CSV"), null, null, null, null, null);
        SafetyPolicyTier e = SafetyPolicyTier.fold(s, w);
        assertTrue(e.permitsConnector("S3"));
        assertFalse(e.permitsConnector("https"));
        assertFalse(e.permitsConnector("sftp"));
        assertTrue(e.permitsExtension("DuckLake"));
        assertFalse(e.permitsExtension("httpfs"));
        assertTrue(e.permitsFormat("csv"));
        assertFalse(e.permitsFormat("PARQUET"));
    }

    // ---- T13: monotonicity -----------------------------------------------------------------------------

    private static final String[] HOST_ENTRIES = {"a.ex", "b.ex", "api.ex.com", "*.ex.com", "*.com", "10.0.0.0/8",
            "10.1.2.3", "127.0.0.1", "169.254.0.0/16"};
    private static final String[] HOSTS = {"a.ex", "b.ex", "api.ex.com", "x.api.ex.com", "evilex.com", "ex.com",
            "10.1.2.3", "10.9.9.9", "127.0.0.1", "169.254.169.254", "c.org"};
    private static final String[] PATHS = {"d", "d/s1", "d/s10", "d/s1/x", "e", "e/y", "d/s10/z"};
    private static final String[] NAMES = {"sftp", "s3", "https", "kafka"};

    @Test
    void t13_aSpaceTierNeverWidensAnyVerdict(@TempDir Path tmp) {
        Random r = new Random(0x5AFE);
        int cases = 0;
        for (int i = 0; i < 2000; i++) {
            SafetyPolicyTier server = random(r, tmp, true);
            SafetyPolicyTier space = random(r, tmp, false);
            SafetyPolicyTier e = SafetyPolicyTier.fold(server, space);
            for (String h : HOSTS) {
                if (e.permitsHost(h)) assertTrue(server.permitsHost(h) && space.permitsHost(h), () -> fail(server, space, h));
                cases++;
            }
            for (String p : PATHS) {
                Path path = tmp.resolve(p);
                if (e.permitsPath(path)) assertTrue(server.permitsPath(path) && space.permitsPath(path), () -> fail(server, space, p));
                cases++;
            }
            for (String n : NAMES) {
                if (e.permitsConnector(n)) assertTrue(server.permitsConnector(n), () -> fail(server, space, n));
                cases++;
            }
            if (e.permitsInstallExtensions()) assertTrue(server.permitsInstallExtensions());
            if (e.permitsAdvanceState()) assertTrue(server.permitsAdvanceState());
            assertTrue(e.capThreads(1000) <= server.capThreads(1000));
            assertTrue(e.capBatchBytes(Long.MAX_VALUE) <= server.capBatchBytes(Long.MAX_VALUE));
        }
        assertTrue(cases >= 1000);
    }

    private static String fail(SafetyPolicyTier s, SafetyPolicyTier w, String act) {
        return "widened " + act + "\n server=" + s + "\n space=" + w;
    }

    private static SafetyPolicyTier random(Random r, Path tmp, boolean server) {
        return new SafetyPolicyTier(server && r.nextBoolean() ? SafetyPolicyTier.Mode.AUDIT : null,
                bool(r), bool(r), bool(r), bool(r),
                r.nextInt(3) == 0 ? null : pick(r, PATHS).stream().map(tmp::resolve).toList(),
                r.nextInt(3) == 0 ? null : pick(r, HOST_ENTRIES).stream().map(HostPattern::parse).toList(),
                r.nextInt(3) == 0 ? null : Set.copyOf(pick(r, NAMES)), null, null,
                r.nextInt(2) == 0 ? null : pick(r, PATHS).stream().map(tmp::resolve).toList(),
                r.nextInt(2) == 0 ? null : pick(r, HOST_ENTRIES).stream().map(HostPattern::parse).toList(),
                r.nextBoolean() ? null : 1 + r.nextInt(64), null, r.nextBoolean() ? null : (long) r.nextInt(1 << 20));
    }

    private static Boolean bool(Random r) {
        int v = r.nextInt(3);
        return v == 0 ? null : v == 1;
    }

    private static List<String> pick(Random r, String[] from) {
        List<String> out = new ArrayList<>();
        for (String s : from) if (r.nextInt(3) == 0) out.add(s);
        return out;
    }
}
