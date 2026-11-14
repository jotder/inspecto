package com.gamma.job;

import com.gamma.alert.ScoreOutputDirs;
import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.consignment.ConsignmentSelector;
import com.gamma.consignment.DbConsignmentOutputStore;
import com.gamma.etl.ConsignmentEventBus;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.PipelineEdge;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.PipelineStore;
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
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
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

    // ── SNAPSHOT-SWAP-UNLOCKED-READERS-1: the pin, the other Job readers, the other writers ──────────

    @Test
    void aPinWaitsForASwapUnderWayAndSeesTheNewSnapshotWhole(@TempDir Path dir) throws Exception {
        Path data = dir.resolve("data");
        Path links = data.resolve("links");
        publish(data, 1);
        String old = visible(links).get(0);
        Path tmp = links.resolve("sql-99.parquet.tmp");
        writeParquet(2, tmp);
        CountDownLatch hidden = new CountDownLatch(1), reveal = new CountDownLatch(1);
        Thread swap = new Thread(() -> {   // a swap stopped between its hide and its reveal
            try (SnapshotSwapLock.Held w = SnapshotSwapLock.write(links)) {
                Files.move(links.resolve(old), links.resolve(old + ".stale"), StandardCopyOption.ATOMIC_MOVE);
                hidden.countDown();
                reveal.await();
                Files.move(tmp, links.resolve("sql-99.parquet"), StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        swap.start();
        hidden.await();
        AtomicReference<String> pinned = new AtomicReference<>();
        Thread reader = new Thread(() -> pinned.set(ConsignmentSelector.sourceLiteral(links.toString(), "parquet")));
        reader.start();
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (reader.getState() != Thread.State.TIMED_WAITING && reader.getState() != Thread.State.TERMINATED) {
            if (System.nanoTime() > deadline) fail("the pin neither waited nor finished");
            Thread.sleep(5);
        }
        reveal.countDown();
        reader.join(30_000);
        swap.join(30_000);

        assertTrue(pinned.get().contains("sql-99.parquet"), "the pin waited for the reveal: " + pinned.get());
        assertFalse(pinned.get().contains(old), pinned.get());
    }

    /** A writer thread that publishes {@code gen} to {@code links}, recording a failure. */
    private static Thread publisher(Path data, int gen, AtomicReference<Throwable> failed) {
        return new Thread(() -> {
            try { publish(data, gen); } catch (Throwable t) { failed.set(t); }
        });
    }

    @Test
    void materializeHoldsItsSourceFromThePinUntilCopyHasScannedIt(@TempDir Path writeRoot, @TempDir Path dir) throws Exception {
        Path data = dir.resolve("data");
        Path links = data.resolve("links");
        publish(data, 1);
        new ComponentStore(writeRoot.resolve("registry")).write("dataset", "src", Map.of("physicalRef", "links"));
        JobConfig cfg = new JobConfig("mx", "maintenance", null, null, true, false,
                Map.of("task", "materialize", "dataset", "src", "target", "mx_out"), null, null);
        AtomicReference<Throwable> writerFailed = new AtomicReference<>();
        Thread writer = publisher(data, 2, writerFailed);
        MaterializeTask.afterPin = () -> {
            writer.start();
            awaitQueuedOrDone(links, writer);
        };
        System.setProperty("assist.write.root", writeRoot.toString());
        JobResult r;
        try {
            r = new MaintenanceJob(cfg, data.toString()).run();
        } finally {
            System.clearProperty("assist.write.root");
            MaterializeTask.afterPin = () -> { };
        }
        writer.join(30_000);

        assertTrue(r.success(), r.message());
        assertEquals(List.of(1), gens(data.resolve("mx_out")), "materialize read the snapshot it pinned");
        assertNull(writerFailed.get(), "the publish landed once the read was done: " + writerFailed.get());
        assertEquals(List.of(2), gens(links));
    }

    @Test
    void aPipelineHoldsItsSourceStoreFromThePinUntilTheRunHasScannedIt(@TempDir Path dir) throws Exception {
        Path data = dir.resolve("data");
        Path links = data.resolve("links");
        publish(data, 1);
        PipelineStore pipelines = new PipelineStore(dir.resolve("flows"));
        pipelines.write("copy_links", new PipelineGraph("copy_links", true,
                List.of(PipelineNode.of("src", "acquisition", Map.of("source_store", "links")),
                        PipelineNode.of("flt", "transform.filter", Map.of("where", "gen > 0")),
                        new PipelineNode("out", "sink.persistent", "Copied", null, Map.of("store", "copied"), null)),
                List.of(PipelineEdge.data("src", "flt"), PipelineEdge.data("flt", "out"))));
        JobConfig cfg = new JobConfig("copy", JobType.PIPELINE, null, null, true, false,
                Map.of("flow", "copy_links", "data_dir", data.toString()));
        AtomicReference<Throwable> writerFailed = new AtomicReference<>();
        Thread writer = publisher(data, 2, writerFailed);
        PipelineJobRunner.afterPin = () -> {
            writer.start();
            awaitQueuedOrDone(links, writer);
        };
        JobResult r;
        try {
            r = new PipelineJobRunner(cfg, new ConsignmentEventBus(), pipelines, data.toString(),
                    dir.resolve("audit").toString()).run();
        } finally {
            PipelineJobRunner.afterPin = () -> { };
        }
        writer.join(30_000);

        assertTrue(r.success(), r.message());
        assertEquals(List.of(1), gensUnder(data.resolve("copied")), "the pipeline read the snapshot it pinned");
        assertNull(writerFailed.get(), "the publish landed once the run had read: " + writerFailed.get());
        assertEquals(List.of(2), gens(links));
    }

    private static List<Integer> gensUnder(Path store) throws Exception {
        List<Integer> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT gen FROM read_parquet('" + store.toString().replace('\\', '/')
                     + "/**/*.parquet') ORDER BY gen")) {
            while (rs.next()) out.add(rs.getInt(1));
        }
        return out;
    }

    @Test
    void aSwapRefusesAThreadThatStillHoldsTheStoreItWouldWaitFor(@TempDir Path dir) throws Exception {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread own = new Thread(() -> {
            try (SnapshotSwapLock.Held reading = SnapshotSwapLock.read(List.of(dir));
                 SnapshotSwapLock.Held swap = SnapshotSwapLock.write(dir)) {
                thrown.set(new AssertionError("the swap went ahead under its own reader"));
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        own.setDaemon(true);   // without the refusal it would wait for itself forever
        own.start();
        own.join(30_000);

        assertFalse(own.isAlive(), "the swap waited for its own thread's read");
        assertInstanceOf(IllegalStateException.class, thrown.get());
        assertTrue(thrown.get().getMessage().contains("this thread's own read"), thrown.get().getMessage());
    }

    @Test
    void theReferenceCompactionWaitsForAReader(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("refdb");
        Files.createDirectories(store);
        for (String[] v : List.of(new String[]{"a", "2026-07-24 10:00:00"}, new String[]{"b", "2026-07-24 11:00:00"}))
            try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
                st.execute("COPY (SELECT 'C1' AS customer_id, md5('C1') AS __key_hash, TIMESTAMP '" + v[1]
                        + "' AS __valid_from, 'upsert' AS __op) TO '"
                        + store.resolve("dim__v_" + v[0] + "_out.parquet").toString().replace('\\', '/') + "' (FORMAT PARQUET)");
            }
        assertPublishWaitsForAReader(store, () -> ReferenceCompactor.compact(store, 0));
    }

    @Test
    void thePartitionCompactionWaitsForAReader(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("cdr");
        Files.createDirectories(store);
        for (int i = 1; i <= 2; i++) {
            Path f = store.resolve("c" + i + "_out.parquet");
            writeParquet(i, f);
            Files.setLastModifiedTime(f, FileTime.from(Instant.now().minus(10, ChronoUnit.DAYS)));
        }
        JobConfig cfg = new JobConfig("compact_job", "compact", null, null, true, false,
                Map.of("dir", store.toString(), "min_age_days", "1", "min_files", "2"), null, null);
        assertPublishWaitsForAReader(store, () -> assertTrue(PartitionCompactor.run(cfg).success()));
    }

    @Test
    void theIncrementalRetentionPruneWaitsForAReader(@TempDir Path dir) throws Exception {
        Path sink = dir.resolve("daily_totals");
        for (String day : List.of("2026-10-01", "2026-10-10")) {
            Files.createDirectories(sink.resolve("event_date=" + day));
            writeParquet(1, sink.resolve("event_date=" + day).resolve("part-r.parquet"));
        }
        assertPublishWaitsForAReader(sink, () -> assertEquals(1,
                IncrementalSink.prune(sink, "event_date", java.time.LocalDate.parse("2026-10-05"))));
        assertEquals(List.of("event_date=2026-10-10/part-r.parquet"), visible(sink));
    }

    @Test
    void theIncrementalRollbackWaitsForAReader(@TempDir Path dir) throws Exception {
        Path sink = dir.resolve("daily_totals");
        Path day = sink.resolve("event_date=2026-10-10");
        Files.createDirectories(day);
        // A replace that died between its hide and its reveal: the old file hidden, the new one unrevealed.
        writeParquet(1, day.resolve("part-old.parquet.stale"));
        writeParquet(2, day.resolve("part-new.parquet.tmp"));
        assertPublishWaitsForAReader(sink, () -> IncrementalSink.recoverAll(sink, "event_date"));
        assertEquals(List.of("event_date=2026-10-10/part-old.parquet"), visible(sink), "rolled back to the old partition");
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
