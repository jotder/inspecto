package com.gamma.job;

import com.gamma.signal.Severity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Trust gate T1 (parser-plugins-trust-design.md slice P1, operator D2/D3 2026-09-25): a Job Pack loads
 * only when the SHA-256 of the exact staged bytes the loader reads is listed in the operator-owned
 * {@code -Djobs.packs.allowlist} file. Packs dir set with no allowlist ⇒ every jar is refused.
 *
 * <p>Every negative here is paired with a probe that would otherwise succeed: the same jar loads when its
 * hash IS listed, and the one-byte-mutated jar is itself a VALID pack that loads once ITS hash is listed —
 * so a refusal can only be the gate, never a broken fixture.
 */
class JobPackTrustTest {

    private static final String PROP = "jobs.packs.allowlist";

    /** Records every signal with its payload. */
    private static final class Sink implements JobPackManager.SignalSink {
        final List<Map<String, Object>> rejected = new CopyOnWriteArrayList<>();
        final List<String> types = new CopyOnWriteArrayList<>();
        @Override public void emit(String type, Severity sev, Map<String, Object> payload) {
            types.add(type);
            if ("job.pack.rejected".equals(type)) rejected.add(payload);
        }
    }

    @TempDir static Path shared;
    private static Path goodJar, mutatedJar;

    @BeforeAll
    static void buildJars() throws Exception {
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "needs a JDK (javac) to build the pack jar");
        goodJar = JobPackManagerTest.buildPackJar(shared, shared.resolve("good.jar"),
                "acme.trusted", "acme.trusted", "TrustedType", "acme-trusted", "1.0.0");
        mutatedJar = shared.resolve("mutated.jar");
        Files.write(mutatedJar, flipOneCentralDirectoryByte(Files.readAllBytes(goodJar)));
        // The fixture's own sanity: exactly one byte differs, and the mutant is still a readable jar.
        byte[] a = Files.readAllBytes(goodJar), b = Files.readAllBytes(mutatedJar);
        assertEquals(a.length, b.length);
        int diff = 0;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) diff++;
        assertEquals(1, diff, "the mutant differs from the trusted jar by exactly one byte");
        try (JarFile jf = new JarFile(mutatedJar.toFile())) { assertNotNull(jf.getManifest()); }
    }

    @BeforeEach
    void narrowRoots() {
        // The surefire sandbox makes java.io.tmpdir a safety root; every allowlist here lives in a temp dir.
        JobPackManagerTest.narrowSafetyRoots(shared.resolve("jail"));
    }

    @AfterEach
    void clearProperties() {
        JobPackManagerTest.clearTrust();
        System.clearProperty("assist.write.root");
    }

    // ── positive probe ────────────────────────────────────────────────────────────────────────────

    @Test
    void anAllowlistedJarLoadsAndTheInventoryReportsTheListedHash(@TempDir Path work) throws Exception {
        Path packs = packsWith(work, goodJar);
        Path allow = allowlist(work, sha(goodJar) + "  pack.jar  vetted 2026-09-25");
        System.setProperty(PROP, allow.toString());
        JobTypeRegistry registry = new JobTypeRegistry();
        try (JobPackManager mgr = manager(packs, registry, new Sink())) {
            assertEquals(List.of("pack.jar"), mgr.rescan().get("loaded"));
            assertTrue(registry.has("acme.trusted"));
            Map<String, Object> row = mgr.inventory().get(0);
            assertEquals("loaded", row.get("state"));
            assertEquals(sha(goodJar), row.get("hash"));
        }
    }

    @Test
    void theOneByteMutantIsItselfALoadablePackWhenItsOwnHashIsListed(@TempDir Path work) throws Exception {
        // Proves the refusal below is the gate: nothing about the mutant's bytes is unloadable.
        Path packs = packsWith(work, mutatedJar);
        System.setProperty(PROP, allowlist(work, sha(mutatedJar) + " pack.jar").toString());
        JobTypeRegistry registry = new JobTypeRegistry();
        try (JobPackManager mgr = manager(packs, registry, new Sink())) {
            assertEquals(List.of("pack.jar"), mgr.rescan().get("loaded"));
            assertTrue(registry.has("acme.trusted"));
        }
    }

    // ── negatives ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void theSameJarWithOneByteChangedIsRefused(@TempDir Path work) throws Exception {
        Path packs = packsWith(work, mutatedJar);
        System.setProperty(PROP, allowlist(work, sha(goodJar) + "  pack.jar").toString());
        JobTypeRegistry registry = new JobTypeRegistry();
        Sink sink = new Sink();
        try (JobPackManager mgr = manager(packs, registry, sink)) {
            assertEquals(List.of("pack.jar"), mgr.rescan().get("rejected"));
            assertFalse(registry.has("acme.trusted"), "an unlisted hash never registers");
            assertEquals(1, sink.rejected.size());
            assertEquals("not trusted: sha256 " + sha(mutatedJar) + " is not in jobs.packs.allowlist",
                    sink.rejected.get(0).get("cause"));
            assertEquals(sha(mutatedJar), sink.rejected.get(0).get("hash"));
            Map<String, Object> row = mgr.inventory().get(0);
            assertEquals("rejected", row.get("state"), "a refused jar is visible in the inventory");
            assertEquals("pack.jar", row.get("file"));
            assertEquals(sha(mutatedJar), row.get("hash"));
            assertEquals(sink.rejected.get(0).get("cause"), row.get("cause"));
        }
    }

    @Test
    void noAllowlistConfiguredRefusesEveryJar(@TempDir Path work) throws Exception {
        Path packs = packsWith(work, goodJar);
        JobTypeRegistry registry = new JobTypeRegistry();
        Sink sink = new Sink();
        try (JobPackManager mgr = manager(packs, registry, sink)) {
            mgr.scanAtStartup();
            assertFalse(registry.has("acme.trusted"), "packs dir set + no allowlist ⇒ nothing loads");
            assertEquals("not trusted: no jobs.packs.allowlist configured", sink.rejected.get(0).get("cause"));
            assertEquals("rejected", mgr.inventory().get(0).get("state"));
        }
    }

    @Test
    void aMissingAllowlistFileRefusesEveryJar(@TempDir Path work) throws Exception {
        Path packs = packsWith(work, goodJar);
        Path absent = work.resolve("trust").resolve("absent.allowlist");
        System.setProperty(PROP, absent.toString());
        Sink sink = new Sink();
        JobTypeRegistry registry = new JobTypeRegistry();
        try (JobPackManager mgr = manager(packs, registry, sink)) {
            assertEquals(List.of("pack.jar"), mgr.rescan().get("rejected"));
            assertFalse(registry.has("acme.trusted"));
            assertTrue(String.valueOf(sink.rejected.get(0).get("cause")).startsWith("not trusted: jobs.packs.allowlist "),
                    "names the unreadable allowlist: " + sink.rejected.get(0).get("cause"));
        }
    }

    @Test
    void aMalformedAllowlistLineRefusesEveryJarEvenAListedOne(@TempDir Path work) throws Exception {
        Path packs = packsWith(work, goodJar);
        System.setProperty(PROP, allowlist(work, sha(goodJar) + " pack.jar", "deadbeef  short.jar").toString());
        Sink sink = new Sink();
        JobTypeRegistry registry = new JobTypeRegistry();
        try (JobPackManager mgr = manager(packs, registry, sink)) {
            assertEquals(List.of("pack.jar"), mgr.rescan().get("rejected"));
            assertFalse(registry.has("acme.trusted"), "a half-readable allowlist is not a trust decision");
            assertTrue(String.valueOf(sink.rejected.get(0).get("cause")).contains("line 2"),
                    "names the bad line: " + sink.rejected.get(0).get("cause"));
        }
    }

    // ── rescan re-reads the allowlist: approve, then revoke ───────────────────────────────────────

    @Test
    void rescanReReadsTheAllowlistToApproveAndToRevoke(@TempDir Path work) throws Exception {
        Path packs = packsWith(work, goodJar);
        Path allow = allowlist(work);                                   // empty: nothing trusted yet
        System.setProperty(PROP, allow.toString());
        JobTypeRegistry registry = new JobTypeRegistry();
        Sink sink = new Sink();
        try (JobPackManager mgr = manager(packs, registry, sink)) {
            assertEquals(List.of("pack.jar"), mgr.rescan().get("rejected"));
            assertFalse(registry.has("acme.trusted"));

            Files.writeString(allow, sha(goodJar) + "  pack.jar\n");   // operator approves on the host
            assertEquals(List.of("pack.jar"), mgr.rescan().get("loaded"), "approval takes effect on rescan");
            assertTrue(registry.has("acme.trusted"));
            assertEquals("loaded", mgr.inventory().get(0).get("state"));

            Files.writeString(allow, "");                               // operator revokes
            Map<String, Object> summary = mgr.rescan();
            assertEquals(List.of("pack.jar"), summary.get("rejected"), "a revoked hash is refused on rescan");
            assertFalse(registry.has("acme.trusted"), "…and its types are taken back");
            assertEquals("rejected", mgr.inventory().get(0).get("state"));
            assertTrue(sink.types.contains("job.pack.unloaded"));

            Files.delete(packs.resolve("pack.jar"));
            mgr.rescan();
            assertTrue(mgr.inventory().isEmpty(), "a removed jar leaves no rejected row behind");
        }
    }

    // ── TOCTOU: the hash that is checked is the hash of the bytes that are loaded ──────────────────

    @Test
    void aJarSwappedBeforeStagingIsHashedAsTheSwapAndRefused(@TempDir Path work) throws Exception {
        Path packs = packsWith(work, goodJar);
        System.setProperty(PROP, allowlist(work, sha(goodJar) + "  pack.jar").toString());
        JobTypeRegistry registry = new JobTypeRegistry();
        Sink sink = new Sink();
        try (JobPackManager mgr = manager(packs, registry, sink)) {
            // rescan hashed the vetted watched jar; the swap lands before the copy the loader reads.
            mgr.beforeStage = () -> copy(mutatedJar, packs.resolve("pack.jar"));
            assertEquals(List.of("pack.jar"), mgr.rescan().get("rejected"));
            assertFalse(registry.has("acme.trusted"), "the swapped-in bytes never load");
            assertEquals(sha(mutatedJar), sink.rejected.get(0).get("hash"),
                    "the refusal names the hash of the bytes that would have loaded");
        }
    }

    @Test
    void aJarSwappedAfterStagingLoadsOnlyTheVettedBytesAndTheSwapIsRefusedOnRescan(@TempDir Path work)
            throws Exception {
        Path packs = packsWith(work, goodJar);
        System.setProperty(PROP, allowlist(work, sha(goodJar) + "  pack.jar").toString());
        JobTypeRegistry registry = new JobTypeRegistry();
        try (JobPackManager mgr = manager(packs, registry, new Sink())) {
            mgr.afterStage = () -> copy(mutatedJar, packs.resolve("pack.jar"));
            assertEquals(List.of("pack.jar"), mgr.rescan().get("loaded"));
            assertEquals(sha(goodJar), mgr.inventory().get(0).get("hash"), "what loaded is the staged, vetted copy");

            mgr.afterStage = () -> {};
            assertEquals(List.of("pack.jar"), mgr.rescan().get("rejected"), "the swapped watched jar is then refused");
            assertFalse(registry.has("acme.trusted"));
        }
    }

    private static void copy(Path from, Path to) {
        try {
            Files.copy(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── A3: the allowlist must not be writable through the control plane or the packs dir ─────────

    @Test
    void anAllowlistInsideThePacksDirRefusesBoot(@TempDir Path work) throws Exception {
        Path packs = packsWith(work, goodJar);
        Path inside = Files.writeString(packs.resolve("allowlist.txt"), sha(goodJar) + " pack.jar\n");
        System.setProperty(PROP, inside.toString());
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> manager(packs, new JobTypeRegistry(), new Sink()));
        assertTrue(e.getMessage().contains("jobs.packs.allowlist"), e.getMessage());
    }

    @Test
    void anAllowlistUnderTheControlPlaneWriteRootRefusesBoot(@TempDir Path work) throws Exception {
        Path packs = packsWith(work, goodJar);
        Path writeRoot = Files.createDirectories(work.resolve("config"));
        Path under = Files.writeString(writeRoot.resolve("allowlist.txt"), sha(goodJar) + " pack.jar\n");
        System.setProperty("assist.write.root", writeRoot.toString());
        System.setProperty(PROP, under.toString());
        assertThrows(IllegalStateException.class, () -> manager(packs, new JobTypeRegistry(), new Sink()));

        // The positive twin: the same file outside the write root boots and loads.
        System.setProperty(PROP, allowlist(work, sha(goodJar) + " pack.jar").toString());
        JobTypeRegistry registry = new JobTypeRegistry();
        try (JobPackManager mgr = manager(packs, registry, new Sink())) {
            assertEquals(List.of("pack.jar"), mgr.rescan().get("loaded"));
        }
    }

    @Test
    void anAllowlistUnderAPathJailSafetyRootRefusesBoot(@TempDir Path work) throws Exception {
        Path packs = packsWith(work, goodJar);
        Path allow = allowlist(work, sha(goodJar) + " pack.jar");
        System.setProperty(PROP, allow.toString());
        System.setProperty("assist.safety.roots", allow.getParent().toString());
        assertThrows(IllegalStateException.class, () -> manager(packs, new JobTypeRegistry(), new Sink()));
    }

    // ── fixture ──────────────────────────────────────────────────────────────────────────────────────

    private static JobPackManager manager(Path packs, JobTypeRegistry registry, Sink sink) {
        return new JobPackManager(packs.toString(), registry, ExpressionRegistry.withBuiltins(), sink);
    }

    private static Path packsWith(Path work, Path jar) throws Exception {
        Path packs = Files.createDirectories(work.resolve("packs"));
        Files.copy(jar, packs.resolve("pack.jar"));
        return packs;
    }

    /** An allowlist OUTSIDE the packs dir, one line per argument. */
    private static Path allowlist(Path work, String... lines) throws Exception {
        Path dir = Files.createDirectories(work.resolve("trust"));
        return Files.writeString(dir.resolve("packs.allowlist"),
                lines.length == 0 ? "" : String.join("\n", lines) + "\n");
    }

    static String sha(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    /** Increment the low byte of the FIRST central-directory entry's last-mod-time: a one-byte change that
     *  keeps the jar valid (the JDK reads entry metadata from the central directory and never cross-checks
     *  the local header's time), so the mutant is still a loadable pack. */
    private static byte[] flipOneCentralDirectoryByte(byte[] jar) {
        ByteBuffer bb = ByteBuffer.wrap(jar).order(ByteOrder.LITTLE_ENDIAN);
        int eocd = jar.length - 22;
        assertEquals(0x06054b50, bb.getInt(eocd), "jar has no archive comment, so EOCD is the last 22 bytes");
        int cd = bb.getInt(eocd + 16);
        assertEquals(0x02014b50, bb.getInt(cd), "central directory entry signature");
        byte[] out = jar.clone();
        out[cd + 12] = (byte) (out[cd + 12] ^ 0x01);
        return out;
    }
}
