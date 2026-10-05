package com.gamma.la.core;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The TWO-JVM race proof (LA-INVESTIGATION-STORE-DESIGN-1, slice S7): two real JVMs, each with its own
 * {@link InvestigationStore}, hammer the same Investigation through the port, then this class reads the result back and asserts
 * the invariants a multi-pod deployment depends on. The in-JVM contract races prove a monitor or a row lock; only two processes
 * prove there is no cross-process hole. A backend subclass supplies {@link #openerClass()} and {@link #spec()}.
 */
abstract class InvestigationStoreTwoJvmRace {

    @TempDir Path work;

    /** Public no-arg {@link TwoJvmRaceWorker.Opener} the worker JVMs instantiate. */
    abstract Class<? extends TwoJvmRaceWorker.Opener> openerClass();

    /** Backend spec handed to every opener (a directory, a schema). */
    abstract String spec() throws Exception;

    /** Extra environment for the child JVMs (the Postgres URL), never a command-line argument. */
    Map<String, String> childEnv() { return Map.of(); }

    TwoJvmRaceWorker.Opener open() throws Exception {
        return openerClass().getDeclaredConstructor().newInstance();
    }

    private static final int N = 60;

    /** Runs scenario {@code name} in two JVMs started together; returns each worker's fact lines. */
    private List<List<String>> race(String scenario, int n) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path go = work.resolve("go-" + scenario);
        List<Process> procs = new ArrayList<>();
        List<Path> outs = new ArrayList<>();
        for (String w : List.of("A", "B")) {
            Path out = work.resolve(scenario + "-" + w + ".txt");
            Path log = work.resolve(scenario + "-" + w + ".log");
            ProcessBuilder pb = new ProcessBuilder(java, "-cp", cp, TwoJvmRaceWorker.class.getName(),
                    openerClass().getName(), spec(), go.toString(), out.toString(), w, scenario, String.valueOf(n))
                    .redirectErrorStream(true).redirectOutput(log.toFile());
            pb.environment().putAll(childEnv());
            procs.add(pb.start());
            outs.add(out);
        }
        Thread.sleep(1500);   // both JVMs are up and polling for the go file; release them together
        Files.writeString(go, "go");
        List<List<String>> facts = new ArrayList<>();
        for (int i = 0; i < procs.size(); i++) {
            assertTrue(procs.get(i).waitFor(120, TimeUnit.SECONDS), "worker " + i + " did not finish");
            assertEquals(0, procs.get(i).exitValue(), "worker " + i + " failed:\n"
                    + Files.readString(work.resolve(scenario + "-" + (i == 0 ? "A" : "B") + ".log"), StandardCharsets.UTF_8));
            facts.add(Files.exists(outs.get(i)) ? Files.readAllLines(outs.get(i), StandardCharsets.UTF_8) : List.of());
        }
        return facts;
    }

    @Test
    void twoJvmsAppendingToOneLogLeaveItGapFreeHashChainedAndLoseNothing() throws Exception {
        InvestigationStore s = open().open(spec());
        s.create("race", "{\"id\":\"race\"}");
        List<List<String>> facts = race("append", N);

        var main = InvestigationStore.Scope.main("race");
        List<String> log = s.log(main);
        assertEquals(2 * N, log.size(), "no lost update and no duplicate: every append of both JVMs is exactly one line");
        assertEquals(2 * N, s.version(main));
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < log.size(); i++) {
            JsonNode line = InvestigationEvaluator.CANONICAL.readTree(log.get(i));
            assertEquals(i + 1, line.get("step").asInt(), "gap-free and in order at line " + (i + 1));
            assertEquals(DraftStore.prefixHash(log, i), line.get("prev").asText(), "hash chain broken at line " + (i + 1)
                    + ": the writer chained to a prefix that is not the one before it");
            assertTrue(seen.add(line.get("w").asText() + ":" + line.get("i").asInt()), "an append landed twice: " + log.get(i));
            assertEquals("{\"set\":" + (i + 1) + ",\"w\":\"" + line.get("w").asText() + "\",\"i\":" + line.get("i").asInt() + "}",
                    s.set("race", i + 1).orElse(null), "the set of step " + (i + 1) + " is the one its own line wrote");
        }
        assertEquals(2 * N, seen.size());
        for (List<String> f : facts) assertTrue(f.contains("APPENDED " + N), "each JVM completed its " + N + " appends: " + f);
        System.out.println("S7 append: " + 2 * N + " steps, conflicts A/B = " + facts.get(0).get(1) + " / " + facts.get(1).get(1));
    }

    @Test
    void twoJvmsDecidingTheSameRequestsGiveExactlyOneWinnerEach() throws Exception {
        InvestigationStore s = open().open(spec());
        s.create("dec", "{\"id\":\"dec\"}");
        for (int r = 0; r < N; r++) s.writePending("dec", "r" + r, "{\"state\":\"pending\"}");
        List<List<String>> facts = race("decide", N);

        Map<String, String> winner = new TreeMap<>();
        for (int w = 0; w < 2; w++)
            for (String f : facts.get(w))
                if (f.startsWith("WON ")) assertNull(winner.put(f.substring(4), w == 0 ? "A" : "B"), "two winners for " + f);
        assertEquals(N, winner.size(), "every request was decided by someone");
        for (int r = 0; r < N; r++)
            assertEquals("{\"state\":\"decided\",\"by\":\"" + winner.get("r" + r) + "\"}", s.pending("dec", "r" + r).orElseThrow(),
                    "the stored decision is the winner's, not a blend of both");
    }

    @Test
    void twoJvmsAskingForTheFirstMaskKeyOfAnInvestigationAgreeOnOneKey() throws Exception {
        InvestigationStore s = open().open(spec());
        for (int k = 0; k < 8; k++) s.create("mk" + k, "{}");
        List<List<String>> facts = race("mask", 8);
        assertEquals(8, facts.get(0).size());
        for (int k = 0; k < 8; k++) {
            String a = facts.get(0).get(k), b = facts.get(1).get(k);
            assertEquals(a, b, "the two JVMs derived different mask keys for mk" + k + " - tokens would not match across pods");
            assertEquals(a, "KEY mk" + k + " " + java.util.HexFormat.of().formatHex(s.maskKey("mk" + k)), "and it is the stored one");
        }
    }

    @Test
    void twoJvmsPromotingTheSameDraftPromoteItExactlyOnce() throws Exception {
        InvestigationStore s = open().open(spec());
        int n = 8;
        for (int k = 0; k < n; k++) {
            String inv = "pr" + k;
            s.create(inv, "{}");
            for (int i = 1; i <= 2; i++) s.append(InvestigationStore.Scope.main(inv), i - 1, i, TwoJvmRaceWorker.MAIN.get(i - 1), "{\"m\":" + i + "}");
            String d = DraftStore.newId();
            s.createDraft(inv, d, "{\"draftId\":\"" + d + "\",\"actor\":\"a" + k + "\",\"baseStep\":2}", "a" + k, 99);
            for (int i = 1; i <= 3; i++) s.append(InvestigationStore.Scope.draft(inv, d), i - 1, 2 + i, TwoJvmRaceWorker.DRAFT_OWN.get(i - 1), "{\"d\":" + (2 + i) + "}");
            Files.writeString(work.resolve("draft-" + inv), d);
        }
        List<List<String>> facts = race("promote", n);
        for (int k = 0; k < n; k++) {
            String inv = "pr" + k;
            long won = facts.stream().flatMap(List::stream).filter(f -> f.equals("PROMOTED " + inv)).count();
            long refused = facts.stream().flatMap(List::stream).filter(f -> f.startsWith("REFUSED " + inv + " ")).count();
            assertEquals(1, won, inv + ": exactly one JVM promotes");
            assertEquals(1, refused, inv + ": the other is refused with a typed conflict");
            List<String> log = s.log(InvestigationStore.Scope.main(inv));
            List<String> expect = new ArrayList<>(TwoJvmRaceWorker.MAIN);
            expect.addAll(TwoJvmRaceWorker.PROMOTED);
            assertEquals(expect, log, inv + ": the main log holds the Draft's steps once, in order");
        }
    }
}
