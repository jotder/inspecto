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
import java.util.Optional;
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
 * The {@link InvestigationStore} contract (design {@code docs/archived-documents/plans-archive/investigation-store-design.md} sections 4, 6 and 9),
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

    /** A new, empty store whose per-set size limit ({@link WorkingSetSizeLimit}) is {@code bytes}. */
    abstract InvestigationStore freshWithSetLimit(long bytes) throws Exception;

    /** A new, empty store whose per-Investigation total set budget ({@link InvestigationSetBudget}) is {@code bytes}. */
    abstract InvestigationStore freshWithInvestigationBudget(long bytes) throws Exception;

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
        assertTrue(s.createTemplate("t0", "{\"id\":\"t0\"}"));
        assertEquals(List.of("{\"id\":\"t0\"}", "{\"id\":\"t1\"}"), s.templates(), "every template, raw, in id order");
        assertEquals("{\"id\":\"t1\"}", s.template("t1").orElseThrow());
    }

    // ── pending records: compare-and-set ────────────────────────────────────────────────────────────────

    @Test
    void replacePendingIsACompareAndSetAndExactlyOneRacerWins() throws Exception {
        InvestigationStore s = fresh();
        s.create("cas", "{}");
        assertFalse(s.replacePending("cas", "p1", "{\"status\":\"pending\"}", "{\"status\":\"approved\"}"), "an absent record is not replaced");
        s.writePending("cas", "p1", "{\"status\":\"pending\"}");
        assertFalse(s.replacePending("cas", "p1", "{\"status\":\"something else\"}", "{\"status\":\"approved\"}"));
        assertEquals("{\"status\":\"pending\"}", s.pending("cas", "p1").orElseThrow(), "a refused CAS wrote nothing");
        int deciders = 12;
        AtomicInteger won = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(deciders);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int d = 0; d < deciders; d++) {
                int me = d;
                fs.add(pool.submit(() -> {
                    go.await();
                    if (s.replacePending("cas", "p1", "{\"status\":\"pending\"}", "{\"status\":\"approved\",\"by\":" + me + "}")) won.incrementAndGet();
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : fs) f.get();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, won.get(), "one decision is made once");
        assertTrue(s.pending("cas", "p1").orElseThrow().startsWith("{\"status\":\"approved\""));
    }

    @Test
    void theMaskKeyIsMintedOnceAndStable() throws Exception {
        InvestigationStore s = fresh();
        s.create("mk", "{}");
        int callers = 8;
        Set<String> seen = Collections.newSetFromMap(new ConcurrentHashMap<>());
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int c = 0; c < callers; c++)
                fs.add(pool.submit(() -> {
                    go.await();
                    seen.add(HexFormat.of().formatHex(s.maskKey("mk")));
                    return null;
                }));
            go.countDown();
            for (Future<?> f : fs) f.get();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, seen.size(), "racing first callers all get the same key");
        assertEquals(32, s.maskKey("mk").length);
        assertEquals(seen.iterator().next(), HexFormat.of().formatHex(s.maskKey("mk")));
    }

    // ── Drafts ──────────────────────────────────────────────────────────────────────────────────────────

    private static String draftHeader(String id, String actor, int base) {
        return "{\"draftId\":\"" + id + "\",\"actor\":\"" + actor + "\",\"baseStep\":" + base + ",\"zeta\":1,\"alpha\":2}";
    }

    private static InvestigationStore.Scope draft(String inv, String id) {
        return InvestigationStore.Scope.draft(inv, id);
    }

    @Test
    void createDraftEnforcesOneLiveDraftPerActorTheSpaceCapAndWriteOnceIds() throws Exception {
        InvestigationStore s = fresh();
        s.create("a", "{}");
        s.create("b", "{}");
        String d1 = DraftStore.newId(), d2 = DraftStore.newId(), d3 = DraftStore.newId();
        var made = s.createDraft("a", d1, draftHeader(d1, "ann", 0), "ann", 2);
        assertEquals(InvestigationStore.DraftCreation.Created.CREATED, made.outcome());
        assertEquals(draftHeader(d1, "ann", 0), s.draftHeader("a", d1).orElseThrow(), "the header comes back verbatim");
        var again = s.createDraft("a", d2, draftHeader(d2, "ann", 0), "ann", 2);
        assertEquals(InvestigationStore.DraftCreation.Created.ACTOR_HAS_LIVE, again.outcome());
        assertEquals(d1, again.detail());
        assertTrue(s.draftHeader("a", d2).isEmpty(), "a refused create wrote nothing");
        assertEquals(InvestigationStore.DraftCreation.Created.CREATED, s.createDraft("a", d2, draftHeader(d2, "bob", 0), "bob", 2).outcome());
        var full = s.createDraft("b", d3, draftHeader(d3, "cy", 0), "cy", 2);
        assertEquals(InvestigationStore.DraftCreation.Created.SPACE_FULL, full.outcome(), "the cap counts across the Space's Investigations");
        assertEquals("2", full.detail());
        assertEquals(2, s.openDraftCount());
        assertEquals(InvestigationStore.DraftCreation.Created.ID_TAKEN, s.createDraft("a", d1, draftHeader(d1, "dee", 0), "dee", 9).outcome());
        assertEquals(List.of(d1, d2).stream().sorted().toList(), s.openDraftIds("a").stream().sorted().toList());
        assertEquals(InvestigationStore.DraftState.OPEN, s.draftState("a", d1));
    }

    @Test
    void aDraftRacingCreatorsNeverExceedTheCap() throws Exception {
        InvestigationStore s = fresh();
        s.create("a", "{}");
        int racers = 12, cap = 3;
        AtomicInteger created = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int r = 0; r < racers; r++) {
                String id = DraftStore.newId(), actor = "actor" + r;
                fs.add(pool.submit(() -> {
                    go.await();
                    if (s.createDraft("a", id, draftHeader(id, actor, 0), actor, cap).outcome() == InvestigationStore.DraftCreation.Created.CREATED)
                        created.incrementAndGet();
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : fs) f.get();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(cap, created.get(), "the cap is checked and the seat taken as one act");
        assertEquals(cap, s.openDraftCount());
    }

    @Test
    void aDraftLogAppendsDenselyAndAStaleVersionWritesNothing() throws Exception {
        InvestigationStore s = fresh();
        s.create("a", "{}");
        s.append(main("a"), 0, 1, line(1), "{\"set\":1}");
        String d = DraftStore.newId();
        s.createDraft("a", d, draftHeader(d, "ann", 1), "ann", 9);
        var sc = draft("a", d);
        assertEquals(0, s.version(sc), "a Draft's version counts its OWN entries");
        s.append(sc, 0, 2, "{ \"own\" : 2 }", "{\"set\":\"d2\"}");
        assertThrows(InvestigationVersionConflictException.class, () -> s.append(sc, 0, 3, "{\"own\":99}", "{}"));
        s.append(sc, 1, 3, "{\"own\":3}", "{\"set\":\"d3\"}");
        assertEquals(List.of("{ \"own\" : 2 }", "{\"own\":3}"), s.log(sc), "verbatim, in order");
        assertEquals("{\"set\":\"d2\"}", s.draftSet("a", d, 2).orElseThrow());
        assertEquals(List.of(line(1)), s.log(main("a")), "the main log is untouched");
    }

    @Test
    void racingDraftWritersLoseNothing() throws Exception {
        InvestigationStore s = fresh();
        s.create("a", "{}");
        String d = DraftStore.newId();
        s.createDraft("a", d, draftHeader(d, "ann", 0), "ann", 9);
        var sc = draft("a", d);
        int writers = 6, each = 15;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int w = 0; w < writers; w++) {
                int me = w;
                fs.add(pool.submit(() -> {
                    for (int n = 0; n < each; n++)
                        while (true) {
                            long v = s.version(sc);
                            try {
                                s.append(sc, v, (int) v + 1, "{\"step\":" + (v + 1) + ",\"w\":" + me + ",\"n\":" + n + "}", "{}");
                                break;
                            } catch (InvestigationVersionConflictException retry) {
                                // lost the race
                            }
                        }
                    return null;
                }));
            }
            for (Future<?> f : fs) f.get();
        } finally {
            pool.shutdownNow();
        }
        List<String> log = s.log(sc);
        assertEquals(writers * each, log.size());
        for (int i = 0; i < log.size(); i++) assertTrue(log.get(i).startsWith("{\"step\":" + (i + 1) + ","), log.get(i));
    }

    /** A main log of {@code n} steps and a Draft based on it holding {@code own} steps; returns the Draft id. */
    private String promotable(InvestigationStore s, String inv, int n, int own) throws Exception {
        s.create(inv, "{}");
        for (int i = 1; i <= n; i++) s.append(main(inv), i - 1, i, line(i), "{\"main\":" + i + "}");
        String d = DraftStore.newId();
        s.createDraft(inv, d, draftHeader(d, "ann", n), "ann", 99);
        for (int k = 1; k <= own; k++)
            s.append(draft(inv, d), k - 1, n + k, "{\"step\":" + (n + k) + ",\"own\":true}", "{\"draftSet\":" + (n + k) + "}");
        return d;
    }

    @Test
    void promoteAppendsToMainVerbatimSealsTheDraftsOwnSetsAndClosesTheDraft() throws Exception {
        InvestigationStore s = fresh();
        String d = promotable(s, "p", 2, 3);
        List<String> main = s.log(main("p"));
        List<String> own = s.log(draft("p", d));
        List<String> lines = List.of("{\"step\":3,\"promoted\":\"a\"}", "{\"step\":4,\"promoted\":\"b\"}", "{\"step\":5,\"promoted\":\"c\"}");
        List<String> sets = new ArrayList<>();
        sets.add(null);                       // step 3: the Draft's own sealed set, as it is
        sets.add("{\"fresh\":4}");            // step 4: a re-sealed set
        sets.add(null);
        s.promoteDraft("p", d, 2, DraftStore.prefixHash(main, 2), DraftStore.prefixHash(own, own.size()), lines, sets, "{\"promoted\":true}");
        assertEquals(List.of(line(1), line(2), lines.get(0), lines.get(1), lines.get(2)), s.log(main("p")), "verbatim, after the existing steps");
        assertEquals("{\"draftSet\":3}", s.set("p", 3).orElseThrow(), "a shared set is the Draft's bytes");
        assertEquals("{\"fresh\":4}", s.set("p", 4).orElseThrow());
        assertEquals("{\"draftSet\":5}", s.set("p", 5).orElseThrow());
        assertEquals("{\"main\":2}", s.set("p", 2).orElseThrow());
        assertEquals(InvestigationStore.DraftState.PROMOTED, s.draftState("p", d));
        assertEquals("{\"promoted\":true}", s.promoteMarker("p", d).orElseThrow());
        assertEquals(List.of(), s.log(draft("p", d)), "the Draft's evidence is gone: it lives in the main log now");
        assertEquals(List.of(), s.openDraftIds("p"));
        assertThrows(InvestigationStore.DraftClosedException.class,
                () -> s.promoteDraft("p", d, 5, DraftStore.prefixHash(s.log(main("p")), 5), DraftStore.prefixHash(List.of(), 0), List.of(), List.of(), "{}"),
                "a closed Draft cannot be promoted again");
    }

    @Test
    void promoteIsRefusedAndWritesNothingWhenEitherLogMovedUnderIt() throws Exception {
        InvestigationStore s = fresh();
        String d = promotable(s, "p", 2, 1);
        List<String> main = s.log(main("p"));
        List<String> own = s.log(draft("p", d));
        String mainHash = DraftStore.prefixHash(main, 2), ownHash = DraftStore.prefixHash(own, own.size());
        List<String> lines = List.of("{\"step\":3}");
        // main moved: another writer appended after the promote was computed
        s.append(main("p"), 2, 3, line(3), "{}");
        assertThrows(InvestigationVersionConflictException.class,
                () -> s.promoteDraft("p", d, 2, mainHash, ownHash, lines, java.util.Arrays.asList((String) null), "{}"));
        assertEquals(3, s.version(main("p")), "nothing was appended");
        assertEquals(InvestigationStore.DraftState.OPEN, s.draftState("p", d), "and the Draft is still open");
        // the Draft moved
        String m3 = DraftStore.prefixHash(s.log(main("p")), 3);
        s.append(draft("p", d), 1, 4, "{\"step\":4,\"own\":true}", "{}");
        assertThrows(InvestigationVersionConflictException.class,
                () -> s.promoteDraft("p", d, 3, m3, ownHash, List.of("{\"step\":4}"), List.of("{}"), "{}"));
        assertEquals(3, s.version(main("p")));
        // a main prefix whose bytes differ from what the caller hashed is as stale as a moved head
        assertThrows(InvestigationVersionConflictException.class,
                () -> s.promoteDraft("p", d, 3, "sha256:" + "00".repeat(32), DraftStore.prefixHash(s.log(draft("p", d)), 2),
                        List.of("{\"step\":4}"), List.of("{}"), "{}"));
        assertEquals(3, s.version(main("p")));
    }

    @Test
    void twoDraftsPromotedOntoOneMainExactlyOneWins() throws Exception {
        InvestigationStore s = fresh();
        s.create("p", "{}");
        s.append(main("p"), 0, 1, line(1), "{}");
        String d1 = DraftStore.newId(), d2 = DraftStore.newId();
        s.createDraft("p", d1, draftHeader(d1, "ann", 1), "ann", 99);
        s.createDraft("p", d2, draftHeader(d2, "bob", 1), "bob", 99);
        s.append(draft("p", d1), 0, 2, "{\"step\":2,\"by\":\"ann\"}", "{}");
        s.append(draft("p", d2), 0, 2, "{\"step\":2,\"by\":\"bob\"}", "{}");
        String mainHash = DraftStore.prefixHash(s.log(main("p")), 1);
        AtomicInteger won = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (String d : List.of(d1, d2))
                fs.add(pool.submit(() -> {
                    go.await();
                    try {
                        s.promoteDraft("p", d, 1, mainHash, DraftStore.prefixHash(s.log(draft("p", d)), 1),
                                List.of("{\"step\":2,\"from\":\"" + d + "\"}"), List.of("{}"), "{}");
                        won.incrementAndGet();
                    } catch (InvestigationVersionConflictException lost) {
                        // the other promote landed first: this Draft must rebase
                    }
                    return null;
                }));
            go.countDown();
            for (Future<?> f : fs) f.get();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, won.get());
        assertEquals(2, s.version(main("p")), "one step landed, not two");
    }

    @Test
    void replaceDraftSwapsHeaderLogAndSetsWithinItsPreconditions() throws Exception {
        InvestigationStore s = fresh();
        String d = promotable(s, "r", 2, 2);
        List<String> main = s.log(main("r"));
        List<String> own = s.log(draft("r", d));
        String mainHash = DraftStore.prefixHash(main, 2), ownHash = DraftStore.prefixHash(own, own.size());
        assertThrows(InvestigationVersionConflictException.class, () -> s.replaceDraft("r", d, 2, mainHash, "sha256:" + "11".repeat(32),
                "{\"new\":1}", List.of("{\"x\":1}"), List.of("{}"), List.of(3)));
        assertEquals(own, s.log(draft("r", d)), "a refused swap changed nothing");
        assertEquals(draftHeader(d, "ann", 2), s.draftHeader("r", d).orElseThrow());
        String newHeader = "{ \"draftId\":\"" + d + "\", \"actor\":\"ann\", \"baseStep\":2,\"rebases\":[1] }";
        s.replaceDraft("r", d, 2, mainHash, ownHash, newHeader, List.of("{\"step\":3,\"re\":true}"), List.of("{\"reset\":3}"), List.of(3));
        assertEquals(newHeader, s.draftHeader("r", d).orElseThrow(), "verbatim");
        assertEquals(List.of("{\"step\":3,\"re\":true}"), s.log(draft("r", d)));
        assertEquals("{\"reset\":3}", s.draftSet("r", d, 3).orElseThrow());
        assertTrue(s.draftSet("r", d, 4).isEmpty(), "the old set is gone");
        assertEquals(InvestigationStore.DraftState.OPEN, s.draftState("r", d));
    }

    // ── the per-set size limit (D-IS2; operator 2026-10-10): at the limit passes, one byte over is refused 413 and writes nothing ──

    private static final long LIMIT = 1024;

    /** {@code bytes} UTF-8 bytes of valid JSON-ish text; {@code multibyte} spends two bytes per char ("é"), so chars != bytes. */
    private static String setOf(long bytes, boolean multibyte) {
        StringBuilder b = new StringBuilder();
        if (multibyte) {
            b.append("\u00e9".repeat((int) (bytes / 2)));
            if (bytes % 2 == 1) b.append('x');
        } else {
            b.append("x".repeat((int) bytes));
        }
        String s = b.toString();
        assertEquals(bytes, s.getBytes(StandardCharsets.UTF_8).length);
        return s;
    }

    private static void assertTooLarge(org.junit.jupiter.api.function.Executable write) {
        com.gamma.spi.auth.ApiException e = assertThrows(com.gamma.spi.auth.ApiException.class, write);
        assertEquals(413, e.status);
        assertEquals(com.gamma.spi.auth.ErrorCodes.PAYLOAD_TOO_LARGE, e.errorCode);
        assertTrue(e.getMessage().contains(String.valueOf(LIMIT)), "the message states the limit: " + e.getMessage());
        assertTrue(e.getMessage().contains("max_set_bytes"), "the message says how to raise it: " + e.getMessage());
    }

    @Test
    void appendAcceptsASetExactlyAtTheLimitAndRefusesOneByteOverCountingBytesNotChars() throws Exception {
        InvestigationStore s = freshWithSetLimit(LIMIT);
        s.create("a", "{}");
        for (boolean multibyte : new boolean[] {false, true}) {
            int step = multibyte ? 2 : 1;
            String over = setOf(LIMIT + 1, multibyte), atLimit = setOf(LIMIT, multibyte);
            assertTooLarge(() -> s.append(main("a"), step - 1, step, line(step), over));
            assertEquals(step - 1, s.version(main("a")), "a refused set writes no log line");
            assertTrue(s.set("a", step).isEmpty(), "a refused set is never stored, not even cut");
            s.append(main("a"), step - 1, step, line(step), atLimit);
            assertEquals(atLimit, s.set("a", step).orElseThrow(), "exactly at the limit is stored verbatim");
        }
        assertEquals(2, s.version(main("a")));
        assertTrue(setOf(LIMIT, true).length() < LIMIT, "the multibyte case really has fewer chars than bytes");
    }

    @Test
    void aDraftAppendIsHeldToTheSameLimit() throws Exception {
        InvestigationStore s = freshWithSetLimit(LIMIT);
        String d = promotable(s, "a", 1, 0);
        var sc = draft("a", d);
        assertTooLarge(() -> s.append(sc, 0, 2, "{\"own\":2}", setOf(LIMIT + 1, false)));
        assertEquals(0, s.version(sc));
        assertTrue(s.draftSet("a", d, 2).isEmpty());
        s.append(sc, 0, 2, "{\"own\":2}", setOf(LIMIT, false));
        assertEquals(1, s.version(sc));
    }

    @Test
    void createForkRefusesAnyOverLimitSetAndCreatesNothing() throws Exception {
        InvestigationStore s = freshWithSetLimit(LIMIT);
        assertTooLarge(() -> s.createFork("f", "{}", List.of(line(1), line(2)), List.of(setOf(LIMIT, false), setOf(LIMIT + 1, false))));
        assertTrue(s.header("f").isEmpty(), "not even the header of a refused fork exists");
        assertEquals(List.of(), s.ids());
        assertTrue(s.createFork("f", "{}", List.of(line(1), line(2)), List.of(setOf(LIMIT, false), setOf(LIMIT, true))));
        assertEquals(2, s.version(main("f")));
    }

    @Test
    void promoteRefusesAnOverLimitResealedSetButNeverReChecksTheDraftsOwnSealedOne() throws Exception {
        InvestigationStore s = freshWithSetLimit(LIMIT);
        String d = promotable(s, "p", 1, 2);
        List<String> main = s.log(main("p")), own = s.log(draft("p", d));
        String mh = DraftStore.prefixHash(main, 1), oh = DraftStore.prefixHash(own, own.size());
        List<String> lines = List.of("{\"step\":2}", "{\"step\":3}");
        List<String> over = new ArrayList<>();
        over.add(null);
        over.add(setOf(LIMIT + 1, false));
        assertTooLarge(() -> s.promoteDraft("p", d, 1, mh, oh, lines, over, "{}"));
        assertEquals(1, s.version(main("p")), "a refused promote left the main log as it was");
        assertEquals(InvestigationStore.DraftState.OPEN, s.draftState("p", d), "and the Draft open");
        List<String> ok = new ArrayList<>();
        ok.add(null);
        ok.add(setOf(LIMIT, false));
        s.promoteDraft("p", d, 1, mh, oh, lines, ok, "{\"promoted\":true}");
        assertEquals(3, s.version(main("p")));
    }

    @Test
    void replaceDraftRefusesAnOverLimitSetAndLeavesTheDraftAsItWas() throws Exception {
        InvestigationStore s = freshWithSetLimit(LIMIT);
        String d = promotable(s, "r", 2, 2);
        List<String> main = s.log(main("r")), own = s.log(draft("r", d));
        String mh = DraftStore.prefixHash(main, 2), oh = DraftStore.prefixHash(own, own.size());
        assertTooLarge(() -> s.replaceDraft("r", d, 2, mh, oh, "{\"new\":1}", List.of("{\"step\":3}"), List.of(setOf(LIMIT + 1, false)), List.of(3)));
        assertEquals(own, s.log(draft("r", d)), "a refused swap changed nothing");
        assertEquals(draftHeader(d, "ann", 2), s.draftHeader("r", d).orElseThrow());
        s.replaceDraft("r", d, 2, mh, oh, "{\"new\":1}", List.of("{\"step\":3}"), List.of(setOf(LIMIT, false)), List.of(3));
        assertEquals(setOf(LIMIT, false), s.draftSet("r", d, 3).orElseThrow());
    }

    @Test
    void closeDraftIsOneIdempotentActMarkerFirstThenTheEvidenceIsDeleted() throws Exception {
        InvestigationStore s = fresh();
        String d = promotable(s, "c", 1, 2);
        var seen = new java.util.concurrent.atomic.AtomicReference<List<String>>();
        assertEquals(Optional.of(true), s.closeDraft("c", d, null, own -> {
            seen.set(own);
            return "{\"discardedBy\":\"ann\",\"steps\":" + own.size() + "}";
        }));
        assertEquals(2, seen.get().size(), "the marker is built from the log as it stood");
        assertEquals(InvestigationStore.DraftState.DISCARDED, s.draftState("c", d));
        assertEquals("{\"discardedBy\":\"ann\",\"steps\":2}", s.discardMarker("c", d).orElseThrow());
        assertEquals(List.of(), s.log(draft("c", d)), "the sealed rows do not outlive the discard");
        assertTrue(s.draftSet("c", d, 2).isEmpty());
        assertEquals(Optional.of(false), s.closeDraft("c", d, null, own -> "{}"), "a repeat reports it was already discarded");
        assertThrows(InvestigationStore.DraftClosedException.class, () -> s.append(draft("c", d), 0, 2, "{}", "{}"));
        assertEquals(List.of(), s.openDraftIds("c"));
        assertEquals(0, s.openDraftCount());
        assertEquals(Optional.empty(), s.closeDraft("c", d, java.time.Duration.ofDays(30), own -> "{}"),
                "a conditional close of a closed Draft is not applicable");
    }

    // ── the per-Investigation total set budget (D-IS12 b) ───────────────────────────────────────────────

    private static final long BUDGET = 3000;

    private static void assertOverBudget(String inv, long held, org.junit.jupiter.api.function.Executable write) {
        com.gamma.spi.auth.ApiException e = assertThrows(com.gamma.spi.auth.ApiException.class, write);
        assertEquals(413, e.status);
        assertEquals(com.gamma.spi.auth.ErrorCodes.PAYLOAD_TOO_LARGE, e.errorCode);
        assertTrue(e.getMessage().contains("'" + inv + "'"), "names the Investigation: " + e.getMessage());
        assertTrue(e.getMessage().contains("holds " + held + " bytes"), "states the current total: " + e.getMessage());
        assertTrue(e.getMessage().contains(String.valueOf(BUDGET)), "states the budget: " + e.getMessage());
        assertTrue(e.getMessage().contains("max_investigation_bytes"), "says how to raise it: " + e.getMessage());
    }

    /** An Investigation with the given main set sizes and a Draft (own steps after main) with the given own set sizes. */
    private String budgeted(InvestigationStore s, String inv, long[] mainSets, long[] ownSets) throws Exception {
        s.create(inv, "{}");
        for (int i = 1; i <= mainSets.length; i++) s.append(main(inv), i - 1, i, line(i), setOf(mainSets[i - 1], false));
        String d = DraftStore.newId();
        s.createDraft(inv, d, draftHeader(d, "ann", mainSets.length), "ann", 99);
        for (int k = 1; k <= ownSets.length; k++)
            s.append(draft(inv, d), k - 1, mainSets.length + k, "{\"step\":" + (mainSets.length + k) + ",\"own\":true}", setOf(ownSets[k - 1], false));
        return d;
    }

    @Test
    void mainAppendIsHeldToTheInvestigationTotalExactlyAtTheBudgetPassesOneByteOverIsRefused() throws Exception {
        InvestigationStore s = freshWithInvestigationBudget(BUDGET);
        s.create("a", "{}");
        for (int i = 1; i <= 3; i++) s.append(main("a"), i - 1, i, line(i), setOf(1000, false));   // 3000 = the budget: passes
        assertOverBudget("a", 3000, () -> s.append(main("a"), 3, 4, line(4), setOf(1, false)));
        assertEquals(3, s.version(main("a")), "a refused set writes no log line");
        assertTrue(s.set("a", 4).isEmpty(), "and stores no set");
        assertTrue(s.header("a").isPresent());
        s.create("b", "{}");   // the budget is per Investigation: a neighbour is unaffected
        s.append(main("b"), 0, 1, line(1), setOf(1000, false));
    }

    @Test
    void aMultibyteSetIsCountedInBytesAgainstTheTotal() throws Exception {
        InvestigationStore s = freshWithInvestigationBudget(BUDGET);
        s.create("m", "{}");
        s.append(main("m"), 0, 1, line(1), setOf(2000, true));   // 1000 chars, 2000 bytes
        assertOverBudget("m", 2000, () -> s.append(main("m"), 1, 2, line(2), setOf(1001, false)));
        s.append(main("m"), 1, 2, line(2), setOf(1000, false));
    }

    @Test
    void liveDraftSetsCountTowardsTheTotalForBothMainAndDraftAppends() throws Exception {
        InvestigationStore s = freshWithInvestigationBudget(BUDGET);
        String d = budgeted(s, "a", new long[] {1000}, new long[] {1000, 1000});   // 1000 main + 2000 draft = 3000
        assertOverBudget("a", 3000, () -> s.append(draft("a", d), 2, 4, "{\"own\":4}", setOf(1, false)));
        assertEquals(2, s.version(draft("a", d)), "a refused Draft append wrote nothing");
        assertTrue(s.draftSet("a", d, 4).isEmpty());
        assertOverBudget("a", 3000, () -> s.append(main("a"), 1, 2, line(2), setOf(1, false)));
        assertEquals(1, s.version(main("a")));
    }

    @Test
    void discardingADraftFreesItsSetsFromTheTotal() throws Exception {
        InvestigationStore s = freshWithInvestigationBudget(BUDGET);
        String d = budgeted(s, "a", new long[] {1000}, new long[] {1000, 1000});
        assertEquals(Optional.of(true), s.closeDraft("a", d, null, own -> "{}"));
        s.append(main("a"), 1, 2, line(2), setOf(2000, false));   // 1000 + 2000 = 3000: the Draft's 2000 are gone
        assertOverBudget("a", 3000, () -> s.append(main("a"), 2, 3, line(3), setOf(1, false)));
    }

    @Test
    void promoteCountsTheSharedSetsOnceNeitherDoubledNorLost() throws Exception {
        InvestigationStore s = freshWithInvestigationBudget(BUDGET);
        String d = budgeted(s, "p", new long[] {1000}, new long[] {1000, 1000});   // 3000 now
        List<String> main = s.log(main("p")), own = s.log(draft("p", d));
        String mh = DraftStore.prefixHash(main, 1), oh = DraftStore.prefixHash(own, own.size());
        List<String> lines = List.of("{\"step\":2}", "{\"step\":3}");
        List<String> over = new ArrayList<>();
        over.add(null);
        over.add(setOf(1001, false));   // net 3000 - 2000 + 1000 + 1001 = 3001
        assertOverBudget("p", 3000, () -> s.promoteDraft("p", d, 1, mh, oh, lines, over, "{}"));
        assertEquals(1, s.version(main("p")), "a refused promote left the main log as it was");
        assertEquals(InvestigationStore.DraftState.OPEN, s.draftState("p", d), "and the Draft open");
        assertEquals(setOf(1000, false), s.draftSet("p", d, 3).orElseThrow(), "and its sets in place");
        List<String> shared = new ArrayList<>();
        shared.add(null);
        shared.add(null);   // net 3000 - 2000 + 2000 = 3000: passes
        s.promoteDraft("p", d, 1, mh, oh, lines, shared, "{\"promoted\":true}");
        assertEquals(3, s.version(main("p")));
        assertOverBudget("p", 3000, () -> s.append(main("p"), 3, 4, line(4), setOf(1, false)));   // exactly 3000: not 1000 (lost), not 5000 (doubled)
    }

    @Test
    void aRebaseReplacesTheDraftsSetsInTheTotalInsteadOfAddingToThem() throws Exception {
        InvestigationStore s = freshWithInvestigationBudget(BUDGET);
        String d = budgeted(s, "r", new long[] {1000}, new long[] {1000, 1000});   // 3000 now
        List<String> main = s.log(main("r")), own = s.log(draft("r", d));
        String mh = DraftStore.prefixHash(main, 1), oh = DraftStore.prefixHash(own, own.size());
        assertOverBudget("r", 3000, () -> s.replaceDraft("r", d, 1, mh, oh, "{\"new\":1}", List.of("{\"step\":2}"), List.of(setOf(2001, false)), List.of(2)));
        assertEquals(own, s.log(draft("r", d)), "a refused swap changed nothing");
        s.replaceDraft("r", d, 1, mh, oh, "{\"new\":1}", List.of("{\"step\":2}"), List.of(setOf(2000, false)), List.of(2));   // net 1000 + 2000
        assertOverBudget("r", 3000, () -> s.append(main("r"), 1, 2, line(2), setOf(1, false)));   // exactly 3000: the old 2000 were replaced
    }

    @Test
    void createForkIsHeldToTheBudgetAndSeedsTheTotal() throws Exception {
        InvestigationStore s = freshWithInvestigationBudget(BUDGET);
        com.gamma.spi.auth.ApiException e = assertThrows(com.gamma.spi.auth.ApiException.class,
                () -> s.createFork("f", "{}", List.of(line(1), line(2)), List.of(setOf(1500, false), setOf(1501, false))));
        assertEquals(413, e.status);
        assertTrue(e.getMessage().contains("max_investigation_bytes"), e.getMessage());
        assertTrue(s.header("f").isEmpty(), "not even the header of a refused fork exists");
        assertTrue(s.createFork("f", "{}", List.of(line(1), line(2)), List.of(setOf(1500, false), setOf(1500, false))));
        assertOverBudget("f", 3000, () -> s.append(main("f"), 2, 3, line(3), setOf(1, false)));
    }

    private static String sha256(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }
}
