package com.gamma.anomaly;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.RowScope;
import com.gamma.control.AuditReadMasking;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import com.gamma.query.DatasetRelation;
import com.gamma.query.QueryExecutor;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.auth.Subject;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;
import com.gamma.sql.SqlSandboxPolicy;
import com.sun.net.httpserver.HttpExchange;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Anomaly Scores (ANOMALY-DETECTION-1 slice S4, design §7 / §10) — the read surface over the scores Datasets the
 * {@code anomaly.score} Job writes, modelled on the Risk Score routes.
 *
 * <h3>{@code GET /anomaly-scores/{model}/{entityKey}}</h3>
 * The entity's latest score with its per-feature explanation, plus its last {@value #HISTORY_RUNS} runs (newest
 * first) for the sparkline. Gates, fail-closed:
 * <ol>
 *   <li>{@code canWorkIncidents} — an Anomaly Score is read while working the Incident its Alert Rule raised.</li>
 *   <li>Unknown model, a model the caller's data scopes exclude (SEC-7d: a data-scoped caller reads only a model
 *       carrying a scope it holds), a row the Enterprise PDP denies ({@link RowScope}), or no score for that entity
 *       → <b>404</b>, all indistinguishable (existence-hiding).</li>
 * </ol>
 * The entity key travels as a bound parameter. It is echoed as the Space {@code masked:} token ({@code keyMasked:
 * true}) unless the caller holds {@code canRevealLinkEntities} (D-P8 mask on read, the helper Risk Scores use).
 *
 * <h3>{@code POST /anomaly-scores/preview}</h3>
 * Score ONE entity under a saved model or unsaved content and write NOTHING; see {@link #preview}.
 */
public final class AnomalyScoreRoutes implements RouteModule {

    static final String TYPE = AnomalyModel.KIND;
    /** Runs returned for the sparkline, newest first (the first is the latest score). */
    static final int HISTORY_RUNS = 30;
    /** The preview's statement fence (design §11): 512 MB, 2 threads, 10 s. */
    static final SqlSandboxPolicy PREVIEW_POLICY = SqlSandboxPolicy.withCaps("512MB", 2, 10);
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_]*");
    private static final int MAX_KEY = 256;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public Set<String> featureIds() { return Set.of("anomaly"); }

    @Override
    public void register(ApiContext api) {
        api.get("/anomaly-scores/([^/]+)/([^/]+)", ApiContext.withCapability("canWorkIncidents",
                (e, m) -> latest(api, e, m.group(1), m.group(2))));
        api.post("/anomaly-scores/preview", ApiContext.withCapability("canWorkIncidents",
                (e, m) -> preview(api, e, api.body(e))));
    }

    private static ApiException notFound(String model, String key) {
        return new ApiException(404, ErrorCodes.NOT_FOUND, "no Anomaly Score for '" + key + "' under model '" + model + "'");
    }

    /** SEC-7d: a data-scoped caller reaches only a model carrying a scope it holds. */
    private static boolean outOfScope(HttpExchange ex, AnomalyModel model) {
        return ApiContext.attr(ex, ApiContext.ATTR_SUBJECT) instanceof Subject s && s.scoped()
                && (model.dataScope() == null || !s.dataScopes().contains(model.dataScope()));
    }

    /** D-P8 mask on read: raw for a caller holding the reveal capability (or no Subject — auth off), else the token. */
    private static void putEntityKey(HttpExchange ex, Path writeRoot, Map<String, Object> out, String entityKey) {
        boolean reveal = ApiContext.subject(ex)
                .map(s -> s.capabilities().contains(AuditReadMasking.UNMASK_CAPABILITY)).orElse(true);
        com.gamma.mask.EntityKeyMasking.put(out, writeRoot, entityKey, reveal);
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
        AnomalyModel model;
        try {
            model = AnomalyModel.fromMap(modelId, content.get());
        } catch (IllegalArgumentException e) {
            throw notFound(modelId, entityKey);   // a stored model that no longer parses is no score
        }
        if (outOfScope(ex, model)) throw notFound(modelId, entityKey);
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
                        + "ORDER BY \"scored_at\" DESC, \"run_id\" DESC LIMIT " + HISTORY_RUNS,
                HISTORY_RUNS, 0, List.of(), List.of(), List.of(modelId, entityKey)));
        if (r.rows().isEmpty()) throw notFound(modelId, entityKey);
        Map<String, Object> row = r.rows().get(0);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", modelId);
        out.put("entityType", row.get("entity_type"));
        putEntityKey(ex, writeRoot, out, String.valueOf(row.get("entity_key")));
        out.put("score", row.get("score"));
        out.put("band", row.get("band"));
        out.put("raw", row.get("raw"));
        out.put("elevatedThreshold", model.elevatedThreshold());
        out.put("highThreshold", model.highThreshold());
        out.put("periodStart", String.valueOf(row.get("period_start")));
        out.put("insufficientCount", row.get("insufficient_count"));
        out.put("modelVersion", row.get("model_version"));
        out.put("runId", row.get("run_id"));
        out.put("scoredAt", String.valueOf(row.get("scored_at")));
        out.put("features", JSON.readValue(String.valueOf(row.get("features")),
                new TypeReference<List<Map<String, Object>>>() {}));
        List<Map<String, Object>> history = new ArrayList<>();
        for (Map<String, Object> h : r.rows()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("periodStart", String.valueOf(h.get("period_start")));
            p.put("score", h.get("score"));
            p.put("band", h.get("band"));
            p.put("runId", h.get("run_id"));
            history.add(p);
        }
        out.put("history", history);
        return out;
    }

    /**
     * {@code POST /anomaly-scores/preview} (design §7, §11): score ONE entity under a saved model ({@code {model,
     * entityKey}}) or unsaved content ({@code {content, entityKey}}), optionally {@code asOf} (YYYY-MM-DD, UTC; default
     * today), and write NOTHING — no scores Dataset, Signal or watch-list entry. Gates, fail-closed:
     * <ol>
     *   <li>{@code canWorkIncidents}; unsaved content additionally needs {@code canAuthorWorkbench} — running arbitrary
     *       features over the Space's Datasets is authoring, not reading (403).</li>
     *   <li>Body shape: exactly one of {@code model}/{@code content}, a key of 1..{@value #MAX_KEY} chars, a valid
     *       {@code asOf} (400).</li>
     *   <li>No write or data root → 503.</li>
     *   <li>Saved model: unknown, unparseable or outside the caller's data scopes → 404 (as the GET). Content:
     *       {@code fromMap} + the save-time Space checks → 422; a data-scoped caller may only preview content carrying
     *       a scope it holds (403).</li>
     *   <li>Bounded evaluation under {@link #PREVIEW_POLICY} ({@link AnomalyScoreEvaluator#preview}): without peers
     *       every feature is narrowed to the entity (a bound filter); with peers the full cohort is read so its medians
     *       and cohort shift are real, and only the entity is scored. A failed or over-cap query → 422 with the
     *       evaluator's value-free message.</li>
     * </ol>
     * The entity key is masked in the response unless the caller holds {@code canRevealLinkEntities} (D-P8).
     */
    private Object preview(ApiContext api, HttpExchange ex, Map<String, Object> body) throws Exception {
        Object modelRef = body.get("model");
        Object contentRef = body.get("content");
        if ((modelRef == null) == (contentRef == null))
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "give exactly one of 'model' (a saved id) or 'content'");
        if (!(body.get("entityKey") instanceof String entityKey) || entityKey.isEmpty() || entityKey.length() > MAX_KEY)
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "'entityKey' must be a string of 1.." + MAX_KEY + " characters");
        if (body.get("asOf") != null && !(body.get("asOf") instanceof String))
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "'asOf' must be a YYYY-MM-DD string");
        LocalDate asOf;
        try {
            asOf = AnomalyScoreJobType.asOf((String) body.get("asOf"), Instant.now());
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "'asOf' must be YYYY-MM-DD");
        }
        Map<String, Object> content;
        String modelId;
        if (contentRef != null) {
            ApiContext.requireCapability(ex, "canAuthorWorkbench");
            if (!(contentRef instanceof Map<?, ?> raw))
                throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "'content' must be an object");
            @SuppressWarnings("unchecked") Map<String, Object> c = (Map<String, Object>) raw;
            content = c;
            modelId = content.get("id") instanceof String s && SAFE_ID.matcher(s).matches() ? s : "preview";
        } else {
            if (!(modelRef instanceof String id) || !SAFE_ID.matcher(id).matches())
                throw new ApiException(404, ErrorCodes.NOT_FOUND, "no Anomaly Model '" + modelRef + "'");
            modelId = id;
            content = null;
        }
        Path writeRoot = api.writeRoot();
        Path dataRoot = api.dataRoot();
        if (writeRoot == null || dataRoot == null)
            throw new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE, "anomaly-score preview needs a Space write and data root");
        ComponentStore registry = new ComponentStore(writeRoot.resolve("registry"));
        AnomalyModel model;
        if (content == null) {
            ApiException hidden = new ApiException(404, ErrorCodes.NOT_FOUND, "no Anomaly Model '" + modelId + "'");
            Map<String, Object> stored = registry.get(TYPE, modelId).map(ComponentRegistry.Component::content)
                    .orElseThrow(() -> hidden);
            try {
                model = AnomalyModel.fromMap(modelId, stored);
            } catch (IllegalArgumentException e) {
                throw hidden;
            }
            if (outOfScope(ex, model)) throw hidden;
        } else {
            Map<String, Object> scored = new LinkedHashMap<>(content);
            scored.remove("id");
            try {
                model = AnomalyModel.fromMap(modelId, scored);
                AnomalyKindValidator.requireStorable(writeRoot, api::dataRoot, model);
            } catch (IllegalArgumentException e) {
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, e.getMessage());
            }
            if (outOfScope(ex, model))
                throw new ApiException(403, ErrorCodes.PERMISSION_DENIED,
                        "a data-scoped caller may only preview a model carrying a data scope it holds");
        }
        ViewStore views = new ViewStore(writeRoot.resolve("views"));
        AnomalyScoreEvaluator.Run run;
        try {
            run = AnomalyScoreEvaluator.preview(model, asOf, datasetId -> {
                Map<String, Object> ds = registry.get("dataset", datasetId).map(ComponentRegistry.Component::content)
                        .orElseThrow(() -> new IllegalArgumentException("anomaly-model feature names unknown dataset '"
                                + datasetId + "'"));
                return DatasetRelation.relationSql(ds, dataRoot, views);
            }, entityKey, PREVIEW_POLICY);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, e.getMessage());
        }
        AnomalyScorer.Scored s = run.scored().stream().filter(x -> entityKey.equals(x.entityKey())).findFirst().orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", model.id());
        out.put("entityType", model.entityType());
        putEntityKey(ex, writeRoot, out, entityKey);
        out.put("found", s != null);
        out.put("periodStart", run.period().toString());
        out.put("score", s == null ? null : s.score());
        out.put("band", s == null ? null : s.band());
        out.put("raw", s == null ? null : s.raw());
        out.put("elevatedThreshold", model.elevatedThreshold());
        out.put("highThreshold", model.highThreshold());
        out.put("insufficientCount", s == null ? null : s.insufficientCount());
        out.put("saved", content == null);
        List<Map<String, Object>> features = new ArrayList<>();
        if (s != null) for (AnomalyScorer.FeatureResult f : s.features()) features.add(f.toMap());
        out.put("features", features);
        return out;
    }
}
