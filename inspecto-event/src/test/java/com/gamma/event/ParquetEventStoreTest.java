package com.gamma.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip tests for {@link ParquetEventStore}: append → flush to rolling Hive-partitioned Parquet →
 * query back through DuckDB {@code read_parquet}, including the live-tail merge of the unflushed
 * buffer and that each flush writes a distinct (non-overwriting) file set.
 */
class ParquetEventStoreTest {

    private static Event ev(long ts, EventLevel lvl, String type, String pipe, String msg) {
        return Event.builder(type).ts(ts).level(lvl).source("src").pipeline(pipe).message(msg)
                .attr("k", 1).build();
    }

    @Test
    void roundTripsThroughParquetAndMergesUnflushedBuffer(@TempDir Path dir) {
        try (ParquetEventStore store = new ParquetEventStore(dir, 1000, 0, 100)) {
            store.append(ev(1_000L, EventLevel.INFO, EventType.JOB_STARTED, "PipeA", "started"));
            store.append(ev(1_001L, EventLevel.ERROR, EventType.BATCH_FAILED, "PipeA", "boom"));
            store.flush();                                              // → Parquet on disk
            store.append(ev(1_002L, EventLevel.INFO, EventType.LOG, "PipeB", "tail"));  // unflushed

            List<Event> all = store.query(EventQuery.recent(100));
            assertEquals(3, all.size(), "2 flushed + 1 buffered");
            assertEquals("tail", all.get(0).message(), "newest-first across buffer + Parquet");

            // attributes survive the columnar round trip
            assertEquals("1", all.get(all.size() - 1).attributes().get("k"));

            // severity filter (partition-pruned) — only the ERROR
            List<Event> errs = store.query(EventQuery.builder().minLevel(EventLevel.ERROR).limit(100).build());
            assertEquals(1, errs.size());
            assertEquals(EventType.BATCH_FAILED, errs.get(0).type());

            // type + pipeline filters
            assertEquals(1, store.query(EventQuery.builder().type("JOB_STARTED").limit(100).build()).size());
            assertEquals(1, store.query(EventQuery.builder().pipeline("pipeb").limit(100).build()).size());

            // text + time window
            assertEquals(1, store.query(EventQuery.builder().textContains("boom").limit(100).build()).size());
            assertEquals(0, store.query(EventQuery.builder().from(5_000L).limit(100).build()).size());

            // live tail from the in-memory ring
            assertEquals("tail", store.recent(1).get(0).message());
        }
    }

    /** COMPLY-3: retention is a delete of whole day-partitions before the cutoff; the window is untouched. */
    @Test
    void pruneDropsWholeDayPartitionsBeforeTheCutoffOnly(@TempDir Path dir) throws Exception {
        long oldTs = java.time.Instant.parse("2020-01-01T10:00:00Z").toEpochMilli();
        long keptTs = java.time.Instant.parse("2020-01-03T10:00:00Z").toEpochMilli();
        try (ParquetEventStore store = new ParquetEventStore(dir, 1000, 0, 100)) {
            store.append(ev(oldTs, EventLevel.INFO, EventType.LOG, "P", "aged"));
            store.append(ev(oldTs + 1, EventLevel.ERROR, EventType.BATCH_FAILED, "P", "aged-error"));
            store.append(ev(keptTs, EventLevel.INFO, EventType.LOG, "P", "kept"));
            store.flush();
            // an unflushed aged event is flushed first, so it is judged on disk like the others
            store.append(ev(oldTs + 2, EventLevel.INFO, EventType.LOG, "P", "aged-buffered"));

            java.time.LocalDate cutoff = java.time.LocalDate.of(2020, 1, 3);
            // INFO and ERROR partitions for 2020-01-01 → two day-partitions, previewed without deleting
            assertEquals(2, store.prune(cutoff, true));
            assertEquals(4, store.query(EventQuery.recent(100)).size(), "a preview removes nothing");

            assertEquals(2, store.prune(cutoff, false));
            List<Event> left = store.query(EventQuery.recent(100));
            assertEquals(1, left.size(), "only the in-window event survives: " + left);
            assertEquals("kept", left.get(0).message());
            assertEquals(0, store.prune(cutoff, false), "idempotent");

            // the emptied year=2020/month=01 parents were NOT removed — day=03 still lives there
            assertTrue(java.nio.file.Files.isDirectory(dir.resolve("level=INFO/year=2020/month=01/day=03")));
            assertFalse(java.nio.file.Files.exists(dir.resolve("level=INFO/year=2020/month=01/day=01")));
            assertFalse(java.nio.file.Files.exists(dir.resolve("level=ERROR")), "an emptied level tree collapses");
        }
    }

    @Test
    void multipleFlushesAccumulateWithoutOverwrite(@TempDir Path dir) {
        try (ParquetEventStore store = new ParquetEventStore(dir, 1000, 0, 100)) {
            store.append(ev(1, EventLevel.INFO, EventType.LOG, "p", "a")); store.flush();
            store.append(ev(2, EventLevel.INFO, EventType.LOG, "p", "b")); store.flush();
            store.append(ev(3, EventLevel.INFO, EventType.LOG, "p", "c")); store.flush();
            assertEquals(3, store.query(EventQuery.recent(100)).size(),
                    "each flush writes a uniquely-named file rather than overwriting");
        }
    }

    @Test
    void emptyStoreQueriesCleanlyAndFlushIsNoop(@TempDir Path dir) {
        try (ParquetEventStore store = new ParquetEventStore(dir, 1000, 0, 100)) {
            assertTrue(store.query(EventQuery.recent(100)).isEmpty(), "no Parquet yet → no crash");
            assertTrue(store.recent(10).isEmpty());
            store.flush();   // empty buffer → no-op
            assertTrue(store.query(EventQuery.recent(100)).isEmpty());
        }
    }

    @Test
    void sizeThresholdAutoFlushes(@TempDir Path dir) {
        try (ParquetEventStore store = new ParquetEventStore(dir, 2, 0, 100)) {
            store.append(ev(1, EventLevel.INFO, EventType.LOG, "p", "a"));
            store.append(ev(2, EventLevel.INFO, EventType.LOG, "p", "b"));   // hits threshold → flush
            // a fresh reader-only store over the same dir sees the auto-flushed rows on disk
            try (ParquetEventStore reader = new ParquetEventStore(dir, 1000, 0, 100)) {
                assertEquals(2, reader.query(EventQuery.recent(100)).size(),
                        "threshold flush persisted both events to Parquet");
            }
        }
    }
}
