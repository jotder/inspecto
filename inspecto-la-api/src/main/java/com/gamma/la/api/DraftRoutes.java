package com.gamma.la.api;

import com.gamma.control.ApiContext;
import com.gamma.control.ApiException;
import com.gamma.control.ErrorCodes;
import com.gamma.control.RouteModule;
import com.gamma.control.Subject;
import com.gamma.la.core.DraftStore;
import com.gamma.la.core.InvestigationEvaluator;
import com.gamma.la.core.InvestigationMembers.Role;
import com.gamma.la.core.LinkEventTypes;
import com.gamma.la.core.SnapshotStore;
import com.gamma.la.storage.IndexManifest;
import com.gamma.la.storage.IndexPins;
import com.gamma.la.storage.IndexStore;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.gamma.la.core.InvestigationEvaluator.canonical;

/**
 * D7-3 - <b>Drafts</b>: a member's own working copy of an Investigation (D16, D17; design
 * {@code docs/superpower/la-separation-d7-design.md} sections 3, 4, 9, 10). A Draft forks from the main log at
 * {@code baseStep k}; its state is the main log's first k entries folded, then its own entries - ONE list, because the
 * Draft's steps continue the numbering (k+1 ...), so the evaluator, the sealed payloads (D-E3) and the Working Set
 * relation run over it unchanged. There is no rebase or promote yet (D7-5): a Draft is explored, undone and discarded.
 *
 * <ul>
 *   <li>{@code POST /inv/investigations/{id}/drafts} - fork ({@code {at?}}); lead or analyst; ONE live Draft per member (409).</li>
 *   <li>{@code GET  .../drafts} - the caller's own Drafts; a lead and a reviewer see all (D7-Q8).</li>
 *   <li>{@code GET  .../drafts/{draftId}} - header, head step, and whether the main log has moved on (behind).</li>
 *   <li>{@code GET  .../drafts/{draftId}/log} · {@code /working-set} · {@code /replay} - reads.</li>
 *   <li>{@code POST .../drafts/{draftId}/ops} · {@code /undo} - the actor only; the SAME op validation as the main log.</li>
 *   <li>{@code POST .../drafts/{draftId}/discard} - the actor or a lead; releases the Draft's index pins.</li>
 * </ul>
 * ⛔ A Draft is NOT an Investigation and has no Dossier (D20): no dossier, bundle or evidence route is mounted for it.
 *
 * <p><b>Gate</b> (every route): the D7-1 member gate ({@link InvestigationRoutes#openAsMember}: non-member, Case member,
 * R3 and a PDP DENY all read as 404 absence) → the Draft id is exactly a server-generated one (422) → no such Draft 404 →
 * the Draft rule: reading another's Draft is a lead's or reviewer's (an analyst gets 404 - the Draft is absent to them);
 * writing is the actor's alone, so a lead or reviewer refused a write gets 403 (they can see it exists). The capability
 * ({@code canManageIncidents}) is the Case-work precedent every Investigation write follows; the real gate is the
 * actor check above, made again after the Draft is opened.
 */
public final class DraftRoutes implements RouteModule {

    private static final int PIN_WARN_DAYS = IndexPins.PIN_WARN_DAYS;
    private final InvestigationRoutes investigations = new InvestigationRoutes();

    @Override
    public void register(ApiContext api) {
        // ⚠ String LITERALS on purpose — CapabilityManifestTest's scanner matches only a literal argument.
        api.post("/inv/investigations/([^/]+)/drafts", ApiContext.withCapability("canManageIncidents",
                (e, m) -> fork(api, e, m.group(1), api.body(e))));
        api.get("/inv/investigations/([^/]+)/drafts", (e, m) -> list(api, e, m.group(1)));
        api.get("/inv/investigations/([^/]+)/drafts/([^/]+)", (e, m) -> get(api, e, m.group(1), m.group(2)));
        api.get("/inv/investigations/([^/]+)/drafts/([^/]+)/log", (e, m) -> log(api, e, m.group(1), m.group(2)));
        api.get("/inv/investigations/([^/]+)/drafts/([^/]+)/working-set", (e, m) -> workingSet(api, e, m.group(1), m.group(2)));
        api.get("/inv/investigations/([^/]+)/drafts/([^/]+)/replay", (e, m) -> replay(api, e, m.group(1), m.group(2)));
        api.post("/inv/investigations/([^/]+)/drafts/([^/]+)/ops", ApiContext.withCapability("canManageIncidents",
                (e, m) -> ops(api, e, m.group(1), m.group(2), api.body(e))));
        api.post("/inv/investigations/([^/]+)/drafts/([^/]+)/undo", ApiContext.withCapability("canManageIncidents",
                (e, m) -> undo(api, e, m.group(1), m.group(2))));
        api.post("/inv/investigations/([^/]+)/drafts/([^/]+)/discard", ApiContext.withCapability("canManageIncidents",
                (e, m) -> discard(api, e, m.group(1), m.group(2))));
    }

    // ── the gate ───────────────────────────────────────────────────────────────────────────────────────

    private enum Act { READ, WRITE, DISCARD }

    /** An opened Draft: the Investigation view working on it, its parsed header, and whether the main prefix is still what it forked from. */
    private record Opened(InvestigationRoutes.Inv view, Map<String, Object> header, boolean discarded, boolean baseIntact, int mainHead, Role role) {
        String draftId() { return view.draft().draftId(); }
    }

    private static Role roleOf(InvestigationRoutes.Inv inv, HttpExchange ex) throws IOException {
        Optional<Subject> s = ApiContext.subject(ex);
        if (s.isEmpty()) return Role.LEAD;   // no Subject attached: nothing is enforced, as everywhere in the control plane
        Role r = InvestigationMemberStore.roles(inv.dir(), inv.header().get("owner")).get(s.get().id());
        if (r == null) throw new ApiException(404, ErrorCodes.NOT_FOUND, "no investigation '" + inv.id() + "'");   // revoked between the gate and here
        return r;
    }

    private static Opened openDraft(ApiContext api, HttpExchange ex, String invId, String draftId, Act act) throws IOException {
        InvestigationRoutes.Inv inv = InvestigationRoutes.openAsMember(api, ex, invId);
        Role role = roleOf(inv, ex);
        if (!DraftStore.DRAFT_ID.matcher(draftId).matches())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "draft id must match " + DraftStore.DRAFT_ID.pattern() + ", got '" + draftId + "'");
        Path dir = DraftStore.draftDir(inv.dir(), draftId);
        String raw = DraftStore.readHeader(inv.dir(), draftId);
        if (raw == null) throw new ApiException(404, ErrorCodes.NOT_FOUND, "no draft '" + draftId + "' on investigation '" + invId + "'");
        @SuppressWarnings("unchecked") Map<String, Object> header = ApiContext.JSON.readValue(raw, LinkedHashMap.class);
        boolean enforced = ApiContext.subject(ex).isPresent();
        boolean mine = !enforced || ApiContext.actor(ex).equals(header.get("actor"));
        ApiException refusal = null;
        if (!mine) {
            // reading another's Draft is a lead's or reviewer's (D7-Q8); to anyone else it does not exist
            if (!role.canReadAnyDraft()) throw new ApiException(404, ErrorCodes.NOT_FOUND, "no draft '" + draftId + "' on investigation '" + invId + "'");
            if (act == Act.WRITE) refusal = new ApiException(403, ErrorCodes.PERMISSION_DENIED, "draft '" + draftId + "' belongs to '" + header.get("actor")
                    + "' - only its actor writes it");
            if (act == Act.DISCARD && role != Role.LEAD) refusal = new ApiException(403, ErrorCodes.PERMISSION_DENIED, "only the actor or a lead discards a draft");
        } else if (act == Act.WRITE && !role.canWriteDraft()) {
            refusal = new ApiException(403, ErrorCodes.PERMISSION_DENIED, "your role on investigation '" + invId + "' is " + role.wire() + " - it does not write a draft");
        }
        if (refusal != null) throw refusal;
        int baseStep = ((Number) header.get("baseStep")).intValue();
        List<String> main = inv.store().readLog(invId);
        boolean intact = main.size() >= baseStep && DraftStore.prefixHash(main, baseStep).equals(header.get("baseLogHash"));
        InvestigationRoutes.Inv view = new InvestigationRoutes.Inv(inv.store(), inv.writeRoot(), inv.id(), inv.header(),
                new InvestigationRoutes.Inv.DraftRef(draftId, baseStep, dir));
        return new Opened(view, header, DraftStore.isDiscarded(dir), intact, main.size(), role);
    }

    private static void requireLive(Opened o) {
        if (o.discarded()) throw new ApiException(409, ErrorCodes.CONFLICT, "draft '" + o.draftId() + "' was discarded");
    }

    /** A Draft whose main prefix no longer hashes to what it forked from is not evaluated: fail closed (the main log was rewritten). */
    private static void requireIntact(Opened o) {
        if (!o.baseIntact())
            throw new ApiException(409, ErrorCodes.CONFLICT, "the first " + o.view().draft().baseStep() + " steps of the main log changed since draft '"
                    + o.draftId() + "' forked - it cannot be evaluated against a different base");
    }

    // ── fork ───────────────────────────────────────────────────────────────────────────────────────────

    private Object fork(ApiContext api, HttpExchange ex, String invId, Map<String, Object> body) throws IOException {
        InvestigationRoutes.Inv inv = InvestigationRoutes.openAsMember(api, ex, invId);
        Role role = roleOf(inv, ex);
        if (!role.canWriteDraft())
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "your role on investigation '" + invId + "' is " + role.wire() + " - it does not fork a draft");
        String me = ApiContext.actor(ex);
        Object atRaw = body.get("at");
        if (atRaw != null && !(atRaw instanceof Number))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'at' must be a step number");
        String draftId = DraftStore.newId();
        List<Map<String, Object>> pinned = new ArrayList<>();
        Map<String, Object> header = new LinkedHashMap<>();
        synchronized (InvestigationRoutes.lock(DraftStore.draftsDir(inv.dir()))) {
            List<String> main = inv.store().readLog(invId);
            int head = main.size();
            int at = atRaw == null ? head : ((Number) atRaw).intValue();
            if (at < 0 || at > head)
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'at' must be between 0 and the head step " + head + ", got " + at);
            for (String other : DraftStore.listIds(inv.dir())) {   // D17: ONE live Draft per member per Investigation
                @SuppressWarnings("unchecked") Map<String, Object> h = ApiContext.JSON.readValue(DraftStore.readHeader(inv.dir(), other), LinkedHashMap.class);
                if (me.equals(h.get("actor")) && !DraftStore.isDiscarded(DraftStore.draftDir(inv.dir(), other)))
                    throw new ApiException(409, ErrorCodes.CONFLICT, "you already have a live draft on investigation '" + invId + "': " + other);
            }
            header.put("draftId", draftId);
            header.put("investigationId", invId);
            header.put("actor", me);
            header.put("createdAt", Instant.now().toString());
            header.put("baseStep", at);
            header.put("baseLogHash", DraftStore.prefixHash(main, at));
            try {
                pinIndexes(inv, draftId, pinned);   // D7-Q2: no index => nothing pinned, and the Draft works (seal-at-use, D-E3)
                Map<String, Object> pins = new LinkedHashMap<>();
                Map<String, Object> byDataset = new LinkedHashMap<>();
                if (!pinned.isEmpty()) byDataset.put(inv.dataset(), pinned.get(0).get("version"));
                pins.put("index", byDataset);
                pins.put("indexes", pinned);
                header.put("pins", pins);
                if (!DraftStore.create(inv.dir(), draftId, canonical(header)))
                    throw new ApiException(409, ErrorCodes.CONFLICT, "draft '" + draftId + "' already exists");
            } catch (IOException | RuntimeException failed) {
                unpinAll(inv.writeRoot(), inv.dataset(), pinned, draftId);   // a failed fork leaves no pin behind either
                throw failed;
            }
        }
        InvestigationRoutes.emit(ex, LinkEventTypes.LINK_DRAFT_FORKED, "link.draft.forked",
                "link.draft.forked — " + draftId + " of " + invId + " at step " + header.get("baseStep"),
                b -> b.attr("investigationId", invId).attr("draftId", draftId).attr("actor", me)
                        .attr("baseStep", header.get("baseStep")).attr("pins", pinned.size()));
        Opened o = openDraft(api, ex, invId, draftId, Act.READ);
        ex.getResponseHeaders().set("Location", "/api/v1/inv/investigations/" + invId + "/drafts/" + draftId);
        return ApiContext.respondJson(ex, 201, describe(o, true));
    }

    /**
     * Pin the CURRENT version of every index that serves this Investigation's bound columns (a Dataset may hold several
     * indexes, one per mapping). Without an index nothing is pinned. A pin the index refuses (the version vanished)
     * fails the fork - the caller unpins whatever was pinned so far.
     */
    private static void pinIndexes(InvestigationRoutes.Inv inv, String draftId, List<Map<String, Object>> pinned) throws IOException {
        Path root = inv.writeRoot().resolve(IndexRoutes.INDEX_DIR);
        List<String> hashes;
        try {
            hashes = IndexStore.mappingHashes(root, inv.dataset());
        } catch (IllegalArgumentException cannotNameADirectory) {
            return;
        }
        String src = String.valueOf(inv.header().get("sourceCol")), dst = String.valueOf(inv.header().get("targetCol"));
        for (String hash : hashes) {
            IndexStore store = new IndexStore(root, inv.dataset(), hash);
            Optional<Path> current = store.current();
            if (current.isEmpty()) continue;
            IndexManifest m;
            try {
                m = IndexManifest.read(current.get());
            } catch (IOException | IllegalArgumentException unreadable) {
                continue;   // an unreadable manifest is not an index
            }
            if (!inv.dataset().equals(m.dataset()) || !m.mapping().srcColumn().equalsIgnoreCase(src)
                    || !m.mapping().dstColumn().equalsIgnoreCase(dst)) continue;
            IndexPins.Pin p;
            try {
                p = store.pins().pin(m.version(), draftId);
            } catch (IllegalArgumentException vanished) {
                throw new ApiException(409, ErrorCodes.CONFLICT, "the index version to pin is gone (" + vanished.getMessage() + "); retry the fork");
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("dataset", inv.dataset());
            entry.put("mappingHash", hash);
            entry.put("version", m.version());
            entry.put("pinnedAt", p.pinnedAt().toString());
            pinned.add(entry);
        }
    }

    private static int unpinAll(Path writeRoot, String dataset, List<Map<String, Object>> pinned, String draftId) {
        int n = 0;
        Path root = writeRoot.resolve(IndexRoutes.INDEX_DIR);
        for (Map<String, Object> p : pinned) {
            try {
                if (new IndexStore(root, dataset, String.valueOf(p.get("mappingHash"))).pins().unpin(draftId)) n++;
            } catch (IOException | RuntimeException ignored) {
                // best effort: an unreleased pin is bounded by its TTL (D7-Q3)
            }
        }
        return n;
    }

    // ── reads ──────────────────────────────────────────────────────────────────────────────────────────

    private Object list(ApiContext api, HttpExchange ex, String invId) throws IOException {
        InvestigationRoutes.Inv inv = InvestigationRoutes.openAsMember(api, ex, invId);
        Role role = roleOf(inv, ex);
        boolean enforced = ApiContext.subject(ex).isPresent();
        boolean withDiscarded = "true".equals(ApiContext.query(ex, "discarded"));
        String me = ApiContext.actor(ex);
        List<Map<String, Object>> items = new ArrayList<>();
        for (String id : DraftStore.listIds(inv.dir())) {
            @SuppressWarnings("unchecked") Map<String, Object> h = ApiContext.JSON.readValue(DraftStore.readHeader(inv.dir(), id), LinkedHashMap.class);
            if (enforced && !role.canReadAnyDraft() && !me.equals(h.get("actor"))) continue;   // an analyst sees only their own
            Path dir = DraftStore.draftDir(inv.dir(), id);
            boolean discarded = DraftStore.isDiscarded(dir);
            if (discarded && !withDiscarded) continue;
            InvestigationRoutes.Inv view = new InvestigationRoutes.Inv(inv.store(), inv.writeRoot(), inv.id(), inv.header(),
                    new InvestigationRoutes.Inv.DraftRef(id, ((Number) h.get("baseStep")).intValue(), dir));
            items.add(describe(new Opened(view, h, discarded, true, inv.store().readLog(invId).size(), role), false));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("investigationId", invId);
        out.put("items", items);
        out.put("total", items.size());
        return out;
    }

    private Object get(ApiContext api, HttpExchange ex, String invId, String draftId) throws IOException {
        return describe(openDraft(api, ex, invId, draftId, Act.READ), true);
    }

    private Object log(ApiContext api, HttpExchange ex, String invId, String draftId) throws IOException {
        Opened o = openDraft(api, ex, invId, draftId, Act.READ);
        requireLive(o);
        requireIntact(o);
        return investigations.logOf(ex, o.view());
    }

    private Object workingSet(ApiContext api, HttpExchange ex, String invId, String draftId) throws IOException {
        Opened o = openDraft(api, ex, invId, draftId, Act.READ);
        requireLive(o);
        requireIntact(o);
        return WorkingSetRoutes.serve(ex, o.view());
    }

    /**
     * {@code GET .../drafts/{draftId}/replay} - the equivalence check (design section 6): re-fold the whole list (main prefix +
     * the Draft's own) from scratch and compare every position's hash with the one recorded at append time
     * ({@code mismatches}), and each own step's persisted {@code sets/<step>.json} hash with the fold ({@code setMismatches}).
     */
    private Object replay(ApiContext api, HttpExchange ex, String invId, String draftId) throws IOException {
        Opened o = openDraft(api, ex, invId, draftId, Act.READ);
        requireLive(o);
        requireIntact(o);
        InvestigationRoutes.Inv v = o.view();
        int base = v.draft().baseStep();
        List<Map<String, Object>> log = new ArrayList<>();
        for (String line : v.logLines()) {
            @SuppressWarnings("unchecked") Map<String, Object> m = ApiContext.JSON.readValue(line, Map.class);
            log.add(m);
        }
        List<String> hashes = new ArrayList<>();
        InvestigationEvaluator.State s = InvestigationEvaluator.evaluate(log, -1, hashes);
        List<Integer> mismatches = new ArrayList<>(), setMismatches = new ArrayList<>();
        for (int i = 0; i < hashes.size(); i++) {
            if (!hashes.get(i).equals(log.get(i).get("workingSetHash"))) mismatches.add(i + 1);
            if (i >= base) {
                Path set = v.draft().dir().resolve("sets").resolve((i + 1) + ".json");
                String raw = java.nio.file.Files.isRegularFile(set) ? java.nio.file.Files.readString(set, java.nio.charset.StandardCharsets.UTF_8) : null;
                @SuppressWarnings("unchecked") Map<String, Object> doc = raw == null ? null : ApiContext.JSON.readValue(raw, Map.class);
                if (doc == null || !hashes.get(i).equals(doc.get("hash"))) setMismatches.add(i + 1);
            }
        }
        Map<String, Object> ws = new LinkedHashMap<>(s.toMap());
        ws.put("hash", s.hash());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("investigationId", invId);
        out.put("draftId", draftId);
        out.put("baseStep", base);
        out.put("at", hashes.size());
        out.put("workingSet", ws);
        out.put("equivalent", mismatches.isEmpty() && setMismatches.isEmpty());
        out.put("mismatches", mismatches);
        out.put("setMismatches", setMismatches);
        return InvestigationRoutes.masked(v, out);
    }

    // ── writes ─────────────────────────────────────────────────────────────────────────────────────────

    private Object ops(ApiContext api, HttpExchange ex, String invId, String draftId, Map<String, Object> body) throws IOException {
        Opened o = openDraft(api, ex, invId, draftId, Act.WRITE);
        requireLive(o);
        requireIntact(o);
        return withDraftId(investigations.appendOpOn(api, ex, o.view(), body), draftId);
    }

    private Object undo(ApiContext api, HttpExchange ex, String invId, String draftId) throws IOException {
        Opened o = openDraft(api, ex, invId, draftId, Act.WRITE);
        requireLive(o);
        requireIntact(o);
        return withDraftId(investigations.undoOn(ex, o.view()), draftId);
    }

    @SuppressWarnings("unchecked")
    private static Object withDraftId(Object answer, String draftId) {
        if (!(answer instanceof Map<?, ?> m)) return answer;
        Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) m);
        copy.put("draftId", draftId);
        return copy;
    }

    /**
     * Discard: the actor, or a lead. {@code header.json} stays and a {@code discarded.json} marker is added - who, when, the
     * head step, the log's hash - as the audit record that the Draft existed; {@code log.jsonl} and {@code sets/} are DELETED,
     * because the sealed rows may hold personal data that should not outlive the Draft (the per-step audit events keep the ops
     * and fingerprints). Every index pin is released. Idempotent: a repeat answers {@code alreadyDiscarded} and re-runs the
     * (idempotent) unpin, so a discard that crashed between the marker and the unpin is finished by the retry.
     */
    private Object discard(ApiContext api, HttpExchange ex, String invId, String draftId) throws IOException {
        Opened o = openDraft(api, ex, invId, draftId, Act.DISCARD);
        InvestigationRoutes.Inv v = o.view();
        boolean first;
        int head;
        synchronized (InvestigationRoutes.lock(v.draft().dir())) {   // serialised with appends: none lands after the marker
            List<String> own = SnapshotStore.readLogAt(v.draft().dir());
            head = o.header().get("baseStep") instanceof Number n ? n.intValue() + own.size() : own.size();
            Map<String, Object> marker = new LinkedHashMap<>();
            marker.put("draftId", draftId);
            marker.put("discardedBy", ApiContext.actor(ex));
            marker.put("discardedAt", Instant.now().toString());
            marker.put("headStep", head);
            marker.put("logHash", DraftStore.prefixHash(own, own.size()));
            first = DraftStore.markDiscarded(v.draft().dir(), canonical(marker));
        }
        int unpinned = unpinAll(v.writeRoot(), v.dataset(), pinsOf(o.header()), draftId);
        if (first) {
            int step = head;
            InvestigationRoutes.emit(ex, LinkEventTypes.LINK_DRAFT_DISCARDED, "link.draft.discarded",
                    "link.draft.discarded — " + draftId + " of " + invId,
                    b -> b.attr("investigationId", invId).attr("draftId", draftId).attr("actor", ApiContext.actor(ex))
                            .attr("baseStep", v.draft().baseStep()).attr("step", step).attr("unpinned", unpinned));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("investigationId", invId);
        out.put("draftId", draftId);
        out.put("discarded", true);
        out.put("alreadyDiscarded", !first);
        out.put("unpinned", unpinned);
        return out;
    }

    // ── shape ──────────────────────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> pinsOf(Map<String, Object> header) {
        if (header.get("pins") instanceof Map<?, ?> p && p.get("indexes") instanceof List<?> l) return (List<Map<String, Object>>) l;
        return List.of();
    }

    /** A Draft as the API shows it: the header, its state, its head, and how far the main log has moved past its base. */
    private static Map<String, Object> describe(Opened o, boolean detail) throws IOException {
        InvestigationRoutes.Inv v = o.view();
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : List.of("draftId", "investigationId", "actor", "createdAt", "baseStep")) out.put(k, o.header().get(k));
        int base = v.draft().baseStep();
        int own = o.discarded() ? 0 : SnapshotStore.readLogAt(v.draft().dir()).size();
        out.put("state", o.discarded() ? "discarded" : "open");
        out.put("steps", own);
        out.put("headStep", base + own);
        int behind = Math.max(0, o.mainHead() - base);
        out.put("behind", behind);                       // main steps appended since the fork
        out.put("stale", behind > 0);
        Instant now = Instant.now();
        Path root = v.writeRoot().resolve(IndexRoutes.INDEX_DIR);
        List<Map<String, Object>> expiry = new ArrayList<>();
        if (!o.discarded())
            for (Map<String, Object> p : pinsOf(o.header())) {
                IndexStore store = new IndexStore(root, v.dataset(), String.valueOf(p.get("mappingHash")));
                for (IndexPins.Pin pin : store.pins().listPins()) {
                    if (!pin.pinId().equals(o.draftId())) continue;
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("dataset", p.get("dataset"));
                    e.put("version", pin.version());
                    e.put("expiresAt", pin.expiresAt().toString());
                    e.put("expired", !pin.expiresAt().isAfter(now));
                    e.put("expiresSoon", pin.expiresAt().isAfter(now) && !pin.expiresAt().isAfter(now.plus(java.time.Duration.ofDays(PIN_WARN_DAYS))));
                    expiry.add(e);
                }
            }
        out.put("pinWarning", expiry.stream().anyMatch(e -> Boolean.TRUE.equals(e.get("expiresSoon")) || Boolean.TRUE.equals(e.get("expired"))));
        if (detail) {
            out.put("baseLogHash", o.header().get("baseLogHash"));
            out.put("baseIntact", o.baseIntact());
            out.put("mainHead", o.mainHead());
            out.put("pins", o.header().get("pins"));
            out.put("pinExpiry", expiry);
            if (o.discarded()) {
                String marker = DraftStore.readDiscarded(v.draft().dir());
                if (marker != null) out.put("discarded", ApiContext.JSON.readValue(marker, LinkedHashMap.class));
            }
        }
        return out;
    }
}
