package com.gamma.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
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

    /**
     * A hard kill (no close(), no shutdown hook) must not lose buffered events — AUDIT rows above all. Found
     * 2026-09-26: a demo build stopped by kill came back with none of its audit trail. The write-ahead journal
     * (operator, 2026-09-26) holds every buffered event until its flush lands; a reopened store replays it.
     */
    @Test
    void bufferedEventsSurviveAHardKillThroughTheJournal(@TempDir Path dir) {
        // Never flushes on its own (threshold 1000, no time roll), and is ABANDONED, never closed: a crash.
        ParquetEventStore crashed = new ParquetEventStore(dir, 1000, 0, 100);
        crashed.append(ev(1_000L, EventLevel.INFO, EventType.AUDIT, null, "admin object.updated INC-1"));
        crashed.append(ev(2_000L, EventLevel.WARN, EventType.ACCESS_DENIED, null, "analyst refused"));
        crashed.append(ev(3_000L, EventLevel.INFO, "JOB_STARTED", "pipea", "job"));

        try (ParquetEventStore reopened = new ParquetEventStore(dir, 1000, 0, 100)) {
            List<Event> back = reopened.query(EventQuery.recent(100));
            assertEquals(List.of("job", "analyst refused", "admin object.updated INC-1"),
                    back.stream().map(Event::message).toList(), "all three buffered events replayed, newest first");
            assertEquals("1", back.get(2).attributes().get("k"), "attributes survive the journal");
        }
        // replayed into Parquet and the journal cleared: a third open does not duplicate them
        try (ParquetEventStore again = new ParquetEventStore(dir, 1000, 0, 100)) {
            assertEquals(3, again.query(EventQuery.recent(100)).size(), "replayed exactly once");
        }
    }

    /** A kill in the middle of a journal write leaves a torn last line; it is skipped, the rest replays. */
    @Test
    void aTornLastJournalLineIsSkipped(@TempDir Path dir) throws Exception {
        ParquetEventStore crashed = new ParquetEventStore(dir, 1000, 0, 100);
        crashed.append(ev(1_000L, EventLevel.INFO, EventType.AUDIT, null, "kept"));
        java.nio.file.Files.writeString(dir.resolve(ParquetEventStore.JOURNAL), "{\"eventId\":\"torn",
                java.nio.file.StandardOpenOption.APPEND);
        try (ParquetEventStore reopened = new ParquetEventStore(dir, 1000, 0, 100)) {
            assertEquals(List.of("kept"), reopened.query(EventQuery.recent(100)).stream().map(Event::message).toList());
        }
    }
    private static List<Path> parquetIn(Path day) throws Exception {
        try (var s = java.nio.file.Files.list(day)) {
            return s.filter(p -> p.toString().endsWith(".parquet")).sorted().toList();
        }
    }

    /** ASSURE-AUDIT-CHAIN-RESIDUALS-1 (9): one corrupt Parquet file used to fail the journal replay's
     *  already-flushed check outright, so a crashed run's events never reached Parquet. A file that cannot hold
     *  the journalled event (another level/day partition) no longer blocks it; the file is reported. */
    @Test
    void aCorruptFileElsewhereDoesNotBlockTheJournalReplay(@TempDir Path dir) throws Exception {
        try (ParquetEventStore store = new ParquetEventStore(dir, 1000, 0, 100)) {
            for (int i = 0; i < 2; i++) {
                store.append(ev(1_000L + i, EventLevel.INFO, EventType.LOG, "P", "old" + i));
                store.flush();
            }
        }
        java.nio.file.Files.writeString(parquetIn(dir.resolve("level=INFO/year=1970/month=01/day=01")).get(0),
                "not a parquet file");
        ParquetEventStore crashed = new ParquetEventStore(dir, 1000, 0, 100);
        crashed.append(ev(3 * 86_400_000L, EventLevel.WARN, EventType.AUDIT, null, "late"));
        crashed.simulateCrash();

        try (ParquetEventStore reopened = new ParquetEventStore(dir, 1000, 0, 100)) {
            List<Event> back = reopened.query(EventQuery.recent(100));
            assertEquals(1, back.stream().filter(e -> "late".equals(e.message())).count(), "the journal replayed");
            assertEquals(1, reopened.unreadableUnits().size(), "and the corrupt file is named");
            assertTrue(back.stream().anyMatch(e -> ParquetEventStore.UNREADABLE_SIGNAL.equals(
                    e.attributes().get("signalType"))), "with its Signal");
        }
        assertEquals(0, java.nio.file.Files.size(dir.resolve(ParquetEventStore.JOURNAL)), "journal cleared");
        assertFalse(java.nio.file.Files.exists(dir.resolve(ParquetEventStore.HELD)), "nothing held");
        try (ParquetEventStore again = new ParquetEventStore(dir, 1000, 0, 100)) {
            assertEquals(1, again.query(EventQuery.recent(100)).stream()
                    .filter(e -> "late".equals(e.message())).count(), "replayed exactly once");
        }
    }

    /** (9) fail-closed: when the unreadable file IS the one the journalled rows were flushed into (a kill between
     *  the flush and the journal truncate), the replay cannot prove them unflushed, so it holds them back instead of
     *  writing them twice — and presentIds refuses to answer "absent". Once the file reads again, the held rows are
     *  found flushed and dropped: exactly one copy. */
    @Test
    void journalledRowsAnUnreadableFileMayHoldAreHeldNotWrittenTwice(@TempDir Path dir) throws Exception {
        ParquetEventStore crashed = new ParquetEventStore(dir, 1000, 0, 100);
        Event a = ev(1_000L, EventLevel.INFO, EventType.AUDIT, null, "A");
        crashed.append(a);
        byte[] journal = java.nio.file.Files.readAllBytes(dir.resolve(ParquetEventStore.JOURNAL));
        crashed.flush();                       // A reaches Parquet ...
        crashed.simulateCrash();
        java.nio.file.Files.write(dir.resolve(ParquetEventStore.JOURNAL), journal);   // ... the truncate did not
        Path f = parquetIn(dir.resolve("level=INFO/year=1970/month=01/day=01")).get(0);
        byte[] good = java.nio.file.Files.readAllBytes(f);
        java.nio.file.Files.writeString(f, "not a parquet file");

        try (ParquetEventStore reopened = new ParquetEventStore(dir, 1000, 0, 100)) {
            assertEquals(0, reopened.query(EventQuery.recent(100)).stream()
                    .filter(e -> "A".equals(e.message())).count(), "held back, not written again");
            assertThrows(IllegalStateException.class, () -> reopened.presentIds(List.of(a.eventId())),
                    "presentIds does not answer 'absent' for an id the unreadable file may hold");
        }
        assertTrue(java.nio.file.Files.exists(dir.resolve(ParquetEventStore.HELD)), "kept for the next open");

        java.nio.file.Files.write(f, good);    // repaired
        try (ParquetEventStore again = new ParquetEventStore(dir, 1000, 0, 100)) {
            assertEquals(1, again.query(EventQuery.recent(100)).stream()
                    .filter(e -> "A".equals(e.message())).count(), "exactly one copy");
            assertEquals(java.util.Set.of(a.eventId()), again.presentIds(List.of(a.eventId())));
        }
        assertFalse(java.nio.file.Files.exists(dir.resolve(ParquetEventStore.HELD)), "hold released");
    }

    /** (9) review: a held event released while another stays held must survive a failed replay flush and a crash —
     *  the held file is never rewritten to drop an event before that event is on disk. */
    @Test
    void aReleasedHeldEventSurvivesAFailedFlushAndACrash(@TempDir Path dir) throws Exception {
        ParquetEventStore crashed = new ParquetEventStore(dir, 1000, 0, 100);
        crashed.append(ev(1_000L, EventLevel.INFO, EventType.LOG, null, "E1"));
        crashed.append(ev(1_000L, EventLevel.WARN, EventType.LOG, null, "E2"));
        byte[] journal = java.nio.file.Files.readAllBytes(dir.resolve(ParquetEventStore.JOURNAL));
        crashed.flush();
        crashed.simulateCrash();
        java.nio.file.Files.write(dir.resolve(ParquetEventStore.JOURNAL), journal);
        Path f1 = parquetIn(dir.resolve("level=INFO/year=1970/month=01/day=01")).get(0);
        Path warn = dir.resolve("level=WARN");
        byte[] good1 = java.nio.file.Files.readAllBytes(f1);
        java.nio.file.Files.writeString(f1, "not a parquet file");
        java.nio.file.Files.writeString(parquetIn(warn.resolve("year=1970/month=01/day=01")).get(0), "bad");
        new ParquetEventStore(dir, 1000, 0, 100).close();              // both held

        deleteTree(warn);                                                // E2's file is gone: E2 is released ...
        Path staging = dir.resolve(".staging");
        if (java.nio.file.Files.exists(staging)) deleteTree(staging);
        java.nio.file.Files.writeString(staging, "a file where the staging directory must go");  // ... its flush fails
        new ParquetEventStore(dir, 1000, 0, 100).simulateCrash();       // and the process dies
        java.nio.file.Files.delete(staging);
        java.nio.file.Files.write(f1, good1);

        try (ParquetEventStore again = new ParquetEventStore(dir, 1000, 0, 100)) {
            List<String> msgs = again.query(EventQuery.recent(100)).stream().map(Event::message).toList();
            assertEquals(1, msgs.stream().filter("E2"::equals).count(), "E2 not lost: " + msgs);
            assertEquals(1, msgs.stream().filter("E1"::equals).count(), "E1 exactly once: " + msgs);
        }
    }

    /** (9) second review: a replay flush that fails AT the retention cap clears the buffer (give-up), which used to
     *  look like success — the journal was deleted and its events, whose only durable copy it was, lost. */
    @Test
    void aReplayFlushThatGivesUpKeepsTheJournal(@TempDir Path dir) throws Exception {
        ParquetEventStore crashed = new ParquetEventStore(dir, 1000, 0, 100);
        EventLog before = EventLog.create();
        before.installStore(crashed);
        before.emit(ev(1_000L, EventLevel.INFO, EventType.AUDIT, null, "J1"));   // linked: seq 1
        before.emit(ev(2_000L, EventLevel.INFO, EventType.AUDIT, null, "J2"));   // seq 2
        crashed.simulateCrash();
        Path staging = dir.resolve(".staging");
        if (java.nio.file.Files.exists(staging)) deleteTree(staging);
        java.nio.file.Files.writeString(staging, "a file where the staging directory must go");
        int cap = ParquetEventStore.maxRetained;
        ParquetEventStore.maxRetained = 1;                               // the replay flush gives up at once
        try {
            ParquetEventStore gaveUp = new ParquetEventStore(dir, 1000, 0, 100);
            // J1/J2 now live only in the held file, invisible to chainHead: a new row must not take seq 1 again.
            EventLog after = EventLog.create();
            after.installStore(gaveUp);
            after.emit(ev(3_000L, EventLevel.INFO, EventType.AUDIT, null, "B"));
            Event b = gaveUp.query(EventQuery.recent(100)).stream().filter(e -> "B".equals(e.message()))
                    .findFirst().orElseThrow();
            assertEquals("true", b.attributes().get(AuditAttrs.AUDIT_UNLINKED), "B is not linked past the held rows");
            gaveUp.simulateCrash();
        } finally {
            ParquetEventStore.maxRetained = cap;
        }
        java.nio.file.Files.delete(staging);
        try (ParquetEventStore again = new ParquetEventStore(dir, 1000, 0, 100)) {
            List<String> msgs = again.query(EventQuery.recent(100)).stream().map(Event::message).toList();
            assertTrue(msgs.contains("J1") && msgs.contains("J2"), "journal kept, replayed later: " + msgs);
            List<Long> seqs = again.chainPage(1, 100).stream().map(AuditChain::seq).toList();
            assertEquals(new java.util.HashSet<>(seqs).size(), seqs.size(), "no seq twice: " + seqs);
        }
    }

    private static void deleteTree(Path p) throws Exception {
        try (var w = java.nio.file.Files.walk(p)) {
            for (Path x : w.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.delete(x);
        }
    }

    /** ASSURE-AUDIT-CHAIN-RESIDUALS-1 (4): one corrupt file in the middle of the store is skipped and REPORTED —
     *  search, keyset page and count still answer from every other file, never an empty "complete" result. */
    @Test
    void aCorruptFileInTheMiddleIsSkippedAndReportedByEveryOrdinaryRead(@TempDir Path dir) throws Exception {
        try (ParquetEventStore store = new ParquetEventStore(dir, 1000, 0, 100)) {
            for (int i = 0; i < 5; i++) {
                store.append(ev(1_000L + i, EventLevel.INFO, EventType.LOG, "P", "row" + i));
                store.flush();                                          // one Parquet file per row
            }
        }
        Path day = dir.resolve("level=INFO/year=1970/month=01/day=01");
        List<Path> files;
        try (var s = java.nio.file.Files.list(day)) {
            files = s.filter(p -> p.toString().endsWith(".parquet")).sorted().toList();
        }
        assertEquals(5, files.size());
        java.nio.file.Files.writeString(files.get(2), "not a parquet file");   // corrupt the middle one in place
        String bad = "level=INFO/year=1970/month=01/day=01/" + files.get(2).getFileName();
        try (ParquetEventStore store = new ParquetEventStore(dir, 1000, 0, 100)) {
            List<Event> rows = store.query(EventQuery.builder().type(EventType.LOG).limit(100).build());
            assertEquals(4, rows.size(), "the four readable files still answer");
            assertEquals(List.of(bad), store.unreadableUnits(), "the skipped file is named, not dropped silently");
            List<Event> signals = store.query(EventQuery.builder().type(EventType.SIGNAL).limit(100).build());
            assertEquals(1, signals.size(), "one warning Signal per newly unreadable file, not one per read");
            assertEquals(ParquetEventStore.UNREADABLE_SIGNAL, signals.get(0).attributes().get("signalType"));
            assertEquals(EventLevel.WARN, signals.get(0).level());
            assertEquals(bad, signals.get(0).attributes().get("file"));

            assertEquals(4, store.page(100, null, null).stream().filter(e -> EventType.LOG.equals(e.type())).count(),
                    "the keyset page skips the same file");
            assertEquals(5, store.count(), "four rows on disk + the buffered Signal");

            java.nio.file.Files.delete(files.get(2));
            store.query(EventQuery.recent(10));
            assertEquals(List.of(), store.unreadableUnits(), "a removed file is no longer reported");
        }
    }

    private static Event chained(long ts, long seq, String msg) {
        return Event.builder(EventType.AUDIT).ts(ts).message(msg).attr(AuditAttrs.AUDIT_SEQ, seq).build();
    }

    /** Every chained row with seq >= from, in chain order, first n — the answer a full scan gives. */
    private static List<Event> reference(List<Event> all, long from, int n) {
        return all.stream().filter(e -> AuditChain.seq(e) >= from).sorted(EventStore.CHAIN_ORDER).limit(n).toList();
    }

    private static List<String> ids(List<Event> es) {
        return es.stream().map(Event::eventId).toList();
    }

    /**
     * ASSURE-AUDIT-CHAIN-RESIDUALS-1 (3): chain pages choose their files from the per-file seq index, and still
     * answer exactly what a full scan would — across a gap, a seq claimed twice in two far-apart files, a file
     * written late with a low seq, files with no audit rows, and a fresh store indexing files an earlier one wrote.
     */
    @Test
    void chainPagesReadOnlyTheFilesTheirSeqRangeNeedsAndMatchAFullScan(@TempDir Path dir) throws Exception {
        List<Event> chain = new ArrayList<>();
        try (ParquetEventStore store = new ParquetEventStore(dir, 100_000, 0, 100)) {
            long ts = 1_000;
            for (long seq = 1; seq <= 200; seq++) {
                if (seq == 57) continue;                                         // a hole
                Event e = chained(ts++, seq, "s" + seq);
                chain.add(e);
                store.append(e);
                if (seq % 10 == 0) {
                    store.append(ev(ts++, EventLevel.INFO, EventType.LOG, "P", "noise"));
                    store.flush();                                               // ~20 chained files
                    for (int k = 0; k < 2; k++) {                                // + files with no audit row
                        store.append(ev(ts++, EventLevel.INFO, EventType.LOG, "P", "noise"));
                        store.flush();
                    }
                }
            }
            Event dup = chained(ts++, 12, "second claim on 12");                 // a duplicate, far from its twin
            Event late = chained(ts++, 3, "second claim on 3");                  // a late file with a low seq
            chain.add(dup);
            chain.add(late);
            store.append(dup);
            store.append(late);
            store.flush();

            for (long from : new long[]{1, 3, 12, 13, 50, 57, 58, 190, 200, 201})
                for (int n : new int[]{1, 2, 7, 10, 25, 1000})
                    assertEquals(ids(reference(chain, from, n)), ids(store.chainPage(from, n)), "from " + from + ", n " + n);
            store.chainPage(100, 5);
            assertTrue(store.lastChainFiles <= 3, "a page reads the files its range needs, not the whole store: "
                    + store.lastChainFiles);
            assertEquals(200, AuditChain.seq(store.chainHead()));
            assertEquals(1, store.lastChainFiles, "the head is read from the file holding the highest seq");
            assertEquals(2, store.chainPage(12, 2).stream().filter(e -> AuditChain.seq(e) == 12).count(),
                    "both claims on seq 12 are seen, so verify still reports the duplicate");
            assertEquals(0, store.unlinkedSince(0));
            assertEquals(0, store.lastChainFiles, "no file holds an unlinked audit row, so none is read");
        }

        // A fresh store knows nothing of these files: it indexes them from their contents on the first chain read.
        try (ParquetEventStore store = new ParquetEventStore(dir, 100_000, 0, 100)) {
            assertEquals(ids(reference(chain, 1, 1000)), ids(store.chainPage(1, 1000)));
            store.append(chained(9_000, 500, "written later"));
            store.flush();
            assertEquals(500, AuditChain.seq(store.chainHead()), "a file new since the last read is indexed");
            assertEquals(List.of(), store.unreadableUnits());
        }
    }

    /** A file indexed while readable and overwritten afterwards (same size, same time — the index is stale) is
     *  dropped from the choice and reported, never silently narrowing a page. */
    @Test
    void aChainFileCorruptedAfterItWasIndexedIsReportedNotSilentlySkipped(@TempDir Path dir) throws Exception {
        try (ParquetEventStore store = new ParquetEventStore(dir, 100_000, 0, 100)) {
            for (long seq = 1; seq <= 30; seq++) {
                store.append(chained(1_000 + seq, seq, "s" + seq));
                if (seq % 10 == 0) store.flush();
            }
            assertEquals(30, store.chainPage(1, 100).size());
            Path mid;
            try (var w = java.nio.file.Files.walk(dir)) {
                mid = w.filter(p -> p.toString().endsWith(".parquet")).sorted().toList().get(1);
            }
            byte[] b = java.nio.file.Files.readAllBytes(mid);
            java.util.Arrays.fill(b, (byte) 0);
            long mtime = mid.toFile().lastModified();
            java.nio.file.Files.write(mid, b);
            assertTrue(mid.toFile().setLastModified(mtime));
            assertEquals(20, store.chainPage(1, 100).size(), "its neighbours still answer");
            assertEquals(1, store.unreadableUnits().size(), "and the corrupt file is named");
            assertEquals(30, AuditChain.seq(store.chainHead()));
        }
    }
    /** Overwrite the middle of {@code column}'s data in {@code f}, leaving the footer (and every other column) valid. */
    private static void corruptColumn(Path f, String column) throws Exception {
        long offset;
        long length;
        com.gamma.util.DuckDbUtil.loadDriver();
        try (java.sql.Connection c = com.gamma.util.DuckDbUtil.openInMemory(null);
             java.sql.PreparedStatement ps = c.prepareStatement("SELECT data_page_offset, total_compressed_size FROM "
                     + "parquet_metadata(?) WHERE path_in_schema = ?")) {
            ps.setString(1, f.toString().replace('\\', '/'));
            ps.setString(2, column);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "the file has a " + column + " column chunk");
                offset = rs.getLong(1);
                length = rs.getLong(2);
            }
        }
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(f,
                java.nio.file.StandardOpenOption.WRITE)) {
            long from = offset + length / 4;
            byte[] junk = new byte[(int) Math.max(8, length / 2)];
            java.util.Arrays.fill(junk, (byte) 0x5A);
            ch.write(java.nio.ByteBuffer.wrap(junk), from);
        }
    }

    private static Event withPayload(Event e, int i) {
        StringBuilder big = new StringBuilder();
        java.util.Random r = new java.util.Random(i);
        for (int k = 0; k < 4000; k++) big.append((char) ('a' + r.nextInt(26)));
        return new Event(e.eventId(), e.ts(), e.level(), e.type(), e.source(), e.pipeline(), e.correlationId(),
                e.message(), e.attributes(), java.util.Map.of("blob", big.toString()));
    }

    private static long signals(ParquetEventStore store) {
        return store.query(EventQuery.builder().type(EventType.SIGNAL).limit(100).build()).size();
    }

    /**
     * A file whose footer and indexed columns read but whose payload page is corrupt passes the index's aggregate
     * and fails the full-row chain read. It must be read at most once and then stay out — not re-indexed and
     * re-chosen forever under the store lock (every append waits on chainHead) — and raise ONE Signal however
     * often a count or page polls it.
     */
    @Test
    void aFileCorruptOnlyInAColumnTheIndexDoesNotReadCannotSpinTheChainOrSpamSignals(@TempDir Path dir)
            throws Exception {
        try (ParquetEventStore store = new ParquetEventStore(dir, 100_000, 0, 100)) {
            for (long seq = 1; seq <= 30; seq++) {
                store.append(withPayload(chained(1_000 + seq, seq, "s" + seq), (int) seq));
                if (seq % 10 == 0) store.flush();
            }
        }
        Path last;
        try (var w = java.nio.file.Files.walk(dir)) {
            last = w.filter(p -> p.toString().endsWith(".parquet")).sorted().toList().get(2);   // holds seq 21..30
        }
        corruptColumn(last, "payload");
        try (java.sql.Connection c = com.gamma.util.DuckDbUtil.openInMemory(null);
             java.sql.Statement st = c.createStatement()) {
            String f = "read_parquet('" + last.toString().replace(java.io.File.separatorChar, '/') + "')";
            st.executeQuery("SELECT COUNT(*), MAX(ts_ms), MAX(json_extract_string(attributes, '$.audit_seq')), "
                    + "MAX(type) FROM " + f).close();                 // the index's columns still read
            assertThrows(java.sql.SQLException.class, () -> st.executeQuery("SELECT payload FROM " + f).close(),
                    "the payload column does not");
        }
        try (ParquetEventStore store = new ParquetEventStore(dir, 100_000, 0, 100)) {
            Event head = assertTimeoutPreemptively(java.time.Duration.ofSeconds(30), store::chainHead,
                    "a chain read over a file that indexes but does not read must finish");
            assertEquals(20, AuditChain.seq(head), "the head comes from the files that do read");
            assertEquals(1, store.unreadableUnits().size());
            assertEquals(20, store.chainPage(1, 100).size());
            for (int poll = 0; poll < 5; poll++) {
                store.count();                                   // COUNT(*) reads the footer only: it would succeed
                store.page(10, null, null);
                store.chainHead();
            }
            assertEquals(1, store.unreadableUnits().size(), "a footer-only success does not clear the file");
            assertEquals(1, signals(store), "one Signal per file version, not one per poll");
        }
    }

    /** A file that failed once (bad bytes, then restored in place with the same size and time — identity
     *  unchanged, as on Windows where there is no file key) is back after the rebuild every verify starts with. */
    @Test
    void aFileThatFailedOnceIsRetriedByTheNextRebuild(@TempDir Path dir) throws Exception {
        try (ParquetEventStore store = new ParquetEventStore(dir, 100_000, 0, 100)) {
            for (long seq = 1; seq <= 20; seq++) {
                store.append(chained(1_000 + seq, seq, "s" + seq));
                if (seq % 10 == 0) store.flush();
            }
            Path second;
            try (var w = java.nio.file.Files.walk(dir)) {
                second = w.filter(p -> p.toString().endsWith(".parquet")).sorted().toList().get(1);
            }
            byte[] good = java.nio.file.Files.readAllBytes(second);
            long mtime = second.toFile().lastModified();
            java.nio.file.Files.write(second, new byte[good.length]);
            assertTrue(second.toFile().setLastModified(mtime));
            assertEquals(10, store.chainPage(1, 100).size());
            assertEquals(1, store.unreadableUnits().size());

            java.nio.file.Files.write(second, good);                          // repaired in place, same identity
            assertTrue(second.toFile().setLastModified(mtime));
            assertEquals(10, store.chainPage(1, 100).size(), "still excluded: its identity did not change");
            store.rebuildChainIndex();
            assertEquals(20, store.chainPage(1, 100).size(), "the rebuild retried it");
            assertEquals(List.of(), store.unreadableUnits());
            assertEquals(1, signals(store), "one Signal for the one failure");
        }
    }

    /** Verify rebuilds the index from the files: a rewrite that restores size and modification time — and so looks
     *  unchanged to the cache — cannot hide a forged row from it. */
    @Test
    void rebuildingTheIndexSeesAForgedRewriteTheCacheWouldTrust(@TempDir Path dir) throws Exception {
        try (ParquetEventStore store = new ParquetEventStore(dir, 100_000, 0, 100)) {
            for (long seq = 1; seq <= 20; seq++) {
                store.append(chained(1_000 + seq, seq, "s" + seq));
                if (seq % 10 == 0) store.flush();
            }
            store.append(ev(5_000, EventLevel.INFO, EventType.LOG, "P", "noise"));
            store.flush();
            assertEquals(20, store.chainPage(1, 100).size());    // every file indexed; the noise file as chained=0
            Path noise;
            try (var w = java.nio.file.Files.walk(dir)) {
                noise = w.filter(p -> p.toString().endsWith(".parquet")).sorted().toList().get(2);
            }
            // Forge: the noise file now carries a second claim on seq 5; the cache is made to see it as unchanged.
            Path forgedDir = java.nio.file.Files.createTempDirectory("forge");
            try (ParquetEventStore forger = new ParquetEventStore(forgedDir, 100_000, 0, 100)) {
                forger.append(chained(5_000, 5, "forged"));
                forger.flush();
            }
            Path forged;
            try (var w = java.nio.file.Files.walk(forgedDir)) {
                forged = w.filter(p -> p.toString().endsWith(".parquet")).findFirst().orElseThrow();
            }
            java.nio.file.Files.copy(forged, noise, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            store.restampForTest(noise);
            assertEquals(1, store.chainPage(5, 2).stream().filter(e -> AuditChain.seq(e) == 5).count(),
                    "the cached range hides the forged row — the reason verify must not trust it");
            store.rebuildChainIndex();
            assertEquals(2, store.chainPage(5, 2).stream().filter(e -> AuditChain.seq(e) == 5).count(),
                    "after a rebuild both claims on seq 5 are read, so verify reports the duplicate");
        }
    }
}
