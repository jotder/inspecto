package com.gamma.la.storage;

import com.gamma.sql.SqlSandboxPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** D7-2 - pinned index versions survive gc and pool eviction; pins expire after 30 days; an unreadable pin file fails closed. */
class IndexPinsTest {

    /** A clock the test moves; every mtime is set explicitly, so nothing sleeps. */
    private static final class TestClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
        void plusDays(long d) { now.updateAndGet(i -> i.plus(Duration.ofDays(d))); }
        void plus(Duration d) { now.updateAndGet(i -> i.plus(d)); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }

    private static Path publishOne(IndexStore s, TestClock clock) throws Exception {
        Path st = s.stage(Duration.ofHours(1));
        Files.writeString(st.resolve("edges.parquet"), "data-" + st.getFileName());
        Path v = s.publish(st);
        Files.setLastModifiedTime(v, FileTime.from(clock.instant().minus(Duration.ofDays(2))));
        return v;
    }

    private static boolean exists(IndexStore s, int n) {
        return Files.isDirectory(s.directory().resolve(String.format("v%06d", n)));
    }

    @Test
    void aPinnedVersionSurvivesGcAndAnUnpinnedOldOneIsDeleted(@TempDir Path root) throws Exception {
        TestClock clock = new TestClock();
        IndexStore s = new IndexStore(root, "ds", "abc", 1, clock);
        publishOne(s, clock);
        publishOne(s, clock);
        publishOne(s, clock);
        s.pins().pin(1, "draft-1");
        List<String> gone = s.gc(Duration.ZERO);
        assertEquals(List.of("v000002"), gone);
        assertTrue(exists(s, 1), "pinned v1 kept despite keepVersions=1 and minAge=0");
        assertFalse(exists(s, 2));
        assertTrue(exists(s, 3));
    }

    @Test
    void anExpiredPinNoLongerProtectsAtDay30(@TempDir Path root) throws Exception {
        TestClock clock = new TestClock();
        IndexStore s = new IndexStore(root, "ds", "abc", 1, clock);
        publishOne(s, clock);
        publishOne(s, clock);
        IndexPins.Pin pin = s.pins().pin(1, "draft-1");
        assertEquals(clock.instant().plus(Duration.ofDays(30)), pin.expiresAt());
        // exactly at expiry the pin is released (expiresAt is exclusive)
        clock.plus(Duration.ofDays(30).minusSeconds(1));
        assertTrue(s.pins().pinnedVersions(clock.instant()).contains(1L), "one second before expiry it still protects");
        assertEquals(List.of(), s.gc(Duration.ZERO));
        assertTrue(exists(s, 1));
        clock.plus(Duration.ofSeconds(1));
        assertTrue(s.pins().pinnedVersions(clock.instant()).isEmpty());
        assertEquals(List.of("v000001"), s.gc(Duration.ZERO));
        assertFalse(exists(s, 1));
        assertEquals(1, s.pins().expire(clock.instant()).size());
        assertTrue(s.pins().listPins().isEmpty());
    }

    @Test
    void pinningAMissingVersionOrAStageIsRefused(@TempDir Path root) throws Exception {
        TestClock clock = new TestClock();
        IndexStore s = new IndexStore(root, "ds", "abc", 2, clock);
        publishOne(s, clock);
        Path stage = s.stage(Duration.ofHours(1));                 // v000002.tmp, not yet published
        assertThrows(IllegalArgumentException.class, () -> s.pins().pin(9, "d"));
        assertThrows(IllegalArgumentException.class, () -> s.pins().pin(2, "d"), "a stage is not a version");
        assertTrue(s.pins().listPins().isEmpty());
        assertTrue(Files.isDirectory(stage));
        // an index with no directory at all
        assertThrows(IllegalArgumentException.class, () -> new IndexStore(root, "nothing", "abc", 2, clock).pins().pin(1, "d"));
    }

    @Test
    void rePinningMovesThePinAndUnpinReleasesIt(@TempDir Path root) throws Exception {
        TestClock clock = new TestClock();
        IndexStore s = new IndexStore(root, "ds", "abc", 1, clock);
        publishOne(s, clock);
        publishOne(s, clock);
        publishOne(s, clock);
        s.pins().pin(1, "d");
        s.pins().pin(2, "d");                                      // same id: moves
        assertEquals(1, s.pins().listPins().size());
        assertEquals(Set.of(2L), s.pins().pinnedVersions(clock.instant()));
        assertEquals(List.of("v000001"), s.gc(Duration.ZERO));
        assertTrue(s.pins().unpin("d"));
        assertFalse(s.pins().unpin("d"));
        assertEquals(List.of("v000002"), s.gc(Duration.ZERO));
    }

    @Test
    void aPinDoesNotStopANewerPublish(@TempDir Path root) throws Exception {
        TestClock clock = new TestClock();
        IndexStore s = new IndexStore(root, "ds", "abc", 1, clock);
        publishOne(s, clock);
        s.pins().pin(1, "d");
        Path v2 = publishOne(s, clock);
        assertEquals(v2, s.current().orElseThrow());
        publishOne(s, clock);
        s.gc(Duration.ZERO);
        assertTrue(exists(s, 1));
        assertEquals("v000003", s.current().orElseThrow().getFileName().toString());
    }

    @Test
    void pinsSurviveANewStoreInstance(@TempDir Path root) throws Exception {
        TestClock clock = new TestClock();
        IndexStore s = new IndexStore(root, "ds", "abc", 1, clock);
        publishOne(s, clock);
        publishOne(s, clock);
        publishOne(s, clock);
        s.pins().pin(1, "d");
        IndexStore restarted = new IndexStore(root, "ds", "abc", 1, clock);
        assertEquals(Set.of(1L), restarted.pins().pinnedVersions(clock.instant()));
        restarted.gc(Duration.ZERO);
        assertTrue(exists(restarted, 1));
        assertTrue(Files.isRegularFile(s.directory().resolve(IndexPins.FILE_NAME)));
    }

    @Test
    void expiresSoonListsOnlyPinsInsideTheWarnWindow(@TempDir Path root) throws Exception {
        TestClock clock = new TestClock();
        IndexStore s = new IndexStore(root, "ds", "abc", 1, clock);
        publishOne(s, clock);
        s.pins().pin(1, "old");                                    // expires day 30
        clock.plusDays(10);
        s.pins().pin(1, "young");                                  // expires day 40
        clock.plusDays(12);                                        // day 22: old has 8 days left, young 18
        assertEquals(List.of(), s.pins().expiresSoon(clock.instant()));
        clock.plusDays(1);                                         // day 23: old has 7 days left = inside the window
        assertEquals(List.of("old"), s.pins().expiresSoon(clock.instant()).stream().map(IndexPins.Pin::pinId).toList());
        clock.plusDays(7);                                         // day 30: old expired, no longer "soon" (it is gone)
        assertEquals(List.of(), s.pins().expiresSoon(clock.instant()).stream().map(IndexPins.Pin::pinId).filter("old"::equals).toList());
        assertEquals(List.of("young"), s.pins().expiresSoon(clock.instant(), 11).stream().map(IndexPins.Pin::pinId).toList());
        assertEquals(30, IndexPins.PIN_TTL_DAYS);
        assertEquals(7, IndexPins.PIN_WARN_DAYS);
    }

    @Test
    void anUnreadablePinFileFailsClosedAndGcDeletesNothing(@TempDir Path root) throws Exception {
        TestClock clock = new TestClock();
        IndexStore s = new IndexStore(root, "ds", "abc", 1, clock);
        publishOne(s, clock);
        publishOne(s, clock);
        publishOne(s, clock);
        s.pins().pin(1, "d");
        Path file = s.directory().resolve(IndexPins.FILE_NAME);
        String whole = Files.readString(file);
        Files.writeString(file, whole.substring(0, whole.length() / 2));       // truncated
        assertThrows(java.io.IOException.class, () -> s.gc(Duration.ZERO));
        assertTrue(exists(s, 1) && exists(s, 2) && exists(s, 3), "nothing deleted when the pin set cannot be read");
        Files.writeString(file, "not json at all");
        assertThrows(java.io.IOException.class, () -> s.gc(Duration.ZERO));
        assertTrue(exists(s, 2));
        assertThrows(java.io.IOException.class, () -> s.pins().pin(2, "e"), "pin refuses to overwrite a file it cannot read");
        assertEquals("not json at all", Files.readString(file));
    }

    @Test
    void concurrentPinAndGcNeverLoseAPinnedVersion(@TempDir Path root) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            int pinnedOk = 0;
            for (int i = 0; i < 25; i++) {
                TestClock clock = new TestClock();
                IndexStore s = new IndexStore(root.resolve("r" + i), "ds", "abc", 1, clock);
                publishOne(s, clock);
                publishOne(s, clock);
                CountDownLatch go = new CountDownLatch(1);
                Future<Boolean> pin = pool.submit(() -> {
                    go.await();
                    try {
                        s.pins().pin(1, "d");
                        return true;
                    } catch (IllegalArgumentException gone) {
                        return false;                              // gc won: the version was already deleted
                    }
                });
                Future<List<String>> gc = pool.submit(() -> { go.await(); return s.gc(Duration.ZERO); });
                go.countDown();
                boolean pinned = pin.get(30, TimeUnit.SECONDS);
                gc.get(30, TimeUnit.SECONDS);
                if (pinned) {
                    pinnedOk++;
                    assertTrue(exists(s, 1), "iteration " + i + ": a pin that was granted must not be deleted by the racing gc");
                } else {
                    assertFalse(exists(s, 1));
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------------------------------- real builds

    private static final String DS = "ds";
    private static final IndexMapping MAPPING = new IndexMapping("s", "t", "k", "ts", null, "w", List.of("cell"));

    private static void plant(Path dir) throws Exception {
        Files.createDirectories(dir);
        Random rnd = new Random(7);
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("SET TimeZone = 'UTC'");
            st.execute("CREATE TABLE e (n INTEGER, s VARCHAR, t VARCHAR, k VARCHAR, ts TIMESTAMP, w DOUBLE, cell VARCHAR)");
            try (PreparedStatement ins = c.prepareStatement("INSERT INTO e VALUES (?, ?, ?, ?, CAST(? AS TIMESTAMP), ?, ?)")) {
                for (int i = 0; i < 5 * 60; i++) {
                    ins.setInt(1, i);
                    ins.setString(2, "N" + rnd.nextInt(30));
                    ins.setString(3, "N" + rnd.nextInt(30));
                    ins.setString(4, rnd.nextBoolean() ? "call" : "sms");
                    ins.setString(5, "2026-01-" + String.format("%02d %02d:00:00", 1 + rnd.nextInt(28), rnd.nextInt(24)));
                    ins.setDouble(6, rnd.nextInt(100));
                    ins.setString(7, "c" + rnd.nextInt(5));
                    ins.addBatch();
                }
                ins.executeBatch();
            }
            for (int k = 0; k < 5; k++)
                st.execute("COPY (SELECT s, t, k, ts, w, cell FROM e WHERE n >= " + k * 60 + " AND n < " + (k + 1) * 60 + " ORDER BY n) TO '"
                        + sql(dir.resolve("f" + k + ".parquet")) + "' (FORMAT parquet)");
        }
    }

    private static String sql(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/').replace("'", "''");
    }

    private static List<String> names(int n) {
        List<String> out = new ArrayList<>();
        for (int k = 0; k < n; k++) out.add("f" + k + ".parquet");
        return out;
    }

    private static String relation(Path dir, List<String> files) {
        StringBuilder sb = new StringBuilder("SELECT * FROM read_parquet([");
        for (int i = 0; i < files.size(); i++) sb.append(i == 0 ? "'" : ", '").append(sql(dir.resolve(files.get(i)))).append('\'');
        return sb.append("])").toString();
    }

    private static IndexBuilder.Result build(Path root, Path data, IndexBuilder.Mode mode, int files) throws Exception {
        List<String> names = names(files);
        List<IndexManifest.InputFile> stamps = new ArrayList<>();
        for (String f : names)
            stamps.add(new IndexManifest.InputFile(f, Files.size(data.resolve(f)), Files.getLastModifiedTime(data.resolve(f)).toMillis()));
        return IndexBuilder.build(new IndexBuilder.Request(DS, MAPPING, relation(data, names), new IndexStore(root, DS, MAPPING.hash()),
                "fp-" + files, null, stamps, mode, added -> relation(data, added), List.of(data)));
    }

    @Test
    void aPinnedAppendVersionStaysWholeAfterItsParentIsCollectedAndAfterManyPublishes(@TempDir Path tmp) throws Exception {
        Path data = tmp.resolve("data");
        plant(data);
        Path root = tmp.resolve("idx");
        IndexBuilder.Result v1 = build(root, data, IndexBuilder.Mode.FULL, 3);
        IndexBuilder.Result v2 = build(root, data, IndexBuilder.Mode.APPEND, 4);       // hard-links v1's files, adds a delta
        assertEquals("v000001", v2.manifest().parent());
        IndexStore store = new IndexStore(root, DS, MAPPING.hash(), 1, Clock.systemUTC());
        store.pins().pin(v2.version(), "draft-a");
        for (int i = 0; i < 4; i++) {                                                   // many publishes, a gc after each
            build(root, data, IndexBuilder.Mode.FULL, 3 + i % 2);
            store.gc(Duration.ZERO);
        }
        assertFalse(Files.exists(v1.directory()), "the parent version was collected");
        assertTrue(Files.isDirectory(v2.directory()), "the pinned append version was not");
        IndexManifest verified = IndexBuilder.verify(v2.directory());                  // every table matches its manifest
        assertEquals(v2.manifest().tables().get("out").rows(), verified.tables().get("out").rows());
        try (IndexReader r = IndexReader.open(v2.directory(), v2.manifest(), SqlSandboxPolicy.defaultPolicy())) {
            long edges = 0;
            for (int i = 0; i < 30; i++) edges += r.degree("N" + i);
            assertTrue(edges > 0, "the pinned version still answers reads");
        }
        store.pins().unpin("draft-a");
        store.gc(Duration.ZERO);
        assertFalse(Files.exists(v2.directory()), "released, it is collected by the normal rules");
    }

    @Test
    void borrowingCurrentDoesNotEvictAPinnedSiblingsIdleReader(@TempDir Path tmp) throws Exception {
        Path data = tmp.resolve("data");
        plant(data);
        Path root = tmp.resolve("idx");
        IndexBuilder.Result v1 = build(root, data, IndexBuilder.Mode.FULL, 3);
        IndexBuilder.Result v2 = build(root, data, IndexBuilder.Mode.FULL, 4);
        IndexReader.evictAll();
        try {
            // unpinned: borrowing v2 closes v1's idle reader (the pre-existing behaviour)
            try (IndexReader r = IndexReader.borrow(v1.directory(), v1.manifest(), SqlSandboxPolicy.defaultPolicy())) { assertNotNull(r); }
            assertEquals(1, IndexReader.idleCount(v1.directory()));
            try (IndexReader r = IndexReader.borrow(v2.directory(), v2.manifest(), SqlSandboxPolicy.defaultPolicy())) { assertNotNull(r); }
            assertEquals(0, IndexReader.idleCount(v1.directory()));
            // pinned: the same sequence leaves v1's idle reader alone
            new IndexStore(root, DS, MAPPING.hash()).pins().pin(v1.version(), "draft-a");
            try (IndexReader r = IndexReader.borrow(v1.directory(), v1.manifest(), SqlSandboxPolicy.defaultPolicy())) { assertNotNull(r); }
            long opens = IndexReader.OPENS.get();
            try (IndexReader r = IndexReader.borrow(v2.directory(), v2.manifest(), SqlSandboxPolicy.defaultPolicy())) { assertNotNull(r); }
            assertEquals(1, IndexReader.idleCount(v1.directory()), "the pinned sibling's idle reader survived a CURRENT borrow");
            assertEquals(1, IndexReader.idleCount(v2.directory()), "borrowing a pinned version did not evict CURRENT's idle reader either");
            try (IndexReader r = IndexReader.borrow(v1.directory(), v1.manifest(), SqlSandboxPolicy.defaultPolicy())) { assertNotNull(r); }
            assertEquals(opens, IndexReader.OPENS.get(), "the pinned reader was reused, not re-opened");
        } finally {
            IndexReader.evictAll();
        }
    }
}
