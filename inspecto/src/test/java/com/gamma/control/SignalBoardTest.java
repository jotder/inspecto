package com.gamma.control;

import com.gamma.job.JobRun;
import com.gamma.job.JobService;
import com.gamma.signal.Ref;
import com.gamma.signal.Signal;
import com.gamma.signal.SignalField;
import com.gamma.signal.SignalTypeCatalog;
import com.gamma.signal.SignalTypeDecl;
import com.gamma.signal.SignalTypeProvider;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SignalBoard} (SIGNAL-BOARD-1 SB-3) over a hand-built catalog, Job list, Run history and ledger page: each count
 * lands on the row of the type it is about, a family row collects its members, the two finding lists say what they
 * claim, and every list is bounded with its true total.
 */
class SignalBoardTest {

    private static final long NOW = System.currentTimeMillis();
    private static final long SINCE = NOW - 3_600_000L;
    private static final DateTimeFormatter RUN_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static SignalTypeCatalog catalog(String... types) {
        List<SignalTypeDecl> decls = new ArrayList<>();
        for (String t : types)
            decls.add(new SignalTypeDecl(t, "title of " + t, "engine",
                    t.endsWith(".*") ? List.of() : List.of(SignalField.optional("x", SignalField.Kind.STRING, "x")), "4.0.0"));
        SignalTypeProvider p = new SignalTypeProvider() {
            @Override public String source() { return "core"; }
            @Override public List<SignalTypeDecl> signalTypes() { return decls; }
        };
        return SignalTypeCatalog.assemble(List.of(p), List.of());
    }

    private static JobService.JobView job(String name, String onSignal, String onPipeline, boolean enabled) {
        return new JobService.JobView(name, "maintenance", null, onPipeline, onSignal, enabled, "", "", "",
                false, true, null, null, null);
    }

    private static Signal sig(String type, Map<String, Object> payload, String jobSource) {
        return new Signal("s-" + Math.random(), type, Instant.ofEpochMilli(NOW - 60_000), null,
                Ref.of("job", jobSource), null, null, null, "default", null, type, payload, 1);
    }

    private static String ts(long epochMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault()).format(RUN_TS);
    }

    private static Map<String, Object> build(SignalTypeCatalog cat, List<JobService.JobView> jobs,
                                             Map<String, List<JobRun>> runs, List<Signal> window, boolean capped) {
        return SignalBoard.build(new SignalBoard.Inputs(cat, jobs, n -> runs.getOrDefault(n, List.of()),
                window, capped, SINCE, NOW, SINCE - 1, null));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> row(Map<String, Object> board, String type) {
        for (Map<String, Object> r : (List<Map<String, Object>>) board.get("types"))
            if (type.equals(r.get("type"))) return r;
        return fail("no row '" + type + "' in " + board.get("types"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(Map<String, Object> board, String key, String field) {
        return ((List<Map<String, Object>>) board.get(key)).stream().map(m -> String.valueOf(m.get(field))).toList();
    }

    @Test
    void eachCountLandsOnTheTypeItIsAbout() {
        SignalTypeCatalog cat = catalog("order.placed", "job.run.started", "job.signal.refused", "job.chain.cut");
        List<Signal> window = List.of(
                sig("order.placed", Map.of(), "shop"),
                sig("order.placed", Map.of(), "shop"),
                sig("job.run.started", Map.of("job", "fulfil", "run", "r1", "trigger", "signal:order.placed"), "fulfil"),
                sig("job.run.started", Map.of("job", "nightly", "run", "r2", "trigger", "cron"), "nightly"),
                sig("job.signal.refused", Map.of("job", "fulfil", "refusedSignalId", "x", "refusedType", "order.placed",
                        "maxPending", 3), "fulfil"),
                sig("job.chain.cut", Map.of("job", "fulfil", "signalType", "order.placed", "maxChainDepth", 8), "fulfil"));
        Map<String, List<JobRun>> runs = new HashMap<>();
        runs.put("fulfil", List.of(
                new JobRun("r3", "fulfil", "maintenance", "signal:order.placed", ts(NOW - 120_000), ts(NOW - 120_000),
                        "SKIPPED", 0, SignalBoard.WHEN_GUARD_FALSE),
                new JobRun("r4", "fulfil", "maintenance", "signal:order.placed", ts(SINCE - 120_000), ts(SINCE - 120_000),
                        "SKIPPED", 0, SignalBoard.WHEN_GUARD_FALSE),                          // before the window
                new JobRun("r5", "fulfil", "maintenance", "signal:order.placed", ts(NOW - 60_000), ts(NOW - 60_000),
                        "SKIPPED", 0, "burst bound: 3 Runs already queued")));             // a refusal, not a guard

        Map<String, Object> b = build(cat, List.of(job("fulfil", "order.placed", null, true)), runs, window, false);

        Map<String, Object> placed = row(b, "order.placed");
        assertEquals(2, placed.get("posted"));
        assertEquals(1, placed.get("runsStarted"), "only the Run whose trigger names this type");
        assertEquals(1, placed.get("guardsSaidNo"), "inside the window, and only the when guard");
        assertEquals(1, placed.get("refused"));
        assertEquals(1, placed.get("chainCuts"));
        assertEquals(List.of("job:shop"), placed.get("postedBy"));
        assertNotNull(placed.get("lastPosted"));
        assertNull(placed.get("missed"), "SB-2b not wired: missed is unknown, not zero");
        assertEquals(false, b.get("missedTracked"));
        assertEquals(1, placed.get("listenersTotal"));
        assertEquals("order.placed", row(b, "order.placed").get("type"));
        assertEquals(0, row(b, "job.chain.cut").get("chainCuts"), "the cut counts on the type it cut, not on itself");
    }

    @Test
    void theTwoFindingListsSayWhatTheyClaim() {
        SignalTypeCatalog cat = catalog("order.placed", "order.shipped", "exchange.*");
        List<Signal> window = List.of(
                sig("order.placed", Map.of(), "shop"),         // heard by an enabled Job
                sig("order.shipped", Map.of(), "shop"),        // heard only by a disabled Job: unheard
                sig("exchange.acme.offer", Map.of(), "x"),     // a family member: counts under exchange.*
                sig("custom.thing", Map.of(), "rule"));        // not catalogued: its own row, unheard
        List<JobService.JobView> jobs = List.of(
                job("on_placed", "order.placed", null, true),
                job("on_shipped_off", "order.shipped", null, false),
                job("on_exchange", "exchange.acme.offer", null, true),
                job("on_typo", "order.plcaed", null, true),        // nothing posts it: unposted
                job("on_wide", "order.*", null, false),           // a glob over catalogued types; off, so it hears nothing
                job("on_ghost", "ghost.*", null, false));         // a glob nothing matches, disabled: still unposted

        Map<String, Object> b = build(cat, jobs, Map.of(), window, false);

        assertEquals(1, row(b, "exchange.*").get("posted"), "a member counts under its family");
        assertEquals(true, row(b, "exchange.*").get("family"));
        assertEquals(false, row(b, "custom.thing").get("catalogued"));
        assertEquals(List.of("custom.thing", "order.shipped"), list(b, "unheard", "type").stream().sorted().toList());
        assertEquals(List.of("on_ghost", "on_typo"), list(b, "unposted", "job").stream().sorted().toList());
        assertEquals(2, b.get("unheardTotal"));
        assertEquals(false, b.get("unpostedTruncated"));
        assertEquals(2, row(b, "order.placed").get("listenersTotal"), "the exact Job and the glob");
    }

    @Test
    void anOnPipelineJobListensToPipelineCommit() {
        SignalTypeCatalog cat = catalog("pipeline.commit");
        SignalTypeCatalog withStart = catalog("pipeline.commit", "job.run.started");
        Map<String, Object> b = build(withStart, List.of(job("rollup", null, "orders", true)), Map.of(),
                List.of(sig("pipeline.commit", Map.of(), "orders"),
                        sig("job.run.started", Map.of("job", "rollup", "run", "r", "trigger", "event:orders"), "rollup")), false);
        assertEquals(1, row(b, "pipeline.commit").get("listenersTotal"), "D-SB2: on_pipeline is on_signal: pipeline.commit");
        assertEquals(1, row(b, "pipeline.commit").get("runsStarted"), "its Runs count where it listens");
        assertEquals(List.of("job.run.started"), list(b, "unheard", "type"));
    }

    /** A family row is heard per member: a Job on one member does not hear another member that was posted. */
    @Test
    void aPostedFamilyMemberNobodyListensToIsUnheardEvenWhenAnotherMemberIsHeard() {
        SignalTypeCatalog cat = catalog("exchange.*");
        Map<String, Object> b = build(cat, List.of(job("acme", "exchange.acme.offer", null, true)), Map.of(),
                List.of(sig("exchange.acme.offer", Map.of(), "x"), sig("exchange.beta.order", Map.of(), "x")), false);
        assertEquals(2, row(b, "exchange.*").get("posted"));
        assertEquals(List.of("exchange.beta.order"), list(b, "unheard", "type"));
    }

    /** The in-memory Run history starts empty at each server start: a window reaching back before it is a floor. */
    @Test
    void guardsAreAFloorWhenTheServerStartedInsideTheWindow() {
        Map<String, Object> b = SignalBoard.build(new SignalBoard.Inputs(catalog("order.placed"), List.of(),
                n -> List.of(), List.of(), false, SINCE, NOW, SINCE + 1000, null));
        assertEquals(true, b.get("guardsCapped"));
    }

    /** A guard count on an uncatalogued type still shows the Job that listens to it. */
    @Test
    void aRowMadeByAGuardCountStillListsItsListener() {
        Map<String, List<JobRun>> runs = Map.of("j", List.of(new JobRun("r", "j", "maintenance", "signal:x.y",
                ts(NOW - 60_000), ts(NOW - 60_000), "SKIPPED", 0, SignalBoard.WHEN_GUARD_FALSE)));
        Map<String, Object> b = build(catalog("order.placed"), List.of(job("j", "x.y", null, true)), runs, List.of(), true);
        assertEquals(1, row(b, "x.y").get("guardsSaidNo"));
        assertEquals(1, row(b, "x.y").get("listenersTotal"));
    }

    @Test
    void everyListIsBoundedWithItsTrueTotalAndCapsAreReported() {
        String[] types = new String[SignalBoard.MAX_TYPES + 5];
        for (int i = 0; i < types.length; i++) types[i] = "t" + i + ".x";
        List<JobService.JobView> jobs = new ArrayList<>();
        for (int i = 0; i < SignalBoard.MAX_FINDINGS + 3; i++) jobs.add(job("j" + i, "none" + i + ".x", null, true));
        List<JobRun> full = new ArrayList<>();
        for (int i = 0; i < SignalBoard.RUN_HISTORY; i++)
            full.add(new JobRun("r" + i, "j0", "maintenance", "cron", ts(NOW - 1000L * i), ts(NOW), "SUCCESS", 0, null));

        Map<String, Object> b = build(catalog(types), jobs, Map.of("j0", full), List.of(), true);

        assertEquals(SignalBoard.MAX_TYPES, ((List<?>) b.get("types")).size());
        assertEquals(types.length, b.get("typesTotal"));
        assertEquals(true, b.get("typesTruncated"));
        assertEquals(SignalBoard.MAX_FINDINGS, ((List<?>) b.get("unposted")).size());
        assertEquals(SignalBoard.MAX_FINDINGS + 3, b.get("unpostedTotal"));
        assertEquals(true, b.get("unpostedTruncated"));
        assertEquals(true, b.get("capped"), "the ledger page was full");
        assertEquals(true, b.get("guardsCapped"), "a full Run history that starts inside the window is a floor");
    }

    @Test
    void missedIsCountedOnceSb2bProvidesIt() {
        Map<String, Object> b = SignalBoard.build(new SignalBoard.Inputs(catalog("order.placed"), List.of(),
                n -> List.of(), List.of(), false, SINCE, NOW, SINCE - 1, Map.of("order.placed", 4)));
        assertEquals(true, b.get("missedTracked"));
        assertEquals(4, row(b, "order.placed").get("missed"));
    }
}
