package com.gamma.job;

import com.gamma.etl.ConsignmentEventBus;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.RunLog;
import com.gamma.util.Scheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code space.comparison} Job Type (space-comparison design, decided 2026-09-24): the per-axis
 * comparison itself, fail-soft per Space, and — the part that matters most — that a run reads only the
 * Spaces its grant names, refusing BEFORE it reads any, and that the registered (authored / scheduled)
 * type can never read outside its own Space.
 *
 * <p>⚠ Every comparison here spans TWO OR MORE data roots: a comparison test over one Space proves nothing
 * (design §6).
 */
class SpaceComparisonJobTest {

    private static JobConfig cfg(Map<String, String> params) {
        return new JobConfig("cmp", SpaceComparisonJob.TYPE, null, null, true, false, params, null, null);
    }

    /** One storage_report-shaped sample (a row per axis) — the MaintenanceLibraryTest idiom. */
    private static void sample(Path dataRoot, Instant created, Map<String, Long> axisBytes) throws Exception {
        Path storeDir = dataRoot.resolve("maintenance_storage");
        Files.createDirectories(storeDir);
        Path parquet = storeDir.resolve("storage_" + created.toEpochMilli() + "_out.parquet");
        DuckDbUtil.loadDriver();
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE s (created VARCHAR, created_ms BIGINT, axis VARCHAR, files BIGINT, bytes BIGINT)");
            }
            try (var ps = conn.prepareStatement("INSERT INTO s VALUES (?,?,?,?,?)")) {
                for (Map.Entry<String, Long> e : axisBytes.entrySet()) {
                    ps.setString(1, created.toString());
                    ps.setLong(2, created.toEpochMilli());
                    ps.setString(3, e.getKey());
                    ps.setLong(4, 1);
                    ps.setLong(5, e.getValue());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (Statement st = conn.createStatement()) {
                st.execute("COPY s TO '" + parquet.toAbsolutePath().toString().replace('\\', '/') + "' (FORMAT PARQUET)");
            }
        }
    }

    /** Two samples ten days apart: {@code from} → {@code to} bytes on axis {@code data}. */
    static void grows(Path dataRoot, long from, long to) throws Exception {
        Instant now = Instant.now();
        sample(dataRoot, now.minus(Duration.ofDays(10)), Map.of("data", from, "config", 50L));
        sample(dataRoot, now, Map.of("data", to, "config", 50L));
    }

    /** A signal-capturing context; the run log is discarded. */
    private static final class Ctx implements JobContext {
        final List<Map<String, Object>> payloads = new ArrayList<>();
        final List<String> types = new ArrayList<>();
        @Override public String runId() { return "r"; }
        @Override public String spaceId() { return "default"; }
        @Override public TriggerInfo trigger() { return TriggerInfo.parse("manual"); }
        @Override public Map<String, String> config() { return Map.of(); }
        @Override public Map<String, String> params() { return Map.of(); }
        @Override public com.gamma.signal.SignalEmitter signals() {
            return (type, severity, payload) -> { types.add(type); payloads.add(payload); };
        }
        @Override public ArtifactRecorder artifacts() { throw new UnsupportedOperationException(); }
        @Override public RunLog log() {
            return new RunLog() {
                @Override public void info(String message, Object... kv) {}
                @Override public void warn(String message, Object... kv) {}
                @Override public void error(String message, Throwable t, Object... kv) {}
            };
        }
    }

    @Test
    void comparesTwoSpacesPerAxis(@TempDir Path a, @TempDir Path b) throws Exception {
        grows(a, 100, 300);   // +20 b/day
        grows(b, 100, 150);   // +5 b/day
        Ctx ctx = new Ctx();
        JobResult r = new SpaceComparisonJob(cfg(Map.of("spaces", "a,b")),
                SpaceStorageAccess.granting(Map.of("a", a, "b", b))).run(ctx);

        assertEquals("SUCCESS", r.status(), r.message());
        assertTrue(r.message().contains("2 comparable"), r.message());
        assertTrue(r.message().contains("data: a=300b(+20b/day) b=150b(+5b/day) spread=150b fastest=a"), r.message());
        // the widest spread is reported first; a flat, equal axis has zero spread
        assertTrue(r.message().indexOf("data:") < r.message().indexOf("config:"), r.message());
        assertTrue(r.message().contains("config: a=50b(+0b/day) b=50b(+0b/day) spread=0b"), r.message());
        assertEquals(List.of(SpaceComparisonJob.SIGNAL), ctx.types, "exactly one completion signal");
        assertEquals(List.of("a", "b"), ctx.payloads.get(0).get("comparable"));
    }

    @Test
    void aSpaceWithTooLittleHistoryIsNotComparableButTheOthersStillCompare(
            @TempDir Path a, @TempDir Path b, @TempDir Path fresh, @TempDir Path empty) throws Exception {
        grows(a, 100, 300);
        grows(b, 100, 150);
        sample(fresh, Instant.now(), Map.of("data", 10L));   // one sample: no slope
        JobResult r = new SpaceComparisonJob(cfg(Map.of("spaces", "a,b,fresh,empty", "axes", "data")),
                SpaceStorageAccess.granting(Map.of("a", a, "b", b, "fresh", fresh, "empty", empty))).run(new Ctx());

        assertEquals("SUCCESS", r.status(), r.message());
        assertTrue(r.message().contains("4 space(s), 2 comparable"), r.message());
        assertTrue(r.message().contains("fresh (1 sample(s) in 30d, need >= 2)"), r.message());
        assertTrue(r.message().contains("empty (no storage_report history)"), r.message());
        assertFalse(r.message().contains("config:"), "the axes filter drops every other axis: " + r.message());
    }

    @Test
    void oneComparableSpaceComparesNothing(@TempDir Path a, @TempDir Path b) throws Exception {
        grows(a, 100, 300);
        JobResult r = new SpaceComparisonJob(cfg(Map.of("spaces", "a,b")),
                SpaceStorageAccess.granting(Map.of("a", a, "b", b))).run(new Ctx());
        assertEquals("SUCCESS", r.status(), r.message());
        assertTrue(r.message().contains("need >= 2 comparable Spaces"), r.message());
        assertFalse(r.message().contains("data:"), r.message());
    }

    /**
     * 🔴 The cross-Space negative: a Space the grant does not name fails the WHOLE run, and it fails before
     * any Space is read. The granted Space's store holds a CORRUPT Parquet, so a run that read it first would
     * die with a DuckDB error instead — the SecurityException is only reachable if authorization came first.
     */
    @Test
    void anUngrantedSpaceIsRefusedBeforeAnySpaceIsRead(@TempDir Path own, @TempDir Path other) throws Exception {
        Files.createDirectories(own.resolve("maintenance_storage"));
        Files.writeString(own.resolve("maintenance_storage").resolve("storage_1_out.parquet"), "not parquet");
        grows(other, 100, 300);
        SpaceComparisonJob job = new SpaceComparisonJob(cfg(Map.of("spaces", "own,other")),
                SpaceStorageAccess.ownSpaceOnly("own", own.toString()));

        SecurityException e = assertThrows(SecurityException.class, () -> job.run(new Ctx()));
        assertTrue(e.getMessage().contains("[other]"), e.getMessage());
        assertFalse(e.getMessage().contains("[own"), "the running Space itself is granted: " + e.getMessage());
    }

    @Test
    void malformedSpacesAreRejected(@TempDir Path a) throws Exception {
        SpaceStorageAccess grant = SpaceStorageAccess.granting(Map.of("a", a));
        assertThrows(IllegalArgumentException.class,
                () -> new SpaceComparisonJob(cfg(Map.of("spaces", "a")), grant).run(new Ctx()), "one Space");
        assertThrows(IllegalArgumentException.class,
                () -> new SpaceComparisonJob(cfg(Map.of("spaces", "a,a")), grant).run(new Ctx()), "a duplicate");
        assertThrows(IllegalArgumentException.class,
                () -> new SpaceComparisonJob(cfg(Map.of()), grant).run(new Ctx()), "none");
    }

    /**
     * The REGISTERED type — what an authored {@code *_job.toon} or a schedule runs — carries the own-Space-only
     * grant: naming another Space fails the run as not authorized, even though that Space's data is right
     * there on disk. There is no Subject on this path, so there is nothing to authorize a cross-Space read.
     */
    @Test
    void theRegisteredTypeCannotReadAnotherSpace(@TempDir Path dir) throws Exception {
        Path ownData = dir.resolve("own-data");
        Path otherData = dir.resolve("other-data");
        grows(ownData, 100, 300);
        grows(otherData, 100, 150);
        JobConfig authored = new JobConfig("cmp", SpaceComparisonJob.TYPE, null, null, true, false,
                Map.of("spaces", "default,other"), null, null);
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(authored), new ConsignmentEventBus(), s, null,
                     dir.resolve("audit").toString(), null, null, ownData.toString())) {
            JobTypeDescriptor d = js.jobTypes().stream().filter(t -> SpaceComparisonJob.TYPE.equals(t.id()))
                    .findFirst().orElseThrow(() -> new AssertionError("space.comparison is not registered"));
            ParameterDecl spaces = d.parameters().stream().filter(p -> "spaces".equals(p.name())).findFirst().orElseThrow();
            assertTrue(spaces.required() && spaces.multi(), "spaces is a required CSV list");
            assertEquals(List.of(SpaceComparisonJob.SIGNAL), d.emits());

            String runId = js.triggerRun("cmp", "tester").orElseThrow();
            JobRun run = await(js, runId);
            assertEquals("FAILED", run.status(), run.message());
            assertTrue(run.message().contains("not authorized") && run.message().contains("[other]"), run.message());
        }
    }

    /** The trigger seam with an explicit grant runs the same comparison the unit tests above pin. */
    @Test
    void theTriggerSeamRunsWithTheGrantItIsHanded(@TempDir Path dir) throws Exception {
        Path a = dir.resolve("a"), b = dir.resolve("b");
        grows(a, 100, 300);
        grows(b, 100, 150);
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(), new ConsignmentEventBus(), s, null,
                     dir.resolve("audit").toString(), null, null, a.toString())) {
            String runId = js.triggerSpaceComparisonRun(Map.of("spaces", "a,b"),
                    SpaceStorageAccess.granting(Map.of("a", a, "b", b)), "tester");
            JobRun run = await(js, runId);
            assertEquals("SUCCESS", run.status(), run.message());
            assertTrue(run.message().contains("fastest=a"), run.message());
            assertEquals("manual:tester", run.trigger());
        }
    }

    private static JobRun await(JobService js, String runId) throws Exception {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < deadline) {
            JobRun r = js.runById(runId).orElse(null);
            if (r != null && !"RUNNING".equals(r.status())) return r;
            Thread.sleep(25);
        }
        throw new AssertionError("run " + runId + " did not finish within 20s");
    }
}
