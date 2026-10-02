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
}
