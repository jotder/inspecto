package com.gamma.job;

import com.gamma.util.RunLog;

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

    @Test
    void loadsRegistersAndUnloadsAPackTypeThroughAnIsolatedClassLoader(@TempDir Path work) throws Exception {
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "needs a JDK (javac) to build the pack jar");
        Path packsDir = Files.createDirectories(work.resolve("packs"));
        Path jar = buildPackJar(work, packsDir.resolve("greet-1.jar"), "acme.greet", "acme.greet",
                "GreetType", "acme-greet", "1.0.0");

        JobTypeRegistry registry = new JobTypeRegistry();
        ExpressionRegistry expressions = ExpressionRegistry.withBuiltins();
        Sink sink = new Sink();
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
        try (JobPackManager mgr = new JobPackManager(packsDir.toString(), registry, expressions, sink)) {
            mgr.scanAtStartup();
            assertTrue(registry.has("report"));
            // The built-in still owns 'report' — its factory throws; the pack's would have returned a Job.
            assertThrows(UnsupportedOperationException.class,
                    () -> registry.create("report", jobConfig("r", "report")),
                    "the built-in still owns 'report' (pack did not displace it)");
            assertTrue(mgr.inventory().isEmpty(), "rejected pack is not in the inventory");
            assertTrue(sink.types.contains("job.pack.rejected"));
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
        try (JobPackManager mgr = new JobPackManager(packsDir.toString(), registry, expressions, sink)) {
            try {
                mgr.scanAtStartup();

                assertTrue(com.gamma.pipeline.PipelineNodeTypes.isKnown("transform.redact"),
                        "a node-type-only pack is a valid pack");
                assertEquals("acme-node-1.jar",
                        com.gamma.pipeline.PipelineNodeTypes.ownerOf("transform.redact").orElseThrow());
                assertTrue(sink.types.contains("job.pack.loaded"));
                // The authoring surface the type exists for: the served step catalog offers it, so the
                // palette can. (`StepKindRegistry.current()` — the parser's load-time gate — is
                // package-private in com.gamma.etl and delegates to the same registry.)
                assertTrue(com.gamma.pipeline.PipelineProjection.stepCatalog().stream()
                                .anyMatch(e -> "transform.redact".equals(e.get("type"))),
                        "a contributed type must reach the served step catalog");

                Files.delete(jar);
                mgr.rescan();

                assertFalse(com.gamma.pipeline.PipelineNodeTypes.isKnown("transform.redact"),
                        "unload takes the pack's node type back with it");
                assertTrue(com.gamma.pipeline.PipelineProjection.stepCatalog().stream()
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
        try (JobPackManager mgr = new JobPackManager(packsDir.toString(), registry, expressions, sink)) {
            mgr.scanAtStartup();

            assertTrue(sink.types.contains("job.pack.rejected"), "rejected loudly, not skipped quietly");
            assertNotEquals("acme", expressions.evaluate("$today", ctx()).orElse(null),
                    "the built-in $today still owns its token");
            assertEquals(java.time.LocalDate.now().toString(), expressions.evaluate("$today", ctx()).orElse(null));
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

        try (JobPackManager mgr = new JobPackManager(packsDir.toString(), registry,
                ExpressionRegistry.withBuiltins(), sink)) {
            mgr.scanAtStartup();

            assertFalse(registry.has("acme.greedy"), "an undeclarable grant refuses the type");
            assertTrue(mgr.inventory().isEmpty(), "and the whole pack is rejected, not partially loaded");
            assertTrue(sink.types.contains("job.pack.rejected"));
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
    private static Path buildPackJar(Path work, Path jar, String descriptorId, String metaId,
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
    private static Path compile(Path work, String relPath, String src) throws Exception {
        Path stage = Files.createTempDirectory(work, "stage-");
        Path srcFile = stage.resolve(relPath);
        Files.createDirectories(srcFile.getParent());
        Files.writeString(srcFile, src);
        Path classes = Files.createDirectories(stage.resolve("classes"));

        String apiCp = Path.of(JobTypeProvider.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toString();
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fm = jc.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            List<String> opts = List.of("-classpath", apiCp, "-d", classes.toString());
            boolean ok = jc.getTask(null, fm, null, opts, null,
                    fm.getJavaFileObjects(srcFile.toFile())).call();
            assertTrue(ok, "pack source compiled");
        }
        return classes;
    }

    private static void writeJar(Path jar, Path classes, String packId, Map<String, String> services)
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
