package com.gamma.query;

import com.gamma.job.PlatformServiceRegistry;
import com.gamma.job.PlatformServices;
import com.gamma.job.SnapshotSwapLock;
import com.gamma.util.JdbcDrivers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
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
            "cdr_big", Map.of("sql", "SELECT id FROM cdr WHERE id > 0", "sourceName", "cdr"),
            "events", Map.of("physicalRef", "events"));

    @AfterEach
    void resetSeams() {
        RunSnapshot.hardLinks = true;
        RunSnapshot.copyCap = 1L << 30;
        RunSnapshot.copyFileCap = 256;
        RunSnapshot.beforeCopy = () -> { };
    }

    /** Older than the sweep's age floor, so only its lock can keep it. */
    private static void age(Path dir) throws Exception {
        Files.setLastModifiedTime(dir, FileTime.from(Instant.now().minus(java.time.Duration.ofHours(1))));
    }

    /** Every column and row of a relation, in one string. */
    private static String table(Connection conn, String relation) throws Exception {
        StringBuilder out = new StringBuilder();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM (" + relation + ") ORDER BY ALL")) {
            for (int c = 1; c <= rs.getMetaData().getColumnCount(); c++) out.append(rs.getMetaData().getColumnLabel(c)).append(',');
            while (rs.next()) {
                out.append('|');
                for (int c = 1; c <= rs.getMetaData().getColumnCount(); c++) out.append(rs.getString(c)).append(',');
            }
        }
        return out.toString();
    }

    /** The files of the Run directories under {@code root}, the lock files left out. */
    private static List<Path> links(Path root) throws Exception {
        try (Stream<Path> s = Files.walk(root.resolve(RunSnapshot.DIR))) {
            return s.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().equals(RunSnapshot.LOCK)).toList();
        }
    }

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
        age(crashed);
        Path young = root.resolve(RunSnapshot.DIR).resolve("not-locked-yet");
        Files.createDirectories(young);
        try (RunSnapshot live = RunSnapshot.open(granted(root))) {
            live.services().get(DatasetAccess.class).read("own", "cdr");
            Path liveDir = runDirs(root).stream().filter(d -> !d.equals(crashed) && !d.equals(young)).findFirst().orElseThrow();
            assertFalse(Files.exists(crashed), "the leftover is swept at the next pin");
            assertTrue(Files.isDirectory(young), "a directory younger than the age floor is left for its owner to lock");
            age(liveDir);

            try (RunSnapshot next = RunSnapshot.open(granted(root))) {
                next.services().get(DatasetAccess.class).read("own", "cdr");
                assertTrue(Files.isDirectory(liveDir), "a live Run's directory is never swept: its lock is held");
                assertEquals(3, runDirs(root).size());
            }
        }
        assertEquals(List.of(young), runDirs(root));
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

    @Test
    void aHivePartitionedStoreReadsTheSameColumnsPinnedAndAnOldPartitionAfterASwap(@TempDir Path root) throws Exception {
        Path day = root.resolve("events").resolve("day=2026-11-01");
        try (Connection conn = JdbcDrivers.connect("jdbc:duckdb:");
             RunSnapshot run = RunSnapshot.open(granted(root))) {
            parquet(conn, day.resolve("a.parquet"), 1);
            parquet(conn, root.resolve("events").resolve("day=2026-11-02").resolve("b.parquet"), 2);
            DatasetAccess.Read perCall = granted(root).get(DatasetAccess.class).read("own", "events");
            DatasetAccess.Read pinned = run.services().get(DatasetAccess.class).read("own", "events");
            String expected = table(conn, perCall.relationSql());
            assertTrue(expected.startsWith("id,day,"), "precondition: the store reads its partition column: " + expected);
            assertEquals(expected, table(conn, pinned.relationSql()), "the same columns and rows, pinned");
            assertEquals(perCall.files(), pinned.files());

            swap(conn, day, "a.parquet", "c.parquet", 3);
            assertEquals(expected, table(conn, run.services().get(DatasetAccess.class).read("own", "events").relationSql()),
                    "the swapped-away partition file is still read, with its day");
        }
    }

    @Test
    void aPinHardLinksTheStoreFilesSoNothingIsCopied(@TempDir Path root) throws Exception {
        Path file = root.resolve("cdr").resolve("old.parquet");
        try (Connection conn = JdbcDrivers.connect("jdbc:duckdb:");
             RunSnapshot run = RunSnapshot.open(granted(root))) {
            parquet(conn, file, 1);
            run.services().get(DatasetAccess.class).read("own", "cdr");
            List<Path> links = links(root);
            assertEquals(1, links.size(), links.toString());
            assertTrue(Files.isSameFile(links.get(0), file), "a hard link to the store's file, not a copy");
        }
    }

    @Test
    void whereHardLinksFailTheFilesAreCopiedOutsideTheLockFromHandlesOpenedUnderIt(@TempDir Path root) throws Exception {
        Path store = root.resolve("cdr");
        RunSnapshot.hardLinks = false;
        try (Connection conn = JdbcDrivers.connect("jdbc:duckdb:");
             RunSnapshot run = RunSnapshot.open(granted(root))) {
            parquet(conn, store.resolve("old.parquet"), 1);
            // a publish lands after the lock is released and before the copy: it must not wait for the copy
            RunSnapshot.beforeCopy = () -> {
                try {
                    CompletableFuture.runAsync(() -> {
                        try (Connection writer = JdbcDrivers.connect("jdbc:duckdb:")) {
                            swap(writer, store, "old.parquet", "fresh.parquet", 2);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }).get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("the publish waited for the copy", e);
                }
            };
            DatasetAccess.Read first = run.services().get(DatasetAccess.class).read("own", "cdr");
            assertFalse(Files.exists(store.resolve("old.parquet")), "precondition: the publish ran before the copy");
            assertEquals(List.of("cdr/old.parquet"), first.files().orElseThrow());
            assertEquals("1", ids(conn, first), "the copy holds the pinned bytes, read through the handle opened under the lock");
            assertFalse(Files.isSameFile(links(root).get(0), store.resolve("fresh.parquet")));
            assertEquals("1", ids(conn, run.services().get(DatasetAccess.class).read("own", "cdr")));
        }
    }

    @Test
    void aCopyOverTheCapIsNotMadeAndTheDatasetIsReadPerCall(@TempDir Path root) throws Exception {
        Path store = root.resolve("cdr");
        RunSnapshot.hardLinks = false;
        RunSnapshot.copyCap = 1;
        try (Connection conn = JdbcDrivers.connect("jdbc:duckdb:");
             RunSnapshot run = RunSnapshot.open(granted(root))) {
            parquet(conn, store.resolve("old.parquet"), 1);
            DatasetAccess datasets = run.services().get(DatasetAccess.class);
            DatasetAccess.Read first = datasets.read("own", "cdr");
            assertFalse(first.relationSql().contains(RunSnapshot.DIR), "read per call: " + first.relationSql());
            assertEquals(List.of(), links(root), "the partial pin is removed");
            swap(conn, store, "old.parquet", "fresh.parquet", 2);
            assertEquals("2", ids(conn, datasets.read("own", "cdr")), "per call, as before Stage 2");
        }
    }

    @Test
    void aCopyOfMoreFilesThanTheOpenFileCapIsNotMadeAndTheDatasetIsReadPerCall(@TempDir Path root) throws Exception {
        Path store = root.resolve("cdr");
        RunSnapshot.hardLinks = false;
        RunSnapshot.copyFileCap = 1;
        try (Connection conn = JdbcDrivers.connect("jdbc:duckdb:");
             RunSnapshot run = RunSnapshot.open(granted(root))) {
            parquet(conn, store.resolve("a.parquet"), 1);
            parquet(conn, store.resolve("b.parquet"), 2);
            DatasetAccess datasets = run.services().get(DatasetAccess.class);
            DatasetAccess.Read first = datasets.read("own", "cdr");
            assertFalse(first.relationSql().contains(RunSnapshot.DIR), "read per call: " + first.relationSql());
            assertEquals("1,2", ids(conn, first));
            Path runDir = runDirs(root).get(0);
            try (Stream<Path> s = Files.list(runDir)) {
                assertEquals(List.of(runDir.resolve(RunSnapshot.LOCK)), s.toList(), "nothing of the pin is left");
            }
        }
    }

    /**
     * On Linux, closing any channel to a file releases every lock this process holds on it: a sweep that probed a live
     * Run's lock file from the same JVM would unlock that Run for every other process. (Windows locks belong to the
     * handle, so this passes there without the guard too.)
     */
    @Test
    void aSweepInThisProcessLeavesALiveRunLockedAgainstOtherProcesses(@TempDir Path root, @TempDir Path src) throws Exception {
        try (Connection conn = JdbcDrivers.connect("jdbc:duckdb:")) {
            parquet(conn, root.resolve("cdr").resolve("old.parquet"), 1);
        }
        Path probe = src.resolve("Probe.java");
        Files.writeString(probe, String.join("\n",
                "public class Probe {",
                "    public static void main(String[] a) throws Exception {",
                "        try (var ch = java.nio.channels.FileChannel.open(java.nio.file.Path.of(a[0]),",
                "                java.nio.file.StandardOpenOption.WRITE)) {",
                "            var lock = ch.tryLock();",
                "            System.out.println(lock == null ? \"REFUSED\" : \"ACQUIRED\");",
                "        } catch (java.io.IOException e) {",
                "            System.out.println(\"REFUSED\");",
                "        }",
                "    }",
                "}"));
        try (RunSnapshot live = RunSnapshot.open(granted(root))) {
            live.services().get(DatasetAccess.class).read("own", "cdr");
            Path liveDir = runDirs(root).get(0);
            age(liveDir);   // past the age floor, so only the guard keeps the sweep off it
            try (RunSnapshot next = RunSnapshot.open(granted(root))) {
                next.services().get(DatasetAccess.class).read("own", "cdr");   // sweeps the data root
                assertTrue(Files.isDirectory(liveDir));
            }
            Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    probe.toString(), liveDir.resolve(RunSnapshot.LOCK).toString()).redirectErrorStream(true).start();
            String answer = new BufferedReader(new InputStreamReader(p.getInputStream())).readLine();
            assertTrue(p.waitFor(30, TimeUnit.SECONDS));
            assertEquals("REFUSED", answer, "another process may not take a live Run's lock after a sweep in this one");
        }
    }

    @Test
    void aFailedPinLeavesNoPartialFolderAndTheNextReadPinsCleanly(@TempDir Path root) throws Exception {
        RunSnapshot.hardLinks = false;
        RunSnapshot.beforeCopy = () -> { throw new IllegalStateException("disk full"); };
        try (Connection conn = JdbcDrivers.connect("jdbc:duckdb:");
             RunSnapshot run = RunSnapshot.open(granted(root))) {
            parquet(conn, root.resolve("cdr").resolve("old.parquet"), 1);
            DatasetAccess datasets = run.services().get(DatasetAccess.class);
            assertThrows(IllegalStateException.class, () -> datasets.read("own", "cdr"));
            Path runDir = runDirs(root).get(0);
            try (Stream<Path> s = Files.list(runDir)) {
                assertEquals(List.of(runDir.resolve(RunSnapshot.LOCK)), s.toList(), "the failed pin's folder is gone");
            }

            RunSnapshot.beforeCopy = () -> { };
            assertEquals("1", ids(conn, datasets.read("own", "cdr")));
            assertEquals(1, links(root).size());
        }
    }

    @Test
    void aRunDirectoryLockedByAnotherProcessIsNeverSwept(@TempDir Path root, @TempDir Path src) throws Exception {
        try (Connection conn = JdbcDrivers.connect("jdbc:duckdb:")) {
            parquet(conn, root.resolve("cdr").resolve("old.parquet"), 1);
        }
        Path other = root.resolve(RunSnapshot.DIR).resolve("other-process-run");
        Files.createDirectories(other.resolve("0"));
        Files.writeString(other.resolve("0").resolve("0.parquet"), "x");
        Path holder = src.resolve("Holder.java");
        Files.writeString(holder, String.join("\n",
                "public class Holder {",
                "    public static void main(String[] a) throws Exception {",
                "        try (var ch = java.nio.channels.FileChannel.open(java.nio.file.Path.of(a[0]),",
                "                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);",
                "             var lock = ch.lock()) {",
                "            System.out.println(\"locked\");",
                "            System.out.flush();",
                "            System.in.read();",
                "        }",
                "    }",
                "}"));
        Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                holder.toString(), other.resolve(RunSnapshot.LOCK).toString()).redirectErrorStream(true).start();
        try {
            String line = new BufferedReader(new InputStreamReader(p.getInputStream())).readLine();
            assertEquals("locked", line, "precondition: another JVM holds the Run directory's lock");
            age(other);
            try (RunSnapshot run = RunSnapshot.open(granted(root))) {
                run.services().get(DatasetAccess.class).read("own", "cdr");
                assertTrue(Files.isDirectory(other), "another process's live Run is not swept");
            }
        } finally {
            p.destroy();
            assertTrue(p.waitFor(20, TimeUnit.SECONDS));
        }
        age(other);
        try (RunSnapshot run = RunSnapshot.open(granted(root))) {
            run.services().get(DatasetAccess.class).read("own", "cdr");
            assertFalse(Files.exists(other), "once its process is gone, the directory is swept");
        }
    }
}
