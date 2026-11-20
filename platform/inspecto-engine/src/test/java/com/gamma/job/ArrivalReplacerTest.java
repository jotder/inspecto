package com.gamma.job;

import com.gamma.consignment.ConsignmentOutput;
import com.gamma.consignment.ConsignmentOutput.State;
import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.consignment.DbConsignmentOutputStore;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code BASIC-ELT-PROFILE-1} B4: <i>Replace the period</i> in the engine ({@link ArrivalReplacer}) - the plan's data-loss
 * risk. The replaced rows are restorable when the import fails, a crash mid-replace heals to the old state or the new one
 * and never to a mix (forward from a swap, back from a restore, back when a rewrite was lost), the merge map stays true both
 * ways, every swap waits for the store's readers, and once the import succeeded every file whose rows were replaced is
 * {@code REPLACED} in the Consignment output registry (Replace everything too). The Add data side (the person's answer,
 * arrivals, the reprocess refusal over HTTP) is pinned by {@code ControlApiOnboardFilesTest} and
 * {@code ControlApiDatasetCompactionTest}.
 */
class ArrivalReplacerTest {

    /** A simulated crash: an {@link Error} no step catches, thrown at a checkpoint. */
    private static final class Crash extends Error {
        Crash() {
            super("simulated crash");
        }
    }

    private DbConsignmentOutputStore registry;

    @BeforeEach
    void registry() throws Exception {
        DuckDbUtil.loadDriver();
        registry = DbConsignmentOutputStore.open("jdbc:duckdb:");
        ConsignmentOutputStores.use(registry);
    }

    @AfterEach
    void reset() throws Exception {
        ArrivalReplacer.checkpointForTest = null;
        ConsignmentOutputStores.use(null);
        registry.close();
    }

    /** A Parquet file of {@code (event_date, v)} rows, recorded LIVE in the registry as Consignment {@code consignment}. */
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

    /** The Dataset {@code calls}: four older arrivals and the upload that corrects 2026-10-01. */
    private static final class Store {
        final Path data, base, db, a, b, c, e, upload;

        Store(Path data) {
            this.data = data;
            this.base = data.resolve("calls");
            this.db = base.resolve("database");
            this.a = db.resolve("a.parquet");   // 1 and 2 October: loses the 1st
            this.b = db.resolve("b.parquet");   // 1 and 4 October: loses the 1st
            this.c = db.resolve("c.parquet");   // 3 October: untouched
            this.e = db.resolve("e.parquet");   // 1 October only: emptied, so it goes
            this.upload = db.resolve("upload.parquet");
        }
    }

    private Store store(Path data) throws Exception {
        Store s = new Store(data);
        parquet(s.a, "ca", "('2026-10-01', 1), ('2026-10-02', 2)");
        parquet(s.b, "cb", "('2026-10-01', 3), ('2026-10-04', 4)");
        parquet(s.c, "cc", "('2026-10-03', 5)");
        parquet(s.e, "ce", "('2026-10-01', 6)");
        parquet(s.upload, "cu", "('2026-10-01', 9)");
        return s;
    }

    private static ArrivalReplacer.Result replace(ArrivalReplacer r, Store s) throws Exception {
        return r.replacePeriod(List.of(s.upload), List.of(s.upload), "event_date");
    }

    private void assertNewState(Store s) throws Exception {
        assertEquals(List.of("2026-10-02:2"), rows(s.a));
        assertEquals(List.of("2026-10-04:4"), rows(s.b));
        assertEquals(List.of("2026-10-03:5"), rows(s.c));
        assertFalse(Files.exists(s.e), "a file left with no row goes");
        assertEquals(List.of("2026-10-01:9"), rows(s.upload), "the upload's rows are the 1st now");
    }

    private void assertOldState(Store s) throws Exception {
        assertEquals(List.of("2026-10-01:1", "2026-10-02:2"), rows(s.a));
        assertEquals(List.of("2026-10-01:3", "2026-10-04:4"), rows(s.b));
        assertEquals(List.of("2026-10-03:5"), rows(s.c));
        assertEquals(List.of("2026-10-01:6"), rows(s.e));
        assertFalse(Files.exists(s.upload), "the upload's own rows go with the replacement: no day is left doubled");
        for (String c : List.of("ca", "cb", "cc", "ce")) assertEquals(State.LIVE, state(c), c);
    }

    /** Nothing of the replacement is left: no journal, no aside folder, no hidden rewrite. */
    private static void assertSettled(Store s) throws Exception {
        assertFalse(Files.exists(s.base.resolve(ArrivalReplacer.JOURNAL)), "the journal is gone");
        try (Stream<Path> l = Files.list(s.base)) {
            assertTrue(l.noneMatch(p -> p.getFileName().toString().startsWith("database.period-")), "the originals are gone");
        }
        try (Stream<Path> w = Files.walk(s.db)) {
            assertTrue(w.noneMatch(p -> p.getFileName().toString().startsWith(".period-")), "no rewrite is left");
        }
    }

    private void assertMarked() {
        assertEquals(State.REPLACED, state("ca"));
        assertEquals(State.REPLACED, state("cb"));
        assertEquals(State.REPLACED, state("ce"));
        assertEquals(State.LIVE, state("cc"), "a file the period missed is untouched");
        assertEquals(State.LIVE, state("cu"), "the upload's own Consignment is untouched");
    }

    @Test
    void replacingThePeriodRemovesExactlyItsDaysAndTheDropMarksTheRegistry(@TempDir Path data) throws Exception {
        Store s = store(data);
        ArrivalReplacer r = new ArrivalReplacer(data, "calls", "t1");
        ArrivalReplacer.Result res = replace(r, s);
        assertEquals(List.of("2026-10-01"), res.days());
        assertEquals(3, res.rowsRemoved());
        assertEquals(3, res.filesTouched());
        assertNewState(s);
        assertTrue(Files.exists(s.base.resolve(ArrivalReplacer.JOURNAL)), "kept until the import is decided");
        for (String c : List.of("ca", "cb", "ce")) assertEquals(State.LIVE, state(c), "not marked before the import succeeded");

        r.drop();
        assertNewState(s);
        assertSettled(s);
        assertMarked();
        assertTrue(registry.isReadable(s.a.toString()), "a rewritten file is still read: it holds the 2nd");
        r.restore();
        assertNewState(s);   // a restore after the drop does nothing
    }

    @Test
    void aFailedImportRestoresEveryReplacedRow(@TempDir Path data) throws Exception {
        Store s = store(data);
        ArrivalReplacer r = new ArrivalReplacer(data, "calls", "t1");
        replace(r, s);
        r.restore();
        assertOldState(s);
        assertSettled(s);
        r.drop();
        assertOldState(s);   // a drop after the restore does nothing
    }

    @Test
    void aReplaceThatFailsBeforeItsSwapStillRemovesTheUploadsRows(@TempDir Path data) throws Exception {
        Store s = store(data);
        ArrivalReplacer r = new ArrivalReplacer(data, "calls", "t1");
        // a When column whose values are not dates: the days cannot be read, nothing is touched
        assertThrows(java.io.IOException.class, () -> r.replacePeriod(List.of(s.upload), List.of(s.upload), "missing_column"));
        r.restore();
        assertOldState(s);
        assertSettled(s);
    }

    @Test
    void aCrashMidSwapHealsForwardToTheNewState(@TempDir Path data) throws Exception {
        Store s = store(data);
        AtomicInteger swapped = new AtomicInteger();
        ArrivalReplacer.checkpointForTest = where -> {
            if ("swap".equals(where) && swapped.incrementAndGet() == 1) throw new Crash();
        };
        assertThrows(Crash.class, () -> replace(new ArrivalReplacer(data, "calls", "t1"), s));
        // the crash left a mix: the first file without the 1st, the others still with it
        assertEquals(List.of("2026-10-02:2"), rows(s.a));
        assertEquals(List.of("2026-10-01:3", "2026-10-04:4"), rows(s.b));
        ArrivalReplacer.checkpointForTest = null;

        ArrivalReplacer.heal(data, "calls");
        assertNewState(s);
        assertSettled(s);
        assertMarked();
    }

    @Test
    void aCrashMidRestoreHealsBackToTheOldState(@TempDir Path data) throws Exception {
        Store s = store(data);
        ArrivalReplacer r = new ArrivalReplacer(data, "calls", "t1");
        replace(r, s);
        AtomicInteger restored = new AtomicInteger();
        ArrivalReplacer.checkpointForTest = where -> {
            if ("restore".equals(where) && restored.incrementAndGet() == 1) throw new Crash();
        };
        assertThrows(Crash.class, r::restore);
        // the crash left a mix: the last file is back, the first is not, the upload is still in
        assertEquals(List.of("2026-10-01:6"), rows(s.e));
        assertEquals(List.of("2026-10-02:2"), rows(s.a));
        assertTrue(Files.exists(s.upload));
        ArrivalReplacer.checkpointForTest = null;

        ArrivalReplacer.heal(data, "calls");
        assertOldState(s);
        assertSettled(s);
    }

    @Test
    void aCrashAfterTheSwapOrMidDropHealsForward(@TempDir Path data) throws Exception {
        Store s = store(data);
        replace(new ArrivalReplacer(data, "calls", "t1"), s);   // the process dies before the import is decided
        ArrivalReplacer.checkpointForTest = where -> {
            if ("drop".equals(where)) throw new Crash();
        };
        assertThrows(Crash.class, () -> ArrivalReplacer.heal(data, "calls"));   // and again while finishing
        assertMarked();
        assertTrue(Files.exists(s.base.resolve(ArrivalReplacer.JOURNAL)));
        ArrivalReplacer.checkpointForTest = null;

        ArrivalReplacer.heal(data, "calls");
        assertNewState(s);
        assertSettled(s);
        assertMarked();
    }

    @Test
    void aSwapWhoseRewriteWasLostHealsBack(@TempDir Path data) throws Exception {
        Store s = store(data);
        AtomicInteger swapped = new AtomicInteger();
        ArrivalReplacer.checkpointForTest = where -> {
            if ("swap".equals(where) && swapped.incrementAndGet() == 1) throw new Crash();
        };
        assertThrows(Crash.class, () -> replace(new ArrivalReplacer(data, "calls", "t1"), s));
        ArrivalReplacer.checkpointForTest = null;
        Files.delete(s.db.resolve(".period-b.parquet.tmp"));   // b's rewrite is lost: forward is impossible

        ArrivalReplacer.heal(data, "calls");
        assertOldState(s);
        assertSettled(s);
    }

    @Test
    void theNextReplaceHealsFirst(@TempDir Path data) throws Exception {
        Store s = store(data);
        AtomicInteger swapped = new AtomicInteger();
        ArrivalReplacer.checkpointForTest = where -> {
            if ("swap".equals(where) && swapped.incrementAndGet() == 1) throw new Crash();
        };
        assertThrows(Crash.class, () -> replace(new ArrivalReplacer(data, "calls", "t1"), s));
        ArrivalReplacer.checkpointForTest = null;

        // a second upload of the 4th: the first replace is finished before the second reads anything
        Path fourth = parquet(s.db.resolve("upload2.parquet"), "cu2", "('2026-10-04', 7)");
        ArrivalReplacer second = new ArrivalReplacer(data, "calls", "t2");
        assertEquals(1, second.replacePeriod(List.of(fourth), List.of(fourth), "event_date").rowsRemoved());
        second.drop();
        assertEquals(List.of("2026-10-02:2"), rows(s.a));
        assertFalse(Files.exists(s.b), "the 1st went with the first replace, the 4th with the second");
        assertSettled(s);
    }

    @Test
    void aCompactionHealsFirstAndNeverMergesAMix(@TempDir Path data) throws Exception {
        Store s = store(data);
        AtomicInteger swapped = new AtomicInteger();
        ArrivalReplacer.checkpointForTest = where -> {
            if ("swap".equals(where) && swapped.incrementAndGet() == 1) throw new Crash();
        };
        assertThrows(Crash.class, () -> replace(new ArrivalReplacer(data, "calls", "t1"), s));
        ArrivalReplacer.checkpointForTest = null;

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("origin", "import");
        entry.put(ArrivalCompactor.EVENT_DATE_KEY, "event_date");
        assertEquals("COMPACTED", ArrivalCompactor.compact(data, "calls", entry, true, List.of(), null).get("result"));
        Path merged = ArrivalCompactor.parquetFiles(s.db).get(0);
        assertEquals(List.of("2026-10-01:9", "2026-10-02:2", "2026-10-03:5", "2026-10-04:4"), rows(merged),
                "the replace was finished before the merge: the 1st once, from the upload");
        assertSettled(s);
        assertEquals(State.REPLACED, state("ce"), "the emptied file was marked by the heal");
        for (String c : List.of("ca", "cb", "cc", "cu"))
            assertEquals(State.COMPACTED_AWAY, state(c), "merged since: still refused, now as merged - " + c);
    }

    @Test
    void theMergeMapStaysTrueBothWays(@TempDir Path data) throws Exception {
        Store s = store(data);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("origin", "import");
        entry.put(ArrivalCompactor.EVENT_DATE_KEY, "event_date");
        Files.delete(s.upload);   // merge the four older arrivals first, then the upload arrives
        assertEquals("COMPACTED", ArrivalCompactor.compact(data, "calls", entry, true, List.of(), null).get("result"));
        Map<String, ArrivalCompactor.Segment> before = ArrivalCompactor.merged(data, "calls");
        assertEquals(4, before.size());
        assertTrue(before.values().stream().allMatch(seg -> seg.rows() > 0), before.toString());
        byte[] mapBefore = Files.readAllBytes(s.base.resolve(ArrivalCompactor.MAP));
        Path upload = parquet(s.upload, "cu", "('2026-10-01', 9)");

        // a crash mid-swap of the merged file heals forward: the map re-counted, every segment at a known place
        ArrivalReplacer.checkpointForTest = where -> {
            if ("swap".equals(where)) throw new Crash();
        };
        assertThrows(Crash.class, () -> new ArrivalReplacer(data, "calls", "t1").replacePeriod(List.of(upload), List.of(upload), "event_date"));
        ArrivalReplacer.checkpointForTest = null;
        ArrivalReplacer.heal(data, "calls");
        Map<String, ArrivalCompactor.Segment> after = ArrivalCompactor.merged(data, "calls");
        assertEquals(3, after.size(), "the arrival left with no row drops out: " + after);
        assertTrue(after.values().stream().allMatch(seg -> seg.rows() > 0), "every arrival still at a known place: " + after);
        assertEquals(3, after.values().stream().mapToLong(ArrivalCompactor.Segment::rows).sum());

        // a failed import's restore puts the merge map back byte for byte, with the merged file it describes
        Path upload2 = parquet(s.db.resolve("upload2.parquet"), "cu2", "('2026-10-02', 8)");
        byte[] mapNow = Files.readAllBytes(s.base.resolve(ArrivalCompactor.MAP));
        ArrivalReplacer r = new ArrivalReplacer(data, "calls", "t2");
        assertEquals(1, r.replacePeriod(List.of(upload2), List.of(upload2), "event_date").rowsRemoved());
        assertNotEquals(new String(mapNow), new String(Files.readAllBytes(s.base.resolve(ArrivalCompactor.MAP))));
        r.restore();
        assertArrayEquals(mapNow, Files.readAllBytes(s.base.resolve(ArrivalCompactor.MAP)));
        assertEquals(after, ArrivalCompactor.merged(data, "calls"), "the map still describes the merged file exactly");
        assertNotEquals(new String(mapBefore), new String(mapNow));
    }

    @Test
    void replaceEverythingMarksWhatItSetAsideUnlessTheRunWroteItAgain(@TempDir Path data) throws Exception {
        Store s = store(data);
        Path aside = s.base.resolve("database.prev-1");
        Files.move(s.db, aside);   // what Add data's setAside does
        parquet(s.upload, "cu", "('2026-10-05', 9)");   // the replacing run writes the upload's path again
        ArrivalReplacer.everythingReplaced(aside, s.db);
        for (String c : List.of("ca", "cb", "cc", "ce")) assertEquals(State.REPLACED, state(c), c);
        assertEquals(State.LIVE, state("cu"), "a path the run wrote again is the new data");
    }

    // ── the store's readers (SNAPSHOT-SWAP-UNLOCKED-READERS-1) ───────────────────────────────────────────────

    private interface Step {
        void run() throws Exception;
    }

    /** Run {@code step} while a reader holds {@code store}: it waits, {@code file} keeps its rows; it finishes after. */
    private static void assertWaitsForAReader(Path store, Path file, Step step) throws Exception {
        List<String> before = rows(file);
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                step.run();
            } catch (Throwable t) {
                failed.set(t);
            }
        });
        try (SnapshotSwapLock.Held reading = SnapshotSwapLock.read(List.of(store))) {
            writer.start();
            long deadline = System.nanoTime() + 30_000_000_000L;
            while (writer.isAlive() && !SnapshotSwapLock.hasQueuedThreads(store)) {
                if (System.nanoTime() > deadline) fail("the writer neither finished nor waited for the reader");
                Thread.sleep(5);
            }
            assertTrue(writer.isAlive(), "the writer waits for the reader (failure: " + failed.get() + ")");
            assertEquals(before, rows(file), "the file the reader pinned is still in place, unchanged");
        }
        writer.join(30_000);
        assertNull(failed.get(), "the writer finished once the reader was done: " + failed.get());
    }

    /**
     * Replacing the period moves each touched file aside and puts its rewrite at the same path, and a restore deletes the
     * rewrite and moves the original back; both remove a path a reader may have pinned, so both wait for the readers of
     * the Dataset's store. The reader holds the store directory ({@code <id>}) while the writer names its
     * {@code database/} read root: one lock.
     */
    @Test
    void replacingAndRestoringWaitForAReaderOfTheStore(@TempDir Path data) throws Exception {
        Store s = store(data);
        ArrivalReplacer r = new ArrivalReplacer(data, "calls", "t1");
        assertWaitsForAReader(s.base, s.a, () -> replace(r, s));
        assertEquals(List.of("2026-10-02:2"), rows(s.a), "the upload's day was removed from the older file");
        assertWaitsForAReader(s.base, s.a, r::restore);
        assertEquals(List.of("2026-10-01:1", "2026-10-02:2"), rows(s.a), "the original is back");
    }
}
