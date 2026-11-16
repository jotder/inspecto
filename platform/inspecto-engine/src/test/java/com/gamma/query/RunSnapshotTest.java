package com.gamma.query;

import com.gamma.job.PlatformServiceRegistry;
import com.gamma.job.PlatformServices;
import com.gamma.job.SnapshotSwapLock;
import com.gamma.util.JdbcDrivers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RunSnapshot} (Platform Services Stage 2, MoSCoW S6): a Run pins each Dataset once, at its first read, and every
 * later read in the Run sees the same files across a snapshot swap; a new Run sees the new files; a Run's pin never holds
 * the swap lock; closing the Run releases it.
 */
class RunSnapshotTest {

    private static final Map<String, Map<String, Object>> DATASETS = Map.of(
            "cdr", Map.of("physicalRef", "cdr"),
            "cdr_calc", Map.of("physicalRef", "cdr", "calculated", List.of(Map.of("name", "twice", "expr", "id * 2"))),
            "cdr_big", Map.of("sql", "SELECT id FROM cdr WHERE id > 0", "sourceName", "cdr"));

    private static PlatformServices granted(Path root) {
        PlatformServiceRegistry platform = new PlatformServiceRegistry();
        platform.register("datasets", DatasetAccess.class,
                DatasetAccess.over("own", id -> Optional.ofNullable(DATASETS.get(id)), () -> root, () -> null));
        return platform.grant(Set.of("datasets"));
    }

    private static void parquet(Connection conn, Path file, int id) throws Exception {
        Files.createDirectories(file.getParent());
        try (Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT " + id + " AS id) TO '" + file.toString().replace("\\", "/") + "' (FORMAT PARQUET)");
        }
    }

    /** A snapshot publish as the real writers do it: hide the old file, reveal the new one under the write lock, then delete. */
    private static void swap(Connection conn, Path store, String old, String fresh, int id) throws Exception {
        Path staged = store.resolve(fresh + ".staged");
        parquet(conn, staged, id);
        try (SnapshotSwapLock.Held w = SnapshotSwapLock.write(store)) {
            Files.move(store.resolve(old), store.resolve(old + ".stale"));
            Files.move(staged, store.resolve(fresh));
        }
        Files.delete(store.resolve(old + ".stale"));
    }

    private static String ids(Connection conn, DatasetAccess.Read r) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT string_agg(CAST(id AS VARCHAR), ',' ORDER BY id) FROM (" + r.relationSql() + ")")) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    private static List<Path> runDirs(Path root) throws Exception {
        Path base = root.resolve(RunSnapshot.DIR);
        if (!Files.isDirectory(base)) return List.of();
        try (Stream<Path> s = Files.list(base)) {
            return s.toList();
        }
    }

    @Test
    void twoReadsInOneRunSeeTheSameFilesEvenWhenASwapLandsBetweenThem(@TempDir Path root) throws Exception {
        Path store = root.resolve("cdr");
        try (Connection conn = JdbcDrivers.connect("jdbc:duckdb:");
             RunSnapshot run = RunSnapshot.open(granted(root))) {
            parquet(conn, store.resolve("old.parquet"), 1);
            DatasetAccess datasets = run.services().get(DatasetAccess.class);

            DatasetAccess.Read first = datasets.read("own", "cdr");
            assertEquals(List.of("cdr/old.parquet"), first.files().orElseThrow());
            assertEquals("1", ids(conn, first));

            swap(conn, store, "old.parquet", "fresh.parquet", 2);
            assertFalse(Files.exists(store.resolve("old.parquet")), "precondition: the swap removed the pinned file");
            assertEquals(List.of("cdr/fresh.parquet"),
                    granted(root).get(DatasetAccess.class).read("own", "cdr").files().orElseThrow(),
                    "precondition: a per-call read sees the new snapshot");

            DatasetAccess.Read second = datasets.read("own", "cdr");
            assertEquals(first.files(), second.files(), "the second read of the Run sees the first read's files");
            assertEquals(first.relationSql(), second.relationSql());
            assertEquals("1", ids(conn, second), "and still reads their bytes, though the store no longer has them");
        }
    }

    @Test
    void aNewRunSeesTheNewFilesWhileTheOlderRunKeepsItsOwn(@TempDir Path root) throws Exception {
        Path store = root.resolve("cdr");
        try (Connection conn = JdbcDrivers.connect("jdbc:duckdb:");
             RunSnapshot older = RunSnapshot.open(granted(root))) {
            parquet(conn, store.resolve("old.parquet"), 1);
            DatasetAccess.Read before = older.services().get(DatasetAccess.class).read("own", "cdr");

            swap(conn, store, "old.parquet", "fresh.parquet", 2);

            try (RunSnapshot newer = RunSnapshot.open(granted(root))) {
                DatasetAccess.Read after = newer.services().get(DatasetAccess.class).read("own", "cdr");
                assertEquals(List.of("cdr/fresh.parquet"), after.files().orElseThrow());
                assertEquals("2", ids(conn, after));
            }
            assertEquals("1", ids(conn, older.services().get(DatasetAccess.class).read("own", "cdr")));
            assertEquals(List.of("cdr/old.parquet"), before.files().orElseThrow());
        }
    }

    @Test
    void aRunHoldingAPinNeverHoldsTheSwapLockSoAPublishDoesNotWaitForIt(@TempDir Path root) throws Exception {
        Path store = root.resolve("cdr");
        try (Connection conn = JdbcDrivers.connect("jdbc:duckdb:");
             RunSnapshot run = RunSnapshot.open(granted(root))) {
            parquet(conn, store.resolve("old.parquet"), 1);
            run.services().get(DatasetAccess.class).read("own", "cdr");   // the Run is live and has pinned

            // the publish runs on another thread, as a writer Job does; it must finish while the Run is still open
            CompletableFuture<Void> publish = CompletableFuture.runAsync(() -> {
                try (Connection writer = JdbcDrivers.connect("jdbc:duckdb:")) {
                    swap(writer, store, "old.parquet", "fresh.parquet", 2);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            publish.get(10, TimeUnit.SECONDS);
            assertFalse(SnapshotSwapLock.hasQueuedThreads(store));
            assertEquals("1", ids(conn, run.services().get(DatasetAccess.class).read("own", "cdr")));
        }
    }

    @Test
    void closingTheRunDeletesItsLinksAndRefusesLaterReads(@TempDir Path root) throws Exception {
        try (Connection conn = JdbcDrivers.connect("jdbc:duckdb:")) {
            parquet(conn, root.resolve("cdr").resolve("old.parquet"), 1);
        }
        RunSnapshot run = RunSnapshot.open(granted(root));
        DatasetAccess datasets = run.services().get(DatasetAccess.class);
        datasets.read("own", "cdr");
        assertEquals(1, runDirs(root).size(), "precondition: the pin linked its files into the Run's directory");

        run.close();
        run.close();   // idempotent

        assertEquals(List.of(), runDirs(root), "the Run's links are gone");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> datasets.read("own", "cdr"));
        assertTrue(e.getMessage().contains("the Run has ended"), e.getMessage());
    }

    @Test
    void theNextRunDeletesADirectoryACrashLeftBehindButNeverALiveRuns(@TempDir Path root) throws Exception {
        try (Connection conn = JdbcDrivers.connect("jdbc:duckdb:")) {
            parquet(conn, root.resolve("cdr").resolve("old.parquet"), 1);
        }
        Path crashed = root.resolve(RunSnapshot.DIR).resolve("left-by-a-crash");
        Files.createDirectories(crashed.resolve("0"));
        Files.writeString(crashed.resolve("0").resolve("0.parquet"), "x");
        try (RunSnapshot live = RunSnapshot.open(granted(root))) {
            live.services().get(DatasetAccess.class).read("own", "cdr");
            Path liveDir = runDirs(root).stream().filter(d -> !d.equals(crashed)).findFirst().orElseThrow();
            assertFalse(Files.exists(crashed), "the leftover is swept at the next pin");

            try (RunSnapshot next = RunSnapshot.open(granted(root))) {
                next.services().get(DatasetAccess.class).read("own", "cdr");
                assertTrue(Files.isDirectory(liveDir), "a live Run's directory is never swept");
                assertEquals(2, runDirs(root).size());
            }
        }
        assertEquals(List.of(), runDirs(root));
    }

    @Test
    void thePinnedRelationReadsWhatThePerCallRelationReadsCalculatedAndVirtualIncluded(@TempDir Path root) throws Exception {
        Path store = root.resolve("cdr");
        try (Connection conn = JdbcDrivers.connect("jdbc:duckdb:");
             RunSnapshot run = RunSnapshot.open(granted(root))) {
            parquet(conn, store.resolve("a.parquet"), 1);
            parquet(conn, store.resolve("b.parquet"), 3);
            DatasetAccess perCall = granted(root).get(DatasetAccess.class);
            DatasetAccess perRun = run.services().get(DatasetAccess.class);
            for (String id : List.of("cdr", "cdr_calc", "cdr_big")) {
                DatasetAccess.Read a = perCall.read("own", id), b = perRun.read("own", id);
                assertEquals(a.files(), b.files(), id);
                assertEquals(ids(conn, a), ids(conn, b), id);
                assertTrue(b.relationSql().contains(RunSnapshot.DIR), id + " reads the Run's links: " + b.relationSql());
            }
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT sum(twice) FROM (" + perRun.read("own", "cdr_calc").relationSql() + ")")) {
                assertTrue(rs.next());
                assertEquals(8, rs.getLong(1), "the calculated column is applied over the pinned files");
            }
        }
    }

    @Test
    void aForeignSpaceOrUnknownDatasetIsStillRefusedAfterTheFirstPin(@TempDir Path root) throws Exception {
        try (Connection conn = JdbcDrivers.connect("jdbc:duckdb:")) {
            parquet(conn, root.resolve("cdr").resolve("old.parquet"), 1);
        }
        try (RunSnapshot run = RunSnapshot.open(granted(root))) {
            DatasetAccess datasets = run.services().get(DatasetAccess.class);
            datasets.read("own", "cdr");
            assertTrue(assertThrows(IllegalStateException.class, () -> datasets.read("other", "cdr"))
                    .getMessage().contains("'other'"));
            assertTrue(assertThrows(IllegalStateException.class, () -> datasets.read("own", "nope"))
                    .getMessage().contains("'nope'"));
        }
    }
}
