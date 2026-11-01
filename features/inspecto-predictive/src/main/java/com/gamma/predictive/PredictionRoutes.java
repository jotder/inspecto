package com.gamma.predictive;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.RowScope;
import com.gamma.control.AuditReadMasking;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.query.DatasetRelation;
import com.gamma.query.QueryExecutor;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;
import com.sun.net.httpserver.HttpExchange;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Predictions (PREDICTIVE-SURFACE-1, first slice) — the read surface over the stores the {@code predict.segment},
 * {@code predict.churn} and {@code predict.forecast} Jobs write, modelled on the Anomaly Score read route.
 *
 * <h3>{@code GET /predictions/{kind}/{model}/{entityKey}}</h3>
 * {@code kind} is the model's component kind ({@code segmentation-model}, {@code churn-model}, {@code forecast-model});
 * for a forecast the key is the series key. A Customer Segment or Churn Score returns the latest row plus its last
 * {@value #HISTORY_RUNS} runs (newest first); a forecast returns every step of the latest run in step order. Gates,
 * fail-closed:
 * <ol>
 *   <li>{@code canWorkIncidents} — the read gate Anomaly and Risk Scores use (OD-8: reuse existing gates).</li>
 *   <li>An unknown kind or model, a model that no longer parses, a row the Advanced PDP denies ({@link RowScope}), or
 *       no row for that key → <b>404</b>, all indistinguishable (existence-hiding).</li>
 * </ol>
 * The key travels as a bound parameter and is echoed as the Space {@code masked:} token ({@code keyMasked: true})
 * unless the caller holds {@code canRevealLinkEntities} (design §4.4, the helper scores use). Every row carries its
 * stored {@code explanation} parsed, so a client can recompute it.
 */
public final class PredictionRoutes implements RouteModule {

    /** Runs returned for a segment or churn history, newest first (the first is the latest). */
    static final int HISTORY_RUNS = 30;
    /** The largest horizon a {@code forecast-model} accepts — the most rows one run writes per series. */
    static final int MAX_HORIZON = 90;
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_]*");
    private static final int MAX_KEY = 256;
    private static final ObjectMapper JSON = new ObjectMapper();
    /** The contract head and tail; everything else in a row is the kind's own. */
    private static final Set<String> FRAME = Set.of("model", "entity_type", "entity_key", "model_version", "run_id",
            "scored_at", "as_of");

    /** A stored model reduced to what the read needs. */
    private record Target(String entityType, String historyDataset, boolean forecast, int rowsPerRun) {}

    @Override
    public Set<String> featureIds() { return Set.of("predictive"); }

    @Override
    public void register(ApiContext api) {
        api.get("/predictions/([^/]+)/([^/]+)/([^/]+)", ApiContext.withCapability("canWorkIncidents",
                (e, m) -> read(api, e, m.group(1), m.group(2), m.group(3))));
    }

    private static ApiException notFound(String kind, String model, String key) {
        return new ApiException(404, ErrorCodes.NOT_FOUND,
                "no prediction for '" + key + "' under " + kind + " '" + model + "'");
    }

    /** Parse the stored model of {@code kind}; empty for an unknown kind or a model that no longer parses. */
    private static Optional<Target> target(String kind, String id, Map<String, Object> content) {
        try {
            return switch (kind) {
                case SegmentationModel.KIND -> {
                    SegmentationModel m = SegmentationModel.fromMap(id, content);
                    yield Optional.of(new Target(m.entityType(), m.segmentsDataset(), false, HISTORY_RUNS));
                }
                case ChurnModel.KIND -> {
                    ChurnModel m = ChurnModel.fromMap(id, content);
                    yield Optional.of(new Target(m.entityType(), m.scoresDataset(), false, HISTORY_RUNS));
                }
                case ForecastModel.KIND -> {
                    ForecastModel m = ForecastModel.fromMap(id, content);
                    // the horizon ceiling, not the current horizon: a run made before the horizon was lowered keeps all its steps
                    yield Optional.of(new Target(m.entityType(), m.forecastsDataset(), true, MAX_HORIZON));
                }
                default -> Optional.empty();
            };
        } catch (IllegalArgumentException e) {
            return Optional.empty();   // a stored model that no longer parses is no prediction
        }
    }

    private Object read(ApiContext api, HttpExchange ex, String kind, String modelId, String entityKey) throws Exception {
        if (!SAFE_ID.matcher(modelId).matches() || entityKey.isEmpty() || entityKey.length() > MAX_KEY)
            throw notFound(kind, modelId, entityKey);
        Path writeRoot = api.writeRoot();
        Path dataRoot = api.dataRoot();
        if (writeRoot == null || dataRoot == null) throw notFound(kind, modelId, entityKey);
        ComponentStore registry = new ComponentStore(writeRoot.resolve("registry"));
        Target t = (switch (kind) {
            case SegmentationModel.KIND, ChurnModel.KIND, ForecastModel.KIND ->
                    registry.get(kind, modelId).map(ComponentRegistry.Component::content);
            default -> Optional.<Map<String, Object>>empty();
        }).flatMap(c -> target(kind, modelId, c)).orElseThrow(() -> notFound(kind, modelId, entityKey));
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("id", modelId + "/" + entityKey);
        resource.put("model", modelId);
        resource.put("entityType", t.entityType());
        resource.put("entityKey", entityKey);
        if (!RowScope.visible(ex, kind, resource)) throw notFound(kind, modelId, entityKey);

        if (!Files.isDirectory(dataRoot.resolve(t.historyDataset()))) throw notFound(kind, modelId, entityKey);
        String relation = DatasetRelation.relationSql(Map.of("physicalRef", t.historyDataset()), dataRoot, null);
        QueryExecutor.Result r = QueryExecutor.run(new QueryExecutor.Request("p", relation,
                "SELECT * FROM \"p\" WHERE \"model\" = ? AND \"entity_key\" = ? ORDER BY \"scored_at\" DESC, "
                        + "\"run_id\" DESC" + (t.forecast() ? ", \"step\" ASC NULLS FIRST" : "")
                        + " LIMIT " + t.rowsPerRun(),
                t.rowsPerRun(), 0, List.of(), List.of(), List.of(modelId, entityKey)));
        if (r.rows().isEmpty()) throw notFound(kind, modelId, entityKey);
        Map<String, Object> head = r.rows().get(0);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind);
        out.put("model", modelId);
        out.put("entityType", head.get("entity_type"));
        boolean reveal = ApiContext.subject(ex)
                .map(s -> s.capabilities().contains(AuditReadMasking.UNMASK_CAPABILITY)).orElse(true);
        com.gamma.mask.EntityKeyMasking.put(out, writeRoot, String.valueOf(head.get("entity_key")), reveal);
        out.put("asOf", String.valueOf(head.get("as_of")));
        out.put("modelVersion", head.get("model_version"));
        out.put("runId", head.get("run_id"));
        out.put("scoredAt", String.valueOf(head.get("scored_at")));
        if (t.forecast()) {
            List<Map<String, Object>> points = new ArrayList<>();
            for (Map<String, Object> row : r.rows())
                if (head.get("run_id").equals(row.get("run_id"))) points.add(own(row));
            out.put("points", points);
        } else {
            out.putAll(own(head));
            List<Map<String, Object>> history = new ArrayList<>();
            for (Map<String, Object> row : r.rows()) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("asOf", String.valueOf(row.get("as_of")));
                p.put("runId", row.get("run_id"));
                if (row.containsKey("segment")) p.put("segment", row.get("segment"));
                if (row.containsKey("score")) p.put("score", row.get("score"));
                if (row.containsKey("band")) p.put("band", row.get("band"));
                history.add(p);
            }
            out.put("history", history);
        }
        return out;
    }

    /** A row's own columns in camelCase, temporal values as text and the explanation parsed. */
    private static Map<String, Object> own(Map<String, Object> row) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> c : row.entrySet()) {
            if (FRAME.contains(c.getKey())) continue;
            Object v = c.getValue();
            if ("explanation".equals(c.getKey()) && v != null)
                v = JSON.readValue(String.valueOf(v), new TypeReference<Map<String, Object>>() {});
            else if (v instanceof java.time.temporal.Temporal || v instanceof java.util.Date) v = String.valueOf(v);
            out.put(camel(c.getKey()), v);
        }
        return out;
    }

    static String camel(String column) {
        StringBuilder b = new StringBuilder();
        boolean up = false;
        for (char ch : column.toLowerCase(Locale.ROOT).toCharArray()) {
            if (ch == '_') { up = true; continue; }
            b.append(up ? Character.toUpperCase(ch) : ch);
            up = false;
        }
        return b.toString();
    }
}
