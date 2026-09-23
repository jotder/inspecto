package com.gamma.inspector;

import com.gamma.etl.ConsignmentEvent;
import com.gamma.etl.PipelineConfig;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PIPELINE-DRYRUN-1 step 5 — the flat ingest lane under a whole-pipeline dry run lands <b>nothing</b>.
 *
 * <p>This is the test the plan calls "the only thing that proves the two phases actually agree": phase one
 * is the ingest cycle itself (it must write no outputs, no audit or commit-log rows, no markers, and must
 * leave the inbox untouched — its ONLY write is one provenance record flagged simulated,
 * DRYRUN-INVISIBLE-ON-FLAT-LANE-1 a), phase two is the chaining (the published
 * {@link ConsignmentEvent} must be MARKED so every downstream consumer can honour the flag instead of
 * acting for real).
 *
 * <p>⚠ The control case matters as much as the dry one: the same config run for real must still write
 * everything, or a green dry-run assertion could just mean the fixture never worked.
 */
class FlatLaneDryRunTest {

    /** The audit ledgers a flat-lane cycle appends to; none may exist after a dry run. */
    private static List<Path> auditLedgers(PipelineConfig cfg) {
        return List.of(Path.of(cfg.dirs().statusFilePath()),
                Path.of(cfg.dirs().batchesFilePath()),
                Path.of(cfg.dirs().lineageFilePath()));
    }

    /**
     * Commit-log ROWS, excluding its header. ⚠ The header is written by {@code CommitLog}'s constructor —
     * i.e. by wiring the writer up, before any batch is processed — so "the file does not exist" is the
     * wrong assertion here; "it gained no row" is the one that means nothing was committed.
     */
    private static List<String> commitLogRows(PipelineConfig cfg) throws Exception {
        Path p = Path.of(cfg.dirs().commitLogPath());
        if (!Files.exists(p)) return List.of();
        List<String> lines = Files.readAllLines(p);
        return lines.isEmpty() ? List.of() : lines.subList(1, lines.size());
    }

    private static List<Path> outputsUnder(PipelineConfig cfg) throws Exception {
        Path db = Path.of(cfg.dirs().database());
        if (!Files.exists(db)) return List.of();
        try (Stream<Path> w = Files.walk(db)) {
            return w.filter(p -> p.getFileName().toString().endsWith("_out.csv")).toList();
        }
    }

    private static long filesUnder(String dir) throws Exception {
        Path p = dir == null ? null : Path.of(dir);
        if (p == null || !Files.exists(p)) return 0;
        try (Stream<Path> w = Files.walk(p)) {
            return w.filter(Files::isRegularFile).count();
        }
    }

    @Test
    void aDryRunIngestCycleWritesNothingAndPublishesAMarkedEvent(@TempDir Path dir) throws Exception {
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, "");
        PipelineConfig cfg = PipelineConfig.load(toon.toString());

        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Path a = inbox.resolve("a.csv");
        Files.writeString(a, "ID,AMT,EVENT_DATE\na1,1.0,2020-04-03\na2,2.0,2020-04-03\n");

        List<ConsignmentEvent> published = new ArrayList<>();
        Consumer<ConsignmentEvent> sink = published::add;

        CollectorProcessor.ingest(cfg, sink, true);

        // ── phase one: nothing landed ────────────────────────────────────────────
        assertTrue(Files.exists(a), "the inbox file must still be there — no backup move, no quarantine");
        assertEquals(List.of(), outputsUnder(cfg), "a dry run must write no partition output");
        for (Path artifact : auditLedgers(cfg))
            assertFalse(Files.exists(artifact), "a dry run must not write " + artifact);
        assertEquals(List.of(), commitLogRows(cfg), "a dry run must record no commit");
        assertEquals(0, filesUnder(cfg.dirs().markers()), "a dry run must write no marker");
        assertEquals(0, filesUnder(cfg.dirs().backup()), "a dry run must back nothing up");
        assertEquals(0, filesUnder(cfg.dirs().quarantine()), "a dry run must quarantine nothing");
        assertEquals(0, filesUnder(cfg.dirs().manifestsDir()), "a dry run must write no manifest");

        // ── phase two: the chain is armed, not silent ───────────────────────────
        assertEquals(1, published.size(), "the terminal event must still publish, so the chain can run dry");
        assertTrue(published.getFirst().dryRun(),
                "the published event must be MARKED — an unmarked one makes every consumer act for real");
    }

    @Test
    void theSameCycleRunForRealStillWritesEverything(@TempDir Path dir) throws Exception {
        // The control case: without this, the assertions above could pass on a broken fixture.
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, "");
        PipelineConfig cfg = PipelineConfig.load(toon.toString());

        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("a.csv"), "ID,AMT,EVENT_DATE\na1,1.0,2020-04-03\n");

        List<ConsignmentEvent> published = new ArrayList<>();
        CollectorProcessor.ingest(cfg, published::add, false);

        assertFalse(outputsUnder(cfg).isEmpty(), "a real run writes partition output");
        assertTrue(Files.exists(Path.of(cfg.dirs().batchesFilePath())), "a real run writes the batch audit");
        assertEquals(1, commitLogRows(cfg).size(), "a real run records exactly one commit");
        assertEquals(1, published.size());
        assertFalse(published.getFirst().dryRun(), "a real run's event must NOT claim to be simulated");
    }

    /** Every regular file under {@code root} with its size + mtime — a filesystem diff baseline. */
    private static java.util.Map<Path, String> snapshot(Path root) throws Exception {
        java.util.Map<Path, String> out = new java.util.TreeMap<>();
        try (Stream<Path> w = Files.walk(root)) {
            for (Path p : w.filter(Files::isRegularFile).toList())
                out.put(root.relativize(p), Files.size(p) + "@" + Files.getLastModifiedTime(p).toMillis());
        }
        return out;
    }

    /**
     * DRYRUN-INVISIBLE-ON-FLAT-LANE-1 (a), operator decision 2026-09-23: a flat-lane dry run writes ONE
     * provenance record, flagged simulated — the same {@code parse}/{@code sink} rows a real run records,
     * so the run picker and overlay can show it — and it is the ONLY write the dry run makes.
     */
    @Test
    void aDryRunRecordsOneSimulatedProvenanceBatchAndWritesNothingElse(@TempDir Path dir) throws Exception {
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, "");
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("a.csv"), "ID,AMT,EVENT_DATE\na1,1.0,2020-04-03\na2,2.0,2020-04-03\n");
        String pipeline = cfg.identity().pipelineName();

        // The store is in memory, so the pipeline tree's diff must be EMPTY — the provenance row is the one write.
        try (com.gamma.pipeline.exec.DbProvenanceStore store =
                     com.gamma.pipeline.exec.DbProvenanceStore.open("jdbc:duckdb:")) {
            com.gamma.pipeline.exec.ProvenanceStores.use(store);
            java.util.Map<Path, String> before = snapshot(dir);
            List<ConsignmentEvent> published = new ArrayList<>();
            CollectorProcessor.ingest(cfg, published::add, true);
            java.util.Map<Path, String> after = snapshot(dir);

            assertEquals(before, after, "a dry run must write nothing but its provenance row");

            List<java.util.Map<String, Object>> batches = store.batches(pipeline, 10);
            assertEquals(1, batches.size(), "exactly one provenance batch: " + batches);
            assertEquals(Boolean.TRUE, batches.getFirst().get("simulated"),
                    "the dry-run batch must be flagged simulated: " + batches);
            String batchId = (String) batches.getFirst().get("batchId");
            assertEquals(published.getFirst().batchId(), batchId, "keyed by the published event's batch id");

            List<java.util.Map<String, Object>> rows = store.query(pipeline, batchId);
            assertEquals(List.of("parse", "sink"), rows.stream().map(r -> r.get("nodeId")).toList(), rows.toString());
            for (java.util.Map<String, Object> r : rows) {
                assertEquals(Boolean.TRUE, r.get("simulated"), r.toString());
                // The strategy is skipped whole (nothing parsed, nothing landed), so both counts are zero.
                assertEquals(0L, ((Number) r.get("rowCount")).longValue(), r.toString());
            }
        } finally {
            com.gamma.pipeline.exec.ProvenanceStores.use(null);
        }
    }

    /** Control: the same cycle run for real records the same node ids, unflagged, with real counts. */
    @Test
    void theSameCycleRunForRealRecordsUnsimulatedProvenance(@TempDir Path dir) throws Exception {
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, "");
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("a.csv"), "ID,AMT,EVENT_DATE\na1,1.0,2020-04-03\na2,2.0,2020-04-03\n");
        String pipeline = cfg.identity().pipelineName();

        try (com.gamma.pipeline.exec.DbProvenanceStore store =
                     com.gamma.pipeline.exec.DbProvenanceStore.open("jdbc:duckdb:")) {
            com.gamma.pipeline.exec.ProvenanceStores.use(store);
            CollectorProcessor.ingest(cfg, e -> { }, false);

            List<java.util.Map<String, Object>> batches = store.batches(pipeline, 10);
            assertEquals(1, batches.size(), batches.toString());
            assertEquals(Boolean.FALSE, batches.getFirst().get("simulated"), batches.toString());
            List<java.util.Map<String, Object>> rows =
                    store.query(pipeline, (String) batches.getFirst().get("batchId"));
            assertEquals(List.of("parse", "sink"), rows.stream().map(r -> r.get("nodeId")).toList(), rows.toString());
            assertEquals(2L, ((Number) rows.get(0).get("rowCount")).longValue(), rows.toString());
        } finally {
            com.gamma.pipeline.exec.ProvenanceStores.use(null);
        }
    }

    /** Provenance disabled (no store registered, the -Dprovenance.backend default): nothing is written at all. */
    @Test
    void withProvenanceDisabledADryRunWritesNothing(@TempDir Path dir) throws Exception {
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, "");
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("a.csv"), "ID,AMT,EVENT_DATE\na1,1.0,2020-04-03\n");

        com.gamma.pipeline.exec.ProvenanceStores.use(null);
        java.util.Map<Path, String> before = snapshot(dir);
        assertDoesNotThrow(() -> CollectorProcessor.ingest(cfg, e -> { }, true));
        assertEquals(before, snapshot(dir), "with no provenance store a dry run writes nothing at all");
    }

    @Test
    void theTerminalBatchSignalCarriesTheFlagSoTheOnSignalChainCanHonourIt() {
        // The on_signal chain builds its Firing from the SIGNAL payload, not from the ConsignmentEvent —
        // a different mechanism from fireOnCommit's, and the half the plan's text conflated with it.
        AtomicReference<Event> seen = new AtomicReference<>();
        Consumer<Event> subscriber = seen::set;
        EventLog.global().addSubscriber(subscriber);
        try {
            com.gamma.signal.PipelineConsignmentSignal.emit(new ConsignmentEvent(
                    "P", "b1", "SUCCESS", List.of("p=1"), 1L, 1L, 0, null, null, 0L, true));
            Event e = seen.get();
            assertNotNull(e, "the terminal-batch Signal must still be emitted for a simulated batch");
            // Read it back exactly as onSignalEvent does — through the Signal, not the raw attributes.
            Object flag = com.gamma.signal.Signal.fromEvent(e).payload().get("dryRun");
            assertEquals(Boolean.TRUE, flag,
                    "without dryRun on the payload, JobService.onSignalEvent fires the chained Job for real");
        } finally {
            EventLog.global().removeSubscriber(subscriber);
        }
    }
}
