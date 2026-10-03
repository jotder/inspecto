package com.gamma.la.api;

import com.gamma.control.ApiException;
import com.gamma.control.ErrorCodes;
import com.gamma.la.core.DraftStore;
import com.gamma.la.core.InvestigationEvaluator;
import com.gamma.la.core.SnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.gamma.la.core.InvestigationEvaluator.canonical;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-DRAFT-REBASE-COST-1: the rebase plan serialises each rebased state ONCE (hash and set file from the same canonical bytes) instead of
 * five times, and the fail-closed fold runs outside the main lock. Pinned byte-for-byte against the pre-fix loop, kept here as the oracle.
 */
class DraftRebaseCostTest {

    @TempDir Path tmp;

    /** Main moved by {@value #MOVED} steps after the fork, one of them the Draft's own first op (so that op is superseded). */
    private static final int MOVED = 3;

    @Test
    void rebasedLogSetsAndConflictReportAreByteIdenticalToThePreFixAlgorithm() throws Exception {
        for (int n : new int[] {1, 2, 30, 60}) {
            InvestigationRoutes.Inv view = moved(tmp.resolve("n" + n), n);
            DraftRebase.Plan want = legacy(view), got = DraftRebase.plan(view, DraftRebaseCostTest::replay);
            assertEquals(wire(want.conflicts()), wire(got.conflicts()), n + " steps: conflict report");
            assertEquals(want.lines(), got.lines(), n + " steps: lines");
            assertEquals(want.sets(), got.sets(), n + " steps: set files");
            assertEquals(want.steps(), got.steps());
            assertEquals(want.finalHash(), got.finalHash());
            assertEquals(1 + MAIN, got.required().get(0), "the Draft's seed of hub is superseded by main's");
            DraftRebase.verify(view.store().readLog(view.id()), got);
        }
    }

    /** The fail-closed fold still refuses a plan whose sealed hash its lines do not fold to. */
    @Test
    void verifyRefusesALineWhoseSealedHashTheFoldDoesNotReach() throws Exception {
        InvestigationRoutes.Inv view = moved(tmp, 20);
        DraftRebase.Plan p = DraftRebase.plan(view, DraftRebaseCostTest::replay);
        List<String> lines = new ArrayList<>(p.lines());
        lines.set(4, lines.get(4).replaceFirst("\"workingSetHash\":\"sha256:.", "\"workingSetHash\":\"sha256:Z"));
        DraftRebase.Plan bad = new DraftRebase.Plan(p.fromBase(), p.toBase(), p.toBaseHash(), p.draftLogHash(), p.conflicts(), lines, p.sets(),
                p.steps(), p.finalHash(), p.targetPins(), p.effective());
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> DraftRebase.verify(view.store().readLog(view.id()), bad));
        assertTrue(e.getMessage().contains("rebase equivalence failed at step"), e.getMessage());
    }

    /** The cost guard, as an operation count: one full-state serialisation per replayed op (+2: the empty and the base state), at any length. */
    @Test
    void oneFullStateSerialisationPerReplayedOpWhateverTheLength() throws Exception {
        for (int n : new int[] {25, 100}) {
            InvestigationRoutes.Inv view = moved(tmp.resolve("g" + n), n);
            long before = InvestigationEvaluator.serialisationCount();
            DraftRebase.Plan p = DraftRebase.plan(view, DraftRebaseCostTest::replay);
            long blocked = p.conflicts().stream().filter(c -> c.kind().equals("blocked")).count();
            assertEquals(2 + p.effective() - blocked, InvestigationEvaluator.serialisationCount() - before, n + " steps");
        }
    }

    /** The before/after curve (run with -Dinspecto.bench.rebase=true; prints PROFILE lines). */
    @Test
    @EnabledIfSystemProperty(named = "inspecto.bench.rebase", matches = "true")
    void profileCurve() throws Exception {
        for (int n : new int[] {100, 100, 200, 400, 800}) {
            InvestigationRoutes.Inv view = moved(tmp.resolve("p" + n + "_" + System.nanoTime()), n);
            List<String> mainLines = view.store().readLog(view.id());
            phase = new long[5];
            long t = System.nanoTime();
            legacy(view);
            long legacyMs = (System.nanoTime() - t) / 1_000_000;
            long[] ph = phase.clone();
            t = System.nanoTime();
            DraftRebase.Plan p = DraftRebase.plan(view, DraftRebaseCostTest::replay);
            long planMs = (System.nanoTime() - t) / 1_000_000;
            t = System.nanoTime();
            DraftRebase.verify(mainLines, p);
            long verifyMs = (System.nanoTime() - t) / 1_000_000;
            System.out.printf("PROFILE n=%d legacy-plan %d ms [replay %d, copy+apply %d, 2x hash (no-op test) %d, line+hash %d, setDoc %d] "
                            + "new-plan %d ms | commit fold (was under the main lock, now before it) %d ms%n",
                    n, legacyMs, ph[0] / 1_000_000, ph[1] / 1_000_000, ph[2] / 1_000_000, ph[3] / 1_000_000, ph[4] / 1_000_000, planMs, verifyMs);
        }
    }

    // ── the replay: InvestigationRoutes.replayOp's validation and entry shape, with the expand's sealed read standing in for an index read ──

    @SuppressWarnings("unchecked")
    static Map<String, Object> replay(Map<String, Object> orig, InvestigationEvaluator.State state, Map<String, Long> pins) {
        String op = String.valueOf(orig.get("op"));
        Map<String, Object> params = (Map<String, Object>) orig.get("params");
        List<String> ids = InvestigationEvaluator.strings(params.get("ids"));
        if (op.equals("hide") || op.equals("keep") || op.equals("annotate"))
            for (String i : ids)
                if (!state.entities.containsKey(i))
                    throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "an entity this op names is no longer in the Working Set");
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("op", op);
        e.put("params", params);
        if (op.equals("expand")) e.put("read", orig.get("read"));
        return e;
    }

    // ── the oracle: DraftRebase.computeHeavy's loop as it was before the fix (phase-timed for the profile) ──

    private static long[] phase = new long[5];

    private static DraftRebase.Plan legacy(InvestigationRoutes.Inv view) throws Exception {
        Path draftDir = view.draft().dir();
        List<String> mainLines = view.store().readLog(view.id());
        List<String> ownLines = SnapshotStore.readLogAt(draftDir);
        int m = mainLines.size(), k = view.draft().baseStep();
        List<Map<String, Object>> main = DraftRebase.parseAll(mainLines), own = DraftRebase.parseAll(ownLines);
        InvestigationEvaluator.State state = InvestigationEvaluator.evaluate(main, -1, null);
        Map<String, Long> pins = new LinkedHashMap<>();
        Set<Integer> mainUndone = InvestigationEvaluator.undone(main);
        Set<String> mainSigs = new HashSet<>();
        for (Map<String, Object> e : main)
            if ("op".equals(e.get("kind")) && DraftRebase.step(e) > k && !mainUndone.contains(DraftRebase.step(e)))
                mainSigs.add(e.get("op") + "|" + canonical(e.get("params")));
        String emptyHash = new InvestigationEvaluator.State().hash();
        Map<Integer, String> prevHash = new LinkedHashMap<>();
        String prev = k == 0 ? emptyHash : String.valueOf(main.get(k - 1).get("workingSetHash"));
        for (Map<String, Object> e : own) {
            prevHash.put(DraftRebase.step(e), prev);
            prev = String.valueOf(e.get("workingSetHash"));
        }
        List<DraftRebase.Conflict> conflicts = new ArrayList<>();
        List<String> lines = new ArrayList<>(), sets = new ArrayList<>();
        List<Integer> steps = new ArrayList<>();
        int next = m;
        for (Map<String, Object> orig : DraftRebase.effectiveOps(own)) {
            int os = DraftRebase.step(orig);
            String op = String.valueOf(orig.get("op"));
            Map<String, Object> fields;
            long t = System.nanoTime();
            try {
                fields = replay(orig, state, pins);
            } catch (ApiException refused) {
                conflicts.add(new DraftRebase.Conflict(os, op, "blocked", refused.getMessage(), null, null));
                continue;
            }
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("step", next + 1);
            e.put("kind", "op");
            e.put("author", orig.get("author"));
            e.put("at", orig.get("at"));
            e.putAll(fields);
            e.put("rebasedFrom", os);
            e = InvestigationRoutes.roundTrip(e);
            long t1 = System.nanoTime();
            InvestigationEvaluator.State after = state.copy();
            InvestigationEvaluator.apply(after, e);
            long t2 = System.nanoTime();
            boolean newChanged = !after.hash().equals(state.hash());
            long t3 = System.nanoTime();
            phase[0] += t1 - t;
            phase[1] += t2 - t1;
            phase[2] += t3 - t2;
            boolean oldChanged = !String.valueOf(orig.get("workingSetHash")).equals(prevHash.get(os));
            if (oldChanged && !newChanged) {
                if (mainSigs.contains(op + "|" + canonical(orig.get("params")))) {
                    conflicts.add(new DraftRebase.Conflict(os, op, "superseded", "main already holds an op with the same effect", null, null));
                    continue;
                }
                conflicts.add(new DraftRebase.Conflict(os, op, "no-op", "the op changed the old base and changes nothing on the new one", null, null));
            } else if (op.equals("expand")) {
                Map<?, ?> oldRead = (Map<?, ?>) orig.get("read"), newRead = (Map<?, ?>) e.get("read");
                if (oldRead != null && newRead != null && !String.valueOf(oldRead.get("fingerprint")).equals(String.valueOf(newRead.get("fingerprint"))))
                    conflicts.add(new DraftRebase.Conflict(os, op, "changed", "the re-sealed read differs from the sealed one",
                            ((Number) oldRead.get("rowCount")).intValue(), ((Number) newRead.get("rowCount")).intValue()));
            }
            next++;
            long t4 = System.nanoTime();
            e.put("workingSetHash", after.hash());
            lines.add(canonical(e));
            long t5 = System.nanoTime();
            sets.add(canonical(InvestigationRoutes.setDoc(next, after)));
            phase[3] += t5 - t4;
            phase[4] += System.nanoTime() - t5;
            steps.add(next);
            state = after;
        }
        return new DraftRebase.Plan(k, m, DraftStore.prefixHash(mainLines, m), DraftStore.prefixHash(ownLines, ownLines.size()), conflicts, lines,
                sets, steps, state.hash(), List.of(), DraftRebase.effectiveOps(own).size());
    }

    private static List<Map<String, Object>> wire(List<DraftRebase.Conflict> cs) {
        return cs.stream().map(DraftRebase.Conflict::wire).toList();
    }

    // ── fixture: DraftPromoteCostTest's Draft, then main moves on ──

    private static final int MAIN = 5;

    /** DraftPromoteCostTest's fixture (a 5-step main, a Draft of {@code n} ops), then main appends {@value #MOVED} steps: hub, m6, m7. */
    static InvestigationRoutes.Inv moved(Path writeRoot, int n) throws Exception {
        DraftPromoteCostTest.Fixture f = DraftPromoteCostTest.fixture(writeRoot, n);
        InvestigationRoutes.Inv main = f.main();
        InvestigationEvaluator.State s = InvestigationEvaluator.evaluate(DraftRebase.parseAll(main.store().readLog(main.id())), -1, null);
        String[] seeds = {"hub", "m6", "m7"};
        for (int i = 0; i < MOVED; i++) {
            int step = MAIN + i + 1;
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("step", step);
            e.put("kind", "op");
            e.put("op", "seed");
            e.put("params", Map.of("ids", List.of(seeds[i])));
            e.put("author", "lead");
            e = InvestigationRoutes.roundTrip(e);
            InvestigationEvaluator.apply(s, e);
            e.put("workingSetHash", s.hash());
            main.store().appendStep(main.id(), step, canonical(e), canonical(InvestigationRoutes.setDoc(step, s)));
        }
        return new InvestigationRoutes.Inv(main.store(), main.writeRoot(), main.id(), main.header(),
                new InvestigationRoutes.Inv.DraftRef(f.draftId(), MAIN, DraftStore.draftDir(main.dir(), f.draftId())));
    }
}
