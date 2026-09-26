package com.gamma.control;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.query.DatasetMeasureProbe;
import com.gamma.query.DatasetRelation;
import com.gamma.query.QueryExecutor;
import com.gamma.risk.RiskScoreModel;
import com.sun.net.httpserver.HttpExchange;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Risk Scores (ASSURE-RISK-SCORE-1, WS-22) — the read surface over the scores Dataset the {@code risk.score}
 * Job writes, plus the save-time Schema check {@code ComponentRoutes} runs for the {@code risk-score} kind.
 *
 * <h3>{@code GET /risk-scores/{model}/{entityKey}}</h3>
 * The entity's latest score with its factors. Gates, fail-closed:
 * <ol>
 *   <li>{@code canWorkIncidents} — a Risk Score is read while working the Incident it raised.</li>
 *   <li>Unknown model, a model the caller's data scopes exclude, a row the Enterprise PDP denies
 *       ({@link RowScope}), or no score for that entity → <b>404</b>, all indistinguishable (existence-hiding,
 *       the SEC-7d rule {@code ObjectRoutes} applies).</li>
 * </ol>
 * The entity key travels as a bound parameter — it is compared, never resolved as a path or spliced into SQL.
 */
final class RiskScoreRoutes implements RouteModule {

    static final String TYPE = "risk-score";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");
    private static final int MAX_KEY = 256;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public void register(ApiContext api) {
        api.get("/risk-scores/([^/]+)/([^/]+)", ApiContext.withCapability("canWorkIncidents",
                (e, m) -> latest(api, e, m.group(1), m.group(2))));
    }

    private static ApiException notFound(String model, String key) {
        return new ApiException(404, ErrorCodes.NOT_FOUND, "no Risk Score for '" + key + "' under model '" + model + "'");
    }

    private Object latest(ApiContext api, HttpExchange ex, String modelId, String entityKey) throws Exception {
        if (!SAFE_ID.matcher(modelId).matches() || entityKey.isEmpty() || entityKey.length() > MAX_KEY)
            throw notFound(modelId, entityKey);
        Path writeRoot = api.writeRoot();
        Path dataRoot = api.dataRoot();
        if (writeRoot == null || dataRoot == null) throw notFound(modelId, entityKey);
        ComponentStore registry = new ComponentStore(writeRoot.resolve("registry"));
        Optional<Map<String, Object>> content = registry.get(TYPE, modelId).map(ComponentRegistry.Component::content);
        if (content.isEmpty()) throw notFound(modelId, entityKey);
        RiskScoreModel model = RiskScoreModel.fromMap(modelId, content.get());

        // Data scopes (SEC-7d), stricter than objects: a data-scoped caller reads ONLY a model carrying a scope it
        // holds. An unscoped model is readable by unscoped callers alone — its evidence is raw source rows, and a
        // caller someone chose to scope has no business in rows nobody scoped.
        if (ApiContext.attr(ex, ApiContext.ATTR_SUBJECT) instanceof Subject s && s.scoped()
                && (model.dataScope() == null || !s.dataScopes().contains(model.dataScope())))
            throw notFound(modelId, entityKey);
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("id", modelId + "/" + entityKey);
        resource.put("model", modelId);
        resource.put("entityType", model.entityType());
        resource.put("entityKey", entityKey);
        if (model.dataScope() != null) resource.put("dataScope", model.dataScope());
        if (!RowScope.visible(ex, TYPE, resource)) throw notFound(modelId, entityKey);

        if (!Files.isDirectory(dataRoot.resolve(model.scoresDataset()))) throw notFound(modelId, entityKey);
        String relation = DatasetRelation.relationSql(Map.of("physicalRef", model.scoresDataset()), dataRoot, null);
        QueryExecutor.Result r = QueryExecutor.run(new QueryExecutor.Request("scores", relation,
                "SELECT * FROM \"scores\" WHERE \"model\" = ? AND \"entity_key\" = ? "
                        + "ORDER BY \"scored_at\" DESC, \"run_id\" DESC LIMIT 1",
                1, 0, List.of(), List.of(), List.of(modelId, entityKey)));
        if (r.rows().isEmpty()) throw notFound(modelId, entityKey);
        Map<String, Object> row = r.rows().get(0);

        RiskScoreMasking masking = RiskScoreMasking.of(registry, dataRoot, model);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", modelId);
        out.put("entityType", row.get("entity_type"));
        out.put("entityKey", masking.entityKey(String.valueOf(row.get("entity_key"))));
        out.put("score", row.get("score"));
        out.put("high", row.get("high"));
        out.put("highThreshold", model.highThreshold());
        out.put("modelVersion", row.get("model_version"));
        out.put("runId", row.get("run_id"));
        out.put("scoredAt", String.valueOf(row.get("scored_at")));
        out.put("factors", masking.factors(JSON.readValue(String.valueOf(row.get("factors")),
                new TypeReference<List<Map<String, Object>>>() {})));
        out.put("masking", masking.basis());
        return out;
    }

    /**
     * Save-time gate for a {@code risk-score} model that needs the Space: run by EVERY writer (the component route and
     * both bulk writers, through {@link ComponentRoutes#validateKind(ApiContext, String, String, Map)}). Fail closed;
     * every refusal is an {@link IllegalArgumentException} the caller maps to 422 (or a per-item bundle failure).
     * <ol>
     *   <li>Every column a factor names (key, measure field, filter fields, evidence) is in its Dataset's Schema.
     *       A Dataset whose Schema cannot be read refuses the save, like the Alert Rule {@code by} check.</li>
     *   <li>The derived scores names ({@code risk_scores_<id>}, {@code …_latest}) collide with nothing: no directory
     *       under the data root this model did not create, and no Dataset of that id over some other store.</li>
     * </ol>
     */
    static void requireStorable(ApiContext api, RiskScoreModel model) {
        Path writeRoot = api.writeRoot();
        if (writeRoot == null) throw new IllegalArgumentException("risk-score write needs a write root");
        DatasetMeasureProbe probe = new DatasetMeasureProbe(() -> writeRoot, api::dataRoot);
        for (Map.Entry<String, Set<String>> e : model.referencedColumns().entrySet()) {
            List<String> columns;
            try {
                columns = probe.columns(e.getKey());
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("risk-score factors cannot be checked against the Schema of "
                        + "dataset '" + e.getKey() + "': " + ex.getMessage(), ex);
            }
            List<String> missing = e.getValue().stream().filter(c -> !columns.contains(c)).toList();
            if (!missing.isEmpty())
                throw new IllegalArgumentException("risk-score column(s) " + missing
                        + " are not in the Schema of dataset '" + e.getKey() + "' (have: " + columns + ")");
        }
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        Path dataRoot = api.dataRoot();
        for (String out : List.of(model.scoresDataset(), model.latestDataset())) {
            if (dataRoot != null && Files.exists(dataRoot.resolve(out))
                    && !com.gamma.risk.RiskScoreEvaluator.ownedBy(dataRoot.resolve(out), model.id()))
                throw new IllegalArgumentException("risk-score '" + model.id() + "' would write '" + out
                        + "', which already exists under the data root and is not this model's output");
            Object ref = store.get("dataset", out).map(ComponentRegistry.Component::content)
                    .map(c -> c.get("physicalRef")).orElse(null);
            if (store.exists("dataset", out) && !out.equals(String.valueOf(ref)))
                throw new IllegalArgumentException("risk-score '" + model.id() + "' would write '" + out
                        + "', which is the id of a Dataset over another store");
        }
    }
}
