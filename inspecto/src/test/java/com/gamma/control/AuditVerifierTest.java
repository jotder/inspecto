package com.gamma.control;

import com.gamma.spi.auth.ApiException;
import com.gamma.audit.AuditAttrs;
import com.gamma.audit.AuditChain;
import com.gamma.audit.Event;
import com.gamma.event.EventLog;
import com.gamma.audit.EventType;
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
            // anchors not REQUIRED here: most tests work on a fixed past day; anchor-missing has its own test
            return AuditVerifier.verify(store, AuditAnchors.readFile(s.config()), from, to, max, e -> true,
                    new AuditVerifier.Policy(LocalDate.now(java.time.ZoneOffset.UTC), false, null));
        }
    }

    private static void assertBad(AuditVerifier.Result r, long seq, String reason) {
        assertFalse(r.ok(), "expected " + reason + " at " + seq + ", got ok");
        assertEquals(reason, r.bad().reason(), r.bad().toString());
        assertEquals(seq, r.bad().seq(), r.bad().toString());
    }

    /** Anchor the fixed test day (2026-09-20) as the scheduled roll would on the day after it. */
    private static AuditAnchors.Anchor anchorNow(Space s) throws Exception {
        try (ParquetEventStore store = new ParquetEventStore(s.events(), 1000, 0, 100)) {
            AuditAnchors.roll(store, s.config(), LocalDate.of(2026, 9, 21));
        }
        List<AuditAnchors.Anchor> all = AuditAnchors.read(s.config());
        return all.get(all.size() - 1);
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
        byte[] started = Files.readAllBytes(AuditAnchors.startedFile(s.config()));
        Files.delete(AuditAnchors.startedFile(s.config()));   // as if this Space had never anchored
        assertTrue(verify(s).ok(), "without the anchor, a recomputed chain verifies — the documented limit");
        Files.write(anchorsFile, anchors);
        Files.write(AuditAnchors.startedFile(s.config()), started);
        assertBad(verify(s), 10, "anchor-mismatch");
    }

    // ── anchors: the daily roll and on demand ─────────────────────────────────────────────────────────────────

    @Test
    void theDailyRollAnchorsEachFinishedDayOnceAndNeverToday(@TempDir Path dir) throws Exception {
        Space s = space(dir);
        LocalDate d1 = LocalDate.of(2026, 9, 20);
        emit(s, T0, T0 + 1000, T0 + 2000,                             // day 1: seq 1..3
                T0 + DAY, T0 + DAY + 1, T0 + DAY + 2, T0 + DAY + 3,  // day 2: seq 4..7
                T0 + 2 * DAY, T0 + 2 * DAY + 1);                     // day 3: 8..9
        try (ParquetEventStore store = new ParquetEventStore(s.events(), 1000, 0, 100)) {
            assertEquals(1, AuditAnchors.roll(store, s.config(), d1.plusDays(1)), "day 1 closes on day 2");
            assertEquals(1, AuditAnchors.roll(store, s.config(), d1.plusDays(2)), "day 2 closes on day 3");
            assertEquals(0, AuditAnchors.roll(store, s.config(), d1.plusDays(2)), "a second roll writes nothing");
        }
        List<AuditAnchors.Anchor> a = AuditAnchors.read(s.config());
        assertEquals(List.of("2026-09-20", "2026-09-21"), a.stream().map(AuditAnchors.Anchor::day).toList());
        assertEquals(List.of(1L, 4L), a.stream().map(AuditAnchors.Anchor::firstSeq).toList());
        assertEquals(List.of(3L, 7L), a.stream().map(AuditAnchors.Anchor::lastSeq).toList());
        assertEquals(List.of(3L, 4L), a.stream().map(AuditAnchors.Anchor::count).toList());
        assertEquals("", a.get(0).prevAnchorMac(), "the first anchor starts the anchor chain");
        assertEquals(a.get(0).mac(), a.get(1).prevAnchorMac(), "each anchor carries the previous one's MAC");
        assertTrue(a.stream().allMatch(x -> x.macValid() && "daily".equals(x.kind())));
        assertTrue(verify(s).ok());
        assertEquals(2, verify(s).anchorsChecked());

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
        LocalDate d1 = LocalDate.of(2026, 9, 20);
        emit(s, T0, T0 + 1000, T0 + DAY, T0 + DAY + 1, T0 + DAY + 2);
        rewrite(s, all -> all.stream().filter(e -> seq(e) != 4).toList());   // a gap in day 2
        try (ParquetEventStore store = new ParquetEventStore(s.events(), 1000, 0, 100)) {
            assertEquals(1, AuditAnchors.roll(store, s.config(), d1.plusDays(1)));
            assertEquals(0, AuditAnchors.roll(store, s.config(), d1.plusDays(2)), "day 2 holds the break");
        }
        List<AuditAnchors.Anchor> a = AuditAnchors.read(s.config());
        assertEquals(1, a.size());
        assertEquals(2, a.get(0).lastSeq(), "day 1 only");
    }

    @Test
    void onDemandIsRateLimitedAndRefusesToSignABrokenChain(@TempDir Path dir) throws Exception {
        Space s = space(dir);
        long now = System.currentTimeMillis();
        emit(s, now - 2000, now - 1000);
        try (ParquetEventStore store = new ParquetEventStore(s.events(), 1000, 0, 100)) {
            AuditAnchors.resetRateLimit(s.config());
            AuditAnchors.OnDemand first = AuditAnchors.onDemand(store, s.config());
            assertTrue(first.created());
            assertEquals("on-demand", first.anchor().kind());
            assertEquals(2, first.anchor().lastSeq());
            ApiException tooSoon = assertThrows(ApiException.class, () -> AuditAnchors.onDemand(store, s.config()));
            assertEquals(429, tooSoon.status);
            AuditAnchors.resetRateLimit(s.config());
            AuditAnchors.OnDemand again = AuditAnchors.onDemand(store, s.config());
            assertFalse(again.created(), "nothing chained since the last anchor");
        }
        emit(s, now);   // seq 3, so there is something new to refuse to sign
        rewrite(s, all -> all.stream().map(e -> seq(e) == 2 ? with(e, "forged", e.ts(), Map.of()) : e).toList());
        try (ParquetEventStore store = new ParquetEventStore(s.events(), 1000, 0, 100)) {
            AuditAnchors.resetRateLimit(s.config());
            ApiException refused = assertThrows(ApiException.class, () -> AuditAnchors.onDemand(store, s.config()));
            assertEquals(409, refused.status);
        }
    }

    // ── the anchor file is a chain too (independent verification, 2026-09-27) ──────────────────────────────

    /** Two daily anchors over days 1 and 2 of ten records (1..5, 6..10). */
    private static Space twoAnchoredDays(Path dir) throws Exception {
        Space s = space(dir);
        long[] ts = new long[10];
        for (int i = 0; i < 10; i++) ts[i] = (i < 5 ? T0 : T0 + DAY) + i * 1000L;
        emit(s, ts);
        try (ParquetEventStore store = new ParquetEventStore(s.events(), 1000, 0, 100)) {
            AuditAnchors.roll(store, s.config(), LocalDate.of(2026, 9, 21));
            AuditAnchors.roll(store, s.config(), LocalDate.of(2026, 9, 22));
        }
        assertEquals(2, AuditAnchors.read(s.config()).size());
        return s;
    }

    @Test
    void aDeletedAnchorBreaksTheAnchorChain(@TempDir Path dir) throws Exception {
        Space s = twoAnchoredDays(dir);
        emit(s, T0 + 2 * DAY, T0 + 2 * DAY + 1);                         // day 3: seq 11..12
        try (ParquetEventStore store = new ParquetEventStore(s.events(), 1000, 0, 100)) {
            AuditAnchors.roll(store, s.config(), LocalDate.of(2026, 9, 23));
        }
        Path f = AuditAnchors.file(s.config());
        List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        assertEquals(3, lines.size());
        // the MIDDLE anchor removed: the first and latest the anchoring record names are still there
        Files.write(f, List.of(lines.get(0), lines.get(2)), StandardCharsets.UTF_8);
        assertBad(verify(s), 12, "anchor-chain-broken");
    }

    @Test
    void aGarbledAnchorLineIsAFailureNotASkip(@TempDir Path dir) throws Exception {
        Space s = twoAnchoredDays(dir);
        Path f = AuditAnchors.file(s.config());
        List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        Files.write(f, List.of(lines.get(0), "{not json"), StandardCharsets.UTF_8);
        assertBad(verify(s), 0, "anchor-unreadable");
        try (ParquetEventStore store = new ParquetEventStore(s.events(), 1000, 0, 100)) {
            AuditAnchors.resetRateLimit(s.config());
            assertEquals(409, assertThrows(ApiException.class, () -> AuditAnchors.onDemand(store, s.config())).status,
                    "nothing is signed over a broken anchor file");
        }
    }

    /** Deleting the whole file must not let the next anchor quietly re-sign history from genesis. */
    @Test
    void aMissingAnchorFileIsNeverSilentlyReSigned(@TempDir Path dir) throws Exception {
        Space s = twoAnchoredDays(dir);
        Files.delete(AuditAnchors.file(s.config()));
        try (ParquetEventStore store = new ParquetEventStore(s.events(), 1000, 0, 100)) {
            AuditAnchors.resetRateLimit(s.config());
            assertEquals(409, assertThrows(ApiException.class, () -> AuditAnchors.onDemand(store, s.config())).status);
            assertThrows(ApiException.class, () -> AuditAnchors.roll(store, s.config(), LocalDate.of(2026, 9, 25)));
        }
        assertFalse(Files.exists(AuditAnchors.file(s.config())));
    }

    @Test
    void aFinishedDayWithNoAnchorFailsWhenAnchorsAreExpected(@TempDir Path dir) throws Exception {
        Space s = space(dir);
        emit(s, T0, T0 + 1000, T0 + DAY, T0 + DAY + 1);
        try (ParquetEventStore store = new ParquetEventStore(s.events(), 1000, 0, 100)) {
            AuditAnchors.roll(store, s.config(), LocalDate.of(2026, 9, 21));   // day 1 only
            AuditVerifier.Result r = AuditVerifier.verify(store, AuditAnchors.readFile(s.config()), null, null,
                    Long.MAX_VALUE, e -> true, new AuditVerifier.Policy(LocalDate.of(2026, 9, 25), true, null));
            assertBad(r, 3, "anchor-missing");
        }
    }

    @Test
    void anAnchoredDayCutShortAtTheFrontIsTruncatedBeforeAnchor(@TempDir Path dir) throws Exception {
        Space s = twoAnchoredDays(dir);
        rewrite(s, all -> all.stream().filter(e -> seq(e) > 2).toList());   // seq 1..2 of day 1 gone, 3..5 remain
        assertBad(verify(s), 1, "truncated-before-anchor");
    }

    private static AuditVerifier.Result verifyWith(Space s, LocalDate configuredCutoff, boolean currentEpochOnly)
            throws Exception {
        try (ParquetEventStore store = new ParquetEventStore(s.events(), 1000, 0, 100)) {
            return AuditVerifier.verify(store, AuditAnchors.readFile(s.config()), null, null, Long.MAX_VALUE,
                    e -> true, new AuditVerifier.Policy(LocalDate.of(2026, 9, 22), false, configuredCutoff,
                            currentEpochOnly, configuredCutoff == null ? "none configured" : "event_prune retention_days=1"));
        }
    }

    /** A chained prune record, as EventPruneTask writes it. */
    private static void pruneRecord(Space s, long ts, LocalDate before) {
        pruneRecord(s, ts, before, "job", "retention");
    }

    private static void pruneRecord(Space s, long ts, LocalDate before, String source, String category) {
        try (ParquetEventStore store = new ParquetEventStore(s.events(), 1000, 0, 100)) {
            EventLog log = EventLog.create();
            log.installStore(store);
            log.emit(Event.builder(EventType.AUDIT).ts(ts).source(source).message("event_prune")
                    .actor("job:retention").action(AuditVerifier.PRUNE_ACTION).actionCategory(category)
                    .attr(AuditVerifier.PRUNE_BEFORE, before));
        }
    }

    /** Anyone can emit an AUDIT row NAMED events.pruned; only the prune task's own shape (source=job,
     *  actionCategory=retention) accounts for removed rows. */
    @Test
    void aRowMerelyNamedEventsPrunedIsNotAPrune(@TempDir Path dir) throws Exception {
        Space s = twoAnchoredDays(dir);
        rewrite(s, all -> all.stream().filter(e -> seq(e) > 5).toList());
        pruneRecord(s, T0 + DAY + 20_000, LocalDate.of(2026, 9, 21), "audit", "retention");
        pruneRecord(s, T0 + DAY + 21_000, LocalDate.of(2026, 9, 21), "job", "data_mutation");
        assertBad(verifyWith(s, null, false), 1, "truncated-before-anchor");
    }

    /** Only what a prune ACTUALLY removed — a chained prune record — accounts for anchored rows being gone. A
     *  {@code retention_days: 1} job that is configured but never ran accounts for nothing. */
    @Test
    void aRemovedAnchoredDayIsRetentionOnlyWhenAPruneRecordAccountsForIt(@TempDir Path dir) throws Exception {
        Space s = twoAnchoredDays(dir);
        rewrite(s, all -> all.stream().filter(e -> seq(e) > 5).toList());   // day 1 removed from the front
        AuditVerifier.Result configuredOnly = verifyWith(s, LocalDate.of(2026, 9, 21), false);
        assertBad(configuredOnly, 1, "truncated-before-anchor");
        @SuppressWarnings("unchecked") Map<String, Object> ret = (Map<String, Object>) configuredOnly.extra().get("retention");
        assertEquals("event_prune retention_days=1", ret.get("configuredSource"), "the cutoff's source is reported");

        pruneRecord(s, T0 + DAY + 20_000, LocalDate.of(2026, 9, 20));      // removed only days BEFORE day 1
        assertBad(verifyWith(s, null, false), 1, "truncated-before-anchor");
        pruneRecord(s, T0 + DAY + 30_000, LocalDate.of(2026, 9, 21));      // removed day 1
        AuditVerifier.Result pruned = verifyWith(s, null, false);
        assertTrue(pruned.ok(), String.valueOf(pruned.bad()));
        @SuppressWarnings("unchecked") Map<String, Object> r2 = (Map<String, Object>) pruned.extra().get("retention");
        assertEquals("prune-record", r2.get("source"));
        assertEquals("2026-09-21", r2.get("prunedBefore"));
    }

    // ── the durable "anchoring started" record ─────────────────────────────────────────────────────────────

    @Test
    void onceAnchoringHasStartedAMissingAnchorFileIsAlwaysRefused(@TempDir Path dir) throws Exception {
        Space s = space(dir);
        long now = System.currentTimeMillis();
        emit(s, now - 2000, now - 1000);                                  // young rows: the grace would apply
        try (ParquetEventStore store = new ParquetEventStore(s.events(), 1000, 0, 100)) {
            AuditAnchors.resetRateLimit(s.config());
            assertTrue(AuditAnchors.onDemand(store, s.config()).created());
        }
        assertTrue(Files.exists(AuditAnchors.startedFile(s.config())));
        Files.delete(AuditAnchors.file(s.config()));
        emit(s, now);
        assertBad(verify(s), 0, "anchor-file-missing");
        try (ParquetEventStore store = new ParquetEventStore(s.events(), 1000, 0, 100)) {
            AuditAnchors.resetRateLimit(s.config());
            assertEquals(409, assertThrows(ApiException.class, () -> AuditAnchors.onDemand(store, s.config())).status,
                    "the young-install grace ends with the first anchor");
        }
    }

    @Test
    void aTruncatedAnchorFileIsRefusedEvenWhenWhatRemainsChains(@TempDir Path dir) throws Exception {
        Space s = twoAnchoredDays(dir);
        Path f = AuditAnchors.file(s.config());
        Files.write(f, List.of(Files.readAllLines(f, StandardCharsets.UTF_8).get(0)), StandardCharsets.UTF_8);
        assertBad(verify(s), 0, "anchor-file-truncated");
    }

    // ── rebaseline: the acknowledged break ─────────────────────────────────────────────────────────────────

    private static AuditAnchors.Anchor rebaseline(Space s, String reason) throws Exception {
        try (ParquetEventStore store = new ParquetEventStore(s.events(), 1000, 0, 100)) {
            AuditAnchors.resetRebaselineLimit(s.config());
            return AuditAnchors.rebaseline(store, s.config(), reason,
                    new AuditVerifier.Policy(LocalDate.of(2026, 9, 22), false, null), "admin-1");
        }
    }

    /** A rebaseline is NEVER invisible: the default verify is not ok while any break exists — it is acknowledged,
     *  with the breaks listed; only ?epoch=current may report ok, and it still lists them. */
    @Test
    void aRebaselineIsASignedBreakThatVerifyNamesAndNeverReportsPlainOk(@TempDir Path dir) throws Exception {
        Space s = twoAnchoredDays(dir);
        assertEquals(409, assertThrows(ApiException.class, () -> rebaseline(s, "no reason to")).status,
                "a healthy chain is never rebaselined");
        Path f = AuditAnchors.file(s.config());
        Files.write(f, List.of(Files.readAllLines(f, StandardCharsets.UTF_8).get(0), "{garbled"),
                StandardCharsets.UTF_8);
        AuditAnchors.Anchor b = rebaseline(s, "disk fault on 2026-09-22, INC-42");
        assertEquals("break", b.kind());
        assertEquals(11, b.firstSeq(), "the new epoch starts after the head");
        assertEquals("disk fault on 2026-09-22, INC-42", b.extra().get("reason"));
        assertEquals("admin-1", b.extra().get("by"));
        assertTrue(String.valueOf(b.extra().get("problem")).startsWith("anchor-unreadable"), b.extra().toString());
        assertEquals(5L, ((Number) b.extra().get("lastGoodSeq")).longValue());

        AuditVerifier.Result whole = verifyWith(s, null, false);
        assertBad(whole, 11, "acknowledged-break");
        assertEquals(true, whole.extra().get("acknowledged"));
        assertEquals(11L, whole.extra().get("epochStart"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> breaks = (List<Map<String, Object>>) whole.extra().get("breaks");
        assertEquals(1, breaks.size());
        assertEquals("disk fault on 2026-09-22, INC-42", breaks.get(0).get("reason"));
        assertEquals("admin-1", breaks.get(0).get("by"));
        AuditVerifier.Result current = verifyWith(s, null, true);
        assertTrue(current.ok(), "the current epoch alone: " + current.bad());
        assertEquals(1, ((List<?>) current.extra().get("breaks")).size(), "…and still says so");
        assertBad(verify(s, 1L, null, Long.MAX_VALUE), 11, "acknowledged-break");

        // anchoring continues on the new epoch
        emit(s, T0 + 2 * DAY, T0 + 2 * DAY + 1);
        try (ParquetEventStore store = new ParquetEventStore(s.events(), 1000, 0, 100)) {
            assertEquals(1, AuditAnchors.roll(store, s.config(), LocalDate.of(2026, 9, 23)));
        }
        List<AuditAnchors.Anchor> a = AuditAnchors.read(s.config());
        assertEquals(b.mac(), a.get(1).prevAnchorMac());
        assertEquals(11, a.get(1).firstSeq());
        assertTrue(verifyWith(s, null, true).ok(), String.valueOf(verifyWith(s, null, true).bad()));
        assertFalse(verifyWith(s, null, false).ok());
    }

    /** The replaced anchor file is the prior epoch: kept, pinned by digest, and checked. */
    @Test
    void theReplacedAnchorFileIsVerifiedAsThePriorEpoch(@TempDir Path dir) throws Exception {
        Space s = twoAnchoredDays(dir);
        Path f = AuditAnchors.file(s.config());
        Files.write(f, List.of(Files.readAllLines(f, StandardCharsets.UTF_8).get(0), "{garbled"),
                StandardCharsets.UTF_8);
        AuditAnchors.Anchor b = rebaseline(s, "disk fault");
        Path replaced = f.resolveSibling(String.valueOf(b.extra().get("replacedFile")));
        assertTrue(Files.exists(replaced));
        Files.writeString(replaced, "edited\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        assertBad(verifyWith(s, null, true), 11, "prior-epoch-altered");
        Files.delete(replaced);
        assertBad(verifyWith(s, null, true), 11, "prior-epoch-missing");
    }

    @Test
    void anAuditRowWithoutALinkIsAHole(@TempDir Path dir) throws Exception {
        Space s = space(dir);
        emit(s, seconds(3));
        try (ParquetEventStore store = new ParquetEventStore(s.events(), 1000, 0, 100)) {
            store.append(Event.builder(EventType.AUDIT).ts(T0 + 10_000).message("slipped past the chain").build());
        }
        assertBad(verify(s), 0, "unlinked");
    }

    @Test
    void aCorruptFileInTheStoreIsReportedNotSkipped(@TempDir Path dir) throws Exception {
        Space s = space(dir);
        emit(s, seconds(3));
        Path day = Files.createDirectories(s.events().resolve("level=INFO/year=2026/month=09/day=20"));
        Files.writeString(day.resolve("planted.parquet"), "not parquet");
        AuditVerifier.Result r = verify(s);
        assertBad(r, 0, "unreadable-file");
        assertEquals(3, r.checked(), "the readable files were still verified");
    }

    @Test
    void oneEventStoredAtTwoSeqsIsADuplicateEvent(@TempDir Path dir) throws Exception {
        Space s = space(dir);
        emit(s, seconds(10));
        rewrite(s, all -> {
            List<Event> out = new ArrayList<>();
            Event three = all.get(2);
            for (Event e : all) {
                if (seq(e) != 7) { out.add(e); continue; }
                Event clone = new Event(three.eventId(), e.ts(), three.level(), three.type(), three.source(),
                        three.pipeline(), three.correlationId(), three.message(), e.attributes(), three.payload());
                out.add(reseal(clone));
            }
            return out;
        });
        assertBad(verify(s), 7, "duplicate-event");
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
