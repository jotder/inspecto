package com.gamma.pipeline.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WATERMARK-ATOMIC-1 (2026-09-17): the watermark was the ONE store still written in place, so a crash mid-write
 * left a torn file the next incremental run read as garbage or empty. It now goes through the temp-file +
 * ATOMIC_MOVE writer like every other store, and this pins the observable half of that: the value round-trips,
 * a second put replaces the first, and no temp file is left behind.
 */
class PipelineWatermarkStoreTest {

    @Test
    void putThenGetRoundTripsAndReplaces(@TempDir Path dir) throws Exception {
        PipelineWatermarkStore store = new PipelineWatermarkStore(dir);
        assertTrue(store.get("orders", "orders_daily").isEmpty(), "absent file => first run");

        store.put("orders", "orders_daily", "2026-09-16T23:59:59");
        assertEquals("2026-09-16T23:59:59", store.get("orders", "orders_daily").orElseThrow());

        store.put("orders", "orders_daily", "2026-09-17T12:00:00");
        assertEquals("2026-09-17T12:00:00", store.get("orders", "orders_daily").orElseThrow(), "last write wins");
    }

    @Test
    void leavesNoTempFileBehind(@TempDir Path dir) throws Exception {
        PipelineWatermarkStore store = new PipelineWatermarkStore(dir);
        store.put("p", "s", "1");
        store.put("p", "s", "2");
        try (Stream<Path> files = Files.list(dir)) {
            List<String> names = files.map(p -> p.getFileName().toString()).toList();
            assertEquals(List.of("p__s.watermark"), names, "exactly the watermark, no .wm- temp file: " + names);
        }
    }

    @Test
    void keysAreFileSafe(@TempDir Path dir) throws Exception {
        PipelineWatermarkStore store = new PipelineWatermarkStore(dir);
        store.put("../evil", "a/b", "x");
        try (Stream<Path> files = Files.list(dir)) {
            assertTrue(files.allMatch(p -> p.getParent().equals(dir)), "the key never escapes the store dir");
        }
        assertEquals("x", store.get("../evil", "a/b").orElseThrow());
    }
}
