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
import com.gamma.audit.AuditAttrs;
import com.gamma.audit.AuditChain;
import com.gamma.audit.Event;
import com.gamma.audit.EventLevel;
import com.gamma.audit.EventQuery;
import com.gamma.audit.EventStore;
import com.gamma.audit.EventType;

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
        crashed.simulateCrash();   // a killed process holds no lock and flushed nothing

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

    // ── security fixes (independent verification, 2026-09-27) ─────────────────────────────────────────────

    /** A store whose chain head cannot be read — what one corrupt file used to cause. */
    private static final class UnreadableHead implements EventStore {
        private final InMemoryEventStore d = new InMemoryEventStore();
        @Override public Event chainHead() { throw new IllegalStateException("head unreadable"); }
        @Override public void append(Event e) { d.append(e); }
        @Override public List<Event> query(EventQuery q) { return d.query(q); }
        @Override public List<Event> recent(int n) { return d.recent(n); }
        @Override public List<Event> page(int n, Long t, String id) { return d.page(n, t, id); }
    }

    @Test
    void aRowThatCannotBeLinkedIsMarkedAndAnnouncedNeverSilentlyUnchained() {
        EventLog log = EventLog.create();
        UnreadableHead store = new UnreadableHead();
        log.installStore(store);
        log.emit(audit(1_000, "while the head is unreadable"));
        Event row = store.query(EventQuery.builder().type(EventType.AUDIT).limit(10).build()).get(0);
        assertEquals("true", row.attributes().get(AuditAttrs.AUDIT_UNLINKED), "the row says it is off the chain");
        assertNull(row.attributes().get(AuditAttrs.AUDIT_SEQ));
        assertTrue(store.query(EventQuery.builder().minLevel(EventLevel.ERROR).limit(10).build()).stream()
                .anyMatch(e -> e.message().contains("UNLINKED")), "and an ERROR event says so");
        assertEquals(1, store.unlinkedSince(0), "the hole is countable");
    }

    /**
     * One corrupt file does not blind the chain — the readable rows still read — but a row cannot be LINKED past
     * it: its seqs are unknown, so the head may be in it, and linking onto the head that was seen would reuse
     * stored seqs (a fork). The row is refused onto the chain and stored marked unlinked, loudly. (Reversed
     * 2026-09-28 by security review of the per-file seq index; the first cut linked on regardless.)
     */
    @Test
    void oneCorruptParquetFileDoesNotBlindTheChainButRefusesALinkPastIt(@TempDir Path dir) throws Exception {
        try (ParquetEventStore store = new ParquetEventStore(dir, 1000, 0, 100)) {
            EventLog log = EventLog.create();
            log.installStore(store);
            for (int i = 0; i < 3; i++) log.emit(audit(1_000 + i, "e" + i));
        }
        Path day = Files.createDirectories(dir.resolve("level=INFO/year=1970/month=01/day=01"));
        Files.writeString(day.resolve("planted.parquet"), "not a parquet file");
        try (ParquetEventStore store = new ParquetEventStore(dir, 1000, 0, 100)) {
            EventLog log = EventLog.create();
            log.installStore(store);
            log.emit(audit(2_000, "after the plant"));
            assertChain(store, 3);
            Event refused = store.recent(10).stream().filter(e -> "after the plant".equals(e.message())).findFirst()
                    .orElseThrow();
            assertEquals("true", refused.attributes().get(AuditAttrs.AUDIT_UNLINKED));
            assertNull(refused.attributes().get(AuditAttrs.AUDIT_SEQ), "no seq reused");
            assertEquals(List.of("level=INFO/year=1970/month=01/day=01/planted.parquet"), store.unreadableUnits(),
                    "the bad file is reported, not skipped silently");
        }
    }

    /** The file holding the chain head goes unreadable; after a restart the next audit row is refused, not linked
     *  onto the lower head still visible — which would store a second row at seqs already taken. */
    @Test
    void anUnreadableHeadFileRefusesTheNextLinkInsteadOfForking(@TempDir Path dir) throws Exception {
        try (ParquetEventStore store = new ParquetEventStore(dir, 1000, 0, 100)) {
            EventLog log = EventLog.create();
            log.installStore(store);
            for (int i = 0; i < 3; i++) log.emit(audit(1_000 + i, "early" + i));
            store.flush();
            for (int i = 0; i < 3; i++) log.emit(audit(2_000 + i, "late" + i));   // seq 4..6, in a second file
        }
        Path headFile;
        try (var w = Files.walk(dir)) {
            headFile = w.filter(p -> p.toString().endsWith(".parquet")).sorted().toList().get(1);
        }
        Files.writeString(headFile, "the head file, destroyed");
        try (ParquetEventStore store = new ParquetEventStore(dir, 1000, 0, 100)) {
            EventLog log = EventLog.create();
            log.installStore(store);
            log.emit(audit(3_000, "after the restart"));
            List<Event> chain = store.chainPage(1, 100);
            assertEquals(List.of(1L, 2L, 3L), chain.stream().map(AuditChain::seq).toList(),
                    "seq 4 is not taken a second time");
            Event refused = store.recent(10).stream().filter(e -> "after the restart".equals(e.message()))
                    .findFirst().orElseThrow();
            assertEquals("true", refused.attributes().get(AuditAttrs.AUDIT_UNLINKED));
            assertEquals(1, store.unreadableUnits().size());
        }
    }

    @Test
    void aSecondChainWriterOnOneDirectoryIsRefusedLoudly(@TempDir Path dir) {
        try (ParquetEventStore first = new ParquetEventStore(dir, 1000, 0, 100);
             ParquetEventStore second = new ParquetEventStore(dir, 1000, 0, 100)) {
            EventLog a = EventLog.create();
            a.installStore(first);
            a.emit(audit(1_000, "first writer"));
            EventLog b = EventLog.create();
            b.installStore(second);
            b.emit(audit(1_001, "second writer"));
            Event refused = second.recent(10).stream().filter(e -> "second writer".equals(e.message())).findFirst()
                    .orElseThrow();
            assertEquals("true", refused.attributes().get(AuditAttrs.AUDIT_UNLINKED),
                    "the second writer never links a fork onto the same head");
        }
    }

    /** The lock guards a FILE: deleted while held, a second writer locks a NEW file at the same path. The first
     *  writer must notice before its next link and stop linking. */
    @Test
    void aDeletedWriterLockStopsTheFirstWriterBeforeASecondCanFork(@TempDir Path dir) throws Exception {
        try (ParquetEventStore first = new ParquetEventStore(dir, 1000, 0, 100);
             ParquetEventStore second = new ParquetEventStore(dir, 1000, 0, 100)) {
            EventLog a = EventLog.create();
            a.installStore(first);
            a.emit(audit(1_000, "first, locked"));
            Files.delete(dir.resolve(ParquetEventStore.WRITER_LOCK));   // mid-run
            EventLog b = EventLog.create();
            b.installStore(second);
            b.emit(audit(1_001, "second, on a new lock file"));
            a.emit(audit(1_002, "first, after its lock vanished"));
            Event late = first.recent(10).stream().filter(e -> e.message().startsWith("first, after")).findFirst()
                    .orElseThrow();
            assertEquals("true", late.attributes().get(AuditAttrs.AUDIT_UNLINKED),
                    "the first writer no longer links once the path no longer names its locked file");
        }
    }

    /** A Space restart re-installing onto its OWN directory must not re-link rows that directory already holds. */
    @Test
    void aCarriedRowTheIncomingStoreAlreadyHoldsIsNotRelinked(@TempDir Path dir) {
        EventLog log = EventLog.create();
        log.emit(audit(1_000, "one"));
        log.emit(audit(1_001, "two"));
        List<Event> linked = log.store().chainPage(1, 10);
        try (ParquetEventStore store = new ParquetEventStore(dir, 1000, 0, 100)) {
            for (Event e : linked) store.append(e);
            log.installStore(store);
            assertChain(store, 2);
        }
    }

    /** Normalisation is load-bearing: a BigDecimal 1.00 reads back from Parquet as 1.0, so the hash must be taken
     *  over the normalised form or every such row fails verify after a restart. */
    @Test
    void awkwardPayloadValuesVerifyAfterTheParquetRoundTrip(@TempDir Path dir) {
        try (ParquetEventStore store = new ParquetEventStore(dir, 1000, 0, 100)) {
            EventLog log = EventLog.create();
            log.installStore(store);
            log.emit(Event.builder(EventType.AUDIT).ts(1).message("p").payload(Map.of(
                    "amount", new java.math.BigDecimal("1.00"),
                    "nested", Map.of("z", List.of(1, "two", 3.5), "a", true),
                    "list", List.of(Map.of("k", 9L)),
                    "flag", Boolean.FALSE)).build());
        }
        try (ParquetEventStore store = new ParquetEventStore(dir, 1000, 0, 100)) {
            assertChain(store, 1);
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

    private static List<Path> parquetUnder(Path dir) throws Exception {
        try (var w = Files.walk(dir)) {
            return w.filter(p -> p.toString().endsWith(".parquet")).sorted().toList();
        }
    }

    /** ASSURE-AUDIT-CHAIN-RESIDUALS-1 (9) review: a HELD audit row carries a seq the chain head cannot see. Once
     *  the unreadable file is gone, a new row must not be linked onto the visible head — it would take the held
     *  row's seq and, when the hold is released, two rows would claim it. The hold blocks linking fail-closed. */
    @Test
    void aHeldAuditRowBlocksLinkingSoItsSeqIsNeverReused(@TempDir Path dir) throws Exception {
        ParquetEventStore crashed = new ParquetEventStore(dir, 1000, 0, 100);
        EventLog before = EventLog.create();
        before.installStore(crashed);
        before.emit(audit(1_000, "A"));                                    // linked as seq 1
        byte[] journal = Files.readAllBytes(dir.resolve(ParquetEventStore.JOURNAL));
        crashed.flush();
        crashed.simulateCrash();
        Files.write(dir.resolve(ParquetEventStore.JOURNAL), journal);    // the truncate never happened
        Path f = parquetUnder(dir).get(0);
        Files.writeString(f, "not a parquet file");

        try (ParquetEventStore reopened = new ParquetEventStore(dir, 1000, 0, 100)) {
            assertTrue(reopened.unreadableUnits().stream().anyMatch(u -> u.startsWith(ParquetEventStore.HELD)),
                    "the hold is reported, so verify names why seq 1 is missing");
            Files.delete(f);                                              // the unreadable file goes away
            reopened.rebuildChainIndex();
            EventLog after = EventLog.create();
            after.installStore(reopened);
            after.emit(audit(2_000, "B"));
            Event b = reopened.query(EventQuery.builder().type(EventType.AUDIT).limit(10).build()).stream()
                    .filter(e -> "B".equals(e.message())).findFirst().orElseThrow();
            assertEquals("true", b.attributes().get(AuditAttrs.AUDIT_UNLINKED), "B is not linked past the hold");
            assertNull(b.attributes().get(AuditAttrs.AUDIT_SEQ));
        }
        try (ParquetEventStore released = new ParquetEventStore(dir, 1000, 0, 100)) {
            assertTrue(released.unreadableUnits().isEmpty());
            List<Event> chain = released.chainPage(1, 100);
            assertEquals(List.of(1L), chain.stream().map(AuditChain::seq).toList(), "seq 1 exactly once: A");
            assertEquals("A", chain.get(0).message());
        }
    }
}
