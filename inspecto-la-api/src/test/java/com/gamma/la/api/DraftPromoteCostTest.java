package com.gamma.la.api;

import com.gamma.la.core.InvestigationStore;
import com.gamma.la.core.FsInvestigationStore;
import com.gamma.la.core.DraftStore;
import com.gamma.la.core.InvestigationEvaluator;
import com.gamma.la.core.SnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.gamma.la.core.InvestigationEvaluator.canonical;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * LA-DRAFT-PROMOTE-COST-1: promote reuses the Draft's own sealed per-step hash and set file instead of re-serialising the whole
 * state per step (quadratic in the Draft's steps). Pinned against the pre-fix algorithm, kept here as the oracle.
 */
class DraftPromoteCostTest {

    private static final Map<String, Object> APPROVAL = Map.of("approvedBy", "lead");

    @TempDir Path tmp;

    @Test
    void promotedLogAndSetsAreByteIdenticalToThePreFixAlgorithm() throws Exception {
        Fixture f = fixture(tmp, 60);
        Expected want = legacy(f);
        long before = DraftPromote.resealed.get();
        DraftPromote.execute(f.main, f.draftId, "promoter", APPROVAL, null);
        assertEquals(0, DraftPromote.resealed.get() - before, "a well-formed Draft re-seals nothing");
        assertPromoted(f, want);
    }

    @Test
    void aMissingOrForeignSetFileIsResealedFromTheFoldWithTheSameBytes() throws Exception {
        Fixture f = fixture(tmp, 30);
        Expected want = legacy(f);
        Path sets = DraftStore.draftDir(f.main.dir(), f.draftId).resolve("sets");
        Files.delete(sets.resolve("12.json"));
        Files.writeString(sets.resolve("20.json"), Files.readString(sets.resolve("19.json"), StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        long before = DraftPromote.resealed.get();
        DraftPromote.execute(f.main, f.draftId, "promoter", APPROVAL, null);
        assertEquals(2, DraftPromote.resealed.get() - before);
        assertPromoted(f, want);
    }

    /** A mid-log set file with an intact head but altered working-set bytes is verified, refused for reuse and re-sealed. */
    @Test
    void aTamperedSetFileWithAnIntactHeadIsNotCarriedOntoMain() throws Exception {
        Fixture f = fixture(tmp, 30);
        Expected want = legacy(f);
        Path set = DraftStore.draftDir(f.main.dir(), f.draftId).resolve("sets").resolve("15.json");
        String raw = Files.readString(set, StandardCharsets.UTF_8);
        int at = raw.indexOf("\"workingSet\":") + 13;
        String tampered = raw.substring(0, at) + raw.substring(at).replaceFirst("x", "y");
        org.junit.jupiter.api.Assertions.assertNotEquals(raw, tampered);
        assertEquals(raw.length(), tampered.length());
        Files.writeString(set, tampered, StandardCharsets.UTF_8);
        long before = DraftPromote.resealed.get();
        DraftPromote.execute(f.main, f.draftId, "promoter", APPROVAL, null);
        assertEquals(1, DraftPromote.resealed.get() - before);
        assertPromoted(f, want);
    }

    /** The cost guard, as an operation count: no step of a well-formed Draft costs a full-state serialisation, at any length. */
    @Test
    void noStepCostsAFullStateSerialisationWhateverTheLength() throws Exception {
        for (int n : new int[] {25, 100}) {
            Fixture f = fixture(tmp.resolve("n" + n), n);
            long before = DraftPromote.resealed.get();
            DraftPromote.execute(f.main, f.draftId, "promoter", APPROVAL, null);
            assertEquals(0, DraftPromote.resealed.get() - before, n + " steps");
        }
    }

    /** The before/after curve (run with -Dinspecto.bench.promote=true; prints PROFILE lines). */
    @Test
    @EnabledIfSystemProperty(named = "inspecto.bench.promote", matches = "true")
    void profileCurve() throws Exception {
        for (int n : new int[] {100, 200, 400, 800}) {
            Fixture f = fixture(tmp.resolve("p" + n), n);
            long t = System.nanoTime();
            legacy(f);
            long legacyMs = (System.nanoTime() - t) / 1_000_000;
            // a real Draft's set files were written over its life; the FIRST open of a file written a moment ago costs ~6 ms on
            // Windows (the on-access scan), so read them once here as that life would have
            if (!Boolean.getBoolean("inspecto.bench.promote.cold")) try (var w = Files.list(DraftStore.draftDir(f.main.dir(), f.draftId).resolve("sets"))) {
                for (Path p : w.toList()) Files.readAllBytes(p);
            }
            t = System.nanoTime();
            DraftPromote.execute(f.main, f.draftId, "promoter", APPROVAL, null);
            System.out.printf("PROFILE n=%d legacy-loop %d ms promote %d ms%n", n, legacyMs, (System.nanoTime() - t) / 1_000_000);
        }
    }

    // ── the oracle: DraftPromote.execute's entry/set construction as it was before the fix ──

    record Expected(List<String> lines, List<String> sets) { }

    private static Expected legacy(Fixture f) throws Exception {
        InvestigationRoutes.Inv main = f.main;
        Path draftDir = DraftStore.draftDir(main.dir(), f.draftId);
        @SuppressWarnings("unchecked") Map<String, Object> header = com.gamma.control.ApiContext.JSON.readValue(
                DraftStore.readHeader(main.dir(), f.draftId), LinkedHashMap.class);
        String actor = String.valueOf(header.get("actor"));
        int base = ((Number) header.get("baseStep")).intValue();
        List<String> mainLines = main.store().log(InvestigationStore.Scope.main(main.id()));
        List<Map<String, Object>> mainEntries = DraftRebase.parseAll(mainLines);
        List<Map<String, Object>> effective = DraftRebase.effectiveOps(DraftRebase.parseAll(SnapshotStore.readLogAt(draftDir)));
        List<Map<String, Object>> sensitiveNow = DraftPromote.sensitiveSteps(main, effective);
        InvestigationEvaluator.State state = InvestigationEvaluator.evaluate(mainEntries, -1, null);
        List<String> lines = new ArrayList<>(), sets = new ArrayList<>();
        int step = mainLines.size();
        for (Map<String, Object> o : effective) {
            Map<String, Object> e = new LinkedHashMap<>(o);
            e.put("step", ++step);
            Map<String, Object> prov = new LinkedHashMap<>();
            prov.put("id", f.draftId);
            prov.put("actor", actor);
            prov.put("baseStep", base);
            prov.put("step", o.get("step"));
            prov.put("promotedBy", "promoter");
            e.put("draft", prov);
            boolean sensitive = sensitiveNow.stream().anyMatch(s -> s.get("step").equals(DraftRebase.step(o)));
            if (sensitive) e.put("approval", APPROVAL);
            e.remove("workingSetHash");
            e = InvestigationRoutes.roundTrip(e);
            InvestigationEvaluator.apply(state, e);
            e.put("workingSetHash", state.hash());
            lines.add(canonical(e));
            sets.add(canonical(InvestigationRoutes.setDoc(step, state)));
        }
        return new Expected(lines, sets);
    }

    private static void assertPromoted(Fixture f, Expected want) throws Exception {
        List<String> log = f.main.store().log(InvestigationStore.Scope.main(f.main.id()));
        assertEquals(MAIN + want.lines.size(), log.size());
        assertEquals(want.lines, log.subList(MAIN, log.size()));
        Path sets = f.main.dir().resolve("sets");
        for (int i = 0; i < want.sets.size(); i++)
            assertEquals(want.sets.get(i), Files.readString(sets.resolve((MAIN + i + 1) + ".json"), StandardCharsets.UTF_8), "set " + (MAIN + i + 1));
    }

    // ── fixture ──

    private static final int MAIN = 5;

    record Fixture(InvestigationRoutes.Inv main, String draftId) { }

    /**
     * A {@value #MAIN}-step main log, then a Draft of {@code steps} ops written the way the append route writes them: one seed, then
     * expands admitting 20 fresh links each (so the state grows with the steps), with an exclude every 7th step and a hide every 11th.
     */
    static Fixture fixture(Path writeRoot, int steps) throws Exception {
        FsInvestigationStore store = new FsInvestigationStore(writeRoot);
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("id", "inv1");
        header.put("dataset", "ds");
        header.put("sourceCol", "s");
        header.put("targetCol", "t");
        store.create("inv1", canonical(header));
        InvestigationRoutes.Inv main = new InvestigationRoutes.Inv(store, writeRoot, "inv1", header);
        InvestigationEvaluator.State s = new InvestigationEvaluator.State();
        for (int i = 1; i <= MAIN; i++) {
            Map<String, Object> e = op(i, "seed", Map.of("ids", List.of("m" + i)));
            InvestigationEvaluator.apply(s, e);
            e.put("workingSetHash", s.hash());
            store.append(InvestigationStore.Scope.main("inv1"), i - 1, i, canonical(e), canonical(InvestigationRoutes.setDoc(i, s)));
        }
        List<String> mainLines = store.log(InvestigationStore.Scope.main("inv1"));
        String id = DraftStore.newId();
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("draftId", id);
        h.put("investigationId", "inv1");
        h.put("actor", "analyst");
        h.put("createdAt", "2026-10-03T00:00:00Z");
        h.put("baseStep", MAIN);
        h.put("baseLogHash", DraftStore.prefixHash(mainLines, MAIN));
        if (!DraftStore.create(main.dir(), id, canonical(h))) throw new IllegalStateException("fork failed");
        Path d = DraftStore.draftDir(main.dir(), id);
        for (int k = 1; k <= steps; k++) {
            int step = MAIN + k;
            Map<String, Object> e;
            if (k == 1) e = op(step, "seed", Map.of("ids", List.of("hub")));
            else if (k % 7 == 0) e = op(step, "exclude", Map.of("ids", List.of("x" + (k - 1) + "_3"), "reason", "noise"));
            else if (k % 11 == 0) e = op(step, "hide", Map.of("ids", List.of("x" + (k - 2) + "_5")));
            else {
                List<Map<String, Object>> rows = new ArrayList<>();
                for (int j = 0; j < 20; j++)
                    rows.add(Map.of("source", "hub", "target", "x" + k + "_" + j, "kind", "call", "count", 1));
                e = op(step, "expand", Map.of("frontier", List.of("hub"), "hops", 1, "budget", 50, "maxFanOut", 50));
                e.put("read", Map.of("query", Map.of("frontier", List.of("hub")), "rows", rows));
            }
            e.put("author", "analyst");
            e = InvestigationRoutes.roundTrip(e);
            InvestigationEvaluator.apply(s, e);
            e.put("workingSetHash", s.hash());
            DraftStore.appendStep(d, step, canonical(e), canonical(InvestigationRoutes.setDoc(step, s)));
        }
        return new Fixture(main, id);
    }

    private static Map<String, Object> op(int step, String op, Map<String, Object> params) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("step", step);
        e.put("kind", "op");
        e.put("op", op);
        e.put("params", params);
        return e;
    }
}
