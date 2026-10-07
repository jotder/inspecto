package com.gamma.job;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TOCTOU pin for {@link JobPackManager}'s verify/stage/load sequence: the bytes that are hashed and
 * signature-verified must be exactly the bytes that are loaded. The manager stages first and verifies the
 * staged copy, so a watched jar swapped AFTER staging cannot reach the loader, and one swapped BEFORE
 * staging is what gets verified — and refused when unsigned, with its staged copy deleted.
 */
class JobPackStagingTest {

    @Test
    void aJarSwappedAfterStagingDoesNotChangeWhatIsVerifiedOrLoaded(@TempDir Path work) throws Exception {
        Fixture f = fixture(work);
        JobTypeRegistry registry = new JobTypeRegistry();
        try (JobPackManager mgr = signingManager(f.packs, registry)) {
            mgr.afterStage = () -> swap(f.unsigned, f.watched);

            Map<String, Object> summary = mgr.rescan();

            assertEquals(List.of("pack.jar"), summary.get("loaded"), "the staged signed bytes load");
            assertTrue(registry.has("acme.signed"), "the verified pack's type is what registered");
            assertFalse(registry.has("acme.evil"), "the jar swapped in after staging never loads");
            assertEquals(f.signedSha, mgr.inventory().get(0).get("hash"),
                    "the recorded sha256 is of the loaded (staged) bytes, not the swapped watched file");
        }
    }

    @Test
    void aJarSwappedBeforeStagingIsWhatGetsVerifiedAndAnUnsignedSwapIsRefused(@TempDir Path work)
            throws Exception {
        Fixture f = fixture(work);
        JobTypeRegistry registry = new JobTypeRegistry();
        try (JobPackManager mgr = signingManager(f.packs, registry)) {
            mgr.beforeStage = () -> swap(f.unsigned, f.watched);

            Map<String, Object> summary = mgr.rescan();

            assertEquals(List.of("pack.jar"), summary.get("rejected"), "the unsigned swap is verified and refused");
            assertFalse(registry.has("acme.evil"), "the unsigned jar never registers");
            assertFalse(registry.has("acme.signed"));
            assertEquals("rejected", mgr.inventory().get(0).get("state"));
            assertTrue(String.valueOf(mgr.inventory().get(0).get("cause")).contains("unsigned"),
                    "refused by the signature check, not the allowlist: " + mgr.inventory().get(0).get("cause"));
            Path stagingDir = stagingDir(mgr);
            assertNotNull(stagingDir, "the jar was staged before verification");
            try (Stream<Path> left = Files.list(stagingDir)) {
                assertEquals(0, left.count(), "the refused staged copy is deleted");
            }
        }
    }

    /**
     * P0 (server-owned half): the staged copy the loader reads lives under the SERVER-OWNED staging root the
     * manager was given, never in the JVM-wide system temp dir (a second, weaker writer path to the bytes that
     * are actually loaded). Red before the fix: the staging dir's parent was {@code java.io.tmpdir}.
     */
    @Test
    void theStagedCopyLivesUnderTheServerOwnedRootNotTheSystemTempDir(@TempDir Path work) throws Exception {
        Fixture f = fixture(work);
        Path root = work.resolve("server-state").resolve("job-packs-staging");
        JobTypeRegistry registry = new JobTypeRegistry();
        try (JobPackManager mgr = new JobPackManager(f.packs.toString(), registry,
                ExpressionRegistry.withBuiltins(), (t, s, p) -> {}, null, root)) {
            assertEquals(List.of("pack.jar"), mgr.rescan().get("loaded"));
            Path stagingDir = stagingDir(mgr);
            assertNotNull(stagingDir);
            assertEquals(root.toAbsolutePath().normalize(), stagingDir.getParent(),
                    "staged under the server-owned root: " + stagingDir);
            try (Stream<Path> staged = Files.list(stagingDir)) {
                assertEquals(1, staged.count(), "the loaded pack's copy is there");
            }
        }
    }

    /** The staging root must not sit inside the watched packs dir: whoever can drop a jar there could then
     *  rewrite the staged bytes after they were hashed. Boot refuses, naming the path. */
    @Test
    void aStagingRootInsideThePacksDirRefusesBoot(@TempDir Path work) throws Exception {
        Fixture f = fixture(work);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new JobPackManager(
                f.packs.toString(), new JobTypeRegistry(), ExpressionRegistry.withBuiltins(), (t, s, p) -> {},
                null, f.packs.resolve("staging")));
        assertTrue(e.getMessage().contains("inside the packs dir"), e.getMessage());
    }

    /** JobService's root: {@code -Djobs.packs.stagingDir} wins, else {@code <auditDir>/job-packs-staging}. */
    @Test
    void theStagingRootIsTheSpacesAuditDirUnlessOverridden(@TempDir Path work) {
        String prior = System.getProperty("jobs.packs.stagingDir");
        try {
            System.clearProperty("jobs.packs.stagingDir");
            assertEquals(work.resolve("audit").resolve("job-packs-staging").toAbsolutePath().normalize(),
                    JobPackManager.stagingRootFor(work.resolve("audit").toString()));
            System.setProperty("jobs.packs.stagingDir", work.resolve("elsewhere").toString());
            assertEquals(work.resolve("elsewhere").toAbsolutePath().normalize(),
                    JobPackManager.stagingRootFor(work.resolve("audit").toString()));
        } finally {
            if (prior == null) System.clearProperty("jobs.packs.stagingDir");
            else System.setProperty("jobs.packs.stagingDir", prior);
        }
    }

    // ── fixture ──────────────────────────────────────────────────────────────────────────────────────

    private record Fixture(Path packs, Path watched, Path unsigned, String signedSha) {}

    @TempDir static Path shared;
    private static Path signedJar, unsignedJar;

    /** Build + sign once per class (keytool/jarsigner each spawn a JVM, which is slow on Windows). */
    @BeforeAll
    static void buildJars() throws Exception {
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "needs a JDK (javac) to build the pack jar");
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        signedJar = JobPackManagerTest.buildPackJar(shared, shared.resolve("signed.jar"),
                "acme.signed", "acme.signed", "SignedType", "acme-signed", "1.0.0");
        unsignedJar = JobPackManagerTest.buildPackJar(shared, shared.resolve("evil.jar"),
                "acme.evil", "acme.evil", "EvilType", "acme-evil", "1.0.0");
        Path ks = shared.resolve("ks.p12");
        run(tool(bin, "keytool"), "-genkeypair", "-keystore", ks.toString(), "-storetype", "PKCS12",
                "-storepass", "changeit", "-keypass", "changeit", "-alias", "pack", "-keyalg", "RSA",
                "-keysize", "2048", "-dname", "CN=pack-test", "-validity", "2");
        run(tool(bin, "jarsigner"), "-keystore", ks.toString(), "-storepass", "changeit",
                signedJar.toString(), "pack");
    }

    /** A copy of the signed pack at {@code packs/pack.jar}; the unsigned pack stays outside the watched dir.
     *  BOTH hashes are on the trust allowlist (T1), so what these tests exercise is the SIGNATURE check on the
     *  staged bytes — the allowlist's own staged-bytes pin is {@code JobPackTrustTest}'s. */
    private static Fixture fixture(Path work) throws Exception {
        Path packs = Files.createDirectories(work.resolve("packs"));
        Path watched = Files.copy(signedJar, packs.resolve("pack.jar"));
        String sha = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(watched)));
        Path allow = Files.writeString(work.resolve("packs.allowlist"),
                sha + "  pack.jar\n" + JobPackTrustTest.sha(unsignedJar) + "  evil.jar\n");
        System.setProperty("jobs.packs.allowlist", allow.toString());
        JobPackManagerTest.narrowSafetyRoots(work.resolve("jail"));
        return new Fixture(packs, watched, unsignedJar, sha);
    }

    @AfterEach
    void clearAllowlist() { JobPackManagerTest.clearTrust(); }

    private static JobPackManager signingManager(Path packs, JobTypeRegistry registry) {
        String prior = System.getProperty("jobs.packs.requireSignature");
        System.setProperty("jobs.packs.requireSignature", "true");
        try {
            return new JobPackManager(packs.toString(), registry, ExpressionRegistry.withBuiltins(),
                    (t, s, p) -> {});
        } finally {
            if (prior == null) System.clearProperty("jobs.packs.requireSignature");
            else System.setProperty("jobs.packs.requireSignature", prior);
        }
    }

    private static void swap(Path from, Path to) {
        try {
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path stagingDir(JobPackManager mgr) throws Exception {
        Field f = JobPackManager.class.getDeclaredField("stagingDir");
        f.setAccessible(true);
        return (Path) f.get(mgr);
    }

    /** A JDK tool from {@code java.home/bin} — FAILS (never skips) when absent, so a signing gap cannot
     *  turn this pin into a silent skip. (The in-process {@code java.util.spi.ToolProvider}s are not
     *  resolvable under surefire, which is why this spawns.) */
    private static String tool(Path bin, String name) {
        for (String n : List.of(name, name + ".exe"))
            if (Files.isRegularFile(bin.resolve(n))) return bin.resolve(n).toString();
        return fail(name + " not found in " + bin);
    }

    private static void run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertTrue(p.waitFor(120, TimeUnit.SECONDS), "tool finished: " + cmd[0]);
        assertEquals(0, p.exitValue(), cmd[0] + " failed: " + out);
    }
}
