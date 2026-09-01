package com.gamma.control;

import com.gamma.config.io.ConfigLoader;
import com.gamma.config.safety.ConfigSafetyValidator;
import com.gamma.config.safety.SafetyPolicy;
import com.gamma.config.spec.ConfigSpec;
import com.gamma.config.spec.ConfigSpecs;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.Severity;
import com.gamma.etl.ConfigValidator;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.TypeFlow;
import com.gamma.pipeline.exec.ComponentPreview;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static com.gamma.util.Values.mapAt;

/**
 * Declarative-config spec/validate/preview routes ({@code /config/spec}, {@code /validate},
 * {@code /config/preview/*}, {@code /config/suggest/schema}; v3.2.0/v4.1.0/v5.1.0): describe a
 * config type's spec, validate a saved file or an unsaved draft, and run the stateless
 * scratch-only onboarding previews. Extracted verbatim from {@code ConfigRoutes}: identical
 * routes, statuses and gating order. The shared findings helpers ({@code schemaFileFindings},
 * {@code routeArmingFindings}, …) stay on {@link ConfigRoutes}, which other route modules and
 * tests reference directly.
 */
final class ConfigPreviewRoutes implements RouteModule {

    @Override
    public void register(ApiContext api) {
        api.get("/config/spec/(.+)", (e, m) -> {
            ConfigSpec spec = ConfigSpecs.forType(ApiContext.name(m));
            if (spec == null) throw new ApiException(404, "unknown config type: " + ApiContext.name(m));
            return spec;
        });
        api.post("/validate", (e, m) -> validate(api, api.body(e)));
        // Parse a raw sample with a draft's parsing: settings — stateless, scratch-only (stream
        // onboarding's sample-as-thread; the raw→parsed hop).
        api.post("/config/preview/parsing", (e, m) -> previewParsing(api.body(e)));
        // TRY_CAST a draft schema's typed fields against already-parsed sample rows — stateless,
        // scratch-only (stream onboarding's Schema & Mapping stage; the parsed→typed hop).
        api.post("/config/preview/schema", (e, m) -> previewSchema(api.body(e)));
        api.post("/config/suggest/schema", (e, m) -> suggestSchema(api.body(e)));
        // The DERIVED output schema of a SAVED pipeline: DESCRIBE over the same SELECT the
        // transform would run, without executing it (step-workbench S5). Distinct from
        // /config/preview/schema above, which TRY_CASTs a DRAFT against caller-supplied sample
        // rows — that one needs rows and executes; this one needs neither.
        api.get("/config/schema/derived", (e, m) -> derivedSchema(api, ApiContext.query(e, "pipeline")));
    }

    /**
     * Derived output schema for every schema a saved pipeline declares.
     *
     * <p>🔴 {@code typedSource} is the load-bearing input: on the built-in CSV path every raw column
     * is {@code VARCHAR}, while on the plugin path the declared field types stand. Passing the wrong
     * one yields types that look authoritative and are wrong — the failure mode the step-workbench
     * design calls out for ASN.1/fixed-width pipelines. It is derived here from the pipeline's own
     * config ({@code ingesterClass != null}), never guessed or defaulted, and it is REPORTED in the
     * response so a reader can see which path was assumed.
     *
     * <p>Gates: unknown pipeline → 404 · traversal in the name → 403 · schema-less draft → 422 ·
     * a Schema/Mapping that does not bind → 422 carrying DuckDB's own column-naming message. There
     * is no write-root gate: this route writes nothing.
     */
    private Object derivedSchema(ApiContext api, String pipeline) throws IOException {
        if (pipeline == null || pipeline.isBlank())
            throw new ApiException(400, "query parameter 'pipeline' is required");
        // A bare config key, never a path: refuse separators outright rather than jailing a path.
        if (pipeline.contains("/") || pipeline.contains("\\") || pipeline.contains(".."))
            throw new ApiException(403, "'pipeline' must be a bare pipeline name, not a path");

        PipelineConfig cfg = api.service().configFor(pipeline)
                .orElseThrow(() -> new ApiException(404, "no pipeline named '" + pipeline + "'"));
        PipelineConfig.Schemas schemas = cfg.schemas();

        // ingesterClass != null is exactly the plugin path: the parser REFUSES a plugin ingester
        // without a non-empty segments map (PipelineConfigParser), so the two never disagree.
        boolean typedSource = schemas.ingesterClass() != null && !schemas.ingesterClass().isBlank();

        List<Map<String, Object>> out = new ArrayList<>();
        if (schemas.segments() != null)
            schemas.segments().forEach((key, schema) -> out.add(derivedEntry(key, key, schema, cfg, typedSource)));
        else if (schemas.selector() != null)
            for (var sel : schemas.selector().entries())
                out.add(derivedEntry(sel.table(), sel.table(), sel.schema(), cfg, typedSource));
        else if (schemas.single() != null)
            out.add(derivedEntry("single", null, schemas.single(), cfg, typedSource));
        else
            throw new ApiException(422, "pipeline '" + pipeline + "' declares no schema "
                    + "(a draft may be saved schema-less, but nothing can be derived from it)");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pipeline", pipeline);
        body.put("sourcePath", typedSource ? "plugin" : "csv");
        body.put("typedSource", typedSource);
        body.put("ingesterClass", schemas.ingesterClass());
        body.put("schemas", out);
        return body;
    }

    /** One schema's derived SINK columns — the written shape, matching PartitionWriter's projection. */
    private Map<String, Object> derivedEntry(String key, String table, Map<String, Object> schema,
                                             PipelineConfig cfg, boolean typedSource) {
        List<TypeFlow.Column> cols;
        try {
            cols = TypeFlow.sinkColumns(schema, cfg, typedSource);
        } catch (IllegalArgumentException doesNotBind) {
            // Fail closed and name the schema: a partial answer here would read as a complete one.
            throw new ApiException(422, "schema '" + key + "' does not compile to a valid transform: "
                    + doesNotBind.getMessage());
        }
        List<Map<String, Object>> columns = new ArrayList<>();
        for (TypeFlow.Column c : cols) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("name", c.name());
            one.put("type", c.type());
            columns.add(one);
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("key", key);
        entry.put("table", table);
        entry.put("columns", columns);
        return entry;
    }

    private Object validate(ApiContext api, Map<String, Object> body) throws IOException {
        String configPath = ApiContext.str(body, "configPath");
        if (configPath != null) {
            PipelineConfig cfg = PipelineConfig.load(configPath);
            List<String> warnings = ConfigValidator.validate(cfg);
            List<Finding> findings = ConfigLoader.filesystem()
                    .validate(ConfigSpecs.pipeline(), ConfigLoader.filesystem().decode(configPath));
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("pipeline", cfg.identity().pipelineName());
            r.put("warnings", warnings);     // legacy string form (back-compat)
            r.put("findings", findings);     // structured form (v3.2.0)
            r.put("clean", warnings.isEmpty());
            return r;
        }
        String type = ApiContext.str(body, "type");
        Object cfgObj = body.get("config");
        if (type == null || !(cfgObj instanceof Map<?, ?>)) {
            throw new ApiException(400,
                    "body must include 'configPath', or 'type' + 'config' (a draft config map)");
        }
        ConfigSpec spec = ConfigSpecs.forType(type);
        if (spec == null) throw new ApiException(404, "unknown config type: " + type);
        Map<String, Object> draft = mapAt(body, "config");
        List<Finding> findings = new ArrayList<>(ConfigLoader.filesystem().validate(spec, draft));
        // Pre-flight: warn when a pipeline draft's schema_file won't resolve on this server —
        // registration would otherwise fail later with an opaque error (v4.1.0).
        //
        // W3: checked against the WRITE ROOT, because that is where this draft would land and a
        // reference resolves config-relative FIRST. Without it every portable bare `<name>.toon` —
        // the form the UI now writes — was reported unresolvable purely for not existing in the
        // server's CWD. Null when writes are disabled: then there is no prospective home, and the
        // CWD-only check is all that can honestly be said.
        findings.addAll(ConfigRoutes.schemaFileFindings(type, draft, Severity.WARNING, api.writeRoot()));
        // Pre-flight: a route: block that would refuse to arm. Reported here so the editor can show
        // it while the operator is still authoring, rather than at the next run.
        findings.addAll(ConfigRoutes.routeArmingFindings(type, draft));
        findings.addAll(ConfigRoutes.stepDisableFindings(type, draft));
        findings.addAll(ConfigRoutes.dedupWindowFindings(type, draft));
        // Opt-in hard-fail safety gate (R6): merged in only when the caller asks, so the default
        // /validate response is byte-for-byte unchanged for existing callers.
        boolean safety = "true".equalsIgnoreCase(String.valueOf(body.get("safety")));
        if (safety) {
            findings.addAll(ConfigSafetyValidator.check(type, draft, SafetyPolicy.defaultPolicy()));
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", type);
        r.put("findings", findings);
        r.put("safetyChecked", safety);
        r.put("clean", findings.isEmpty());
        return r;
    }

    /** Character cap on {@code sample_text} — a preview sample, not a data upload. */
    private static final int MAX_SAMPLE_CHARS = 1_000_000;

    /**
     * {@code POST /config/preview/parsing} — parse a raw sample with a pipeline draft's
     * {@code parsing:} settings and return the produced columns/rows (stream onboarding,
     * v5.1.0). Stateless and scratch-only: body {@code {config:{…}, sample_text}} where
     * {@code config} is a full pipeline draft map (the same shape {@code /validate} takes).
     * The draft is interpreted by the real config parser and the sample is read with the same
     * DuckDB idioms the ingest engine uses ({@link ComponentPreview#parsing}), so what the
     * builder sees is what the engine would parse. Config/parse problems are the caller's
     * (422 with the reason), never a server error.
     */
    private Object previewParsing(Map<String, Object> body) {
        Object cfgObj = body.get("config");
        String sample = ApiContext.str(body, "sample_text");
        if (!(cfgObj instanceof Map<?, ?>) || sample == null || sample.isBlank())
            throw new ApiException(400, "body must include 'config' (a pipeline draft map) and 'sample_text'");
        if (sample.length() > MAX_SAMPLE_CHARS)
            throw new ApiException(400, "sample_text too large (max " + MAX_SAMPLE_CHARS + " chars)");
        Map<String, Object> draft = mapAt(body, "config");
        PipelineConfig cfg;
        try {
            cfg = PipelineConfig.fromMap(draft);
        } catch (Exception invalid) {
            throw new ApiException(422, "config is not a valid pipeline draft: " + invalid.getMessage());
        }
        try {
            ComponentPreview.GrammarResult r = ComponentPreview.parsing(cfg, sample);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("frontend", frontendOf(cfg));
            out.put("columns", r.columns());
            out.put("rowCount", r.rowCount());
            out.put("rows", r.rows());
            out.put("rejectedRows", r.rejectedRows());
            // B2, additive: per-column inferred types (delimited sniff) — absent for other frontends.
            if (!r.columnTypes().isEmpty()) out.put("columnTypes", r.columnTypes());
            return out;
        } catch (IllegalArgumentException unsupported) {
            throw new ApiException(422, unsupported.getMessage());
        } catch (Exception parseFail) {
            throw new ApiException(422, "sample does not parse with these settings: " + parseFail.getMessage());
        }
    }

    /**
     * {@code POST /config/preview/schema} — {@code TRY_CAST} already-parsed {@code sampleRows}
     * against a draft schema's typed fields, splitting ok/rejected (stream onboarding, v5.2.0).
     * Body {@code {config:{raw:{fields:[{name,type}]}}, sampleRows:[{...}]}}. Reuses
     * {@link ComponentPreview#schema} — the same scratch-only cast check the Studio schema
     * component's own preview runs — so the Schema & Mapping stage's "Validate types" sees exactly
     * what that engine path would do. Config/cast problems are the caller's (422), never a server
     * error.
     *
     * <p><b>B1 (definition-surface unification P4):</b> when the posted draft also carries
     * {@code mapping.rules}, the response gains {@code mappedColumns} / {@code mappedCount} /
     * {@code mappedRows} — the rules compiled over the rows that passed the cast, i.e. the Load drawer's
     * "mapped output" in TARGET columns. Additive: a draft without rules gets byte-identical output to
     * before, which is what the onboarding pane still posts.
     */
    private Object previewSchema(Map<String, Object> body) {
        Object cfgObj = body.get("config");
        List<Map<String, Object>> sampleRows = ApiContext.sampleRows(body);
        if (!(cfgObj instanceof Map<?, ?>) || sampleRows.isEmpty())
            throw new ApiException(400, "body must include 'config' (a schema draft map) and non-empty 'sampleRows'");
        Map<String, Object> content = mapAt(body, "config");
        try {
            ComponentPreview.Result r = ComponentPreview.schema(content, sampleRows);
            int okCount = 0, rejectedCount = 0;
            List<Map<String, Object>> rejectedRows = List.of();
            ComponentPreview.RelationPreview mapped = null;
            for (ComponentPreview.RelationPreview rel : r.relations()) {
                if ("data".equals(rel.rel())) okCount = rel.rowCount();
                else if ("rejected".equals(rel.rel())) {
                    rejectedCount = rel.rowCount();
                    rejectedRows = rel.rows();
                } else if ("mapped".equals(rel.rel())) mapped = rel;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("columns", r.inputColumns());
            out.put("okCount", okCount);
            out.put("rejectedCount", rejectedCount);
            out.put("rejectedRows", rejectedRows);
            // B1: present only when the draft declared mapping rules. The column list comes from the rules
            // themselves, not from the returned rows, so an empty mapped set still renders its grid header.
            if (mapped != null) {
                out.put("mappedColumns", targetColumns(content));
                out.put("mappedCount", mapped.rowCount());
                out.put("mappedRows", mapped.rows());
            }
            return out;
        } catch (IllegalArgumentException badSchema) {
            throw new ApiException(422, badSchema.getMessage());
        } catch (Exception castFail) {
            throw new ApiException(422, "schema preview failed: " + castFail.getMessage());
        }
    }

    /** The mapped relation's column names, in rule order — the draft's own {@code mapping.rules} targets. */
    private static List<String> targetColumns(Map<String, Object> content) {
        if (!(content.get("mapping") instanceof Map<?, ?> mapping)
                || !(mapping.get("rules") instanceof List<?> rules)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object r : rules)
            if (r instanceof Map<?, ?> rule && rule.get("targetColumn") != null)
                out.add(rule.get("targetColumn").toString());
        return List.copyOf(out);
    }

    /**
     * {@code POST /config/suggest/schema} — draft-schema inference over already-parsed
     * {@code sampleRows} (G1, {@code consignment-chain-plan.md} S4). {@code SchemaSuggest} runs
     * TRY_CAST voting per column on a scratch DuckDB and this handler shapes the winners into a
     * DRAFT: a {@code raw.fields} list ({@code selector} = the sample's own column key) plus
     * identity {@code mapping} rules, for the schema editor to seed a HUMAN edit — never
     * auto-applied (the {@code ParserPlugin.suggest} posture), and real ingest keeps
     * {@code auto_detect=false}. Body {@code {sampleRows:[{...}]}} — the parsing preview's output
     * shape, so the two routes chain: parse the sample, then suggest from what parsed.
     *
     * <p><b>B3 (definition-surface unification P4):</b> when the body also carries {@code config} — the
     * draft the caller is currently holding — the response gains a {@code drift} block
     * ({@code drifted}, {@code added}, {@code missing}, {@code typeChanged}) diffing that draft against
     * this sample's vote, which backs §5.2's drift indicator and its merge-don't-clobber re-sync. Purely
     * additive: without {@code config} the response is exactly the pre-B3 full suggestion. Note the diff
     * is informational — unlike {@link com.gamma.config.safety.SchemaCompatibility} it gates nothing and
     * never emits an ERROR.
     */
    private Object suggestSchema(Map<String, Object> body) {
        List<Map<String, Object>> sampleRows = ApiContext.sampleRows(body);
        if (sampleRows.isEmpty())
            throw new ApiException(400, "body must include non-empty 'sampleRows'");
        try {
            List<com.gamma.pipeline.exec.SchemaSuggest.Field> inferred =
                    com.gamma.pipeline.exec.SchemaSuggest.infer(sampleRows);
            List<Map<String, Object>> fields = new ArrayList<>();
            List<Map<String, Object>> rules = new ArrayList<>();
            for (com.gamma.pipeline.exec.SchemaSuggest.Field f : inferred) {
                Map<String, Object> field = new LinkedHashMap<>();
                field.put("name", f.name());
                field.put("selector", f.name());
                field.put("type", f.type());
                fields.add(field);
                Map<String, Object> rule = new LinkedHashMap<>();
                rule.put("targetColumn", f.name());
                rule.put("sourceExpression", f.name());
                rule.put("transformType", "DIRECT");
                rules.add(rule);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("fields", fields);
            out.put("mapping", Map.of("rules", rules));
            // B3: only when the caller posted the draft it is holding. Without one there is nothing to have
            // drifted FROM, and the response stays the pre-B3 full suggestion.
            if (body.get("config") instanceof Map<?, ?>)
                out.put("drift", driftBody(com.gamma.pipeline.exec.SchemaSuggest.drift(
                        mapAt(body, "config"), inferred)));
            return out;
        } catch (IllegalArgumentException badSample) {
            throw new ApiException(422, badSample.getMessage());
        } catch (Exception inferFail) {
            throw new ApiException(422, "schema suggestion failed: " + inferFail.getMessage());
        }
    }

    /** The drift diff as response JSON; {@code drifted} lets a client light its indicator without counting. */
    private static Map<String, Object> driftBody(com.gamma.pipeline.exec.SchemaSuggest.Drift d) {
        List<Map<String, Object>> added = d.added().stream()
                .<Map<String, Object>>map(f -> Map.of("name", f.name(), "type", f.type())).toList();
        List<Map<String, Object>> missing = d.missing().stream()
                .<Map<String, Object>>map(f -> Map.of("name", f.name(), "type", f.type())).toList();
        List<Map<String, Object>> typeChanged = d.typeChanged().stream()
                .<Map<String, Object>>map(t -> Map.of("name", t.name(), "declared", t.declared(),
                        "suggested", t.suggested())).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("drifted", !d.isEmpty());
        out.put("added", added);
        out.put("missing", missing);
        out.put("typeChanged", typeChanged);
        return out;
    }

    /** The parsing frontend a config resolves to (the same precedence the ingester applies). */
    private static String frontendOf(PipelineConfig cfg) {
        if (cfg.fixedWidth() != null) return "fixedwidth";
        if (cfg.json() != null) return "json";
        if (cfg.textRegex() != null) return "text_regex";
        if (cfg.schemas().ingesterClass() != null) return "plugin";
        return "delimited";
    }
}
