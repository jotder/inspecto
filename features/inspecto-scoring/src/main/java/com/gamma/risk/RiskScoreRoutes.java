package com.gamma.risk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.query.DatasetMeasureProbe;
import com.gamma.query.DatasetRelation;
import com.gamma.query.QueryExecutor;
import com.gamma.pipeline.ViewStore;
import com.gamma.mask.EvidenceMasker;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.auth.ApiException;
import com.gamma.control.AuditReadMasking;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.RouteModule;
import com.gamma.access.RowScope;
import com.gamma.spi.auth.Subject;
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
 * The response echoes it as the Space {@code masked:} token (with {@code keyMasked: true}) unless the caller holds
 * {@code canRevealLinkEntities} (D-P8 mask on read, as {@code POST /risk-scores/preview}).
 */
public final class RiskScoreRoutes implements RouteModule {

    static final String TYPE = "risk-score";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");
    private static final int MAX_KEY = 256;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public java.util.Set<String> featureIds() { return java.util.Set.of("scoring"); }

    @Override
    public void register(ApiContext api) {
        api.get("/risk-scores/([^/]+)/([^/]+)", ApiContext.withCapability("canWorkIncidents",
                (e, m) -> latest(api, e, m.group(1), m.group(2))));
        api.post("/risk-scores/preview", ApiContext.withCapability("canWorkIncidents",
                (e, m) -> preview(api, e, api.body(e))));
    }

    /**
     * {@code POST /risk-scores/preview} (ASSURE-RISK-SCORE-RESIDUALS-1 S3, D-RP4/D-RP5/D-RP6, operator 2026-10-06):
     * score ONE entity under a saved model ({@code {model, entityKey}}) or unsaved content ({@code {content,
     * entityKey}}) and write NOTHING — no scores Dataset, no Signal, no watch-list entry. Gates, fail-closed:
     * <ol>
     *   <li>{@code canWorkIncidents} (the read gate); unsaved content additionally needs {@code canAuthorWorkbench}
     *       — running arbitrary factors over the Space's Datasets is authoring, not reading (403).</li>
     *   <li>Body shape: exactly one of {@code model}/{@code content}, a key of 1..{@value #MAX_KEY} chars (400).</li>
     *   <li>No write or data root → 503.</li>
     *   <li>Saved model: unknown, unparseable or outside the caller's data scopes → 404 (as the GET). Content:
     *       {@code fromMap} + {@link #requireStorable} → 422 with the save-time message; a data-scoped caller may
     *       only preview content carrying a scope it holds (403).</li>
     *   <li>Bounded evaluation ({@link RiskScoreEvaluator#preview}): one entity per factor, evidence rows capped,
     *       a tighter sandbox timeout; a failed query → 422 with the evaluator's value-free message.</li>
     * </ol>
     * The entity key is masked in the response for a caller without {@link AuditReadMasking#UNMASK_CAPABILITY}
     * (D-P8 mask on read) — the same Space token as masked evidence.
     */
    private Object preview(ApiContext api, HttpExchange ex, Map<String, Object> body) throws Exception {
        Object modelRef = body.get("model");
        Object contentRef = body.get("content");
        if ((modelRef == null) == (contentRef == null))
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "give exactly one of 'model' (a saved id) or 'content'");
        if (!(body.get("entityKey") instanceof String entityKey) || entityKey.isEmpty() || entityKey.length() > MAX_KEY)
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "'entityKey' must be a string of 1.." + MAX_KEY + " characters");
        Map<String, Object> content;
        String modelId;
        if (contentRef != null) {
            ApiContext.requireCapability(ex, "canAuthorWorkbench");
            if (!(contentRef instanceof Map<?, ?> raw))
                throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "'content' must be an object");
            @SuppressWarnings("unchecked") Map<String, Object> c = (Map<String, Object>) raw;
            content = c;
            modelId = content.get("id") instanceof String s && !s.isBlank() ? s : "preview";
        } else {
            if (!(modelRef instanceof String id) || !SAFE_ID.matcher(id).matches())
                throw new ApiException(404, ErrorCodes.NOT_FOUND, "no Risk Score model '" + modelRef + "'");
            modelId = id;
            content = null;
        }
        Path writeRoot = api.writeRoot();
        Path dataRoot = api.dataRoot();
        if (writeRoot == null || dataRoot == null)
            throw new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE, "risk-score preview needs a Space write and data root");
        ComponentStore registry = new ComponentStore(writeRoot.resolve("registry"));
        RiskScoreModel model;
        if (content == null) {
            ApiException hidden = new ApiException(404, ErrorCodes.NOT_FOUND, "no Risk Score model '" + modelId + "'");
            Map<String, Object> stored = registry.get(TYPE, modelId).map(ComponentRegistry.Component::content)
                    .orElseThrow(() -> hidden);
            try {
                model = RiskScoreModel.fromMap(modelId, stored);
            } catch (IllegalArgumentException e) {
                throw hidden;
            }
            if (outOfScope(ex, model)) throw hidden;
        } else {
            try {
                model = RiskScoreModel.fromMap(modelId, content);
                requireStorable(api, model);
            } catch (IllegalArgumentException e) {
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, e.getMessage());
            }
            if (outOfScope(ex, model))
                throw new ApiException(403, ErrorCodes.PERMISSION_DENIED,
                        "a data-scoped caller may only preview a model carrying a data scope it holds");
        }
        ViewStore views = new ViewStore(writeRoot.resolve("views"));
        RiskScoreEvaluator.Preview p;
        try {
            p = RiskScoreEvaluator.preview(model, entityKey, datasetId -> {
                Map<String, Object> ds = registry.get("dataset", datasetId).map(ComponentRegistry.Component::content)
                        .orElseThrow(() -> new IllegalArgumentException("risk-score factor names unknown dataset '" + datasetId + "'"));
                return DatasetRelation.relationSql(ds, dataRoot, views);
            }, EvidenceMasker.of(registry, writeRoot, model.datasetIds()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", model.id());
        out.put("entityType", model.entityType());
        putEntityKey(ex, writeRoot, out, entityKey);
        out.put("found", p.found());
        out.put("score", p.scored().score());
        out.put("high", p.scored().high());
        out.put("highThreshold", model.highThreshold());
        out.put("saved", content == null);
        List<Map<String, Object>> factors = new java.util.ArrayList<>();
        for (RiskScorer.FactorResult f : p.scored().factors()) factors.add(f.toMap());
        out.put("factors", factors);
        return out;
    }

    /**
     * D-P8 mask on read: put {@code entityKey} raw for a caller holding {@link AuditReadMasking#UNMASK_CAPABILITY}
     * (or no Subject at all — auth off), else as the Space {@code masked:} token, plus a {@code keyMasked} flag.
     */
    private static void putEntityKey(HttpExchange ex, Path writeRoot, Map<String, Object> out, String entityKey) {
        boolean reveal = ApiContext.subject(ex)
                .map(s -> s.capabilities().contains(AuditReadMasking.UNMASK_CAPABILITY)).orElse(true);
        com.gamma.mask.EntityKeyMasking.put(out, writeRoot, entityKey, reveal);
    }

    /** SEC-7d as the GET applies it: a data-scoped caller reaches only a model carrying a scope it holds. */
    private static boolean outOfScope(HttpExchange ex, RiskScoreModel model) {
        return ApiContext.attr(ex, ApiContext.ATTR_SUBJECT) instanceof Subject s && s.scoped()
                && (model.dataScope() == null || !s.dataScopes().contains(model.dataScope()));
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
        // A stored model that no longer parses (hand-edited, or written before a rule tightened) is no score: 404.
        RiskScoreModel model;
        try {
            model = RiskScoreModel.fromMap(modelId, content.get());
        } catch (IllegalArgumentException e) {
            throw notFound(modelId, entityKey);
        }

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

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", modelId);
        out.put("entityType", row.get("entity_type"));
        // The entity key is masked on read (D-P8) like the preview's; classified evidence was masked at WRITE time
        // by the Job under the same Space key (EvidenceMasker), so the factors are returned as stored.
        putEntityKey(ex, writeRoot, out, String.valueOf(row.get("entity_key")));
        out.put("score", row.get("score"));
        out.put("high", row.get("high"));
        out.put("highThreshold", model.highThreshold());
        out.put("modelVersion", row.get("model_version"));
        out.put("runId", row.get("run_id"));
        out.put("scoredAt", String.valueOf(row.get("scored_at")));
        out.put("factors", JSON.readValue(String.valueOf(row.get("factors")),
                new TypeReference<List<Map<String, Object>>>() {}));
        return out;
    }

    /**
     * Save-time gate for a {@code risk-score} model that needs the Space: run by EVERY writer (the component route and
     * both bulk writers, through {@code ComponentRoutes.validateKind} (through {@link RiskScoreKindValidator})). Fail closed;
     * every refusal is an {@link IllegalArgumentException} the caller maps to 422 (or a per-item bundle failure).
     * <ol>
     *   <li>Every column a factor names (key, measure field, filter fields, evidence) is in its Dataset's Schema.
     *       A Dataset whose Schema cannot be read refuses the save, like the Alert Rule {@code by} check.</li>
     *   <li>The derived scores names ({@code risk_scores_<id>}, {@code …_latest}) collide with nothing: no directory
     *       under the data root this model did not create, and no Dataset of that id over some other store.</li>
     * </ol>
     */
    static void requireStorable(ApiContext api, RiskScoreModel model) {
        requireStorable(api.writeRoot(), api::dataRoot, model);
    }

    /** {@link #requireStorable(ApiContext, RiskScoreModel)} against explicit roots. */
    static void requireStorable(Path writeRoot, java.util.function.Supplier<Path> dataRoots, RiskScoreModel model) {
        if (writeRoot == null) throw new IllegalArgumentException("risk-score write needs a write root");
        if (model.watchList() != null) {
            // ASSURE-ENTITY-LISTS-1: fail closed at save — the list must exist, be a live watch list, and the edition
            // must carry Entity Lists at all.
            com.gamma.entitylist.WatchListFeed feed = com.gamma.entitylist.WatchListFeed.installed().orElseThrow(() ->
                    new IllegalArgumentException("risk-score.watchList needs Entity Lists, which this edition does not carry"));
            try {
                feed.check(writeRoot, model.watchList().list());
            } catch (java.io.IOException e) {
                throw new IllegalArgumentException("risk-score.watchList cannot be checked: the Entity List log is unreadable", e);
            }
        }
        DatasetMeasureProbe probe = new DatasetMeasureProbe(() -> writeRoot, dataRoots);
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
        Path dataRoot = dataRoots.get();
        for (String out : List.of(model.scoresDataset(), model.latestDataset())) {
            if (dataRoot != null && Files.exists(dataRoot.resolve(out))
                    && !RiskScoreEvaluator.ownedBy(dataRoot.resolve(out), model.id()))
                throw new IllegalArgumentException("risk-score '" + model.id() + "' would write '" + out
                        + "', which already exists under the data root and is not this model's output");
            Object ref = store.get("dataset", out).map(ComponentRegistry.Component::content)
                    .map(c -> c.get("physicalRef")).orElse(null);
            if (store.exists("dataset", out) && !out.equals(String.valueOf(ref)))
                throw new IllegalArgumentException("risk-score '" + model.id() + "' would write '" + out
                        + "', which is the id of a Dataset over another store");
            // A registered Dataset over the derived store name is refused too — unless that store already IS this
            // model's output (the documented Alert Rule Dataset over _latest), which reads, never collides.
            boolean ours = dataRoot != null && RiskScoreEvaluator.ownedBy(dataRoot.resolve(out), model.id());
            if (!ours)
                for (ComponentRegistry.Component ds : store.list("dataset"))
                    if (out.equals(String.valueOf(ds.content().get("physicalRef")).trim()))
                        throw new IllegalArgumentException("risk-score '" + model.id() + "' would write '" + out
                                + "', which Dataset '" + ds.content().get("name") + "' already reads as its store");
        }
    }

    /** Content keys through which a Dataset or sink names the store it reads or writes. */
    private static final List<String> STORE_KEYS = List.of("physicalRef", "store", "output_store", "path", "sourceName");

    /**
     * The {@code risk_scores_} prefix is RESERVED for the {@code risk.score} Job's outputs: a Dataset or sink whose id,
     * or whose store (any of {@link #STORE_KEYS}, compared case-insensitively on its normalised first path segment),
     * starts with it is refused — so nothing else can name, read into, or write over a scores store. The one exception
     * is the documented Dataset over a saved model's own {@code _latest}: its id AND its {@code physicalRef} both
     * equal {@code risk_scores_<model>_latest}, and that model exists.
     */
    static void requireNotReserved(ApiContext api, String type, String id, Map<String, Object> content) {
        requireNotReserved(api.writeRoot(), type, id, content);
    }

    /** {@link #requireNotReserved(ApiContext, String, String, Map)} against an explicit write root. */
    static void requireNotReserved(Path writeRoot, String type, String id, Map<String, Object> content) {
        String prefix = RiskScoreModel.SCORES_PREFIX;
        java.util.List<String> named = com.gamma.alert.ScoreOutputDirs.namedStores(id, content, STORE_KEYS);
        boolean reserved = com.gamma.alert.ScoreOutputDirs.anyReserved(named, prefix);
        if (!reserved) return;
        if ("dataset".equals(type) && id != null && id.equals(content.get("physicalRef"))
                && named.size() == 2 && id.endsWith(RiskScoreModel.LATEST_SUFFIX) && writeRoot != null) {
            String model = id.substring(prefix.length(), id.length() - RiskScoreModel.LATEST_SUFFIX.length());
            if (id.startsWith(prefix) && new ComponentStore(writeRoot.resolve("registry")).exists(TYPE, model))
                return;   // the documented Alert Rule Dataset over risk_scores_<model>_latest
        }
        throw new IllegalArgumentException(type + " '" + id + "' names a store under the reserved prefix '" + prefix
                + "' (Risk Score outputs); only a Dataset with id = physicalRef = " + prefix
                + "<model>" + RiskScoreModel.LATEST_SUFFIX + " over a saved model is allowed");
    }
}
