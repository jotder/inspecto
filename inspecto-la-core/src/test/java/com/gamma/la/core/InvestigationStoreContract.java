package com.gamma.la.core;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static com.gamma.la.core.InvestigationEvaluator.canonical;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@link InvestigationStore} contract (design {@code docs/superpower/investigation-store-design.md} sections 4, 6 and 9),
 * written once so every implementation runs the SAME cases: {@code FsInvestigationStoreContractTest} today, the Postgres one
 * when it exists. A subclass supplies only {@link #fresh()}.
 *
 * <p>The cases that matter most are the ones an ordinary test would never notice a backend get wrong: the bytes come back
 * exactly as written (the sealed hashes are defined over them), a stale precondition writes NOTHING, and racing writers lose
 * none. Each of those has a mutation that must turn it red (recorded in the design doc, section 13.2).
 */
abstract class InvestigationStoreContract {

    /** A new, empty store (a fresh directory, a throwaway schema). */
    abstract InvestigationStore fresh() throws Exception;

    private static InvestigationStore.Scope main(String id) {
        return InvestigationStore.Scope.main(id);
    }

    private static String line(int step) {
        return "{\"kind\":\"op\",\"step\":" + step + ",\"workingSetHash\":\"sha256:" + "ab".repeat(32) + "\"}";
    }

    // ── identity ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void createIsWriteOnceAndHeaderComesBackVerbatim() throws Exception {
        InvestigationStore s = fresh();
        String header = "{\"id\":\"a\",\"title\":\"  café — テスト  \",\"zeta\":1,\"alpha\":2}";
        assertTrue(s.create("a", header));
        assertFalse(s.create("a", "{\"id\":\"a\",\"title\":\"replaced\"}"), "an existing id is never replaced");
        assertEquals(header, s.header("a").orElseThrow(), "header bytes: no re-encoding, no key reorder");
        assertTrue(s.header("nope").isEmpty());
        s.create("B", "{}");
        s.create("c", "{}");
        assertEquals(List.of("B", "a", "c"), s.ids(), "ids come back in code-point order");
    }

    @Test
    void forkIsAllOrNothingAndReadableBackStepByStep() throws Exception {
        InvestigationStore s = fresh();
        assertTrue(s.createFork("f", "{\"id\":\"f\"}", List.of(line(1), line(2)), List.of("{\"s\":1}", "{\"s\":2}")));
        assertEquals(List.of(line(1), line(2)), s.log(main("f")));
        assertEquals("{\"s\":2}", s.set("f", 2).orElseThrow());
        assertEquals(2, s.version(main("f")));
        assertFalse(s.createFork("f", "{\"id\":\"other\"}", List.of(line(1)), List.of("{}")), "an id that is taken refuses the fork");
        assertEquals("{\"id\":\"f\"}", s.header("f").orElseThrow());
        assertEquals(List.of(line(1), line(2)), s.log(main("f")), "the refused fork changed nothing");
    }

    // ── the sealed log ──────────────────────────────────────────────────────────────────────────────────

    @Test
    void appendIsDenseAndEveryByteComesBackAsWritten() throws Exception {
        InvestigationStore s = fresh();
        s.create("i", "{}");
        String big = "{\"k\":\"" + "x".repeat(1_000_000) + "\"}";
        List<String> lines = List.of(
                "{\"z\":1,\"a\":2}",                                   // reordered keys stay reordered
                "{ \"spaced\" :  [ 1 ,  2 ] }",                       // whitespace stays
                "{\"u\":\"café — テスト 😀\"}",   // non-ASCII, a surrogate pair
                "{\"nul\":\"a\\u0000b\"}",                            // the ASCII escape of a NUL
                big);
        for (int i = 0; i < lines.size(); i++) {
            assertEquals(i, s.version(main("i")));
            s.append(main("i"), i, i + 1, lines.get(i), "{\"set\":" + (i + 1) + "}");
        }
        assertEquals(lines.size(), s.version(main("i")));
        assertEquals(lines, s.log(main("i")), "the log is the written lines, in order, no blank, no re-encoding");
        for (int i = 1; i <= lines.size(); i++) assertEquals("{\"set\":" + i + "}", s.set("i", i).orElseThrow());
        assertTrue(s.set("i", lines.size() + 1).isEmpty(), "no set without its step");
    }

    @Test
    void sealedHashesSurviveTheRoundTrip() throws Exception {
        InvestigationStore s = fresh();
        s.create("h", "{}");
        InvestigationEvaluator.State state = new InvestigationEvaluator.State();
        state.entities.put("a", new InvestigationEvaluator.Entity("a", "msisdn", 0, "a", 1));
        state.entities.put("b", new InvestigationEvaluator.Entity("b", "msisdn", 1, "a", 1));
        state.links.put("a\u0000b\u0000call", new InvestigationEvaluator.Link("a", "b", "call", 3, 1));
        String hash = state.hash();
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("step", 1);
        doc.put("hash", hash);
        doc.put("workingSet", state.toMap());
        String setText = canonical(doc);
        String logLine = canonical(Map.of("step", 1, "kind", "op", "op", "seed", "workingSetHash", hash));
        s.append(main("h"), 0, 1, logLine, setText);

        // G1/G3: the log line carries the hash verbatim and the prefix hash is over line + "\n" exactly
        String readLine = s.log(main("h")).get(0);
        assertEquals(logLine, readLine);
        assertEquals(DraftStore.prefixHash(List.of(logLine), 1), DraftStore.prefixHash(s.log(main("h")), 1));
        assertEquals("sha256:" + sha256((logLine + "\n").getBytes(StandardCharsets.UTF_8)), DraftStore.prefixHash(s.log(main("h")), 1));
        // G2: the way DraftPromote.sealedSet re-hashes - the bytes between the head and the closing brace hash to the sealed hash
        String stored = s.set("h", 1).orElseThrow();
        String head = "{\"hash\":\"" + hash + "\",\"step\":1,\"workingSet\":";
        assertTrue(stored.startsWith(head) && stored.endsWith("}"), stored.substring(0, Math.min(80, stored.length())));
        String inner = stored.substring(head.length(), stored.length() - 1);
        assertEquals(hash, "sha256:" + sha256(inner.getBytes(StandardCharsets.UTF_8)), "the stored set re-hashes to its sealed hash");
        assertEquals(setText, stored);
    }

    @Test
    void aStaleVersionWritesNothing() throws Exception {
        InvestigationStore s = fresh();
        s.create("c", "{}");
        s.append(main("c"), 0, 1, line(1), "{\"set\":1}");
        InvestigationVersionConflictException lost = assertThrows(InvestigationVersionConflictException.class,
                () -> s.append(main("c"), 0, 1, line(99), "{\"set\":99}"));   // read at version 0, the log is at 1
        assertEquals(0, lost.expectedVersion());
        assertEquals(1, lost.actualVersion());
        assertEquals("c", lost.investigationId());
        assertThrows(InvestigationVersionConflictException.class, () -> s.append(main("c"), 2, 3, line(3), "{}"),
                "a version AHEAD of the log is as stale as one behind it");
        assertEquals(List.of(line(1)), s.log(main("c")), "the refused appends left the log as it was");
        assertEquals("{\"set\":1}", s.set("c", 1).orElseThrow(), "and did not touch the sealed set");
        assertTrue(s.set("c", 99).isEmpty());
        assertEquals(1, s.version(main("c")));
    }

    @Test
    void racingOneStepExactlyOneWriterWins() throws Exception {
        InvestigationStore s = fresh();
        s.create("r", "{}");
        int writers = 16;
        AtomicInteger won = new AtomicInteger(), lost = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int w = 0; w < writers; w++) {
                int me = w;
                fs.add(pool.submit(() -> {
                    go.await();
                    try {
                        s.append(main("r"), 0, 1, "{\"step\":1,\"by\":" + me + "}", "{\"by\":" + me + "}");
                        won.incrementAndGet();
                    } catch (InvestigationVersionConflictException e) {
                        lost.incrementAndGet();
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : fs) f.get();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, won.get(), "exactly one writer wins a step");
        assertEquals(writers - 1, lost.get());
        assertEquals(1, s.log(main("r")).size());
    }

    @Test
    void concurrentWritersThatRetryLoseNothingAndLeaveADenseLog() throws Exception {
        InvestigationStore s = fresh();
        s.create("d", "{}");
        int writers = 8, each = 25;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int w = 0; w < writers; w++) {
                int me = w;
                fs.add(pool.submit(() -> {
                    for (int n = 0; n < each; n++)
                        while (true) {   // the caller's loop: re-read the version, retry on a lost race
                            long v = s.version(main("d"));
                            try {
                                s.append(main("d"), v, (int) v + 1, "{\"step\":" + (v + 1) + ",\"w\":" + me + ",\"n\":" + n + "}", "{}");
                                break;
                            } catch (InvestigationVersionConflictException retry) {
                                // another writer got there first
                            }
                        }
                    return null;
                }));
            }
            for (Future<?> f : fs) f.get();
        } finally {
            pool.shutdownNow();
        }
        List<String> log = s.log(main("d"));
        assertEquals(writers * each, log.size(), "no write was lost");
        Set<String> who = new TreeSet<>();
        for (int i = 0; i < log.size(); i++) {
            assertTrue(log.get(i).startsWith("{\"step\":" + (i + 1) + ","), "steps are dense and in order: " + log.get(i));
            who.add(log.get(i).substring(log.get(i).indexOf("\"w\"")));
        }
        assertEquals(writers * each, who.size(), "no write was duplicated");
        for (int i = 1; i <= log.size(); i++) assertTrue(s.set("d", i).isPresent(), "every step has its set");
    }

    // ── members and references ──────────────────────────────────────────────────────────────────────────

    @Test
    void membersAreAppendOnlyAndConditionalOnWhatTheCallerRead() throws Exception {
        InvestigationStore s = fresh();
        s.create("m", "{}");
        assertEquals(List.of(), s.members("m"));
        s.appendMember("m", 0, "{\"seq\":1,\"op\":\"grant\"}");
        assertThrows(InvestigationVersionConflictException.class, () -> s.appendMember("m", 0, "{\"seq\":1,\"op\":\"revoke\"}"));
        s.appendMember("m", 1, "{\"seq\":2,\"op\":\"grant\"}");
        assertEquals(List.of("{\"seq\":1,\"op\":\"grant\"}", "{\"seq\":2,\"op\":\"grant\"}"), s.members("m"));
    }

    @Test
    void racingMemberAppendsExactlyOneWinsPerCount() throws Exception {
        InvestigationStore s = fresh();
        s.create("m", "{}");
        int writers = 12;
        AtomicInteger won = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int w = 0; w < writers; w++) {
                int me = w;
                fs.add(pool.submit(() -> {
                    go.await();
                    try {
                        s.appendMember("m", 0, "{\"seq\":1,\"by\":" + me + "}");
                        won.incrementAndGet();
                    } catch (InvestigationVersionConflictException lost) {
                        // lost
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : fs) f.get();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, won.get());
        assertEquals(1, s.members("m").size());
    }

    @Test
    void referencesRefuseDuplicatesAndTheCapEvenUnderARace() throws Exception {
        InvestigationStore s = fresh();
        s.create("x", "{}");
        assertEquals(InvestigationStore.Appended.ADDED, s.appendReference("x", "k1", "{\"key\":\"k1\",\"v\":1}", 3));
        assertEquals(InvestigationStore.Appended.DUPLICATE, s.appendReference("x", "k1", "{\"key\":\"k1\",\"v\":2}", 3));
        assertEquals(List.of("{\"key\":\"k1\",\"v\":1}"), s.references("x"));
        int writers = 10;
        Set<InvestigationStore.Appended> seen = Collections.newSetFromMap(new ConcurrentHashMap<>());
        AtomicInteger added = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int w = 0; w < writers; w++) {
                int me = w;
                fs.add(pool.submit(() -> {
                    go.await();
                    var r = s.appendReference("x", "n" + me, "{\"key\":\"n" + me + "\"}", 3);
                    seen.add(r);
                    if (r == InvestigationStore.Appended.ADDED) added.incrementAndGet();
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : fs) f.get();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(2, added.get(), "the cap of 3 (one already held) admits exactly two more");
        assertEquals(3, s.references("x").size());
        assertTrue(seen.contains(InvestigationStore.Appended.FULL));
    }

    // ── workflow records ────────────────────────────────────────────────────────────────────────────────

    @Test
    void workflowRecordsRoundTripAndCaseLinkIsRemovable() throws Exception {
        InvestigationStore s = fresh();
        s.create("w", "{}");
        assertTrue(s.pending("w", "p1").isEmpty());
        s.writePending("w", "p1", "{\"status\":\"pending\"}");
        s.writePending("w", "p1", "{\"status\":\"approved\"}");
        s.writePending("w", "p2", "{\"status\":\"pending\"}");
        assertEquals("{\"status\":\"approved\"}", s.pending("w", "p1").orElseThrow());
        assertEquals(List.of("{\"status\":\"approved\"}", "{\"status\":\"pending\"}"), s.listPending("w"));
        assertTrue(s.caseLink("w").isEmpty());
        s.writeCaseLink("w", "{\"caseRef\":\"c1\"}");
        assertEquals("{\"caseRef\":\"c1\"}", s.caseLink("w").orElseThrow());
        assertTrue(s.deleteCaseLink("w"));
        assertFalse(s.deleteCaseLink("w"));
        assertTrue(s.alertRuleBinding("w", "r").isEmpty());
        s.bindAlertRule("w", "r", "{\"rule\":\"r\"}");
        assertEquals("{\"rule\":\"r\"}", s.alertRuleBinding("w", "r").orElseThrow());
        assertTrue(s.createTemplate("t1", "{\"id\":\"t1\"}"));
        assertFalse(s.createTemplate("t1", "{\"id\":\"changed\"}"));
        assertEquals("{\"id\":\"t1\"}", s.template("t1").orElseThrow());
    }

    private static String sha256(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }
}
