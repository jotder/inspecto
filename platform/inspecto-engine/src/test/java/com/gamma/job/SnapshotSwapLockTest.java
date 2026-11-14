package com.gamma.job;

import com.gamma.alert.ScoreOutputDirs;
import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.consignment.DbConsignmentOutputStore;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.signal.SignalEmitter;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.RunLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JOB-SNAPSHOT-SWAP-READER-RACE-1: a Job reading a snapshot Dataset while the Job that writes it publishes. The
 * reader pins the source's file list when it registers its view (an output registry is on, as in production) and
 * DuckDB opens the file by that path only when the statement runs; the swap renames the old file away, so before
 * {@link SnapshotSwapLock} the reader failed with "No files found that match the pattern …/sql-&lt;n&gt;.parquet".
 * Every snapshot writer's swap now waits for the readers of its store.
 */
class SnapshotSwapLockTest {

    private DbConsignmentOutputStore registry;

    @BeforeEach
    void registryOn() throws Exception {
        DuckDbUtil.loadDriver();
        registry = DbConsignmentOutputStore.open("jdbc:duckdb:");
        ConsignmentOutputStores.use(registry);
    }

    @AfterEach
    void reset() throws Exception {
        SqlTemplateJob.afterPin = () -> { };
        ConsignmentOutputStores.use(null);
        registry.close();
    }

    /** A Run context that keeps nothing: artifacts and Signals are not under test here. */
    private static JobContext ctx(Map<String, String> params) {
        return new JobContext() {
            private final String runId = "run-" + System.nanoTime();
            @Override public String runId() { return runId; }
            @Override public String spaceId() { return "default"; }
            @Override public TriggerInfo trigger() { return null; }
            @Override public Map<String, String> config() { return Map.of(); }
            @Override public Map<String, String> params() { return params; }
            @Override public SignalEmitter signals() { return (type, severity, payload) -> { }; }
            @Override public RunLog log() {
                return new RunLog() {
                    @Override public void info(String message, Object... kv) { }
                    @Override public void warn(String message, Object... kv) { }
                    @Override public void error(String message, Throwable t, Object... kv) { }
                };
            }
            @Override public ArtifactRecorder artifacts() {
                return new ArtifactRecorder() {
                    @Override public void dataset(String name, String ref, ResultSetMeta rs, long rows, Instant at) { }
                    @Override public void file(String name, Path path, long bytes) { }
                };
            }
        };
    }

    private static JobConfig job(String sink, String sql, String sources) {
        Map<String, String> p = new HashMap<>(Map.of("sql", sql, "sink_dataset", sink));
        if (sources != null) p.put("sources", sources);
        return new JobConfig(sink + "_job", "sql.template", null, null, true, false, p, null, null);
    }

    /** The writer: publish a one-row snapshot {@code gen} to {@code links}. */
    private static void publish(Path data, int gen) throws Exception {
        JobResult r = new SqlTemplateJob(job("links", "SELECT " + gen + " AS gen", null), data.toString()).run(ctx(Map.of()));
        assertTrue(r.success(), r.message());
    }

    /** The reader: copy {@code links} into {@code seen}. */
    private static JobResult readLinks(Path data) throws Exception {
        return new SqlTemplateJob(job("seen", "SELECT gen FROM links", "links"), data.toString()).run(ctx(Map.of()));
    }

    private static List<Integer> gens(Path store) throws Exception {
        List<Integer> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT gen FROM read_parquet('" + store.toString().replace('\\', '/')
                     + "/*.parquet') ORDER BY gen")) {
            while (rs.next()) out.add(rs.getInt(1));
        }
        return out;
    }

    private static void writeParquet(int gen, Path file) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT " + gen + " AS gen) TO '" + file.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        }
    }

    /** The visible {@code *.parquet} files under {@code store} (dot-segments skipped, as the readers do). */
    private static List<String> visible(Path store) throws Exception {
        try (Stream<Path> s = Files.walk(store)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".parquet"))
                    .map(p -> store.relativize(p).toString().replace('\\', '/'))
                    .filter(r -> !r.startsWith(".") && !r.contains("/."))
                    .sorted().toList();
        }
    }

    private static List<String> leftovers(Path store) throws Exception {
        List<String> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(store, "*.{tmp,stale}")) {
            ds.forEach(p -> out.add(p.getFileName().toString()));
        }
        return out;
    }

    /** Until {@code writer} has finished, or waits on {@code store}'s lock. */
    private static void awaitQueuedOrDone(Path store, Thread writer) {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (writer.getState() != Thread.State.TERMINATED && !SnapshotSwapLock.hasQueuedThreads(store)) {
            if (System.nanoTime() > deadline) fail("the writer neither finished nor waited for the reader");
            try { Thread.sleep(5); } catch (InterruptedException e) { throw new IllegalStateException(e); }
        }
    }

    // ── the race, as the la-crypto showcase hit it ───────────────────────────────

    @Test
    void aReaderThatPinnedTheOldSnapshotReadsItWhileTheWriterPublishes(@TempDir Path dir) throws Exception {
        Path data = dir.resolve("data");
        Path links = data.resolve("links");
        publish(data, 1);

        AtomicReference<Throwable> writerFailed = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try { publish(data, 2); } catch (Throwable t) { writerFailed.set(t); }
        });
        // The reader has pinned links' one file; the writer publishes in the window before the reader opens it.
        Thread reader = Thread.currentThread();
        SqlTemplateJob.afterPin = () -> {
            if (Thread.currentThread() != reader) return;   // the writer runs the same Job Type
            writer.start();
            awaitQueuedOrDone(links, writer);
        };
        JobResult read = readLinks(data);
        writer.join(30_000);

        assertTrue(read.success(), read.message());
        assertNull(writerFailed.get(), "the writer published: " + writerFailed.get());
        assertEquals(List.of(1), gens(data.resolve("seen")), "the reader read the snapshot it pinned");
        assertEquals(List.of(2), gens(links), "and the writer's snapshot replaced it after the read");
        assertEquals(List.of(), leftovers(links), "the hidden snapshot is gone once no reader holds it");
    }

    @Test
    void aReaderStartedAfterTheSwapSeesOnlyTheNewSnapshot(@TempDir Path dir) throws Exception {
        Path data = dir.resolve("data");
        publish(data, 1);
        publish(data, 2);

        assertTrue(readLinks(data).success());
        assertEquals(List.of(2), gens(data.resolve("seen")), "one snapshot, never doubled rows");
        assertEquals(1, visible(data.resolve("links")).size());
    }

    @Test
    void aHiddenLeftoverIsNeverReadAndTheNextPublishRemovesIt(@TempDir Path dir) throws Exception {
        Path data = dir.resolve("data");
        Path links = data.resolve("links");
        publish(data, 1);
        // What a run that died mid-swap leaves behind: a hidden old snapshot and an unrevealed new one, both real Parquet.
        writeParquet(99, links.resolve("sql-0.parquet.stale"));
        writeParquet(98, links.resolve("sql-0.parquet.tmp"));

        assertTrue(readLinks(data).success());
        assertEquals(List.of(1), gens(data.resolve("seen")), "the readers' *.parquet glob never matches a hidden file");

        publish(data, 2);
        assertEquals(List.of(), leftovers(links), "the next publish removed them");
        assertEquals(List.of(2), gens(links));
    }

    @Test
    void aJobThatReadsTheStoreItWritesDoesNotWaitOnItself(@TempDir Path dir) throws Exception {
        Path data = dir.resolve("data");
        publish(data, 1);
        JobConfig self = job("links", "SELECT gen + 1 AS gen FROM links", "links");

        JobResult r = assertTimeoutPreemptively(Duration.ofSeconds(60),
                () -> new SqlTemplateJob(self, data.toString()).run(ctx(Map.of())),
                "the read lock is released before the Job's own swap");
        assertTrue(r.success(), r.message());
        assertEquals(List.of(2), gens(data.resolve("links")));
    }

    // ── every writer that shares the swap waits for a reader of its store ───────

    private interface Publish { void run() throws Exception; }

    /** Hold a reader's lock on {@code store}, publish beside it, and show the swap waited for the reader. */
    private static void assertPublishWaitsForAReader(Path store, Publish publish) throws Exception {
        List<String> before = visible(store);
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try { publish.run(); } catch (Throwable t) { failed.set(t); }
        });
        try (SnapshotSwapLock.Held reading = SnapshotSwapLock.read(List.of(store))) {
            writer.start();
            awaitQueuedOrDone(store, writer);
            assertTrue(writer.isAlive(), "the writer waits for the reader (failure: " + failed.get() + ")");
            assertEquals(before, visible(store), "the snapshot the reader pinned is still in place");
        }
        writer.join(30_000);
        assertNull(failed.get(), "the writer published once the reader was done: " + failed.get());
        List<String> after = visible(store);
        assertEquals(1, after.size(), String.valueOf(after));
        assertNotEquals(before, after);
    }

    @Test
    void theSqlTemplateSwapWaitsForAReader(@TempDir Path dir) throws Exception {
        Path data = dir.resolve("data");
        publish(data, 1);
        assertPublishWaitsForAReader(data.resolve("links"), () -> publish(data, 2));
    }

    @Test
    void theMaterializeSwapWaitsForAReader(@TempDir Path writeRoot, @TempDir Path data) throws Exception {
        new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("src_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES ('EU', 10.0)) AS t(region, amount)", "2026-07-08T00:00:00Z"));
        new ComponentStore(writeRoot.resolve("registry")).write("dataset", "src", Map.of("view", "src_view"));
        JobConfig cfg = new JobConfig("mx", "maintenance", null, null, true, false,
                Map.of("task", "materialize", "dataset", "src", "target", "mx_out"), null, null);
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            new MaintenanceJob(cfg, data.toString()).run();
            assertPublishWaitsForAReader(data.resolve("mx_out"), () -> new MaintenanceJob(cfg, data.toString()).run());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    @Test
    void theLatestScoresSwapWaitsForAReader(@TempDir Path dir) throws Exception {
        Path latest = dir.resolve("risk_scores_m_latest");
        Files.createDirectories(latest);
        Files.writeString(latest.resolve("scores-1.parquet"), "run 1");
        Path tmp = latest.resolve("scores-2.parquet.tmp");
        Files.writeString(tmp, "run 2");
        assertPublishWaitsForAReader(latest, () -> ScoreOutputDirs.swapIn(latest, tmp, "scores-2.parquet", "scores-*.parquet"));
        assertEquals(List.of("scores-2.parquet"), visible(latest));
    }

    @Test
    void theIncrementalDaySwapWaitsForAReader(@TempDir Path dir) throws Exception {
        Path data = dir.resolve("data");
        Files.createDirectories(data.resolve("events"));
        writeParquet(1, data.resolve("events").resolve("e.parquet"));
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("name", "daily");
        j.put("type", "sql.template");
        j.put("sql", "SELECT DATE '2026-10-10' AS event_date, sum(gen) AS total FROM events");
        j.put("sources", "events");
        j.put("sink_dataset", "daily_totals");
        j.put("incremental", Map.of("by", "day", "column", "event_date", "lookback", "1"));
        JobConfig cfg = JobConfig.fromMap(Map.of("job", j));
        Publish run = () -> {
            JobResult r = new SqlTemplateJob(cfg, data.toString()).run(ctx(Map.of("day", "2026-10-10")));
            assertTrue(r.success(), r.message());
        };
        run.run();
        assertPublishWaitsForAReader(data.resolve("daily_totals"), run);
    }
}
