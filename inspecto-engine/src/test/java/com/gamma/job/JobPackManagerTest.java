package com.gamma.job;

import com.gamma.util.RunLog;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * P2c Job Packs (§12): a pack jar dropped in the watched dir is loaded through its own isolated
 * {@link ClassLoader}, its {@link JobTypeProvider} registers, {@link #rescan()} reconciles add/remove/
 * change, and a whole pack is rejected atomically on collision or {@code @JobTypeMeta} mismatch. The
 * provider classes are compiled at test time into a jar so they are genuinely OFF the test classpath —
 * proving real classloader isolation, not a classpath fake.
 */
class JobPackManagerTest {

    /** Captures the audit signals a manager emits, so tests can assert loaded/unloaded/rejected. */
    private static final class Sink implements JobPackManager.SignalSink {
        final List<String> types = new CopyOnWriteArrayList<>();
        @Override public void emit(String type, com.gamma.signal.Severity sev, Map<String, Object> payload) {
            types.add(type);
        }
    }

    /** The trust gate (T1) refuses every jar with no allowlist, so these tests approve the jars they build:
     *  an allowlist of every jar now in {@code packsDir}, written BESIDE it (inside is refused at boot). */
    static void trustEveryJarIn(Path packsDir) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> jars = Files.list(packsDir)) {
            for (Path j : jars.filter(p -> p.toString().endsWith(".jar")).toList())
                sb.append(JobPackTrustTest.sha(j)).append("  ").append(j.getFileName()).append('\n');
        }
        Path allow = packsDir.resolveSibling(packsDir.getFileName() + ".allowlist");
        Files.writeString(allow, sb.toString());
        System.setProperty("jobs.packs.allowlist", allow.toString());
        narrowSafetyRoots(packsDir.resolveSibling("jail"));
    }

    private static String priorSafetyRoots;
    private static boolean narrowed;

    /** The surefire sandbox makes {@code java.io.tmpdir} an {@code assist.safety.roots} root, and boot refuses
     *  an allowlist under any such root (threat A3) — so a test keeping its allowlist in a temp dir narrows
     *  the roots to a jail that does not contain it. Undone by {@link #clearTrust()}. */
    static void narrowSafetyRoots(Path jail) {
        if (!narrowed) { priorSafetyRoots = System.getProperty("assist.safety.roots"); narrowed = true; }
        System.setProperty("assist.safety.roots", jail.toString());
    }

    static void clearTrust() {
        System.clearProperty("jobs.packs.allowlist");
        if (!narrowed) return;
        if (priorSafetyRoots == null) System.clearProperty("assist.safety.roots");
        else System.setProperty("assist.safety.roots", priorSafetyRoots);
        narrowed = false;
    }

    @AfterEach
    void clearAllowlist() { clearTrust(); }

    private static List<Object> states(JobPackManager mgr) {
        return mgr.inventory().stream().map(r -> r.get("state")).toList();
    }

    @Test
    void loadsRegistersAndUnloadsAPackTypeThroughAnIsolatedClassLoader(@TempDir Path work) throws Exception {
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "needs a JDK (javac) to build the pack jar");
        Path packsDir = Files.createDirectories(work.resolve("packs"));
        Path jar = buildPackJar(work, packsDir.resolve("greet-1.jar"), "acme.greet", "acme.greet",
                "GreetType", "acme-greet", "1.0.0");

        JobTypeRegistry registry = new JobTypeRegistry();
        ExpressionRegistry expressions = ExpressionRegistry.withBuiltins();
        Sink sink = new Sink();
        trustEveryJarIn(packsDir);
        try (JobPackManager mgr = new JobPackManager(packsDir.toString(), registry, expressions, sink)) {
            mgr.scanAtStartup();

            assertTrue(registry.has("acme.greet"), "pack type registered");
            Job job = registry.create("acme.greet", jobConfig("g1", "acme.greet"));
            assertNotSame(getClass().getClassLoader(), job.getClass().getClassLoader(),
                    "pack Job is loaded by the pack's own classloader, not the test's");
            assertEquals("SUCCESS", job.run().status());
            assertTrue(sink.types.contains("job.pack.loaded"));

            List<Map<String, Object>> inv = mgr.inventory();
            assertEquals(1, inv.size());
            assertEquals("acme-greet", inv.get(0).get("id"));
            assertEquals(List.of("acme.greet"), inv.get(0).get("types"));

            // Remove the jar (unlocked because we load from a staged copy) → rescan unloads the type.
            Files.delete(jar);
            Map<String, Object> summary = mgr.rescan();
            assertEquals(List.of("greet-1.jar"), summary.get("unloaded"));
            assertFalse(registry.has("acme.greet"), "type deregisters when the jar is removed");
            assertTrue(mgr.inventory().isEmpty());
            assertTrue(sink.types.contains("job.pack.unloaded"));
        }
    }

    @Test
    void unloadDefersClassloaderCloseWhileARunIsInFlight(@TempDir Path work) throws Exception {
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "needs a JDK (javac) to build the pack jar");
        Path packsDir = Files.createDirectories(work.resolve("packs"));
        Path jar = buildPackJar(work, packsDir.resolve("greet-1.jar"), "acme.greet", "acme.greet",
                "GreetType", "acme-greet", "1.0.0");

        JobTypeRegistry registry = new JobTypeRegistry();
        ExpressionRegistry expressions = ExpressionRegistry.withBuiltins();
        Sink sink = new Sink();
        trustEveryJarIn(packsDir);
        try (JobPackManager mgr = new JobPackManager(packsDir.toString(), registry, expressions, sink)) {
            mgr.scanAtStartup();
            Job job = registry.create("acme.greet", jobConfig("g1", "acme.greet"));

            // Simulate JobService pinning the pack for the duration of a Run.
            mgr.acquireRun("greet-1.jar");
            assertFalse(mgr.isDraining("greet-1.jar"), "not draining until an unload is actually requested");

            Files.delete(jar);
            Map<String, Object> summary = mgr.rescan();
            assertEquals(List.of("greet-1.jar"), summary.get("unloaded"));
            assertFalse(registry.has("acme.greet"), "type deregisters immediately regardless of in-flight Runs");
            assertTrue(mgr.isDraining("greet-1.jar"), "classloader close is deferred while the Run is in flight");

            // The classloader is still open, so the Job instance built before the unload keeps working —
            // this is the actual bug being fixed: without quiescing, closing the loader here could break a
            // Run still executing pack code (a lazy class load / reflection / resource read failing).
            assertEquals("SUCCESS", job.run().status(), "in-flight Run's classes remain usable during quiesce");

            mgr.releaseRun("greet-1.jar");
            assertFalse(mgr.isDraining("greet-1.jar"), "close finishes once the last in-flight Run ends");
        }
    }

    @Test
    void collisionWithAnExistingTypeRejectsTheWholePack(@TempDir Path work) throws Exception {
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null);
        Path packsDir = Files.createDirectories(work.resolve("packs"));
        // A pack that tries to claim an id already held by a "built-in".
        buildPackJar(work, packsDir.resolve("dupe-1.jar"), "report", "report", "DupeType", "dupe", "1.0.0");

        JobTypeRegistry registry = new JobTypeRegistry();
        ExpressionRegistry expressions = ExpressionRegistry.withBuiltins();
        JobTypeProvider builtin = JobTypeProvider.of(
                new JobTypeDescriptor("report", "Report", "built-in", List.of(), List.of(), List.of()),
                c -> { throw new UnsupportedOperationException(); });
        registry.register(builtin);   // permanent, owner=null

        Sink sink = new Sink();
        trustEveryJarIn(packsDir);
        try (JobPackManager mgr = new JobPackManager(packsDir.toString(), registry, expressions, sink)) {
            mgr.scanAtStartup();
            assertTrue(registry.has("report"));
            // The built-in still owns 'report' — its factory throws; the pack's would have returned a Job.
            assertThrows(UnsupportedOperationException.class,
                    () -> registry.create("report", jobConfig("r", "report")),
                    "the built-in still owns 'report' (pack did not displace it)");
            assertEquals(List.of("rejected"), states(mgr), "the rejected pack is listed as rejected, never loaded");
            assertTrue(sink.types.contains("job.pack.rejected"));
        }
    }

    /**
     * 🔴 Blast radius: an UNLOADABLE pack must reject <b>itself</b> and nothing else.
     *
     * <p>The four {@code ServiceLoader} loops in {@code load()} run over an operator-supplied pack
     * classloader. A pack whose provider class cannot be <i>defined</i> — compiled for a newer Java,
     * truncated, corrupt — raises a {@link LinkageError} from {@code Class.forName}, and
     * {@code ServiceLoader} does <b>not</b> wrap that in {@code ServiceConfigurationError}: it comes
     * straight out of {@code hasNext()}. {@code load()} caught only
     * {@code Exception | ServiceConfigurationError}, so the Error escaped {@code load()} AND
     * {@code rescan()}'s per-pack loop — every other operator's pack silently never loaded, and at
     * startup the boot died.
     *
     * <p>⚠ The property pinned here is that the <b>good</b> pack is still discovered. The broken jar
     * sorts first in the packs dir precisely so that a regression cannot pass by loading the good pack
     * before the Error is thrown. (Under the old catch the test reds either way: bad-first loses the
     * good pack, good-first throws out of {@code scanAtStartup}.)
     */
    @Test
    void anUnloadablePackRejectsItselfAndTheOtherPacksStillLoad(@TempDir Path work) throws Exception {
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null);
        Path packsDir = Files.createDirectories(work.resolve("packs"));
        buildUnlinkablePackJar(work, packsDir.resolve("aaa-broken-1.jar"), "acme-broken");
        buildPackJar(work, packsDir.resolve("zzz-greet-1.jar"), "acme.greet", "acme.greet",
                "GreetType", "acme-greet", "1.0.0");

        JobTypeRegistry registry = new JobTypeRegistry();
        ExpressionRegistry expressions = ExpressionRegistry.withBuiltins();
        Sink sink = new Sink();
        trustEveryJarIn(packsDir);
        try (JobPackManager mgr = new JobPackManager(packsDir.toString(), registry, expressions, sink)) {
            mgr.scanAtStartup();

            assertTrue(registry.has("acme.greet"),
                    "a DIFFERENT operator's pack must still load — one unloadable jar is not a discovery-wide kill");
            assertEquals("SUCCESS", registry.create("acme.greet", jobConfig("g1", "acme.greet")).run().status());
            assertTrue(sink.types.contains("job.pack.loaded"));
            assertTrue(sink.types.contains("job.pack.rejected"), "the broken jar is rejected, explicitly");

            List<Map<String, Object>> inv = mgr.inventory().stream()
                    .filter(r -> "loaded".equals(r.get("state"))).toList();
            assertEquals(1, inv.size(), "only the good pack is loaded");
            assertEquals("acme-greet", inv.get(0).get("id"));
        }
    }

    @Test
    void metaMismatchRejectsThePack(@TempDir Path work) throws Exception {
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null);
        Path packsDir = Files.createDirectories(work.resolve("packs"));
        // @JobTypeMeta id ("meta.id") disagrees with descriptor id ("real.id") → fail-closed.
        buildPackJar(work, packsDir.resolve("mismatch-1.jar"), "real.id", "meta.id", "MismatchType", "mm", "1.0.0");

        JobTypeRegistry registry = new JobTypeRegistry();
        ExpressionRegistry expressions = ExpressionRegistry.withBuiltins();
        Sink sink = new Sink();
        trustEveryJarIn(packsDir);
        try (JobPackManager mgr = new JobPackManager(packsDir.toString(), registry, expressions, sink)) {
            mgr.scanAtStartup();
            assertFalse(registry.has("real.id"));
            assertFalse(registry.has("meta.id"));
            assertTrue(sink.types.contains("job.pack.rejected"));
        }
    }

    /**
     * Pipeline spec gap 7's actual remainder: a pack carrying ONLY a {@code PipelineNodeType} — no Job
     * Type, no Expression — loads, its type becomes authorable, and removing the jar takes it back.
     *
     * <p>🔴 Before this, {@code PipelineNodeTypes} was a {@code static final} map built once at class-load,
     * so a contributed node type had to be on the classpath at boot. The gap was never the SPI (which
     * `StepKindRegistry` already honoured) — it was the deployment path.
     */
    @Test
    void aPackContributesAPipelineNodeTypeAndTakesItBackOnUnload(@TempDir Path work) throws Exception {
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "needs a JDK (javac) to build the pack jar");
        Path packsDir = Files.createDirectories(work.resolve("packs"));
        Path jar = buildNodeTypePackJar(work, packsDir.resolve("acme-node-1.jar"), "transform.redact",
                "RedactNodeType", "acme.redact");

        JobTypeRegistry registry = new JobTypeRegistry();
        ExpressionRegistry expressions = ExpressionRegistry.withBuiltins();
        Sink sink = new Sink();
        trustEveryJarIn(packsDir);
        try (JobPackManager mgr = new JobPackManager(packsDir.toString(), registry, expressions, sink)) {
            try {
                mgr.scanAtStartup();

                assertTrue(com.gamma.pipeline.PipelineNodeTypes.isKnown("transform.redact"),
                        "a node-type-only pack is a valid pack");
                assertEquals("acme-node-1.jar",
                        com.gamma.pipeline.PipelineNodeTypes.ownerOf("transform.redact").orElseThrow());
                assertTrue(sink.types.contains("job.pack.loaded"));
                // The authoring surface the type exists for: the served node-type catalog offers it, so the
                // palette can. (`StepKindRegistry.current()` — the parser's load-time gate — is
                // package-private in com.gamma.etl and delegates to the same registry.)
                assertTrue(com.gamma.pipeline.PipelineProjection.catalog().stream()
                                .anyMatch(e -> "transform.redact".equals(e.get("type"))),
                        "a contributed type must reach the served node-type catalog");

                Files.delete(jar);
                mgr.rescan();

                assertFalse(com.gamma.pipeline.PipelineNodeTypes.isKnown("transform.redact"),
                        "unload takes the pack's node type back with it");
                assertTrue(com.gamma.pipeline.PipelineProjection.catalog().stream()
                                .noneMatch(e -> "transform.redact".equals(e.get("type"))),
                        "…and the served catalog stops offering it");
                for (com.gamma.pipeline.BuiltinNodeType b : com.gamma.pipeline.BuiltinNodeType.values())
                    assertTrue(com.gamma.pipeline.PipelineNodeTypes.isKnown(b.type()),
                            "and leaves the built-ins alone: " + b.type());
            } finally {
                // Static registries outlive this test; a leak would show another suite a type that does
                // not exist in a stock build.
                com.gamma.pipeline.PipelineNodeTypes.deregister("acme-node-1.jar");
            }
        }
    }

    @Test
    void aPackContributesAnExpressionTokenAndTakesItBackOnUnload(@TempDir Path work) throws Exception {
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "needs a JDK (javac) to build the pack jar");
        Path packsDir = Files.createDirectories(work.resolve("packs"));
        Path jar = buildExpressionPackJar(work, packsDir.resolve("tenant-1.jar"), "$tenant.id", "TenantExpr", "tenant");

        JobTypeRegistry registry = new JobTypeRegistry();
        ExpressionRegistry expressions = ExpressionRegistry.withBuiltins();
        Sink sink = new Sink();
        trustEveryJarIn(packsDir);
        try (JobPackManager mgr = new JobPackManager(packsDir.toString(), registry, expressions, sink)) {
            mgr.scanAtStartup();

            // §0 working as intended: new vocabulary with no engine edit. A pack carrying only tokens and
            // no Job Type is a valid pack.
            assertEquals("acme", expressions.evaluate("$tenant.id", ctx()).orElse(null));
            assertTrue(expressions.declarations().stream().anyMatch(d -> d.token().equals("$tenant.id")));
            assertTrue(sink.types.contains("job.pack.loaded"));

            Files.delete(jar);
            mgr.rescan();

            assertFalse(expressions.declares("$tenant.id"), "unload takes the pack's tokens back with it");
            assertTrue(expressions.declares("$today"), "and leaves the built-ins alone");
        }
    }

    @Test
    void aPackWhoseTokenCollidesWithABuiltInIsRejectedWhole(@TempDir Path work) throws Exception {
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null);
        Path packsDir = Files.createDirectories(work.resolve("packs"));
        // A captured token would change what an authored $-value means with no signal — §4.2's hazard.
        buildExpressionPackJar(work, packsDir.resolve("shadow-1.jar"), "$today", "ShadowExpr", "shadow");

        JobTypeRegistry registry = new JobTypeRegistry();
        ExpressionRegistry expressions = ExpressionRegistry.withBuiltins();
        Sink sink = new Sink();
        trustEveryJarIn(packsDir);
        try (JobPackManager mgr = new JobPackManager(packsDir.toString(), registry, expressions, sink)) {
            mgr.scanAtStartup();

            assertTrue(sink.types.contains("job.pack.rejected"), "rejected loudly, not skipped quietly");
            assertNotEquals("acme", expressions.evaluate("$today", ctx()).orElse(null),
                    "the built-in $today still owns its token");
            assertEquals(java.time.LocalDate.now().toString(), expressions.evaluate("$today", ctx()).orElse(null));
        }
    }

    // ── S2-0 / S2-3: a pack Step may only run the pack's OWN node type ────────────────────────

    /** Every row of the input to {@code data} — so a hijacked filter is visible as "kept all three rows". */
    private static final String COPY_ALL =
            "StepInput in = ctx.in(); StepOutput out = ctx.emit(\"data\"); while (in.next()) out.copyRow(in);";

    /**
     * ⛔ D-2 (S2-3): a pack carrying the raw-{@code Connection} {@code PipelineNodeExecutor} is rejected
     * whole — its own legal node type included — because that seam writes third-party SQL on the batch
     * connection. The seam stays open to the classpath only.
     */
    @Test
    void aPackCarryingARawConnectionExecutorIsRejectedWhole(@TempDir Path work) throws Exception {
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "needs a JDK (javac) to build the pack jar");
        Path packsDir = Files.createDirectories(work.resolve("packs"));
        buildNodePackJar(work, packsDir.resolve("raw-1.jar"), "RawPack", "acme.raw",
                "transform.acme_raw", "transform.acme_raw");

        Sink sink = new Sink();
        trustEveryJarIn(packsDir);
        try (JobPackManager mgr = new JobPackManager(packsDir.toString(), new JobTypeRegistry(),
                ExpressionRegistry.withBuiltins(), sink)) {
            try {
                assertEquals(List.of("raw-1.jar"), mgr.rescan().get("rejected"), "refused, not loaded");
                assertTrue(sink.types.contains("job.pack.rejected"));
                assertTrue(mgr.inventory().get(0).get("cause").toString().contains("StepExecutor"),
                        "the refusal names the seam to use instead: " + mgr.inventory());
                assertFalse(com.gamma.pipeline.PipelineNodeTypes.isKnown("transform.acme_raw"),
                        "atomic: the pack's node type is rolled back with it");
                assertTrue(com.gamma.pipeline.exec.PipelineNodeExecutors.get("transform.acme_raw").isEmpty());
            } finally {
                com.gamma.pipeline.PipelineNodeTypes.deregister("raw-1.jar");
            }
        }
    }

    /**
     * 🔴 A jar dropped in the packs dir must not change how a BUILT-IN verb runs for every pipeline.
     * {@code RowShaper.shape} consults the Step registry before its built-in chain, so a Step for
     * {@code transform.filter} would silently replace the filter everywhere. The pack is rejected whole —
     * its own (legal) node type included — and the built-in still decides which rows survive.
     */
    @Test
    void aPackStepForABuiltInVerbIsRefusedWholeAndTheBuiltInStillRuns(@TempDir Path work) throws Exception {
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "needs a JDK (javac) to build the pack jar");
        Path packsDir = Files.createDirectories(work.resolve("packs"));
        buildStepPackJar(work, packsDir.resolve("hijack-1.jar"), "HijackPack", "acme.hijack",
                "transform.acme_hijack", "transform.filter", "EXECUTED", "", COPY_ALL);

        Sink sink = new Sink();
        trustEveryJarIn(packsDir);
        try (JobPackManager mgr = new JobPackManager(packsDir.toString(), new JobTypeRegistry(),
                ExpressionRegistry.withBuiltins(), sink)) {
            try {
                Map<String, Object> summary = mgr.rescan();

                assertEquals(List.of("hijack-1.jar"), summary.get("rejected"), "refused, not loaded");
                assertTrue(sink.types.contains("job.pack.rejected"), "rejected loudly, not skipped quietly");
                assertTrue(com.gamma.pipeline.exec.StepExecutors.get("transform.filter").isEmpty(),
                        "no pack Step may sit in front of the built-in filter");
                assertFalse(com.gamma.pipeline.PipelineNodeTypes.isKnown("transform.acme_hijack"),
                        "atomic: the pack's own node type is rolled back with it");
                assertEquals(List.of(1, 3), shapeIds(com.gamma.pipeline.PipelineNode.of("f", "transform.filter",
                        Map.of("where", "amt >= 100"))), "the built-in filter still decides (the hijack keeps all 3)");
            } finally {
                com.gamma.pipeline.PipelineNodeTypes.deregister("hijack-1.jar");
                com.gamma.pipeline.exec.StepExecutors.deregister("hijack-1.jar");
            }
        }
    }

    /** A Step for a kind NO node type of the same pack declares is refused too — it is not the pack's. */
    @Test
    void aPackStepForAKindItsPackDoesNotDeclareIsRefused(@TempDir Path work) throws Exception {
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "needs a JDK (javac) to build the pack jar");
        Path packsDir = Files.createDirectories(work.resolve("packs"));
        buildStepPackJar(work, packsDir.resolve("orphan-1.jar"), "OrphanPack", "acme.orphan",
                null, "transform.acme_orphan", "EXECUTED", "", COPY_ALL);

        trustEveryJarIn(packsDir);
        try (JobPackManager mgr = new JobPackManager(packsDir.toString(), new JobTypeRegistry(),
                ExpressionRegistry.withBuiltins(), new Sink())) {
            try {
                assertEquals(List.of("orphan-1.jar"), mgr.rescan().get("rejected"));
                assertTrue(com.gamma.pipeline.exec.StepExecutors.get("transform.acme_orphan").isEmpty());
            } finally {
                com.gamma.pipeline.exec.StepExecutors.deregister("orphan-1.jar");
            }
        }
    }

    /** The positive twin: a Step for the pack's own declared kind loads and is what runs it. */
    @Test
    void aPackStepForItsOwnDeclaredKindLoadsAndRuns(@TempDir Path work) throws Exception {
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "needs a JDK (javac) to build the pack jar");
        Path packsDir = Files.createDirectories(work.resolve("packs"));
        buildStepPackJar(work, packsDir.resolve("own-1.jar"), "OwnPack", "acme.own",
                "transform.acme_own", "transform.acme_own", "EXECUTED", "", COPY_ALL);

        trustEveryJarIn(packsDir);
        try (JobPackManager mgr = new JobPackManager(packsDir.toString(), new JobTypeRegistry(),
                ExpressionRegistry.withBuiltins(), new Sink())) {
            try {
                assertEquals(List.of("own-1.jar"), mgr.rescan().get("loaded"));
                assertTrue(com.gamma.pipeline.exec.StepExecutors.get("transform.acme_own").isPresent());
                assertEquals(List.of(1, 2, 3), shapeIds(com.gamma.pipeline.PipelineNode.of("n", "transform.acme_own",
                        Map.of())), "the pack's Step shapes its own kind (keeps every row)");
            } finally {
                com.gamma.pipeline.PipelineNodeTypes.deregister("own-1.jar");
                com.gamma.pipeline.exec.StepExecutors.deregister("own-1.jar");
            }
        }
    }

    // ── S2-1: a pack node type must declare EXECUTED ─────────────────────────────────────────

    /** ⛔ A pack declaring LOWERED would put third-party SQL in the engine's query (R2): the pack is
     *  rejected whole, with the reason on the {@code job.pack.rejected} signal. */
    @Test
    void aPackNodeTypeDeclaringLoweredIsRejectedWhole(@TempDir Path work) throws Exception {
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "needs a JDK (javac) to build the pack jar");
        Path packsDir = Files.createDirectories(work.resolve("packs"));
        buildStepPackJar(work, packsDir.resolve("lowered-1.jar"), "LoweredPack", "acme.lowered",
                "transform.acme_lowered", "transform.acme_lowered", "LOWERED", "", COPY_ALL);

        Sink sink = new Sink();
        trustEveryJarIn(packsDir);
        try (JobPackManager mgr = new JobPackManager(packsDir.toString(), new JobTypeRegistry(),
                ExpressionRegistry.withBuiltins(), sink)) {
            try {
                assertEquals(List.of("lowered-1.jar"), mgr.rescan().get("rejected"));
                assertTrue(sink.types.contains("job.pack.rejected"));
                assertFalse(com.gamma.pipeline.PipelineNodeTypes.isKnown("transform.acme_lowered"));
                assertTrue(com.gamma.pipeline.exec.StepExecutors.get("transform.acme_lowered").isEmpty(),
                        "atomic: the Step is rolled back with the descriptor");
            } finally {
                com.gamma.pipeline.PipelineNodeTypes.deregister("lowered-1.jar");
                com.gamma.pipeline.exec.StepExecutors.deregister("lowered-1.jar");
            }
        }
    }

    // ── S2-3: a pack Step's requires: grant, dry run and ceiling ─────────────────────────────

    /**
     * S2-3: a pack Step runs armed and in a dry run, and knows which. It writes {@code ctx.dryRun()} into its
     * output and notifies through its declared {@code notifications} grant: armed, the host feed receives it;
     * dry, the dry-run stand-in records it and the feed stays empty.
     */
    @Test
    void aPackStepRunsArmedAndInADryRunAndKnowsWhich(@TempDir Path work) throws Exception {
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "needs a JDK (javac) to build the pack jar");
        Path packsDir = Files.createDirectories(work.resolve("packs"));
        buildStepPackJar(work, packsDir.resolve("dry-1.jar"), "DryPack", "acme.dry",
                "transform.acme_dry", "transform.acme_dry", "EXECUTED", "\"notifications\"", """
                        com.gamma.notify.NotificationAccess feed =
                                ctx.services().get(com.gamma.notify.NotificationAccess.class);
                        feed.notify(com.gamma.notify.Notification.create("step", "STEP_RUN", ctx.nodeId(),
                                "from the step", "dry=" + ctx.dryRun(), "pack:step"));
                        StepInput in = ctx.in();
                        StepOutput out = ctx.emit("data", List.of(new TypeFlow.Column("id", "INTEGER"),
                                new TypeFlow.Column("dry", "BOOLEAN")));
                        while (in.next()) out.beginRow().append(in.getInt(in.column("id"))).append(ctx.dryRun()).endRow();
                        """);

        List<com.gamma.notify.Notification> feed = new CopyOnWriteArrayList<>();
        PlatformServiceRegistry platform = new PlatformServiceRegistry();
        platform.register("notifications", com.gamma.notify.NotificationAccess.class, n -> {
            feed.add(n);
            return java.util.Optional.of(n);
        });
        trustEveryJarIn(packsDir);
        java.io.File db = com.gamma.util.DuckDbUtil.tempDbFile("steprun_");
        try (JobPackManager mgr = new JobPackManager(packsDir.toString(), new JobTypeRegistry(platform),
                ExpressionRegistry.withBuiltins(), new Sink());
             java.sql.Connection conn = com.gamma.util.DuckDbUtil.openConnection(db);
             java.sql.Statement st = conn.createStatement()) {
            try {
                assertEquals(List.of("dry-1.jar"), mgr.rescan().get("loaded"));
                st.execute("CREATE TABLE parsed AS SELECT * FROM (VALUES (1,150),(2,50),(3,200)) t(id,amt)");
                com.gamma.pipeline.PipelineGraph g = linear("DRY", "transform.acme_dry");

                var dry = com.gamma.pipeline.exec.PipelineExecutor.dryRun(conn, g, "parse", "parsed");
                assertEquals(List.of(true), distinct(st, dry.produced().get("n").get("data"), "dry"),
                        "the dry run tells the Step it is one");
                assertTrue(feed.isEmpty(), "a dry run's mutating service records instead of acting: " + feed);
                st.execute("DROP TABLE \"" + dry.produced().get("n").get("data") + "\"");   // the preview's scratch

                List<String> written = new java.util.ArrayList<>();
                var coordinator = new com.gamma.pipeline.exec.BranchCommitCoordinator(
                        new com.gamma.pipeline.exec.BranchCommitLog(work.resolve("commit.csv").toString()));
                com.gamma.pipeline.exec.PipelineExecutor.execute(conn, g, "parse", "parsed", "b1", coordinator,
                        (sink, table) -> written.add(table), () -> {});
                assertEquals(1, written.size());
                assertEquals(List.of(false), distinct(st, written.get(0), "dry"), "armed, the Step knows it is armed");
                assertEquals(1, feed.size(), "armed, the declared grant reaches the real feed");
                assertEquals("dry=false", feed.get(0).body());
            } finally {
                com.gamma.pipeline.PipelineNodeTypes.deregister("dry-1.jar");
                com.gamma.pipeline.exec.StepExecutors.deregister("dry-1.jar");
            }
        } finally {
            com.gamma.util.DuckDbUtil.deleteTempDb(db);
        }
    }

    /** S2-3 (R4): a service the Step did not declare is invisible, though the host binds it. */
    @Test
    void aPackStepSeesNoServiceItDidNotDeclare(@TempDir Path work) throws Exception {
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "needs a JDK (javac) to build the pack jar");
        Path packsDir = Files.createDirectories(work.resolve("packs"));
        buildStepPackJar(work, packsDir.resolve("blind-1.jar"), "BlindPack", "acme.blind",
                "transform.acme_blind", "transform.acme_blind", "EXECUTED", "", """
                        if (ctx.services().find(com.gamma.notify.NotificationAccess.class).isPresent())
                            throw new IllegalStateException("an undeclared service was visible");
                        """ + COPY_ALL);

        PlatformServiceRegistry platform = new PlatformServiceRegistry();
        platform.register("notifications", com.gamma.notify.NotificationAccess.class, n -> java.util.Optional.of(n));
        trustEveryJarIn(packsDir);
        try (JobPackManager mgr = new JobPackManager(packsDir.toString(), new JobTypeRegistry(platform),
                ExpressionRegistry.withBuiltins(), new Sink())) {
            try {
                assertEquals(List.of("blind-1.jar"), mgr.rescan().get("loaded"));
                assertEquals(List.of(1, 2, 3), shapeIds(com.gamma.pipeline.PipelineNode.of("n", "transform.acme_blind",
                        Map.of())), "the Step ran to completion and saw no notifications service");
            } finally {
                com.gamma.pipeline.PipelineNodeTypes.deregister("blind-1.jar");
                com.gamma.pipeline.exec.StepExecutors.deregister("blind-1.jar");
            }
        }
    }

    /** S2-3: a Step requiring a service above the stage-2 data-path ceiling ({@code mail}), or an unbound
     *  one, is rejected whole at load — even though the host binds {@code mail}. */
    @Test
    void aPackStepRequiringAServiceAboveTheCeilingIsRejectedWhole(@TempDir Path work) throws Exception {
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "needs a JDK (javac) to build the pack jar");
        Path packsDir = Files.createDirectories(work.resolve("packs"));
        buildStepPackJar(work, packsDir.resolve("mailer-1.jar"), "MailerPack", "acme.mailer",
                "transform.acme_mailer", "transform.acme_mailer", "EXECUTED", "\"mail\"", COPY_ALL);
        buildStepPackJar(work, packsDir.resolve("typo-1.jar"), "TypoPack", "acme.typo",
                "transform.acme_typo", "transform.acme_typo", "EXECUTED", "\"notifcations\"", COPY_ALL);

        PlatformServiceRegistry platform = new PlatformServiceRegistry();
        platform.register("mail", com.gamma.notify.MailAccess.class, (com.gamma.notify.MailAccess) java.lang.reflect.Proxy
                .newProxyInstance(getClass().getClassLoader(), new Class<?>[]{com.gamma.notify.MailAccess.class},
                        (p, m, a) -> null));
        trustEveryJarIn(packsDir);
        try (JobPackManager mgr = new JobPackManager(packsDir.toString(), new JobTypeRegistry(platform),
                ExpressionRegistry.withBuiltins(), new Sink())) {
            try {
                assertEquals(List.of("mailer-1.jar", "typo-1.jar"), mgr.rescan().get("rejected"));
                assertTrue(mgr.inventory().get(0).get("cause").toString().contains("ceiling"), mgr.inventory().toString());
                assertFalse(com.gamma.pipeline.PipelineNodeTypes.isKnown("transform.acme_mailer"), "rolled back whole");
                assertTrue(com.gamma.pipeline.exec.StepExecutors.get("transform.acme_typo").isEmpty());
            } finally {
                for (String p : List.of("mailer-1.jar", "typo-1.jar")) {
                    com.gamma.pipeline.PipelineNodeTypes.deregister(p);
                    com.gamma.pipeline.exec.StepExecutors.deregister(p);
                }
            }
        }
    }

    // ── S2-0: a PIPELINE run executing pack code pins the pack, as a Job run does ────────────────

    /**
     * 🔴 Only {@code JobService}'s Job path held the pack lease, so a pipeline whose Step is a pack's
     * node type could have that pack's classloader closed under it mid-run. The run blocks inside the walk
     * (its sink write, after the pack's Step has run); an unload arriving then must DEFER the close
     * until the run finishes.
     */
    @Test
    void anUnloadDuringAnInFlightPipelineRunIsDeferredUntilTheRunCompletes(@TempDir Path work) throws Exception {
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "needs a JDK (javac) to build the pack jar");
        Path packsDir = Files.createDirectories(work.resolve("packs"));
        Path jar = buildStepPackJar(work, packsDir.resolve("lease-1.jar"), "LeasePack", "acme.lease",
                "transform.acme_lease", "transform.acme_lease", "EXECUTED", "", COPY_ALL);

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.CountDownLatch inRun = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch proceed = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger sinkRows = new java.util.concurrent.atomic.AtomicInteger(-1);
        java.io.File db = com.gamma.util.DuckDbUtil.tempDbFile("lease_");
        trustEveryJarIn(packsDir);
        try (JobPackManager mgr = new JobPackManager(packsDir.toString(), new JobTypeRegistry(),
                ExpressionRegistry.withBuiltins(), new Sink());
             java.sql.Connection conn = com.gamma.util.DuckDbUtil.openConnection(db)) {
            try {
                assertEquals(List.of("lease-1.jar"), mgr.rescan().get("loaded"));
                try (java.sql.Statement st = conn.createStatement()) {
                    st.execute("CREATE TABLE parsed AS SELECT * FROM (VALUES (1,150),(2,50),(3,200)) t(id,amt)");
                }
                com.gamma.pipeline.PipelineGraph g = linear("LEASED", "transform.acme_lease");
                var coordinator = new com.gamma.pipeline.exec.BranchCommitCoordinator(
                        new com.gamma.pipeline.exec.BranchCommitLog(work.resolve("commit.csv").toString()));

                var run = pool.submit(() -> com.gamma.pipeline.exec.PipelineExecutor.execute(conn, g, "parse",
                        "parsed", "b1", coordinator, (sinkNode, table) -> {
                            inRun.countDown();
                            assertTrue(proceed.await(20, java.util.concurrent.TimeUnit.SECONDS), "test released the run");
                            // the walk resumes AFTER the unload: the pack's rows must still be intact
                            try (java.sql.Statement q = conn.createStatement();
                                 java.sql.ResultSet rs = q.executeQuery("SELECT count(*) FROM \"" + table + "\"")) {
                                rs.next();
                                sinkRows.set(rs.getInt(1));
                            }
                        }, () -> {}));
                assertTrue(inRun.await(20, java.util.concurrent.TimeUnit.SECONDS), "the run reached its sink write");

                Files.delete(jar);
                assertEquals(List.of("lease-1.jar"), mgr.rescan().get("unloaded"));
                assertTrue(mgr.isDraining("lease-1.jar"),
                        "the pack's classloader close must wait for the in-flight pipeline run");

                proceed.countDown();
                run.get(20, java.util.concurrent.TimeUnit.SECONDS);
                assertFalse(mgr.isDraining("lease-1.jar"), "the close finishes once the run completes");
                assertEquals(3, sinkRows.get(), "the walk completed correctly: the pack Step's 3 rows reached the sink");
            } finally {
                proceed.countDown();
                pool.shutdownNow();
                com.gamma.pipeline.PipelineNodeTypes.deregister("lease-1.jar");
                com.gamma.pipeline.exec.StepExecutors.deregister("lease-1.jar");
            }
        } finally {
            com.gamma.util.DuckDbUtil.deleteTempDb(db);
        }
    }

    /** {@code parse → n (<type>) → sink}. */
    private static com.gamma.pipeline.PipelineGraph linear(String name, String type) {
        return new com.gamma.pipeline.PipelineGraph(name, true,
                List.of(com.gamma.pipeline.PipelineNode.of("parse", "parser"),
                        com.gamma.pipeline.PipelineNode.of("n", type, Map.of()),
                        com.gamma.pipeline.PipelineNode.of("sink", "sink.persistent",
                                Map.of(com.gamma.pipeline.PipelineStores.CONFIG_STORE, "out"))),
                List.of(com.gamma.pipeline.PipelineEdge.data("parse", "n"),
                        com.gamma.pipeline.PipelineEdge.data("n", "sink")));
    }

    /** The distinct values of {@code column} in {@code table}. */
    private static List<Object> distinct(java.sql.Statement st, String table, String column) throws Exception {
        List<Object> out = new java.util.ArrayList<>();
        try (java.sql.ResultSet rs = st.executeQuery("SELECT DISTINCT \"" + column + "\" FROM \"" + table + "\"")) {
            while (rs.next()) out.add(rs.getObject(1));
        }
        return out;
    }

    /** Run {@code node} through {@code RowShaper.shape} over {@code src(id,amt)} = (1,150)(2,50)(3,200);
     *  returns the ids on its {@code data} relation. */
    private static List<Integer> shapeIds(com.gamma.pipeline.PipelineNode node) throws Exception {
        java.io.File db = com.gamma.util.DuckDbUtil.tempDbFile("shape_");
        try (java.sql.Connection conn = com.gamma.util.DuckDbUtil.openConnection(db);
             java.sql.Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE src AS SELECT * FROM (VALUES (1,150),(2,50),(3,200)) t(id,amt)");
            String data = com.gamma.pipeline.exec.RowShaper.shape(conn, node, "src", node.id()).stream()
                    .filter(r -> com.gamma.pipeline.PipelineRel.DATA.equals(r.rel())).findFirst().orElseThrow().table();
            List<Integer> ids = new java.util.ArrayList<>();
            try (java.sql.ResultSet rs = st.executeQuery("SELECT id FROM \"" + data + "\" ORDER BY id")) {
                while (rs.next()) ids.add(rs.getInt(1));
            }
            return ids;
        } finally {
            com.gamma.util.DuckDbUtil.deleteTempDb(db);
        }
    }

    // ── S1-7: a pack that declares and consumes a Platform Service ────────────────────────────

    @Test
    void aPackDeclaringAServiceLoadsAndItsRunConsumesTheGrant(@TempDir Path work) throws Exception {
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "needs a JDK (javac) to build the pack jar");
        Path packsDir = Files.createDirectories(work.resolve("packs"));
        buildServicePackJar(work, packsDir.resolve("notify-1.jar"), "acme.notify", "NotifyType",
                "acme-notify", "\"notifications\"");

        // The host's service registry, exactly as CollectorService builds it at boot.
        List<com.gamma.notify.Notification> feed = new CopyOnWriteArrayList<>();
        PlatformServiceRegistry platform = new PlatformServiceRegistry();
        platform.register("notifications", com.gamma.notify.NotificationAccess.class, n -> {
            feed.add(n);
            return java.util.Optional.of(n);
        });
        JobTypeRegistry registry = new JobTypeRegistry(platform);

        trustEveryJarIn(packsDir);
        try (JobPackManager mgr = new JobPackManager(packsDir.toString(), registry,
                ExpressionRegistry.withBuiltins(), new Sink())) {
            mgr.scanAtStartup();

            assertTrue(registry.has("acme.notify"), "the pack's type registered — its grant resolved");
            assertEquals(List.of("notifications"), registry.descriptor("acme.notify").orElseThrow().requires(),
                    "the declared grant travelled from inside the jar");

            // Grant exactly what the descriptor declares — the same step JobService.runJob performs.
            Job job = registry.create("acme.notify", jobConfig("n1", "acme.notify"));
            assertNotSame(getClass().getClassLoader(), job.getClass().getClassLoader(),
                    "pack Job runs from the pack's own classloader");
            JobResult res = job.run(new GrantedContext(platform.grant(Set.of("notifications"))));

            assertEquals("SUCCESS", res.status(), "the Run succeeds using only a declared grant");
            assertEquals(1, feed.size(), "the pack reached the real feed through the service");
            assertEquals("from the pack", feed.get(0).title());
        }
    }

    @Test
    void aPackDeclaringAnUnavailableServiceIsRefusedWholeAtLoad(@TempDir Path work) throws Exception {
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "needs a JDK (javac) to build the pack jar");
        Path packsDir = Files.createDirectories(work.resolve("packs"));
        buildServicePackJar(work, packsDir.resolve("greedy-1.jar"), "acme.greedy", "GreedyType",
                "acme-greedy", "\"notifications\", \"nonexistent\"");

        PlatformServiceRegistry platform = new PlatformServiceRegistry();
        platform.register("notifications", com.gamma.notify.NotificationAccess.class,
                n -> java.util.Optional.of(n));
        JobTypeRegistry registry = new JobTypeRegistry(platform);
        Sink sink = new Sink();

        trustEveryJarIn(packsDir);
        try (JobPackManager mgr = new JobPackManager(packsDir.toString(), registry,
                ExpressionRegistry.withBuiltins(), sink)) {
            mgr.scanAtStartup();

            assertFalse(registry.has("acme.greedy"), "an undeclarable grant refuses the type");
            assertEquals(List.of("rejected"), states(mgr), "and the whole pack is rejected, not partially loaded");
            assertTrue(sink.types.contains("job.pack.rejected"));
        }
    }

    // ── S3-1: pack-contributed Platform Services ────────────────────────────────────────────────────

    /** Source of a pack contributing a MUTATING {@code mail} service: the real one writes "real" to the file
     *  named by {@code svc.out}; its dry-run stand-in logs and writes nothing. */
    private static final String MAIL_SERVICE_SRC = """
            package com.acme.pack;
            import com.gamma.job.ServiceProvider;
            import com.gamma.notify.MailAccess;
            import com.gamma.util.RunLog;
            public class MailSvc implements ServiceProvider {
                public String id() { return "mail"; }
                public Class<?> type() { return MailAccess.class; }
                public Object create() {
                    return (MailAccess) (to, cc, subject, body, att) -> {
                        java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("svc.out")),
                                "real;", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                        return true;
                    };
                }
                //DRYRUN-BEGIN
                public Object dryRun(RunLog log) {
                    return (MailAccess) (to, cc, subject, body, att) -> {
                        log.info("stand-in: would send mail", "subject", subject);
                        return false;
                    };
                }
                //DRYRUN-END
            }
            """;

    private static Path buildMailServiceJar(Path work, Path jar) throws Exception {
        Path classes = compile(work, "com/acme/pack/MailSvc.java", MAIL_SERVICE_SRC);
        writeJar(jar, classes, "acme-mail", "1.0.0", Map.of("com.gamma.job.ServiceProvider", "com.acme.pack.MailSvc"));
        return jar;
    }

    /** A pack whose Job requires {@code mail} and sends one message through it. */
    private static Path buildMailConsumerJar(Path work, Path jar) throws Exception {
        String src = """
                package com.acme.pack;
                import com.gamma.job.*;
                import com.gamma.notify.MailAccess;
                import java.util.List;
                @JobTypeMeta(id = "acme.mailer", title = "Mailer")
                public class Mailer implements JobTypeProvider {
                    public JobTypeDescriptor descriptor() {
                        return new JobTypeDescriptor("acme.mailer", "Mailer", "sends through a granted service",
                                List.of(), List.of(), List.of(), List.of("mail"));
                    }
                    public Job create(JobConfig config) {
                        return new Job() {
                            public String name() { return config.name(); }
                            public String type() { return "acme.mailer"; }
                            public JobResult run() { return JobResult.ok("no context", 0L); }
                            public JobResult run(JobContext ctx) {
                                try {
                                    ctx.services().get(MailAccess.class).send(List.of("a@b.c"), List.of(), "hi", "body");
                                } catch (Exception e) { throw new IllegalStateException(e); }
                                return JobResult.ok("sent", 0L);
                            }
                        };
                    }
                }
                """;
        Path classes = compile(work, "com/acme/pack/Mailer.java", src);
        writeJar(jar, classes, "acme-mailer", "1.0.0", Map.of("com.gamma.job.JobTypeProvider", "com.acme.pack.Mailer"));
        return jar;
    }

    @Test
    void aServiceFromPackAIsConsumedByAJobInPackBAndADryRunRecordsInsteadOfActing(@TempDir Path work) throws Exception {
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "needs a JDK (javac) to build the pack jar");
        Path packsDir = Files.createDirectories(work.resolve("packs"));
        // The consumer sorts FIRST, so it is refused on the first pass and only the D-9 retry loads it.
        buildMailConsumerJar(work, packsDir.resolve("a-consumer.jar"));
        buildMailServiceJar(work, packsDir.resolve("b-provider.jar"));
        Path out = work.resolve("mail.out");
        System.setProperty("svc.out", out.toString());

        PlatformServiceRegistry platform = new PlatformServiceRegistry();
        JobTypeRegistry registry = new JobTypeRegistry(platform);
        trustEveryJarIn(packsDir);
        try (JobPackManager mgr = new JobPackManager(packsDir.toString(), registry,
                ExpressionRegistry.withBuiltins(), new Sink())) {
            mgr.scanAtStartup();

            assertTrue(platform.has("mail"), "pack B's service is bound: " + mgr.inventory());
            assertTrue(registry.has("acme.mailer"), "pack A's Job loaded on the retry pass: " + mgr.inventory());

            Job job = registry.create("acme.mailer", jobConfig("m1", "acme.mailer"));
            assertEquals("SUCCESS", job.run(new GrantedContext(platform.grant(Set.of("mail")))).status());
            assertEquals("real;", Files.readString(out), "the consumer reached pack B's real service");

            // Dry run: the same grant, wrapped. The contributed stand-in logs and the real service is not called.
            List<String> lines = new CopyOnWriteArrayList<>();
            RunLog log = new RunLog() {
                @Override public void info(String m, Object... kv) { lines.add(m); }
                @Override public void warn(String m, Object... kv) {}
                @Override public void error(String m, Throwable t, Object... kv) {}
            };
            PlatformServices dry = DryRunServices.wrap(platform.grant(Set.of("mail")), log);
            assertFalse(dry.get(com.gamma.notify.MailAccess.class)
                    .send(List.of("a@b.c"), List.of(), "hi", "body"));
            assertEquals(List.of("stand-in: would send mail"), lines);
            assertEquals("real;", Files.readString(out), "a dry run did not act");
        } finally {
            System.clearProperty("svc.out");
            clearTrust();
        }
    }

    /** S3-3: a pack may contribute the read-only {@code datasets} service; readOnly() needs no stand-in, and a
     *  dry run hands the real one through unchanged; disable() refuses the grant. */
    @Test
    void aPackContributedReadOnlyDatasetAccessNeedsNoStandInAndHonoursDisable(@TempDir Path work) throws Exception {
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "needs a JDK (javac) to build the pack jar");
        Path packsDir = Files.createDirectories(work.resolve("packs"));
        String src = """
                package com.acme.pack;
                import com.gamma.job.ServiceProvider;
                import com.gamma.query.DatasetAccess;
                public class DsSvc implements ServiceProvider {
                    public String id() { return "datasets"; }
                    public Class<?> type() { return DatasetAccess.class; }
                    public boolean readOnly() { return true; }
                    public Object create() {
                        return (DatasetAccess) (space, id) ->
                                new DatasetAccess.Read(id, "SELECT 1", java.util.Optional.of(java.util.List.of("a.parquet")));
                    }
                }
                """;
        Path classes = compile(work, "com/acme/pack/DsSvc.java", src);
        writeJar(packsDir.resolve("ds.jar"), classes, "acme-ds", "1.0.0",
                Map.of("com.gamma.job.ServiceProvider", "com.acme.pack.DsSvc"));
        PlatformServiceRegistry platform = new PlatformServiceRegistry();
        trustEveryJarIn(packsDir);
        try (JobPackManager mgr = new JobPackManager(packsDir.toString(), new JobTypeRegistry(platform),
                ExpressionRegistry.withBuiltins(), new Sink())) {
            mgr.scanAtStartup();
            assertTrue(platform.has("datasets"), String.valueOf(mgr.inventory()));
            RunLog quiet = new RunLog() {
                @Override public void info(String m, Object... kv) {}
                @Override public void warn(String m, Object... kv) {}
                @Override public void error(String m, Throwable t, Object... kv) {}
            };
            PlatformServices dry = DryRunServices.wrap(platform.grant(Set.of("datasets")), quiet);
            assertEquals(List.of("a.parquet"), dry.get(com.gamma.query.DatasetAccess.class)
                    .read("s", "d").files().orElseThrow(), "read-only: a dry run reads for real");
            platform.disable("datasets");
            assertThrows(IllegalStateException.class, () -> platform.grant(Set.of("datasets")));
        } finally {
            clearTrust();
        }
    }

    @Test
    void unloadingAServiceProviderDefersItsCloseWhileAGrantedRunIsInFlight(@TempDir Path work) throws Exception {
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "needs a JDK (javac) to build the pack jar");
        Path packsDir = Files.createDirectories(work.resolve("packs"));
        Path jar = buildMailServiceJar(work, packsDir.resolve("b-provider.jar"));
        PlatformServiceRegistry platform = new PlatformServiceRegistry();
        JobTypeRegistry registry = new JobTypeRegistry(platform);
        trustEveryJarIn(packsDir);
        try (JobPackManager mgr = new JobPackManager(packsDir.toString(), registry,
                ExpressionRegistry.withBuiltins(), new Sink())) {
            mgr.scanAtStartup();
            assertEquals(Set.of("b-provider.jar"), mgr.serviceOwners(Set.of("mail")),
                    "the provider pack owns the granted id");
            assertEquals(Set.of(), mgr.serviceOwners(Set.of("nope")), "an unbound id has no owner");

            // What JobService does for a Run granted 'mail': pin each owner.
            Set<String> pinned = mgr.serviceOwners(Set.of("mail"));   // taken once, like JobService, BEFORE the unload
            pinned.forEach(mgr::acquireRun);
            Files.delete(jar);
            mgr.rescan();
            assertFalse(platform.has("mail"), "the binding goes at once: no NEW grant after the unload");
            assertTrue(mgr.isDraining("b-provider.jar"), "but the provider's loader close waits for the granted Run");

            pinned.forEach(mgr::releaseRun);
            assertFalse(mgr.isDraining("b-provider.jar"), "the close finishes when the granted Run ends");
        } finally {
            clearTrust();
        }
    }

    @Test
    void aCollidingServiceRejectsThePackWholeAndLeavesNothingRegistered(@TempDir Path work) throws Exception {
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "needs a JDK (javac) to build the pack jar");
        Path packsDir = Files.createDirectories(work.resolve("packs"));
        // Two services: the first ('ok') is fine, the second ('mail') collides with the host's binding.
        String src = """
                package com.acme.pack;
                import com.gamma.job.ServiceProvider;
                import com.gamma.notify.MailAccess;
                import com.gamma.notify.NotificationAccess;
                public class Two {
                    public static class Ok implements ServiceProvider {
                        public String id() { return "ok"; }
                        public Class<?> type() { return NotificationAccess.class; }
                        public Object create() { return (NotificationAccess) n -> java.util.Optional.of(n); }
                        public boolean readOnly() { return true; }
                    }
                    public static class Clash implements ServiceProvider {
                        public String id() { return "mail"; }
                        public Class<?> type() { return MailAccess.class; }
                        public Object create() { return (MailAccess) (to, cc, s, b, a) -> true; }
                        public boolean readOnly() { return true; }
                    }
                }
                """;
        Path classes = compile(work, "com/acme/pack/Two.java", src);
        writeJar(packsDir.resolve("two.jar"), classes, "acme-two", "1.0.0", Map.of("com.gamma.job.ServiceProvider",
                "com.acme.pack.Two$Ok\ncom.acme.pack.Two$Clash"));

        PlatformServiceRegistry platform = new PlatformServiceRegistry();
        platform.register("mail", com.gamma.notify.MailAccess.class, (to, cc, s, b, a) -> true);
        JobTypeRegistry registry = new JobTypeRegistry(platform);
        trustEveryJarIn(packsDir);
        try (JobPackManager mgr = new JobPackManager(packsDir.toString(), registry,
                ExpressionRegistry.withBuiltins(), new Sink())) {
            mgr.scanAtStartup();
            assertEquals(List.of("rejected"), states(mgr));
            assertFalse(platform.has("ok"), "the first service was rolled back with the pack");
            assertEquals(Set.of("mail"), platform.ids(), "only the host's own binding remains");
        } finally {
            clearTrust();
        }
    }

    @Test
    void aMutatingServiceWithoutADryRunStandInIsRefused(@TempDir Path work) throws Exception {
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "needs a JDK (javac) to build the pack jar");
        Path packsDir = Files.createDirectories(work.resolve("packs"));
        String src = MAIL_SERVICE_SRC.replace("MailSvc", "NoStandIn")
                .replaceAll("(?s)//DRYRUN-BEGIN.*?//DRYRUN-END", "");
        Path classes = compile(work, "com/acme/pack/NoStandIn.java", src);
        writeJar(packsDir.resolve("nostandin.jar"), classes, "acme-nsi", "1.0.0",
                Map.of("com.gamma.job.ServiceProvider", "com.acme.pack.NoStandIn"));
        PlatformServiceRegistry platform = new PlatformServiceRegistry();
        trustEveryJarIn(packsDir);
        try (JobPackManager mgr = new JobPackManager(packsDir.toString(), new JobTypeRegistry(platform),
                ExpressionRegistry.withBuiltins(), new Sink())) {
            mgr.scanAtStartup();
            assertEquals(List.of("rejected"), states(mgr));
            assertTrue(String.valueOf(mgr.inventory().get(0).get("cause")).contains("dry-run stand-in"));
            assertFalse(platform.has("mail"));
        } finally {
            clearTrust();
        }
    }

    /** A {@link JobContext} carrying a pre-granted {@link PlatformServices} — everything else is inert,
     *  because what is under test is the grant a pack Job receives. */
    private record GrantedContext(PlatformServices services) implements JobContext {
        @Override public String runId()   { return "run-1"; }
        @Override public String spaceId() { return "default"; }
        @Override public TriggerInfo trigger() { return TriggerInfo.parse("manual"); }
        @Override public Map<String, String> config() { return Map.of(); }
        @Override public Map<String, String> params() { return Map.of(); }
        @Override public RunLog log() {
            return new RunLog() {
                @Override public void info(String m, Object... kv) {}
                @Override public void warn(String m, Object... kv) {}
                @Override public void error(String m, Throwable t, Object... kv) {}
            };
        }
        @Override public com.gamma.signal.SignalEmitter signals() {
            return (type, sev, payload) -> {};
        }
        @Override public ArtifactRecorder artifacts() {
            return new ArtifactRecorder() {
                @Override public void dataset(String n, String ref, ResultSetMeta m, long rows,
                                              java.time.Instant w) {}
                @Override public void file(String n, Path p, long bytes) {}
            };
        }
    }

    /**
     * Compile a {@link JobTypeProvider} whose descriptor declares {@code requires: [<requiresLiteral>]} and
     * whose Job emits through {@code ctx.services()} — a pack that owns no engine object, only a grant.
     */
    private static Path buildServicePackJar(Path work, Path jar, String typeId, String cls, String packId,
                                            String requiresLiteral) throws Exception {
        String fqcn = "com.acme.pack." + cls;
        String src = """
                package com.acme.pack;
                import com.gamma.job.*;
                import com.gamma.notify.Notification;
                import com.gamma.notify.NotificationAccess;
                import java.util.List;
                @JobTypeMeta(id = "%s", title = "Notify")
                public class %s implements JobTypeProvider {
                    public JobTypeDescriptor descriptor() {
                        return new JobTypeDescriptor("%s", "Notify", "emits through a granted service",
                                List.of(), List.of(), List.of(), List.of(%s));
                    }
                    public Job create(JobConfig config) {
                        return new Job() {
                            public String name() { return config.name(); }
                            public String type() { return "%s"; }
                            public JobResult run() { return JobResult.ok("no context", 0L); }
                            public JobResult run(JobContext ctx) {
                                NotificationAccess feed = ctx.services().get(NotificationAccess.class);
                                feed.notify(Notification.create("job", "JOB_RUN", ctx.runId(),
                                        "from the pack", "emitted via a declared grant", "pack:" + name()));
                                return JobResult.ok("notified", 0L);
                            }
                        };
                    }
                }
                """.formatted(typeId, cls, typeId, requiresLiteral, typeId);

        Path classes = compile(work, "com/acme/pack/" + cls + ".java", src);
        writeJar(jar, classes, packId, "1.0.0", Map.of("com.gamma.job.JobTypeProvider", fqcn));
        return jar;
    }

    private static ExpressionContext ctx() {
        return new ExpressionContext("run-1", java.time.Instant.now(), "cron", java.time.ZoneId.systemDefault(),
                java.util.Optional::empty, (j, n) -> java.util.Optional.empty(), Map.of());
    }

    /** Compile an {@link ExpressionProvider} declaring {@code token} (and nothing else) into a real pack
     *  jar — the "a plugin adds vocabulary" case, with no Job Type in the pack at all. */
    /** Compile a {@link com.gamma.pipeline.PipelineNodeType} and package it as a node-type-only pack. */
    private static Path buildNodeTypePackJar(Path work, Path jar, String nodeType, String cls, String packId)
            throws Exception {
        String fqcn = "com.acme.pack." + cls;
        String src = """
                package com.acme.pack;
                import com.gamma.pipeline.PipelineNodeType;
                public class %s implements PipelineNodeType {
                    public String type() { return "%s"; }
                    public String label() { return "Redact"; }
                }
                """.formatted(cls, nodeType);

        // `compile` builds against the com.gamma.job code-source location — the same jar/classes dir that
        // holds com.gamma.pipeline, so the node-type SPI resolves without a second classpath entry.
        Path classes = compile(work, "com/acme/pack/" + cls + ".java", src);
        writeJar(jar, classes, packId, Map.of("com.gamma.pipeline.PipelineNodeType", fqcn));
        return jar;
    }

    /**
     * Compile a pack with an optional {@link com.gamma.pipeline.PipelineNodeType} ({@code nodeType}, or
     * {@code null} for none) and a raw-{@code Connection} {@link com.gamma.pipeline.exec.PipelineNodeExecutor}
     * for {@code executorKind} — the seam a pack may no longer carry (D-2).
     */
    private static Path buildNodePackJar(Path work, Path jar, String cls, String packId,
                                         String nodeType, String executorKind) throws Exception {
        return buildNodePackJar(work, jar, cls, packId, nodeType, executorKind, "EXECUTED");
    }

    /** As above, with the descriptor declaring {@code ExecutionMode.<mode>} (S2-1). */
    private static Path buildNodePackJar(Path work, Path jar, String cls, String packId,
                                         String nodeType, String executorKind, String mode) throws Exception {
        String src = """
                package com.acme.pack;
                import com.gamma.pipeline.PipelineNode;
                import com.gamma.pipeline.PipelineNodeType;
                import com.gamma.pipeline.exec.PipelineNodeExecutor;
                import com.gamma.pipeline.exec.RowShaper;
                import java.util.List;
                public class %s {
                    public static class Type implements PipelineNodeType {
                        public String type() { return "%s"; }
                        public java.util.Optional<com.gamma.pipeline.ExecutionMode> mode() {
                            return java.util.Optional.of(com.gamma.pipeline.ExecutionMode.%s);
                        }
                    }
                    public static class Exec implements PipelineNodeExecutor {
                        public String type() { return "%s"; }
                        public List<RowShaper.Relation> shape(java.sql.Connection conn, PipelineNode node,
                                String input, String outPrefix, RowShaper.ReferenceResolver refs)
                                throws java.sql.SQLException {
                            String data = outPrefix + "__data";
                            try (java.sql.Statement st = conn.createStatement()) {
                                st.execute("CREATE TABLE \\"" + data + "\\" AS SELECT * FROM \\"" + input + "\\"");
                            }
                            return List.of(new RowShaper.Relation("data", data));
                        }
                    }
                }
                """.formatted(cls, nodeType == null ? "unused" : nodeType, mode, executorKind);

        Path classes = compile(work, "com/acme/pack/" + cls + ".java", src);
        Map<String, String> services = new java.util.LinkedHashMap<>();
        if (nodeType != null) services.put("com.gamma.pipeline.PipelineNodeType", "com.acme.pack." + cls + "$Type");
        services.put("com.gamma.pipeline.exec.PipelineNodeExecutor", "com.acme.pack." + cls + "$Exec");
        writeJar(jar, classes, packId, services);
        return jar;
    }

    /**
     * Compile a pack with an optional {@link com.gamma.pipeline.PipelineNodeType} ({@code nodeType}, or
     * {@code null} for none) declaring {@code ExecutionMode.<mode>}, and a
     * {@link com.gamma.pipeline.exec.StepExecutor} for {@code stepKind} that requires
     * {@code [<requiresLiteral>]} and runs {@code body} (with {@code ctx} in scope).
     */
    private static Path buildStepPackJar(Path work, Path jar, String cls, String packId, String nodeType,
                                         String stepKind, String mode, String requiresLiteral, String body)
            throws Exception {
        String src = """
                package com.acme.pack;
                import com.gamma.etl.TypeFlow;
                import com.gamma.pipeline.PipelineNodeType;
                import com.gamma.pipeline.exec.StepContext;
                import com.gamma.pipeline.exec.StepExecutor;
                import com.gamma.pipeline.exec.StepInput;
                import com.gamma.pipeline.exec.StepOutput;
                import java.util.List;
                import java.util.Set;
                public class %s {
                    public static class Type implements PipelineNodeType {
                        public String type() { return "%s"; }
                        public java.util.Optional<com.gamma.pipeline.ExecutionMode> mode() {
                            return java.util.Optional.of(com.gamma.pipeline.ExecutionMode.%s);
                        }
                    }
                    public static class Step implements StepExecutor {
                        public String type() { return "%s"; }
                        public Set<String> requires() { return Set.of(%s); }
                        public void execute(StepContext ctx) throws Exception {
                            %s
                        }
                    }
                }
                """.formatted(cls, nodeType == null ? "unused" : nodeType, mode, stepKind, requiresLiteral, body);

        Path classes = compile(work, "com/acme/pack/" + cls + ".java", src);
        Map<String, String> services = new java.util.LinkedHashMap<>();
        if (nodeType != null) services.put("com.gamma.pipeline.PipelineNodeType", "com.acme.pack." + cls + "$Type");
        services.put("com.gamma.pipeline.exec.StepExecutor", "com.acme.pack." + cls + "$Step");
        writeJar(jar, classes, packId, services);
        return jar;
    }

    private static Path buildExpressionPackJar(Path work, Path jar, String token, String cls, String packId)
            throws Exception {
        String fqcn = "com.acme.pack." + cls;
        String src = """
                package com.acme.pack;
                import com.gamma.job.*;
                import java.util.List;
                import java.util.Optional;
                public class %s implements ExpressionProvider {
                    public List<ExpressionDecl> declarations() {
                        return List.of(ExpressionDecl.literal("%s", ParamType.STRING, "the tenant", "acme"));
                    }
                    public Optional<String> evaluate(String expr, ExpressionContext ctx) {
                        return Optional.of("acme");
                    }
                }
                """.formatted(cls, token);

        Path classes = compile(work, "com/acme/pack/" + cls + ".java", src);
        writeJar(jar, classes, packId, Map.of("com.gamma.job.ExpressionProvider", fqcn));
        return jar;
    }

    // ── fixture: compile a provider + Job and package them into a real jar off the test classpath ──

    private static JobConfig jobConfig(String name, String type) {
        return JobConfig.fromMap(Map.of("job", Map.of("name", name, "type", type)));
    }

    /**
     * Compile a {@link JobTypeProvider} whose {@code descriptor().id()} is {@code descriptorId} and whose
     * {@code @JobTypeMeta.id} is {@code metaId} (equal ⇒ valid pack; different ⇒ rejected), plus its Job,
     * and package them with a {@code META-INF/services} entry + {@code Pack-Id}/{@code Pack-Version} manifest.
     */
    static Path buildPackJar(Path work, Path jar, String descriptorId, String metaId,
                                     String cls, String packId, String version) throws Exception {
        String fqcn = "com.acme.pack." + cls;
        String src = """
                package com.acme.pack;
                import com.gamma.job.*;
                import java.util.List;
                @JobTypeMeta(id = "%s", title = "Test")
                public class %s implements JobTypeProvider {
                    public JobTypeDescriptor descriptor() {
                        return new JobTypeDescriptor("%s", "Test", "test pack type",
                                List.of(), List.of(), List.of());
                    }
                    public Job create(JobConfig config) {
                        return new Job() {
                            public String name() { return config.name(); }
                            public String type() { return "%s"; }
                            public JobResult run() { return JobResult.ok("hi", 0L); }
                        };
                    }
                }
                """.formatted(metaId, cls, descriptorId, descriptorId);

        Path classes = compile(work, "com/acme/pack/" + cls + ".java", src);
        writeJar(jar, classes, packId, version, Map.of("com.gamma.job.JobTypeProvider", fqcn));
        return jar;
    }

    /** Compile one source file against the real {@code com.gamma.job} code-source location (robust under
     *  Maven surefire, where {@code java.class.path} is just the booter jar); returns the classes dir. */
    /**
     * A pack jar whose declared provider class is present but CANNOT BE DEFINED. The bytes carry the
     * class-file magic and nothing else, so the pack's own {@link java.net.URLClassLoader} finds the
     * resource (the parent has no such class) and {@code defineClass} raises {@code ClassFormatError} —
     * a {@link LinkageError}, thrown out of {@code ServiceLoader.hasNext()} rather than wrapped in a
     * {@code ServiceConfigurationError}. That is the same shape as the real-world case (a jar compiled
     * for a newer Java throws {@code UnsupportedClassVersionError}), without needing a second JDK.
     */
    private static Path buildUnlinkablePackJar(Path work, Path jar, String packId) throws Exception {
        Path classes = Files.createDirectories(
                Files.createTempDirectory(work, "broken-").resolve("classes"));
        Path cls = classes.resolve("com/acme/broken/BrokenProvider.class");
        Files.createDirectories(cls.getParent());
        Files.write(cls, new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 0});
        writeJar(jar, classes, packId, Map.of("com.gamma.job.JobTypeProvider", "com.acme.broken.BrokenProvider"));
        return jar;
    }

    private static Path compile(Path work, String relPath, String src) throws Exception {
        Path stage = Files.createTempDirectory(work, "stage-");
        Path srcFile = stage.resolve(relPath);
        Files.createDirectories(srcFile.getParent());
        Files.writeString(srcFile, src);
        Path classes = Files.createDirectories(stage.resolve("classes"));

        // The engine's own code source, plus the running test classpath: a StepContext signature names
        // inspecto-etl/inspecto-util types (TypeFlow, RunLog) that live in other jars.
        String apiCp = Path.of(JobTypeProvider.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                + java.io.File.pathSeparator + System.getProperty("java.class.path");
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fm = jc.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            List<String> opts = List.of("-classpath", apiCp, "-d", classes.toString());
            boolean ok = jc.getTask(null, fm, null, opts, null,
                    fm.getJavaFileObjects(srcFile.toFile())).call();
            assertTrue(ok, "pack source compiled");
        }
        return classes;
    }

    static void writeJar(Path jar, Path classes, String packId, Map<String, String> services)
            throws Exception {
        writeJar(jar, classes, packId, "1.0.0", services);
    }

    /** Package compiled classes with the {@code Pack-Id}/{@code Pack-Version} manifest and one
     *  {@code META-INF/services} entry per {@code (SPI → impl)}. */
    private static void writeJar(Path jar, Path classes, String packId, String version,
                                 Map<String, String> services) throws Exception {
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().putValue("Pack-Id", packId);
        mf.getMainAttributes().putValue("Pack-Version", version);
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar), mf);
             Stream<Path> files = Files.walk(classes)) {
            for (Path p : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                jos.putNextEntry(new JarEntry(classes.relativize(p).toString().replace('\\', '/')));
                Files.copy(p, jos);
                jos.closeEntry();
            }
            for (var e : services.entrySet()) {
                jos.putNextEntry(new JarEntry("META-INF/services/" + e.getKey()));
                writeUtf8(jos, e.getValue() + "\n");
                jos.closeEntry();
            }
        }
    }

    private static void writeUtf8(OutputStream os, String s) throws Exception {
        os.write(s.getBytes(StandardCharsets.UTF_8));
    }
}
