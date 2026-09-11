package com.gamma.control;

import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import com.gamma.query.DatasetRelation;
import com.gamma.query.ReconConfigLoader;
import com.gamma.query.ReconService;

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
        api.post("/recon/promote", (e, m) -> promote(api, api.body(e)));
    }

    // ── POST /recon/columns {datasets:[ids]} ────────────────────────────────────────

    private Object columns(ApiContext api, Map<String, Object> body) {
        Path writeRoot = WriteGates.requireWriteRoot(api, "reconciliation");
        List<String> ids = strings(body.get("datasets"));
        if (ids.isEmpty()) throw new ApiException(422, "missing 'datasets' (the dataset ids to inventory)");
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        List<ReconService.Side> sides = ids.stream()
                .map(id -> new ReconService.Side(id, relationSql(api, store, writeRoot, id), null, null))
                .toList();
        try {
            return ReconService.columns(sides);
        } catch (SQLException e) {
            throw new ApiException(422, "column inventory failed: " + e.getMessage());
        } catch (IOException e) {
            throw new ApiException(503, "query sandbox unavailable: " + e.getMessage());
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
            throw new ApiException(422, bad.getMessage());
        } catch (SQLException e) {
            throw new ApiException(422, "reconciliation failed: " + e.getMessage());
        } catch (IOException e) {
            throw new ApiException(503, "query sandbox unavailable: " + e.getMessage());
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
        if (other < 0) throw new ApiException(422, "side must be b|c, got '" + side + "'");
        Map<String, String> path = pathOf(body.get("path"));
        Map<String, ReconService.BreakSet> sets;
        try {
            sets = ReconService.breaks(spec, path, type, other, limit, offset);
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, bad.getMessage());
        } catch (SQLException e) {
            throw new ApiException(422, "reconciliation failed: " + e.getMessage());
        } catch (IOException e) {
            throw new ApiException(503, "query sandbox unavailable: " + e.getMessage());
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
     * <p>The dedupe is on the KEY, not on {@code (key, type, column)}: one business key that breaks on three
     * columns is one thing for an operator to investigate, and the specific type/column ride along as
     * attributes. ⚠ A consequence worth knowing: when the same key later breaks a different way, the open
     * Incident is reused and its attributes still describe the FIRST observation.
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
            throw new ApiException(422, "missing 'reconciliation' (the saved reconciliation this Break came from)");
        String key = ApiContext.str(body, "key");
        if (key == null || key.isBlank())
            throw new ApiException(422, "missing 'key' (the Break's business key — the dedupe identity)");

        // An inline draft config is accepted by every other recon route; NOT here. An Incident that
        // outlives the session must reference a reconciliation someone can still open.
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        component(store, "reconciliation", reconId)
                .orElseThrow(() -> new ApiException(404, "no reconciliation '" + reconId + "'"));

        com.gamma.objects.ObjectAccess objects = api.service().objects().orElseThrow(() -> new ApiException(503,
                "operational objects are not installed — promoting a Break to an Incident needs the "
                        + "inspecto-ops module, which ships in Standard and Enterprise (EDITIONS CP-11)"));

        String type = orDefault(ApiContext.str(body, "type"), "break");
        String column = ApiContext.str(body, "column");
        String runId = ApiContext.str(body, "runId");

        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("reconciliation", reconId);
        attrs.put("breakKey", key);                 // ⚠ the dedupe attribute — see the class note
        attrs.put("breakType", type);
        if (column != null && !column.isBlank()) attrs.put("column", column);
        if (runId != null && !runId.isBlank()) attrs.put("runId", runId);
        attrs.put("promotedFrom", "reconciliation");

        String where = (column == null || column.isBlank()) ? "" : " on '" + column + "'";
        Optional<String> incidentId = com.gamma.objects.IncidentAccess.over(() -> objects).openIncident(
                "Reconciliation break: " + key,
                "Break '" + key + "' (" + type + ")" + where + " promoted from reconciliation '" + reconId + "'"
                        + (runId == null || runId.isBlank() ? "" : ", run " + runId) + ".",
                "WARNING", reconId, attrs, "breakKey");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("incidentId", incidentId.orElse(null));
        out.put("deduped", incidentId.isEmpty());
        out.put("reconciliation", reconId);
        out.put("key", key);
        return out;
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
            if (id == null) throw new ApiException(422, "provide a saved reconciliation 'id' or an inline 'config'");
            config = component(store, "reconciliation", id)
                    .orElseThrow(() -> new ApiException(404, "no reconciliation '" + id + "'"));
        }
        // Shared spec assembly (identical to the scheduled recon.run Job); relation-SQL resolution stays
        // here so an unknown/unusable dataset keeps its route gate (404/422 via relationSql).
        try {
            return ReconConfigLoader.buildSpec(config, dsId -> relationSql(api, store, writeRoot, dsId));
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, bad.getMessage());
        }
    }

    /** A dataset component's trusted relation SQL — 404 unknown dataset, 422 unusable dataset config. */
    private static String relationSql(ApiContext api, ComponentStore store, Path writeRoot, String datasetId) {
        Map<String, Object> dataset = component(store, "dataset", datasetId)
                .orElseThrow(() -> new ApiException(404, "unknown dataset '" + datasetId + "'"));
        try {
            return DatasetRelation.relationSql(dataset, api.dataRoot(), new ViewStore(writeRoot.resolve("views")));
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, "dataset '" + datasetId + "': " + bad.getMessage());
        }
    }

    private static Optional<Map<String, Object>> component(ComponentStore store, String type, String id) {
        try {
            return store.get(type, id).map(ComponentRegistry.Component::content);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
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
