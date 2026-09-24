package com.gamma.control;

import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import com.gamma.query.DatasetRelation;
import com.gamma.query.ReconConfigLoader;
import com.gamma.query.ReconService;
import com.gamma.util.DuckDbUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static com.gamma.util.Values.intOr;

/**
 * Reconciliation execution (DAT-7; design {@code docs/superpower/reconciliation-board-design.md}) —
 * {@code POST /recon/columns} (per-dataset column inventory + cross-side auto-matches for the unified
 * column list), {@code POST /recon/run} (the Board's grain rows + exact totals + Break summary) and
 * {@code POST /recon/breaks} (the paged Break sets, optionally scoped to a Board dimension path).
 * Each accepts a saved {@code reconciliation} component by {@code id} <em>or</em> an inline
 * {@code config} (the Board's draft mode). Authoring reuses the generic component CRUD
 * ({@code /components/reconciliation/{id}}); Break lifecycle (auto-close / preserved resolutions) stays
 * client-side per the C9 contract — these routes are stateless compute over {@link ReconService}.
 * The one exception is {@code POST /recon/promote} ({@code BREAK-INCIDENT-1}), which writes: it hands a
 * single Break to Ops as an {@code INCIDENT}, deduped on {@code (reconciliation, key)}. It is still not a
 * Break store — nothing about the Break is persisted, only the Incident that references it.
 *
 * <p>Fail-closed: write root unset → 503; unknown reconciliation / dataset → 404; an unusable config
 * (dataset count, unsafe identifier, bad agg/tolerance, {@code ExpressionGuard}-rejected filter) or a
 * failing comparison query → 422. All comparison SQL is server-built — there is no caller-SQL surface.
 */
final class ReconRoutes implements RouteModule {

    private static final int GRAIN_LIMIT = 5_000;      // Board grain-row cap (MAX_LIMIT parity)
    private static final int DEFAULT_BREAKS_LIMIT = 200;
    private static final int MAX_LIMIT = 5_000;

    @Override
    public void register(ApiContext api) {
        api.post("/recon/columns", (e, m) -> columns(api, api.body(e)));
        api.post("/recon/run", (e, m) -> run(api, api.body(e)));
        api.post("/recon/breaks", (e, m) -> breaks(api, api.body(e)));
        // RECON-CARDINALITY-2: the raw rows behind ONE key — read-shaped like its siblings (recorded so in
        // CapabilityManifest.EXEMPTIONS); fetched on demand when a reader expands a cardinality break.
        api.post("/recon/rows", (e, m) -> rows(api, api.body(e)));
        // Opening an Incident from a Break: gated by `canManageIncidents` (operator, 2026-09-16). The
        // capability was CREATED for this act rather than borrowed from `canOperateRuns` (DecisionRoutes'
        // choice) or `canAuthorWorkbench` — `POST /objects` performs the same act and shares the gate.
        api.post("/recon/promote", ApiContext.withCapability("canManageIncidents",
                (e, m) -> promote(api, api.body(e))));
        api.get("/recon/promoted", (e, m) -> promoted(api, ApiContext.query(e, "reconciliation")));
    }

    // ── POST /recon/columns {datasets:[ids]} ────────────────────────────────────────

    private Object columns(ApiContext api, Map<String, Object> body) {
        Path writeRoot = WriteGates.requireWriteRoot(api, "reconciliation");
        List<String> ids = strings(body.get("datasets"));
        if (ids.isEmpty()) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "missing 'datasets' (the dataset ids to inventory)");
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        List<ReconService.Side> sides = ids.stream()
                .map(id -> new ReconService.Side(id, relationSql(api, store, writeRoot, id), null, null))
                .toList();
        try {
            return ReconService.columns(sides);
        } catch (SQLException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "column inventory failed: " + DuckDbUtil.withoutPendingQueryPreamble(e.getMessage()));
        } catch (IOException e) {
            throw new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE, "query sandbox unavailable: " + e.getMessage());
        }
    }

    // ── POST /recon/run {id | config, limit?} ───────────────────────────────────────

    private Object run(ApiContext api, Map<String, Object> body) {
        ReconService.Spec spec = spec(api, body);
        int limit = clamp(intOr(body.get("limit"), GRAIN_LIMIT));
        ReconService.RunResult r;
        try {
            r = ReconService.run(spec, limit);
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, bad.getMessage());
        } catch (SQLException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "reconciliation failed: " + DuckDbUtil.withoutPendingQueryPreamble(e.getMessage()));
        } catch (IOException e) {
            throw new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE, "query sandbox unavailable: " + e.getMessage());
        }
        Map<String, Object> statistics = new LinkedHashMap<>();
        statistics.put("rowCount", r.rows().size());
        statistics.put("elapsedMs", r.elapsedMs());
        statistics.put("truncated", r.truncated());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("keyColumns", spec.keyColumns());
        data.put("measures", measureNames(spec));
        data.put("rows", r.rows());
        data.put("totals", r.totals());
        data.put("summary", r.summary());
        data.put("statistics", statistics);
        return data;
    }

    // ── POST /recon/breaks {id | config, path?, type?, limit?, offset?} ─────────────

    private Object breaks(ApiContext api, Map<String, Object> body) {
        ReconService.Spec spec = spec(api, body);
        int limit = clamp(intOr(body.get("limit"), DEFAULT_BREAKS_LIMIT));
        int offset = Math.max(0, intOr(body.get("offset"), 0));
        String type = ApiContext.str(body, "type");
        // The compared side of the anchor-relative pair: "b" (default) or, on a 3-way spec, "c".
        String side = orDefault(ApiContext.str(body, "side"), "b");
        int other = "b".equals(side) ? 1 : "c".equals(side) ? 2 : -1;
        if (other < 0) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "side must be b|c, got '" + side + "'");
        Map<String, String> path = pathOf(body.get("path"));
        Map<String, ReconService.BreakSet> sets;
        try {
            sets = ReconService.breaks(spec, path, type, other, limit, offset);
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, bad.getMessage());
        } catch (SQLException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "reconciliation failed: " + DuckDbUtil.withoutPendingQueryPreamble(e.getMessage()));
        } catch (IOException e) {
            throw new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE, "query sandbox unavailable: " + e.getMessage());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        for (Map.Entry<String, ReconService.BreakSet> e : sets.entrySet()) {
            Map<String, Object> set = new LinkedHashMap<>();
            set.put("rows", e.getValue().rows());
            set.put("rowCount", e.getValue().rowCount());
            set.put("truncated", e.getValue().truncated());
            data.put(e.getKey(), set);
        }
        return data;
    }

    // ── POST /recon/rows {id | config, key:{col:val…}, side?, limit?} ───────────────

    /**
     * The raw rows behind one key on both sides ({@code RECON-CARDINALITY-2}). {@code key} must name every
     * key column; {@code side} picks the compared side as {@code /recon/breaks} does. Stateless compute like
     * its siblings — nothing is persisted, so nothing here needs a capability.
     */
    private Object rows(ApiContext api, Map<String, Object> body) {
        ReconService.Spec spec = spec(api, body);
        Map<String, String> key = pathOf(body.get("key"));
        if (key == null) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "body must include 'key' — every key column and its value");
        int limit = clamp(intOr(body.get("limit"), DEFAULT_BREAKS_LIMIT));
        String side = orDefault(ApiContext.str(body, "side"), "b");
        int other = "b".equals(side) ? 1 : "c".equals(side) ? 2 : -1;
        if (other < 0) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "side must be b|c, got '" + side + "'");
        Map<String, ReconService.BreakSet> sets;
        try {
            sets = ReconService.rows(spec, other, key, limit);
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, bad.getMessage());
        } catch (SQLException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "reconciliation failed: " + DuckDbUtil.withoutPendingQueryPreamble(e.getMessage()));
        } catch (IOException e) {
            throw new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE, "query sandbox unavailable: " + e.getMessage());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("key", key);
        for (Map.Entry<String, ReconService.BreakSet> e : sets.entrySet()) {
            Map<String, Object> set = new LinkedHashMap<>();
            set.put("rows", e.getValue().rows());
            set.put("rowCount", e.getValue().rowCount());
            set.put("truncated", e.getValue().truncated());
            data.put(e.getKey(), set);
        }
        return data;
    }

    // ── POST /recon/promote {reconciliation, key, type?, column?, runId?} ───────────

    /**
     * Promote one reconciliation Break to a managed {@link com.gamma.objects.ObjectType#INCIDENT} — {@code BREAK-INCIDENT-1}.
     *
     * <p>Until this route, a Break could be marked {@code resolved} on the board but could not be handed to
     * Ops at all. ⚠ The backlog row said the tree's only promotion was {@code Alert → Incident}; that was
     * <b>wrong</b>. {@link com.gamma.job.ReconRunJob} has always opened an Incident on a breach — but one
     * <em>aggregate</em> Incident per reconciliation run, carrying only break counts. This route is the
     * missing granularity, not the missing mechanism, and the two coexist on purpose: the Job says "this
     * reconciliation is breaching", an operator promoting here says "<em>this</em> Break is being worked".
     *
     * <h3>Why a Break can be promoted at all, given it does not exist server-side</h3>
     * 🔴 Reconciliation is <b>stateless compute</b> — {@code /recon/run} and {@code /recon/breaks} recompute
     * from SQL on every call and persist nothing, so there is no stored Break row to carry a foreign key to.
     * The identity is therefore reconstructed from the request: {@code (reconciliation, key)}, which is
     * stable across runs because it is what the comparison itself keys on. That pair is the dedupe key, so
     * promoting the same Break twice — from two operators, or after a re-run — suppresses the second rather
     * than handing Ops a clone. ⛔ Do not "fix" this by persisting Breaks to make the reference real: the C9
     * contract puts Break lifecycle on the client deliberately, and an Incident is the durable artifact.
     *
     * <p>🔴 <b>The dedupe grain is {@code (type, key, column)} — full parity with the client's
     * {@code breakId}</b> ({@code BREAK-DEDUPE-GRAIN-1}, decided 2026-09-15). It was the KEY alone until then,
     * which meant one business key breaking on {@code amount} and on {@code count} produced ONE Incident and
     * the second promote was silently suppressed. ⚠ <b>Accepted cost</b>, decided rather than overlooked: a
     * value Break and a missing-row Break on one key+column are now separate Incidents.
     *
     * <p>⛔ The write and the read MUST stay on one spelling — the offer read is keyed to match the dedupe
     * precisely so the two cannot disagree — which is why the identity is one synthesized
     * {@link #BREAK_ID} attribute rather than three matched attributes: {@code activeAttributeIndex} indexes a
     * SINGLE attribute, so three-attribute matching would leave the read unable to express the grain.
     *
     * <p>⚠ <b>One-time upgrade effect.</b> Incidents opened before this change carry no {@link #BREAK_ID}
     * attribute, so they no longer suppress a re-promote of the same Break: each such Break can be promoted
     * once more, after which the new grain governs. Accepted as a bounded, low-harm transition rather than
     * carrying a second legacy predicate that would re-introduce the key-only grain it replaces.
     *
     * <p>Gates, in order: write root unset → 503 · missing {@code reconciliation}/{@code key} → 422 ·
     * unknown reconciliation → 404 · no operational-object engine (a Personal build has none) → 503.
     * The last is why the UI must render an explained panel rather than a toast.
     *
     * @return {@code {incidentId, deduped, reconciliation, key}}; {@code deduped} means an active Incident
     *         already covers this Break and {@code incidentId} is then {@code null} — the seam reports
     *         suppression without naming the survivor, and widening it for that alone was not worth an
     *         SPI change (the UI lists the reconciliation's Incidents instead).
     */
    private Object promote(ApiContext api, Map<String, Object> body) {
        Path writeRoot = WriteGates.requireWriteRoot(api, "reconciliation");
        String reconId = ApiContext.str(body, "reconciliation");
        if (reconId == null || reconId.isBlank())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "missing 'reconciliation' (the saved reconciliation this Break came from)");
        String key = ApiContext.str(body, "key");
        if (key == null || key.isBlank())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "missing 'key' (the Break's business key — the dedupe identity)");

        // An inline draft config is accepted by every other recon route; NOT here. An Incident that
        // outlives the session must reference a reconciliation someone can still open.
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        component(store, "reconciliation", reconId)
                .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "no reconciliation '" + reconId + "'"));

        com.gamma.objects.ObjectAccess objects = api.service().objects().orElseThrow(() -> new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE,
                "operational objects are not installed — promoting a Break to an Incident needs the "
                        + "inspecto-ops module, which ships in Standard and Enterprise (EDITIONS CP-11)"));

        String type = orDefault(ApiContext.str(body, "type"), "break");
        String column = ApiContext.str(body, "column");
        String runId = ApiContext.str(body, "runId");

        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("reconciliation", reconId);
        attrs.put(BREAK_ID, breakIdentity(type, key, column));   // ⚠ the dedupe attribute — see the class note
        attrs.put("breakKey", key);                 // kept: the human-readable half, and what reports group by
        attrs.put("breakType", type);
        if (column != null && !column.isBlank()) attrs.put("column", column);
        if (runId != null && !runId.isBlank()) attrs.put("runId", runId);
        attrs.put("promotedFrom", "reconciliation");

        String where = (column == null || column.isBlank()) ? "" : " on '" + column + "'";
        Optional<String> incidentId = com.gamma.objects.IncidentAccess.over(() -> objects).openIncident(
                "Reconciliation break: " + key,
                "Break '" + key + "' (" + type + ")" + where + " promoted from reconciliation '" + reconId + "'"
                        + (runId == null || runId.isBlank() ? "" : ", run " + runId) + ".",
                "WARNING", reconId, attrs, BREAK_ID);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("incidentId", incidentId.orElse(null));
        out.put("deduped", incidentId.isEmpty());
        out.put("reconciliation", reconId);
        out.put("key", key);
        return out;
    }

    // ── GET /recon/promoted?reconciliation=<id> ──────────────────────────────

    /**
     * Which Breaks of {@code reconId} already have an <b>active</b> Incident — {@code breakKey → incidentId},
     * the read half of {@link #promote}.
     *
     * <p>🔴 <b>Why the server answers this and not the client.</b> The SPA held "promoted" as an in-memory
     * Set, so a reload forgot every promotion and re-offered the action as if it had never happened. The
     * obvious client-side fix — list the Incidents for this reconciliation and match {@code breakKey} — is
     * wrong in a way that is easy to miss: {@link #promote} dedupes on <b>non-terminal</b> Incidents only
     * ({@code IncidentAccess} → {@code hasActiveMatching}), so a Break whose Incident was CLOSED <em>can</em>
     * be promoted again. A client matching on mere existence would report "already promoted" and discourage
     * a legitimate action — the same defect class as a refusal message that outlived its rule. Both halves
     * now read {@code ObjectAccess.activeAttributeIndex}, so the offer and the dedupe cannot disagree.
     *
     * <p>⚠ Bounded like every diagnostic read: at most {@link #PROMOTED_CAP} entries, with {@code truncated}
     * and the TRUE {@code total} reported rather than a silently short map.
     *
     * <p>⚠ No write-root gate — this writes nothing. It keeps {@link #promote}'s <b>503</b> when the ops
     * module is absent, because "no Incidents here" and "Incidents are not installed" are different answers
     * and a client that cannot tell them apart would render an empty board as a healthy one.
     */
    private static Object promoted(ApiContext api, String reconId) {
        if (reconId == null || reconId.isBlank())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "missing 'reconciliation' (the reconciliation id to report on)");
        // The id becomes a filename under the registry, so it is gated as a bare name (422) rather than
        // jailed after the fact — a separator never reaches a path resolve.
        String safeId = WriteGates.safeName(reconId, "reconciliation id");

        // ⚠ NO write-root 503: this route writes nothing. `api.writeRoot()` is read directly rather than
        // through `requireWriteRoot`, and an unset root means there is no component registry at all — which
        // makes "no such reconciliation" the TRUE answer, not "service unavailable".
        Path root = api.writeRoot();
        // ⚠ 404 on an unknown reconciliation, exactly as promote() does — an empty map would be
        // indistinguishable from "nothing is promoted", so a typo would read as a healthy board.
        if (root == null || component(new ComponentStore(root.resolve("registry")), "reconciliation", safeId).isEmpty())
            throw new ApiException(404, ErrorCodes.NOT_FOUND, "no reconciliation '" + safeId + "'");

        com.gamma.objects.ObjectAccess objects = api.service().objects().orElseThrow(() -> new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE,
                "operational objects are not installed — reading promoted Breaks needs the "
                        + "inspecto-ops module, which ships in Standard and Enterprise (EDITIONS CP-11)"));

        // ⚠ `scope` is the reconciliation id, exactly as promote() passes it to openIncident — that shared
        // spelling is what makes this index findable at all.
        Map<String, String> index = objects.activeAttributeIndex(
                com.gamma.objects.ObjectType.INCIDENT, safeId, BREAK_ID);

        Map<String, String> page = index;
        if (index.size() > PROMOTED_CAP) {
            page = new LinkedHashMap<>();
            for (Map.Entry<String, String> en : index.entrySet()) {
                if (page.size() >= PROMOTED_CAP) break;
                page.put(en.getKey(), en.getValue());
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reconciliation", safeId);
        out.put("promoted", page);
        out.put("total", index.size());
        out.put("truncated", index.size() > PROMOTED_CAP);
        return out;
    }

    /** Hard cap on {@link #promoted}'s map — a diagnostic read must not become an unbounded export. */
    private static final int PROMOTED_CAP = 1000;

    /**
     * The Incident attribute carrying a Break's full identity, and the attribute {@link #promote} dedupes on
     * and {@link #promoted} indexes by ({@code BREAK-DEDUPE-GRAIN-1}). One spelling, used by both halves.
     */
    static final String BREAK_ID = "breakId";

    /**
     * A Break's identity — {@code (type, key, column)} — rendered as the one string both the dedupe and the
     * offer read match on. The client computes the byte-identical value in {@code reconciliation-types.ts}'s
     * {@code breakId()}; ⛔ <b>the two are one contract and must change together</b>, because the SPA looks its
     * own Breaks up in {@link #promoted}'s map by this exact string.
     *
     * <p>🔴 <b>Why the parts are escaped rather than merely joined.</b> A reconciliation key is itself a
     * composite of the key columns' values, so it routinely CONTAINS the obvious separators — the repo's own
     * test data uses keys like {@code "EU|voice"}. A plain join would let {@code (break, "EU|voice", "amount")}
     * and {@code (break, "EU", "voice|amount")} collide into one identity, silently merging two different
     * Breaks into one Incident — the very defect this decision exists to fix, reintroduced one level down.
     * Escaping {@code \} then {@code |} makes the rendering injective, so distinct triples stay distinct.
     */
    static String breakIdentity(String type, String key, String column) {
        return esc(type) + '|' + esc(key) + '|' + esc(column == null ? "" : column);
    }

    /** Escape a single identity part so {@code |} inside a value cannot be read as the separator. */
    private static String esc(String part) {
        return (part == null ? "" : part).replace("\\", "\\\\").replace("|", "\\|");
    }

    // ── spec assembly ───────────────────────────────────────────────────────────────

    /** Resolve the request's reconciliation config ({@code config} inline, or saved {@code id}) to a validated spec. */
    private ReconService.Spec spec(ApiContext api, Map<String, Object> body) {
        Path writeRoot = WriteGates.requireWriteRoot(api, "reconciliation");
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));

        Map<String, Object> config;
        if (body.get("config") instanceof Map<?, ?> inline) {
            config = cast(inline);
        } else {
            String id = ApiContext.str(body, "id");
            if (id == null) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "provide a saved reconciliation 'id' or an inline 'config'");
            config = component(store, "reconciliation", id)
                    .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "no reconciliation '" + id + "'"));
        }
        // Shared spec assembly (identical to the scheduled recon.run Job); relation-SQL resolution stays
        // here so an unknown/unusable dataset keeps its route gate (404/422 via relationSql).
        try {
            return ReconConfigLoader.buildSpec(config, dsId -> relationSql(api, store, writeRoot, dsId));
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, bad.getMessage());
        }
    }

    /** A dataset component's trusted relation SQL — 404 unknown dataset, 422 unusable dataset config. */
    private static String relationSql(ApiContext api, ComponentStore store, Path writeRoot, String datasetId) {
        Map<String, Object> dataset = component(store, "dataset", datasetId)
                .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "unknown dataset '" + datasetId + "'"));
        try {
            return DatasetRelation.relationSql(dataset, api.dataRoot(), new ViewStore(writeRoot.resolve("views")));
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "dataset '" + datasetId + "': " + bad.getMessage());
        }
    }

    private static Optional<Map<String, Object>> component(ComponentStore store, String type, String id) {
        try {
            return store.get(type, id).map(ComponentRegistry.Component::content);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, e.getMessage());
        }
    }

    // ── request parsing helpers ─────────────────────────────────────────────────────

    private List<String> measureNames(ReconService.Spec spec) {
        List<String> names = new ArrayList<>();
        for (ReconService.Measure m : spec.measures()) names.add(m.name());
        if (spec.includeRecordCount()) names.add(ReconService.RECORDS);
        return names;
    }

    private static Map<String, String> pathOf(Object v) {
        if (!(v instanceof Map<?, ?> m) || m.isEmpty()) return null;
        Map<String, String> path = new LinkedHashMap<>();
        m.forEach((k, val) -> { if (val != null) path.put(String.valueOf(k), String.valueOf(val)); });
        return path;
    }

    private static List<String> strings(Object v) {
        if (!(v instanceof List<?> list)) return List.of();
        return list.stream().filter(o -> o != null && !o.toString().isBlank())
                .map(o -> o.toString().trim()).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private static String orDefault(String v, String def) {
        return v == null ? def : v;
    }

    private static int clamp(int l) {
        return Math.max(1, Math.min(MAX_LIMIT, l));
    }
}
