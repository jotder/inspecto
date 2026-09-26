package com.gamma.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ASSURE-AUDIT-CHAIN-1 at the seam that builds the chain: {@link EventLog#emit} links every AUDIT /
 * ACCESS_DENIED event ({@link AuditChain}) under one monitor with the append. These tests read the stored chain
 * back and check it link by link — seq contiguous from 1, each prevHash the previous hash, each hash recomputable —
 * across the cases that could fork or duplicate it: a restart, a hard kill replayed from the journal, a journal
 * whose events had already been flushed, a store swap, and many threads at once. (Tamper DETECTION is
 * {@code AuditVerifierTest}'s, in the control plane where {@code /audit/verify} lives.)
 */
class AuditChainTest {

    private static Event audit(long ts, String msg) {
        return Event.builder(EventType.AUDIT).ts(ts).source("audit").message(msg).actor("alice")
                .action("pipeline.updated").build();
    }

    /** The chain as stored, asserted whole: seq 1..n, linked, every hash recomputes. Returns it. */
    private static List<Event> assertChain(EventStore store, int n) {
        List<Event> chain = store.chainPage(1, 100_000);
        assertEquals(n, chain.size(), "chained records: " + chain.stream().map(AuditChain::seq).toList());
        String prev = AuditChain.GENESIS;
        long prevTs = Long.MIN_VALUE;
        for (int i = 0; i < chain.size(); i++) {
            Event e = chain.get(i);
            assertEquals(i + 1, AuditChain.seq(e), "seq contiguous from 1");
            assertEquals(prev, AuditChain.prevHash(e), "seq " + (i + 1) + " links onto its predecessor");
            assertEquals(AuditChain.hash(e), AuditChain.storedHash(e), "seq " + (i + 1) + " hash recomputes");
            assertTrue(e.ts() >= prevTs, "time never goes back along the chain");
            prev = AuditChain.storedHash(e);
            prevTs = e.ts();
        }
        return chain;
    }

    @Test
    void genesisIsSeqOneOnTheEmptyPrevHashAndOnlyAuditTypesAreChained() {
        EventLog log = EventLog.create();
        log.emit(audit(1_000, "first"));
        log.emit(Event.builder(EventType.LOG).ts(1_001).message("a log line").build());
        log.emit(Event.builder(EventType.ACCESS_DENIED).ts(1_002).message("refused").build());
        List<Event> chain = assertChain(log.store(), 2);
        assertEquals("", AuditChain.prevHash(chain.get(0)));
        assertEquals(64, AuditChain.storedHash(chain.get(0)).length(), "SHA-256 hex");
        Event line = log.store().query(EventQuery.builder().type(EventType.LOG).limit(10).build()).get(0);
        assertNull(line.attributes().get(AuditAttrs.AUDIT_SEQ), "a LOG event is not part of the chain");
    }

    @Test
    void theCanonicalEncodingIsKeySortedWithExplicitNullsAndExcludesOnlyTheHash() {
        EventLog log = EventLog.create();
        log.emit(Event.builder(EventType.AUDIT).ts(5).message("m").attr("zeta", "1").attr("alpha", "2").build());
        String c = AuditChain.canonical(log.store().chainPage(1, 1).get(0));
        assertTrue(c.startsWith("{\"attributes\":{\"alpha\":\"2\",\"zeta\":\"1\"},\"correlationId\":null,"), c);
        assertTrue(c.contains("\"pipeline\":null"), "an absent field is written as null, never dropped: " + c);
        assertTrue(c.contains("\"prevHash\":\"\"") && c.contains("\"seq\":1"), c);
        assertFalse(c.contains(AuditAttrs.AUDIT_HASH), "the hash cannot cover itself: " + c);
        assertFalse(c.contains("\"" + AuditAttrs.AUDIT_SEQ + "\""), "seq is a field of its own, not an attribute: " + c);
    }

    @Test
    void aCallerCannotSupplyItsOwnChainAttributes() {
        EventLog log = EventLog.create();
        log.emit(audit(1, "one"));
        log.emit(Event.builder(EventType.AUDIT).ts(2).message("spoof").attr(AuditAttrs.AUDIT_SEQ, 99)
                .attr(AuditAttrs.AUDIT_PREV_HASH, "x").attr(AuditAttrs.AUDIT_HASH, "y").build());
        List<Event> chain = assertChain(log.store(), 2);
        assertEquals("spoof", chain.get(1).message());
    }

    @Test
    void aRecordWhoseClockReadsEarlierIsRaisedToItsPredecessorsTime() {
        EventLog log = EventLog.create();
        log.emit(audit(5_000, "later clock"));
        log.emit(audit(4_000, "earlier clock, linked second"));
        List<Event> chain = assertChain(log.store(), 2);
        assertEquals(5_000, chain.get(1).ts());
    }

    @Test
    void theChainContinuesAcrossARestart(@TempDir Path dir) {
        try (ParquetEventStore store = new ParquetEventStore(dir, 1000, 0, 100)) {
            EventLog log = EventLog.create();
            log.installStore(store);
            for (int i = 0; i < 5; i++) log.emit(audit(1_000 + i, "before " + i));
        }
        try (ParquetEventStore store = new ParquetEventStore(dir, 1000, 0, 100)) {
            EventLog log = EventLog.create();
            log.installStore(store);
            for (int i = 0; i < 5; i++) log.emit(audit(2_000 + i, "after " + i));
            List<Event> chain = assertChain(store, 10);
            assertEquals("after 0", chain.get(5).message(), "seq 6 is the first record of the second run");
        }
    }

    /** A hard kill (no close) leaves the linked rows in the journal only; the replay must not re-link them. */
    @Test
    void aJournalReplayAfterAHardKillNeitherForksNorDuplicatesTheChain(@TempDir Path dir) {
        ParquetEventStore crashed = new ParquetEventStore(dir, 1000, 0, 100);   // never flushes, never closed
        EventLog before = EventLog.create();
        before.installStore(crashed);
        for (int i = 0; i < 4; i++) before.emit(audit(1_000 + i, "buffered " + i));

        try (ParquetEventStore reopened = new ParquetEventStore(dir, 1000, 0, 100)) {
            EventLog after = EventLog.create();
            after.installStore(reopened);
            for (int i = 0; i < 3; i++) after.emit(audit(2_000 + i, "after " + i));
            List<Event> chain = assertChain(reopened, 7);
            assertEquals("buffered 3", chain.get(3).message());
            assertEquals("after 0", chain.get(4).message(), "the next record continues after the replayed head");
        }
    }

    /** A kill between a flush landing and its journal truncate leaves events that are ALREADY in Parquet in the
     *  journal too. Replayed blindly, every one of them would be stored twice: a duplicated seq. */
    @Test
    void aJournalOfAlreadyFlushedEventsIsNotReplayedTwice(@TempDir Path dir) throws Exception {
        byte[] journal;
        try (ParquetEventStore store = new ParquetEventStore(dir, 1000, 0, 100)) {
            EventLog log = EventLog.create();
            log.installStore(store);
            for (int i = 0; i < 3; i++) log.emit(audit(1_000 + i, "e" + i));
            journal = Files.readAllBytes(dir.resolve(ParquetEventStore.JOURNAL));
            store.flush();
        }
        assertTrue(journal.length > 0);
        Files.write(dir.resolve(ParquetEventStore.JOURNAL), journal);   // the truncate "never happened"
        try (ParquetEventStore reopened = new ParquetEventStore(dir, 1000, 0, 100)) {
            assertChain(reopened, 3);
            assertEquals(3, reopened.count(), "no event stored twice");
        }
    }

    /** Audit rows emitted before the durable store is installed are re-linked onto ITS head, not appended with
     *  seqs its history already holds. */
    @Test
    void aStoreSwapRelinksTheCarriedAuditRowsOntoTheIncomingChain(@TempDir Path dir) {
        try (ParquetEventStore store = new ParquetEventStore(dir, 1000, 0, 100)) {
            EventLog log = EventLog.create();
            log.installStore(store);
            for (int i = 0; i < 3; i++) log.emit(audit(1_000 + i, "history " + i));
        }
        EventLog log = EventLog.create();                 // starts on its bootstrap in-memory store
        log.emit(audit(2_000, "emitted at startup"));
        try (ParquetEventStore store = new ParquetEventStore(dir, 1000, 0, 100)) {
            log.installStore(store);
            log.emit(audit(3_000, "after the swap"));
            List<Event> chain = assertChain(store, 5);
            assertEquals("emitted at startup", chain.get(3).message());
        }
    }

    /** The JDBC store has no chain reads of its own; the EventStore defaults (a keyset walk) serve it. */
    @Test
    void theChainContinuesAcrossARestartOnTheDbStoreThroughTheDefaultReads(@TempDir Path dir) throws Exception {
        String url = "jdbc:duckdb:" + dir.resolve("events.db").toString().replace('\\', '/');
        try (DbEventStore store = DbEventStore.open(url, null, null)) {
            EventLog log = EventLog.create();
            log.installStore(store);
            for (int i = 0; i < 3; i++) log.emit(audit(1_000 + i, "before " + i));
        }
        try (DbEventStore store = DbEventStore.open(url, null, null)) {
            EventLog log = EventLog.create();
            log.installStore(store);
            for (int i = 0; i < 2; i++) log.emit(audit(2_000 + i, "after " + i));
            assertChain(store, 5);
        }
    }

    @Test
    void manyThreadsAuditingProduceOneTotalOrder(@TempDir Path dir) throws Exception {
        int threads = 8;
        int each = 50;
        try (ParquetEventStore store = new ParquetEventStore(dir, 64, 0, 100)) {   // flushes as it goes
            EventLog log = EventLog.create();
            log.installStore(store);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch go = new CountDownLatch(1);
            for (int t = 0; t < threads; t++) {
                int id = t;
                pool.submit(() -> {
                    go.await();
                    for (int i = 0; i < each; i++) log.emit(audit(System.currentTimeMillis(), "t" + id + "-" + i));
                    return null;
                });
            }
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
            List<Event> chain = assertChain(store, threads * each);
            Set<String> messages = new HashSet<>();
            for (Event e : chain) messages.add(e.message());
            assertEquals(threads * each, messages.size(), "every emit is exactly one record");
        }
    }

    @Test
    void aPayloadHashesTheSameBeforeAndAfterTheParquetRoundTrip(@TempDir Path dir) {
        List<String> before = new ArrayList<>();
        try (ParquetEventStore store = new ParquetEventStore(dir, 1000, 0, 100)) {
            EventLog log = EventLog.create();
            log.installStore(store);
            log.emit(Event.builder(EventType.AUDIT).ts(1).message("p")
                    .payload(Map.of("ratio", 0.1, "whole", 1.0, "n", 7L, "nested", Map.of("b", 2, "a", List.of(3, 1))))
                    .build());
            before.add(AuditChain.storedHash(store.chainPage(1, 1).get(0)));
        }
        try (ParquetEventStore store = new ParquetEventStore(dir, 1000, 0, 100)) {
            Event back = assertChain(store, 1).get(0);   // read from Parquet: recomputes over the round-tripped payload
            assertEquals(before.get(0), AuditChain.storedHash(back));
        }
    }
}
