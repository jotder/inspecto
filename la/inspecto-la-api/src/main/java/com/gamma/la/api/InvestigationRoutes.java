package com.gamma.la.api;

import com.gamma.la.core.InvestigationStore;
import com.gamma.la.core.InvestigationStores;
import com.gamma.la.core.InvestigationVersionConflictException;
import com.gamma.la.core.LinkEventTypes;
import com.gamma.la.core.InvestigationEvaluator;
import com.gamma.la.core.InvestigationMembers;
import com.gamma.la.core.DatasetProviders;
import com.gamma.la.core.DatasetProvider;
import com.gamma.la.core.AdmiraltyGrade;
import com.gamma.la.core.InvestigationTime;
import com.gamma.la.core.LinkIds;
import com.gamma.la.core.SnapshotStore;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.auth.ApiException;
import com.gamma.access.ComponentAccess;
import com.gamma.entitystore.EntityTypes;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.entitystore.LinkAnalysisSettings;
import com.gamma.access.RowScope;
import com.gamma.spi.http.RouteModule;
import com.gamma.spi.auth.Subject;
import com.gamma.access.WriteGates;
import com.gamma.entitystore.EntityFactLog;
import com.gamma.entitystore.EntityListFacts;
import com.gamma.entitystore.EntityRegistry;
import com.gamma.audit.Event;
import com.gamma.audit.EventSink;
import com.gamma.util.SqlIdent;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import static com.gamma.la.core.InvestigationEvaluator.canonical;
import static com.gamma.la.core.InvestigationEvaluator.evaluate;
import static com.gamma.la.core.InvestigationEvaluator.strings;

/**
 * The <b>Investigation</b> object (LA-10, {@code docs/archived-documents/plans-archive/link-analysis-backlog-plan.md} §2, §5.5): an
 * ordered, append-only op log over one Dataset + projection mapping, and the <b>Working Set</b> it evaluates to.
 *
 * <ul>
 *   <li>{@code GET /inv/investigations} — the Investigations the caller may read (owned, or shared via an open
 *       linked Case), paged; see {@link #list}.</li>
 *   <li>{@code POST /inv/investigations} — create, bound to {@code {dataset, sourceCol, targetCol, linkKindCol?,
 *       timeCol?, timeColZone?}}.</li>
 *   <li>{@code POST /inv/investigations/{id}/ops} — append one op ({@code seed · expand · exclude · hide · keep ·
 *       window · annotate · excludeBy · seedBy · resolve}); answers the Working Set DELTA and {@code truncated}. An
 *       {@code expand} is one hop-ladder rung (LA-13, plan §2.4) — see {@link #expandParams}. The two Entity List
 *       ops (LA-17) SEAL the list as it stands at the fact log's head — see {@link #sealList} and
 *       {@link #seedRead}. A {@code resolve} (LA-17 slice 2) SEALS the Space's identity resolution at a pinned
 *       fact seq — see {@link #sealResolution}.</li>
 *   <li>{@code POST /inv/investigations/{id}/undo} — real undo: a log edit that reverts the latest op.</li>
 *   <li>{@code POST /inv/investigations/{id}/reorder} — re-ordering FORKS (D-E4): a new Investigation with explicit
 *       parent lineage; the original log, its Working Sets and any Artifact anchored to them are untouched.</li>
 *   <li>{@code POST /inv/investigations/{id}/replay} — full evaluation from the sealed log, with the equivalence
 *       check against the hashes recorded at append time, and — with {@code reread} — a drift check against
 *       current data.</li>
 *   <li>{@code GET /inv/investigations/{id}/log} — the ordered log, each step rendered as a plain-language line, and
 *       the pending sensitive expands (D-U7).</li>
 *   <li>{@code POST /inv/investigations/{id}/reveal} — reveal masked entity ids, per entity (D-U6).</li>
 *   <li>{@code POST /inv/investigations/{id}/pending/{rid}/approve} · {@code .../deny} — four-eyes (D-U7).</li>
 * </ul>
 *
 * <p><b>LA-19 controls (operator decisions 2026-09-24).</b> <i>Purpose</i> (D-U5): every Investigation states a
 * {@code purpose} — its legal basis — at create; it is sealed in the write-once header and shown in the Dossier, and
 * NOT enforced. <i>Masking</i> (D-U6): every response carrying entity ids is masked per the Space's
 * {@code maskingMode} ({@link EntityMasking}); a holder of {@code canRevealLinkEntities} reveals one entity at a time,
 * audited. <i>Four-eyes</i> (D-U7): an {@code expand} whose budget or fan-out exceeds the Space's threshold does not
 * run — it becomes a PENDING request that runs only when a DIFFERENT Subject holding
 * {@code canApproveLinkExpansions} approves it.
 *
 * <p><b>SEAL NOW, versioned reads deferred (D-E3).</b> There is no version-addressable Dataset read in the
 * backend, so {@code datasetVersion} is always {@code null}. Instead every Dataset-reading step MATERIALISES what
 * it read — the folded rows — and fingerprints them (SHA-256); the Dataset name and read time are recorded as
 * weak provenance, explicitly NOT a replay pin. Replay therefore evaluates the sealed reads and cannot move
 * (G-E11); {@code reread} re-runs each recorded query and reports drift (G-E3). ⛔ Byte-identical replay against
 * a PINNED version (G-E2) is the one thing this does not give, by decision.
 *
 * <p><b>Store (D-E2).</b> The log and every step's Working Set persist in {@link SnapshotStore} — the same
 * durable store as snapshots, under {@code audit/snapshots/investigations/<id>/}.
 *
 * <p><b>Access (D7-1, amends D-E7).</b> MEMBERS: the creating Subject is the implicit first {@code lead}, and a
 * lead may grant {@code analyst} / {@code reviewer} / {@code lead} (see {@link InvestigationMembers} and
 * {@link InvestigationMemberRoutes}). Every member READS; only a lead writes the main log (a lead-only {@link #open}).
 * Anyone else gets a 404 indistinguishable from absence (the R3 answer). An Investigation with no
 * {@code members.jsonl} behaves exactly as owner-only always did. The Enterprise PDP can only NARROW the member rule.
 * With no Subject attached nothing is enforced,
 * as everywhere else in the control plane. Every route additionally applies the R3 Dataset gate: a bound
 * Dataset the caller can no longer view reads as absent, and every Dataset READ goes through the same
 * {@code ComponentAccess.canView} check {@code InvRoutes.relationFor} applies.
 *
 * <p>Mutating routes are gated on {@code canManageIncidents}, as {@code POST /inv/snapshots} is: the log is
 * evidence and belongs to Case work. {@code /replay} persists nothing and takes the read-shaped exemption.
 */
public final class InvestigationRoutes implements RouteModule {

    private static final Pattern SAFE_IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    /** The fourteen ops evaluated (LA-10's five + LA-13's {@code window} + LA-19's {@code annotate} + LA-17's three + {@code threshold}, {@code snapshot}, {@code compare} and {@code temporal}). */
    private static final Set<String> SHIPPED = Set.of("seed", "expand", "exclude", "hide", "keep", "window", "annotate",
            "excludeBy", "seedBy", "resolve", "threshold", "snapshot", "compare", "temporal");
    /** The ops over a named Entity List (LA-17, design §4.4.1): they carry {@code listId}, never ids. */
    static final Set<String> LIST_OPS = Set.of("excludeBy", "seedBy");
    /** A list op seals at most this many members (design §4.4.1: bounded like every other op payload). */
    private static final int MAX_LIST_MEMBERS = 5_000;
    /** An {@code expand} rung's traversal direction (plan §2.4). */
    private static final List<String> DIRECTIONS = List.of("either", "out", "in", "reciprocal");
    private static final int MAX_IDS = 1_000;
    private static final int MAX_ID_LENGTH = 512;
    private static final int MAX_NOTE_LENGTH = 2_000;
    private static final int MAX_PURPOSE_LENGTH = 1_000;
    private static final int MAX_REVEAL = 100;
    private static final int MAX_FRONTIER = 1_000;
    private static final int MAX_LINK_KINDS = 100;
    private static final int DEFAULT_EXPAND_BUDGET = 2_000;
    private static final int MAX_EXPAND_BUDGET = 20_000;
    private static final int LOG_DEFAULT = 500;
    private static final int LOG_MAX = 5_000;
    private static final int LIST_DEFAULT = 100;
    private static final int LIST_MAX = 1_000;

    @Override
    public void register(ApiContext api) {
        // ⚠ String LITERALS on purpose — CapabilityManifestTest's scanner matches only a literal argument.
        api.post("/inv/investigations", ApiContext.withCapability("canManageIncidents",
                (e, m) -> create(api, e, api.body(e))));
        api.get("/inv/investigations", (e, m) -> list(api, e));
        api.post("/inv/investigations/([^/]+)/ops", ApiContext.withCapability("canManageIncidents",
                (e, m) -> appendOp(api, e, m.group(1), api.body(e))));
        api.post("/inv/investigations/([^/]+)/undo", ApiContext.withCapability("canManageIncidents",
                (e, m) -> undo(api, e, m.group(1))));
        api.post("/inv/investigations/([^/]+)/reorder", ApiContext.withCapability("canManageIncidents",
                (e, m) -> reorder(api, e, m.group(1), api.body(e))));
        api.post("/inv/investigations/([^/]+)/replay", (e, m) -> replay(api, e, m.group(1), api.body(e)));
        api.get("/inv/investigations/([^/]+)/log", (e, m) -> log(api, e, m.group(1)));
        api.post("/inv/investigations/([^/]+)/reveal", ApiContext.withCapability("canRevealLinkEntities",
                (e, m) -> reveal(api, e, m.group(1), api.body(e))));
        api.post("/inv/investigations/([^/]+)/pending/([^/]+)/approve", ApiContext.withCapability("canApproveLinkExpansions",
                (e, m) -> decide(api, e, m.group(1), m.group(2), true, api.body(e))));
        api.post("/inv/investigations/([^/]+)/pending/([^/]+)/deny", ApiContext.withCapability("canApproveLinkExpansions",
                (e, m) -> decide(api, e, m.group(1), m.group(2), false, api.body(e))));
        try {
            InvestigationStores.probeAtBoot(api.writeRoot());   // -Dinvestigations.backend=db: WARN + health now, never refuse the boot
        } catch (RuntimeException noBootSpace) {
            // no Space is bound yet; each Space is checked on its first request and reported the same way
        }
    }

    /** One opened Investigation: its store, write root and parsed header. Package-private for {@link WorkingSetRoutes}. */
    public record Inv(InvestigationStore store, Path writeRoot, String id, Map<String, Object> header, DraftRef draft) {
        /** An Investigation as opened for the MAIN log (no Draft). */
        public Inv(InvestigationStore store, Path writeRoot, String id, Map<String, Object> header) {
            this(store, writeRoot, id, header, null);
        }

        /** The scope this view reads and appends: the main log, or its Draft's own log. */
        public InvestigationStore.Scope scope() {
            return draft == null ? InvestigationStore.Scope.main(id) : InvestigationStore.Scope.draft(id, draft.draftId());
        }

        /** D7-3: the Draft this view is working on - its id and the main step it forked from. */
        public record DraftRef(String draftId, int baseStep) { }

        public String dataset() { return String.valueOf(header.get("dataset")); }

        /** The key naming this view's log in per-pod caches (checkpoints, cached relations); see {@link InvestigationStore#cacheKey}. */
        String cacheKey() { return store.cacheKey(scope()); }

        /** The token that changes whenever this view's own log does; see {@link InvestigationStore#logToken}. */
        String logToken() throws IOException { return store.logToken(scope()); }

        /**
         * The log this view evaluates: the main log, or - for a Draft - the main log's first {@code baseStep} entries
         * followed by the Draft's own (D7-3). One list, because the Draft's steps continue the numbering.
         */
        List<String> logLines() throws IOException {
            if (draft == null) return store.log(scope());
            List<String> main = store.log(InvestigationStore.Scope.main(id));
            List<String> out = new ArrayList<>(main.subList(0, Math.min(draft.baseStep(), main.size())));
            out.addAll(store.log(scope()));
            return out;
        }

        /**
         * Append one step through the writer that matches this view: the main log's, or the Draft's. {@code logSize} is the
         * length of the {@link #logLines} the caller evaluated against; the store verifies that the scope's own log still holds
         * exactly the entries that list implies, so a writer that lost a race is refused
         * ({@link InvestigationVersionConflictException}), never interleaved.
         */
        void appendStep(int logSize, int step, String lineJson, String workingSetJson) throws IOException {
            long own = draft == null ? logSize : logSize - draft.baseStep();
            try {
                store.append(scope(), own, step, lineJson, workingSetJson);
            } catch (InvestigationStore.DraftClosedException closed) {   // discarded or promoted between the gate and the write
                throw new ApiException(409, ErrorCodes.CONFLICT, closed.getMessage());
            }
        }
    }

    // ── routes ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * {@code POST /inv/investigations} — body {@code {id?, title?, purpose, dataset, sourceCol, targetCol, linkKindCol?,
     * timeCol?, timeColZone?}}. {@code purpose} (D-U5) is the stated purpose / legal basis — required, recorded in
     * the sealed header and shown in the Dossier, not enforced. {@code timeCol} (LA-13) binds the event time every window reads; see
     * {@link InvestigationTime} for the timezone contract {@code timeColZone} is part of.
     * Gates: write root 503 → a missing/unsafe field (incl. {@code purpose}) 422 → unknown or not-viewable Dataset 404 → a column the
     * relation lacks, a time column that is not a timestamp, or a bad zone 422 → id escaping the store 403 → id
     * taken 409 → write the header CREATE_NEW.
     */
    private Object create(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "link analysis investigation create");
        String given = ApiContext.str(body, "id");
        String id = given != null ? given : "inv-" + UUID.randomUUID();
        requireSafeId(id);
        String purpose = purpose(body);
        String dataset = ApiContext.str(body, "dataset");
        if (dataset == null) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "body must include 'dataset'");
        String sourceCol = ident(body, "sourceCol", true);
        String targetCol = ident(body, "targetCol", true);
        String kindCol = ident(body, "linkKindCol", false);
        String timeCol = ident(body, "timeCol", false);

        String relationSql = InvRoutes.relationFor(api, ex, writeRoot, dataset);
        List<String> columns = relationColumns(dataset, relationSql);
        for (String col : java.util.Arrays.asList(sourceCol, targetCol, kindCol, timeCol))
            if (col != null && columns.stream().noneMatch(col::equalsIgnoreCase))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "unknown column '" + col + "' — not a column of dataset '" + dataset + "'");
        String timeColZone = timeColZone(dataset, relationSql, timeCol, ApiContext.str(body, "timeColZone"));
        // LA-24: an optional Case link, checked BEFORE anything is written; stored outside the sealed header.
        Map<String, Object> caseLink = InvestigationCaseRoutes.linkRecord(api, ex, ApiContext.str(body, "caseRef"));

        InvestigationStore store = InvestigationStores.of(writeRoot);
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("id", id);
        header.put("title", ApiContext.str(body, "title"));
        header.put("purpose", purpose);
        header.put("owner", ApiContext.actor(ex));
        header.put("dataset", dataset);
        header.put("sourceCol", sourceCol);
        header.put("targetCol", targetCol);
        header.put("linkKindCol", kindCol);
        if (timeCol != null) {   // absent keys keep a timeless Investigation's header exactly as LA-10 wrote it
            header.put("timeCol", timeCol);
            header.put("timeColZone", timeColZone);
        }
        header.put("createdAt", Instant.now().toString());
        // D-E3: no version-addressable read exists, so nothing is pinned — reads are sealed at use instead.
        header.put("datasetVersion", null);
        header.put("parent", null);
        if (!createChecked(store, id, canonical(header)))
            throw new ApiException(409, ErrorCodes.CONFLICT, "investigation '" + id + "' already exists");
        if (caseLink != null) InvestigationCaseRoutes.write(ex, store, id, caseLink);
        emit(ex, LinkEventTypes.LINK_INVESTIGATION_CREATED, "link.investigation.created",
                "link.investigation.created — " + id + " over " + dataset,
                b -> b.attr("investigationId", id).attr("dataset", dataset));
        return header;
    }

    /**
     * {@code GET /inv/investigations?limit&offset} — the Investigations the caller may READ, newest first: each one is
     * judged by {@link #openForRead} itself, so the list can never show what a read would refuse — the owner's own,
     * plus those shared READ-ONLY with a member of an open linked Case (LA-24), minus any whose bound Dataset the
     * caller cannot view (R3) or the Enterprise PDP denies (D-E7). Gates: write root 503 → a bad limit/offset 422.
     * Each item: {@code {id, title, dataset, owner, createdAt, headStep, caseRef?, access: owner|case-member}}; the
     * title is masked per the Space's {@code maskingMode} as the log masks it (D-U6). Read-shaped: no capability.
     */
    private Object list(ApiContext api, HttpExchange ex) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "link analysis investigation list");
        int limit = Math.min(Math.max(queryInt(ex, "limit", LIST_DEFAULT), 1), LIST_MAX);
        int offset = queryInt(ex, "offset", 0);
        if (offset < 0) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "offset must be >= 0");
        Optional<Subject> subject = ApiContext.subject(ex);
        List<Map<String, Object>> readable = new ArrayList<>();
        for (String id : InvestigationStores.of(writeRoot).ids()) {
            Inv inv;
            try {
                inv = openForRead(api, ex, id);
            } catch (ApiException refused) {
                continue;   // not readable by this caller — absent from the list exactly as from a read
            }
            Map<String, Object> h = inv.header();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            item.put("title", h.get("title") == null ? null : EntityMasking.of(inv, List.of()).inText(String.valueOf(h.get("title"))));
            item.put("dataset", h.get("dataset"));
            item.put("owner", h.get("owner"));
            item.put("createdAt", h.get("createdAt"));
            List<String> log = inv.store().log(InvestigationStore.Scope.main(id));
            item.put("headStep", log.isEmpty() ? 0 : ((Number) parse(log.get(log.size() - 1)).get("step")).intValue());
            String caseRef = InvestigationCaseRoutes.caseRef(inv.store(), id);
            if (caseRef != null) item.put("caseRef", caseRef);
            InvestigationMembers.Role role = subject.isEmpty() ? InvestigationMembers.Role.LEAD
                    : InvestigationMemberStore.roles(inv.store(), inv.id(), h.get("owner")).get(subject.get().id());
            item.put("access", role == null ? "case-member" : role == InvestigationMembers.Role.LEAD ? "owner" : role.wire());
            item.put("readOnly", role != InvestigationMembers.Role.LEAD);
            readable.add(item);
        }
        readable.sort(java.util.Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("createdAt")))
                .reversed().thenComparing(m -> String.valueOf(m.get("id"))));
        List<Map<String, Object>> page = readable.subList(Math.min(offset, readable.size()),
                (int) Math.min((long) offset + limit, readable.size()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", new ArrayList<>(page));
        out.put("total", readable.size());
        out.put("offset", offset);
        out.put("limit", limit);
        out.put("truncated", (long) offset + page.size() < readable.size());
        return out;
    }

    private static int queryInt(HttpExchange ex, String name, int dflt) {
        String raw = ApiContext.query(ex, name);
        if (raw == null || raw.isBlank()) return dflt;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, name + " must be an integer, got '" + raw + "'");
        }
    }

    /** {@code POST /inv/investigations/{id}/ops} — body {@code {op, ...params}}. See the class note for gates. */
    private Object appendOp(ApiContext api, HttpExchange ex, String id, Map<String, Object> body) throws IOException {
        return appendOpOn(api, ex, open(api, ex, id), body);
    }

    /** The append, over an already-opened view: the main log's, or (D7-3) a Draft's - the SAME validation, sealing and rules. */
    Object appendOpOn(ApiContext api, HttpExchange ex, Inv inv, Map<String, Object> body) throws IOException {
        String op = ApiContext.str(body, "op");
        if (op == null) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "body must include 'op'");
        if (!SHIPPED.contains(op))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "op '" + op + "' is not in the closed op vocabulary");
        Map<String, Object> params = params(op, resolvePseudonyms(inv, body));
        if (params.get("links") != null) resolveLinkPseudonyms(inv, params);
        requireBindings(inv.header(), op, params, "");

        return untilWon(() -> {
            List<Map<String, Object>> log = readLog(inv);
            InvestigationEvaluator.State before = stateBefore(inv, log);
            List<String> ids = strings(params.get("ids"));
            if (op.equals("hide") || op.equals("keep") || op.equals("annotate") || (op.equals("expand") && !ids.isEmpty()))
                for (String i : ids)
                    if (!before.entities.containsKey(i))
                        throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + i + "' is not in the Working Set");
            if (params.get("links") instanceof List<?> ls)   // D-U9: a link note names a link the Working Set holds
                for (Object o : ls) {
                    Map<?, ?> l = (Map<?, ?>) o;
                    if (!before.links.containsKey(LinkIds.key(String.valueOf(l.get("source")), String.valueOf(l.get("target")),
                            String.valueOf(l.get("kind")))))
                        throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "a named link is not in the Working Set");
                }

            int step = log.size() + 1;
            Map<String, Object> entry = entry(step, "op", ex);
            entry.put("op", op);
            entry.put("params", params);
            if (LIST_OPS.contains(op)) {
                Map<String, Object> list = sealList(inv.writeRoot(), String.valueOf(params.get("listId")), "");
                entry.put("list", list);
                if (op.equals("seedBy")) entry.put("read", seedRead(api, ex, inv, list));
            }
            if (op.equals("resolve")) entry.put("resolution", sealResolution(inv.writeRoot(), inv.header(), params.get("atSeq"), ""));
            if (op.equals("compare")) entry.put("comparison", sealComparison(api, ex, inv, before, params));
            if (op.equals("temporal")) entry.put("temporalFindings", TemporalFindings.seal(TemporalFindings.compute(api, ex, inv, before, params)));
            if (op.equals("exclude") && Boolean.TRUE.equals(params.get("merged"))) {
                requireResolution(before, "");
                List<Object> groups = new ArrayList<>(before.groupsHit(ids).values());
                if (groups.isEmpty())
                    throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "a merged exclude needs an id that resolves to an identity "
                            + "group under the resolution in force; none of " + ids + " does - exclude them without 'merged'");
                entry.put("groups", groups);
            }
            if (op.equals("expand")) {
                List<String> frontier = ids.isEmpty() ? new ArrayList<>(before.entities.keySet()) : sorted(ids);
                if (frontier.isEmpty()) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "nothing to expand — the Working Set is empty");
                if (frontier.size() > MAX_FRONTIER)
                    throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "an expand frontier is capped at " + MAX_FRONTIER
                            + " entities; name them with 'ids'");
                Map<String, Object> sensitive = sensitivity(inv, params);
                if (sensitive != null && inv.draft() != null)   // D7-3: the four-eyes queue belongs to the main log (D-U7); promote (D7-5) is where it meets a Draft
                    throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "this expand is sensitive " + sensitive.get("exceeded")
                            + " - four-eyes approval applies to the main log, so a Draft cannot hold it; lower the budget / fan-out or ask a lead to expand");
                if (sensitive != null) return masked(inv, requestExpansion(ex, inv, params, sensitive, before));
                entry.put("read", read(api, ex, inv, expandRung(api, ex, inv, params, frontier, before, "")));
            }
            return masked(inv, commit(ex, inv, log, entry, before));
        });
    }

    /**
     * D7-5 - re-apply one recorded op over {@code state} (the new base plus the ops carried so far), with the SAME validation an append
     * runs, returning the entry's op fields. List / resolution / seedBy payloads keep what they sealed (as a fork does); a merged
     * exclude re-seals its groups; an expand is RE-SEALED, reading {@code pins} (mappingHash to version). A refusal is an
     * {@link ApiException} 422 - the caller reports it as a {@code blocked} conflict.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> replayOp(ApiContext api, HttpExchange ex, Inv inv, Map<String, Object> orig,
                                 InvestigationEvaluator.State state, Map<String, Long> pins) {
        String op = String.valueOf(orig.get("op"));
        Map<String, Object> params = (Map<String, Object>) orig.get("params");
        List<String> ids = strings(params.get("ids"));
        if (op.equals("hide") || op.equals("keep") || op.equals("annotate") || (op.equals("expand") && !ids.isEmpty()))
            for (String i : ids)
                if (!state.entities.containsKey(i))
                    throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "an entity this op names is no longer in the Working Set");
        if (params.get("links") instanceof List<?> ls)
            for (Object o : ls) {
                Map<?, ?> l = (Map<?, ?>) o;
                if (!state.links.containsKey(LinkIds.key(String.valueOf(l.get("source")), String.valueOf(l.get("target")), String.valueOf(l.get("kind")))))
                    throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "a link this op names is no longer in the Working Set");
            }
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("op", op);
        e.put("params", params);
        if (orig.get("list") != null) e.put("list", orig.get("list"));
        if ("seedBy".equals(op) && orig.get("read") != null) e.put("read", orig.get("read"));
        if (orig.get("resolution") != null) e.put("resolution", orig.get("resolution"));
        if (op.equals("compare")) e.put("comparison", sealComparison(api, ex, inv, state, params));   // re-sealed over the NEW base, as an expand is
        if (op.equals("temporal")) e.put("temporalFindings", TemporalFindings.seal(TemporalFindings.compute(api, ex, inv, state, params)));
        if (op.equals("exclude") && Boolean.TRUE.equals(params.get("merged"))) {
            requireResolution(state, "");
            List<Object> groups = new ArrayList<>(state.groupsHit(ids).values());
            if (groups.isEmpty())
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "a merged exclude's identity group no longer resolves");
            e.put("groups", groups);
        }
        if (op.equals("expand")) {
            List<String> frontier = ids.isEmpty() ? new ArrayList<>(state.entities.keySet()) : sorted(ids);
            if (frontier.isEmpty()) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "nothing to expand - the Working Set is empty");
            if (frontier.size() > MAX_FRONTIER)
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "an expand frontier is capped at " + MAX_FRONTIER + " entities");
            e.put("read", read(api, ex, inv, expandRung(api, ex, inv, params, frontier, state, ""), pins == null ? Map.of() : pins));
        }
        return e;
    }

    /** D7-5: whether an expand with these params is four-eyes sensitive under the Space's CURRENT thresholds (D-U7), or null. */
    static Map<String, Object> sensitivityOf(Inv inv, Map<String, Object> params) {
        return sensitivity(inv, params);
    }

    /** D7-5: hold a promote that carries a sensitive expand as a PENDING request on the main log's four-eyes queue (one at a time). */
    static Map<String, Object> requestPromote(HttpExchange ex, Inv inv, Map<String, Object> rec) throws IOException {
        List<String> existing = inv.store().listPending(inv.id());
        for (String raw : existing)
            if ("pending".equals(parse(raw).get("status")))
                throw new ApiException(409, ErrorCodes.CONFLICT, "a request is already pending approval (" + parse(raw).get("id")
                        + ") - it must be approved or denied before another is requested");
        String rid = "p" + (existing.size() + 1);
        rec.put("id", rid);
        rec.put("status", "pending");
        rec.put("requestedBy", ApiContext.actor(ex));
        rec.put("requestedAt", Instant.now().toString());
        inv.store().writePending(inv.id(), rid, canonical(rec));
        emit(ex, LinkEventTypes.LINK_EXPANSION_REQUESTED, "link.expansion.requested",
                "link.expansion.requested - " + inv.id() + " " + rid + " promote of " + rec.get("draftId"),
                b -> b.attr("investigationId", inv.id()).attr("requestId", rid).attr("draftId", rec.get("draftId")));
        return rec;
    }

    /** {@code POST /inv/investigations/{id}/undo} — revert the latest effective op; 409 when there is none. */
    private Object undo(ApiContext api, HttpExchange ex, String id) throws IOException {
        return undoOn(ex, open(api, ex, id));
    }

    /** The undo, over an opened view. A Draft undoes only its OWN ops: the main prefix it forked from is not its to revert. */
    Object undoOn(HttpExchange ex, Inv inv) throws IOException {
        return untilWon(() -> {
            List<Map<String, Object>> log = readLog(inv);
            int own = inv.draft() == null ? 0 : inv.draft().baseStep();
            int target = InvestigationEvaluator.undoTarget(log.subList(Math.min(own, log.size()), log.size()));
            if (target < 0) throw new ApiException(409, ErrorCodes.CONFLICT, "nothing to undo");
            Map<String, Object> entry = entry(log.size() + 1, "undo", ex);
            entry.put("undoes", target);
            return masked(inv, commit(ex, inv, log, entry, stateBefore(inv, log)));
        });
    }

    /**
     * {@code POST /inv/investigations/{id}/reorder} — body {@code {order: [step...], id?, title?}}: {@code order}
     * must be a permutation of the log's EFFECTIVE op steps (undo entries and undone ops excluded). The result is
     * a NEW Investigation whose header names its parent and the order it was forked with. Every op is re-applied
     * in the new order and every {@code expand} re-reads — a different order means a different frontier, so the
     * parent's sealed rows do not describe it — sealing a fresh read. The fork is assembled off to the side and
     * moved into place in one rename, so a failed fork writes nothing.
     */
    private Object reorder(ApiContext api, HttpExchange ex, String id, Map<String, Object> body) throws IOException {
        Inv parent = open(api, ex, id);
        List<Map<String, Object>> log = readLog(parent);
        Set<Integer> undone = InvestigationEvaluator.undone(log);
        Map<Integer, Map<String, Object>> effective = new LinkedHashMap<>();
        for (Map<String, Object> e : log) {
            int s = ((Number) e.get("step")).intValue();
            if ("op".equals(e.get("kind")) && !undone.contains(s)) effective.put(s, e);
        }
        if (!(body.get("order") instanceof List<?> rawOrder))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "body must include 'order', a list of step numbers");
        List<Integer> order = new ArrayList<>();
        for (Object o : rawOrder) {
            if (!(o instanceof Number n)) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'order' entries must be step numbers");
            order.add(n.intValue());
        }
        if (order.size() != effective.size() || !new HashSet<>(order).equals(effective.keySet()))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'order' must be a permutation of the effective op steps "
                    + effective.keySet());

        String forkId = ApiContext.str(body, "id");
        if (forkId == null) forkId = "inv-" + UUID.randomUUID();
        requireSafeId(forkId);
        if (parent.store().header(forkId).orElse(null) != null)
            throw new ApiException(409, ErrorCodes.CONFLICT, "investigation '" + forkId + "' already exists");

        Map<String, Object> header = new LinkedHashMap<>(parent.header());
        header.put("id", forkId);
        String title = ApiContext.str(body, "title");
        if (title != null) header.put("title", title);
        header.put("owner", ApiContext.actor(ex));
        header.put("createdAt", Instant.now().toString());
        Map<String, Object> lineage = new LinkedHashMap<>();
        lineage.put("id", parent.id());
        lineage.put("order", order);
        lineage.put("parentSteps", log.size());
        header.put("parent", lineage);

        InvestigationEvaluator.State state = new InvestigationEvaluator.State();
        List<String> lines = new ArrayList<>(), sets = new ArrayList<>();
        int step = 0;
        for (int from : order) {
            Map<String, Object> orig = effective.get(from);
            Map<String, Object> e = entry(++step, "op", ex);
            e.put("op", orig.get("op"));
            e.put("params", orig.get("params"));
            e.put("derivedFrom", Map.of("investigation", parent.id(), "step", from));
            if (orig.get("approval") != null) e.put("approval", orig.get("approval"));   // D-U7: approved in the parent
            // LA-17: a list op keeps the list it sealed (a fork re-orders the method, it does not re-resolve it), and a
            // seedBy's read — a DISTINCT over the whole Dataset — does not depend on the order, so it travels too.
            if (orig.get("list") != null) e.put("list", orig.get("list"));
            if ("seedBy".equals(orig.get("op"))) e.put("read", orig.get("read"));
            // LA-17 slice 2: a resolve keeps the resolution it sealed, for the same reason — a fork re-orders the
            // method, it does not re-read the identity facts.
            if (orig.get("resolution") != null) e.put("resolution", orig.get("resolution"));
            // compare: a different order is a different Working Set, so the diff is re-sealed over the NEW state (as an expand re-reads).
            if ("compare".equals(orig.get("op")))
                e.put("comparison", sealComparison(api, ex, parent, state, castParams(orig.get("params"))));
            if ("temporal".equals(orig.get("op")))   // likewise: the findings are narrowed to the NEW state, so they are re-sealed
                e.put("temporalFindings", TemporalFindings.seal(TemporalFindings.compute(api, ex, parent, state, castParams(orig.get("params")))));
            // LA-17 merged traversal: a merged exclude re-seals its groups against the NEW order's state (a group is
            // judged from the entities admitted at that point), under the resolution the fork kept verbatim.
            if ("exclude".equals(orig.get("op")) && orig.get("groups") != null) {
                requireResolution(state, "fork step " + step + ": ");   // operator 2026-09-30: refused, as a merged expand
                e.put("groups", new ArrayList<Object>(state.groupsHit(strings(((Map<?, ?>) orig.get("params")).get("ids"))).values()));
            }
            if ("expand".equals(orig.get("op"))) {
                @SuppressWarnings("unchecked") Map<String, Object> p = (Map<String, Object>) orig.get("params");
                List<String> named = strings(p.get("ids"));
                List<String> frontier = new ArrayList<>();
                for (String n : named.isEmpty() ? state.entities.keySet() : sorted(named))
                    if (state.entities.containsKey(n)) frontier.add(n);
                e.put("read", read(api, ex, parent, expandRung(api, ex, parent, p, frontier, state, "fork step " + step + ": ")));
            }
            e = roundTrip(e);
            InvestigationEvaluator.apply(state, e);
            e.put("workingSetHash", state.hash());
            lines.add(canonical(e));
            sets.add(canonical(setDoc(step, state)));
        }
        if (!forkChecked(parent.store(), forkId, canonical(header), lines, sets))
            throw new ApiException(409, ErrorCodes.CONFLICT, "investigation '" + forkId + "' already exists");
        String fid = forkId;
        emit(ex, LinkEventTypes.LINK_INVESTIGATION_FORKED, "link.investigation.forked",
                "link.investigation.forked — " + parent.id() + " → " + fid,
                b -> b.attr("investigationId", fid).attr("parentId", parent.id()).attr("steps", order.size()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", forkId);
        out.put("parent", lineage);
        out.put("steps", step);
        out.put("workingSet", summary(state));
        return out;
    }

    /**
     * LA-23 — build a NEW Investigation from an Investigation Template's ops (called by
     * {@link InvestigationTemplateRoutes}, which resolves the template, its parameters and the binding). Additive:
     * every existing route is untouched. {@code header} carries the new id, title, Dataset and column roles plus the
     * template lineage; each op is an {@code /ops}-shaped body ({@code {op, ids?, entityType?, window?, ...rung}}) and is
     * validated by the same {@link #params} an append uses. Gates, in {@link #create}'s order: unsafe id or column 422
     * → unknown or not-viewable Dataset 404 (R3) → a column the relation lacks 422 → id escaping the store 403 → id
     * taken 409. Every {@code expand} reads the NEW binding — the frontier is the whole Working Set at that point,
     * because a template names no entities — and seals that read (D-E3). Assembled off to the side and moved into
     * place in one rename, as a fork is, so a failed instantiation writes nothing.
     */
    Map<String, Object> instantiate(ApiContext api, HttpExchange ex, Path writeRoot, Map<String, Object> header,
                                    List<Map<String, Object>> ops) throws IOException {
        String id = String.valueOf(header.get("id"));
        requireSafeId(id);
        String dataset = String.valueOf(header.get("dataset"));
        List<String> cols = new ArrayList<>();
        for (String key : List.of("sourceCol", "targetCol", "linkKindCol", "timeCol")) {
            String col = ident(header, key, key.equals("sourceCol") || key.equals("targetCol"));
            if (col != null) cols.add(col);
        }
        String relationSql = InvRoutes.relationFor(api, ex, writeRoot, dataset);
        List<String> columns = relationColumns(dataset, relationSql);
        for (String col : cols)
            if (columns.stream().noneMatch(col::equalsIgnoreCase))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "unknown column '" + col + "' — not a column of dataset '" + dataset + "'");
        String timeCol = ident(header, "timeCol", false);
        String timeColZone = timeColZone(dataset, relationSql, timeCol, ApiContext.str(header, "timeColZone"));
        InvestigationStore store = InvestigationStores.of(writeRoot);
        if (store.header(id).isPresent()) throw new ApiException(409, ErrorCodes.CONFLICT, "investigation '" + id + "' already exists");

        Map<String, Object> h = new LinkedHashMap<>(header);
        h.put("purpose", purpose(header));   // D-U5: an instantiated Investigation states its purpose like a created one
        h.remove("timeCol");
        h.remove("timeColZone");
        if (timeCol != null) {
            h.put("timeCol", timeCol);
            h.put("timeColZone", timeColZone);
        }
        h.put("owner", ApiContext.actor(ex));
        h.put("createdAt", Instant.now().toString());
        h.put("datasetVersion", null);   // D-E3, as create
        h.put("parent", null);
        Inv inv = new Inv(store, writeRoot, id, h);
        InvestigationEvaluator.State state = new InvestigationEvaluator.State();
        List<String> lines = new ArrayList<>(), sets = new ArrayList<>();
        int step = 0;
        for (Map<String, Object> body : ops) {
            String op = ApiContext.str(body, "op");
            if (!SHIPPED.contains(op)) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "op '" + op + "' is not in the closed op vocabulary");
            Map<String, Object> e = entry(++step, "op", ex);
            e.put("op", op);
            Map<String, Object> params = params(op, body);
            requireBindings(h, op, params, "template step " + step + ": ");
            e.put("params", params);
            e.put("derivedFrom", body.get("derivedFrom"));
            if (LIST_OPS.contains(op)) {   // LA-17: re-resolved at THIS moment's head — the method travels, not the membership
                Map<String, Object> list = sealList(writeRoot, String.valueOf(params.get("listId")), "template step " + step + ": ");
                e.put("list", list);
                if (op.equals("seedBy")) e.put("read", seedRead(api, ex, inv, list));
            }
            // LA-17 slice 2 (D-E8): a resolve travels as the method "resolve identities here" — re-sealed at THIS
            // moment's head over the NEW binding, never the authored atSeq or groups.
            if (op.equals("resolve")) e.put("resolution", sealResolution(writeRoot, h, params.get("atSeq"), "template step " + step + ": "));
            if (op.equals("expand")) {
                List<String> frontier = new ArrayList<>(state.entities.keySet());
                if (frontier.isEmpty()) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "template step " + step + " expands an empty Working Set");
                if (frontier.size() > MAX_FRONTIER)
                    throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "template step " + step + " would expand " + frontier.size()
                            + " entities; an expand frontier is capped at " + MAX_FRONTIER);
                // D-U7: a template names no entities, so there is no frontier a second person could approve in
                // advance — a sensitive step is refused rather than run unapproved.
                Map<String, Object> sensitive = sensitivity(inv, params);
                if (sensitive != null)
                    throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "template step " + step + " is a sensitive expand " + sensitive.get("exceeded")
                            + " — four-eyes applies and a template names no frontier to approve; save the template "
                            + "with a smaller budget/fan-out and expand further from the Investigation, where the "
                            + "step can be approved");
                e.put("read", read(api, ex, inv, expandRung(api, ex, inv, params, frontier, state, "template step " + step + ": ")));
            }
            e = roundTrip(e);
            InvestigationEvaluator.apply(state, e);
            e.put("workingSetHash", state.hash());
            lines.add(canonical(e));
            sets.add(canonical(setDoc(step, state)));
        }
        if (!forkChecked(store, id, canonical(h), lines, sets))
            throw new ApiException(409, ErrorCodes.CONFLICT, "investigation '" + id + "' already exists");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("header", h);
        out.put("steps", step);
        out.put("workingSet", summary(state));
        return out;
    }

    /**
     * {@code POST /inv/investigations/{id}/replay} — body {@code {at?, reread?}}. Evaluates the sealed log from
     * the first step (to {@code at} when given) and compares every position's hash with the one recorded when the
     * step was appended: {@code equivalent} is the incremental/full equivalence check (plan §2.3). With
     * {@code reread}, each effective {@code expand} re-runs its RECORDED query against current data and reports
     * whether the fingerprint still matches — drift is reported, never silently served (G-E3).
     */
    private Object replay(ApiContext api, HttpExchange ex, String id, Map<String, Object> body) throws IOException {
        Inv inv = openAsMember(api, ex, id);   // read-shaped (persists nothing): any member, not a Case member (LA-24 unchanged)
        int at = body.get("at") instanceof Number n ? n.intValue() : -1;
        boolean reread = Boolean.TRUE.equals(body.get("reread"));
        List<Map<String, Object>> log = readLog(inv);
        List<String> hashes = new ArrayList<>();
        InvestigationEvaluator.State s = evaluate(log, at, hashes);
        List<Integer> mismatches = new ArrayList<>();
        for (int i = 0; i < hashes.size(); i++)
            if (!hashes.get(i).equals(log.get(i).get("workingSetHash"))) mismatches.add(i + 1);

        List<Map<String, Object>> drift = new ArrayList<>();
        boolean diverged = false;
        if (reread) {
            Set<Integer> undone = InvestigationEvaluator.undone(log.subList(0, hashes.size()));   // prefix semantics
            for (Map<String, Object> e : log.subList(0, hashes.size())) {
                int step = ((Number) e.get("step")).intValue();
                if (!"expand".equals(e.get("op")) || undone.contains(step)) continue;
                @SuppressWarnings("unchecked") Map<String, Object> sealed = (Map<String, Object>) e.get("read");
                @SuppressWarnings("unchecked") Map<String, Object> q = (Map<String, Object>) sealed.get("query");
                Map<String, Object> now = read(api, ex, inv, q);   // the RECORDED rung, window resolved as sealed
                boolean d = !sealed.get("fingerprint").equals(now.get("fingerprint"));
                diverged |= d;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("step", step);
                row.put("dataset", sealed.get("dataset"));
                row.put("sealedAt", sealed.get("readAt"));
                row.put("sealedFingerprint", sealed.get("fingerprint"));
                row.put("currentFingerprint", now.get("fingerprint"));
                row.put("sealedRows", sealed.get("rowCount"));
                row.put("currentRows", now.get("rowCount"));
                row.put("diverged", d);
                // D-3 step 6: the index moving is reported on its own - `diverged` stays about the fingerprint (the rows) alone.
                Object sealedIx = sealed.get("index") instanceof Map<?, ?> si ? si.get("version") : null;
                Object nowIx = now.get("index") instanceof Map<?, ?> ni ? ni.get("version") : null;
                if (sealedIx != null || nowIx != null) {
                    row.put("indexVersionSealed", sealedIx);
                    row.put("indexVersionNow", nowIx);
                }
                drift.add(row);
            }
        }
        boolean div = diverged;
        emit(ex, LinkEventTypes.LINK_INVESTIGATION_REPLAYED, "link.investigation.replayed",
                "link.investigation.replayed — " + id + (mismatches.isEmpty() ? "" : " (NOT equivalent)")
                        + (div ? " (diverged)" : ""),
                b -> b.attr("investigationId", id).attr("equivalent", mismatches.isEmpty())
                        .attr("reread", reread).attr("diverged", div));
        Map<String, Object> ws = new LinkedHashMap<>(s.toMap());
        ws.put("hash", s.hash());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("at", hashes.size());
        out.put("workingSet", ws);
        out.put("equivalent", mismatches.isEmpty());
        out.put("mismatches", mismatches);
        out.put("reread", reread);
        out.put("drift", drift);
        out.put("diverged", diverged);
        return masked(inv, out);
    }

    /** {@code GET /inv/investigations/{id}/log?limit=n} — bounded; the TRUE total ships beside it. */
    private Object log(ApiContext api, HttpExchange ex, String id) throws IOException {
        return logOf(ex, openForRead(api, ex, id));
    }

    /** The log answer over an opened view; for a Draft (D7-3) only its OWN entries (steps after {@code baseStep}) are listed. */
    Object logOf(HttpExchange ex, Inv inv) throws IOException {
        int skip = inv.draft() == null ? 0 : inv.draft().baseStep();
        int limit = LOG_DEFAULT;
        String raw = ApiContext.query(ex, "limit");
        if (raw != null && !raw.isBlank()) {
            try {
                limit = Integer.parseInt(raw.trim());
            } catch (NumberFormatException e) {
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "limit must be an integer, got '" + raw + "'");
            }
        }
        limit = Math.min(Math.max(limit, 1), LOG_MAX);
        List<Map<String, Object>> log = readLog(inv);
        Map<Integer, Integer> undoneBy = new LinkedHashMap<>();
        for (Map<String, Object> e : log)
            if ("undo".equals(e.get("kind")))
                undoneBy.put(((Number) e.get("undoes")).intValue(), ((Number) e.get("step")).intValue());
        List<Map<String, Object>> entries = new ArrayList<>();
        // How many entities each excludeBy removed is a property of the state it ran on, not of its log line.
        List<Integer> counts = log.stream().anyMatch(e -> "excludeBy".equals(e.get("op")))
                ? InvestigationEvaluator.entityCounts(log) : List.of();
        // ...and so is what each merged exclude actually removed (plan §5.10: the line never claims more).
        Map<Integer, Map<String, List<String>>> merged = InvestigationEvaluator.mergedExcludeOutcomes(log);
        for (Map<String, Object> e : log.subList(Math.min(skip, log.size()), Math.min(skip + limit, log.size()))) {
            int step = ((Number) e.get("step")).intValue();
            Map<String, Object> out = new LinkedHashMap<>(e);
            if (e.get("read") instanceof Map<?, ?> r) {
                Map<String, Object> summary = new LinkedHashMap<>();   // the sealed rows stay out of the log view
                for (String k : List.of("dataset", "readAt", "rowCount", "truncated", "fanOutCapped", "fingerprint"))
                    summary.put(k, r.get(k));
                if (r.get("index") != null) summary.put("index", r.get("index"));          // which store answered: only when the index did
                if (r.get("fallback") != null) summary.put("fallback", r.get("fallback"));   // ...or why the flat Dataset did
                out.put("read", summary);
            }
            if (e.get("comparison") instanceof Map<?, ?> c) out.put("comparison", WindowComparison.summary(c));   // ...and a diff's item lists
            if (e.get("temporalFindings") instanceof Map<?, ?> t) out.put("temporalFindings", TemporalFindings.summary(t));   // ...and a finding set's findings
            if (e.get("list") instanceof Map<?, ?> l) out.put("list", listSummary(l));   // ...and so do the sealed members
            if (e.get("resolution") instanceof Map<?, ?> r) out.put("resolution", resolutionSummary(r));   // ...and groups
            Integer removed = "excludeBy".equals(e.get("op"))
                    ? (step == 1 ? 0 : counts.get(step - 2)) - counts.get(step - 1) : null;
            out.put("undoneBy", undoneBy.get(step));
            out.put("text", step + ". " + render(e, removed, merged.get(step)) + (undoneBy.containsKey(step)
                    ? " (undone by step " + undoneBy.get(step) + ")" : ""));
            entries.add(out);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("header", inv.header());
        out.put("entries", entries);
        out.put("total", log.size() - Math.min(skip, log.size()));
        out.put("truncated", log.size() - Math.min(skip, log.size()) > entries.size());
        List<Map<String, Object>> pending = new ArrayList<>();
        if (inv.draft() == null) for (String rec : inv.store().listPending(inv.id())) pending.add(parse(rec));   // the four-eyes queue is the main log's
        out.put("pending", pending);
        if (inv.draft() != null) {
            out.put("draftId", inv.draft().draftId());
            out.put("baseStep", inv.draft().baseStep());
        }
        return masked(inv, out);
    }

    // ── steps ──────────────────────────────────────────────────────────────────────────────────────────

    /** Append {@code entry} after {@code log}, seal its Working Set, audit, and answer the delta. */
    private Object commit(HttpExchange ex, Inv inv, List<Map<String, Object>> log, Map<String, Object> entry,
                          InvestigationEvaluator.State before) throws IOException {
        // Round-trip BEFORE evaluating, so the append-time evaluation reads exactly what replay will read back.
        Map<String, Object> e = roundTrip(entry);
        List<Map<String, Object>> next = new ArrayList<>(log);
        next.add(e);
        // D7-4: a Draft resumes from its last checkpoint (one apply for an op) instead of folding main prefix + own log again
        InvestigationEvaluator.State after = inv.draft() == null ? evaluate(next, -1, null)
                : com.gamma.la.core.DraftCheckpoints.after(before, log, e);
        e.put("workingSetHash", after.hash());
        int step = ((Number) e.get("step")).intValue();
        inv.appendStep(log.size(), step, canonical(e), canonical(setDoc(step, after)));
        if (inv.draft() != null) com.gamma.la.core.DraftCheckpoints.remember(inv.cacheKey(), inv.logToken(), next.size(), after);

        String op = "undo".equals(e.get("kind")) ? "undo" : String.valueOf(e.get("op"));
        @SuppressWarnings("unchecked") Map<String, Object> read = (Map<String, Object>) e.get("read");
        boolean truncated = read != null && Boolean.TRUE.equals(read.get("truncated"));
        Inv.DraftRef draft = inv.draft();
        boolean undo = "undo".equals(op);
        emit(ex, draft == null ? LinkEventTypes.LINK_INVESTIGATION_STEPPED : undo ? LinkEventTypes.LINK_DRAFT_UNDONE : LinkEventTypes.LINK_DRAFT_OP_APPENDED,
                draft == null ? "link.investigation.stepped" : undo ? "link.draft.undone" : "link.draft.op_appended",
                (draft == null ? "link.investigation.stepped — " : "link.draft.step — " + draft.draftId() + " of ") + inv.id() + " step " + step + " " + op,
                b -> {
                    b.attr("investigationId", inv.id()).attr("step", step).attr("op", op);
                    if (draft != null) {   // D7-3: structured attributes only - which Draft, whose, forked from where
                        b.attr("draftId", draft.draftId()).attr("actor", ApiContext.actor(ex)).attr("baseStep", draft.baseStep());
                        if (undo) b.attr("undoes", e.get("undoes"));
                    }
                    if (read != null) b.attr("dataset", read.get("dataset")).attr("rows", read.get("rowCount"))
                            .attr("truncated", truncated).attr("fingerprint", read.get("fingerprint"));
                    // D-3 step 6: only an index-answered read says so (a Dataset-answered read keeps today's attributes)
                    if (read != null && read.get("index") instanceof Map<?, ?> ix)
                        b.attr("source", "index").attr("indexVersion", ix.get("version")).attr("indexStale", ix.get("stale"));
                    if (read != null && read.get("fallback") instanceof Map<?, ?> fb) b.attr("fallbackReason", fb.get("reason"));
                    // LA-17: which list, at which fact — never its members (as ENTITY_LIST_CHANGED carries counts).
                    if (e.get("list") instanceof Map<?, ?> l) b.attr("listId", l.get("listId")).attr("atSeq", l.get("atSeq"));
                    // LA-17 slice 2: which fact seq was pinned and how many groups it sealed — never a key.
                    if (e.get("resolution") instanceof Map<?, ?> r)
                        b.attr("atSeq", r.get("atSeq")).attr("groups", strings(r.get("groups")).size());
                    return b;
                });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("step", step);
        out.put("op", op);
        if (e.get("undoes") != null) out.put("undoes", e.get("undoes"));
        out.put("delta", InvestigationEvaluator.delta(before, after));
        out.put("truncated", truncated);
        if ("exclude".equals(op)) {
            List<String> kept = new ArrayList<>();
            for (String i : strings(((Map<?, ?>) e.get("params")).get("ids"))) if (before.kept.contains(i)) kept.add(i);
            out.put("protected", kept);
        }
        if (e.get("list") instanceof Map<?, ?> l) out.putAll(listResult(op, l, e, before, after));
        if (read != null) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("rowCount", read.get("rowCount"));
            r.put("fingerprint", read.get("fingerprint"));
            r.put("readAt", read.get("readAt"));
            r.put("fanOutCapped", read.get("fanOutCapped"));
            if (read.get("index") != null) r.put("index", read.get("index"));   // D-3 step 6: only when the index answered
            if (read.get("fallback") != null) r.put("fallback", read.get("fallback"));   // only when it fell back, and why
            r.put("rung", read.get("query"));   // the rung as READ: window resolved, frontier and exclusions included
            out.put("read", r);
        }
        if (e.get("comparison") != null) out.put("comparison", e.get("comparison"));   // the sealed diff (masked with the rest)
        if (e.get("temporalFindings") != null) out.put("temporalFindings", e.get("temporalFindings"));   // the sealed finding set (masked with the rest)
        if (after.window != null || "window".equals(op)) out.put("window", after.window);
        // LA-17 slice 2: while a resolve is in force every step answers the merged nodes, so the canvas can redraw them.
        if (after.resolution != null) out.put("resolution", after.toMap().get("resolution"));
        out.put("workingSet", summary(after));
        return out;
    }

    /**
     * The rung an {@code expand} reads with (plan §2.4): the op's validated params, resolved against the Working Set
     * it runs on — its frontier, its exclusions and, for {@code window: "inherit"}, the window the latest
     * {@code window} op set. This resolved map is recorded as {@code read.query}, so {@code reread} re-runs exactly
     * the statement that was sealed, whatever window ops come later.
     */
    /**
     * The rung an {@code expand} reads with - {@link #rung} - and, for {@code merged: true} (LA-17 merged traversal,
     * operator 2026-09-30), the frontier WIDENED to every member of each identity group a frontier entity resolves to
     * under the resolution in force: the admitted entities resolving to the group, and every value of the bound
     * columns whose key under a member's sealed type normaliser is a member (a DISTINCT read through
     * {@link InvRoutes#relationFor}, capped per column at the Space's {@code merged_distinct_cap} - default 20 000,
     * {@link LinkAnalysisSettings#effectiveMergedDistinctCap} - refused above it, never a sample). Sealed as
     * {@code query.merged {groupOf{value -> group}, anchorOf{member value not admitted -> the admitted entity it stands
     * for}}}; the read's fan-out cap then counts per GROUP, and the budget over the whole widened read - one combined
     * fan-out, never one per member (the four-eyes thresholds are compared with those same numbers, D-U7).
     */
    private Map<String, Object> expandRung(ApiContext api, HttpExchange ex, Inv inv, Map<String, Object> p,
                                           List<String> frontier, InvestigationEvaluator.State s, String where) {
        Map<String, Object> q = rung(p, frontier, s);
        if (!Boolean.TRUE.equals(p.get("merged"))) return q;
        requireResolution(s, where);
        TreeMap<String, String> groupOf = new TreeMap<>(), anchorOf = new TreeMap<>();
        TreeMap<String, Map<String, Object>> groups = new TreeMap<>();
        for (String f : frontier) {
            for (var g : s.groupsHit(List.of(f)).entrySet()) {
                groups.putIfAbsent(g.getKey(), g.getValue());
                groupOf.putIfAbsent(f, g.getKey());
            }
        }
        // admitted entities resolving to a touched group join the frontier (themselves their own anchor)
        for (String id : s.entities.keySet())
            if (!groupOf.containsKey(id))
                for (String gid : s.groupsHit(List.of(id)).keySet())
                    if (groups.containsKey(gid)) { groupOf.put(id, gid); break; }
        if (!groups.isEmpty()) {
            String relationSql = InvRoutes.relationFor(api, ex, inv.writeRoot(), inv.dataset());   // R3 gate on EVERY read
            Set<String> excluded = s.excluded.keySet();
            int cap = LinkAnalysisSettings.forRoot(inv.writeRoot()).effectiveMergedDistinctCap();
            for (String col : new LinkedHashSet<>(List.of(String.valueOf(inv.header().get("sourceCol")),
                    String.valueOf(inv.header().get("targetCol")))))
                for (String v : distinctValues(inv.dataset(), relationSql, col, cap, where + "a merged expand",
                        "the Space's merged_distinct_cap")) {
                    if (groupOf.containsKey(v) || excluded.contains(v)) continue;
                    for (var g : groups.entrySet()) {
                        if (!memberValue(g.getValue(), v)) continue;
                        groupOf.put(v, g.getKey());
                        break;
                    }
                }
        }
        // the anchor of a group: the smallest admitted frontier entity resolving to it
        TreeMap<String, String> anchor = new TreeMap<>();
        for (String f : frontier) if (groupOf.containsKey(f)) anchor.putIfAbsent(groupOf.get(f), f);
        for (var e : groupOf.entrySet())
            if (!s.entities.containsKey(e.getKey())) anchorOf.put(e.getKey(), anchor.get(e.getValue()));
        TreeSet<String> widened = new TreeSet<>(frontier);
        widened.addAll(groupOf.keySet());
        if (widened.size() > MAX_FRONTIER)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, where + "a merged expand widens its frontier to " + widened.size()
                    + " member values; an expand frontier is capped at " + MAX_FRONTIER);
        q.put("frontier", new ArrayList<>(widened));
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("groupOf", groupOf);
        merged.put("anchorOf", anchorOf);
        q.put("merged", merged);
        return q;
    }

    /** Whether raw value {@code v} keys, under a member type's sealed normaliser, to a member of sealed group {@code g}. */
    private static boolean memberValue(Map<String, Object> g, String v) {
        return !InvestigationEvaluator.rawMemberKeys(g, v).isEmpty();   // unambiguous only (D-U11)
    }

    /** {@code merged: true} is opt-in traversal OVER a resolution - without one in force it has nothing to merge. */
    private static void requireResolution(InvestigationEvaluator.State s, String where) {
        if (s.resolution == null)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, where + "'merged' traverses identity groups, and no resolve is in "
                    + "force at this point - append a resolve first");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rung(Map<String, Object> p, List<String> frontier,
                                            InvestigationEvaluator.State s) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("frontier", frontier);
        q.put("excluded", new ArrayList<>(s.excluded.keySet()));
        q.put("budget", p.get("budget"));
        q.put("direction", p.get("direction"));
        q.put("linkKinds", p.get("linkKinds"));
        Object w = p.get("window");
        q.put("window", "inherit".equals(w) ? s.window : "full".equals(w) ? null : (Map<String, Object>) w);
        q.put("minEvents", p.get("minEvents"));
        q.put("minDistinctDays", p.get("minDistinctDays"));
        q.put("candidateDegreeMin", p.get("candidateDegreeMin"));
        q.put("candidateDegreeMax", p.get("candidateDegreeMax"));
        q.put("maxFanOut", p.get("maxFanOut"));
        return q;
    }

    /**
     * The sealed one-hop read behind an {@code expand} (D-E3), for one resolved rung ({@link #rung}). Every value is
     * a bound parameter; every identifier came from the validated header. The shape, in CTE order:
     * <ol>
     *   <li>{@code ev} — the events: both endpoints present, of an allowed link kind, touching no excluded entity
     *       (prune, then expand: an excluded hub neither spends the budget nor counts toward a degree), and — when a
     *       window applies — inside it, per the {@link InvestigationTime} timezone contract;</li>
     *   <li>{@code pairs} — folded per (source, target, kind) with the IN-WINDOW event count and distinct local days,
     *       over the WHOLE Dataset, because a candidate's degree is a property of the windowed graph, not of the
     *       frontier;</li>
     *   <li>{@code cand} — the pairs touching the frontier in the rung's direction, each with its anchor (the frontier
     *       end, source first — the same choice {@link InvestigationEvaluator} makes) and its candidate (the other);</li>
     *   <li>{@code elig} — those passing {@code minEvents}, {@code minDistinctDays} and the candidate's in-window
     *       degree bounds, ranked strongest first per anchor for {@code maxFanOut}.</li>
     * </ol>
     * The budget caps the rows returned; a breach sets {@code truncated}. The fan-out cap is reported separately as
     * {@code fanOutCapped} — a rule the analyst stated, not a limit the engine hit.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> read(ApiContext api, HttpExchange ex, Inv inv, Map<String, Object> query) {
        return read(api, ex, inv, query, null);
    }

    /** The versions a Draft pinned ({@code mappingHash -> version}), from its header; null for the main log (CURRENT). D7-5: a Draft's expand reads these. */
    static Map<String, Long> draftPins(Inv inv) {
        if (inv.draft() == null) return null;
        try {
            String raw = inv.store().draftHeader(inv.id(), inv.draft().draftId()).orElse(null);
            Map<String, Long> out = new LinkedHashMap<>();
            if (raw != null && ApiContext.JSON.readValue(raw, Map.class).get("pins") instanceof Map<?, ?> p && p.get("indexes") instanceof List<?> l)
                for (Object o : l)
                    if (o instanceof Map<?, ?> m && m.get("mappingHash") != null && m.get("version") instanceof Number n)
                        out.put(String.valueOf(m.get("mappingHash")), n.longValue());
            return out;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(ApiContext api, HttpExchange ex, Inv inv, Map<String, Object> query, Map<String, Long> pinsOverride) {
        Map<String, Long> pins = pinsOverride != null ? pinsOverride : draftPins(inv);
        String dataset = inv.dataset();
        String relationSql = InvRoutes.relationFor(api, ex, inv.writeRoot(), dataset);   // R3 gate on EVERY read
        List<String> frontier = strings(query.get("frontier"));
        // LA-17 merged traversal: a member value fans out as its GROUP, so maxFanOut counts per group, not per member.
        Map<?, ?> groupOf = query.get("merged") instanceof Map<?, ?> mg && mg.get("groupOf") instanceof Map<?, ?> g
                ? g : Map.of();
        List<String> excluded = strings(query.get("excluded"));
        Map<String, Object> window = query.get("window") instanceof Map<?, ?> w ? (Map<String, Object>) w : null;
        Integer minDays = query.get("minDistinctDays") instanceof Number n ? n.intValue() : null;
        Integer degMin = query.get("candidateDegreeMin") instanceof Number n ? n.intValue() : null;
        Integer degMax = query.get("candidateDegreeMax") instanceof Number n ? n.intValue() : null;
        Integer fanOut = query.get("maxFanOut") instanceof Number n ? n.intValue() : null;
        List<String> kinds = query.get("linkKinds") == null ? null : strings(query.get("linkKinds"));
        String direction = String.valueOf(query.get("direction"));
        int budget = ((Number) query.get("budget")).intValue();
        boolean timed = window != null || minDays != null;

        List<Map<String, Object>> rows = new ArrayList<>();
        boolean truncated = false;
        long capped = 0;
        // D-3 step 6: a SIMPLE rung is answered from the edge index when - and only when - it can answer exactly
        // (IndexedExpand); anything else, and every index failure, falls through to the CTE below untouched. The R3 gate above has run.
        IndexedRead.Outcome<IndexedExpand.Result> indexed = null;
        if (!frontier.isEmpty()) {
            Map<String, Object> hdr = inv.header();
            indexed = IndexedExpand.attempt(inv.writeRoot(), api.dataRoot(), relationSql, new IndexedExpand.Request(dataset,
                    String.valueOf(hdr.get("sourceCol")), String.valueOf(hdr.get("targetCol")),
                    hdr.get("linkKindCol") == null ? null : String.valueOf(hdr.get("linkKindCol")), frontier, excluded, kinds,
                    direction, ((Number) query.get("minEvents")).longValue(), fanOut, budget, window != null, minDays != null,
                    degMin != null || degMax != null, query.get("merged") != null), InvRoutes.traversalPolicy(), pins);
            if (indexed.served()) {
                rows = new ArrayList<>(indexed.result().rows());
                truncated = indexed.result().truncated();
                capped = indexed.result().capped();
            }
        }
        if (!frontier.isEmpty() && (indexed == null || !indexed.served())) {
            Map<String, Object> h = inv.header();
            String src = SqlIdent.q(String.valueOf(h.get("sourceCol")));
            String tgt = SqlIdent.q(String.valueOf(h.get("targetCol")));
            String kind = h.get("linkKindCol") == null ? null : SqlIdent.q(String.valueOf(h.get("linkKindCol")));
            List<String> binds = new ArrayList<>();
            StringBuilder sql = new StringBuilder("WITH fr(id, g) AS (VALUES ")
                    .append(String.join(",", Collections.nCopies(frontier.size(), "(?, ?)"))).append(")");
            for (String f : frontier) {
                binds.add(f);
                binds.add(groupOf.get(f) == null ? f : String.valueOf(groupOf.get(f)));
            }
            sql.append(", ev0 AS (SELECT CAST(").append(src).append(" AS VARCHAR) AS s, CAST(").append(tgt)
               .append(" AS VARCHAR) AS t, ").append(kind == null ? "CAST(NULL AS VARCHAR)" : "CAST(" + kind + " AS VARCHAR)")
               .append(" AS k");
            if (timed) sql.append(", ").append(InvestigationTime.instantExpr(
                    SqlIdent.q(String.valueOf(h.get("timeCol"))),
                    h.get("timeColZone") == null ? null : String.valueOf(h.get("timeColZone")), binds)).append(" AS ts");
            sql.append(" FROM ").append(SqlIdent.q(dataset)).append(" WHERE ").append(src).append(" IS NOT NULL AND ")
               .append(tgt).append(" IS NOT NULL");
            if (kinds != null) {
                sql.append(" AND CAST(").append(kind).append(" AS VARCHAR) IN (")
                   .append(String.join(",", Collections.nCopies(kinds.size(), "?"))).append(")");
                binds.addAll(kinds);
            }
            if (!excluded.isEmpty()) {
                String out = String.join(",", Collections.nCopies(excluded.size(), "?"));
                sql.append(" AND CAST(").append(src).append(" AS VARCHAR) NOT IN (").append(out)
                   .append(") AND CAST(").append(tgt).append(" AS VARCHAR) NOT IN (").append(out).append(")");
                binds.addAll(excluded);
                binds.addAll(excluded);
            }
            sql.append(")");
            if (timed) {
                sql.append(", ev1 AS (SELECT *, timezone(?, ts) AS lt FROM ev0), ev AS (SELECT * FROM ev1 WHERE TRUE");
                binds.add(InvestigationTime.localZone(window));
                if (window != null) InvestigationTime.predicates(window, sql, binds);
                sql.append(")");
            } else {
                sql.append(", ev AS (SELECT * FROM ev0)");
            }
            sql.append(", pairs AS (SELECT s, t, k, COUNT(*) AS cnt, ")
               .append(timed ? "COUNT(DISTINCT CAST(lt AS DATE))" : "0").append(" AS days FROM ev GROUP BY s, t, k)");
            boolean degree = degMin != null || degMax != null;
            if (degree)
                sql.append(", deg AS (SELECT id, COUNT(DISTINCT o) AS d FROM (SELECT s AS id, t AS o FROM pairs "
                        + "UNION ALL SELECT t AS id, s AS o FROM pairs) u GROUP BY id)");
            String inF = " IN (SELECT id FROM fr)";
            sql.append(", cand AS (SELECT p.*, CASE WHEN p.s").append(inF).append(" THEN p.s ELSE p.t END AS anchor, ")
               .append("CASE WHEN p.s").append(inF).append(" THEN p.t ELSE p.s END AS other FROM pairs p WHERE ")
               .append(switch (direction) {
                   case "out" -> "p.s" + inF;
                   case "in" -> "p.t" + inF;
                   case "reciprocal" -> "(p.s" + inF + " OR p.t" + inF + ") AND EXISTS (SELECT 1 FROM pairs r "
                           + "WHERE r.s = p.t AND r.t = p.s)";
                   default -> "(p.s" + inF + " OR p.t" + inF + ")";
               }).append(")");
            sql.append(", elig AS (SELECT c.*, ROW_NUMBER() OVER (PARTITION BY (SELECT fr.g FROM fr WHERE fr.id = c.anchor) ORDER BY c.cnt DESC, c.s, c.t, "
                    + "c.k NULLS FIRST) AS rn FROM cand c");
            if (degree) sql.append(" JOIN deg ON deg.id = c.other");
            sql.append(" WHERE c.cnt >= CAST(? AS BIGINT)");
            binds.add(String.valueOf(query.get("minEvents")));
            if (minDays != null) {
                sql.append(" AND c.days >= CAST(? AS BIGINT)");
                binds.add(minDays.toString());
            }
            if (degree) {   // an already-frontier candidate is admitted already; its degree gates nothing
                sql.append(" AND (c.other").append(inF)
                   .append(" OR (deg.d >= CAST(? AS BIGINT) AND deg.d <= CAST(? AS BIGINT)))");
                binds.add(Integer.toString(degMin == null ? 0 : degMin));
                binds.add(Long.toString(degMax == null ? Long.MAX_VALUE : degMax));
            }
            sql.append(")");
            sql.append(" SELECT s AS source, t AS target, k AS kind, cnt, ");
            if (fanOut != null) {
                sql.append("(SELECT COUNT(*) FROM elig WHERE rn > CAST(? AS BIGINT)) AS capped FROM elig "
                        + "WHERE rn <= CAST(? AS BIGINT)");
                binds.add(fanOut.toString());
                binds.add(fanOut.toString());
            } else {
                sql.append("0 AS capped FROM elig");
            }
            sql.append(" ORDER BY cnt DESC, source, target, kind NULLS FIRST");
            try {
                DatasetProvider.Result r = DatasetProviders.require().run(new DatasetProvider.Request(
                        dataset, relationSql, sql.toString(), budget, 0, List.of(), List.of(), binds));
                for (Map<String, Object> row : r.rows()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("source", row.get("source"));
                    m.put("target", row.get("target"));
                    m.put("kind", kind != null ? row.get("kind") : null);
                    m.put("count", ((Number) row.get("cnt")).longValue());
                    rows.add(m);
                    capped = ((Number) row.get("capped")).longValue();
                }
                truncated = r.truncated();
            } catch (SQLException | IOException e) {
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "expand over dataset '" + dataset + "' failed: " + e.getMessage());
            }
        }
        Map<String, Object> read = new LinkedHashMap<>();
        read.put("dataset", dataset);
        read.put("readAt", Instant.now().toString());   // weak provenance — NOT a replay pin (D-E3)
        read.put("query", query);
        read.put("rows", rows);
        read.put("rowCount", rows.size());
        read.put("truncated", truncated);
        read.put("fanOutCapped", capped);
        read.put("fingerprint", InvestigationEvaluator.sha256(canonical(rows)));   // rows ONLY: where they were read from never enters it
        if (indexed != null && indexed.served()) read.put("index", indexed.readIndex());
        else if (indexed != null) read.put("fallback", indexed.readFallback());   // why the flat Dataset answered; never in the fingerprint
        return read;
    }

    /**
     * LA-17 (design §4.4.1) — resolve Entity List {@code listId} at the identity fact log's HEAD and seal it:
     * {@code {listId, atSeq, headHash, entityType, normaliser, purpose, masked, members[]}}, the members as they are now, so
     * replay never re-reads the list. Refusals, in order: unknown list 404 · retired 409 · its Entity Type no longer
     * in force 409 · more than {@link #MAX_LIST_MEMBERS} members 422. A broken fact chain is a 500.
     */
    private static Map<String, Object> sealList(Path writeRoot, String listId, String where) throws IOException {
        EntityFactLog.Log head = EntityListFacts.read(new EntityFactLog(writeRoot));
        EntityRegistry.EntityList l = EntityRegistry.fold(head.facts(), head.headSeq()).get(listId);
        if (l == null) throw new ApiException(404, ErrorCodes.NOT_FOUND, where + "entity list '" + listId + "' not found");
        if (l.retired()) throw new ApiException(409, ErrorCodes.CONFLICT, where + "entity list '" + listId + "' is retired");
        EntityTypes.EntityType type = EntityListFacts.type(writeRoot, l.entityType()).orElseThrow(() -> new ApiException(409,
                ErrorCodes.CONFLICT, where + "entity list '" + listId + "' is of Entity Type '" + l.entityType()
                        + "', which is no longer in force"));
        if (l.members().size() > MAX_LIST_MEMBERS)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, where + "entity list '" + listId + "' has "
                    + l.members().size() + " members; a list op seals at most " + MAX_LIST_MEMBERS);
        Map<String, Object> sealed = new LinkedHashMap<>();
        sealed.put("listId", listId);
        sealed.put("atSeq", head.headSeq());
        sealed.put("headHash", head.headHash());
        sealed.put("entityType", l.entityType());
        sealed.put("normaliser", l.normaliser());   // D-M9: the list's sealed rule — its members were stored under it
        sealed.put("purpose", l.purpose());
        sealed.put("masked", type.masked());   // the type's masking flag AS SEALED — EntityMasking reads only the log
        // ASSURE-ENTITY-LISTS-1: an expired entry no longer matches, so it is not sealed. Range entries are not
        // sealed by excludeBy / seedBy yet (they match exact keys only).
        sealed.put("members", new ArrayList<>(l.liveMembers(java.time.Instant.now())));
        return sealed;
    }

    /**
     * LA-17 slice 2 (design §8.1) — the Space's identity resolution at identity fact seq {@code atSeq} (the head when
     * null), SEALED so replay, {@code ?at}, a fork and the Dossier never re-read the fact log:
     * {@code {atSeq, atHash, headSeq, headHash, groups[{id, members[], assertions[{seq, a, b, via, actor, at, reason}]}],
     * types{id → {normaliser, masked}}, columnTypes{source, target}}}. {@code types} are the Entity Types in force NOW
     * (an entity's typed key is minted with the sealed normaliser, and {@link EntityMasking} reads the sealed flag, fail
     * closed); {@code columnTypes} are the bound columns' types by registry classification, as the projection routes
     * report them ({@code InvRoutes.columnTypes}). {@code atHash} is the chain hash of the fact AT {@code atSeq} (null at
     * 0), so the pinned prefix is itself verifiable. Refusals: {@code atSeq} past the head 422 · more than
     * {@link #MAX_LIST_MEMBERS} member keys in all 422 (bounded like a list op). A broken fact chain is a 500.
     */
    static Map<String, Object> sealResolution(Path writeRoot, Map<String, Object> header, Object atSeq, String where)
            throws IOException {
        EntityFactLog.Log head = EntityListFacts.read(new EntityFactLog(writeRoot));
        long at = atSeq instanceof Number n ? n.longValue() : head.headSeq();
        if (at > head.headSeq())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, where + "'atSeq' " + at
                    + " is beyond the identity fact log head " + head.headSeq());
        Map<Long, EntityRegistry.Assertion> assertions = EntityRegistry.assertions(head.facts(), at);
        List<Map<String, Object>> groups = new ArrayList<>();
        int keys = 0;
        for (EntityRegistry.Group g : EntityRegistry.resolve(head.facts(), at).values()) {
            keys += g.members().size();
            List<Map<String, Object>> joined = new ArrayList<>();
            for (long seq : g.assertions()) {
                EntityRegistry.Assertion x = assertions.get(seq);
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("seq", x.seq());
                a.put("a", x.a());
                a.put("b", x.b());
                a.put("via", x.via());
                a.put("actor", x.actor());
                a.put("at", x.at());
                a.put("reason", x.reason());
                joined.add(a);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", g.id());
            m.put("members", new ArrayList<>(g.members()));
            m.put("assertions", joined);
            groups.add(m);
        }
        if (keys > MAX_LIST_MEMBERS)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, where + "the identity resolution at seq " + at + " has "
                    + keys + " member keys; a resolve seals at most " + MAX_LIST_MEMBERS);
        String atHash = null;
        for (EntityFactLog.Fact f : head.facts()) if (f.seq() == at) atHash = f.hash();
        Map<String, Object> types = new java.util.TreeMap<>();
        for (EntityTypes.EntityType t : LinkAnalysisSettings.forRoot(writeRoot).effectiveEntityTypes())
            types.put(t.id(), Map.of("normaliser", t.normaliser(), "masked", t.masked()));
        String src = String.valueOf(header.get("sourceCol")), tgt = String.valueOf(header.get("targetCol"));
        Map<String, Map<String, Object>> cols = InvRoutes.columnTypes(writeRoot, String.valueOf(header.get("dataset")), List.of(src, tgt));
        Map<String, Object> columnTypes = new LinkedHashMap<>();
        columnTypes.put("source", cols.containsKey(src) ? cols.get(src).get("id") : null);
        columnTypes.put("target", cols.containsKey(tgt) ? cols.get(tgt).get("id") : null);
        Map<String, Object> sealed = new LinkedHashMap<>();
        sealed.put("atSeq", at);
        sealed.put("atHash", atHash);
        sealed.put("headSeq", head.headSeq());
        sealed.put("headHash", head.headHash());
        sealed.put("groups", groups);
        sealed.put("types", types);
        sealed.put("columnTypes", columnTypes);
        return sealed;
    }

    /** A sealed resolution as the log view shows it: the pin and counts, not the groups (the log line hashes them). */
    static Map<String, Object> resolutionSummary(Map<?, ?> r) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : List.of("atSeq", "atHash", "headSeq", "headHash", "columnTypes")) out.put(k, r.get(k));
        int members = 0;
        List<?> groups = r.get("groups") instanceof List<?> g ? g : List.of();
        for (Object g : groups) if (g instanceof Map<?, ?> m) members += strings(m.get("members")).size();
        out.put("groups", groups.size());
        out.put("members", members);
        return out;
    }

    /** {@code "Applied identity resolution as of fact 12 (3 groups, 7 member keys)."} — no key is named. */
    static String resolveLine(Map<String, Object> e) {
        Map<String, Object> s = resolutionSummary((Map<?, ?>) e.get("resolution"));
        int g = (Integer) s.get("groups"), m = (Integer) s.get("members");
        return "Applied identity resolution as of fact " + s.get("atSeq") + " (" + g + " group" + (g == 1 ? "" : "s") + ", "
                + m + " member key" + (m == 1 ? "" : "s") + "): an entity whose typed key is a member shows as its merged "
                + "node; traversal and counts are unchanged.";
    }

    /**
     * The sealed read behind a {@code seedBy} (design §4.4.1): the DISTINCT values of the bound {@code sourceCol} and
     * {@code targetCol} over the Investigation's Dataset (R3 gate on the read, as {@link #read}), normalised in Java
     * with the sealed list's normaliser, keeping the RAW values whose key is a member. Shaped like an expand's read —
     * {@code {dataset, readAt, query, ids[], rowCount, fingerprint}}, the fingerprint over the ids — so the Dossier's
     * integrity check covers it. Above the Space's {@code seed_by_distinct_cap} ({@link
     * LinkAnalysisSettings#effectiveSeedByDistinctCap}) distinct values in a column → 422, never a sample.
     */
    private Map<String, Object> seedRead(ApiContext api, HttpExchange ex, Inv inv, Map<String, Object> list) {
        String dataset = inv.dataset();
        String relationSql = InvRoutes.relationFor(api, ex, inv.writeRoot(), dataset);   // R3 gate on EVERY read
        String normaliser = String.valueOf(list.get("normaliser"));
        Set<String> members = new HashSet<>(strings(list.get("members")));
        List<String> columns = new ArrayList<>(new LinkedHashSet<>(List.of(
                String.valueOf(inv.header().get("sourceCol")), String.valueOf(inv.header().get("targetCol")))));
        TreeSet<String> ids = new TreeSet<>();
        int cap = LinkAnalysisSettings.forRoot(inv.writeRoot()).effectiveSeedByDistinctCap();
        for (String col : columns)
            for (String v : distinctValues(dataset, relationSql, col, cap, "seedBy", "the Space's seed_by_distinct_cap"))
                if (members.contains(EntityTypes.normalise(normaliser, v))) ids.add(v);
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("columns", columns);
        query.put("distinctCap", cap);
        List<String> sealed = new ArrayList<>(ids);
        Map<String, Object> read = new LinkedHashMap<>();
        read.put("dataset", dataset);
        read.put("readAt", Instant.now().toString());   // weak provenance — NOT a replay pin (D-E3)
        read.put("query", query);
        read.put("ids", sealed);
        read.put("rowCount", sealed.size());
        read.put("fingerprint", InvestigationEvaluator.sha256(canonical(sealed)));
        return read;
    }

    /**
     * The distinct non-null values of {@code col} (a validated header identifier) as text, at most {@code cap} —
     * more is a 422 naming the cap. No value is ever inlined: the statement carries identifiers only.
     */
    static List<String> distinctValues(String dataset, String relationSql, String col, int cap) {
        return distinctValues(dataset, relationSql, col, cap, "seedBy", "the cap");
    }

    /** {@link #distinctValues(String, String, String, int)}, its refusals naming {@code what} and the cap's {@code source}. */
    static List<String> distinctValues(String dataset, String relationSql, String col, int cap, String what, String source) {
        String c = SqlIdent.q(col);
        String sql = "SELECT DISTINCT CAST(" + c + " AS VARCHAR) AS v FROM " + SqlIdent.q(dataset) + " WHERE " + c + " IS NOT NULL";
        DatasetProvider.Result r;
        try {
            r = DatasetProviders.require().run(new DatasetProvider.Request(dataset, relationSql, sql, cap, 0, List.of(), List.of()));
        } catch (SQLException | IOException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, what + " over dataset '" + dataset + "' failed: " + e.getMessage());
        }
        if (r.truncated())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, what + " reads at most " + cap + " distinct values per "
                    + "column (" + source + "), and column '" + col + "' of dataset '" + dataset + "' has more — never a "
                    + "silent sample; narrow the Dataset" + ("seedBy".equals(what) ? " or seed the ids explicitly" : ""));
        List<String> out = new ArrayList<>(r.rows().size());
        for (Map<String, Object> row : r.rows()) out.add(String.valueOf(row.get("v")));
        return out;
    }

    /** A sealed list as the log view shows it: everything but the members, which it counts (as the rows stay out). */
    private static Map<String, Object> listSummary(Map<?, ?> l) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : l.entrySet()) if (!"members".equals(e.getKey())) out.put(String.valueOf(e.getKey()), e.getValue());
        out.put("size", strings(l.get("members")).size());
        return out;
    }

    /**
     * What a list op's step reports (design §4.4.1): the list it sealed, and — {@code excludeBy} — how many entities
     * it removed, the kept ones it could not ({@code protected}) and the members that matched no admitted entity;
     * {@code seedBy} — how many ids it seeded and the members that matched no value of the bound columns.
     */
    private static Map<String, Object> listResult(String op, Map<?, ?> l, Map<String, Object> e,
                                                  InvestigationEvaluator.State before, InvestigationEvaluator.State after) {
        String normaliser = String.valueOf(l.get("normaliser"));
        List<String> members = strings(l.get("members"));
        Set<String> matched = new HashSet<>();
        Map<String, Object> list = new LinkedHashMap<>();
        for (String k : List.of("listId", "atSeq", "headHash", "entityType", "purpose")) list.put(k, l.get(k));
        list.put("members", members.size());
        Map<String, Object> out = new LinkedHashMap<>();
        if ("excludeBy".equals(op)) {
            List<String> kept = new ArrayList<>();
            for (String id : before.entities.keySet()) {
                String key = EntityTypes.normalise(normaliser, id);
                if (!members.contains(key)) continue;
                matched.add(key);
                if (before.kept.contains(id)) kept.add(id);
            }
            int removed = 0;
            for (String id : before.entities.keySet()) if (!after.entities.containsKey(id)) removed++;
            list.put("removed", removed);
            out.put("protected", kept);
        } else {
            @SuppressWarnings("unchecked") List<String> ids = strings(((Map<String, Object>) e.get("read")).get("ids"));
            for (String id : ids) matched.add(EntityTypes.normalise(normaliser, id));
            list.put("seeded", ids.size());
        }
        List<String> unmatched = new ArrayList<>();
        for (String m : members) if (!matched.contains(m)) unmatched.add(m);
        list.put("unmatched", unmatched);
        out.put("list", list);
        return out;
    }

    /**
     * The bindings an op needs from the Investigation's header, checked at append (and at template
     * instantiation): {@code linkKinds} needs a link-kind column; a window, a {@code compare}, or {@code minDistinctDays}, needs a
     * time column.
     */
    private static void requireBindings(Map<String, Object> header, String op, Map<String, Object> p, String where) {
        boolean timed = op.equals("window") || op.equals("compare") || op.equals("temporal")
                || (op.equals("expand") && (p.get("window") instanceof Map<?, ?> || p.get("minDistinctDays") != null));
        if (timed && header.get("timeCol") == null)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, where + "this Investigation has no time column — create it with 'timeCol' "
                    + "to use a window or minDistinctDays");
        if (op.equals("expand") && p.get("linkKinds") != null && header.get("linkKindCol") == null)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, where + "'linkKinds' needs a link-kind column — this Investigation has none");
    }

    /** Validate and normalise one op's parameters (422 on anything malformed). */
    private static Map<String, Object> params(String op, Map<String, Object> body) {
        Map<String, Object> p = new LinkedHashMap<>();
        if (LIST_OPS.contains(op)) {   // LA-17: over a named Entity List, which the append seals — never over ids
            if (body.containsKey("ids"))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + op + "' names an Entity List, not ids — send 'listId'");
            String listId = ApiContext.str(body, "listId");
            if (listId == null || !EntityListFacts.LIST_ID.matcher(listId).matches())
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + op + "' requires 'listId', an Entity List id matching "
                        + EntityListFacts.LIST_ID.pattern());
            p.put("listId", listId);
            if (op.equals("excludeBy")) p.put("reason", exclusionReason(body, op));
            return p;
        }
        if (op.equals("resolve")) {   // LA-17 slice 2: no ids — the pinned identity fact seq, or the head when absent
            if (body.containsKey("ids"))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'resolve' applies the Space's identity resolution, not ids — "
                        + "send 'atSeq' (optional)");
            Object at = body.get("atSeq");
            if (at != null && (!(at instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue()) || n.longValue() < 0))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'atSeq' must be an identity fact seq (an integer >= 0), got " + at);
            if (at != null) p.put("atSeq", ((Number) at).longValue());
            return p;
        }
        if (op.equals("window")) {   // no ids: an intensional op over time, not over entities
            Object w = body.get("window");
            if (w == null) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'window' requires 'window': an object, or \"full\" to clear it");
            p.put("window", "full".equals(w) ? null : InvestigationTime.window(w, "window"));
            return p;
        }
        if (op.equals("compare")) {   // no ids: two windows of the bound time column, diffed over the Working Set at this position
            if (body.containsKey("ids"))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'compare' applies to the whole Working Set, not ids");
            for (String w : List.of("windowA", "windowB")) {
                if (body.get(w) == null)
                    throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'compare' requires '" + w + "': {from?, to?, timezone?}, or \"inherit\" "
                            + "(the Investigation's window at this step)");
                p.put(w, "inherit".equals(body.get(w)) ? "inherit" : InvestigationTime.window(body.get(w), w));
            }
            Object mode = body.get("mode");
            if (mode != null && !"presence".equals(mode) && !"activity".equals(mode))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'mode' is \"presence\" (default) or \"activity\" (event-count delta), got " + mode);
            if ("activity".equals(mode)) p.put("mode", "activity");   // presence is the default and stays out of the params
            Long minAbs = WindowComparison.minAbsDelta(body.get("minAbsDelta"));
            if (minAbs != null) {
                if (!"activity".equals(mode))
                    throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'minAbsDelta' applies to \"mode\": \"activity\" only");
                p.put("minAbsDelta", minAbs);   // sealed in the params, and in the diff's hashed content, only when given
            }
            return p;
        }
        if (op.equals("temporal")) return TemporalFindings.params(body);   // no ids: the scan knobs; the columns are the Investigation's own
        if (op.equals("threshold") || op.equals("snapshot")) {   // no ids: over the whole Working Set / a log position
            if (body.containsKey("ids"))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + op + "' applies to the whole Working Set, not ids");
            if (op.equals("snapshot")) {
                String label = ApiContext.str(body, "label");
                if (label != null && label.length() > MAX_NOTE_LENGTH)
                    throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'label' is at most " + MAX_NOTE_LENGTH + " chars");
                if (label != null) p.put("label", label);
                return p;
            }
            if (body.get("min") == null && body.get("max") == null)
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'threshold' requires 'min' and/or 'max' - the band of the measure (default degree) to keep "
                        + "(min inclusive, max exclusive)");
            Object measure = body.get("measure");
            if (measure != null) {
                if (!InvestigationEvaluator.MEASURES.contains(String.valueOf(measure)))
                    throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'measure' must be one of "
                            + InvestigationEvaluator.MEASURES + ", got '" + measure + "'");
                if (!"degree".equals(String.valueOf(measure))) p.put("measure", String.valueOf(measure));   // degree is the absent default: old entries stay byte-identical
            }
            Integer min = body.get("min") == null ? null : positive(body, "min", 0);
            Integer max = body.get("max") == null ? null : positive(body, "max", 1);
            if (min != null && max != null && min >= max)
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'min' must be less than 'max' (max is exclusive)");
            if (min != null) p.put("min", min);
            if (max != null) p.put("max", max);
            return p;
        }
        Object rawIds = body.get("ids");
        if (rawIds != null && !(rawIds instanceof List<?>)) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'ids' must be a list");
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (rawIds instanceof List<?> l) for (Object o : l) {
            String v = o == null ? "" : String.valueOf(o);
            if (v.isBlank() || v.length() > MAX_ID_LENGTH)
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "every id must be a non-blank string of at most " + MAX_ID_LENGTH + " chars");
            ids.add(v);
        }
        if (ids.size() > MAX_IDS) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "at most " + MAX_IDS + " ids per op");
        boolean linkNote = op.equals("annotate") && body.get("links") != null;   // D-U9: an annotate may name only links
        if (!op.equals("expand") && ids.isEmpty() && !linkNote)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "op '" + op + "' requires 'ids'");
        p.put("ids", new ArrayList<>(ids));
        switch (op) {
            case "seed" -> {
                String type = ApiContext.str(body, "entityType");
                if (type != null && type.length() > 64) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'entityType' is at most 64 chars");
                p.put("entityType", type);
            }
            case "expand" -> {
                expandParams(body, p);
                merged(body, p);
            }
            case "exclude" -> {
                p.put("reason", exclusionReason(body, op));
                merged(body, p);
            }
            case "annotate" -> {
                String note = ApiContext.str(body, "note");
                if (note == null || note.isBlank()) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'annotate' requires a 'note'");
                if (note.length() > MAX_NOTE_LENGTH)
                    throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'note' is at most " + MAX_NOTE_LENGTH + " chars");
                p.put("note", note);
                // D-U9: an Admiralty grade (A-F x 1-6). Absent when not given, so an ungraded annotate is sealed
                // byte-for-byte as it was before the grade existed.
                if (body.get("confidence") != null) p.put("confidence", AdmiraltyGrade.validate(body.get("confidence")));
                // D-U9 remainder: links named by their wire id (LinkIds), sealed as the decoded {source, target, kind}
                // (pseudonyms are resolved by the append, which has the Investigation). Absent when none, so an
                // entity-only annotate seals exactly as before.
                if (linkNote) p.put("links", linkParams(body.get("links")));
            }
            default -> { }
        }
        return p;
    }

    /** An annotate's {@code links}: a non-empty list of {@link LinkIds} wire ids, each decoded (duplicates dropped). */
    private static List<Map<String, Object>> linkParams(Object raw) {
        if (!(raw instanceof List<?> l) || l.isEmpty())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'links' must be a non-empty list of link ids");
        if (l.size() > MAX_IDS) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "at most " + MAX_IDS + " links per op");
        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : l) {
            List<String> parts = LinkIds.decode(o instanceof String v ? v : null);
            if (!seen.add(LinkIds.key(parts.get(0), parts.get(1), parts.get(2)))) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("source", parts.get(0));
            m.put("target", parts.get(1));
            m.put("kind", parts.get(2));
            out.add(m);
        }
        return out;
    }

    /** An annotate's decoded links with every pseudonym this Investigation issued resolved to its entity (D-U6). */
    @SuppressWarnings("unchecked")
    private static void resolveLinkPseudonyms(Inv inv, Map<String, Object> params) throws IOException {
        List<Map<String, Object>> links = (List<Map<String, Object>>) params.get("links");
        if (links.stream().noneMatch(m -> m.values().stream().anyMatch(v -> String.valueOf(v).startsWith(EntityMasking.TOKEN_PREFIX))))
            return;
        EntityMasking mask = EntityMasking.of(inv, List.of());
        for (Map<String, Object> m : links)
            for (String k : List.of("source", "target", "kind")) m.put(k, mask.resolve(List.of(String.valueOf(m.get(k)))).get(0));
    }

    /**
     * LA-17 merged traversal (operator 2026-09-30): {@code merged} is an OPT-IN boolean on {@code expand} and
     * {@code exclude}; set only when true, so every op without it seals - and hashes - exactly as before.
     */
    private static void merged(Map<String, Object> body, Map<String, Object> p) {
        Object m = body.get("merged");
        if (m == null || Boolean.FALSE.equals(m)) return;
        if (!Boolean.TRUE.equals(m)) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'merged' must be a boolean, got " + m);
        p.put("merged", true);
    }

    /** An exclusion's required reason ({@code exclude}, {@code excludeBy}): one without it cannot be challenged. */
    private static String exclusionReason(Map<String, Object> body, String op) {
        String reason = ApiContext.str(body, "reason");
        if (reason == null) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + op + "' requires a 'reason' — an exclusion "
                + "without one cannot be challenged");
        if (reason.length() > 200) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'reason' is at most 200 chars");
        return reason;
    }

    /**
     * One hop-ladder rung (plan §2.4): {@code direction} (either · out · in · reciprocal, default either) ·
     * {@code linkKinds} (null = all) · {@code window} ("inherit" the Investigation's current window — the default —
     * "full", or an override object) · {@code minEvents} (default 1) · {@code minDistinctDays} ·
     * {@code candidateDegreeMin}/{@code Max} (evaluated within the window) · {@code maxFanOut} (strongest first) ·
     * {@code budget} (rows read; a breach sets {@code truncated}).
     */
    private static void expandParams(Map<String, Object> body, Map<String, Object> p) {
        if (body.containsKey("limit"))   // renamed by LA-13 — refused, never silently replaced by the default
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'limit' is now 'budget' (plan §2.4: the rung's row budget)");
        p.put("budget", body.get("budget") == null ? DEFAULT_EXPAND_BUDGET
                : Math.min(MAX_EXPAND_BUDGET, positive(body, "budget", 1)));
        String direction = body.get("direction") == null ? "either" : String.valueOf(body.get("direction"));
        if (!DIRECTIONS.contains(direction))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'direction' must be one of " + DIRECTIONS + ", got '" + direction + "'");
        p.put("direction", direction);
        List<String> kinds = null;
        if (body.get("linkKinds") != null) {
            if (!(body.get("linkKinds") instanceof List<?> l) || l.isEmpty() || l.size() > MAX_LINK_KINDS)
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'linkKinds' must be a non-empty list of at most " + MAX_LINK_KINDS
                        + " kinds (omit it for all kinds)");
            kinds = new ArrayList<>(new java.util.TreeSet<>(strings(l)));
        }
        p.put("linkKinds", kinds);
        Object w = body.get("window");
        p.put("window", w == null || "inherit".equals(w) ? "inherit" : "full".equals(w) ? "full"
                : InvestigationTime.window(w, "expand.window"));
        p.put("minEvents", body.get("minEvents") == null ? 1 : positive(body, "minEvents", 1));
        p.put("minDistinctDays", body.get("minDistinctDays") == null ? null : positive(body, "minDistinctDays", 1));
        Integer dMin = body.get("candidateDegreeMin") == null ? null : positive(body, "candidateDegreeMin", 0);
        Integer dMax = body.get("candidateDegreeMax") == null ? null : positive(body, "candidateDegreeMax", 1);
        if (dMin != null && dMax != null && dMin > dMax)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'candidateDegreeMin' must not exceed 'candidateDegreeMax'");
        p.put("candidateDegreeMin", dMin);
        p.put("candidateDegreeMax", dMax);
        p.put("maxFanOut", body.get("maxFanOut") == null ? null : positive(body, "maxFanOut", 1));
    }

    private static int positive(Map<String, Object> body, String key, int min) {
        if (!(body.get(key) instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())
                || n.longValue() < min || n.longValue() > Integer.MAX_VALUE)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + key + "' must be an integer >= " + min + ", got " + body.get(key));
        return n.intValue();
    }

    /**
     * Validate {@code timeCol}'s type and settle its zone (see {@link InvestigationTime}): a naive {@code TIMESTAMP}
     * takes {@code timeColZone} (UTC when absent, recorded explicitly); a {@code TIMESTAMP WITH TIME ZONE} is an
     * instant and refuses one. Anything else is not an event time. Returns the zone to record, or null.
     */
    private static String timeColZone(String dataset, String relationSql, String timeCol, String zone) {
        if (timeCol == null) {
            if (zone != null) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'timeColZone' needs a 'timeCol'");
            return null;
        }
        String type;
        try {
            DatasetProvider.Result r = DatasetProviders.require().run(new DatasetProvider.Request(dataset, relationSql,
                    "SELECT typeof(x) AS t FROM ((SELECT " + SqlIdent.q(timeCol) + " AS x FROM " + SqlIdent.q(dataset)
                            + " LIMIT 0) UNION ALL (SELECT NULL)) u", 1, 0, List.of(), List.of()));
            type = String.valueOf(r.rows().get(0).get("t")).toUpperCase(java.util.Locale.ROOT);
        } catch (Exception unusable) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "cannot read the type of '" + timeCol + "': " + unusable.getMessage());
        }
        if (type.equals("TIMESTAMP WITH TIME ZONE")) {
            if (zone != null) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + timeCol + "' is TIMESTAMP WITH TIME ZONE — already an "
                    + "instant, so a 'timeColZone' would be ignored; omit it");
            return null;
        }
        if (!type.equals("TIMESTAMP"))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'timeCol' must be a TIMESTAMP or TIMESTAMP WITH TIME ZONE column; '" + timeCol
                    + "' is " + type);
        String z = zone == null ? "UTC" : zone;
        String refusal = com.gamma.config.spec.SourceZoneGrammar.zoneRefusal(z, "timeColZone");
        if (refusal != null) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, refusal);
        return z;
    }

    /**
     * The plain-language line for one step (plan §2.7: every op, including exclusions, with its reason). {@code removed}
     * is how many entities an {@code excludeBy} removed — a property of the state it ran on — and null otherwise;
     * {@code merged} is what a merged {@code exclude} did ({@link InvestigationEvaluator.State#lastMerged}), else null.
     */
    @SuppressWarnings("unchecked")
    private static String render(Map<String, Object> e, Integer removed, Map<String, List<String>> merged) {
        if ("undo".equals(e.get("kind"))) return "Undid step " + e.get("undoes") + ".";
        Map<String, Object> p = (Map<String, Object>) e.get("params");
        List<String> ids = strings(p.get("ids"));
        return switch (String.valueOf(e.get("op"))) {
            case "seed" -> "Seeded " + ids.size() + " entit" + (ids.size() == 1 ? "y" : "ies")
                    + (p.get("entityType") != null ? " of type " + p.get("entityType") : "") + ": " + list(ids) + ".";
            case "expand" -> {
                Map<String, Object> r = (Map<String, Object>) e.get("read");
                int frontier = strings(((Map<String, Object>) r.get("query")).get("frontier")).size();
                Map<String, Object> q = (Map<String, Object>) r.get("query");
                yield "Expanded one hop from " + frontier + " entit" + (frontier == 1 ? "y" : "ies") + " over "
                        + r.get("dataset") + InvestigationTime.rungClause(q) + " — " + r.get("rowCount") + " link rows read"
                        + (r.get("fanOutCapped") instanceof Number c && c.longValue() > 0
                                ? ", " + c + " more left out by the fan-out cap" : "")
                        + (Boolean.TRUE.equals(r.get("truncated")) ? ", TRUNCATED at its budget of " + q.get("budget") : "")
                        + "." + approvalClause(e);
            }
            case "window" -> p.get("window") == null
                    ? "Cleared the time window: later expansions read the full time range."
                    : "Set the time window to " + InvestigationTime.describe((Map<String, Object>) p.get("window"))
                            + "; later expansions read inside it (earlier steps are unchanged).";
            case "exclude" -> Boolean.TRUE.equals(p.get("merged")) ? mergedExcludeLine(e, merged)
                    : "Excluded " + ids.size() + " entit" + (ids.size() == 1 ? "y" : "ies")
                    + " (reason: " + p.get("reason") + "): " + list(ids) + ".";
            case "hide" -> "Hid " + list(ids) + " from display (still traversed and counted).";
            case "keep" -> "Kept " + list(ids) + " (protected from later exclusion).";
            case "annotate" -> "Annotated " + annotated(ids, p) + gradeClause(p) + ": \"" + p.get("note") + "\"";
            case "excludeBy" -> "Excluded " + removed + " entit" + (removed != null && removed == 1 ? "y" : "ies")
                    + " on " + listClause(e) + " (reason: " + p.get("reason") + ").";
            case "resolve" -> resolveLine(e);
            case "threshold" -> thresholdLine(p);
            case "snapshot" -> snapshotLine(e, p);
            case "compare" -> compareLine(e, p);
            case "temporal" -> TemporalFindings.line(e, p);
            case "seedBy" -> {
                Map<String, Object> r = (Map<String, Object>) e.get("read");
                int n = strings(r.get("ids")).size();
                yield "Seeded " + n + " entit" + (n == 1 ? "y" : "ies") + " of type "
                        + ((Map<String, Object>) e.get("list")).get("entityType") + " from " + listClause(e)
                        + " — the values of " + r.get("dataset") + " whose key is a member.";
            }
            default -> "Applied " + e.get("op") + ".";
        };
    }

    /**
     * One line per merged exclude: each group it excluded as a whole, with its members (masked per key, D-U6), and -
     * from {@code outcome} ({@link InvestigationEvaluator.State#lastMerged}) - exactly which entities left, which
     * members {@code keep} protected, and which member keys no entity in the Working Set carried (plan §5.10: the line
     * never claims more than happened).
     */
    static String mergedExcludeLine(Map<String, Object> e, Map<String, List<String>> outcome) {
        List<String> parts = new ArrayList<>();
        for (Object o : e.get("groups") instanceof List<?> l ? l : List.of())
            if (o instanceof Map<?, ?> g) parts.add(g.get("id") + " (members: " + String.join(", ", strings(g.get("members"))) + ")");
        return "Excluded identity group" + (parts.size() == 1 ? " " : "s ") + String.join("; ", parts) + " as a whole (reason: "
                + ((Map<?, ?>) e.get("params")).get("reason") + "): " + mergedOutcomeClause(outcome);
    }

    /** {@code "left the Working Set: a, b; kept (protected): c; no entity in the Working Set for k — ..."}. */
    static String mergedOutcomeClause(Map<String, List<String>> outcome) {
        Map<String, List<String>> o = outcome == null ? Map.of() : outcome;
        List<String> left = o.getOrDefault("left", List.of()), kept = o.getOrDefault("kept", List.of()),
                unmatched = o.getOrDefault("unmatched", List.of()), ambiguous = o.getOrDefault("ambiguous", List.of());
        StringBuilder b = new StringBuilder(left.isEmpty() ? "no entity left the Working Set;"
                : "left the Working Set: " + String.join(", ", left) + ";");
        if (!kept.isEmpty()) b.append(" kept (protected), still in the Working Set: ").append(String.join(", ", kept)).append(";");
        if (!unmatched.isEmpty()) b.append(" no entity in the Working Set for ").append(String.join(", ", unmatched))
                .append(" (not removed, only blocked);");
        if (!ambiguous.isEmpty()) b.append(" not matched (ambiguous - more than one member type maps it to a member), still in "
                + "the Working Set: ").append(String.join(", ", ambiguous)).append(";");
        return b.append(" no member is admitted again.").toString();
    }

    /** {@code "Entity List `known-mules` (exclusion, 40 members, as of fact 17)"} — the sealed list a list op names. */
    static String listClause(Map<String, Object> e) {
        Map<?, ?> l = (Map<?, ?>) e.get("list");
        int n = strings(l.get("members")).size();
        return "Entity List `" + l.get("listId") + "` (" + l.get("purpose") + ", " + n + " member" + (n == 1 ? "" : "s")
                + ", as of fact " + l.get("atSeq") + ")";
    }

    /** What a {@code threshold} did, in words (the removed entities are in the step's delta, not the line). */
    static String thresholdLine(Map<String, Object> p) {
        String m = InvestigationEvaluator.measureOf(p);
        return "Removed every entity whose " + switch (m) {
            case "weightedDegree" -> "weighted degree (links touching it, each kind and direction once)";
            case "eventCount" -> "event count (the folded events on its links)";
            default -> "degree (distinct counterparties in the Working Set)";
        } + " is outside "
                + "[" + (p.get("min") == null ? "0" : p.get("min")) + ", " + (p.get("max") == null ? "unbounded" : p.get("max"))
                + ") - min inclusive, max exclusive; kept (protected) entities stay, removed ones are not re-admitted.";
    }

    /** A {@code compare}'s line: the two windows and what the sealed diff found (counts only; the lists are in the entry). */
    @SuppressWarnings("unchecked")
    static String compareLine(Map<String, Object> e, Map<String, Object> p) {
        Map<?, ?> c = e.get("comparison") instanceof Map<?, ?> c0 ? c0 : null;
        String line = "Compared " + windowText(p.get("windowA"), c == null ? null : c.get("windowA")) + " (A) with "
                + windowText(p.get("windowB"), c == null ? null : c.get("windowB")) + " (B)"
                + ("activity".equals(p.get("mode")) ? ", by event count" + (p.get("minAbsDelta") == null ? "" : " (changes of at least " + p.get("minAbsDelta") + " events)") : "");
        if (c == null) return line + ".";
        Map<?, ?> links = (Map<?, ?>) c.get("links"), ents = (Map<?, ?>) c.get("entities");
        return line + ": links only in A " + ((Map<?, ?>) links.get("onlyA")).get("count") + ", only in B "
                + ((Map<?, ?>) links.get("onlyB")).get("count") + ", in both " + ((Map<?, ?>) links.get("both")).get("count")
                + "; entities only in A " + ((Map<?, ?>) ents.get("onlyA")).get("count") + ", only in B "
                + ((Map<?, ?>) ents.get("onlyB")).get("count") + ", in both " + ((Map<?, ?>) ents.get("both")).get("count")
                + (c.get("activity") instanceof Map<?, ?> act
                        ? "; event count changed on " + ((Map<?, ?>) ((Map<?, ?>) act.get("links")).get("changed")).get("count") + " links and "
                        + ((Map<?, ?>) ((Map<?, ?>) act.get("entities")).get("changed")).get("count") + " entities" : "")
                + ". Sealed at this step (fingerprint " + c.get("fingerprint") + "); the Working Set does not change.";
    }

    /** One side of a {@code compare}: {@code inherit} reads as the window it resolved to (from the sealed diff when there is one). */
    @SuppressWarnings("unchecked")
    private static String windowText(Object param, Object resolved) {
        if (!"inherit".equals(param)) return InvestigationTime.describe((Map<String, Object>) param);
        return "the Investigation's window" + (resolved instanceof Map<?, ?> r ? " (" + InvestigationTime.describe((Map<String, Object>) r) + ")" : "");
    }

    /** Seal a {@code compare}'s diff over {@code state}: both windows read live NOW, then frozen into the entry (never re-read). */
    private static Map<String, Object> sealComparison(ApiContext api, HttpExchange ex, Inv inv, InvestigationEvaluator.State state,
                                                      Map<String, Object> params) {
        Map<String, Map<String, Object>> windows = new LinkedHashMap<>();
        List<String> inherited = new ArrayList<>();
        for (String w : List.of("a", "b")) {
            Object side = params.get("window" + w.toUpperCase());
            if ("inherit".equals(side)) {   // the Investigation's window at THIS position, frozen into the diff as the concrete window it was
                if (state.window == null)
                    throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'compare' window" + w.toUpperCase() + " is \"inherit\" but the "
                            + "Investigation has no window at this step (it reads the full time range) - set a window first or give the side explicit bounds");
                inherited.add(w.toUpperCase());
                windows.put(w, state.window);
            } else windows.put(w, castParams(side));
        }
        Map<String, Object> raw = WindowComparison.compare(api, ex, inv, state, windows, "activity".equals(params.get("mode")),
                params.get("minAbsDelta") instanceof Number n ? Long.valueOf(n.longValue()) : null);
        if (!inherited.isEmpty()) raw.put("inherited", inherited);
        return WindowComparison.seal(raw);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castParams(Object o) {
        return (Map<String, Object>) o;
    }

    /** A {@code snapshot} marker's line (the Working Set hash is on the entry). */
    static String snapshotLine(Map<String, Object> e, Map<String, Object> p) {
        return "Froze the Working Set here" + (p.get("label") == null ? "" : " as \"" + p.get("label") + "\"")
                + " (log position " + e.get("step") + "; later steps do not change it).";
    }

    /** {@code " graded B2 (source usually reliable, information probably true)"} — empty when ungraded (D-U9). */
    static String gradeClause(Map<String, Object> params) {
        return params.get("confidence") == null ? ""
                : " graded " + AdmiraltyGrade.describe(String.valueOf(params.get("confidence")));
    }

    /** {@code " Four-eyes: requested by a, approved by b."} — empty for an expand that needed no approval (D-U7). */
    static String approvalClause(Map<String, Object> e) {
        return e.get("approval") instanceof Map<?, ?> a
                ? " Four-eyes: requested by " + a.get("requestedBy") + ", approved by " + a.get("approvedBy") + "." : "";
    }

    private static String list(List<String> ids) {
        int shown = Math.min(10, ids.size());
        String head = String.join(", ", ids.subList(0, shown));
        return ids.size() > shown ? head + " and " + (ids.size() - shown) + " more" : head;
    }

    /** What an annotate named: its entities, then its links as {@code source → target (kind)} (D-U9). */
    private static String annotated(List<String> ids, Map<String, Object> p) {
        List<String> links = new ArrayList<>();
        if (p.get("links") instanceof List<?> ls)
            for (Object o : ls) {
                Map<?, ?> l = (Map<?, ?>) o;
                links.add(l.get("source") + " → " + l.get("target") + " (" + l.get("kind") + ")");
            }
        if (links.isEmpty()) return list(ids);
        String named = "link" + (links.size() == 1 ? " " : "s ") + list(links);
        return ids.isEmpty() ? named : list(ids) + " and " + named;
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Open one Investigation: 503 without a write root; 422 for an unsafe id; 404 when it does not exist, when
     * a Subject other than its owner asks (indistinguishable from absence), when its bound Dataset exists but
     * the caller can no longer view it (R3), or when the Enterprise PDP denies it (D-E7).
     *
     * <p>🔴 The D-E7 policy check lives HERE, the one gate every Investigation route opens through — log, ops,
     * undo, reorder, replay, the Working Set and the Dossier. It first shipped only on the Working Set route, so a
     * policy DENY blocked that one read while {@code /log} (which carries the sealed rows), {@code /replay} and
     * {@code /dossier} still served the same content and {@code /ops} still wrote.
     */
    public static Inv open(ApiContext api, HttpExchange ex, String id) throws IOException {
        return open(api, ex, id, Need.LEAD, false);
    }

    /** What the caller needs of the Investigation (D7-1; design §9). Every need still passes R3 and the Enterprise PDP. */
    private enum Need {
        /** Any member (lead, analyst, reviewer) reads. */
        READ,
        /** A lead: writes the main log and manages members; the only writer in D7-1. */
        LEAD,
        /** Decides a pending four-eyes expand (D-U7): a lead or reviewer; without a membership record, any Subject (legacy). */
        APPROVE
    }

    /**
     * The four-eyes APPROVER gate (D-U7): someone other than the owner must be able to reach a pending request to
     * decide it. Where the Investigation has a membership record only a lead or reviewer may; without one, as before,
     * any Subject holding {@code canApproveLinkExpansions}. R3 and the Enterprise PDP still judge the approver.
     */
    private static Inv openForApproval(ApiContext api, HttpExchange ex, String id) throws IOException {
        return open(api, ex, id, Need.APPROVE, false);
    }

    /**
     * LA-24 + D7-1: the READ gate — any MEMBER of the Investigation (lead, analyst, reviewer; with no membership
     * record the owner is the sole lead), OR a member of its linked Case ({@link InvestigationCaseRoutes#grants}, decided
     * live on every read). Only the read routes (log, Working Set, Dossier, measures, coverage (A9), Graph Runs, the Case
     * link itself) open through this; every write stays on {@link #open}, lead-only.
     * R3 and the Enterprise PDP below still judge a member, so they can only narrow the grant.
     */
    public static Inv openForRead(ApiContext api, HttpExchange ex, String id) throws IOException {
        return open(api, ex, id, Need.READ, true);
    }

    /** A read open to MEMBERS only (not Case members): the membership list. */
    static Inv openAsMember(ApiContext api, HttpExchange ex, String id) throws IOException {
        return open(api, ex, id, Need.READ, false);
    }

    private static Inv open(ApiContext api, HttpExchange ex, String id, Need need, boolean caseRead)
            throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "link analysis investigation");
        requireSafeId(id);
        InvestigationStore store = InvestigationStores.of(writeRoot);
        String raw = store.header(id).orElse(null);
        if (raw == null) throw new ApiException(404, ErrorCodes.NOT_FOUND, "no investigation '" + id + "'");
        @SuppressWarnings("unchecked") Map<String, Object> header = ApiContext.JSON.readValue(raw, Map.class);
        Optional<Subject> subject = ApiContext.subject(ex);
        Map<String, InvestigationMembers.Role> roles = InvestigationMemberStore.roles(store, id, header.get("owner"));
        ApiException roleRefusal = null;
        if (subject.isPresent()) {
            InvestigationMembers.Role role = roles.get(subject.get().id());
            boolean admitted = switch (need) {
                case LEAD -> role != null && role.canWriteMainLog();
                case APPROVE -> !InvestigationMemberStore.explicit(store, id) || role != null && role.canApprove();
                case READ -> role != null && role.canRead()
                        || caseRead && InvestigationCaseRoutes.grants(api, ex, store, id, subject.get());
            };
            // A MEMBER who may read but not do this already knows the Investigation exists, so say so (403); a
            // non-member (and a Case member, who is not a member) keeps the 404 that reads as absence.
            // The 403 waits until R3 and the PDP have had their say: a policy DENY hides the Investigation from a member too.
            if (!admitted && role != null && need != Need.READ)
                roleRefusal = new ApiException(403, ErrorCodes.PERMISSION_DENIED, "your role on investigation '" + id + "' is "
                        + role.wire() + " — " + (need == Need.LEAD ? "only a lead changes it" : "only a lead or reviewer decides this"));
            else if (!admitted) throw new ApiException(404, ErrorCodes.NOT_FOUND, "no investigation '" + id + "'");
        }
        String dataset = String.valueOf(header.get("dataset"));
        Optional<Map<String, Object>> ds = DatasetProviders.require().dataset(writeRoot, dataset);
        if (ds.isPresent() && !ComponentAccess.canView(ex, ds.get()))
            throw new ApiException(404, ErrorCodes.NOT_FOUND, "no dataset '" + dataset + "'");
        Inv inv = new Inv(store, writeRoot, id, header);
        if (!RowScope.visible(ex, "investigation", resource(inv, roles)))   // Enterprise PDP (D-E7); a DENY reads as absence
            throw new ApiException(404, ErrorCodes.NOT_FOUND, "no investigation '" + id + "'");
        if (roleRefusal != null) throw roleRefusal;
        return inv;
    }

    /**
     * What the Enterprise PDP judges: the Investigation's id, owner, bound Dataset, (for a fork) parent, and
     * {@code members} — the current role map {@code subject → lead|analyst|reviewer} (D7-1), so a policy can key on it.
     * The PDP can only NARROW access: it is consulted after the member rule, a DENY hides the Investigation even from
     * a lead, and an ALLOW or ABSTAIN grants nothing.
     */
    static Map<String, Object> resource(Inv inv, Map<String, InvestigationMembers.Role> roles) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", inv.id());
        r.put("owner", inv.header().get("owner"));
        r.put("dataset", inv.dataset());
        if (inv.header().get("parent") instanceof Map<?, ?> p) r.put("parent", p.get("id"));
        r.put("members", InvestigationMembers.asWire(roles));
        return r;
    }

    // ── LA-19 controls: purpose (D-U5), masking (D-U6), four-eyes (D-U7) ─────────────────────────────────────

    /** The stated purpose / legal basis (D-U5): required, non-blank, bounded. Recorded, not enforced. */
    private static String purpose(Map<String, Object> body) {
        String purpose = ApiContext.str(body, "purpose");
        if (purpose == null || purpose.isBlank())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "body must include 'purpose' — the stated purpose / legal basis of this "
                    + "Investigation (D-U5); it is recorded in the sealed header and shown in the Dossier, not enforced");
        if (purpose.length() > MAX_PURPOSE_LENGTH)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'purpose' is at most " + MAX_PURPOSE_LENGTH + " chars");
        return purpose.trim();
    }

    /** The response, masked per the Space's {@code maskingMode}, with a {@code masking} note saying what was (D-U6). */
    @SuppressWarnings("unchecked")
    static Object masked(Inv inv, Object out) throws IOException {
        EntityMasking mask = EntityMasking.of(inv, List.of());
        Object masked = LinkIds.stamp(mask.apply(out));   // D-U9: link ids minted from what the caller sees
        if (!(masked instanceof Map<?, ?> m)) return masked;
        Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) m);
        copy.put("masking", mask.describe());
        return copy;
    }

    /** An op body whose {@code ids} may carry pseudonyms this Investigation issued, each resolved to its entity. */
    private static Map<String, Object> resolvePseudonyms(Inv inv, Map<String, Object> body) throws IOException {
        if (!(body.get("ids") instanceof List<?> l)
                || l.stream().noneMatch(o -> o instanceof String v && v.startsWith(EntityMasking.TOKEN_PREFIX)))
            return body;
        Map<String, Object> out = new LinkedHashMap<>(body);
        out.put("ids", EntityMasking.of(inv, List.of()).resolve(strings(l)));
        return out;
    }

    /**
     * Whether an expand is SENSITIVE under the Space's four-eyes thresholds (D-U7): its row budget above
     * {@code fourEyesBudgetAbove}, or its {@code maxFanOut} above {@code fourEyesFanOutAbove} — an unbounded fan-out
     * exceeds any fan-out threshold. Null when it is not (or no threshold is set, the shipped default).
     */
    private static Map<String, Object> sensitivity(Inv inv, Map<String, Object> params) {
        LinkAnalysisSettings s = LinkAnalysisSettings.forRoot(inv.writeRoot());
        List<String> exceeded = new ArrayList<>();
        int budget = ((Number) params.get("budget")).intValue();
        if (s.fourEyesBudgetAbove() != null && budget > s.fourEyesBudgetAbove())
            exceeded.add("budget " + budget + " > " + s.fourEyesBudgetAbove());
        Object fanOut = params.get("maxFanOut");
        if (s.fourEyesFanOutAbove() != null && (!(fanOut instanceof Number n) || n.intValue() > s.fourEyesFanOutAbove()))
            exceeded.add(fanOut == null ? "maxFanOut unbounded (threshold " + s.fourEyesFanOutAbove() + ")"
                    : "maxFanOut " + fanOut + " > " + s.fourEyesFanOutAbove());
        if (exceeded.isEmpty()) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("exceeded", exceeded);
        m.put("budget", budget);
        m.put("maxFanOut", fanOut);
        m.put("fourEyesBudgetAbove", s.fourEyesBudgetAbove());
        m.put("fourEyesFanOutAbove", s.fourEyesFanOutAbove());
        return m;
    }

    /**
     * Hold a sensitive expand as a PENDING request instead of running it (D-U7). Nothing is read and nothing enters the
     * log: the request waits outside it ({@link SnapshotStore#writePending}) until a different Subject decides it. One
     * pending request per Investigation — a second is a 409, so an approver never decides against a queue whose order
     * they cannot see. The frontier is resolved when it RUNS, against the Working Set at approval time.
     */
    private static Map<String, Object> requestExpansion(HttpExchange ex, Inv inv, Map<String, Object> params,
                                                        Map<String, Object> sensitive,
                                                        InvestigationEvaluator.State before) throws IOException {
        List<String> existing = inv.store().listPending(inv.id());
        for (String raw : existing)
            if ("pending".equals(parse(raw).get("status")))
                throw new ApiException(409, ErrorCodes.CONFLICT, "a sensitive expand is already pending approval (" + parse(raw).get("id")
                        + ") — it must be approved or denied before another is requested");
        String rid = "p" + (existing.size() + 1);
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("id", rid);
        rec.put("investigationId", inv.id());
        rec.put("op", "expand");
        rec.put("params", params);
        rec.put("sensitivity", sensitive);
        rec.put("status", "pending");
        rec.put("requestedBy", ApiContext.actor(ex));
        rec.put("requestedAt", Instant.now().toString());
        inv.store().writePending(inv.id(), rid, canonical(rec));
        emit(ex, LinkEventTypes.LINK_EXPANSION_REQUESTED, "link.expansion.requested",
                "link.expansion.requested — " + inv.id() + " " + rid + " " + sensitive.get("exceeded"),
                b -> b.attr("investigationId", inv.id()).attr("requestId", rid).attr("budget", sensitive.get("budget"))
                        .attr("maxFanOut", sensitive.get("maxFanOut")).attr("exceeded", sensitive.get("exceeded")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "pending");
        out.put("pending", rec);
        out.put("workingSet", summary(before));
        return out;
    }

    /**
     * {@code POST /inv/investigations/{id}/pending/{rid}/approve | deny} — four-eyes (D-U7). Gates, in order:
     * {@code canApproveLinkExpansions} (the route) → an authenticated Subject 403 (without one two people cannot be
     * told apart, and the actor would be spoofable) → the Investigation, WITHOUT the owner check but with R3 and the
     * PDP ({@link #open(ApiContext, HttpExchange, String, boolean)}) → an unsafe request id 422 → no such request 404
     * → already decided 409 → the requester deciding their own request 403. Approve then RUNS the expand as the
     * requester's op, against the Working Set as it is now, and seals it with {@code approval{requestedBy,
     * approvedBy, ...}} in the log line; deny records who denied it (and an optional {@code reason}) and reads nothing.
     */
    @SuppressWarnings("unchecked")
    private Object decide(ApiContext api, HttpExchange ex, String id, String rid, boolean approve,
                          Map<String, Object> body) throws IOException {
        Optional<Subject> subject = ApiContext.subject(ex);
        if (subject.isEmpty())
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "four-eyes needs an authenticated Subject — without one, the requester and "
                    + "the approver cannot be told apart");
        Inv inv = openForApproval(api, ex, id);
        if (!SnapshotStore.SAFE_ID.matcher(rid).matches())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "request id must match " + SnapshotStore.SAFE_ID.pattern() + ", got '" + rid + "'");
        String reason = ApiContext.str(body, "reason");
        if (reason != null && reason.length() > 200) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'reason' is at most 200 chars");
        {   // no lock: the decision is claimed by a compare-and-set on the pending record BEFORE the append it authorises
            String raw = inv.store().pending(inv.id(), rid).orElse(null);
            if (raw == null) throw new ApiException(404, ErrorCodes.NOT_FOUND, "no pending request '" + rid + "' on investigation '" + id + "'");
            Map<String, Object> rec = parse(raw);
            if (!"pending".equals(rec.get("status")))
                throw new ApiException(409, ErrorCodes.CONFLICT, "request '" + rid + "' is already " + rec.get("status"));
            if (subject.get().id().equals(rec.get("requestedBy")))
                throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "four-eyes: '" + rec.get("requestedBy") + "' requested this expand and "
                        + "cannot " + (approve ? "approve" : "deny") + " it — a different person must");
            String by = ApiContext.actor(ex);
            String at = Instant.now().toString();
            rec.put("decidedBy", by);
            rec.put("decidedAt", at);
            if (!approve) {
                rec.put("status", "denied");
                if (reason != null) rec.put("reason", reason);
                if (!inv.store().replacePending(inv.id(), rid, raw, canonical(rec))) throw alreadyDecided(inv, rid);
                emit(ex, LinkEventTypes.LINK_EXPANSION_DENIED, "link.expansion.denied",
                        "link.expansion.denied — " + id + " " + rid + " by " + by,
                        b -> b.attr("investigationId", id).attr("requestId", rid)
                                .attr("requestedBy", rec.get("requestedBy")).attr("reason", reason));
                return masked(inv, Map.of("id", id, "pending", rec));
            }

            // CLAIM the decision first: the status moves pending -> approved in one compare-and-set, so a second decider (or a retry
            // of this one) finds it already moved and is refused, never double-approving. If the authorised act then fails, the claim
            // is handed back so the request can be decided again; once the append has landed it is never handed back.
            Map<String, Object> claimed = new LinkedHashMap<>(rec);
            claimed.put("status", "approved");
            String claimedJson = canonical(claimed);
            if (!inv.store().replacePending(inv.id(), rid, raw, claimedJson)) throw alreadyDecided(inv, rid);
            if ("promote".equals(rec.get("kind"))) {   // D7-5: a held promote of a Draft carrying a sensitive expand
                Map<String, Object> approval = new LinkedHashMap<>();
                approval.put("request", rid);
                approval.put("requestedBy", rec.get("requestedBy"));
                approval.put("requestedAt", rec.get("requestedAt"));
                approval.put("approvedBy", by);
                approval.put("approvedAt", at);
                Map<String, Object> out;
                try {
                    out = DraftPromote.executeApproved(api, ex, inv, rec, approval);
                } catch (IOException | RuntimeException failed) {
                    inv.store().replacePending(inv.id(), rid, claimedJson, raw);
                    throw failed;
                }
                rec.put("status", "approved");
                rec.put("step", out.get("toStep"));
                inv.store().writePending(inv.id(), rid, canonical(rec));
                out.put("approval", approval);
                return masked(inv, out);
            }
            Map<String, Object> params = (Map<String, Object>) rec.get("params");
            Map<String, Object> approval = new LinkedHashMap<>();
            approval.put("request", rid);
            approval.put("requestedBy", rec.get("requestedBy"));
            approval.put("requestedAt", rec.get("requestedAt"));
            approval.put("approvedBy", by);
            approval.put("approvedAt", at);
            Map<String, Object> out;
            try {
                out = untilWon(() -> {
            List<Map<String, Object>> log = readLog(inv);
            InvestigationEvaluator.State before = stateBefore(inv, log);
            List<String> named = strings(params.get("ids"));
            List<String> frontier = new ArrayList<>();
            for (String n : named.isEmpty() ? before.entities.keySet() : sorted(named))
                if (before.entities.containsKey(n)) frontier.add(n);
            if (frontier.isEmpty())
                throw new ApiException(409, ErrorCodes.CONFLICT, "nothing left to expand — the entities this request names have left the "
                        + "Working Set since it was made; deny it instead");
            if (frontier.size() > MAX_FRONTIER)
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "an expand frontier is capped at " + MAX_FRONTIER + " entities");
            Map<String, Object> entry = entry(log.size() + 1, "op", ex);
            entry.put("author", rec.get("requestedBy"));   // the requester's op; the approver is recorded beside it
            entry.put("op", "expand");
            entry.put("params", params);
            entry.put("approval", approval);
            entry.put("read", read(api, ex, inv, expandRung(api, ex, inv, params, frontier, before, "")));
            return (Map<String, Object>) commit(ex, inv, log, entry, before);
                });
            } catch (IOException | RuntimeException failed) {
                inv.store().replacePending(inv.id(), rid, claimedJson, raw);   // nothing was appended: hand the claim back
                throw failed;
            }
            rec.put("status", "approved");
            rec.put("step", out.get("step"));
            inv.store().writePending(inv.id(), rid, canonical(rec));
            emit(ex, LinkEventTypes.LINK_EXPANSION_APPROVED, "link.expansion.approved",
                    "link.expansion.approved — " + id + " " + rid + " by " + by + " → step " + out.get("step"),
                    b -> b.attr("investigationId", id).attr("requestId", rid)
                            .attr("requestedBy", rec.get("requestedBy")).attr("step", out.get("step")));
            out.put("approval", approval);
            return masked(inv, out);
        }
    }

    /**
     * {@code POST /inv/investigations/{id}/reveal} — body {@code {tokens: ["masked:…", …]}} (D-U6). Per entity: each
     * pseudonym this Investigation issued is answered with the entity id behind it; anything else is listed under
     * {@code unknown}, never guessed. Gates: {@code canRevealLinkEntities} (the route) → the Investigation, owner-only /
     * R3 / PDP ({@link #open}) → a missing, empty or over-long token list 422. Audited as
     * {@code LINK_ENTITY_REVEALED} with the TOKENS revealed — never the raw ids, so the trail does not re-leak them.
     */
    private Object reveal(ApiContext api, HttpExchange ex, String id, Map<String, Object> body) throws IOException {
        Inv inv = open(api, ex, id);
        if (!(body.get("tokens") instanceof List<?> raw) || raw.isEmpty() || raw.size() > MAX_REVEAL)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "body must include 'tokens', a list of 1.." + MAX_REVEAL + " masked ids to reveal");
        EntityMasking mask = EntityMasking.of(inv, List.of());
        List<Map<String, Object>> revealed = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String token : new LinkedHashSet<>(strings(raw))) {
            String value = mask.reveal(token);
            if (value == null) unknown.add(token);
            else revealed.add(Map.of("token", token, "id", value));
        }
        List<Object> tokens = revealed.stream().map(r -> r.get("token")).toList();
        emit(ex, LinkEventTypes.LINK_ENTITY_REVEALED, "link.entity.revealed",
                "link.entity.revealed — " + id + " " + revealed.size() + " entit" + (revealed.size() == 1 ? "y" : "ies"),
                b -> b.attr("investigationId", id).attr("tokens", tokens).attr("count", revealed.size()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("revealed", revealed);
        out.put("unknown", unknown);
        out.put("masking", mask.describe());
        return out;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parse(String raw) throws IOException {
        return ApiContext.JSON.readValue(raw, LinkedHashMap.class);
    }

    private static List<String> relationColumns(String datasetId, String relationSql) {
        try {
            DatasetProvider.Result r = DatasetProviders.require().run(new DatasetProvider.Request(
                    datasetId, relationSql, "SELECT * FROM " + SqlIdent.q(datasetId), 0, 0, List.of(), List.of()));
            return r.columns().stream().map(DatasetProvider.Column::name).toList();
        } catch (Exception unusable) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "cannot read the columns of dataset '" + datasetId + "': "
                    + unusable.getMessage());
        }
    }

    /** A decide that lost the compare-and-set: another decider moved the request first. */
    private static ApiException alreadyDecided(Inv inv, String rid) throws IOException {
        String raw = inv.store().pending(inv.id(), rid).orElse(null);
        String now = raw == null ? "decided" : String.valueOf(parse(raw).get("status"));
        return new ApiException(409, ErrorCodes.CONFLICT, "request '" + rid + "' is already " + now);
    }

    /** The state of {@code log} before the next step: the main log folds; a Draft resumes from its checkpoint (D7-4). */
    private static InvestigationEvaluator.State stateBefore(Inv inv, List<Map<String, Object>> log) throws IOException {
        return inv.draft() == null ? evaluate(log, -1, null) : com.gamma.la.core.DraftCheckpoints.stateOf(inv.cacheKey(), inv.logToken(), log);
    }

    private static List<Map<String, Object>> readLog(Inv inv) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String line : inv.logLines()) {
            @SuppressWarnings("unchecked") Map<String, Object> m = ApiContext.JSON.readValue(line, Map.class);
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> entry(int step, String kind, HttpExchange ex) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("step", step);
        e.put("kind", kind);
        e.put("author", ApiContext.actor(ex));
        e.put("at", Instant.now().toString());
        return e;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> roundTrip(Map<String, Object> entry) throws IOException {
        return ApiContext.JSON.readValue(canonical(entry), LinkedHashMap.class);
    }

    static Map<String, Object> setDoc(int step, InvestigationEvaluator.State s) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("step", step);
        doc.put("hash", s.hash());
        doc.put("workingSet", s.toMap());
        return doc;
    }

    private static Map<String, Object> summary(InvestigationEvaluator.State s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entities", s.entities.size());
        m.put("links", s.links.size());
        m.put("excluded", s.excluded.size());
        m.put("hash", s.hash());
        return m;
    }

    private static List<String> sorted(List<String> ids) {
        List<String> out = new ArrayList<>(ids);
        Collections.sort(out);
        return out;
    }

    private static void requireSafeId(String id) {
        if (id == null || !SnapshotStore.SAFE_ID.matcher(id).matches())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "investigation id must match " + SnapshotStore.SAFE_ID.pattern()
                    + ", got '" + id + "'");
    }

    /** The store refuses an id that would escape it; the route answers 403, as its own jail check did before the port. */
    private static ApiException escapes() {
        return new ApiException(403, ErrorCodes.PATH_JAIL_VIOLATION, "investigation id escapes the investigation store");
    }

    private static boolean createChecked(InvestigationStore store, String id, String headerJson) throws IOException {
        try {
            return store.create(id, headerJson);
        } catch (IllegalArgumentException escape) {
            throw escapes();
        }
    }

    private static boolean forkChecked(InvestigationStore store, String id, String headerJson, List<String> lines, List<String> sets)
            throws IOException {
        try {
            return store.createFork(id, headerJson, lines, sets);
        } catch (IllegalArgumentException escape) {
            throw escapes();
        }
    }

    private static String ident(Map<String, Object> body, String key, boolean required) {
        String v = ApiContext.str(body, key);
        if (v == null) {
            if (required) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "body must include '" + key + "'");
            return null;
        }
        if (!SAFE_IDENT.matcher(v).matches())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "unsafe column identifier '" + v + "' for " + key);
        return v;
    }

    /** How many times an op or undo re-reads, re-evaluates and retries after another writer moved the log under it. */
    static final int MAX_APPEND_ATTEMPTS = 20;

    /** One evaluate-then-append over the CURRENT log; run again from a fresh read whenever it loses the race. */
    @FunctionalInterface
    private interface Attempt<T> {
        T run() throws IOException;
    }

    /**
     * Run {@code attempt}; if its append finds the log moved ({@link InvestigationVersionConflictException}), run it again from
     * a fresh read, so concurrent writers each land in turn exactly as they did under the old per-log monitor. A writer that
     * keeps losing is refused {@code 409 CONFLICT_STALE_VERSION}.
     */
    private static <T> T untilWon(Attempt<T> attempt) throws IOException {
        for (int n = 1; ; n++) {
            try {
                return attempt.run();
            } catch (InvestigationVersionConflictException lost) {
                if (n >= MAX_APPEND_ATTEMPTS) throw new ApiException(409, ErrorCodes.CONFLICT_STALE_VERSION, lost.getMessage());
            }
        }
    }

    /** Best-effort audit (LA-04 pattern), emitted only AFTER the act succeeded so the trail never over-claims. */
    static void emit(HttpExchange ex, String type, String action, String message,
                             UnaryOperator<Event.Builder> attrs) {
        try {
            Event.Builder b = Event.builder(type).source("inv").message(message)
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action(action).actionCategory("analysis");
            EventSink.current().emit(attrs.apply(b));
        } catch (RuntimeException ignored) {
            // best effort — the step is already sealed and that is what matters
        }
    }
}
