package com.gamma.control;

import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;
import com.gamma.config.spec.Finding;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import com.gamma.query.DatasetRelation;
import com.gamma.query.MeasureCompiler;
import com.gamma.query.Parameters;
import com.gamma.query.QueryExecutor;
import com.gamma.query.ResultSetDescriptor;
import com.gamma.sql.SqlGuard;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import com.gamma.access.ComponentAccess;
import com.gamma.access.WriteGates;

/**
 * The semantic / headless BI API (BI-7): spec-based aggregation over Datasets without authoring a
 * {@code query} component — the backend twin of the UI viz layer's QuerySpec seam, so an external
 * consumer (or the UI itself, later) can evaluate measures server-side over the at-rest data.
 *
 * <ul>
 *   <li>{@code POST /bi/query} — {@code {dataset, measures[{agg,field}], groupBy[], filters[],
 *       orderBy[{field,dir,agg?}], limit}} → compiled by {@link MeasureCompiler} (validated identifiers + typed
 *       literals only), SqlGuard-checked, executed in the same ephemeral DuckDB sandbox as
 *       {@code /queries/{id}/run}, returning the same Result Set contract.</li>
 * </ul>
 *
 * <p>Fail-closed like {@link QueryRoutes}: write root unset → 503; unknown dataset → 404; a bad
 * spec, unsafe identifier, or guard/DuckDB failure → 422.
 */
final class BiRoutes implements RouteModule {

    /** The default row limit is the Space's {@link ListLimitSettings}; this is the hard cap a request cannot exceed. */
    private static final int MAX_LIMIT = ListLimitSettings.MAX_ROWS;

    @Override
    public void register(ApiContext api) {
        api.post("/bi/query", (e, m) -> biQuery(api, e, api.body(e)));
        // BI-8 template gallery: curated starter widget/dashboard sets, parameterized by dataset and
        // applied server-side into the component store (cross-space sharing stays BundleRoutes' job).
        api.get("/bi/templates", (e, m) -> BiTemplates.list());
        api.post("/bi/templates/([^/]+)/apply", ApiContext.withCapability("canAuthorWorkbench", (e, m) ->
                BiTemplates.apply(api, ApiContext.name(m), api.body(e))));
    }

    /** {@code POST /bi/query} — compile + execute one measure spec (see class doc). */
    private Object biQuery(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "BI query execution");
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));

        // 1. Parse + compile the spec (validated identifiers / typed literals only).
        MeasureCompiler.Spec spec;
        MeasureCompiler.Spec probe;
        MeasureCompiler.Compiled sql;   // the probe statement that is EXECUTED (cap + 1), its values bound
        String echoSql;   // the statement for the ORIGINAL spec (LIMIT = cap) that the response echoes, values rendered
        try {
            spec = MeasureCompiler.parse(body, ListLimitSettings.effectiveRows(writeRoot), MAX_LIMIT);
            // One row PAST the cap: the compiled statement carries its own LIMIT, so asking for exactly the cap
            // could never come back truncated (statistics.truncated was always false). QueryExecutor trims it.
            probe = spec.withLimit(spec.limit() + 1);
            sql = MeasureCompiler.compile(probe);
            echoSql = MeasureCompiler.render(spec);
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, bad.getMessage());
        }

        // 2. Defence in depth: the compiled text still passes the same guard as caller-authored SQL.
        List<Finding> findings = SqlGuard.check(sql.sql());
        if (!findings.isEmpty())
            return ApiContext.respondJson(ex, 422, Map.of(
                    "error", "compiled BI query failed the SQL safety check", "findings", findings));

        // 3. Resolve the dataset to its trusted relation and execute in the sandbox.
        final String datasetId = spec.dataset();
        Map<String, Object> dataset = store.get("dataset", datasetId)
                .map(ComponentRegistry.Component::content)
                .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "no dataset '" + datasetId + "'"));
        if (!ComponentAccess.canView(ex, dataset))   // R3: shared-away ⇒ indistinguishable from absence
            throw new ApiException(404, ErrorCodes.NOT_FOUND, "no dataset '" + datasetId + "'");
        String relationSql;
        try {
            relationSql = DatasetRelation.relationSql(dataset, api.dataRoot(), new ViewStore(writeRoot.resolve("views")));
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, bad.getMessage());
        }

        vetHiddenOrderColumns(spec, dataset, store, new ViewStore(writeRoot.resolve("views")));
        // BI-QUERY-MASKING-1: classified entity keys are masked in the rows unless the caller may reveal them.
        BiResultMasking masking = BiResultMasking.of(writeRoot, store, new ViewStore(writeRoot.resolve("views")),
                spec.dataset(), dataset, BiResultMasking.mayReveal(ex));
        String queryId = ApiContext.str(body, "query");
        // The saved query's own rendered text: the masking reads it (the WITH prefix below does not parse on its own).
        String boundQuery = queryId == null ? null : boundQueryText(ex, store, queryId, spec);
        String boundText = boundQuery == null ? null : "WITH \"" + BOUND + "\" AS (" + boundQuery + ") ";
        // The chart's cosmetic category order over a masked key is dropped (not refused) for a caller who cannot reveal.
        MeasureCompiler.Spec stripped = masking.stripCosmeticOrder(spec, boundQuery);
        if (stripped != spec) {
            spec = stripped;
            probe = spec.withLimit(spec.limit() + 1);
            sql = MeasureCompiler.compile(probe);
            echoSql = MeasureCompiler.render(spec);
        }

        // 4. QUERY-BOUND-WIDGET-1: a Widget bound to a saved Query aggregates over THAT query's result, so the
        //    query's own filtering reaches the Widget. Its text is read and rendered NOW (parameter defaults
        //    + session context), never baked at save time, and the whole statement is re-guarded.
        if (queryId != null) {
            String with = boundText;
            MeasureCompiler.Compiled over = MeasureCompiler.compile(overBound(probe));
            // The WITH carries no placeholders (the query text is rendered), so the Measure's parameters stay in order.
            sql = new MeasureCompiler.Compiled(with + over.sql(), over.params());
            echoSql = with + MeasureCompiler.render(overBound(spec));
            List<Finding> boundFindings = SqlGuard.check(sql.sql());
            if (!boundFindings.isEmpty())
                return ApiContext.respondJson(ex, 422, Map.of(
                        "error", "bound query '" + queryId + "' failed the SQL safety check", "findings", boundFindings));
        }

        masking.vet(spec, true, boundQuery);
        if (boundQuery != null) masking.vetAdHocSql(boundQuery);   // BI-MASKING-FREE-SQL-ORACLES-1: the bound text, per caller
        QueryExecutor.Request req = new QueryExecutor.Request(spec.dataset(), relationSql, sql.sql(),
                spec.limit(), 0, List.of(), List.of(), sql.params());
        try {
            QueryExecutor.Result raw = QueryExecutor.run(req);
            // only a raw groupBy column may keep the Space alias: not a Measure / ratio id, not a grain bucket (any case)
            Set<String> grained = new HashSet<>();
            if (spec.grains() != null) for (String g : spec.grains().keySet()) grained.add(g.toLowerCase(Locale.ROOT));
            Set<String> grantable = new HashSet<>();
            for (String g : spec.groupBy()) grantable.add(g.toLowerCase(Locale.ROOT));
            grantable.removeAll(grained);
            for (MeasureCompiler.Measure m : spec.measures()) grantable.remove(m.id().toLowerCase(Locale.ROOT));
            for (MeasureCompiler.Ratio rt : spec.ratios()) grantable.remove(rt.id().toLowerCase(Locale.ROOT));
            QueryExecutor.Result shown = masking.apply(raw, boundText == null ? spec.groupBy() : null, boundText, boundQuery, grantable);
            return response(shown, echoSql, masking.describe());
        } catch (SQLException e) {
            throw masking.scrub(ServerFaults.datasetRead("BI query failed", spec.dataset(), dataset, e, api));
        }
    }

    /**
     * CATEGORY-SORT-ORDER-1: a hidden-aggregate {@code orderBy} column must be a real Dataset column (when the Dataset
     * declares its columns) and must NOT be classified — ordering by a masked column is an inference channel
     * (the row order leaks the values' rank). Lineage that cannot be traced fails closed, as it does for masking.
     */
    private static void vetHiddenOrderColumns(MeasureCompiler.Spec spec, Map<String, Object> dataset,
                                              ComponentStore store, ViewStore views) {
        List<String> hidden = MeasureCompiler.hiddenOrderColumns(spec);
        if (hidden.isEmpty()) return;
        if (dataset.get("columns") instanceof List<?> cols && !cols.isEmpty()) {
            java.util.Set<String> known = new java.util.HashSet<>();
            for (Object o : cols)
                if (o instanceof Map<?, ?> c && c.get("name") != null)
                    known.add(String.valueOf(c.get("name")).trim().toLowerCase(java.util.Locale.ROOT));
            for (String h : hidden)
                if (!known.contains(h.toLowerCase(java.util.Locale.ROOT)))
                    throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED,
                            "orderBy column '" + h + "' is not a column of dataset '" + spec.dataset() + "'");
        }
        java.util.Set<String> sensitive = com.gamma.mask.EvidenceMasker.sensitiveColumns(store, views, spec.dataset());
        for (String h : hidden)
            if (sensitive.contains(h.toLowerCase(java.util.Locale.ROOT)) || sensitive.contains(com.gamma.mask.EvidenceMasker.UNKNOWN_LINEAGE))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED,
                        "orderBy column '" + h + "' is classified (or its lineage is untraceable); ordering by it would leak its values");
    }

    /** The reserved CTE name a bound query's result is aggregated from. */
    private static final String BOUND = "__bound_query";

    /**
     * The saved query {@code queryId}'s rendered text, which the route wraps as {@code WITH "__bound_query" AS (<text>) } to
     * put {@code spec} over its result ({@link #overBound} then compiles {@code SELECT … FROM "__bound_query"}). The query must
     * exist and be visible (else 404, like a dataset), be {@code type:sql} with text, and read the SAME
     * dataset the spec names (the dataset view it references is the one registered for the run).
     */
    private static String boundQueryText(HttpExchange ex, ComponentStore store, String queryId, MeasureCompiler.Spec spec) {
        Map<String, Object> query;
        try {
            query = store.get("query", queryId).map(ComponentRegistry.Component::content).orElse(null);
        } catch (IllegalArgumentException bad) {
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, bad.getMessage());
        }
        if (query == null || !ComponentAccess.canView(ex, query))
            throw new ApiException(404, ErrorCodes.NOT_FOUND, "no query '" + queryId + "' — the Widget's bound query was deleted or is not visible to you");
        String type = ApiContext.str(query, "type");
        if (type != null && !"sql".equalsIgnoreCase(type))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "bound query '" + queryId + "' is type '" + type + "'; only type:sql queries run server-side");
        String text = ApiContext.str(query, "text");
        if (text == null) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "bound query '" + queryId + "' has no 'text'");
        String queryDataset = ApiContext.str(query, "datasetId");
        if (!spec.dataset().equals(queryDataset))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "bound query '" + queryId + "' reads dataset '" + queryDataset
                    + "', not the Widget's dataset '" + spec.dataset() + "'");
        String resolved;
        try {
            resolved = Parameters.resolve(text, QueryRoutes.declaredParams(query), Map.of(),
                    Parameters.Context.of(ApiContext.actor(ex), null));
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, bad.getMessage());
        }
        return resolved.strip().replaceAll(";+$", "").strip();
    }

    /** {@code spec} re-pointed at the bound query's CTE. */
    private static MeasureCompiler.Spec overBound(MeasureCompiler.Spec spec) {
        return spec.withDataset(BOUND);
    }

    /** The Result Set contract (same shape as {@code /queries/{id}/run}) + the compiled SQL for transparency. */
    private static Object response(QueryExecutor.Result r, String sql, Map<String, Object> masking) {
        List<Map<String, Object>> columns = new ArrayList<>();
        for (ResultSetDescriptor.Column c : r.columns()) {
            Map<String, Object> col = new LinkedHashMap<>();
            col.put("name", c.name());
            col.put("type", c.type());
            col.put("role", c.role());
            col.put("cardinality", c.cardinality());
            columns.add(col);
        }
        Map<String, Object> resultSet = new LinkedHashMap<>();
        resultSet.put("columns", columns);
        resultSet.put("rowCount", r.rowCount());

        Map<String, Object> statistics = new LinkedHashMap<>();
        statistics.put("rowCount", r.rowCount());
        statistics.put("elapsedMs", r.elapsedMs());
        statistics.put("truncated", r.truncated());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("resultSet", resultSet);
        data.put("rows", r.rows());
        data.put("statistics", statistics);
        data.put("sql", sql);
        if (masking != null) data.put("masking", masking);
        return data;
    }
}
