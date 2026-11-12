package com.gamma.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** LA-PERF-SERVER-1: a stamped cache serves a quiet tree from memory and never serves a stale answer. */
class StampedCacheTest {

    private static void age(Path dir, long minutes) throws Exception {
        try (var w = Files.walk(dir)) {
            for (Path p : w.filter(Files::isRegularFile).toList())
                Files.setLastModifiedTime(p, FileTime.fromMillis(System.currentTimeMillis() - minutes * 60_000L));
        }
    }

    @Test
    void aQuietTreeIsLoadedOnceAndAChangedFileLoadsAgain(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("a.toon"), "name: a\n");
        age(root, 10);
        StampedCache<String> c = new StampedCache<>();
        for (int i = 0; i < 5; i++) assertEquals("v", c.get(root, () -> "v"));
        assertEquals(1, c.loadCount(), "five reads of a quiet tree parse once");

        Files.writeString(root.resolve("a.toon"), "name: ab\n");   // size changes
        age(root, 9);
        assertEquals("w", c.get(root, () -> "w"));
        Files.writeString(root.resolve("b.toon"), "x");            // a file is added
        age(root, 8);
        assertEquals("x", c.get(root, () -> "x"));
        Files.delete(root.resolve("b.toon"));                       // a file is removed
        assertEquals("y", c.get(root, () -> "y"));
        assertEquals(4, c.loadCount());
    }

    @Test
    void aSameSizeRewriteInsideTheRacyWindowIsNeverServedStale(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("a.toon"), "name: a\n");   // just written: inside RACY_MS
        StampedCache<String> c = new StampedCache<>();
        assertEquals("1", c.get(root, () -> "1"));
        Files.writeString(root.resolve("a.toon"), "name: b\n");   // same size, same tick
        assertEquals("2", c.get(root, () -> "2"), "a tree modified in the last two seconds is not remembered");
        assertEquals(2, c.loadCount());
    }

    @Test
    void invalidateAndAVetoedValueAreNotRemembered(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("a.toon"), "n");
        age(root, 10);
        StampedCache<String> c = new StampedCache<>();
        c.get(root, () -> "1");
        c.invalidate(root);
        assertEquals("2", c.get(root, () -> "2"));
        assertEquals("2", c.get(root.resolve("..").resolve(root.getFileName()), () -> "3"), "the key is the normalised path: a hit");
        // a veto (the value read a file outside the root): returned, never kept
        StampedCache<String> v = new StampedCache<>();
        v.get(root, () -> "a", x -> false);
        assertEquals("b", v.get(root, () -> "b", x -> false));
        assertEquals(2, v.loadCount());
    }

    @Test
    void aMissingRootIsNeverCached(@TempDir Path root) {
        StampedCache<String> c = new StampedCache<>();
        Path gone = root.resolve("nope");
        c.get(gone, () -> "1");
        c.get(gone, () -> "2");
        assertEquals(2, c.loadCount());
    }

    @Test
    void theRegistryScanIsServedFromMemoryUntilAComponentChanges(@TempDir Path root) throws Exception {
        ComponentStore store = new ComponentStore(root);
        store.write("grammar", "pipe", Map.of("delimiter", "|"));
        age(root, 10);
        long before = ComponentRegistry.scanCount();
        for (int i = 0; i < 5; i++) assertEquals(1, store.list("grammar").size());
        long scans = ComponentRegistry.scanCount() - before;
        assertTrue(scans <= 1, "five reads scan once at most (was " + scans + ")");
        store.write("grammar", "comma", Map.of("delimiter", ","));   // a write is seen at once
        assertEquals(2, store.list("grammar").size());
        assertTrue(store.delete("grammar", "comma"));
        assertEquals(1, store.list("grammar").size());
    }

    @Test
    void aRenameAndAChangedSaltAreNeverServedStale(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("Aa.toon"), "n");
        age(root, 10);
        assertNotEquals(StampedCache.stamp(root), 0L);
        long before = StampedCache.stamp(root);
        Files.move(root.resolve("Aa.toon"), root.resolve("BB.toon"));   // same size, same mtime, colliding String.hashCode
        assertNotEquals(before, StampedCache.stamp(root), "a rename changes the stamp");
        StampedCache<String> c = new StampedCache<>();
        c.get(root, "policy-a", () -> "1", v -> true);
        assertEquals("2", c.get(root, "policy-b", () -> "2", v -> true), "a changed salt (the Safety Policy) is a miss");
        assertEquals("3", c.get(root, null, () -> "3", v -> true), "a null salt is never cached");
        assertEquals("4", c.get(root, null, () -> "4", v -> true));
    }
}
