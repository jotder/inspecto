package com.gamma.job;

import com.gamma.parse.ParserPlugin;
import com.gamma.parse.Parsers;
import com.gamma.signal.Severity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A parser is the fifth Job Pack kind (parser-plugins-trust-design.md slice P2, operator D1 2026-09-25): a
 * {@link ParserPlugin} in an allowlisted pack registers into {@link Parsers} under the pack's owner key, an
 * unload or a revocation takes it back, and a pack whose parser collides with a built-in or with another
 * pack is rejected WHOLE — every other kind it carries rolled back.
 *
 * <p>The pack classes are compiled at test time into a real jar, so they are off the test classpath. Every
 * refusal is paired with a probe that would otherwise succeed: the same pack shape with a free id loads.
 */
class JobPackParserTest {

    private static final class Sink implements JobPackManager.SignalSink {
        final List<Map<String, Object>> rejected = new CopyOnWriteArrayList<>();
        @Override public void emit(String type, Severity sev, Map<String, Object> payload) {
            if ("job.pack.rejected".equals(type)) rejected.add(payload);
        }
    }

    private List<String> baseline;

    @BeforeEach
    void needsJavac() {
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "needs a JDK (javac) to build the pack jar");
        baseline = ids();
    }

    @AfterEach
    void clean() {
        JobPackManagerTest.clearTrust();
        // A failing assertion must not leak a pack parser into the process-wide registry for later classes.
        for (String owner : List.of("cdr-1.jar", "cdr-2.jar", "evil.jar", "mixed.jar")) Parsers.deregister(owner);
    }

    private static List<String> ids() {
        return Parsers.catalog().stream().map(ParserPlugin::id).toList();
    }

    private static JobPackManager manager(Path packs, JobTypeRegistry registry, Sink sink) {
        return new JobPackManager(packs.toString(), registry, ExpressionRegistry.withBuiltins(), sink);
    }

    // ── P2: registration, provenance, unload ─────────────────────────────────────────────────────────

    @Test
    void aParserOnlyPackLoadsAndItsParserJoinsTheCatalogWithThePackAsProvenance(@TempDir Path work) throws Exception {
        Path packs = Files.createDirectories(work.resolve("packs"));
        buildParserPackJar(work, packs.resolve("cdr-1.jar"), "acme_cdr", "AcmeCdr", false);
        JobPackManagerTest.trustEveryJarIn(packs);
        try (JobPackManager mgr = manager(packs, new JobTypeRegistry(), new Sink())) {
            assertEquals(List.of("cdr-1.jar"), mgr.rescan().get("loaded"), "a parser-only pack is a valid pack");
            List<String> now = ids();
            assertEquals(baseline, now.subList(0, baseline.size()), "built-ins and classpath parsers keep their order");
            assertEquals("acme_cdr", now.get(now.size() - 1));
            assertEquals("pack:cdr-1.jar", Parsers.sourceOf("acme_cdr"));
            ParserPlugin p = Parsers.get("acme_cdr").orElseThrow();
            assertNotSame(ClassLoader.getSystemClassLoader(), p.getClass().getClassLoader(),
                    "the parser was defined by the pack's own loader, not found on the classpath");
            assertTrue(Parsers.ingestable(p), "it names its ingester");
        }
    }

    @Test
    void removingTheJarUnregistersItsParser(@TempDir Path work) throws Exception {
        Path packs = Files.createDirectories(work.resolve("packs"));
        Path jar = buildParserPackJar(work, packs.resolve("cdr-1.jar"), "acme_cdr", "AcmeCdr", false);
        JobPackManagerTest.trustEveryJarIn(packs);
        try (JobPackManager mgr = manager(packs, new JobTypeRegistry(), new Sink())) {
            mgr.rescan();
            assertTrue(Parsers.get("acme_cdr").isPresent());
            Files.delete(jar);
            assertEquals(List.of("cdr-1.jar"), mgr.rescan().get("unloaded"));
            assertTrue(Parsers.get("acme_cdr").isEmpty(), "an unloaded pack's parser leaves the catalog");
            assertEquals(baseline, ids());
        }
    }

    @Test
    void revokingThePacksHashUnregistersItsParser(@TempDir Path work) throws Exception {
        Path packs = Files.createDirectories(work.resolve("packs"));
        buildParserPackJar(work, packs.resolve("cdr-1.jar"), "acme_cdr", "AcmeCdr", false);
        JobPackManagerTest.trustEveryJarIn(packs);
        try (JobPackManager mgr = manager(packs, new JobTypeRegistry(), new Sink())) {
            mgr.rescan();
            assertTrue(Parsers.get("acme_cdr").isPresent());
            Files.writeString(Path.of(System.getProperty("jobs.packs.allowlist")), "");   // revoke = remove the line
            assertEquals(List.of("cdr-1.jar"), mgr.rescan().get("rejected"));
            assertTrue(Parsers.get("acme_cdr").isEmpty(), "a revoked pack's parser leaves the catalog");
        }
    }

    // ── P2: atomic refusal on collision ──────────────────────────────────────────────────────────────

    @Test
    void aPackWhoseParserCollidesWithABuiltinIsRejectedWholeAndItsJobTypeRolledBack(@TempDir Path work) throws Exception {
        Path packs = Files.createDirectories(work.resolve("packs"));
        buildParserPackJar(work, packs.resolve("evil.jar"), "delimited", "EvilDelimited", true);
        JobPackManagerTest.trustEveryJarIn(packs);
        JobTypeRegistry registry = new JobTypeRegistry();
        ParserPlugin builtin = Parsers.get("delimited").orElseThrow();
        Sink sink = new Sink();
        try (JobPackManager mgr = manager(packs, registry, sink)) {
            assertEquals(List.of("evil.jar"), mgr.rescan().get("rejected"));
            assertSame(builtin, Parsers.get("delimited").orElseThrow(), "the built-in still answers");
            assertFalse(registry.has("acme.evildelimited"), "the pack's Job Type was rolled back with it");
            assertEquals(baseline, ids());
            assertTrue(String.valueOf(sink.rejected.get(0).get("cause")).contains("'delimited'"),
                    () -> "the cause names the parser id: " + sink.rejected);
        }
    }

    @Test
    void theSameMixedPackWithAFreeParserIdLoadsBothKinds(@TempDir Path work) throws Exception {
        // The probe that would otherwise succeed: the refusal above is the collision, not the fixture.
        Path packs = Files.createDirectories(work.resolve("packs"));
        buildParserPackJar(work, packs.resolve("mixed.jar"), "acme_mixed", "AcmeMixed", true);
        JobPackManagerTest.trustEveryJarIn(packs);
        JobTypeRegistry registry = new JobTypeRegistry();
        try (JobPackManager mgr = manager(packs, registry, new Sink())) {
            assertEquals(List.of("mixed.jar"), mgr.rescan().get("loaded"));
            assertTrue(registry.has("acme.acmemixed"));
            assertEquals("pack:mixed.jar", Parsers.sourceOf("acme_mixed"));
        }
    }

    @Test
    void aSecondPackClaimingTheSameParserIdIsRejectedAndTheFirstKeepsIt(@TempDir Path work) throws Exception {
        Path packs = Files.createDirectories(work.resolve("packs"));
        buildParserPackJar(work, packs.resolve("cdr-1.jar"), "acme_cdr", "AcmeCdr", false);
        buildParserPackJar(work, packs.resolve("cdr-2.jar"), "acme_cdr", "OtherCdr", false);
        JobPackManagerTest.trustEveryJarIn(packs);
        try (JobPackManager mgr = manager(packs, new JobTypeRegistry(), new Sink())) {
            Map<String, Object> s = mgr.rescan();   // directory order: cdr-1 before cdr-2
            assertEquals(List.of("cdr-1.jar"), s.get("loaded"));
            assertEquals(List.of("cdr-2.jar"), s.get("rejected"));
            assertEquals("pack:cdr-1.jar", Parsers.sourceOf("acme_cdr"), "first pack wins");
        }
    }

    // ── P3: the ingester resolves through the pack's loader, and the pack is pinned for the ingest ─────

    private static final String INGESTER = "com.acme.pack.AcmeCdr$Ingest";

    @Test
    void aPackParsersIngesterIngestsEndToEndThroughThePacksOwnLoader(@TempDir Path work) throws Exception {
        Path packs = Files.createDirectories(work.resolve("packs"));
        buildParserPackJar(work, packs.resolve("cdr-1.jar"), "acme_cdr", "AcmeCdr", false);
        JobPackManagerTest.trustEveryJarIn(packs);
        try (JobPackManager mgr = manager(packs, new JobTypeRegistry(), new Sink())) {
            mgr.rescan();
            // The negative probe: the engine's own loader — what Class.forName(name) used — cannot see it.
            assertThrows(ClassNotFoundException.class, () -> Class.forName(INGESTER),
                    "the pack ingester is genuinely off the classpath");
            Ingest in = Ingest.prepare(work, INGESTER);
            com.gamma.inspector.ConsignmentIngestor.process(in.batch(), in.cfg(), in.audit());
            assertFalse(in.landedFiles().isEmpty(), "the CALL segment landed");
            assertEquals(2, in.callRows(), "both CALL rows landed via the pack's ingester");
        }
    }

    @Test
    void anUnloadMidIngestDefersTheLoaderCloseUntilTheIngestEnds(@TempDir Path work) throws Exception {
        Path packs = Files.createDirectories(work.resolve("packs"));
        Path jar = buildParserPackJar(work, packs.resolve("cdr-1.jar"), "acme_cdr", "AcmeCdr", false);
        JobPackManagerTest.trustEveryJarIn(packs);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (JobPackManager mgr = manager(packs, new JobTypeRegistry(), new Sink())) {
            mgr.rescan();
            Ingest in = Ingest.prepare(work, INGESTER);
            Path hold = Path.of(in.input() + ".hold"), inRun = Path.of(in.input() + ".inrun");
            Files.writeString(hold, "x");
            var run = pool.submit(() -> {
                com.gamma.inspector.ConsignmentIngestor.process(in.batch(), in.cfg(), in.audit());
                return null;
            });
            long end = System.currentTimeMillis() + 20_000;
            while (!Files.exists(inRun) && System.currentTimeMillis() < end) Thread.sleep(20);
            assertTrue(Files.exists(inRun), "the ingest reached pack code");

            Files.delete(jar);
            assertEquals(List.of("cdr-1.jar"), mgr.rescan().get("unloaded"));
            assertTrue(Parsers.get("acme_cdr").isEmpty(), "the parser leaves the catalog at once");
            assertTrue(mgr.isDraining("cdr-1.jar"), "the loader close waits for the in-flight ingest");

            Files.delete(hold);
            run.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertFalse(mgr.isDraining("cdr-1.jar"), "the close finishes once the ingest releases the pack");
            assertEquals(2, in.callRows(), "the ingest finished on the vetted pack code it started with");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aPipelineNamingAnUnloadedPacksIngesterFailsWithANamedError(@TempDir Path work) throws Exception {
        Path packs = Files.createDirectories(work.resolve("packs"));
        Path jar = buildParserPackJar(work, packs.resolve("cdr-1.jar"), "acme_cdr", "AcmeCdr", false);
        JobPackManagerTest.trustEveryJarIn(packs);
        try (JobPackManager mgr = manager(packs, new JobTypeRegistry(), new Sink())) {
            mgr.rescan();
            Files.delete(jar);
            mgr.rescan();
            Ingest in = Ingest.prepare(work, INGESTER);
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> com.gamma.inspector.ConsignmentIngestor.process(in.batch(), in.cfg(), in.audit()));
            assertTrue(e.getMessage().contains(INGESTER) && e.getMessage().contains("GET /jobs/packs"),
                    () -> "the error names the ingester and where to look: " + e.getMessage());
            for (Throwable t = e; t != null; t = t.getCause())
                assertFalse(t instanceof ClassNotFoundException, "a named error, not a ClassNotFoundException");
        }
    }

    /** One CALL-segment Pipeline naming {@code ingester}, and one input file with two CALL lines. */
    private record Ingest(Path input, com.gamma.etl.PipelineConfig cfg, com.gamma.etl.Consignment batch,
                          com.gamma.etl.ConsignmentAuditWriter audit) {
        static Ingest prepare(Path work, String ingester) throws Exception {
            // Under the safety-root jail trustEveryJarIn narrows to (the allowlist sits outside it).
            Path dir = Files.createDirectories(work.resolve("jail").resolve("pipe"));
            Path schema = dir.resolve("call_schema.toon");
            Files.writeString(schema, """
                    raw:
                      name: call
                      format: CSV
                      fields[3]{name,selector,type}:
                        ID,"0",VARCHAR
                        EVENT_TYPE,"1",VARCHAR
                        EVENT_DATE,"2",DATE
                    mapping:
                      canonicalName: call
                      rawName: call
                      rules[3]{targetColumn,sourceExpression,transformType}:
                        ID,ID,DIRECT
                        EVENT_TYPE,EVENT_TYPE,DIRECT
                        EVENT_DATE,EVENT_DATE,DIRECT
                    """);
            String d = dir.toString().replace('\\', '/');
            Path pipeline = dir.resolve("cdr_pipeline.toon");
            Files.writeString(pipeline, """
                    name: ACME_CDR
                    version: 1
                    dirs:
                      poll: %1$s/inbox
                      database: %1$s/db
                      backup: %1$s/backup
                      temp: %1$s/temp
                      errors: %1$s/errors
                      quarantine: %1$s/quarantine
                      status_dir: %1$s/status
                      log_dir: %1$s/logs
                    output:
                      format: CSV
                    processing:
                      threads: 1
                      file_pattern: "glob:**/*.cdr"
                      ingester: %2$s
                      segments:
                        CALL: %3$s
                      csv_settings:
                        delimiter: ","
                        skip_header_lines: 0
                        skip_junk_lines: 0
                        skip_tail_lines: 0
                        date_formats[1]: "%%Y-%%m-%%d"
                        timestamp_formats[1]: "%%Y-%%m-%%d"
                    """.formatted(d, ingester, schema.toString().replace('\\', '/')));
            com.gamma.etl.PipelineConfig cfg = com.gamma.etl.PipelineConfig.load(pipeline.toString());
            Path inbox = Files.createDirectories(Path.of(cfg.dirs().poll()));
            Path input = inbox.resolve("acme_20260925.cdr");
            Files.writeString(input, "CALL,C001,2026-09-25\nCALL,C002,2026-09-25\n");
            File f = input.toFile();
            var member = new com.gamma.etl.Consignment.Member(f, 0, f.length(),
                    new com.gamma.etl.SchemaSelector.Selection(Map.of(), null));
            var batch = new com.gamma.etl.Consignment(cfg.identity().runTimestamp() + "_acme_0001", "acme", null,
                    List.of(member));
            var audit = new com.gamma.etl.ConsignmentAuditWriter(cfg.dirs().statusFilePath(),
                    cfg.dirs().batchesFilePath(), cfg.dirs().lineageFilePath());
            return new Ingest(input, cfg, batch, audit);
        }

        List<Path> landedFiles() throws Exception {
            Path out = Path.of(cfg.dirs().database(), "CALL");
            if (!Files.exists(out)) return List.of();
            try (var s = Files.walk(out)) { return s.filter(Files::isRegularFile).toList(); }
        }

        /** Rows across every landed CALL file, read back by DuckDB. */
        long callRows() throws Exception {
            long n = 0;
            File db = com.gamma.util.DuckDbUtil.tempDbFile("acme_rb_");
            try (var conn = com.gamma.util.DuckDbUtil.openConnection(db); var st = conn.createStatement()) {
                for (Path p : landedFiles()) {
                    try (var rs = st.executeQuery("SELECT count(*) FROM read_csv('"
                            + p.toString().replace('\\', '/') + "')")) {
                        rs.next();
                        n += rs.getLong(1);
                    }
                }
            } finally {
                com.gamma.util.DuckDbUtil.deleteTempDb(db);
            }
            return n;
        }
    }

    // ── fixture ──────────────────────────────────────────────────────────────────────────────────────

    /**
     * Compile a pack carrying a {@link ParserPlugin} with {@code parserId} that names its own
     * {@code StreamingFileIngester} ({@code com.acme.pack.<cls>$Ingest}), and — with {@code withJobType} —
     * also a Job Type {@code acme.<cls lowercased>}, so a rejection can be seen rolling a second kind back.
     *
     * <p>The ingester emits one {@code CALL} record per {@code CALL,<id>,<date>} line. When a file
     * {@code <input>.hold} exists it first writes {@code <input>.inrun} and waits (up to 30 s) for the hold
     * to be deleted, so a test can act while pack code is mid-ingest.
     */
    static Path buildParserPackJar(Path work, Path jar, String parserId, String cls, boolean withJobType)
            throws Exception {
        String typeId = "acme." + cls.toLowerCase();
        String src = """
                package com.acme.pack;
                import com.gamma.config.spec.FieldSpec;
                import com.gamma.etl.PipelineConfig;
                import com.gamma.etl.RecordSink;
                import com.gamma.etl.StreamingFileIngester;
                import com.gamma.job.*;
                import com.gamma.parse.ParseResult;
                import com.gamma.parse.ParserPlugin;
                import java.io.File;
                import java.nio.file.Files;
                import java.nio.file.Path;
                import java.util.List;
                import java.util.Map;
                import java.util.Optional;
                public class %1$s implements ParserPlugin {
                    public String id() { return "%2$s"; }
                    public String label() { return "Acme %1$s"; }
                    public boolean hierarchical() { return false; }
                    public List<FieldSpec> grammarSchema() { return List.of(); }
                    public ParseResult preview(byte[] sample, Map<String, Object> grammar) {
                        return new ParseResult.Table(List.of("line"),
                                List.of(Map.of("line", new String(sample))), 1, 0);
                    }
                    public Optional<String> ingesterClass() { return Optional.of("com.acme.pack.%1$s$Ingest"); }

                    public static class Ingest implements StreamingFileIngester {
                        public void ingest(File file, RecordSink sink, int srcId, PipelineConfig cfg) throws Exception {
                            Path hold = Path.of(file.getPath() + ".hold");
                            if (Files.exists(hold)) {
                                Files.writeString(Path.of(file.getPath() + ".inrun"), "x");
                                long end = System.currentTimeMillis() + 30_000;
                                while (Files.exists(hold) && System.currentTimeMillis() < end) Thread.sleep(20);
                            }
                            for (String line : Files.readAllLines(file.toPath())) {
                                String[] p = line.split(",", 3);
                                if (p.length == 3 && p[0].equals("CALL")) sink.emit("CALL", p[1], "CALL", p[2]);
                            }
                        }
                    }

                    @JobTypeMeta(id = "%3$s", title = "Test")
                    public static class Type implements JobTypeProvider {
                        public JobTypeDescriptor descriptor() {
                            return new JobTypeDescriptor("%3$s", "Test", "test", List.of(), List.of(), List.of());
                        }
                        public Job create(JobConfig config) {
                            return new Job() {
                                public String name() { return config.name(); }
                                public String type() { return "%3$s"; }
                                public JobResult run() { return JobResult.ok("hi", 0L); }
                            };
                        }
                    }
                }
                """.formatted(cls, parserId, typeId);
        Path classes = compile(work, "com/acme/pack/" + cls + ".java", src);
        Map<String, String> services = new LinkedHashMap<>();
        services.put("com.gamma.parse.ParserPlugin", "com.acme.pack." + cls);
        if (withJobType) services.put("com.gamma.job.JobTypeProvider", "com.acme.pack." + cls + "$Type");
        JobPackManagerTest.writeJar(jar, classes, "acme." + cls.toLowerCase(), services);
        return jar;
    }

    /** Compile against the engine, ETL, config and API code sources (the SPIs a parser pack builds on). */
    private static Path compile(Path work, String relPath, String src) throws Exception {
        Path stage = Files.createTempDirectory(work, "stage-");
        Path srcFile = stage.resolve(relPath);
        Files.createDirectories(srcFile.getParent());
        Files.writeString(srcFile, src);
        Path classes = Files.createDirectories(stage.resolve("classes"));
        String cp = String.join(File.pathSeparator,
                codeSource(JobTypeProvider.class), codeSource(com.gamma.etl.StreamingFileIngester.class),
                codeSource(com.gamma.config.spec.FieldSpec.class), codeSource(com.gamma.api.PublicApi.class));
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fm = jc.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            boolean ok = jc.getTask(null, fm, null, List.of("-classpath", cp, "-d", classes.toString()), null,
                    fm.getJavaFileObjects(srcFile.toFile())).call();
            assertTrue(ok, "pack source compiled");
        }
        return classes;
    }

    private static String codeSource(Class<?> c) throws Exception {
        return Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }
}
