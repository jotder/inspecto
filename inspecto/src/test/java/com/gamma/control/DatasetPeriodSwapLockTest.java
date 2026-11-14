package com.gamma.control;

import com.gamma.job.SnapshotSwapLock;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SNAPSHOT-SWAP-UNLOCKED-READERS-1: replacing an upload's period moves each touched Parquet file aside and puts its
 * rewrite at the same path, and {@link DatasetPeriod#restore} deletes the rewrite and moves the original back; both
 * remove a path a reader may have pinned, so both wait for the readers holding the Dataset's store. The reader here
 * holds the store directory ({@code <id>}) while the writer names its {@code database/} read root: one lock.
 */
class DatasetPeriodSwapLockTest {

    private interface Step { void run() throws Exception; }

    private static void parquet(Path file, String values) throws Exception {
        Files.createDirectories(file.getParent());
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT CAST(d AS DATE) AS event_date, v FROM (VALUES " + values + ") t(d, v)) TO '"
                    + file.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        }
    }

    private static long rows(Path file) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM read_parquet('" + file.toString().replace('\\', '/') + "')")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** Run {@code step} while a reader holds {@code store}: it waits, {@code file} keeps its rows; it finishes after. */
    private static void assertWaitsForAReader(Path store, Path file, Step step) throws Exception {
        long before = rows(file);
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try { step.run(); } catch (Throwable t) { failed.set(t); }
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

    @Test
    void replacingAPeriodAndRestoringItWaitForAReaderOfTheStore(@TempDir Path data) throws Exception {
        DuckDbUtil.loadDriver();
        Path store = data.resolve("calls");
        Path older = store.resolve("database").resolve("a.parquet");
        Path fresh = store.resolve("database").resolve("upload.parquet");
        parquet(older, "('2026-10-01', 1), ('2026-10-02', 2)");
        parquet(fresh, "('2026-10-01', 9)");
        DatasetPeriod period = new DatasetPeriod(data, "calls", "t1");

        AtomicReference<DatasetPeriod.Result> replaced = new AtomicReference<>();
        assertWaitsForAReader(store, older,
                () -> replaced.set(period.replace(List.of(fresh), List.of(fresh), "event_date")));
        assertEquals(1, replaced.get().rowsRemoved());
        assertEquals(1, rows(older), "the upload's day was removed from the older file");

        assertWaitsForAReader(store, older, period::restore);
        assertEquals(2, rows(older), "the original is back");
    }
}
