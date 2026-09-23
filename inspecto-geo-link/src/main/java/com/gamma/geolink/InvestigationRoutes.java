package com.gamma.geolink;

import com.gamma.control.ApiContext;
import com.gamma.control.ApiException;
import com.gamma.control.ComponentAccess;
import com.gamma.control.RowScope;
import com.gamma.control.RouteModule;
import com.gamma.control.Subject;
import com.gamma.control.WriteGates;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import com.gamma.query.DatasetRelation;
import com.gamma.query.QueryExecutor;
import com.gamma.query.ResultSetDescriptor;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import static com.gamma.geolink.InvestigationEvaluator.canonical;
import static com.gamma.geolink.InvestigationEvaluator.evaluate;
import static com.gamma.geolink.InvestigationEvaluator.strings;

/**
 * The <b>Investigation</b> object (LA-10, {@code docs/superpower/link-analysis-backlog-plan.md} §2, §5.5): an
 * ordered, append-only op log over one Dataset + projection mapping, and the <b>Working Set</b> it evaluates to.
 *
 * <ul>
 *   <li>{@code POST /inv/investigations} — create, bound to {@code {dataset, sourceCol, targetCol, linkKindCol?,
 *       timeCol?, timeColZone?}}.</li>
 *   <li>{@code POST /inv/investigations/{id}/ops} — append one op ({@code seed · expand · exclude · hide · keep ·
 *       window}); answers the Working Set DELTA and {@code truncated}. An {@code expand} is one hop-ladder rung
 *       (LA-13, plan §2.4) — see {@link #expandParams}.</li>
 *   <li>{@code POST /inv/investigations/{id}/undo} — real undo: a log edit that reverts the latest op.</li>
 *   <li>{@code POST /inv/investigations/{id}/reorder} — re-ordering FORKS (D-E4): a new Investigation with explicit
 *       parent lineage; the original log, its Working Sets and any Artifact anchored to them are untouched.</li>
 *   <li>{@code POST /inv/investigations/{id}/replay} — full evaluation from the sealed log, with the equivalence
 *       check against the hashes recorded at append time, and — with {@code reread} — a drift check against
 *       current data.</li>
 *   <li>{@code GET /inv/investigations/{id}/log} — the ordered log, each step rendered as a plain-language line.</li>
 * </ul>
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
 * <p><b>Access.</b> Owner-only: the creating Subject owns it, and anyone else gets a 404 indistinguishable from
 * absence (the R3 answer). ⚠ Grounded, not assumed: the only sharing model in the control plane is
 * {@code ComponentAccess}'s envelope on REGISTRY components, and snapshots — the nearest comparable object, in
 * the same store — have no owner or sharing model at all, so there is nothing to reuse and owner-only is the
 * fail-closed choice (consistent with D-E7's Professional answer). With no Subject attached nothing is enforced,
 * as everywhere else in the control plane. Every route additionally applies the R3 Dataset gate: a bound
 * Dataset the caller can no longer view reads as absent, and every Dataset READ goes through the same
 * {@code ComponentAccess.canView} check {@code InvRoutes.relationFor} applies.
 *
 * <p>Mutating routes are gated on {@code canManageIncidents}, as {@code POST /inv/snapshots} is: the log is
 * evidence and belongs to Case work. {@code /replay} persists nothing and takes the read-shaped exemption.
 */
public final class InvestigationRoutes implements RouteModule {

    private static final Pattern SAFE_IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    /** The six ops this slice evaluates (LA-10's five + LA-13's {@code window}). */
    private static final Set<String> SHIPPED = Set.of("seed", "expand", "exclude", "hide", "keep", "window");
    /** The rest of the closed vocabulary (plan §2.2): named so they refuse as "not yet", never as "unknown". */
    private static final Set<String> DEFERRED = Set.of("seedBy", "excludeBy", "threshold", "annotate", "snapshot");
    /** An {@code expand} rung's traversal direction (plan §2.4). */
    private static final List<String> DIRECTIONS = List.of("either", "out", "in", "reciprocal");
    private static final int MAX_IDS = 1_000;
    private static final int MAX_ID_LENGTH = 512;
    private static final int MAX_FRONTIER = 1_000;
    private static final int MAX_LINK_KINDS = 100;
    private static final int DEFAULT_EXPAND_BUDGET = 2_000;
    private static final int MAX_EXPAND_BUDGET = 20_000;
    private static final int LOG_DEFAULT = 500;
    private static final int LOG_MAX = 5_000;

    /** Writers to one Investigation (or to the investigations root) are serialised within this JVM. */
    private static final ConcurrentHashMap<Path, Object> LOCKS = new ConcurrentHashMap<>();

    @Override
    public void register(ApiContext api) {
        // ⚠ String LITERALS on purpose — CapabilityManifestTest's scanner matches only a literal argument.
        api.post("/inv/investigations", ApiContext.withCapability("canManageIncidents",
                (e, m) -> create(api, e, api.body(e))));
        api.post("/inv/investigations/([^/]+)/ops", ApiContext.withCapability("canManageIncidents",
                (e, m) -> appendOp(api, e, m.group(1), api.body(e))));
        api.post("/inv/investigations/([^/]+)/undo", ApiContext.withCapability("canManageIncidents",
                (e, m) -> undo(api, e, m.group(1))));
        api.post("/inv/investigations/([^/]+)/reorder", ApiContext.withCapability("canManageIncidents",
                (e, m) -> reorder(api, e, m.group(1), api.body(e))));
        api.post("/inv/investigations/([^/]+)/replay", (e, m) -> replay(api, e, m.group(1), api.body(e)));
        api.get("/inv/investigations/([^/]+)/log", (e, m) -> log(api, e, m.group(1)));
    }

    /** One opened Investigation: its store, write root and parsed header. Package-private for {@link WorkingSetRoutes}. */
    record Inv(SnapshotStore store, Path writeRoot, String id, Map<String, Object> header) {
        String dataset() { return String.valueOf(header.get("dataset")); }
        Path dir() { return store.investigationDir(id); }
    }

    // ── routes ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * {@code POST /inv/investigations} — body {@code {id?, title?, dataset, sourceCol, targetCol, linkKindCol?,
     * timeCol?, timeColZone?}}. {@code timeCol} (LA-13) binds the event time every window reads; see
     * {@link InvestigationTime} for the timezone contract {@code timeColZone} is part of.
     * Gates: write root 503 → a missing/unsafe field 422 → unknown or not-viewable Dataset 404 → a column the
     * relation lacks, a time column that is not a timestamp, or a bad zone 422 → id escaping the store 403 → id
     * taken 409 → write the header CREATE_NEW.
     */
    private Object create(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "link analysis investigation create");
        String given = ApiContext.str(body, "id");
        String id = given != null ? given : "inv-" + UUID.randomUUID();
        requireSafeId(id);
        String dataset = ApiContext.str(body, "dataset");
        if (dataset == null) throw new ApiException(422, "body must include 'dataset'");
        String sourceCol = ident(body, "sourceCol", true);
        String targetCol = ident(body, "targetCol", true);
        String kindCol = ident(body, "linkKindCol", false);
        String timeCol = ident(body, "timeCol", false);

        String relationSql = InvRoutes.relationFor(api, ex, writeRoot, dataset);
        List<String> columns = relationColumns(dataset, relationSql);
        for (String col : java.util.Arrays.asList(sourceCol, targetCol, kindCol, timeCol))
            if (col != null && columns.stream().noneMatch(col::equalsIgnoreCase))
                throw new ApiException(422, "unknown column '" + col + "' — not a column of dataset '" + dataset + "'");
        String timeColZone = timeColZone(dataset, relationSql, timeCol, ApiContext.str(body, "timeColZone"));

        SnapshotStore store = new SnapshotStore(writeRoot);
        jail(store, id);
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("id", id);
        header.put("title", ApiContext.str(body, "title"));
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
        synchronized (lock(store.directory().resolve("investigations"))) {
            if (!store.createInvestigation(id, canonical(header)))
                throw new ApiException(409, "investigation '" + id + "' already exists");
        }
        emit(ex, EventType.LINK_INVESTIGATION_CREATED, "link.investigation.created",
                "link.investigation.created — " + id + " over " + dataset,
                b -> b.attr("investigationId", id).attr("dataset", dataset));
        return header;
    }

    /** {@code POST /inv/investigations/{id}/ops} — body {@code {op, ...params}}. See the class note for gates. */
    private Object appendOp(ApiContext api, HttpExchange ex, String id, Map<String, Object> body) throws IOException {
        Inv inv = open(api, ex, id);
        String op = ApiContext.str(body, "op");
        if (op == null) throw new ApiException(422, "body must include 'op'");
        if (DEFERRED.contains(op))
            throw new ApiException(422, "op '" + op + "' is in the closed vocabulary but not implemented yet (LA-10 "
                    + "and LA-13 ship seed, expand, exclude, hide, keep, window)");
        if (!SHIPPED.contains(op))
            throw new ApiException(422, "op '" + op + "' is not in the closed op vocabulary");
        Map<String, Object> params = params(op, body);
        requireBindings(inv.header(), op, params, "");

        synchronized (lock(inv.dir())) {
            List<Map<String, Object>> log = readLog(inv);
            InvestigationEvaluator.State before = evaluate(log, -1, null);
            List<String> ids = strings(params.get("ids"));
            if (op.equals("hide") || op.equals("keep") || (op.equals("expand") && !ids.isEmpty()))
                for (String i : ids)
                    if (!before.entities.containsKey(i))
                        throw new ApiException(422, "'" + i + "' is not in the Working Set");

            int step = log.size() + 1;
            Map<String, Object> entry = entry(step, "op", ex);
            entry.put("op", op);
            entry.put("params", params);
            if (op.equals("expand")) {
                List<String> frontier = ids.isEmpty() ? new ArrayList<>(before.entities.keySet()) : sorted(ids);
                if (frontier.isEmpty()) throw new ApiException(422, "nothing to expand — the Working Set is empty");
                if (frontier.size() > MAX_FRONTIER)
                    throw new ApiException(422, "an expand frontier is capped at " + MAX_FRONTIER
                            + " entities; name them with 'ids'");
                entry.put("read", read(api, ex, inv, rung(params, frontier, before)));
            }
            return commit(ex, inv, log, entry, before);
        }
    }

    /** {@code POST /inv/investigations/{id}/undo} — revert the latest effective op; 409 when there is none. */
    private Object undo(ApiContext api, HttpExchange ex, String id) throws IOException {
        Inv inv = open(api, ex, id);
        synchronized (lock(inv.dir())) {
            List<Map<String, Object>> log = readLog(inv);
            int target = InvestigationEvaluator.undoTarget(log);
            if (target < 0) throw new ApiException(409, "nothing to undo");
            Map<String, Object> entry = entry(log.size() + 1, "undo", ex);
            entry.put("undoes", target);
            return commit(ex, inv, log, entry, evaluate(log, -1, null));
        }
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
            throw new ApiException(422, "body must include 'order', a list of step numbers");
        List<Integer> order = new ArrayList<>();
        for (Object o : rawOrder) {
            if (!(o instanceof Number n)) throw new ApiException(422, "'order' entries must be step numbers");
            order.add(n.intValue());
        }
        if (order.size() != effective.size() || !new HashSet<>(order).equals(effective.keySet()))
            throw new ApiException(422, "'order' must be a permutation of the effective op steps "
                    + effective.keySet());

        String forkId = ApiContext.str(body, "id");
        if (forkId == null) forkId = "inv-" + UUID.randomUUID();
        requireSafeId(forkId);
        jail(parent.store(), forkId);
        if (parent.store().readInvestigation(forkId) != null)
            throw new ApiException(409, "investigation '" + forkId + "' already exists");

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
            if ("expand".equals(orig.get("op"))) {
                @SuppressWarnings("unchecked") Map<String, Object> p = (Map<String, Object>) orig.get("params");
                List<String> named = strings(p.get("ids"));
                List<String> frontier = new ArrayList<>();
                for (String n : named.isEmpty() ? state.entities.keySet() : sorted(named))
                    if (state.entities.containsKey(n)) frontier.add(n);
                e.put("read", read(api, ex, parent, rung(p, frontier, state)));
            }
            e = roundTrip(e);
            InvestigationEvaluator.apply(state, e);
            e.put("workingSetHash", state.hash());
            lines.add(canonical(e));
            sets.add(canonical(setDoc(step, state)));
        }
        synchronized (lock(parent.store().directory().resolve("investigations"))) {
            if (!parent.store().createFork(forkId, canonical(header), lines, sets))
                throw new ApiException(409, "investigation '" + forkId + "' already exists");
        }
        String fid = forkId;
        emit(ex, EventType.LINK_INVESTIGATION_FORKED, "link.investigation.forked",
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
                throw new ApiException(422, "unknown column '" + col + "' — not a column of dataset '" + dataset + "'");
        String timeCol = ident(header, "timeCol", false);
        String timeColZone = timeColZone(dataset, relationSql, timeCol, ApiContext.str(header, "timeColZone"));
        SnapshotStore store = new SnapshotStore(writeRoot);
        jail(store, id);
        if (store.readInvestigation(id) != null) throw new ApiException(409, "investigation '" + id + "' already exists");

        Map<String, Object> h = new LinkedHashMap<>(header);
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
            if (DEFERRED.contains(op))
                throw new ApiException(422, "op '" + op + "' is in the closed vocabulary but not implemented yet");
            if (!SHIPPED.contains(op)) throw new ApiException(422, "op '" + op + "' is not in the closed op vocabulary");
            Map<String, Object> e = entry(++step, "op", ex);
            e.put("op", op);
            Map<String, Object> params = params(op, body);
            requireBindings(h, op, params, "template step " + step + ": ");
            e.put("params", params);
            e.put("derivedFrom", body.get("derivedFrom"));
            if (op.equals("expand")) {
                List<String> frontier = new ArrayList<>(state.entities.keySet());
                if (frontier.isEmpty()) throw new ApiException(422, "template step " + step + " expands an empty Working Set");
                if (frontier.size() > MAX_FRONTIER)
                    throw new ApiException(422, "template step " + step + " would expand " + frontier.size()
                            + " entities; an expand frontier is capped at " + MAX_FRONTIER);
                e.put("read", read(api, ex, inv, rung(params, frontier, state)));
            }
            e = roundTrip(e);
            InvestigationEvaluator.apply(state, e);
            e.put("workingSetHash", state.hash());
            lines.add(canonical(e));
            sets.add(canonical(setDoc(step, state)));
        }
        synchronized (lock(store.directory().resolve("investigations"))) {
            if (!store.createFork(id, canonical(h), lines, sets))
                throw new ApiException(409, "investigation '" + id + "' already exists");
        }
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
        Inv inv = open(api, ex, id);
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
                drift.add(row);
            }
        }
        boolean div = diverged;
        emit(ex, EventType.LINK_INVESTIGATION_REPLAYED, "link.investigation.replayed",
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
        return out;
    }

    /** {@code GET /inv/investigations/{id}/log?limit=n} — bounded; the TRUE total ships beside it. */
    private Object log(ApiContext api, HttpExchange ex, String id) throws IOException {
        Inv inv = open(api, ex, id);
        int limit = LOG_DEFAULT;
        String raw = ApiContext.query(ex, "limit");
        if (raw != null && !raw.isBlank()) {
            try {
                limit = Integer.parseInt(raw.trim());
            } catch (NumberFormatException e) {
                throw new ApiException(422, "limit must be an integer, got '" + raw + "'");
            }
        }
        limit = Math.min(Math.max(limit, 1), LOG_MAX);
        List<Map<String, Object>> log = readLog(inv);
        Map<Integer, Integer> undoneBy = new LinkedHashMap<>();
        for (Map<String, Object> e : log)
            if ("undo".equals(e.get("kind")))
                undoneBy.put(((Number) e.get("undoes")).intValue(), ((Number) e.get("step")).intValue());
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map<String, Object> e : log.subList(0, Math.min(limit, log.size()))) {
            int step = ((Number) e.get("step")).intValue();
            Map<String, Object> out = new LinkedHashMap<>(e);
            if (e.get("read") instanceof Map<?, ?> r) {
                Map<String, Object> summary = new LinkedHashMap<>();   // the sealed rows stay out of the log view
                for (String k : List.of("dataset", "readAt", "rowCount", "truncated", "fanOutCapped", "fingerprint"))
                    summary.put(k, r.get(k));
                out.put("read", summary);
            }
            out.put("undoneBy", undoneBy.get(step));
            out.put("text", step + ". " + render(e) + (undoneBy.containsKey(step)
                    ? " (undone by step " + undoneBy.get(step) + ")" : ""));
            entries.add(out);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("header", inv.header());
        out.put("entries", entries);
        out.put("total", log.size());
        out.put("truncated", log.size() > entries.size());
        return out;
    }

    // ── steps ──────────────────────────────────────────────────────────────────────────────────────────

    /** Append {@code entry} after {@code log}, seal its Working Set, audit, and answer the delta. */
    private Object commit(HttpExchange ex, Inv inv, List<Map<String, Object>> log, Map<String, Object> entry,
                          InvestigationEvaluator.State before) throws IOException {
        // Round-trip BEFORE evaluating, so the append-time evaluation reads exactly what replay will read back.
        Map<String, Object> e = roundTrip(entry);
        List<Map<String, Object>> next = new ArrayList<>(log);
        next.add(e);
        InvestigationEvaluator.State after = evaluate(next, -1, null);
        e.put("workingSetHash", after.hash());
        int step = ((Number) e.get("step")).intValue();
        inv.store().appendStep(inv.id(), step, canonical(e), canonical(setDoc(step, after)));

        String op = "undo".equals(e.get("kind")) ? "undo" : String.valueOf(e.get("op"));
        @SuppressWarnings("unchecked") Map<String, Object> read = (Map<String, Object>) e.get("read");
        boolean truncated = read != null && Boolean.TRUE.equals(read.get("truncated"));
        emit(ex, EventType.LINK_INVESTIGATION_STEPPED, "link.investigation.stepped",
                "link.investigation.stepped — " + inv.id() + " step " + step + " " + op,
                b -> {
                    b.attr("investigationId", inv.id()).attr("step", step).attr("op", op);
                    if (read != null) b.attr("dataset", read.get("dataset")).attr("rows", read.get("rowCount"))
                            .attr("truncated", truncated).attr("fingerprint", read.get("fingerprint"));
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
        if (read != null) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("rowCount", read.get("rowCount"));
            r.put("fingerprint", read.get("fingerprint"));
            r.put("readAt", read.get("readAt"));
            r.put("fanOutCapped", read.get("fanOutCapped"));
            r.put("rung", read.get("query"));   // the rung as READ: window resolved, frontier and exclusions included
            out.put("read", r);
        }
        if (after.window != null || "window".equals(op)) out.put("window", after.window);
        out.put("workingSet", summary(after));
        return out;
    }

    /**
     * The rung an {@code expand} reads with (plan §2.4): the op's validated params, resolved against the Working Set
     * it runs on — its frontier, its exclusions and, for {@code window: "inherit"}, the window the latest
     * {@code window} op set. This resolved map is recorded as {@code read.query}, so {@code reread} re-runs exactly
     * the statement that was sealed, whatever window ops come later.
     */
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
        String dataset = inv.dataset();
        String relationSql = InvRoutes.relationFor(api, ex, inv.writeRoot(), dataset);   // R3 gate on EVERY read
        List<String> frontier = strings(query.get("frontier"));
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
        if (!frontier.isEmpty()) {
            Map<String, Object> h = inv.header();
            String src = SqlIdent.q(String.valueOf(h.get("sourceCol")));
            String tgt = SqlIdent.q(String.valueOf(h.get("targetCol")));
            String kind = h.get("linkKindCol") == null ? null : SqlIdent.q(String.valueOf(h.get("linkKindCol")));
            List<String> binds = new ArrayList<>();
            StringBuilder sql = new StringBuilder("WITH fr(id) AS (VALUES ")
                    .append(String.join(",", Collections.nCopies(frontier.size(), "(?)"))).append(")");
            binds.addAll(frontier);
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
            sql.append(", elig AS (SELECT c.*, ROW_NUMBER() OVER (PARTITION BY c.anchor ORDER BY c.cnt DESC, c.s, c.t, "
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
                QueryExecutor.Result r = QueryExecutor.run(new QueryExecutor.Request(
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
                throw new ApiException(422, "expand over dataset '" + dataset + "' failed: " + e.getMessage());
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
        read.put("fingerprint", InvestigationEvaluator.sha256(canonical(rows)));
        return read;
    }

    /**
     * The bindings an op needs from the Investigation's header, checked at append (and at template
     * instantiation): {@code linkKinds} needs a link-kind column; a window, or {@code minDistinctDays}, needs a
     * time column.
     */
    private static void requireBindings(Map<String, Object> header, String op, Map<String, Object> p, String where) {
        boolean timed = op.equals("window")
                || (op.equals("expand") && (p.get("window") instanceof Map<?, ?> || p.get("minDistinctDays") != null));
        if (timed && header.get("timeCol") == null)
            throw new ApiException(422, where + "this Investigation has no time column — create it with 'timeCol' "
                    + "to use a window or minDistinctDays");
        if (op.equals("expand") && p.get("linkKinds") != null && header.get("linkKindCol") == null)
            throw new ApiException(422, where + "'linkKinds' needs a link-kind column — this Investigation has none");
    }

    /** Validate and normalise one op's parameters (422 on anything malformed). */
    private static Map<String, Object> params(String op, Map<String, Object> body) {
        Map<String, Object> p = new LinkedHashMap<>();
        if (op.equals("window")) {   // no ids: an intensional op over time, not over entities
            Object w = body.get("window");
            if (w == null) throw new ApiException(422, "'window' requires 'window': an object, or \"full\" to clear it");
            p.put("window", "full".equals(w) ? null : InvestigationTime.window(w, "window"));
            return p;
        }
        Object rawIds = body.get("ids");
        if (rawIds != null && !(rawIds instanceof List<?>)) throw new ApiException(422, "'ids' must be a list");
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (rawIds instanceof List<?> l) for (Object o : l) {
            String v = o == null ? "" : String.valueOf(o);
            if (v.isBlank() || v.length() > MAX_ID_LENGTH)
                throw new ApiException(422, "every id must be a non-blank string of at most " + MAX_ID_LENGTH + " chars");
            ids.add(v);
        }
        if (ids.size() > MAX_IDS) throw new ApiException(422, "at most " + MAX_IDS + " ids per op");
        if (!op.equals("expand") && ids.isEmpty()) throw new ApiException(422, "op '" + op + "' requires 'ids'");
        p.put("ids", new ArrayList<>(ids));
        switch (op) {
            case "seed" -> {
                String type = ApiContext.str(body, "entityType");
                if (type != null && type.length() > 64) throw new ApiException(422, "'entityType' is at most 64 chars");
                p.put("entityType", type);
            }
            case "expand" -> expandParams(body, p);
            case "exclude" -> {
                String reason = ApiContext.str(body, "reason");
                if (reason == null) throw new ApiException(422, "'exclude' requires a 'reason' — an exclusion "
                        + "without one cannot be challenged");
                if (reason.length() > 200) throw new ApiException(422, "'reason' is at most 200 chars");
                p.put("reason", reason);
            }
            default -> { }
        }
        return p;
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
            throw new ApiException(422, "'limit' is now 'budget' (plan §2.4: the rung's row budget)");
        p.put("budget", body.get("budget") == null ? DEFAULT_EXPAND_BUDGET
                : Math.min(MAX_EXPAND_BUDGET, positive(body, "budget", 1)));
        String direction = body.get("direction") == null ? "either" : String.valueOf(body.get("direction"));
        if (!DIRECTIONS.contains(direction))
            throw new ApiException(422, "'direction' must be one of " + DIRECTIONS + ", got '" + direction + "'");
        p.put("direction", direction);
        List<String> kinds = null;
        if (body.get("linkKinds") != null) {
            if (!(body.get("linkKinds") instanceof List<?> l) || l.isEmpty() || l.size() > MAX_LINK_KINDS)
                throw new ApiException(422, "'linkKinds' must be a non-empty list of at most " + MAX_LINK_KINDS
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
            throw new ApiException(422, "'candidateDegreeMin' must not exceed 'candidateDegreeMax'");
        p.put("candidateDegreeMin", dMin);
        p.put("candidateDegreeMax", dMax);
        p.put("maxFanOut", body.get("maxFanOut") == null ? null : positive(body, "maxFanOut", 1));
    }

    private static int positive(Map<String, Object> body, String key, int min) {
        if (!(body.get(key) instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())
                || n.longValue() < min || n.longValue() > Integer.MAX_VALUE)
            throw new ApiException(422, "'" + key + "' must be an integer >= " + min + ", got " + body.get(key));
        return n.intValue();
    }

    /**
     * Validate {@code timeCol}'s type and settle its zone (see {@link InvestigationTime}): a naive {@code TIMESTAMP}
     * takes {@code timeColZone} (UTC when absent, recorded explicitly); a {@code TIMESTAMP WITH TIME ZONE} is an
     * instant and refuses one. Anything else is not an event time. Returns the zone to record, or null.
     */
    private static String timeColZone(String dataset, String relationSql, String timeCol, String zone) {
        if (timeCol == null) {
            if (zone != null) throw new ApiException(422, "'timeColZone' needs a 'timeCol'");
            return null;
        }
        String type;
        try {
            QueryExecutor.Result r = QueryExecutor.run(new QueryExecutor.Request(dataset, relationSql,
                    "SELECT typeof(x) AS t FROM ((SELECT " + SqlIdent.q(timeCol) + " AS x FROM " + SqlIdent.q(dataset)
                            + " LIMIT 0) UNION ALL (SELECT NULL)) u", 1, 0, List.of(), List.of()));
            type = String.valueOf(r.rows().get(0).get("t")).toUpperCase(java.util.Locale.ROOT);
        } catch (Exception unusable) {
            throw new ApiException(422, "cannot read the type of '" + timeCol + "': " + unusable.getMessage());
        }
        if (type.equals("TIMESTAMP WITH TIME ZONE")) {
            if (zone != null) throw new ApiException(422, "'" + timeCol + "' is TIMESTAMP WITH TIME ZONE — already an "
                    + "instant, so a 'timeColZone' would be ignored; omit it");
            return null;
        }
        if (!type.equals("TIMESTAMP"))
            throw new ApiException(422, "'timeCol' must be a TIMESTAMP or TIMESTAMP WITH TIME ZONE column; '" + timeCol
                    + "' is " + type);
        String z = zone == null ? "UTC" : zone;
        String refusal = com.gamma.config.spec.SourceZoneGrammar.zoneRefusal(z, "timeColZone");
        if (refusal != null) throw new ApiException(422, refusal);
        return z;
    }

    /** The plain-language line for one step (plan §2.7: every op, including exclusions, with its reason). */
    @SuppressWarnings("unchecked")
    private static String render(Map<String, Object> e) {
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
                        + ".";
            }
            case "window" -> p.get("window") == null
                    ? "Cleared the time window: later expansions read the full time range."
                    : "Set the time window to " + InvestigationTime.describe((Map<String, Object>) p.get("window"))
                            + "; later expansions read inside it (earlier steps are unchanged).";
            case "exclude" -> "Excluded " + ids.size() + " entit" + (ids.size() == 1 ? "y" : "ies")
                    + " (reason: " + p.get("reason") + "): " + list(ids) + ".";
            case "hide" -> "Hid " + list(ids) + " from display (still traversed and counted).";
            case "keep" -> "Kept " + list(ids) + " (protected from later exclusion).";
            default -> "Applied " + e.get("op") + ".";
        };
    }

    private static String list(List<String> ids) {
        int shown = Math.min(10, ids.size());
        String head = String.join(", ", ids.subList(0, shown));
        return ids.size() > shown ? head + " and " + (ids.size() - shown) + " more" : head;
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
    static Inv open(ApiContext api, HttpExchange ex, String id) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "link analysis investigation");
        requireSafeId(id);
        SnapshotStore store = new SnapshotStore(writeRoot);
        jail(store, id);
        String raw = store.readInvestigation(id);
        if (raw == null) throw new ApiException(404, "no investigation '" + id + "'");
        @SuppressWarnings("unchecked") Map<String, Object> header = ApiContext.JSON.readValue(raw, Map.class);
        Optional<Subject> subject = ApiContext.subject(ex);
        if (subject.isPresent() && !subject.get().id().equals(header.get("owner")))
            throw new ApiException(404, "no investigation '" + id + "'");
        String dataset = String.valueOf(header.get("dataset"));
        Optional<Map<String, Object>> ds = new ComponentStore(writeRoot.resolve("registry")).get("dataset", dataset)
                .map(ComponentRegistry.Component::content);
        if (ds.isPresent() && !ComponentAccess.canView(ex, ds.get()))
            throw new ApiException(404, "no dataset '" + dataset + "'");
        Inv inv = new Inv(store, writeRoot, id, header);
        if (!RowScope.visible(ex, "investigation", resource(inv)))   // Enterprise PDP (D-E7); a DENY reads as absence
            throw new ApiException(404, "no investigation '" + id + "'");
        return inv;
    }

    /** What the Enterprise PDP judges: the Investigation's id, owner, bound Dataset and (for a fork) parent. */
    static Map<String, Object> resource(Inv inv) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", inv.id());
        r.put("owner", inv.header().get("owner"));
        r.put("dataset", inv.dataset());
        if (inv.header().get("parent") instanceof Map<?, ?> p) r.put("parent", p.get("id"));
        return r;
    }

    private static List<String> relationColumns(String datasetId, String relationSql) {
        try {
            QueryExecutor.Result r = QueryExecutor.run(new QueryExecutor.Request(
                    datasetId, relationSql, "SELECT * FROM " + SqlIdent.q(datasetId), 0, 0, List.of(), List.of()));
            return r.columns().stream().map(ResultSetDescriptor.Column::name).toList();
        } catch (Exception unusable) {
            throw new ApiException(422, "cannot read the columns of dataset '" + datasetId + "': "
                    + unusable.getMessage());
        }
    }

    private static List<Map<String, Object>> readLog(Inv inv) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String line : inv.store().readLog(inv.id())) {
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
    private static Map<String, Object> roundTrip(Map<String, Object> entry) throws IOException {
        return ApiContext.JSON.readValue(canonical(entry), LinkedHashMap.class);
    }

    private static Map<String, Object> setDoc(int step, InvestigationEvaluator.State s) {
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
            throw new ApiException(422, "investigation id must match " + SnapshotStore.SAFE_ID.pattern()
                    + ", got '" + id + "'");
    }

    private static void jail(SnapshotStore store, String id) {
        Path root = store.directory().resolve("investigations").normalize();
        Path target = store.investigationDir(id).normalize();
        if (!target.startsWith(root) || target.equals(root))
            throw new ApiException(403, "investigation id escapes the investigation store");
    }

    private static String ident(Map<String, Object> body, String key, boolean required) {
        String v = ApiContext.str(body, key);
        if (v == null) {
            if (required) throw new ApiException(422, "body must include '" + key + "'");
            return null;
        }
        if (!SAFE_IDENT.matcher(v).matches())
            throw new ApiException(422, "unsafe column identifier '" + v + "' for " + key);
        return v;
    }

    private static Object lock(Path p) {
        return LOCKS.computeIfAbsent(p.toAbsolutePath().normalize(), k -> new Object());
    }

    /** Best-effort audit (LA-04 pattern), emitted only AFTER the act succeeded so the trail never over-claims. */
    private static void emit(HttpExchange ex, String type, String action, String message,
                             UnaryOperator<Event.Builder> attrs) {
        try {
            Event.Builder b = Event.builder(type).source("inv").message(message)
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action(action).actionCategory("analysis");
            EventLog.current().emit(attrs.apply(b));
        } catch (RuntimeException ignored) {
            // best effort — the step is already sealed and that is what matters
        }
    }
}
