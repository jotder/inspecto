package com.gamma.la.api;

import com.gamma.control.ApiContext;
import com.gamma.control.ApiException;
import com.gamma.control.ErrorCodes;
import com.gamma.la.core.DraftCheckpoints;
import com.gamma.la.core.DraftStore;
import com.gamma.la.core.InvestigationEvaluator;
import com.gamma.la.core.SnapshotStore;
import com.gamma.la.storage.IndexPins;
import com.gamma.la.storage.IndexStore;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static com.gamma.la.core.InvestigationEvaluator.canonical;

/**
 * D7-5 - rebase a Draft onto the CURRENT main head and report what that did (design {@code la-separation-d7-design.md} section 8).
 *
 * <p>The Draft's EFFECTIVE ops (not undone; the undo entries and undone ops are compacted away) are re-applied, in order, over
 * {@code main[1..M]} through {@link InvestigationRoutes#replayOp} - the validation an append runs - and every one is classified:
 * <ul>
 *   <li>{@code no-op} - it changed the old base's Working Set and changes nothing on the new one; kept, reported.</li>
 *   <li>{@code changed} - an expand whose re-sealed rows differ from what it sealed; kept, reported with both counts.</li>
 *   <li>{@code superseded} - a no-op here AND main already holds an op with the same effect (same op, same params); dropped only
 *       once the analyst CONFIRMS it (D7-Q7: nothing leaves the record silently).</li>
 *   <li>{@code blocked} - the op is refused on the new base (a named entity left, a merged exclude's group no longer resolves, nothing
 *       left to expand); never carried, so the rebase needs it confirmed as dropped.</li>
 * </ul>
 * A report holds step numbers, kinds and counts - never an entity id or a row.
 */
final class DraftRebase {

    private DraftRebase() { }

    record Conflict(int draftStep, String op, String kind, String detail, Integer oldRows, Integer newRows) {
        boolean needsConfirm() {
            return kind.equals("superseded") || kind.equals("blocked");
        }

        Map<String, Object> wire() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("step", draftStep);
            m.put("op", op);
            m.put("kind", kind);
            m.put("detail", detail);
            m.put("requiresConfirm", needsConfirm());
            if (oldRows != null) m.put("oldRows", oldRows);
            if (newRows != null) m.put("newRows", newRows);
            return m;
        }
    }

    /** One computed rebase: nothing is written until {@link #commit}. */
    record Plan(int fromBase, int toBase, String toBaseHash, String draftLogHash, List<Conflict> conflicts, List<String> lines,
                List<String> sets, List<Integer> steps, String finalHash, List<Map<String, Object>> targetPins, int effective) {
        List<Integer> required() {
            return conflicts.stream().filter(Conflict::needsConfirm).map(Conflict::draftStep).toList();
        }
    }

    static List<Map<String, Object>> parseAll(List<String> lines) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String l : lines) out.add(ApiContext.JSON.readValue(l, LinkedHashMap.class));
        return out;
    }

    /** The Draft's effective ops: op entries no later undo reverted, in step order. */
    static List<Map<String, Object>> effectiveOps(List<Map<String, Object>> own) {
        Set<Integer> undone = InvestigationEvaluator.undone(own);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> e : own)
            if ("op".equals(e.get("kind")) && !undone.contains(((Number) e.get("step")).intValue())) out.add(e);
        return out;
    }

    static int step(Map<String, Object> e) {
        return ((Number) e.get("step")).intValue();
    }

    /** Compute the rebase of {@code view}'s Draft onto the main log's current head. Reads (re-seals) but writes nothing. */
    static Plan compute(InvestigationRoutes routes, ApiContext api, HttpExchange ex, InvestigationRoutes.Inv view) throws IOException {
        // D7-6: the replay re-reads the index / Dataset - a heavy job, admitted under the heavy-job cap (429 when full)
        return DraftAdmission.heavy("rebase replay of draft " + view.draft().draftId(), () -> computeHeavy(routes, api, ex, view));
    }

    private static Plan computeHeavy(InvestigationRoutes routes, ApiContext api, HttpExchange ex, InvestigationRoutes.Inv view) throws IOException {
        Path draftDir = view.draft().dir();
        List<String> mainLines = view.store().readLog(view.id());
        List<String> ownLines = SnapshotStore.readLogAt(draftDir);
        int m = mainLines.size(), k = view.draft().baseStep();
        List<Map<String, Object>> main = parseAll(mainLines), own = parseAll(ownLines);
        InvestigationEvaluator.State state = InvestigationEvaluator.evaluate(main, -1, null);

        // the version each pinned index is read at, resolved ONCE: the same versions are then pinned (never CURRENT drifting between)
        List<Map<String, Object>> target = DraftRoutes.currentIndexes(view);
        Map<String, Long> pins = new LinkedHashMap<>();
        for (Map<String, Object> t : target) pins.put(String.valueOf(t.get("mappingHash")), ((Number) t.get("version")).longValue());

        // what main did since the fork: effective op signatures (op + canonical params) of steps k+1..M
        Set<Integer> mainUndone = InvestigationEvaluator.undone(main);
        Set<String> mainSigs = new HashSet<>();
        for (Map<String, Object> e : main)
            if ("op".equals(e.get("kind")) && step(e) > k && !mainUndone.contains(step(e)))
                mainSigs.add(e.get("op") + "|" + canonical(e.get("params")));

        String emptyHash = new InvestigationEvaluator.State().hash();
        Map<Integer, String> prevHash = new LinkedHashMap<>();
        String prev = k == 0 ? emptyHash : String.valueOf(main.get(k - 1).get("workingSetHash"));
        for (Map<String, Object> e : own) {
            prevHash.put(step(e), prev);
            prev = String.valueOf(e.get("workingSetHash"));
        }

        List<Conflict> conflicts = new ArrayList<>();
        List<String> lines = new ArrayList<>(), sets = new ArrayList<>();
        List<Integer> steps = new ArrayList<>();
        int next = m;
        for (Map<String, Object> orig : effectiveOps(own)) {
            int os = step(orig);
            String op = String.valueOf(orig.get("op"));
            Map<String, Object> fields;
            try {
                fields = routes.replayOp(api, ex, view, orig, state, pins);
            } catch (ApiException refused) {
                conflicts.add(new Conflict(os, op, "blocked", refused.getMessage(), null, null));
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
            InvestigationEvaluator.State after = state.copy();
            InvestigationEvaluator.apply(after, e);
            boolean newChanged = !after.hash().equals(state.hash());
            boolean oldChanged = !String.valueOf(orig.get("workingSetHash")).equals(prevHash.get(os));
            if (oldChanged && !newChanged) {
                if (mainSigs.contains(op + "|" + canonical(orig.get("params")))) {
                    conflicts.add(new Conflict(os, op, "superseded", "main already holds an op with the same effect", null, null));
                    continue;
                }
                conflicts.add(new Conflict(os, op, "no-op", "the op changed the old base and changes nothing on the new one", null, null));
            } else if (op.equals("expand")) {
                Map<?, ?> oldRead = (Map<?, ?>) orig.get("read"), newRead = (Map<?, ?>) e.get("read");
                if (oldRead != null && newRead != null && !String.valueOf(oldRead.get("fingerprint")).equals(String.valueOf(newRead.get("fingerprint"))))
                    conflicts.add(new Conflict(os, op, "changed", "the re-sealed read differs from the sealed one",
                            ((Number) oldRead.get("rowCount")).intValue(), ((Number) newRead.get("rowCount")).intValue()));
            }
            next++;
            e.put("workingSetHash", after.hash());
            lines.add(canonical(e));
            sets.add(canonical(InvestigationRoutes.setDoc(next, after)));
            steps.add(next);
            state = after;
        }
        return new Plan(k, m, DraftStore.prefixHash(mainLines, m), DraftStore.prefixHash(ownLines, ownLines.size()), conflicts, lines, sets,
                steps, state.hash(), target, effectiveOps(own).size());
    }

    /** The steps the analyst must confirm (superseded and blocked) that {@code confirm} does not name; 422 for a step with nothing to confirm. */
    static List<Integer> unconfirmed(Plan plan, List<Integer> confirm) {
        Set<Integer> required = new HashSet<>(plan.required());
        for (Integer c : confirm)
            if (!required.contains(c))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "step " + c + " has no superseded or blocked conflict to confirm");
        List<Integer> missing = new ArrayList<>();
        for (Integer r : plan.required()) if (!confirm.contains(r)) missing.add(r);
        return missing;
    }

    /**
     * Swap the rebased Draft in. Under the main lock THEN the Draft lock (the order promote uses): the main log must still be exactly
     * the {@code M} entries the plan was computed over and the Draft's log byte-identical to the one it replayed, else 409. The new
     * index pins are taken first (a pin moves: it renews the TTL) and put back if the swap fails.
     */
    static Map<String, Object> commit(ApiContext api, HttpExchange ex, InvestigationRoutes.Inv view, Map<String, Object> header, Plan plan,
                                      List<Integer> confirmed) throws IOException {
        Path draftDir = view.draft().dir();
        String draftId = view.draft().draftId();
        synchronized (InvestigationRoutes.lock(view.dir())) {
            synchronized (InvestigationRoutes.lock(draftDir)) {
                if (DraftStore.isClosed(draftDir)) throw new ApiException(409, ErrorCodes.CONFLICT, "draft '" + draftId + "' was closed");
                List<String> mainLines = view.store().readLog(view.id());
                if (mainLines.size() != plan.toBase() || !DraftStore.prefixHash(mainLines, mainLines.size()).equals(plan.toBaseHash()))
                    throw new ApiException(409, ErrorCodes.CONFLICT, "the main log moved while the rebase was computed (head " + mainLines.size()
                            + ", was " + plan.toBase() + ") - read the conflict report again");
                List<String> ownLines = SnapshotStore.readLogAt(draftDir);
                if (!DraftStore.prefixHash(ownLines, ownLines.size()).equals(plan.draftLogHash()))
                    throw new ApiException(409, ErrorCodes.CONFLICT, "the draft changed while the rebase was computed - read the conflict report again");

                // fail closed: the carried log must fold to exactly the state the plan reached
                List<Map<String, Object>> all = new ArrayList<>(parseAll(mainLines));
                all.addAll(parseAll(plan.lines()));
                List<String> hashes = new ArrayList<>();
                InvestigationEvaluator.State folded = InvestigationEvaluator.evaluate(all, -1, hashes);
                for (int i = 0; i < hashes.size(); i++)
                    if (!hashes.get(i).equals(all.get(i).get("workingSetHash")))
                        throw new IllegalStateException("rebase equivalence failed at step " + (i + 1));
                if (!folded.hash().equals(plan.finalHash())) throw new IllegalStateException("rebase equivalence failed: final state differs");

                Path root = view.writeRoot().resolve(IndexRoutes.INDEX_DIR);
                List<Map<String, Object>> oldPins = pinsOf(header), newPins = new ArrayList<>();
                try {
                    for (Map<String, Object> t : plan.targetPins()) {
                        IndexPins.Pin p = new IndexStore(root, view.dataset(), String.valueOf(t.get("mappingHash")))
                                .pins().pin(((Number) t.get("version")).longValue(), draftId);
                        Map<String, Object> n = new LinkedHashMap<>(t);
                        n.put("pinnedAt", p.pinnedAt().toString());
                        newPins.add(n);
                    }
                    Map<String, Object> h = new LinkedHashMap<>(header);
                    h.put("baseStep", plan.toBase());
                    h.put("baseLogHash", plan.toBaseHash());
                    Map<String, Object> pinsDoc = new LinkedHashMap<>();
                    Map<String, Object> byDataset = new LinkedHashMap<>();
                    if (!newPins.isEmpty()) byDataset.put(view.dataset(), newPins.get(0).get("version"));
                    pinsDoc.put("index", byDataset);
                    pinsDoc.put("indexes", newPins);
                    h.put("pins", pinsDoc);
                    List<Object> history = new ArrayList<>();
                    if (header.get("rebases") instanceof List<?> l) history.addAll(l);
                    Map<String, Object> rec = new LinkedHashMap<>();
                    rec.put("at", Instant.now().toString());
                    rec.put("by", ApiContext.actor(ex));
                    rec.put("fromBase", plan.fromBase());
                    rec.put("toBase", plan.toBase());
                    rec.put("carried", plan.lines().size());
                    rec.put("confirmedDropped", confirmed);
                    history.add(rec);
                    h.put("rebases", history);
                    DraftStore.replaceRebased(draftDir, canonical(h), plan.lines(), plan.sets(), plan.steps());
                } catch (IOException | RuntimeException failed) {
                    restorePins(root, view, draftId, oldPins, newPins);
                    throw failed;
                }
                DraftCheckpoints.forget(draftDir);
                // an index the Draft pinned that no longer serves its columns is released
                Set<String> keep = new HashSet<>();
                for (Map<String, Object> n : newPins) keep.add(String.valueOf(n.get("mappingHash")));
                for (Map<String, Object> o : oldPins)
                    if (!keep.contains(String.valueOf(o.get("mappingHash"))))
                        try {
                            new IndexStore(root, view.dataset(), String.valueOf(o.get("mappingHash"))).pins().unpin(draftId);
                        } catch (IOException | RuntimeException ignored) {
                            // bounded by its TTL
                        }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("investigationId", view.id());
        out.put("draftId", draftId);
        out.put("fromBase", plan.fromBase());
        out.put("toBase", plan.toBase());
        out.put("carried", plan.lines().size());
        out.put("droppedConfirmed", confirmed);
        out.put("conflicts", plan.conflicts().stream().map(Conflict::wire).toList());
        out.put("headStep", plan.toBase() + plan.lines().size());
        out.put("workingSetHash", plan.finalHash());
        out.put("pins", newPinsWire(plan));
        return out;
    }

    private static List<Map<String, Object>> newPinsWire(Plan plan) {
        List<Map<String, Object>> l = new ArrayList<>();
        for (Map<String, Object> t : plan.targetPins()) l.add(new TreeMap<>(t));
        return l;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> pinsOf(Map<String, Object> header) {
        if (header.get("pins") instanceof Map<?, ?> p && p.get("indexes") instanceof List<?> l) return (List<Map<String, Object>>) l;
        return List.of();
    }

    /** Best effort: put each pin back where it was (an index the Draft did not hold before is released). */
    private static void restorePins(Path root, InvestigationRoutes.Inv view, String draftId, List<Map<String, Object>> oldPins,
                                    List<Map<String, Object>> newPins) {
        Set<String> had = new HashSet<>();
        for (Map<String, Object> o : oldPins) {
            had.add(String.valueOf(o.get("mappingHash")));
            try {
                new IndexStore(root, view.dataset(), String.valueOf(o.get("mappingHash"))).pins().pin(((Number) o.get("version")).longValue(), draftId);
            } catch (IOException | RuntimeException ignored) {
                // the version may be gone: the TTL bounds it
            }
        }
        for (Map<String, Object> n : newPins)
            if (!had.contains(String.valueOf(n.get("mappingHash"))))
                try {
                    new IndexStore(root, view.dataset(), String.valueOf(n.get("mappingHash"))).pins().unpin(draftId);
                } catch (IOException | RuntimeException ignored) {
                    // bounded by its TTL
                }
    }
}
