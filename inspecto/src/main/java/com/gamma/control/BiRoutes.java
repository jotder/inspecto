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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.gamma.access.ComponentAccess;
import com.gamma.access.WriteGates;

/**
 * The semantic / headless BI API (BI-7): spec-based aggregation over Datasets without authoring a
 * {@code query} component — the backend twin of the UI viz layer's QuerySpec seam, so an external
 * consumer (or the UI itself, later) can evaluate measures server-side over the at-rest data.
 *
 * <ul>
 *   <li>{@code POST /bi/query} — {@code {dataset, measures[{agg,field}], groupBy[], filters[],
 *       orderBy[], limit}} → compiled by {@link MeasureCompiler} (validated identifiers + typed
 *       literals only), SqlGuard-checked, executed in the same ephemeral DuckDB sandbox as
 *       {@code /queries/{id}/run}, returning the same Result Set contract.</li>
 * </ul>
 *
 * <p>Fail-closed like {@link QueryRoutes}: write root unset → 503; unknown dataset → 404; a bad
 * spec, unsafe identifier, or guard/DuckDB failure → 422.
 */
final class BiRoutes implements RouteModule {

    private static final int DEFAULT_LIMIT = 500;
    private static final int MAX_LIMIT = 10_000;

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
            spec = MeasureCompiler.parse(body, DEFAULT_LIMIT, MAX_LIMIT);
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
        Map<String, Object> dataset = store.get("dataset", spec.dataset())
                .map(ComponentRegistry.Component::content)
                .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "no dataset '" + spec.dataset() + "'"));
        if (!ComponentAccess.canView(ex, dataset))   // R3: shared-away ⇒ indistinguishable from absence
            throw new ApiException(404, ErrorCodes.NOT_FOUND, "no dataset '" + spec.dataset() + "'");
        String relationSql;
        try {
            relationSql = DatasetRelation.relationSql(dataset, api.dataRoot(), new ViewStore(writeRoot.resolve("views")));
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, bad.getMessage());
        }

        // 4. QUERY-BOUND-WIDGET-1: a Widget bound to a saved Query aggregates over THAT query's result, so the
        //    query's own filtering reaches the Widget. Its text is read and rendered NOW (parameter defaults
        //    + session context), never baked at save time, and the whole statement is re-guarded.
        String queryId = ApiContext.str(body, "query");
        if (queryId != null) {
            String with = boundQueryWith(ex, store, queryId, spec);
            MeasureCompiler.Compiled over = MeasureCompiler.compile(overBound(probe));
            // The WITH carries no placeholders (the query text is rendered), so the Measure's parameters stay in order.
            sql = new MeasureCompiler.Compiled(with + over.sql(), over.params());
            echoSql = with + MeasureCompiler.render(overBound(spec));
            List<Finding> boundFindings = SqlGuard.check(sql.sql());
            if (!boundFindings.isEmpty())
                return ApiContext.respondJson(ex, 422, Map.of(
                        "error", "bound query '" + queryId + "' failed the SQL safety check", "findings", boundFindings));
        }

        QueryExecutor.Request req = new QueryExecutor.Request(spec.dataset(), relationSql, sql.sql(),
                spec.limit(), 0, List.of(), List.of(), sql.params());
        try {
            return response(QueryExecutor.run(req), echoSql);
        } catch (SQLException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "BI query failed: "
                    + ServerFaults.sqlMessage(e.getMessage(), api));
        }
    }

    /** The reserved CTE name a bound query's result is aggregated from. */
    private static final String BOUND = "__bound_query";

    /**
     * The {@code WITH "__bound_query" AS (<rendered query text>) } prefix that puts {@code spec} over the saved
     * query {@code queryId}'s result ({@link #overBound} then compiles {@code SELECT … FROM "__bound_query"}). The query must
     * exist and be visible (else 404, like a dataset), be {@code type:sql} with text, and read the SAME
     * dataset the spec names (the dataset view it references is the one registered for the run).
     */
    private static String boundQueryWith(HttpExchange ex, ComponentStore store, String queryId, MeasureCompiler.Spec spec) {
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
        resolved = resolved.strip().replaceAll(";+$", "").strip();
        return "WITH \"" + BOUND + "\" AS (" + resolved + ") ";
    }

    /** {@code spec} re-pointed at the bound query's CTE. */
    private static MeasureCompiler.Spec overBound(MeasureCompiler.Spec spec) {
        return new MeasureCompiler.Spec(BOUND, spec.measures(), spec.groupBy(),
                spec.grains(), spec.filters(), spec.orderBy(), spec.limit(), spec.when());
    }

    /** The Result Set contract (same shape as {@code /queries/{id}/run}) + the compiled SQL for transparency. */
    private static Object response(QueryExecutor.Result r, String sql) {
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
        return data;
    }
}
