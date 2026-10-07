package com.gamma.la.api;

import com.gamma.la.core.InvestigationStore;
import com.gamma.la.core.InvestigationVersionConflictException;
import com.gamma.control.ApiContext;
import com.gamma.control.ApiException;
import com.gamma.control.ErrorCodes;
import com.gamma.la.core.DraftCheckpoints;
import com.gamma.la.core.DraftStore;
import com.gamma.la.core.InvestigationEvaluator;
import com.gamma.la.core.LinkEventTypes;
import com.gamma.la.storage.IndexStore;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.gamma.la.core.InvestigationEvaluator.canonical;

/**
 * D7-5 - promote: append a Draft's accepted (effective) ops to the MAIN log as new steps (design section 8).
 *
 * <p>Atomic and fail closed. Under the main lock THEN the Draft lock it re-verifies, from the files, that the Draft is open, that
 * its base IS the main head (it was rebased onto the current head) and that the main prefix still hashes to {@code baseLogHash}; the
 * new entries are folded over the main log and must reproduce the Draft's own state hash; they are then appended one by one
 * and, if any append or the {@code promoted.json} marker fails, the main log is truncated back to its old length and the new set
 * files removed - the main log either gains every step or none. Each appended entry gains
 * {@code draft{id, actor, baseStep, step, promotedBy}} provenance (the state hash is unaffected: it folds only op fields).
 *
 * <p><b>Four-eyes (D-U7), the call recorded in the design note:</b> a Draft cannot HOLD a sensitive expand (D7-3 refuses it), but the
 * Space's thresholds can fall after the op was taken. Sensitivity is therefore judged at PROMOTE against the thresholds then in
 * force: when any carried expand is sensitive nothing is appended - the promote is held as a PENDING request on the main log's
 * four-eyes queue and decided through the existing approve / deny routes by a lead or reviewer who is not the requester; approval
 * runs the same atomic promote and records {@code approval} on the sensitive entries.
 */
final class DraftPromote {

    private DraftPromote() { }

    /** Steps a promote re-sealed from the fold (one full-state serialisation each) - the cost regression test counts these. */
    static final java.util.concurrent.atomic.AtomicLong resealed = new java.util.concurrent.atomic.AtomicLong();

    /**
     * The Draft's sealed set file of {@code step} when it is the one today's writer produces for that step and hash, VERIFIED - its canonical
     * form (keys sorted) begins {@code {"hash":"<hash>","step":<step>,"workingSet":} and its working-set bytes hash to that hash - else null.
     */
    static boolean sealedSet(InvestigationStore store, String investigationId, String draftId, int step, Object hash) throws IOException {
        if (!(hash instanceof String h)) return false;
        String text = store.draftSet(investigationId, draftId, step).orElse(null);
        if (text == null) return false;
        String want = "{\"hash\":\"" + h + "\",\"step\":" + step + ",\"workingSet\":";
        if (text.length() <= want.length() || !text.endsWith("}") || !text.startsWith(want)) return false;
        // verify, never trust: the text between the head and the closing brace is canonical(state.toMap()) - exactly what the seal
        // hashed into workingSetHash (State.hash() = sha256(canonical(toMap()))) - so it must hash to the step's sealed hash
        return InvestigationEvaluator.sha256(text.substring(want.length(), text.length() - 1)).equals(h);
    }

    /** The sensitive carried expands: {@code [{step, exceeded}]} judged against the thresholds in force now. */
    static List<Map<String, Object>> sensitiveSteps(InvestigationRoutes.Inv inv, List<Map<String, Object>> effective) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> e : effective) {
            if (!"expand".equals(e.get("op"))) continue;
            @SuppressWarnings("unchecked") Map<String, Object> s = InvestigationRoutes.sensitivityOf(inv, (Map<String, Object>) e.get("params"));
            if (s == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("step", DraftRebase.step(e));
            m.put("exceeded", s.get("exceeded"));
            out.add(m);
        }
        return out;
    }

    /** Hold the promote as a pending four-eyes request (nothing is appended). */
    static Map<String, Object> hold(HttpExchange ex, InvestigationRoutes.Inv main, String draftId, Map<String, Object> header,
                                    List<Map<String, Object>> sensitive) throws IOException {
        if (ApiContext.subject(ex).isEmpty())
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "four-eyes needs an authenticated Subject - without one, the requester and "
                    + "the approver cannot be told apart");

        List<String> mainLines = main.store().log(InvestigationStore.Scope.main(main.id())), own = main.store().log(InvestigationStore.Scope.draft(main.id(), draftId));
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("kind", "promote");
        rec.put("op", "promote");
        rec.put("investigationId", main.id());
        rec.put("draftId", draftId);
        rec.put("draftActor", header.get("actor"));
        rec.put("baseStep", header.get("baseStep"));
        rec.put("mainHead", mainLines.size());
        rec.put("draftLogHash", DraftStore.prefixHash(own, own.size()));
        rec.put("sensitivity", sensitive);
        return InvestigationRoutes.requestPromote(ex, main, rec);
    }

    /** The approval of a held promote: run it, after checking the Draft and the main log are exactly what was requested. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> executeApproved(ApiContext api, HttpExchange ex, InvestigationRoutes.Inv main, Map<String, Object> rec,
                                               Map<String, Object> approval) throws IOException {
        String draftId = String.valueOf(rec.get("draftId"));
        if (!DraftStore.DRAFT_ID.matcher(draftId).matches())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "the request names no valid draft");
        Map<String, Object> out = execute(main, draftId, String.valueOf(rec.get("requestedBy")), approval, rec);
        audit(ex, main, out, String.valueOf(rec.get("draftActor")), approval.get("approvedBy"));
        return out;
    }

    /**
     * Promote {@code draftId} (see the class note). {@code request}, when set, is the held request being approved.
     *
     * <p>Everything is computed OUTSIDE any lock, from one read of the main log and the Draft: the checks, the re-fold and the sealing.
     * The store then verifies, atomically with the write, that the main log still holds exactly the entries this was computed over and
     * that the Draft's own log is the one it replayed ({@link InvestigationStore#promoteDraft}); a writer that moved either
     * under it is refused 409, never interleaved.
     */
    static Map<String, Object> execute(InvestigationRoutes.Inv main, String draftId, String promotedBy, Map<String, Object> approval,
                                       Map<String, Object> request) throws IOException {
        InvestigationStore store = main.store();
        InvestigationStore.Scope draftScope = InvestigationStore.Scope.draft(main.id(), draftId);
        String rawHeader = store.draftHeader(main.id(), draftId).orElse(null);
        if (rawHeader == null) throw new ApiException(404, ErrorCodes.NOT_FOUND, "no draft '" + draftId + "'");
        @SuppressWarnings("unchecked") Map<String, Object> header = ApiContext.JSON.readValue(rawHeader, LinkedHashMap.class);
        if (store.draftState(main.id(), draftId).closed()) throw new ApiException(409, ErrorCodes.CONFLICT, "draft '" + draftId + "' was closed (discarded or promoted)");
        String actor = String.valueOf(header.get("actor"));
        int base = ((Number) header.get("baseStep")).intValue();
        List<String> mainLines = store.log(InvestigationStore.Scope.main(main.id()));
        if (base != mainLines.size())
            throw new ApiException(409, ErrorCodes.CONFLICT, "must rebase: the draft is based on step " + base + " and the main log is at "
                    + mainLines.size() + " - rebase it onto the current head, then promote");
        String mainHash = DraftStore.prefixHash(mainLines, mainLines.size());
        if (!DraftStore.prefixHash(mainLines, base).equals(String.valueOf(header.get("baseLogHash"))))
            throw new ApiException(409, ErrorCodes.CONFLICT, "the main log changed under the draft's base - it cannot be promoted");
        if (DraftRoutes.expiredPin(main, header))
            throw new ApiException(409, ErrorCodes.CONFLICT, "must rebase: the index version this draft pinned expired");
        List<String> ownLines = store.log(draftScope);
        String ownHash = DraftStore.prefixHash(ownLines, ownLines.size());
        if (request != null && (!ownHash.equals(String.valueOf(request.get("draftLogHash")))
                || !String.valueOf(mainLines.size()).equals(String.valueOf(request.get("mainHead")))))
            throw new ApiException(409, ErrorCodes.CONFLICT, "the draft or the main log changed since the promote was requested - deny it and request again");
        List<Map<String, Object>> mainEntries = DraftRebase.parseAll(mainLines), own = DraftRebase.parseAll(ownLines);
        // an undo (or an undone op) would renumber the steps the state records, so the promoted entries could not reproduce
        // the Draft's state: such a Draft rebases first (a rebase compacts them away)
        for (Map<String, Object> e : own)
            if (!"op".equals(e.get("kind")))
                throw new ApiException(409, ErrorCodes.CONFLICT, "must rebase: the draft holds undone steps - rebase it (which compacts them away), then promote");
        List<Map<String, Object>> effective = DraftRebase.effectiveOps(own);
        if (effective.isEmpty()) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "the draft has no ops to promote");
        List<Map<String, Object>> sensitiveNow = sensitiveSteps(main, effective);
        if (!sensitiveNow.isEmpty() && approval == null)
            throw new ApiException(409, ErrorCodes.CONFLICT, "this promote carries a sensitive expand " + sensitiveNow + " - it needs four-eyes approval");

        // re-fold: the new entries over the main log must reproduce the Draft's own state (undone ops compacted away)
        List<Map<String, Object>> all = new ArrayList<>(mainEntries);
        all.addAll(own);
        String draftState = InvestigationEvaluator.evaluate(all, -1, null).hash();
        InvestigationEvaluator.State state = InvestigationEvaluator.evaluate(mainEntries, -1, null);
        // LA-DRAFT-PROMOTE-COST-1: an undo-free Draft based at the main head numbers its steps exactly as they land on main, and
        // the provenance added here is not folded - so the state after promoted step i IS the Draft's own state after its step i.
        // Its own sealed workingSetHash and set are therefore today's output byte for byte, and are reused instead of
        // re-serialising the whole state per step (O(state) per step - quadratic in the Draft's steps). A step whose set is
        // missing, or whose text does not hash to that hash, is re-sealed from the fold, as before; the final hash is checked below.
        List<String> lines = new ArrayList<>(), sets = new ArrayList<>();
        String lastHash = null;
        int step = mainLines.size();
        for (Map<String, Object> o : effective) {
            Map<String, Object> e = new LinkedHashMap<>(o);
            e.put("step", ++step);
            Map<String, Object> prov = new LinkedHashMap<>();
            prov.put("id", draftId);
            prov.put("actor", actor);
            prov.put("baseStep", base);
            prov.put("step", o.get("step"));
            prov.put("promotedBy", promotedBy);
            e.put("draft", prov);
            boolean sensitive = sensitiveNow.stream().anyMatch(s -> s.get("step").equals(DraftRebase.step(o)));
            if (sensitive) e.put("approval", approval);
            e.remove("workingSetHash");
            e = InvestigationRoutes.roundTrip(e);
            InvestigationEvaluator.apply(state, e);
            if (sealedSet(store, main.id(), draftId, step, o.get("workingSetHash"))) {
                lastHash = String.valueOf(o.get("workingSetHash"));
                sets.add(null);   // null = seal the Draft's own set of this step as it is
            } else {
                resealed.incrementAndGet();
                lastHash = state.hash();
                sets.add(canonical(InvestigationRoutes.setDoc(step, state)));
            }
            e.put("workingSetHash", lastHash);
            lines.add(canonical(e));
        }
        if (!state.hash().equals(draftState) || !draftState.equals(lastHash))
            throw new IllegalStateException("promote equivalence failed: the appended entries do not reproduce the draft's state");

        int from = mainLines.size();
        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put("draftId", draftId);
        marker.put("promotedBy", promotedBy);
        marker.put("promotedAt", Instant.now().toString());
        marker.put("fromStep", from);
        marker.put("toStep", from + lines.size());
        marker.put("steps", lines.size());
        if (approval != null) marker.put("approvedBy", approval.get("approvedBy"));
        try {
            store.promoteDraft(main.id(), draftId, from, mainHash, ownHash, lines, sets, canonical(marker));
        } catch (InvestigationVersionConflictException moved) {
            throw new ApiException(409, ErrorCodes.CONFLICT, "the main log or draft '" + draftId + "' changed while the promote was computed"
                    + " - read them again, rebase if the draft is behind, and retry");
        } catch (InvestigationStore.DraftClosedException closed) {
            throw new ApiException(409, ErrorCodes.CONFLICT, "draft '" + draftId + "' was closed (discarded or promoted)");
        }
        int to = from + lines.size();
        DraftCheckpoints.forget(store.cacheKey(draftScope));
        Path root = main.writeRoot().resolve(IndexRoutes.INDEX_DIR);
        for (Map<String, Object> p : DraftRebase.pinsOf(header))
            try {
                new IndexStore(root, main.dataset(), String.valueOf(p.get("mappingHash"))).pins().unpin(draftId);
            } catch (IOException | RuntimeException ignored) {
                // bounded by its TTL (D7-Q3)
            }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("investigationId", main.id());
        out.put("draftId", draftId);
        out.put("promoted", true);
        out.put("fromStep", from);
        out.put("toStep", to);
        out.put("steps", to - from);
        return out;
    }

    static void audit(HttpExchange ex, InvestigationRoutes.Inv main, Map<String, Object> out, String actor, Object approvedBy) {
        InvestigationRoutes.emit(ex, LinkEventTypes.LINK_DRAFT_PROMOTED, "link.draft.promoted",
                "link.draft.promoted - " + out.get("draftId") + " of " + main.id() + " steps " + out.get("fromStep") + ".." + out.get("toStep"),
                b -> {
                    b.attr("investigationId", main.id()).attr("draftId", out.get("draftId")).attr("actor", actor)
                            .attr("promotedBy", ApiContext.actor(ex)).attr("fromStep", out.get("fromStep"))
                            .attr("toStep", out.get("toStep")).attr("steps", out.get("steps"));
                    if (approvedBy != null) b.attr("approvedBy", approvedBy);
                    return b;
                });
    }
}
