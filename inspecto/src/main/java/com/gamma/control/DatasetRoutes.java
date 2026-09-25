package com.gamma.control;

import com.gamma.job.JobService;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import com.gamma.query.DatasetRelation;
import com.gamma.query.QueryExecutor;
import com.gamma.query.ResultSetDescriptor;
import com.gamma.util.DuckDbUtil;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dataset actions ({@code POST /datasets/{id}/materialize}) plus the Dataset's own row page
 * ({@code GET /datasets/{id}/rows}, see {@link #rows}) — the one capability the generic component
 * CRUD ({@code /components/dataset/{id}}) cannot express, because materializing is an <em>action</em> on a
 * Dataset rather than an edit of its document.
 *
 * <p>Why a route at all (STUDIO-HALVES-1, decided 2026-09-14): the alternative was having the Dataset page
 * create and trigger a job through {@code POST /jobs} + {@code POST /jobs/{id}/trigger}, which makes a
 * viewer action <b>write a job document</b> — an authoring side effect from a page a non-author can open.
 * One more endpoint was judged the better trade. ⚠ The cost is stated on the row: this adds a surface to
 * the next MAJOR's API review.
 *
 * <p><b>Asynchronous by design.</b> A materialize defaults to a 1,000,000-row snapshot, so this follows
 * {@code POST /jobs/{name}/trigger} exactly — {@code 202} + {@code runId} + a {@code Location} to poll —
 * rather than blocking a request thread for minutes. It runs through the ordinary job lifecycle
 * ({@link JobService#triggerMaterializeRun}) but registers nothing, so {@code GET /jobs} stays
 * config-only.
 *
 * <p>Gates, fail-closed and in order: write root unset → 503 · unknown source Dataset → 404 · a missing,
 * unsafe or self-referential {@code target} → 422 · a target escaping the data root → 403 · a materialize
 * of the same target already running → 409. ⛔ The 409 is not decoration: without it a second fire is
 * admitted and silently recorded {@code SKIPPED} by the per-name non-overlap guard, so the caller would
 * get a {@code runId} for a run that never does anything.
 *
 * <p>⚠ Gated on {@code canOperateRuns}, the same capability as {@code POST /jobs/{name}/trigger} — NOT on
 * an authoring capability. Materializing writes <em>data</em>, never config, and a holder of
 * {@code canOperateRuns} can already reach exactly this effect today by triggering a {@code maintenance}
 * job with {@code task: materialize}. An authoring gate here would be stricter than the existing path to
 * the same outcome while protecting nothing.
 */
final class DatasetRoutes implements RouteModule {

    private static final int DEFAULT_ROW_LIMIT = 200;
    private static final int MAX_ROW_LIMIT = 5_000;

    @Override
    public void register(ApiContext api) {
        api.get("/datasets/([^/]+)/rows", (e, m) -> rows(api, e, ApiContext.name(m)));
        api.post("/datasets/([^/]+)/materialize",
                ApiContext.withCapability("canOperateRuns",
                        (e, m) -> materialize(api, e, ApiContext.name(m))));
    }

    /**
     * {@code GET /datasets/{id}/rows?limit=} — one page of the Dataset's <em>relation</em>
     * ({@link DatasetRelation#relationSql}: its view or {@code physicalRef} PLUS its calculated columns),
     * where {@code GET /db/table} reads the raw store behind it. This is what lets the Widget Builder see a
     * calculated column's served type and cardinality at all — the raw store has no such column. Same
     * payload shape as {@code /db/table} ({@code columns[{name,type,role,cardinality}], rows, statistics}),
     * so the UI rows seam maps both identically.
     *
     * <p>Read-only, so no capability gate (like {@code POST /bi/query}). Fail-closed: write root unset → 503 ·
     * unknown or shared-away Dataset → 404 · an unusable Dataset config (a calculated expression the
     * {@code ExpressionGuard} refuses, no view/physicalRef, a ref escaping the data root) or a DuckDB failure
     * → 422. Bounded: {@code limit} clamps to 1..5000 and {@code statistics.truncated} says the relation
     * held more.
     */
    private Object rows(ApiContext api, HttpExchange ex, String id) {
        Path writeRoot = WriteGates.requireWriteRoot(api, "dataset rows");
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        Map<String, Object> dataset;
        try {
            dataset = store.get("dataset", id).map(ComponentRegistry.Component::content)
                    .orElseThrow(() -> new ApiException(404, "no dataset '" + id + "'"));
        } catch (IllegalArgumentException bad) {             // an id the store refuses to resolve
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, bad.getMessage());
        }
        if (!ComponentAccess.canView(ex, dataset))             // R3: shared-away ⇒ indistinguishable from absence
            throw new ApiException(404, "no dataset '" + id + "'");
        String relationSql;
        try {
            relationSql = DatasetRelation.relationSql(dataset, api.dataRoot(), new ViewStore(writeRoot.resolve("views")));
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, bad.getMessage());
        }
        int limit = Math.max(1, Math.min(MAX_ROW_LIMIT,
                ApiContext.parseIntOr(ApiContext.query(ex, "limit"), DEFAULT_ROW_LIMIT)));
        // The view is named after the Dataset id; QueryExecutor quotes it on CREATE VIEW the same way.
        String sql = "SELECT * FROM \"" + id.replace("\"", "\"\"") + "\"";
        QueryExecutor.Result r;
        try {
            r = QueryExecutor.run(new QueryExecutor.Request(id, relationSql, sql, limit, 0, List.of(), List.of()));
        } catch (SQLException e) {
            throw new ApiException(422, "dataset read failed: " + DuckDbUtil.withoutPendingQueryPreamble(e.getMessage()));
        } catch (IOException e) {
            throw new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE, "query sandbox unavailable: " + e.getMessage());
        }
        List<Map<String, Object>> columns = new ArrayList<>();
        for (ResultSetDescriptor.Column c : r.columns()) {
            Map<String, Object> col = new LinkedHashMap<>();
            col.put("name", c.name());
            col.put("type", c.type());
            col.put("role", c.role());
            col.put("cardinality", c.cardinality());
            columns.add(col);
        }
        Map<String, Object> statistics = new LinkedHashMap<>();
        statistics.put("rowCount", r.rowCount());
        statistics.put("elapsedMs", r.elapsedMs());
        statistics.put("truncated", r.truncated());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("columns", columns);
        data.put("rows", r.rows());
        data.put("statistics", statistics);
        return data;
    }

    private Object materialize(ApiContext api, HttpExchange ex, String id) throws IOException {
        // Gate 1 — writes disabled. MaterializeTask needs -Dassist.write.root and would otherwise fail
        // INSIDE the run, i.e. after a 202 had already promised the caller something.
        Path writeRoot = WriteGates.requireWriteRoot(api, "dataset materialize");


        // 404 — the source Dataset must exist. The task looks it up in this same store and throws at run
        // time; checking here turns that into an answer the caller can act on.
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        store.get("dataset", id).map(ComponentRegistry.Component::content)
                .orElseThrow(() -> new ApiException(404, "no dataset '" + id + "'"));

        Map<String, Object> body = api.body(ex);
        String target = ApiContext.str(body, "target");
        if (target == null)
            throw new ApiException(422, "materialize needs a 'target' dataset id to write to");
        // Gate 2 — the target becomes a directory name under the data root.
        target = WriteGates.safeName(target, "materialize target");
        if (target.equals(id))
            throw new ApiException(422, "materialize target must differ from the source dataset '" + id + "'");

        // Gate 3 — jail the resolved output directory. safeName already refuses separators and '..', so
        // this cannot currently fire; it is kept because the containment rule must live at the edge too,
        // not only in whatever the name check happens to allow today.
        Path dataRoot = api.dataRoot();
        WriteGates.jail(dataRoot, dataRoot.resolve(target), "materialize target");

        // ⚠ ...OrCreate, not jobService(): this run is ad-hoc and needs no registry, so a space holding no
        // *_job.toon must still be able to materialize. JobRoutes' own trigger uses the Optional form
        // because triggering a REGISTERED job by name genuinely requires the registry to exist.
        JobService jobs = api.service().jobServiceOrCreate();

        // Gate 4 — non-overlap, surfaced as a refusal instead of a silently-SKIPPED run.
        String runName = "materialize:" + target;
        WriteGates.conflictIf(jobs.isRunning(runName),
                "a materialize of '" + target + "' is already running");

        String runId = jobs.triggerMaterializeRun(params(id, target, body), ApiContext.actor(ex));

        ex.getResponseHeaders().set("Location", "/api/v1/jobs/runs/" + runId);
        return ApiContext.respondJson(ex, 202, Map.of(
                "runId", runId, "dataset", id, "target", target, "status", "running"));
    }

    /**
     * The task's parameter map. Optional keys are passed through only when present, so an absent key
     * keeps {@code MaterializeTask}'s own default rather than this route restating it — two copies of a
     * default is how they drift.
     */
    private static Map<String, String> params(String source, String target, Map<String, Object> body) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("dataset", source);
        p.put("target", target);
        for (String key : new String[] {"measures", "group_by", "limit"}) {
            String v = ApiContext.str(body, key);
            if (v != null) p.put(key, v);
        }
        return p;
    }
}
