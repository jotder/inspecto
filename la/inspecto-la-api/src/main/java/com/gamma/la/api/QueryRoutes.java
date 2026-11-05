package com.gamma.la.api;

import com.gamma.access.WriteGates;
import com.gamma.audit.Event;
import com.gamma.audit.EventSink;
import com.gamma.la.core.DatasetProvider;
import com.gamma.la.core.DatasetProviders;
import com.gamma.la.core.LinkEventTypes;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;
import com.gamma.sql.SqlSandboxPolicy;
import com.sun.net.httpserver.HttpExchange;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.gamma.util.SqlIdent.q;

/**
 * {@code POST /inv/datasets/{id}/query {query}} (LA-QUERY-BUILDER-1): runs one {@link QueryBuilder} query over a Dataset - the
 * builder's own question, or a Space preset saved from it (the UI sends the stored query). Answers like a preset run:
 * {@code {dataset, query, columns, rows, count, truncated, masking}}. Read-shaped: persists nothing. Basic and up.
 *
 * <p>Gate order: write root 503 → body 422 → Dataset 404 (R3) → a query the Dataset's roles do not support 422 (an unknown key,
 * a column of the wrong role, a bad value, an alias this server never minted). Entity ids leave as exploration aliases when the
 * Space masks; a From / To value may be such an alias. At most {@code top} rows (and the Space's entity fence), under the Space's
 * statement timeout (a timeout is a 422 sentence, as for presets). Audited as {@code LINK_QUERY_RUN}, without entity values.
 */
public final class QueryRoutes implements RouteModule {

    private static final ZoneId UTC = ZoneId.of("UTC");   // DuckDB refuses "Z"

    @Override
    public Set<String> featureIds() {
        return Set.of("geoLink");
    }

    @Override
    public void register(ApiContext api) {
        api.post("/inv/datasets/([^/]+)/query", (e, m) -> run(api, e, m.group(1), api.body(e)));
    }

    /** What the builder offers over one Dataset: its role columns. Shared with {@code GET /inv/presets}. */
    static Map<String, Object> columnsOf(PresetRoutes.Target t, Map<String, Object> dataset, List<DatasetProvider.Column> real) {
        return QueryBuilder.columns(dataset, real, t.roles()).toMap(DatasetDiscovery.measures(dataset, real, t.roles()));
    }

    private static Object run(ApiContext api, HttpExchange ex, String datasetId, Map<String, Object> body) {
        Path writeRoot = WriteGates.requireWriteRoot(api, "query");
        if (body == null || !body.containsKey("query"))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "a JSON body {query} is required");
        PresetRoutes.Target t = PresetRoutes.target(api, ex, writeRoot, datasetId);
        Map<String, Object> dataset = DatasetProviders.require().dataset(writeRoot, datasetId).orElseThrow();
        List<DatasetProvider.Column> real = realColumns(t, writeRoot);
        ValueMeasures.Fences fences = ValueMeasures.Fences.of(writeRoot);
        ExplorationMasking mask = ExplorationMasking.of(writeRoot, datasetId,
                Map.of("sourceCol", t.roles().from() == null ? "" : t.roles().from(), "targetCol", t.roles().to() == null ? "" : t.roles().to()));
        QueryBuilder.Query query;
        List<String> binds = new ArrayList<>();
        String sql;
        try {
            query = QueryBuilder.parse(body.get("query"), t.roles(), QueryBuilder.columns(dataset, real, t.roles()));
            sql = QueryBuilder.sql(datasetId, query, t.roles(), mask.in(query.from()), mask.in(query.to()), binds);
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, bad.getMessage());
        }
        List<PresetQueries.Column> columns = QueryBuilder.resultColumns(query);
        int limit = Math.min(query.top(), fences.maxEntities());
        DatasetProvider.Result r;
        try {
            r = DatasetProviders.require().run(new DatasetProvider.Request(datasetId, t.relationSql(), sql, limit, 0, List.of(), List.of(),
                    binds), SqlSandboxPolicy.withCaps(null, 0, fences.timeoutSeconds()), UTC);
        } catch (Exception e) {
            if (e instanceof java.sql.SQLTimeoutException || String.valueOf(e.getMessage()).startsWith("INTERRUPT Error"))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "This question took longer than this Space allows ("
                        + fences.timeoutSeconds() + " s) on '" + datasetId + "'. Narrow it: a shorter window, one entity, or fewer rows.");
            throw DatasetReadFault.of("query failed", datasetId, writeRoot, e);
        }
        List<Map<String, Object>> shown = new ArrayList<>();
        for (Map<String, Object> row : r.rows()) {
            Map<String, Object> c = new LinkedHashMap<>();
            for (PresetQueries.Column col : columns) {
                Object v = row.get(col.id());
                c.put(col.id(), col.entity() ? mask.out(v) : "total".equals(col.id()) ? DatasetDiscovery.number(v) : v);
            }
            shown.add(c);
        }
        Map<String, Object> echoed = QueryBuilder.toMap(query);
        if (query.from() != null) echoed.put("from", mask.out(mask.in(query.from())));   // never echo a raw id the Space masks
        if (query.to() != null) echoed.put("to", mask.out(mask.in(query.to())));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataset", datasetId);
        out.put("query", echoed);
        out.put("columns", columns.stream().map(PresetQueries.Column::toMap).toList());
        out.put("rows", shown);
        out.put("count", shown.size());
        out.put("truncated", r.truncated());
        out.put("masking", mask.describe());
        try {
            EventSink.current().emit(Event.builder(LinkEventTypes.LINK_QUERY_RUN).source("inv")
                    .message("link.query.run — " + datasetId)
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action("link.query.run").actionCategory("analysis")
                    .attr("dataset", datasetId).attr("by", query.by()).attr("rank", query.rank())
                    .attr("rows", shown.size()).attr("truncated", r.truncated()));
        } catch (RuntimeException ignored) {
            // best effort (LA-04) - the read already succeeded
        }
        return out;
    }

    /** The relation's real columns (a zero-row probe). */
    static List<DatasetProvider.Column> realColumns(PresetRoutes.Target t, Path writeRoot) {
        try {
            return DatasetProviders.require().run(new DatasetProvider.Request(t.datasetId(), t.relationSql(),
                    "SELECT * FROM " + q(t.datasetId()), 0, 0, List.of(), List.of())).columns();
        } catch (Exception unusable) {
            throw DatasetReadFault.of("cannot read the columns of dataset '" + t.datasetId() + "'", t.datasetId(), writeRoot, unusable);
        }
    }
}
