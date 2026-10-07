package com.gamma.inspector;

import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.consignment.DbConsignmentOutputStore;
import com.gamma.etl.Consignment;
import com.gamma.etl.ConsignmentAuditWriter;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.SchemaSelector;
import com.gamma.audit.EventLog;
import com.gamma.signal.Signals;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LEASE-TAKEOVER-INFLIGHT-1 at the engine's commit points: a Consignment whose run lease was taken over
 * mid-run must not commit, must not spend an X1 attempt, and its file stays in the inbox for the new holder.
 *
 * <p>The runs here go through the REAL batch workers ({@code CollectorProcessor.run}), so a fence keyed by the
 * Space MDC only refuses if the workers inherited that MDC.
 */
class CommitFenceTest {

    private String space;
    private EventLog log;

    @BeforeEach
    void isolateSpace() {
        space = "commit-fence-" + UUID.randomUUID();
        log = EventLog.create();
        EventLog.register(space, log);
        org.slf4j.MDC.put(EventLog.SPACE_MDC_KEY, space);
    }

    @AfterEach
    void restore() {
        org.slf4j.MDC.remove(EventLog.SPACE_MDC_KEY);
        ConsignmentOutputStores.unregister(space);
        EventLog.unregister(space);
        for (String k : List.of("ingest.retry.max", "ingest.retry.backoff.initialMs", "ingest.retry.backoff.maxMs"))
            System.clearProperty(k);
    }

    private static PipelineConfig pipelineWithFeed(Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTestRef.writePipeline(dir, "").toString());
        Path inbox = Files.createDirectories(Path.of(cfg.dirs().poll()));
        Files.writeString(inbox.resolve("feed.csv"), "ID,AMT,EVENT_DATE\nr1,1.0,2020-04-03\n");
        return cfg;
    }

    private static long filesUnder(String dir) throws Exception {
        return relFiles(dir).size();
    }

    private static Set<String> relFiles(String dir) throws Exception {
        Path root = Path.of(dir);
        if (!Files.exists(root)) return Set.of();
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile).map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .collect(Collectors.toSet());
        }
    }

    /**
     * D1 + D2 + T2. Three refused runs at {@code ingest.retry.max=2}: a good file is never quarantined and no
     * X1 attempt is spent, nothing durable is committed, and the outputs the lost run had already written are
     * the same-named files the new holder's run overwrites — so nothing doubles.
     */
    @Test
    void aLostLeaseSpendsNoAttemptCommitsNothingAndLeavesOnlyOverwritableOutputs(@TempDir Path dir) throws Exception {
        System.setProperty("ingest.retry.max", "2");
        System.setProperty("ingest.retry.backoff.initialMs", "0");
        PipelineConfig cfg = pipelineWithFeed(dir);
        File feed = Path.of(cfg.dirs().poll()).resolve("feed.csv").toFile();

        try (CommitFence.Held fence = CommitFence.hold(
                CommitFence.Scope.RUN, cfg.identity().pipelineName(), () -> false)) {
            for (int i = 0; i < 3; i++) CollectorProcessor.run(cfg);          // 3 > retry.max: would exhaust
        }

        assertTrue(feed.exists(), "the file stays in the inbox for the new holder");
        assertNull(CommitRetry.recordFor(feed, cfg), "a lease loss spends no X1 attempt");
        assertEquals(0, filesUnder(cfg.dirs().quarantine()), "a good file is never quarantined for it");
        assertTrue(Signals.query(log.store(), CommitRetry.SIGNAL_TYPE, null, null, null, null, 10).isEmpty(),
                "no retry-exhausted Signal");
        assertTrue(Files.readString(Path.of(cfg.dirs().batchesFilePath())).contains("lease lost"),
                "audited FAILED with a distinct 'lease lost' reason, not 'commit failed'");

        // D2: nothing durable but the outputs.
        assertEquals(0, filesUnder(cfg.dirs().manifestsDir()), "no manifest");
        assertEquals(0, filesUnder(cfg.dirs().markers()), "no marker");
        assertEquals(0, filesUnder(cfg.dirs().backup()), "no backup");
        Set<String> orphans = relFiles(cfg.dirs().database());
        assertFalse(orphans.isEmpty(),
                "documented posture: the lost run's outputs were already written (a glob reader can see them "
                        + "until the new holder commits) — exactly what a crash mid-commit leaves");

        // The new holder's run (no fence) commits the same file and OVERWRITES those same-named outputs.
        CollectorProcessor.run(cfg);
        assertFalse(feed.exists(), "committed: the original moved to backup");
        assertEquals(orphans, relFiles(cfg.dirs().database()),
                "same names, overwritten in place — the orphans do not double the data");
    }

    /** D2: the output registry (§11.3) gets no entry from a refused commit, and gets it from the new holder's. */
    @Test
    void aLostLeaseRegistersNoOutputs(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTestRef.writePipeline(dir, "").toString());
        Path inbox = Files.createDirectories(Path.of(cfg.dirs().poll()));
        Path solo = Files.writeString(inbox.resolve("solo.csv"), "ID,AMT,EVENT_DATE\nx,9.0,2020-04-03\n");
        List<Consignment.Member> survivors = List.of(new Consignment.Member(solo.toFile(), 0, Files.size(solo),
                new SchemaSelector.Selection(cfg.schemas().single(), null)));
        Consignment batch = new Consignment(cfg.identity().runTimestamp() + "_mini_0001", "mini", null, survivors);

        try (DbConsignmentOutputStore store = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            ConsignmentOutputStores.register(space, store);
            File db = com.gamma.util.DuckDbUtil.tempDbFile("cf_ingest_");
            try (Connection conn = twoPartitions(db)) {
                ConsignmentIngestStrategy.Written written = ConsignmentIngestStrategy.writeAndTrace(
                        conn, "transformed", List.of("year", "month", "day"), cfg,
                        cfg.dirs().database(), "b1", batch.batchId(), java.util.Map.of(1, "a.csv", 2, "b.csv"), "");
                assertEquals(2, written.outputs().size(), "harness precondition: two partitions written");

                try (CommitFence.Held fence = CommitFence.hold(
                        CommitFence.Scope.RUN, cfg.identity().pipelineName(), () -> false)) {
                    assertThrows(CommitFence.LeaseLostException.class, () -> ConsignmentIngestor.finalizeSource(
                            batch, cfg, survivors, written.outputs(), written.lineage()));
                }
                assertTrue(store.outputs(batch.batchId()).isEmpty(), "a refused commit registers nothing");
                assertTrue(Files.exists(solo), "and leaves the file in the inbox");

                ConsignmentIngestor.finalizeSource(batch, cfg, survivors, written.outputs(), written.lineage());
                assertEquals(2, store.outputs(batch.batchId()).size(), "control: the holder's commit registers them");
            } finally {
                com.gamma.util.DuckDbUtil.deleteTempDb(db);
            }
        }
    }

    private static Connection twoPartitions(File db) throws Exception {
        com.gamma.util.DuckDbUtil.loadDriver();
        Connection conn = com.gamma.util.DuckDbUtil.openConnection(db);
        try (Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE transformed AS SELECT * FROM (VALUES
                      ('alice', 250.0, '2026', '07', '01', 1),
                      ('carol', 999.0, '2026', '07', '02', 1)
                    ) v(name, cost, year, month, day, __src_id)""");
        }
        return conn;
    }

    /**
     * The second look, just before the backup move: a lease lost DURING the commit still leaves the file in
     * the inbox — no backup, no marker — even though the manifest was already written (crash-mid-commit posture).
     */
    @Test
    void aLeaseLostDuringTheCommitStopsBeforeTheOriginalIsMovedAway(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = pipelineWithFeed(dir);
        File feed = Path.of(cfg.dirs().poll()).resolve("feed.csv").toFile();
        AtomicInteger asked = new AtomicInteger();
        try (CommitFence.Held fence = CommitFence.hold(CommitFence.Scope.RUN, cfg.identity().pipelineName(),
                () -> asked.incrementAndGet() <= 1)) {                       // held at the first look, lost by the second
            CollectorProcessor.run(cfg);
        }
        assertTrue(asked.get() >= 2, "the commit looked again before the backup move");
        assertTrue(feed.exists(), "the original was not moved out from under the new holder");
        assertEquals(0, filesUnder(cfg.dirs().backup()), "no backup");
        assertEquals(0, filesUnder(cfg.dirs().markers()), "no marker");
        assertNull(CommitRetry.recordFor(feed, cfg), "still no X1 attempt spent");
        assertTrue(filesUnder(cfg.dirs().manifestsDir()) > 0,
                "the manifest written between the two looks stays (documented): its id is the new holder's too");
    }

    private static Consignment batchOf(PipelineConfig cfg, String id, File... files) {
        SchemaSelector.Selection sel = new SchemaSelector.Selection(cfg.schemas().single(), null);
        List<Consignment.Member> members = new java.util.ArrayList<>();
        for (int i = 0; i < files.length; i++) members.add(new Consignment.Member(files[i], i, files[i].length(), sel));
        return new Consignment(id, "mini", null, members);
    }

    private static ConsignmentAuditWriter auditOf(PipelineConfig cfg) {
        return new ConsignmentAuditWriter(cfg.dirs().statusFilePath(), cfg.dirs().batchesFilePath(),
                cfg.dirs().lineageFilePath());
    }

    /**
     * ⛔ The hazard that ruled out cleaning up. A batch id is a deterministic hash of its members
     * ({@code ConsignmentId}), so the new holder re-plans the same inbox and mints the SAME id — the same output
     * names, manifest name and registry key. Here the new holder commits that batch BEFORE the lost run's refusal
     * runs; the refusal must leave everything the holder committed exactly as it is.
     */
    @Test
    void aRefusalNeverTouchesTheNewHoldersCommittedBatchOfTheSameId(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = pipelineWithFeed(dir);
        Path inbox = Path.of(cfg.dirs().poll());
        Files.writeString(inbox.resolve("second.csv"), "ID,AMT,EVENT_DATE\nr2,2.0,2020-04-04\n");
        File a = inbox.resolve("feed.csv").toFile();
        File b = inbox.resolve("second.csv").toFile();
        String sameId = cfg.identity().runTimestamp() + "_mini_0001";
        AtomicInteger asked = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<Set<String>> held = new java.util.concurrent.atomic.AtomicReference<>();

        try (CommitFence.Held fence = CommitFence.hold(CommitFence.Scope.RUN, cfg.identity().pipelineName(), () -> {
            if (asked.getAndIncrement() > 0) return CommitFence.State.HELD;      // the new holder's own checks
            try {
                ConsignmentIngestor.process(batchOf(cfg, sameId, a, b), cfg, auditOf(cfg));   // it commits first
                held.set(relFiles(cfg.dirs().database()));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            return CommitFence.State.LOST;                                       // ... and only now A is refused
        })) {
            ConsignmentIngestor.process(batchOf(cfg, sameId, a, b), cfg, auditOf(cfg));
        }
        assertNotNull(held.get());
        assertFalse(held.get().isEmpty(), "harness: the new holder wrote its outputs");
        assertTrue(held.get().stream().allMatch(f -> f.contains(sameId)), "harness: named by the shared batch id");
        assertEquals(held.get(), relFiles(cfg.dirs().database()), "the holder's outputs survive the refusal");
        assertTrue(Files.exists(Path.of(cfg.dirs().manifestsDir(), sameId + ".json")),
                "and so does the holder's manifest");
        assertFalse(a.exists() || b.exists(), "the holder's backup moves stand");
        assertEquals(Set.of("feed.csv", "second.csv"), relFiles(cfg.dirs().backup()));
    }

    /** The same hazard at the §11.3 registry: the holder's LIVE rows stay LIVE through the lost run's refusal. */
    @Test
    void aRefusalLeavesTheNewHoldersLiveRegistryRowsLive(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTestRef.writePipeline(dir, "").toString());
        Path inbox = Files.createDirectories(Path.of(cfg.dirs().poll()));
        Path solo = Files.writeString(inbox.resolve("solo.csv"), "ID,AMT,EVENT_DATE\nx,9.0,2020-04-03\n");
        List<Consignment.Member> survivors = List.of(new Consignment.Member(solo.toFile(), 0, Files.size(solo),
                new SchemaSelector.Selection(cfg.schemas().single(), null)));
        Consignment batch = new Consignment(cfg.identity().runTimestamp() + "_mini_0001", "mini", null, survivors);

        try (DbConsignmentOutputStore store = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            ConsignmentOutputStores.register(space, store);
            File db = com.gamma.util.DuckDbUtil.tempDbFile("cf_same_");
            try (Connection conn = twoPartitions(db)) {
                ConsignmentIngestStrategy.Written written = ConsignmentIngestStrategy.writeAndTrace(
                        conn, "transformed", List.of("year", "month", "day"), cfg,
                        cfg.dirs().database(), "b1", batch.batchId(), java.util.Map.of(1, "a.csv", 2, "b.csv"), "");
                ConsignmentIngestor.finalizeSource(batch, cfg, survivors, written.outputs(), written.lineage());   // the holder
                assertEquals(2, store.outputs(batch.batchId()).size(), "harness: the holder registered its outputs");

                try (CommitFence.Held fence = CommitFence.hold(
                        CommitFence.Scope.RUN, cfg.identity().pipelineName(), () -> false)) {
                    assertThrows(CommitFence.LeaseLostException.class, () -> ConsignmentIngestor.finalizeSource(
                            batch, cfg, survivors, written.outputs(), written.lineage()));      // the lost run, SAME id
                }
                assertEquals(2, store.outputs(batch.batchId()).size());
                assertTrue(store.outputs(batch.batchId()).stream()
                                .allMatch(o -> o.state() == com.gamma.consignment.ConsignmentOutput.State.LIVE),
                        "the holder's rows are still LIVE");
            } finally {
                com.gamma.util.DuckDbUtil.deleteTempDb(db);
            }
        }
    }

    /**
     * An identical re-plan self-heals: the refused run's outputs carry the same names the new holder's run writes
     * for the same members, so its write replaces them in place and nothing is left over.
     */
    @Test
    void anIdenticalReBatchOverwritesTheRefusedRunsOutputsInPlace(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = pipelineWithFeed(dir);
        Path inbox = Path.of(cfg.dirs().poll());
        Files.writeString(inbox.resolve("second.csv"), "ID,AMT,EVENT_DATE\nr2,2.0,2020-04-04\n");
        File a = inbox.resolve("feed.csv").toFile();
        File b = inbox.resolve("second.csv").toFile();
        String sameId = cfg.identity().runTimestamp() + "_mini_0001";

        try (CommitFence.Held fence = CommitFence.hold(
                CommitFence.Scope.RUN, cfg.identity().pipelineName(), () -> false)) {
            ConsignmentIngestor.process(batchOf(cfg, sameId, a, b), cfg, auditOf(cfg));
        }
        Set<String> refused = relFiles(cfg.dirs().database());
        assertFalse(refused.isEmpty(), "the refused run's outputs are on disk (the documented posture)");
        assertTrue(a.exists() && b.exists(), "both files stay in the inbox");

        ConsignmentIngestor.process(batchOf(cfg, sameId, a, b), cfg, auditOf(cfg));     // the new holder, same members
        assertEquals(refused, relFiles(cfg.dirs().database()), "same names, replaced in place — nothing left over");
        assertFalse(a.exists() || b.exists(), "and it committed");
    }

    /** An unreadable lease is refused with its OWN cause, spends no attempt, and never quarantines a good file. */
    @Test
    void anUnreadableLeaseIsRefusedWithItsOwnCauseAndNeverQuarantines(@TempDir Path dir) throws Exception {
        System.setProperty("ingest.retry.max", "2");
        System.setProperty("ingest.retry.backoff.initialMs", "0");
        PipelineConfig cfg = pipelineWithFeed(dir);
        File feed = Path.of(cfg.dirs().poll()).resolve("feed.csv").toFile();
        try (CommitFence.Held fence = CommitFence.hold(CommitFence.Scope.RUN, cfg.identity().pipelineName(),
                () -> CommitFence.State.UNREADABLE)) {
            for (int i = 0; i < 4; i++) CollectorProcessor.run(cfg);
        }
        assertTrue(feed.exists(), "never quarantined for a database blip");
        assertNull(CommitRetry.recordFor(feed, cfg));
        assertEquals(0, filesUnder(cfg.dirs().quarantine()));
        String audit = Files.readString(Path.of(cfg.dirs().batchesFilePath()));
        assertTrue(audit.contains("lease unverifiable") && audit.contains("could not be read"), audit);
        assertFalse(audit.contains("taken over by another node"), "a blip must not read as a takeover");
    }

    /** The catch around {@code process} spends an attempt for a thrown fault, but never for a lost lease. */
    @Test
    void aLeaseLossThatEscapesProcessSpendsNoAttempt(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = pipelineWithFeed(dir);
        File feed = Path.of(cfg.dirs().poll()).resolve("feed.csv").toFile();
        Consignment batch = batchOf(cfg, cfg.identity().runTimestamp() + "_mini_0001", feed);

        CollectorProcessor.onBatchThrown(batch, cfg, false, new CommitFence.LeaseLostException("lease lost: x"));
        assertNull(CommitRetry.recordFor(feed, cfg), "no attempt for a lost lease");

        CollectorProcessor.onBatchThrown(batch, cfg, false, new IllegalStateException("boom"));
        assertNotNull(CommitRetry.recordFor(feed, cfg), "control: an ordinary thrown fault does spend one");
    }

    /** Parking moves the original out of the inbox too, so it is refused for a lost lease as well. */
    @Test
    void aLostLeaseRefusesParkingToo(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = pipelineWithFeed(dir);
        Path feed = Path.of(cfg.dirs().poll()).resolve("feed.csv");
        SchemaSelector.Selection sel = new SchemaSelector.Selection(cfg.schemas().single(), null);
        ConsignmentAuditWriter audit = new ConsignmentAuditWriter(
                cfg.dirs().statusFilePath(), cfg.dirs().batchesFilePath(), cfg.dirs().lineageFilePath());

        String lostId = cfg.identity().runTimestamp() + "_mini_0001";
        ParkedBranches.record(lostId, "sink_parked", dir.resolve("parked.parquet"));
        try (CommitFence.Held fence = CommitFence.hold(
                CommitFence.Scope.RUN, cfg.identity().pipelineName(), () -> false)) {
            ConsignmentIngestor.process(new Consignment(lostId, "mini", null,
                    List.of(new Consignment.Member(feed.toFile(), 0, Files.size(feed), sel))), cfg, audit);
        }
        assertTrue(Files.exists(feed), "not parked: the file stays in the inbox");
        assertEquals(0, filesUnder(Path.of(cfg.dirs().backup(), "parked").toString()), "nothing in the park home");

        // Control: the same batch shape with no fence parks the file.
        String heldId = cfg.identity().runTimestamp() + "_mini_0002";
        ParkedBranches.record(heldId, "sink_parked", dir.resolve("parked.parquet"));
        ConsignmentIngestor.process(new Consignment(heldId, "mini", null,
                List.of(new Consignment.Member(feed.toFile(), 0, Files.size(feed), sel))), cfg, audit);
        assertFalse(Files.exists(feed), "control: without a lost lease the file is parked");
        assertTrue(Files.exists(Path.of(cfg.dirs().backup(), "parked", "feed.csv")), "control: it is in the park home");
    }

    /** A registration is per scope: losing an ACQUIRE lease must not refuse a run's commit, or vice versa. */
    @Test
    void theRunAndAcquireLeasesAreCheckedIndependently() {
        AtomicBoolean runHeld = new AtomicBoolean(false);
        try (CommitFence.Held r = CommitFence.hold(CommitFence.Scope.RUN, "p", runHeld::get);
             CommitFence.Held a = CommitFence.hold(CommitFence.Scope.ACQUIRE, "p", () -> true)) {
            assertThrows(CommitFence.LeaseLostException.class, () -> CommitFence.check(CommitFence.Scope.RUN, "p"));
            assertDoesNotThrow(() -> CommitFence.check(CommitFence.Scope.ACQUIRE, "p"));
            runHeld.set(true);
            assertDoesNotThrow(() -> CommitFence.check(CommitFence.Scope.RUN, "p"));
        }
        assertDoesNotThrow(() -> CommitFence.check(CommitFence.Scope.RUN, "p"), "no registration: nothing to lose");
    }
}
