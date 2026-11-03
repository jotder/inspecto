package com.gamma.la.api;

import com.gamma.access.WriteGates;
import com.gamma.audit.Event;
import com.gamma.audit.EventSink;
import com.gamma.entitystore.IndustryPack;
import com.gamma.entitystore.LinkAnalysisSettings;
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
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.gamma.util.SqlIdent.q;

/**
 * Preset queries over a Dataset (LA-PRESET-QUERIES-1, {@link PresetQueries}) - Basic's way to ask the questions an
 * industry already asks, without writing a query.
 *
 * <ul>
 *   <li>{@code GET /inv/presets?dataset=} - {@code {dataset, pack, roles, presets}}: the presets the Space's Industry
 *       pack offers whose roles this Dataset maps, with their parameters; {@code roles} says which column plays which
 *       role, so the screen can say why a preset is missing.</li>
 *   <li>{@code POST /inv/presets/{id}/run {dataset, params}} - {@code {preset, columns, rows, count, truncated, masking}}.
 *       Read-shaped: it persists nothing.</li>
 * </ul>
 *
 * <p>Gate order: write root 503 → body 422 → Dataset 404 (R3 via {@link InvRoutes#relationFor}) → preset 404 → a role
 * the Dataset does not map, an unknown or bad parameter 422. Entity ids leave as aliases when the Space masks them
 * (exactly as exploration does), and an {@code entity} parameter may be an alias this server served. At most
 * {@link PresetQueries#MAX_ROWS} rows (and the Space's entity fence), under the Space's statement timeout; every run is
 * audited as {@code LINK_PRESET_RUN}.
 */
public final class PresetRoutes implements RouteModule {

    private static final ZoneId UTC = ZoneId.of("UTC");   // DuckDB refuses "Z"

    /** Gated by the geoLink feature: a Space whose modules.toon disables it answers 404 MODULE_DISABLED here. */
    @Override
    public java.util.Set<String> featureIds() {
        return java.util.Set.of("geoLink");
    }

    @Override
    public void register(ApiContext api) {
        api.get("/inv/presets", (e, m) -> list(api, e));
        api.post("/inv/presets/([^/]+)/run", (e, m) -> run(api, e, m.group(1), api.body(e)));
    }

    /** What a Dataset is, for presets: its relation, its roles, and the Space's pack. */
    private record Target(String datasetId, String relationSql, PresetQueries.Roles roles, String packId) {}

    private static Target target(ApiContext api, HttpExchange ex, Path writeRoot, String datasetId) {
        String relationSql = InvRoutes.relationFor(api, ex, writeRoot, datasetId);
        Map<String, Object> dataset = DatasetProviders.require().dataset(writeRoot, datasetId).orElseThrow();
        List<DatasetProvider.Column> columns;
        try {
            columns = DatasetProviders.require().run(new DatasetProvider.Request(datasetId, relationSql,
                    "SELECT * FROM " + q(datasetId), 0, 0, List.of(), List.of())).columns();
        } catch (Exception unusable) {
            throw DatasetReadFault.of("cannot read the columns of dataset '" + datasetId + "'", datasetId, writeRoot, unusable);
        }
        LinkAnalysisSettings settings = LinkAnalysisSettings.forRoot(writeRoot);
        IndustryPack pack = settings.activePack();
        return new Target(datasetId, relationSql,
                PresetQueries.roles(dataset, columns, settings.effectiveEntityTypes(), pack), pack == null ? null : pack.id());
    }

    private static String datasetOf(Object raw) {
        if (!(raw instanceof String s) || s.isBlank())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'dataset' is required");
        return s.trim();
    }

    private Object list(ApiContext api, HttpExchange ex) {
        Path writeRoot = WriteGates.requireWriteRoot(api, "preset queries");
        Target t = target(api, ex, writeRoot, datasetOf(ApiContext.query(ex, "dataset")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataset", t.datasetId());
        out.put("pack", t.packId());
        out.put("roles", t.roles().toMap());
        out.put("presets", PresetQueries.forPack(t.packId()).stream().filter(p -> p.offeredFor(t.roles()))
                .map(PresetQueries.Preset::toMap).toList());
        return out;
    }

    private Object run(ApiContext api, HttpExchange ex, String presetId, Map<String, Object> body) {
        Path writeRoot = WriteGates.requireWriteRoot(api, "preset query");
        if (body == null) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "a JSON body is required");
        String datasetId = datasetOf(body.get("dataset"));
        Map<String, String> given = new LinkedHashMap<>();
        if (body.get("params") != null) {
            if (!(body.get("params") instanceof Map<?, ?> ps))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'params' must be an object");
            ps.forEach((k, v) -> given.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
        }
        Target t = target(api, ex, writeRoot, datasetId);
        PresetQueries.Preset p = PresetQueries.find(t.packId(), presetId);
        if (p == null) throw new ApiException(404, ErrorCodes.NOT_FOUND, "no preset query '" + presetId + "' in this Space");
        List<String> missing = p.needs().stream().filter(n -> t.roles().get(n) == null).toList();
        if (!missing.isEmpty())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + p.label() + "' needs the Dataset to map "
                    + missing + "; '" + datasetId + "' does not");
        for (String k : given.keySet())
            if (p.param(k) == null)
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + p.label() + "' has no parameter '" + k + "'");

        ValueMeasures.Fences fences = ValueMeasures.Fences.of(writeRoot);
        ExplorationMasking mask = ExplorationMasking.of(writeRoot, datasetId,
                Map.of("sourceCol", t.roles().from(), "targetCol", t.roles().to()));
        List<PresetQueries.Column> columns;
        List<Map<String, Object>> rows;
        boolean truncated;
        try {
            if (p.measure() != null) {
                Map<String, Object> block = new LinkedHashMap<>();
                block.put("name", p.measure());
                block.put("valueCol", t.roles().amount());
                block.put("timeCol", t.roles().time());
                for (PresetQueries.Param param : p.params()) block.put(param.name(), PresetQueries.value(p, given, param.name()));
                ValueMeasures.Spec spec = ValueMeasures.parse(block, false, fences);
                ValueMeasures.Result r = ValueMeasures.evaluate(datasetId, t.relationSql(), t.roles().from(), t.roles().to(),
                        t.roles().kind(), spec, null, fences);
                columns = List.of(new PresetQueries.Column("entity", "Entity", true),
                        new PresetQueries.Column("hours", "Median hours until it leaves", false),
                        new PresetQueries.Column("legs", "Links out", false),
                        new PresetQueries.Column("inbound", "Received", false));
                int cap = Math.min(PresetQueries.MAX_ROWS, fences.maxEntities());
                rows = r.entities().size() > cap ? r.entities().subList(0, cap) : r.entities();
                truncated = r.truncated() || r.entities().size() > cap;
            } else {
                PresetQueries.Aggregate a = p.aggregate();
                String entity = a.entityParam() == null ? null : PresetQueries.value(p, given, a.entityParam());
                if (a.entityParam() != null && entity == null)
                    throw new IllegalArgumentException("'" + a.entityParam() + "' is required");
                if (entity != null) entity = mask.in(entity);
                Double min = a.minParam() == null ? null : PresetQueries.number(p, given, a.minParam());
                columns = PresetQueries.columns(a, t.roles());
                List<String> binds = new ArrayList<>();
                String sql = PresetQueries.aggregateSql(datasetId, a, t.roles(), columns, entity,
                        PresetQueries.kinds(PresetQueries.value(p, given, a.kindsParam())), min, binds);
                int limit = Math.min(PresetQueries.top(p, given), fences.maxEntities());
                DatasetProvider.Result r = DatasetProviders.require().run(new DatasetProvider.Request(datasetId,
                        t.relationSql(), sql, limit, 0, List.of(), List.of(), binds),
                        SqlSandboxPolicy.withCaps(null, 0, fences.timeoutSeconds()), UTC);
                rows = r.rows();
                truncated = r.truncated();
            }
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, bad.getMessage());
        } catch (SQLException | java.io.IOException e) {
            throw DatasetReadFault.of("preset query failed", datasetId, writeRoot, e);
        }

        List<Map<String, Object>> shown = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> c = new LinkedHashMap<>();
            for (PresetQueries.Column col : columns)
                c.put(col.id(), col.entity() ? mask.out(row.get(col.id())) : row.get(col.id()));
            shown.add(c);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("preset", p.toMap());
        out.put("dataset", datasetId);
        out.put("columns", columns.stream().map(PresetQueries.Column::toMap).toList());
        out.put("rows", shown);
        out.put("count", shown.size());
        out.put("truncated", truncated);
        out.put("masking", mask.describe());
        try {
            EventSink.current().emit(Event.builder(LinkEventTypes.LINK_PRESET_RUN).source("inv")
                    .message("link.preset.run — " + p.id() + " over " + datasetId)
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action("link.preset.run").actionCategory("analysis")
                    .attr("dataset", datasetId).attr("preset", p.id())
                    .attr("rows", shown.size()).attr("truncated", truncated));
        } catch (RuntimeException ignored) {
            // best effort (LA-04) — the read already succeeded
        }
        return out;
    }
}
