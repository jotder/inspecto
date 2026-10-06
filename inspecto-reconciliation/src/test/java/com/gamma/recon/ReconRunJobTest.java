package com.gamma.recon;

import com.gamma.job.*;

import com.gamma.pipeline.ComponentStore;
import com.gamma.etl.ConsignmentEventBus;
import com.gamma.util.Scheduler;
import com.gamma.signal.Severity;
import com.gamma.signal.SignalEmitter;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.RunLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code recon.run} Job Type: runs a saved {@code reconciliation} over its Datasets and emits a
 * {@code recon.run.completed} Signal with the Break counts. Mirrors {@code ControlApiReconTest}'s example
 * (orders_a vs orders_b → 1 missing-left, 1 missing-right, 1 value-break) but drives the Job directly with
 * a capturing {@link JobContext}, and confirms the type is registered as a built-in.
 */
class ReconRunJobTest {

    @AfterEach
    void clearWriteRoot() {
        System.clearProperty("assist.write.root");
    }

    @Test
    void runsSavedReconciliationAndEmitsBreakCounts(@TempDir Path dir) throws Exception {
        Path writeRoot = dir.resolve("cfg");
        Path dataDir = dir.resolve("data");
        // the design doc's example: EU/voice matches, EU/data value-break (118 vs 114), MEA only in a, APAC only in b
        seedStore(dataDir, "orders_a", "VALUES ('EU','voice',100.0),('EU','data',118.0),('MEA','voice',10.0)");
        seedStore(dataDir, "orders_b", "VALUES ('EU','voice',100.0),('EU','data',114.0),('APAC','sms',7.0)");
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        store.write("dataset", "a_ds", Map.of("physicalRef", "orders_a"));
        store.write("dataset", "b_ds", Map.of("physicalRef", "orders_b"));
        store.write("reconciliation", "orders_recon", Map.of(
                "datasets", List.of("a_ds", "b_ds"),
                "keyColumns", List.of("region", "product"),
                "compareColumns", List.of(Map.of("column", "amount", "toleranceType", "percent", "tolerance", 0.5))));
        System.setProperty("assist.write.root", writeRoot.toString());

        JobConfig cfg = new JobConfig("nightly_recon", "recon.run", null, null, true, false,
                Map.of("reconciliation", "orders_recon"), null, null);
        CapturingContext ctx = new CapturingContext(Map.of("reconciliation", "orders_recon"));
        JobResult result = new ReconRunJob(cfg, dataDir.toString(), () -> null).run(ctx);

        assertEquals("SUCCESS", result.status(), result.message());
        assertTrue(result.message().contains("3 break(s)"), result.message());

        assertEquals("recon.run.completed", ctx.type.get());
        assertEquals(Severity.WARN, ctx.severity.get(), "breaks present ⇒ WARN");
        Map<String, Object> p = ctx.payload.get();
        assertEquals("orders_recon", p.get("reconciliation"));
        assertEquals(1L, ((Number) p.get("missingLeft")).longValue(), "APAC only in b");
        assertEquals(1L, ((Number) p.get("missingRight")).longValue(), "MEA only in a");
        assertEquals(1L, ((Number) p.get("valueBreak")).longValue(), "EU/data 118 vs 114 outside 0.5%");
        assertEquals(3L, ((Number) p.get("breaks")).longValue());
    }

    @Test
    void breachOpensAnIncidentDedupedPerReconciliation(@TempDir Path dir) throws Exception {
        Path writeRoot = dir.resolve("cfg");
        Path dataDir = dir.resolve("data");
        seedStore(dataDir, "orders_a", "VALUES ('EU','voice',100.0),('EU','data',118.0),('MEA','voice',10.0)");
        seedStore(dataDir, "orders_b", "VALUES ('EU','voice',100.0),('EU','data',114.0),('APAC','sms',7.0)");
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        store.write("dataset", "a_ds", Map.of("physicalRef", "orders_a"));
        store.write("dataset", "b_ds", Map.of("physicalRef", "orders_b"));
        store.write("reconciliation", "orders_recon", Map.of(
                "datasets", List.of("a_ds", "b_ds"),
                "keyColumns", List.of("region", "product"),
                "compareColumns", List.of(Map.of("column", "amount", "toleranceType", "percent", "tolerance", 0.5))));
        System.setProperty("assist.write.root", writeRoot.toString());

        // ⚠ The seam's test double since EDG-01 cell 7 — core cannot construct an ObjectService any more
        // (com.gamma.ops is an optional module). The assertions are unchanged in substance: what this test
        // is about is that ReconRunJob opens ONE Incident per reconciliation and dedupes the second run.
        com.gamma.objects.FakeObjectAccess objects = new com.gamma.objects.FakeObjectAccess();
        JobConfig cfg = new JobConfig("nightly_recon", "recon.run", null, null, true, false,
                Map.of("reconciliation", "orders_recon"), null, null);
        ReconRunJob job = new ReconRunJob(cfg, dataDir.toString(), () -> objects);

        job.run(new CapturingContext(Map.of("reconciliation", "orders_recon")));
        assertEquals(1, incidentCount(objects), "3 breaks ⇒ one Incident opened");

        // a second run while the first Incident is still open must not clone it
        job.run(new CapturingContext(Map.of("reconciliation", "orders_recon")));
        assertEquals(1, incidentCount(objects), "deduped to one open Incident per reconciliation");
    }

    /**
     * R2-03: a scheduled run records into the SAME operational state the Board's record route writes, so it
     * ages and auto-closes Breaks — before this a scheduled run left "Last run: never" and aged nothing.
     */
    @Test
    void aScheduledRunRecordsTheBreakLifecycle(@TempDir Path dir) throws Exception {
        Path writeRoot = dir.resolve("cfg");
        Path dataDir = dir.resolve("data");
        seedStore(dataDir, "orders_a", "VALUES ('EU','voice',100.0),('EU','data',118.0),('MEA','voice',10.0)");
        seedStore(dataDir, "orders_b", "VALUES ('EU','voice',100.0),('EU','data',114.0),('APAC','sms',7.0)");
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        store.write("dataset", "a_ds", Map.of("physicalRef", "orders_a"));
        store.write("dataset", "b_ds", Map.of("physicalRef", "orders_b"));
        store.write("reconciliation", "orders_recon", Map.of(
                "datasets", List.of("a_ds", "b_ds"),
                "keyColumns", List.of("region", "product"),
                "compareColumns", List.of(Map.of("column", "amount", "toleranceType", "percent", "tolerance", 0.5))));
        System.setProperty("assist.write.root", writeRoot.toString());
        JobConfig cfg = new JobConfig("nightly_recon", "recon.run", null, null, true, false,
                Map.of("reconciliation", "orders_recon"), null, null);
        ReconRunJob job = new ReconRunJob(cfg, dataDir.toString(), () -> null);

        JobResult first = job.run(new CapturingContext(Map.of("reconciliation", "orders_recon")));
        assertFalse(first.message().contains("not recorded"), first.message());
        com.gamma.recon.ReconStateStore.State s1 = new com.gamma.recon.ReconStateStore(writeRoot).read("orders_recon");
        assertEquals(1, s1.runs());
        assertNotNull(s1.lastRunAt());
        assertEquals(3, s1.breaks().size());
        assertTrue(s1.breaks().stream().allMatch(b -> "open".equals(b.status()) && s1.lastRunAt().equals(b.firstSeenAt())),
                "every Break is new, stamped with the run's own instant");

        // MEA disappears from A: its Break auto-closes on the next scheduled run; the others keep their sighting
        seedStore(dataDir, "orders_a", "VALUES ('EU','voice',100.0),('EU','data',118.0)");
        job.run(new CapturingContext(Map.of("reconciliation", "orders_recon")));
        com.gamma.recon.ReconStateStore.State s2 = new com.gamma.recon.ReconStateStore(writeRoot).read("orders_recon");
        assertEquals(2, s2.runs());
        assertEquals("auto_closed", s2.breaks().stream().filter(b -> b.key().equals("MEA · voice")).findFirst()
                .orElseThrow().status());
        assertTrue(s2.breaks().stream().filter(b -> !b.key().equals("MEA · voice"))
                .allMatch(b -> s1.lastRunAt().equals(b.firstSeenAt())), "carried Breaks keep their first sighting");
    }

    /** A scheduled run of a 3-way Reconciliation records its A↔C Breaks too — through the same compute. */
    @Test
    void aScheduledThreeWayRunRecordsBothPairs(@TempDir Path dir) throws Exception {
        Path writeRoot = dir.resolve("cfg");
        Path dataDir = dir.resolve("data");
        seedStore(dataDir, "orders_a", "VALUES ('EU','voice',100.0),('MEA','voice',10.0)");
        seedStore(dataDir, "orders_b", "VALUES ('EU','voice',100.0)");
        seedStore(dataDir, "orders_c", "VALUES ('EU','voice',100.0)");
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        for (String s : List.of("a", "b", "c")) store.write("dataset", s + "_ds", Map.of("physicalRef", "orders_" + s));
        store.write("reconciliation", "three_recon", Map.of(
                "datasets", List.of("a_ds", "b_ds", "c_ds"),
                "keyColumns", List.of("region", "product"),
                "compareColumns", List.of(Map.of("column", "amount"))));
        System.setProperty("assist.write.root", writeRoot.toString());
        JobConfig cfg = new JobConfig("nightly_recon", "recon.run", null, null, true, false,
                Map.of("reconciliation", "three_recon"), null, null);
        JobResult r = new ReconRunJob(cfg, dataDir.toString(), () -> null)
                .run(new CapturingContext(Map.of("reconciliation", "three_recon")));
        assertFalse(r.message().contains("not recorded"), r.message());
        com.gamma.recon.ReconStateStore.State s = new com.gamma.recon.ReconStateStore(writeRoot).read("three_recon");
        assertEquals(List.of("AB", "AC"), s.breaks().stream().map(com.gamma.recon.ReconBreaks.Break::pair).toList(),
                "MEA · voice is missing from B AND from C — one Break per pair");
    }

    /** The run message and Signal count EVERY pair's Breaks, not only A-B's (the run summary's byType). */
    @Test
    void aThreeWayRunCountsTheBreaksOfEveryPair(@TempDir Path dir) throws Exception {
        Path writeRoot = dir.resolve("cfg");
        Path dataDir = dir.resolve("data");
        seedStore(dataDir, "orders_a", "VALUES ('EU','voice',100.0),('MEA','voice',10.0),('APAC','sms',5.0)");
        seedStore(dataDir, "orders_b", "VALUES ('EU','voice',100.0),('MEA','voice',10.0),('APAC','sms',5.0)");
        seedStore(dataDir, "orders_c", "VALUES ('EU','voice',100.0)");   // MEA + APAC lost only at C
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        for (String s : List.of("a", "b", "c")) store.write("dataset", s + "_ds", Map.of("physicalRef", "orders_" + s));
        store.write("reconciliation", "three_recon", Map.of(
                "datasets", List.of("a_ds", "b_ds", "c_ds"),
                "keyColumns", List.of("region", "product"),
                "compareColumns", List.of(Map.of("column", "amount"))));
        System.setProperty("assist.write.root", writeRoot.toString());
        JobConfig cfg = new JobConfig("nightly_recon", "recon.run", null, null, true, false,
                Map.of("reconciliation", "three_recon"), null, null);
        CapturingContext ctx = new CapturingContext(Map.of("reconciliation", "three_recon"));
        JobResult r = new ReconRunJob(cfg, dataDir.toString(), () -> null).run(ctx);
        assertTrue(r.message().contains("2 break(s)"), "A-B is clean, A-C has 2: " + r.message());
        assertEquals(2L, ((Number) ctx.payload.get().get("breaks")).longValue());
        assertEquals(2L, ((Number) ctx.payload.get().get("missingRight")).longValue());
        assertEquals(2, new com.gamma.recon.ReconStateStore(writeRoot).read("three_recon").breaks().size());
    }

    private static int incidentCount(com.gamma.objects.FakeObjectAccess objects) {
        return (int) objects.opened.stream()
                .filter(o -> o.kind() == com.gamma.workflow.ObjectType.INCIDENT).count();
    }

    @Test
    void unknownReconciliationFailsClosed(@TempDir Path dir) throws Exception {
        System.setProperty("assist.write.root", dir.resolve("cfg").toString());
        JobConfig cfg = new JobConfig("r", "recon.run", null, null, true, false,
                Map.of("reconciliation", "ghost"), null, null);
        ReconRunJob job = new ReconRunJob(cfg, dir.resolve("data").toString(), () -> null);
        assertThrows(IllegalArgumentException.class, () -> job.run(new CapturingContext(Map.of())));
    }

    /** recon.run arrives through the module's ServiceLoader JobTypeProvider (P7) and declares the "objects" grant. */
    @Test
    void reconRunIsContributedThroughTheServiceLoader() {
        com.gamma.job.JobTypeProvider p = java.util.ServiceLoader.load(com.gamma.job.JobTypeProvider.class).stream()
                .map(java.util.ServiceLoader.Provider::get).filter(x -> "recon.run".equals(x.id())).findFirst().orElse(null);
        assertNotNull(p, "recon.run is listed in META-INF/services/com.gamma.job.JobTypeProvider");
        assertEquals("Reconciliation Run", p.descriptor().title());
        assertEquals(List.of("objects"), p.descriptor().requires());
        assertEquals(java.time.Duration.ofHours(24), p.deadline(), "a long-running built-in default, as before the move");
        assertEquals("recon.run", p.create(new JobConfig("r", "recon.run", null, null, true, false,
                Map.of("reconciliation", "x"), null, null)).type());
    }

    private static void seedStore(Path dataDir, String name, String values) throws Exception {
        Path partition = dataDir.resolve(name).resolve("dt=2026");
        Files.createDirectories(partition);
        String parquet = partition.resolve("data.parquet").toString().replace("\\", "/");
        DuckDbUtil.loadDriver();
        File db = DuckDbUtil.tempDbFile("recon_job_seed_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT * FROM (" + values + ") t(region, product, amount)) TO '"
                    + parquet + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** A {@link JobContext} that captures the one Signal the Job emits. */
    private static final class CapturingContext implements JobContext {
        final AtomicReference<String> type = new AtomicReference<>();
        final AtomicReference<Severity> severity = new AtomicReference<>();
        final AtomicReference<Map<String, Object>> payload = new AtomicReference<>();
        private final Map<String, String> params;

        CapturingContext(Map<String, String> params) { this.params = params; }

        @Override public String runId() { return "test-run"; }
        @Override public String spaceId() { return "default"; }
        @Override public TriggerInfo trigger() { return null; }
        @Override public Map<String, String> config() { return params; }
        @Override public Map<String, String> params() { return params; }
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
        @Override public ArtifactRecorder artifacts() { return null; }
    }
}
