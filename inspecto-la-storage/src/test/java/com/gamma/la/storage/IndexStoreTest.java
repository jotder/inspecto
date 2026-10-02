package com.gamma.la.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class IndexStoreTest {

    private static Path stageWithFile(IndexStore s) throws Exception {
        Path st = s.stage(Duration.ofHours(1));
        Files.writeString(st.resolve("edges.parquet"), "data-" + st.getFileName());
        return st;
    }

    private static void age(Path p, Duration d) throws Exception {
        Files.setLastModifiedTime(p, FileTime.from(java.time.Instant.now().minus(d)));
    }

    @Test
    void concurrentStagesGetDistinctDirectories(@TempDir Path root) throws Exception {
        IndexStore s = new IndexStore(root, "ds", "abc");
        int n = 16;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        var start = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<Path>> out = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(pool.submit(() -> { start.await(); return s.stage(Duration.ofHours(1)); }));
        }
        start.countDown();
        java.util.Set<Path> distinct = new java.util.HashSet<>();
        for (var f : out) distinct.add(f.get(30, java.util.concurrent.TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(n, distinct.size());
    }

    @Test
    void emptyStoreHasNoCurrent(@TempDir Path root) {
        assertTrue(new IndexStore(root, "ds", "abc").current().isEmpty());
    }

    @Test
    void stagePublishResolvesCurrent(@TempDir Path root) throws Exception {
        IndexStore s = new IndexStore(root, "ds", "abc");
        Path st = stageWithFile(s);
        assertTrue(st.getFileName().toString().endsWith(".tmp"));
        Path v = s.publish(st);
        assertEquals("v000001", v.getFileName().toString());
        assertEquals(v, s.current().orElseThrow());
        assertFalse(Files.exists(st));
        assertEquals("v000002.tmp", stageWithFile(s).getFileName().toString());
    }

    @Test
    void concurrentReaderAlwaysSeesACompleteVersionAcrossManySwitches(@TempDir Path root) throws Exception {
        IndexStore s = new IndexStore(root, "ds", "abc");
        s.publish(stageWithFile(s));
        AtomicBoolean stop = new AtomicBoolean();
        AtomicReference<String> bad = new AtomicReference<>();
        AtomicInteger reads = new AtomicInteger();
        IndexStore reader = new IndexStore(root, "ds", "abc");
        Thread t = new Thread(() -> {
            while (!stop.get()) {
                var cur = reader.current();
                if (cur.isEmpty()) { bad.compareAndSet(null, "current() was empty after the first publish"); return; }
                try {
                    String body = Files.readString(cur.get().resolve("edges.parquet"));
                    if (!body.equals("data-" + cur.get().getFileName() + ".tmp")) { bad.compareAndSet(null, "partial/mismatched " + body); return; }
                } catch (Exception e) { bad.compareAndSet(null, "unreadable " + cur.get() + " " + e); return; }
                reads.incrementAndGet();
            }
        });
        t.start();
        for (int i = 0; i < 400; i++) s.publish(stageWithFile(s));
        stop.set(true);
        t.join(10_000);
        assertNull(bad.get());
        assertTrue(reads.get() > 0);
        assertEquals("v000401", s.current().orElseThrow().getFileName().toString());
    }

    @Test
    void gcKeepsNAndTheLiveVersion(@TempDir Path root) throws Exception {
        IndexStore s = new IndexStore(root, "ds", "abc", 2, Clock.systemUTC());
        for (int i = 0; i < 5; i++) { Path v = s.publish(stageWithFile(s)); age(v, Duration.ofDays(2)); }
        assertEquals(List.of("v000003", "v000002", "v000001").stream().sorted().toList(), s.gc(Duration.ofHours(1)).stream().sorted().toList());
        assertTrue(Files.isDirectory(s.directory().resolve("v000004")));
        assertTrue(Files.isDirectory(s.directory().resolve("v000005")));
        assertEquals("v000005", s.current().orElseThrow().getFileName().toString());
    }

    @Test
    void gcNeverDeletesAVersionYoungerThanTheMinimumAge(@TempDir Path root) throws Exception {
        IndexStore s = new IndexStore(root, "ds", "abc", 1, Clock.systemUTC());
        Path v1 = s.publish(stageWithFile(s));
        Path v2 = s.publish(stageWithFile(s));
        s.publish(stageWithFile(s));
        age(v1, Duration.ofDays(2));              // v1 old, v2 fresh
        assertEquals(List.of("v000001"), s.gc(Duration.ofHours(1)));
        assertTrue(Files.isDirectory(v2), "a fresh older version may still be held open by a reader");
        age(v2, Duration.ofDays(2));
        assertEquals(List.of("v000002"), s.gc(Duration.ofHours(1)));
    }

    @Test
    void aCrashedStageIsCleanedByTheNextStageAndByGc(@TempDir Path root) throws Exception {
        IndexStore s = new IndexStore(root, "ds", "abc");
        Path crashed = stageWithFile(s);          // never published
        age(crashed, Duration.ofDays(1));
        Path next = s.stage(Duration.ofHours(1));
        assertFalse(Files.exists(crashed.resolve("edges.parquet")), "next stage removes the stale stage (and its number is reused)");
        assertEquals("v000001.tmp", next.getFileName().toString());
        Path fresh = stageWithFile(s);
        age(fresh, Duration.ofDays(1));
        assertTrue(s.gc(Duration.ofHours(1)).contains(fresh.getFileName().toString()));
        assertFalse(Files.exists(fresh));
        Path young = stageWithFile(s);            // a live stage is untouched by gc
        s.gc(Duration.ofHours(1));
        assertTrue(Files.exists(young));
    }

    @Test
    void publishRefusesWhatIsNotAStageOfThisStore(@TempDir Path root) throws Exception {
        IndexStore s = new IndexStore(root, "ds", "abc");
        IndexStore other = new IndexStore(root, "ds", "zzz");
        Path foreign = other.stage();
        Path plain = Files.createDirectories(s.directory().resolve("v000009"));
        Path outside = Files.createDirectories(root.resolve("elsewhere").resolve("v000001.tmp"));
        assertThrows(IllegalArgumentException.class, () -> s.publish(foreign));
        assertThrows(IllegalArgumentException.class, () -> s.publish(plain));
        assertThrows(IllegalArgumentException.class, () -> s.publish(outside));
        assertThrows(IllegalArgumentException.class, () -> s.publish(s.directory().resolve("v000001.tmp")));   // does not exist
        assertTrue(s.current().isEmpty());
    }

    @Test
    void spacesAndNonAsciiDatasetIdsWorkAndUnsafeSegmentsAreRefused(@TempDir Path root) throws Exception {
        IndexStore s = new IndexStore(root.resolve("my root"), "Datensatz Größe 日本", "abc");
        Path v = s.publish(stageWithFile(s));
        assertEquals(v, new IndexStore(root.resolve("my root"), "Datensatz Größe 日本", "abc").current().orElseThrow());
        for (String bad : new String[] {"", ".", "..", "a/b", "a\\b", null})
            assertThrows(IllegalArgumentException.class, () -> new IndexStore(root, bad, "abc"), String.valueOf(bad));
        assertThrows(IllegalArgumentException.class, () -> new IndexStore(root, "ds", "../x"));
    }

    @Test
    void aMappingChangeIsADifferentDirectory(@TempDir Path root) throws Exception {
        IndexStore a = new IndexStore(root, "ds", "aaaa");
        IndexStore b = new IndexStore(root, "ds", "bbbb");
        a.publish(stageWithFile(a));
        assertTrue(b.current().isEmpty());
    }

    // ---- verifier hardening (D-3 step 3) -------------------------------------------------------------------------

    @Test
    void segmentRefusesColonWindowsDeviceNamesAndTrailingDotsOrSpaces(@TempDir Path root) {
        for (String bad : new String[] {"a:b", "C:", "CON", "con", "Con", "PRN", "aux", "NUL", "nul.txt", "COM1", "com9", "LPT1", "lpt9.log",
                "ds.", "ds ", "ds..", "a<b", "a>b", "a\"b", "a|b", "a?b", "a*b", "a\u0001b", "a\tb"})
            assertThrows(IllegalArgumentException.class, () -> new IndexStore(root, bad, "abc"), "dataset id '" + bad + "'");
        assertThrows(IllegalArgumentException.class, () -> new IndexStore(root, "ds", "a:b"));
        assertThrows(IllegalArgumentException.class, () -> new IndexStore(root, "ds", "NUL"));
        for (String ok : new String[] {"COM", "COM10", "CONSOLE", "LPT", "a.b", "a b", "con-tacts"})
            assertDoesNotThrow(() -> new IndexStore(root, ok, "abc"), ok);
    }

    @Test
    void idsThatDifferOnlyByCaseOrNormalisationAreOneIndexOnEveryFileSystem(@TempDir Path root) throws Exception {
        IndexStore lower = new IndexStore(root, "ds", "abc");
        IndexStore upper = new IndexStore(root, "Ds", "ABC");
        assertEquals(lower.directory(), upper.directory(), "Ds/ds must be the same directory on Linux too");
        assertEquals(new IndexStore(root, "DS", "abc").directory(), lower.directory());
        Path v = lower.publish(stageWithFile(lower));
        assertEquals(v, upper.current().orElseThrow());
        // composed vs decomposed e-acute, and an upper-case non-ASCII id
        assertEquals(new IndexStore(root, "café", "abc").directory(), new IndexStore(root, "café", "abc").directory());
        assertEquals(new IndexStore(root, "Été", "abc").directory(), new IndexStore(root, "éTÉ", "abc").directory());
        assertEquals(new IndexStore(root, "Größe", "abc").directory(), new IndexStore(root, "GRÖSSE", "abc").directory());
        assertNotEquals(new IndexStore(root, "ds", "abc").directory(), new IndexStore(root, "ds2", "abc").directory());
    }

    @Test
    void gcNeverDeletesAnInFlightStageEvenWithMinAgeZero(@TempDir Path root) throws Exception {
        IndexStore s = new IndexStore(root, "ds", "abc");
        Path building = stageWithFile(s);                 // a build in progress: written just now
        assertEquals(List.of(), s.gc(Duration.ZERO));
        assertTrue(Files.exists(building.resolve("edges.parquet")), "an in-flight stage survives gc(ZERO)");
        assertEquals(List.of(), s.gc(Duration.ofSeconds(1)));
        assertTrue(Files.isDirectory(building));
        age(building, Duration.ofMinutes(4));              // quiet for 4 min: still inside the liveness window
        assertEquals(List.of(), s.gc(Duration.ZERO));
        age(building, Duration.ofHours(1));                // crashed: no heartbeat for an hour
        assertEquals(List.of(building.getFileName().toString()), s.gc(Duration.ZERO));
        assertFalse(Files.exists(building));
    }

    @Test
    void aHeartbeatKeepsALongBuildAlive(@TempDir Path root) throws Exception {
        IndexStore s = new IndexStore(root, "ds", "abc");
        Path building = stageWithFile(s);
        age(building, Duration.ofHours(2));
        s.heartbeat(building);
        assertEquals(List.of(), s.gc(Duration.ZERO));
        assertTrue(Files.isDirectory(building));
        assertEquals("v000002.tmp", s.stage(Duration.ZERO).getFileName().toString(), "stage(ZERO) spares the in-flight stage too");
        assertTrue(Files.isDirectory(building));
    }

    @Test
    void discardRemovesOnlyAStageOfThisStore(@TempDir Path root) throws Exception {
        IndexStore s = new IndexStore(root, "ds", "abc");
        Path st = stageWithFile(s);
        s.discard(st);
        assertFalse(Files.exists(st));
        s.discard(st);                                     // already gone: fine
        Path v = s.publish(stageWithFile(s));
        assertThrows(IllegalArgumentException.class, () -> s.discard(v));
        assertTrue(Files.isDirectory(v));
    }

    @Test
    void gcRemovesOrphanCurrentTempFilesButNotAFreshOne(@TempDir Path root) throws Exception {
        IndexStore s = new IndexStore(root, "ds", "abc");
        s.publish(stageWithFile(s));
        Path orphan = Files.writeString(s.directory().resolve("CURRENT.tmp-000007"), "v000007\n");
        Path fresh = Files.writeString(s.directory().resolve("CURRENT.tmp-000008"), "v000008\n");
        age(orphan, Duration.ofHours(1));
        assertEquals(List.of("CURRENT.tmp-000007"), s.gc(Duration.ZERO));
        assertFalse(Files.exists(orphan));
        assertTrue(Files.exists(fresh), "a pointer written a moment ago may be mid-publish");
        assertTrue(Files.exists(s.directory().resolve("CURRENT")));
    }

    @Test
    void publishRefusesToMoveCurrentToALowerVersion(@TempDir Path root) throws Exception {
        IndexStore s = new IndexStore(root, "ds", "abc");
        Path older = stageWithFile(s);                     // v000001.tmp
        Path newer = stageWithFile(s);                     // v000002.tmp
        Path v2 = s.publish(newer);
        var e = assertThrows(IllegalStateException.class, () -> s.publish(older));
        assertTrue(e.getMessage().contains("v000001") && e.getMessage().contains("v000002"), e.getMessage());
        assertEquals(v2, s.current().orElseThrow());
        assertTrue(Files.isDirectory(older), "the refused stage is left for its owner to discard");
        s.discard(older);
        assertEquals("v000003.tmp", stageWithFile(s).getFileName().toString());
    }

    @Test
    void concurrentPublishesNeverRegressCurrent(@TempDir Path root) throws Exception {
        IndexStore s = new IndexStore(root, "ds", "abc");
        List<Path> stages = new java.util.ArrayList<>();
        for (int i = 0; i < 24; i++) stages.add(stageWithFile(s));
        java.util.Collections.shuffle(stages, new java.util.Random(7));
        AtomicBoolean stop = new AtomicBoolean();
        AtomicReference<String> bad = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            long last = 0;
            while (!stop.get()) {
                long now = s.current().map(p -> Long.parseLong(p.getFileName().toString().substring(1))).orElse(0L);
                if (now < last) { bad.compareAndSet(null, "CURRENT went from " + last + " to " + now); return; }
                last = now;
            }
        });
        reader.start();
        java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newFixedThreadPool(8);
        AtomicInteger published = new AtomicInteger();
        List<java.util.concurrent.Future<?>> fs = new java.util.ArrayList<>();
        for (Path st : stages)
            fs.add(ex.submit(() -> {
                try {
                    s.publish(st);
                    published.incrementAndGet();
                } catch (IllegalStateException refused) {
                    // an older stage that lost the race: refused, not applied
                } catch (Exception e) {
                    bad.compareAndSet(null, e.toString());
                }
            }));
        for (var f : fs) f.get(30, java.util.concurrent.TimeUnit.SECONDS);
        ex.shutdown();
        stop.set(true);
        reader.join(10_000);
        assertNull(bad.get());
        assertEquals("v000024", s.current().orElseThrow().getFileName().toString(), "the highest stage always wins");
        assertTrue(published.get() >= 1);
    }
}
