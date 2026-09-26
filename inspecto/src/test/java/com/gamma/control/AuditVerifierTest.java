package com.gamma.control;

import com.gamma.event.AuditAttrs;
import com.gamma.event.AuditChain;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.event.ParquetEventStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ASSURE-AUDIT-CHAIN-1: {@code /audit/verify}'s walk ({@link AuditVerifier}) and the signed anchors
 * ({@link AuditAnchors}) against a REAL Parquet event store that each test tampers with directly — rewriting the
 * files under the store, never going through {@link EventLog} — and then asserts the walk names the right seq for
 * the right reason. The records are ten AUDIT events one second apart, seq 1..10.
 */
class AuditVerifierTest {

    /** 2026-09-20T10:00:00Z — a fixed past day, so the daily roll has finished days to anchor. */
    private static final long T0 = Instant.parse("2026-09-20T10:00:00Z").toEpochMilli();
    private static final long DAY = 86_400_000L;

    private record Space(Path events, Path config) {}

    private static Space space(Path dir) throws Exception {
        return new Space(dir.resolve("events"), Files.createDirectories(dir.resolve("config")));
    }

    /** Emit {@code n} audit events through a Space's EventLog at the given times; flushed and closed. */
    private static void emit(Space s, long... ts) {
        try (ParquetEventStore store = new ParquetEventStore(s.events(), 1000, 0, 100)) {
            EventLog log = EventLog.create();
            log.installStore(store);
            for (int i = 0; i < ts.length; i++)
                log.emit(Event.builder(EventType.AUDIT).ts(ts[i]).source("audit").message("act " + i)
                        .actor("alice").action("pipeline.updated").target("pipeline", "p" + i));
        }
    }

    private static long[] seconds(int n) {
        long[] ts = new long[n];
        for (int i = 0; i < n; i++) ts[i] = T0 + i * 1000L;
        return ts;
    }

    private static AuditVerifier.Result verify(Space s) throws Exception {
        return verify(s, null, null, Long.MAX_VALUE);
    }

    private static AuditVerifier.Result verify(Space s, Long from, Long to, long max) throws Exception {
        try (ParquetEventStore store = new ParquetEventStore(s.events(), 1000, 0, 100)) {
            return AuditVerifier.verify(store, AuditAnchors.read(s.config()), from, to, max, e -> true);
        }
    }

    private static void assertBad(AuditVerifier.Result r, long seq, String reason) {
        assertFalse(r.ok(), "expected " + reason + " at " + seq + ", got ok");
        assertEquals(reason, r.bad().reason(), r.bad().toString());
        assertEquals(seq, r.bad().seq(), r.bad().toString());
    }

    private static AuditAnchors.Anchor anchorNow(Space s) throws Exception {
        try (ParquetEventStore store = new ParquetEventStore(s.events(), 1000, 0, 100)) {
            return AuditAnchors.onDemand(store, s.config()).anchor();
        }
    }

    // ── tampering, directly on the store ──────────────────────────────────────────────────────────────────────

    /** Rewrite the stored chain: read every record, transform the list, delete every Parquet file, and write the
     *  result straight into the store's files ({@code append} + {@code flush} — no EventLog, so no linking). */
    private static void rewrite(Space s, UnaryOperator<List<Event>> tamper) throws Exception {
        List<Event> all;
        try (ParquetEventStore store = new ParquetEventStore(s.events(), 1000, 0, 100)) {
            all = new ArrayList<>(store.chainPage(1, 100_000));
        }
        deleteParquet(s.events());
        try (ParquetEventStore store = new ParquetEventStore(s.events(), 1000, 0, 100)) {
            for (Event e : tamper.apply(all)) store.append(e);
        }
    }

    private static void deleteParquet(Path events) throws Exception {
        try (Stream<Path> w = Files.walk(events)) {
            for (Path p : w.filter(p -> p.toString().endsWith(".parquet")).toList()) Files.delete(p);
        }
    }

    private static long seq(Event e) {
        return AuditChain.seq(e);
    }

    /** A copy of {@code e} with some attributes and/or its message changed; its chain attributes as given. */
    private static Event with(Event e, String message, long ts, Map<String, String> attrChanges) {
        Map<String, String> a = new LinkedHashMap<>(e.attributes());
        a.putAll(attrChanges);
        return new Event(e.eventId(), ts, e.level(), e.type(), e.source(), e.pipeline(), e.correlationId(), message,
                a, e.payload());
    }

    /** Re-seal {@code e}: its audit_hash recomputed over its current fields — what a forger without the key can do. */
    private static Event reseal(Event e) {
        return with(e, e.message(), e.ts(), Map.of(AuditAttrs.AUDIT_HASH, AuditChain.hash(e)));
    }

    // ── the tests ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void anUntouchedChainVerifiesFromGenesis(@TempDir Path dir) throws Exception {
        Space s = space(dir);
        emit(s, seconds(10));
        AuditVerifier.Result r = verify(s);
        assertTrue(r.ok(), String.valueOf(r.bad()));
        assertEquals(10, r.checked());
        assertTrue(r.fromGenesis());
        assertEquals(10, r.lastSeq());
        assertTrue(r.complete());
    }

    /** Edited at the FILE level with DuckDB: the message of seq 5 is rewritten in place of the store's own files. */
    @Test
    void anEditedFieldIsAHashMismatchAtThatSeq(@TempDir Path dir) throws Exception {
        Space s = space(dir);
        emit(s, seconds(10));
        String root = s.events().toString().replace('\\', '/');
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE t AS SELECT event_id, ts_ms, type, source, pipeline, correlation_id, message, "
                    + "attributes, payload FROM read_parquet('" + root + "/**/*.parquet', hive_partitioning=true)");
            st.execute("UPDATE t SET message = 'alice pipeline.updated p-FORGED' "
                    + "WHERE json_extract_string(attributes, '$.audit_seq') = '5'");
            deleteParquet(s.events());
            Path day = Files.createDirectories(s.events().resolve("level=INFO/year=2026/month=09/day=20"));
            st.execute("COPY t TO '" + day.toString().replace('\\', '/') + "/tampered.parquet' (FORMAT PARQUET)");
        }
        assertBad(verify(s), 5, "hash-mismatch");
    }

    @Test
    void aDeletedRecordIsAGapAtItsSeq(@TempDir Path dir) throws Exception {
        Space s = space(dir);
        emit(s, seconds(10));
        rewrite(s, all -> all.stream().filter(e -> seq(e) != 5).toList());
        assertBad(verify(s), 5, "gap");
    }

    /** Two records trade places in the chain: each takes the other's seq, keeping its own time and content. */
    @Test
    void twoSwappedRecordsAreAReorder(@TempDir Path dir) throws Exception {
        Space s = space(dir);
        emit(s, seconds(10));
        rewrite(s, all -> all.stream().map(e -> seq(e) == 5 ? with(e, e.message(), e.ts(), Map.of(AuditAttrs.AUDIT_SEQ, "6"))
                : seq(e) == 6 ? with(e, e.message(), e.ts(), Map.of(AuditAttrs.AUDIT_SEQ, "5")) : e).toList());
        assertBad(verify(s), 5, "reorder");
    }

    /** A forged record claiming seq 5, sealed with a correctly recomputed hash and the genuine prevHash. */
    @Test
    void anInsertedForgedRecordIsADuplicateSeq(@TempDir Path dir) throws Exception {
        Space s = space(dir);
        emit(s, seconds(10));
        rewrite(s, all -> {
            List<Event> out = new ArrayList<>(all);
            Event five = all.get(4);
            Event forged = new Event("forged-" + five.eventId(), five.ts(), five.level(), five.type(), five.source(),
                    five.pipeline(), five.correlationId(), "mallory pipeline.deleted p-everything",
                    five.attributes(), five.payload());
            out.add(reseal(forged));
            return out;
        });
        assertBad(verify(s), 5, "duplicate");
    }

    /** seq 5 REPLACED by a self-consistent forgery (its own hash recomputed): 5 passes on its own, 6 no longer
     *  links onto it. */
    @Test
    void aReplacedRecordBreaksTheNextLink(@TempDir Path dir) throws Exception {
        Space s = space(dir);
        emit(s, seconds(10));
        rewrite(s, all -> all.stream().map(e -> seq(e) == 5 ? reseal(with(e, "forged", e.ts(), Map.of())) : e).toList());
        assertBad(verify(s), 6, "broken-link");
    }

    /** Anchored through seq 10, then seqs 8..10 deleted: the store is a valid chain prefix, but the anchor says more. */
    @Test
    void aTailTruncatedAfterAnAnchorIsMissing(@TempDir Path dir) throws Exception {
        Space s = space(dir);
        emit(s, seconds(10));
        assertEquals(10, anchorNow(s).lastSeq());
        rewrite(s, all -> all.stream().filter(e -> seq(e) < 8).toList());
        assertBad(verify(s), 8, "missing");
    }

    @Test
    void anEditedAnchorIsAnAnchorMismatch(@TempDir Path dir) throws Exception {
        Space s = space(dir);
        emit(s, seconds(10));
        AuditAnchors.Anchor a = anchorNow(s);
        assertTrue(verify(s).ok());
        Path f = AuditAnchors.file(s.config());
        String line = Files.readString(f, StandardCharsets.UTF_8);
        // The edit keeps the anchor internally consistent (its hash, seqs and count still agree with the chain) —
        // only the MAC can see it.
        assertTrue(line.contains("\"day\":\"2026-09-20\""), line);
        Files.writeString(f, line.replace("\"day\":\"2026-09-20\"", "\"day\":\"2026-09-19\""), StandardCharsets.UTF_8);
        assertBad(verify(s), a.lastSeq(), "anchor-mismatch");
        assertFalse(AuditAnchors.read(s.config()).get(0).macValid());
    }

    /** SHA-256 needs no key: an attacker who edits seq 5 and recomputes every later link leaves a chain that is
     *  internally perfect. Only the MAC'd anchor, which the attacker cannot re-sign, still names the old hash. */
    @Test
    void aChainRewrittenEndToEndIsCaughtOnlyByTheAnchor(@TempDir Path dir) throws Exception {
        Space s = space(dir);
        emit(s, seconds(10));
        Path anchorsFile = AuditAnchors.file(s.config());
        anchorNow(s);
        byte[] anchors = Files.readAllBytes(anchorsFile);
        rewrite(s, all -> {
            List<Event> out = new ArrayList<>();
            String prev = null;
            for (Event e : all) {
                Event x = seq(e) == 5 ? with(e, "forged", e.ts(), Map.of()) : e;
                if (seq(e) >= 5) {
                    if (prev != null) x = with(x, x.message(), x.ts(), Map.of(AuditAttrs.AUDIT_PREV_HASH, prev));
                    x = reseal(x);
                }
                out.add(x);
                prev = AuditChain.storedHash(x);
            }
            return out;
        });
        Files.delete(anchorsFile);
        assertTrue(verify(s).ok(), "without the anchor, a recomputed chain verifies — the documented limit");
        Files.write(anchorsFile, anchors);
        assertBad(verify(s), 10, "anchor-mismatch");
    }

    // ── anchors: the daily roll and on demand ─────────────────────────────────────────────────────────────────

    @Test
    void theDailyRollAnchorsEachFinishedDayOnceAndNeverToday(@TempDir Path dir) throws Exception {
        Space s = space(dir);
        LocalDate d1 = LocalDate.of(2026, 9, 20);
        emit(s, T0, T0 + 1000, T0 + 2000,                             // day 1: seq 1..3
                T0 + DAY, T0 + DAY + 1, T0 + DAY + 2, T0 + DAY + 3,  // day 2: seq 4..7
                T0 + 2 * DAY, T0 + 2 * DAY + 1);                     // day 3 (the "today" of this roll): 8..9
        try (ParquetEventStore store = new ParquetEventStore(s.events(), 1000, 0, 100)) {
            assertEquals(2, AuditAnchors.roll(store, s.config(), d1.plusDays(2)));
            assertEquals(0, AuditAnchors.roll(store, s.config(), d1.plusDays(2)), "a second roll writes nothing");
        }
        List<AuditAnchors.Anchor> a = AuditAnchors.read(s.config());
        assertEquals(List.of("2026-09-20", "2026-09-21"), a.stream().map(AuditAnchors.Anchor::day).toList());
        assertEquals(List.of(1L, 4L), a.stream().map(AuditAnchors.Anchor::firstSeq).toList());
        assertEquals(List.of(3L, 7L), a.stream().map(AuditAnchors.Anchor::lastSeq).toList());
        assertEquals(List.of(3L, 4L), a.stream().map(AuditAnchors.Anchor::count).toList());
        assertTrue(a.stream().allMatch(x -> x.macValid() && "daily".equals(x.kind())));
        assertTrue(verify(s).ok());
        assertEquals(2, verify(s).anchorsChecked());

        // the day after: day 3 is finished too, and continues where day 2 stopped
        try (ParquetEventStore store = new ParquetEventStore(s.events(), 1000, 0, 100)) {
            assertEquals(1, AuditAnchors.roll(store, s.config(), d1.plusDays(3)));
        }
        AuditAnchors.Anchor third = AuditAnchors.read(s.config()).get(2);
        assertEquals(8, third.firstSeq());
        assertEquals(9, third.lastSeq());
    }

    @Test
    void theRollNeverAnchorsTheDayABreakIsIn(@TempDir Path dir) throws Exception {
        Space s = space(dir);
        emit(s, T0, T0 + 1000, T0 + DAY, T0 + DAY + 1, T0 + DAY + 2);
        rewrite(s, all -> all.stream().filter(e -> seq(e) != 4).toList());   // a gap in day 2
        try (ParquetEventStore store = new ParquetEventStore(s.events(), 1000, 0, 100)) {
            assertEquals(1, AuditAnchors.roll(store, s.config(), LocalDate.of(2026, 9, 25)));
        }
        List<AuditAnchors.Anchor> a = AuditAnchors.read(s.config());
        assertEquals(1, a.size());
        assertEquals(2, a.get(0).lastSeq(), "day 1 only");
    }

    @Test
    void onDemandRefusesToSignABrokenChainAndAnswersNothingNewWhenUpToDate(@TempDir Path dir) throws Exception {
        Space s = space(dir);
        long now = System.currentTimeMillis();
        emit(s, T0, T0 + 1000, now - 2000, now - 1000);   // a finished day, then today
        try (ParquetEventStore store = new ParquetEventStore(s.events(), 1000, 0, 100)) {
            AuditAnchors.OnDemand first = AuditAnchors.onDemand(store, s.config());
            assertTrue(first.created());
            assertEquals("on-demand", first.anchor().kind());
            assertEquals(3, first.anchor().firstSeq(), "after the daily anchor the roll wrote for the finished day");
            assertEquals(4, first.anchor().lastSeq());
            AuditAnchors.OnDemand again = AuditAnchors.onDemand(store, s.config());
            assertFalse(again.created(), "nothing chained since the last anchor");
            assertEquals(4, again.anchor().lastSeq());
        }
        assertEquals(List.of("daily", "on-demand"),
                AuditAnchors.read(s.config()).stream().map(AuditAnchors.Anchor::kind).toList());
        emit(s, now);   // seq 5, so there is something new to refuse to sign
        rewrite(s, all -> all.stream().map(e -> seq(e) == 4 ? with(e, "forged", e.ts(), Map.of()) : e).toList());
        ApiException refused = assertThrows(ApiException.class, () -> anchorNow(s));
        assertEquals(409, refused.status);
    }

    // ── range, bounds and retention ───────────────────────────────────────────────────────────────────────────

    @Test
    void aWalkIsBoundedAndResumable(@TempDir Path dir) throws Exception {
        Space s = space(dir);
        emit(s, seconds(10));
        AuditVerifier.Result first = verify(s, null, null, 4);
        assertTrue(first.ok());
        assertFalse(first.complete());
        assertEquals(5L, first.next());
        assertEquals(4, first.checked());
        AuditVerifier.Result rest = verify(s, first.next(), null, 100);
        assertTrue(rest.ok());
        assertFalse(rest.fromGenesis());
        assertEquals(6, rest.checked());
        // a middle range links onto the stored hash of the record before it — so an edit inside still shows
        rewrite(s, all -> all.stream().map(e -> seq(e) == 6 ? with(e, "forged", e.ts(), Map.of()) : e).toList());
        assertBad(verify(s, 4L, 7L, 100), 6, "hash-mismatch");
        assertTrue(verify(s, 7L, 10L, 100).ok(), "a range after the edit does not see it");
    }

    /** Retention drops whole old days, so the lowest retained seq is legitimately above 1. */
    @Test
    void aChainWhoseHeadWasPrunedVerifiesFromTheFirstRetainedRecord(@TempDir Path dir) throws Exception {
        Space s = space(dir);
        emit(s, seconds(10));
        rewrite(s, all -> all.stream().filter(e -> seq(e) > 3).toList());
        AuditVerifier.Result r = verify(s);
        assertTrue(r.ok(), String.valueOf(r.bad()));
        assertEquals(4, r.from());
        assertFalse(r.fromGenesis());
        assertEquals(7, r.checked());
    }

    /** A record from before the chain existed carries no seq: it is not part of the chain, so it cannot break it. */
    @Test
    void preChainRecordsAreOutsideTheChain(@TempDir Path dir) throws Exception {
        Space s = space(dir);
        try (ParquetEventStore store = new ParquetEventStore(s.events(), 1000, 0, 100)) {
            store.append(Event.builder(EventType.AUDIT).ts(T0 - 5000).message("legacy, unchained").build());
        }
        emit(s, seconds(3));
        AuditVerifier.Result r = verify(s);
        assertTrue(r.ok(), String.valueOf(r.bad()));
        assertEquals(3, r.checked());
        assertTrue(r.fromGenesis(), "the chain starts at the first new record, at seq 1");
    }
}
