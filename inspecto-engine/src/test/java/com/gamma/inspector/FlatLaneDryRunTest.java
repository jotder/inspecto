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
 * is the ingest cycle itself (it must write no outputs, no audit or commit-log rows, no provenance row, no
 * markers, and must leave the inbox untouched), phase two is the chaining (the published
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
