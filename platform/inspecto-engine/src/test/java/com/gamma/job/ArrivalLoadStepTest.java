package com.gamma.job;

import com.gamma.consignment.ConsignmentOutput;
import com.gamma.consignment.ConsignmentOutput.State;
import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.consignment.DbConsignmentOutputStore;
import com.gamma.etl.ConsignmentEvent;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfig.ArrivalLoad;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code BASIC-ELT-PROFILE-1} O6: the {@code output.load} commit step ({@link ArrivalLoadStep}) on a Standard Pipeline's
 * own store folder (not an import's {@code database}): a {@code period} Consignment removes exactly its days from the
 * other files, a {@code replace} one every other file, each final at once (journal gone, files {@code REPLACED}); append,
 * a failed or a dry-run commit touch nothing. A run-scoped choice wins over the config, replaces only the Consignment
 * holding its file, never removes what the run committed before, and is the caller's to undo or make final.
 */
class ArrivalLoadStepTest {

    private DbConsignmentOutputStore registry;

    @BeforeEach
    void registry() throws Exception {
        DuckDbUtil.loadDriver();
        registry = DbConsignmentOutputStore.open("jdbc:duckdb:");
        ConsignmentOutputStores.use(registry);
    }

    @AfterEach
    void reset() throws Exception {
        ConsignmentOutputStores.use(null);
        registry.close();
    }

    private Path parquet(Path file, String consignment, String values) throws Exception {
        Files.createDirectories(file.getParent());
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT CAST(d AS DATE) AS event_date, v FROM (VALUES " + values + ") t(d, v)) TO '"
                    + file.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        }
        registry.record(List.of(new ConsignmentOutput(consignment, null, "t", null, null, file.toString(), 1, 1,
                "2026-11-20T00:00:00Z", State.LIVE)));
        return file;
    }

    private static List<String> rows(Path file) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT CAST(event_date AS VARCHAR) || ':' || v FROM read_parquet('"
                     + file.toString().replace('\\', '/') + "') ORDER BY 1")) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private State state(String consignment) {
        return registry.outputs(consignment).get(0).state();
    }

    private static PipelineConfig config(Path store, Map<String, Object> output) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "calls");
        m.put("dirs", Map.of("poll", store.resolveSibling("in").toString(), "database", store.toString()));
        m.put("processing", Map.of("threads", 1));
        m.put("output", output);
        return PipelineConfig.fromMap(m);
    }

    private static ConsignmentEvent commit(String status, boolean dryRun, String id, Path... outputs) {
        return new ConsignmentEvent("calls", id, status, List.of(), 1, 1, 0, null, null, 0, dryRun,
                Stream.of(outputs).map(Path::toString).toList());
    }

    /** The store {@code out/calls} (a Standard Pipeline's own folder): three older arrivals. */
    private record Store(Path dir, Path a, Path b, Path c) {}

    private Store store(Path root) throws Exception {
        Path dir = root.resolve("out").resolve("calls");
        Store s = new Store(dir, dir.resolve("a.parquet"), dir.resolve("b.parquet"), dir.resolve("c.parquet"));
        parquet(s.a, "ca", "('2026-10-01', 1), ('2026-10-02', 2)");
        parquet(s.b, "cb", "('2026-10-01', 3)");
        parquet(s.c, "cc", "('2026-10-03', 5)");
        return s;
    }

    private static void assertSettled(Store s) throws Exception {
        assertFalse(Files.exists(ArrivalReplacer.journalFile(s.dir)), "the journal is gone");
        assertEquals("calls.period.pending", ArrivalReplacer.journalFile(s.dir).getFileName().toString(),
                "a store's journal is named by the store, beside it");
        try (Stream<Path> l = Files.list(s.dir.getParent())) {
            assertTrue(l.noneMatch(p -> p.getFileName().toString().startsWith("calls.period-")), "the originals are gone");
        }
    }

    @Test
    void aPeriodPipelineReplacesTheDaysEachConsignmentCoversAtOnce(@TempDir Path root) throws Exception {
        Store s = store(root);
        PipelineConfig cfg = config(s.dir, Map.of("load", "period", "period_column", "event_date"));
        Path fresh = parquet(s.dir.resolve("fresh.parquet"), "cf", "('2026-10-01', 9)");
        ArrivalLoadStep.commit(cfg, commit("SUCCESS", false, "cf", fresh), List::of);
        assertEquals(List.of("2026-10-02:2"), rows(s.a));
        assertFalse(Files.exists(s.b), "a file left with no row goes");
        assertEquals(List.of("2026-10-03:5"), rows(s.c));
        assertEquals(List.of("2026-10-01:9"), rows(fresh));
        assertSettled(s);
        assertEquals(State.REPLACED, state("ca"));
        assertEquals(State.REPLACED, state("cb"));
        assertEquals(State.LIVE, state("cc"));
        assertEquals(State.LIVE, state("cf"));
    }

    @Test
    void aReplacePipelineKeepsOnlyTheLastConsignment(@TempDir Path root) throws Exception {
        Store s = store(root);
        PipelineConfig cfg = config(s.dir, Map.of("load", "replace"));
        Path snap = parquet(s.dir.resolve("snap.parquet"), "cs", "('2026-10-05', 7)");
        ArrivalLoadStep.commit(cfg, commit("SUCCESS", false, "cs", snap), List::of);
        try (Stream<Path> l = Files.list(s.dir)) {
            assertEquals(List.of("snap.parquet"), l.map(p -> p.getFileName().toString()).toList());
        }
        assertSettled(s);
        for (String c : List.of("ca", "cb", "cc")) assertEquals(State.REPLACED, state(c), c);
        assertEquals(State.LIVE, state("cs"));
    }

    @Test
    void appendAFailedOrADryRunCommitAndAnEmptyArrivalTouchNothing(@TempDir Path root) throws Exception {
        Store s = store(root);
        Path fresh = parquet(s.dir.resolve("fresh.parquet"), "cf", "('2026-10-01', 9)");
        ArrivalLoadStep.commit(config(s.dir, Map.of("format", "PARQUET")), commit("SUCCESS", false, "cf", fresh), List::of);
        PipelineConfig replace = config(s.dir, Map.of("load", "replace"));
        ArrivalLoadStep.commit(replace, commit("FAILED", false, "cf", fresh), List::of);
        ArrivalLoadStep.commit(replace, commit("SUCCESS", true, "cf", fresh), List::of);
        ArrivalLoadStep.commit(replace, commit("SUCCESS", false, "c0"), List::of);   // wrote no file: never empties the store
        assertEquals(List.of("2026-10-01:1", "2026-10-02:2"), rows(s.a));
        assertEquals(List.of("2026-10-01:3"), rows(s.b));
        assertEquals(List.of("2026-10-03:5"), rows(s.c));
        for (String c : List.of("ca", "cb", "cc")) assertEquals(State.LIVE, state(c), c);
    }

    @Test
    void aFailedReplacementKeepsTheCommittedRows(@TempDir Path root) throws Exception {
        Store s = store(root);
        // an older file written before the store had the period column: the period cannot be read from it
        Path older = s.dir.resolve("older.parquet");
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT 7 AS v) TO '" + older.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        }
        Path fresh = parquet(s.dir.resolve("fresh.parquet"), "cf", "('2026-10-01', 9)");
        ArrivalLoadStep.commit(config(s.dir, Map.of("load", "period", "period_column", "event_date")),
                commit("SUCCESS", false, "cf", fresh), List::of);
        assertEquals(List.of("2026-10-01:9"), rows(fresh), "the engine committed it: its rows are never deleted");
        assertEquals(List.of("2026-10-01:1", "2026-10-02:2"), rows(s.a), "nothing replaced");
        assertTrue(Files.exists(older));
        assertSettled(s);
        assertEquals(State.LIVE, state("ca"));
    }

    @Test
    void aCrashedReplacementIsFinishedAtTheStoresNextCommitWhateverItsMode(@TempDir Path root) throws Exception {
        Store s = store(root);
        Path fresh = parquet(s.dir.resolve("fresh.parquet"), "cf", "('2026-10-01', 9)");
        ArrivalReplacer.checkpointForTest = where -> {
            if ("swap".equals(where)) throw new StackOverflowError("simulated crash");   // an Error: nothing catches it
        };
        try {
            PipelineConfig period = config(s.dir, Map.of("load", "period", "period_column", "event_date"));
            assertThrows(StackOverflowError.class, () -> ArrivalLoadStep.commit(period, commit("SUCCESS", false, "cf", fresh), List::of));
        } finally {
            ArrivalReplacer.checkpointForTest = null;
        }
        assertTrue(Files.exists(ArrivalReplacer.journalFile(s.dir)), "the crash left the replacement half done");
        // the config went back to append: the next commit still finishes the swap (forward: the arrival was committed)
        Path later = parquet(s.dir.resolve("later.parquet"), "cl", "('2026-10-07', 1)");
        ArrivalLoadStep.commit(config(s.dir, Map.of("format", "PARQUET")), commit("SUCCESS", false, "cl", later), List::of);
        assertEquals(List.of("2026-10-02:2"), rows(s.a));
        assertFalse(Files.exists(s.b));
        assertEquals(List.of("2026-10-01:9"), rows(fresh));
        assertEquals(List.of("2026-10-07:1"), rows(later));
        assertSettled(s);
        assertEquals(State.REPLACED, state("ca"));
    }

    private static final PipelineConfig.ArrivalLoad PERIOD = new ArrivalLoad(ArrivalLoad.Mode.PERIOD, "event_date");

    /** A config {@code period} commit of {@code fresh} with {@code at} the checkpoint seam (reset after). */
    private static void periodCommit(Store s, Path fresh, java.util.function.Consumer<String> at) throws Exception {
        ArrivalReplacer.checkpointForTest = at;
        try {
            ArrivalLoadStep.commit(config(s.dir, Map.of("load", "period", "period_column", "event_date")),
                    commit("SUCCESS", false, "cf", fresh), List::of);
        } finally {
            ArrivalReplacer.checkpointForTest = null;
        }
    }

    private void assertOriginalsAndFreshKept(Store s, Path fresh) throws Exception {
        assertEquals(List.of("2026-10-01:1", "2026-10-02:2"), rows(s.a), "the original is back");
        assertEquals(List.of("2026-10-01:3"), rows(s.b), "the emptied file is back");
        assertEquals(List.of("2026-10-01:9"), rows(fresh), "the committed rows are never deleted");
        assertSettled(s);
        assertEquals(State.LIVE, state("ca"));
    }

    @Test
    void aReplacementThatFailsMidSwapPutsTheOriginalsBackAndKeepsTheCommittedRows(@TempDir Path root) throws Exception {
        Store s = store(root);
        Path fresh = parquet(s.dir.resolve("fresh.parquet"), "cf", "('2026-10-01', 9)");
        java.util.concurrent.atomic.AtomicBoolean once = new java.util.concurrent.atomic.AtomicBoolean();
        // a failure (not a crash) after the journal and the first file's swap: the step catches it and abandons
        periodCommit(s, fresh, where -> {
            if ("swap".equals(where) && once.compareAndSet(false, true)) throw new IllegalStateException("disk full");
        });
        assertOriginalsAndFreshKept(s, fresh);
    }

    @Test
    void aCrashDuringTheAbandonHealsBackAndStillKeepsTheCommittedRows(@TempDir Path root) throws Exception {
        Store s = store(root);
        Path fresh = parquet(s.dir.resolve("fresh.parquet"), "cf", "('2026-10-01', 9)");
        java.util.concurrent.atomic.AtomicBoolean failed = new java.util.concurrent.atomic.AtomicBoolean();
        assertThrows(StackOverflowError.class, () -> periodCommit(s, fresh, where -> {
            if ("swap".equals(where) && failed.compareAndSet(false, true)) throw new IllegalStateException("disk full");
            if ("restore".equals(where)) throw new StackOverflowError("simulated crash in the put-back");
        }));
        assertTrue(Files.exists(ArrivalReplacer.journalFile(s.dir)), "the crash left the put-back half done");
        Path later = parquet(s.dir.resolve("later.parquet"), "cl", "('2026-10-07', 1)");
        ArrivalLoadStep.commit(config(s.dir, Map.of("format", "PARQUET")), commit("SUCCESS", false, "cl", later), List::of);
        assertOriginalsAndFreshKept(s, fresh);
    }

    @Test
    void aLostRewriteHealsBackAndKeepsTheCommittedRowsOfAPipelinesOwnStore(@TempDir Path root) throws Exception {
        Store s = store(root);
        Path d = parquet(s.dir.resolve("d.parquet"), "cd", "('2026-10-01', 4), ('2026-10-04', 5)");
        Path fresh = parquet(s.dir.resolve("fresh.parquet"), "cf", "('2026-10-01', 9)");
        assertThrows(StackOverflowError.class, () -> periodCommit(s, fresh, where -> {
            if ("swap".equals(where)) throw new StackOverflowError("simulated crash after the first swap");
        }));
        Path dRewrite = s.dir.resolve(".period-d.parquet.tmp");
        assertTrue(Files.exists(dRewrite), "d is not swapped yet");
        Files.delete(dRewrite);   // the rewrite is lost: the heal must go back
        Path later = parquet(s.dir.resolve("later.parquet"), "cl", "('2026-10-07', 1)");
        ArrivalLoadStep.commit(config(s.dir, Map.of("format", "PARQUET")), commit("SUCCESS", false, "cl", later), List::of);
        assertEquals(List.of("2026-10-01:4", "2026-10-04:5"), rows(d));
        assertOriginalsAndFreshKept(s, fresh);
    }

    @Test
    void aHalfSwappedStoreIsNotHealedWhileARunScopedChoiceIsOpen(@TempDir Path root) throws Exception {
        Store s = store(root);
        Path fresh = parquet(s.dir.resolve("fresh.parquet"), "cf", "('2026-10-01', 9)");
        assertThrows(StackOverflowError.class, () -> periodCommit(s, fresh, where -> {
            if ("swap".equals(where)) throw new StackOverflowError("simulated crash after the first swap");
        }));
        PipelineConfig append = config(s.dir, Map.of("format", "PARQUET"));
        try (ArrivalLoadStep.RunLoad run = ArrivalLoadStep.override(s.dir, PERIOD, "other.csv")) {
            Path waiting = parquet(s.dir.resolve("waiting.parquet"), "cw", "('2026-10-05', 8)");
            ArrivalLoadStep.commit(append, commit("SUCCESS", false, "cw", waiting), () -> List.of("waiting.csv"));
            assertTrue(Files.exists(ArrivalReplacer.journalFile(s.dir)), "left alone while the run's choice is open");
            assertTrue(Files.exists(s.b), "still half swapped");
            assertNull(run.result());
        }
        Path later = parquet(s.dir.resolve("later.parquet"), "cl", "('2026-10-07', 1)");
        ArrivalLoadStep.commit(append, commit("SUCCESS", false, "cl", later), List::of);
        assertFalse(Files.exists(s.b), "healed forward at the next commit once the choice is closed");
        assertSettled(s);
    }

    @Test
    void aRunScopedChoiceReplacesOnlyItsFilesConsignmentAndIsTheCallersToUndo(@TempDir Path root) throws Exception {
        Store s = store(root);
        PipelineConfig cfg = config(s.dir, Map.of("format", "PARQUET"));   // the config appends
        try (ArrivalLoadStep.RunLoad run = ArrivalLoadStep.override(s.dir, new ArrivalLoad(ArrivalLoad.Mode.PERIOD,
                "event_date"), "upload.csv")) {
            assertThrows(IllegalStateException.class, () -> ArrivalLoadStep.override(s.dir, ArrivalLoad.APPEND, null),
                    "one run-scoped choice per store");
            // a waiting file of the same run commits first: it appends, and the upload's period never removes it
            Path waiting = parquet(s.dir.resolve("waiting.parquet"), "cw", "('2026-10-01', 8)");
            ArrivalLoadStep.commit(cfg, commit("SUCCESS", false, "cw", waiting), () -> List.of("old_waiting.csv"));
            assertEquals(List.of("2026-10-01:1", "2026-10-02:2"), rows(s.a), "not the named file: appended");
            assertNull(run.result());
            Path upload = parquet(s.dir.resolve("upload.parquet"), "cu", "('2026-10-01', 9)");
            ArrivalLoadStep.commit(cfg, commit("SUCCESS", false, "cu", upload), () -> List.of("upload.csv"));
            assertEquals(List.of("2026-10-01"), run.result().days());
            assertEquals(2, run.result().rowsRemoved());
            assertEquals(List.of("2026-10-02:2"), rows(s.a));
            assertFalse(Files.exists(s.b));
            assertEquals(List.of("2026-10-01:8"), rows(waiting), "the run's own earlier file keeps its rows");
            assertNull(run.failure());
            assertEquals(State.LIVE, state("ca"), "not final before the caller decides");

            run.restore();   // the caller refused the run
            assertEquals(List.of("2026-10-01:1", "2026-10-02:2"), rows(s.a));
            assertEquals(List.of("2026-10-01:3"), rows(s.b));
            assertFalse(Files.exists(upload), "the replacing Consignment's rows go with it: no day is doubled");
        }
        assertSettled(s);
        assertEquals(State.LIVE, state("ca"), "a restored replacement marks nothing");
        // closed: the config rules again (append)
        Path later = parquet(s.dir.resolve("later.parquet"), "cl", "('2026-10-02', 4)");
        ArrivalLoadStep.commit(cfg, commit("SUCCESS", false, "cl", later), List::of);
        assertEquals(List.of("2026-10-01:1", "2026-10-02:2"), rows(s.a));
    }

    @Test
    void aRunScopedReplaceWinsOverAnAppendConfigAndClosingMakesItFinal(@TempDir Path root) throws Exception {
        Store s = store(root);
        PipelineConfig cfg = config(s.dir, Map.of("format", "PARQUET"));
        Path snap;
        try (ArrivalLoadStep.RunLoad run = ArrivalLoadStep.override(s.dir, new ArrivalLoad(ArrivalLoad.Mode.REPLACE, null), null)) {
            snap = parquet(s.dir.resolve("snap.parquet"), "cs", "('2026-10-05', 7)");
            ArrivalLoadStep.commit(cfg, commit("SUCCESS", false, "cs", snap), List::of);
            assertEquals(3, run.result().filesTouched());
            assertEquals(4, run.result().rowsRemoved());
            Path second = parquet(s.dir.resolve("second.parquet"), "c2", "('2026-10-06', 1)");
            ArrivalLoadStep.commit(cfg, commit("SUCCESS", false, "c2", second), List::of);
            assertTrue(Files.exists(snap), "one Consignment replaces; the run's next one appends");
        }
        try (Stream<Path> l = Files.list(s.dir)) {
            assertEquals(List.of("second.parquet", "snap.parquet"), l.map(p -> p.getFileName().toString()).sorted().toList());
        }
        assertSettled(s);
        for (String c : List.of("ca", "cb", "cc")) assertEquals(State.REPLACED, state(c), c);
    }
}
