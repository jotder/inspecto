package com.gamma.job;

import com.gamma.etl.BatchEventBus;
import com.gamma.ops.InMemoryObjectStore;
import com.gamma.ops.ObjectService;
import com.gamma.ops.ObjectType;
import com.gamma.query.DatasetRelation;
import com.gamma.signal.Severity;
import com.gamma.signal.SignalEmitter;
import com.gamma.util.Scheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code objects.analytics} Job Type: samples {@link ObjectService#analytics(ObjectType)} for each object
 * type into tall Parquet rows under {@code <dataDir>/ops_analytics/} and result-stamps the {@code ops_analytics}
 * Dataset. Covers the pure flatten, the end-to-end write (append across runs + retention), read-back through
 * the real {@link DatasetRelation} seam (the whole point of the feature — Studio/BI binding), and the
 * fail-closed paths.
 */
class ObjectsAnalyticsJobTest {

    private static JobConfig cfg(Map<String, String> config) {
        return new JobConfig("ops_analytics_sample", "objects.analytics", null, null, true, false,
                config, null, null);
    }

    private static ObjectService seeded() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        svc.open(ObjectType.INCIDENT, "one", "d", "HIGH", "CRITICAL", null, null, "corr",
                Map.of("category", "Pipeline / Ingest", "impactAmount", "150.5", "recordsAffected", "20"));
        svc.open(ObjectType.INCIDENT, "two", "d", "HIGH", "LOW", null, null, "corr",
                Map.of("category", "Pipeline / Parse"));
        svc.open(ObjectType.CASE, "a case", "d", "HIGH", "LOW", null, null, "corr", Map.of());
        return svc;
    }

    /** Rows keyed {@code type|axis|key → value}, the shape the assertions actually care about. */
    private static Map<String, Double> byKey(List<Object[]> rows) {
        Map<String, Double> out = new HashMap<>();
        for (Object[] r : rows) out.put(r[0] + "|" + r[1] + "|" + r[2], (Double) r[3]);
        return out;
    }

    // ── 1. the pure flatten ───────────────────────────────────────────────────────────

    @Test
    void flattensTheNestedRollupIntoTallRows() {
        Map<String, Object> rollup = seeded().analytics(ObjectType.INCIDENT);

        Map<String, Double> rows = byKey(ObjectsAnalyticsJob.flatten(ObjectType.INCIDENT, rollup));

        assertEquals(2d, rows.get("INCIDENT|scalar|total"));
        assertEquals(2d, rows.get("INCIDENT|scalar|backlog"), "both are freshly opened ⇒ non-terminal");
        assertEquals(1d, rows.get("INCIDENT|priority|CRITICAL"));
        assertEquals(1d, rows.get("INCIDENT|priority|LOW"));
        assertEquals(2d, rows.get("INCIDENT|category|Pipeline"), "breakdown is by L1 category");
        assertEquals(150.5d, rows.get("INCIDENT|impact|impact_amount"), "value is DOUBLE, not a count");
        assertEquals(20d, rows.get("INCIDENT|impact|records_affected"));
        assertEquals(0d, rows.get("INCIDENT|cycle_time|count"), "nothing closed yet");
        assertEquals(0d, rows.get("INCIDENT|cycle_time|avg_ms"));
        assertEquals(2d, rows.get("INCIDENT|status|IDENTIFIED"), "the INCIDENT workflow's initial state");
    }

    @Test
    void flattenToleratesAnEmptyRollup() {
        Map<String, Double> rows = byKey(ObjectsAnalyticsJob.flatten(ObjectType.TASK, new LinkedHashMap<>()));

        assertEquals(0d, rows.get("TASK|scalar|total"), "absent scalars fold to 0, never to a null row");
        assertEquals(0d, rows.get("TASK|scalar|backlog"));
        assertEquals(2, rows.size(), "no breakdown axes when the rollup carries no maps");
    }

    // ── 2. end-to-end run ─────────────────────────────────────────────────────────────

    @Test
    void writesASampleAndStampsTheDataset(@TempDir Path tmp) throws Exception {
        Path data = tmp.resolve("data");
        Path write = tmp.resolve("write");
        Files.createDirectories(data);
        Files.createDirectories(write);
        String prior = System.getProperty("assist.write.root");
        System.setProperty("assist.write.root", write.toString());
        try {
            CapturingContext ctx = new CapturingContext();
            JobResult result = new ObjectsAnalyticsJob(cfg(Map.of()), data.toString(), ObjectsAnalyticsJobTest::seeded)
                    .run(ctx);

            assertEquals("SUCCESS", result.status(), result.message());
            List<Path> samples = samples(data);
            assertEquals(1, samples.size(), "one Parquet per run");

            List<Map<String, Object>> read = readBack(data);
            assertFalse(read.isEmpty());
            assertEquals(1, read.stream().map(r -> r.get("sampled_at")).distinct().count(),
                    "one run ⇒ one sampled_at across every row");
            assertEquals(4, read.stream().map(r -> r.get("object_type")).distinct().count(),
                    "all four object types sampled by default");
            Map<String, Double> rows = new HashMap<>();
            for (Map<String, Object> r : read)
                rows.put(r.get("object_type") + "|" + r.get("axis") + "|" + r.get("key"),
                        ((Number) r.get("value")).doubleValue());
            assertEquals(2d, rows.get("INCIDENT|scalar|total"));
            assertEquals(1d, rows.get("CASE|scalar|total"));
            assertEquals(0d, rows.get("ALERT|scalar|total"), "an empty type still samples its scalars");

            // the dataset component is stamped so Studio/BI pickers see it
            Path dataset = write.resolve("registry").resolve("datasets").resolve("ops_analytics.toon");
            assertTrue(Files.exists(dataset), "dataset component written at " + dataset);
            assertTrue(Files.readString(dataset).contains("ops_analytics"));

            assertEquals("objects.analytics.completed", ctx.type.get());
            assertEquals(Severity.INFO, ctx.severity.get());
            assertEquals(read.size(), ctx.payload.get().get("rows"));
            assertEquals("ops_analytics", ctx.payload.get().get("dataset"));
            assertEquals("ops_analytics", ctx.datasetArtifact.get());
        } finally {
            restore(prior);
        }
    }

    @Test
    void rerunAppendsASecondSampleAndRetentionZeroKeepsBoth(@TempDir Path tmp) throws Exception {
        Path data = tmp.resolve("data");
        Path write = tmp.resolve("write");
        Files.createDirectories(data);
        Files.createDirectories(write);
        String prior = System.getProperty("assist.write.root");
        System.setProperty("assist.write.root", write.toString());
        try {
            ObjectsAnalyticsJob job = new ObjectsAnalyticsJob(cfg(Map.of("retention_days", "0")),
                    data.toString(), ObjectsAnalyticsJobTest::seeded);
            job.run(new CapturingContext());
            Thread.sleep(2);   // the filename key is epoch millis
            job.run(new CapturingContext());

            assertEquals(2, samples(data).size(), "append per run, retention_days=0 keeps forever");
            assertEquals(2, readBack(data).stream().map(r -> r.get("sampled_at")).distinct().count(),
                    "the glob unions both samples ⇒ two points on the trend axis");
        } finally {
            restore(prior);
        }
    }

    @Test
    void retentionForgetsSamplesOlderThanTheWindow(@TempDir Path tmp) throws Exception {
        Path data = tmp.resolve("data");
        Path write = tmp.resolve("write");
        Path storeDir = data.resolve("ops_analytics");
        Files.createDirectories(storeDir);
        Files.createDirectories(write);
        long old = Instant.now().minusSeconds(10 * 86_400L).toEpochMilli();
        Path stale = storeDir.resolve("analytics_" + old + "_out.parquet");
        Path foreign = storeDir.resolve("analytics_notanumber_out.parquet");
        Files.writeString(stale, "x");     // content is irrelevant — only the filename key is read
        Files.writeString(foreign, "x");
        String prior = System.getProperty("assist.write.root");
        System.setProperty("assist.write.root", write.toString());
        try {
            JobResult result = new ObjectsAnalyticsJob(cfg(Map.of("retention_days", "7")),
                    data.toString(), ObjectsAnalyticsJobTest::seeded).run(new CapturingContext());

            assertFalse(Files.exists(stale), "10-day-old sample is outside a 7-day window");
            assertTrue(Files.exists(foreign), "a file that isn't one of ours is left alone");
            assertTrue(result.message().contains("purged 1"), result.message());
        } finally {
            restore(prior);
        }
    }

    @Test
    void typesFilterSamplesOnlyTheListedTypes(@TempDir Path tmp) throws Exception {
        Path data = tmp.resolve("data");
        Path write = tmp.resolve("write");
        Files.createDirectories(data);
        Files.createDirectories(write);
        String prior = System.getProperty("assist.write.root");
        System.setProperty("assist.write.root", write.toString());
        try {
            new ObjectsAnalyticsJob(cfg(Map.of("types", "INCIDENT, case")), data.toString(),
                    ObjectsAnalyticsJobTest::seeded).run(new CapturingContext());

            List<Object> sampled = readBack(data).stream().map(r -> r.get("object_type")).distinct().toList();
            assertEquals(2, sampled.size(), "case-insensitive CSV ⇒ " + sampled);
            assertTrue(sampled.contains("INCIDENT") && sampled.contains("CASE"), sampled.toString());
        } finally {
            restore(prior);
        }
    }

    @Test
    void dryRunWritesNothing(@TempDir Path tmp) throws Exception {
        Path data = tmp.resolve("data");
        Path write = tmp.resolve("write");
        Files.createDirectories(data);
        Files.createDirectories(write);
        String prior = System.getProperty("assist.write.root");
        System.setProperty("assist.write.root", write.toString());
        try {
            JobResult result = new ObjectsAnalyticsJob(cfg(Map.of()), data.toString(),
                    ObjectsAnalyticsJobTest::seeded).run(new DryRunContext());

            assertEquals("SUCCESS", result.status());
            assertTrue(result.message().contains("dry run"), result.message());
            assertFalse(Files.exists(data.resolve("ops_analytics")), "no sample directory created");
            assertFalse(Files.exists(write.resolve("registry")), "no dataset stamped");
        } finally {
            restore(prior);
        }
    }

    // ── 3. fail-closed + registration ─────────────────────────────────────────────────

    @Test
    void missingObjectEngineFailsClosed(@TempDir Path tmp) {
        ObjectsAnalyticsJob job = new ObjectsAnalyticsJob(cfg(Map.of()), tmp.toString(), () -> null);
        assertThrows(IllegalStateException.class, () -> job.run(new CapturingContext()));
    }

    @Test
    void missingWriteRootFailsClosed(@TempDir Path tmp) {
        String prior = System.getProperty("assist.write.root");
        System.clearProperty("assist.write.root");
        try {
            ObjectsAnalyticsJob job = new ObjectsAnalyticsJob(cfg(Map.of()), tmp.toString(),
                    ObjectsAnalyticsJobTest::seeded);
            assertThrows(IllegalStateException.class, () -> job.run(new CapturingContext()));
        } finally {
            restore(prior);
        }
    }

    @Test
    void unknownTypeAndNegativeRetentionFailClosed(@TempDir Path tmp) {
        String prior = System.getProperty("assist.write.root");
        System.setProperty("assist.write.root", tmp.toString());
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> new ObjectsAnalyticsJob(cfg(Map.of("types", "GHOST")), tmp.toString(),
                            ObjectsAnalyticsJobTest::seeded).run(new CapturingContext()),
                    "an unknown type never silently samples a subset");
            assertThrows(IllegalArgumentException.class,
                    () -> new ObjectsAnalyticsJob(cfg(Map.of("retention_days", "-1")), tmp.toString(),
                            ObjectsAnalyticsJobTest::seeded).run(new CapturingContext()));
        } finally {
            restore(prior);
        }
    }

    @Test
    void objectsAnalyticsIsRegisteredAsABuiltInType() throws Exception {
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(), new BatchEventBus(), s, null,
                     "audit", null, null, "data")) {
            assertTrue(js.jobType("objects.analytics").isPresent(), "registered as a built-in");
            JobTypeDescriptor d = js.jobType("objects.analytics").get();
            assertEquals("Object Analytics Sample", d.title());
            assertTrue(d.emits().contains("objects.analytics.completed"));
            assertTrue(d.parameters().stream().anyMatch(p -> p.name().equals("retention_days")));
            assertTrue(d.parameters().stream().anyMatch(p -> p.name().equals("types")));
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────

    private static void restore(String prior) {
        if (prior == null) System.clearProperty("assist.write.root");
        else System.setProperty("assist.write.root", prior);
    }

    private static List<Path> samples(Path dataDir) throws Exception {
        Path dir = dataDir.resolve("ops_analytics");
        if (!Files.isDirectory(dir)) return List.of();
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".parquet")).sorted().toList();
        }
    }

    /**
     * Read the samples back the way Studio/BI will: through {@link DatasetRelation#relationSql} over the
     * stamped dataset config, not through a hand-written glob. This is the seam that makes the feature real.
     */
    private static List<Map<String, Object>> readBack(Path dataDir) throws Exception {
        Map<String, Object> dataset = Map.of("name", "ops_analytics", "physicalRef", "ops_analytics");
        String sql = DatasetRelation.relationSql(dataset, dataDir, null);
        com.gamma.util.DuckDbUtil.loadDriver();
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++)
                    row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                out.add(row);
            }
        }
        return out;
    }

    /** A {@link JobContext} capturing the Signal + the dataset artifact the Job records. */
    private static class CapturingContext implements JobContext {
        final AtomicReference<String> type = new AtomicReference<>();
        final AtomicReference<Severity> severity = new AtomicReference<>();
        final AtomicReference<Map<String, Object>> payload = new AtomicReference<>();
        final AtomicReference<String> datasetArtifact = new AtomicReference<>();

        @Override public String runId() { return "test-run"; }
        @Override public String spaceId() { return "default"; }
        @Override public TriggerInfo trigger() { return null; }
        @Override public Map<String, String> config() { return Map.of(); }
        @Override public Map<String, String> params() { return Map.of(); }
        @Override public RunLog log() {
            return new RunLog() {
                @Override public void info(String message, Object... kv) {}
                @Override public void warn(String message, Object... kv) {}
                @Override public void error(String message, Throwable t, Object... kv) {}
            };
        }
        @Override public SignalEmitter signals() {
            return (t, sev, p) -> { type.set(t); severity.set(sev); payload.set(p); };
        }
        @Override public ArtifactRecorder artifacts() {
            return new ArtifactRecorder() {
                @Override public void dataset(String name, String datasetRef, ResultSetMeta resultSet,
                                              long rows, Instant watermark) {
                    datasetArtifact.set(datasetRef);
                }
                @Override public void file(String name, Path path, long bytes) {}
            };
        }
    }

    private static final class DryRunContext extends CapturingContext {
        @Override public boolean dryRun() { return true; }
    }
}
