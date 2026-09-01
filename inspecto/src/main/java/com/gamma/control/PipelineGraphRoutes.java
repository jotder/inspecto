package com.gamma.control;

import com.gamma.config.io.ConfigCodec;
import com.gamma.config.io.ConfigLoader;
import com.gamma.config.spec.ConfigSpecs;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.Severity;
import com.gamma.config.safety.ConfigSafetyValidator;
import com.gamma.config.safety.SafetyPolicy;
import com.gamma.etl.PipelineConfig;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.PipelineCodec;
import com.gamma.pipeline.PipelineDocument;
import com.gamma.pipeline.PipelineCompileException;
import com.gamma.pipeline.PipelineEditable;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineProjection;
import com.gamma.pipeline.PipelineStore;
import com.gamma.pipeline.PipelineValidator;
import com.gamma.pipeline.RecipeConverter;
import com.gamma.pipeline.PipelineLift;
import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.ConnectionWorkbench;
import com.gamma.acquire.LocalConnectionWorkbench;
import com.gamma.enrich.ReferenceReader;
import com.gamma.inspector.PipelineTestRun;
import com.gamma.pipeline.exec.PipelineDryRun;
import com.gamma.pipeline.exec.RowShaper;
import com.gamma.service.CollectorService;
import com.gamma.service.SpaceRoot;
import com.gamma.util.AtomicFiles;
import com.gamma.util.MappingCsv;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import static com.gamma.util.Values.mapAt;

/**
 * Pipeline graph, document, dry-run, run-to-here and trigger routes: the editable graph round-trip over
 * the canonical {@code *_pipeline.toon} (W5, plan U-A), the read-only Pipeline Document projection (ELT
 * amendment §5.1), and the scratch-only preview/run surfaces (T18/§7.2, Build→Test→Run Step 5c).
 * Extracted verbatim from {@code PipelineRoutes}: identical routes, order, HTTP statuses and validation.
 */
final class PipelineGraphRoutes implements RouteModule {

    private static final Logger log = LoggerFactory.getLogger(PipelineGraphRoutes.class);

    @Override
    public void register(ApiContext api) {
        api.post("/pipelines/authored/([^/]+)/dry-run", (e, m) -> dryRunFlow(api, ApiContext.name(m), api.body(e)));
        // Run-to-here (Build→Test→Run Step 5c): a bounded, scratch-only run over REAL inbox files.
        // canAuthorWorkbench, not canOperateRuns — this is a simulate, and it mirrors DecisionRoutes'
        // /simulate vs /apply split. It writes nothing outside a scratch root; see PipelineTestRun.
        api.post("/pipelines/authored/([^/]+)/run", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> testRun(api, ApiContext.name(m), ApiContext.query(e, "to"), api.body(e))));
        // A real run is an operational verb (canOperateRuns) and mirrors POST /jobs/{name}/trigger — deliberately
        // NOT ".../run": that path is the editor's scratch-only run-to-here contract (POST …/run?to={nodeId},
        // pipelines.service.ts) and must never fire a production run.
        api.post("/pipelines/authored/([^/]+)/trigger", ApiContext.withCapability("canOperateRuns", (e, m) -> runPipeline(api, e, ApiContext.name(m))));
        api.get("/pipelines/([^/]+)/graph", (e, m) -> graphForPipeline(api, ApiContext.name(m)));
        // The Pipeline Document (ELT amendment §5.1): a read-only Markdown projection of config for
        // business verification and sign-off. A read, not an authoring action — no capability gate.
        api.get("/pipelines/([^/]+)/document", (e, m) -> document(api, e, ApiContext.name(m)));
        // W5 (plan U-A): the editable round-trip over the canonical *_pipeline.toon.
        api.get("/pipelines/([^/]+)/graph/raw", (e, m) -> editableGraph(api, ApiContext.name(m)));
        api.put("/pipelines/([^/]+)/graph", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> saveGraph(api, e, ApiContext.name(m), api.body(e))));
    }

    /** {@code GET /pipelines/{name}/graph} — lift one registered pipeline to its graph; 404 if no such pipeline. */
    private Object graphForPipeline(ApiContext api, String name) {
        PipelineConfig c = api.service().configFor(name)
                .orElseThrow(() -> new ApiException(404, "no pipeline named '" + name + "'"));
        return PipelineProjection.graph(PipelineLift.lift(c));
    }

    /**
     * {@code GET /pipelines/{name}/document} — the <b>Pipeline Document</b> (ELT amendment §5.1):
     * the pipeline's configuration projected to Markdown for business verification and sign-off.
     * Regenerated on demand and <b>never stored as truth</b>; the header table and the
     * {@code X-Config-Fingerprint} response header carry a hash over the recipe plus every resolved
     * component, so an approved document is verifiably tied to the config that produced it.
     *
     * <p>Reads the same registered file {@link #editableGraph} does, then projects it through
     * {@link RecipeConverter#toRecipe} — the document describes the Steps a user authored, not the
     * lowered graph. 404 if no such registered pipeline.
     */
    private Object document(ApiContext api, HttpExchange ex, String name) throws IOException {
        PipelineConfig cfg = api.service().configFor(name)
                .orElseThrow(() -> new ApiException(404, "no pipeline named '" + name + "'"));
        Path file = api.service().pathFor(name)
                .orElseThrow(() -> new ApiException(404, "no config file for pipeline '" + name + "'"));

        Map<String, Object> recipe = RecipeConverter.toRecipe(ConfigLoader.filesystem().decode(file.toString()));
        Map<String, Map<String, Object>> components = resolveDocumentRefs(api, cfg, recipe);

        Map<String, Object> hashed = new LinkedHashMap<>();
        hashed.put("recipe", recipe);
        hashed.put("components", components);
        String fingerprint = ContentHash.of(hashed);

        ex.getResponseHeaders().set("X-Config-Fingerprint", fingerprint);
        return ApiContext.respondText(ex,
                PipelineDocument.render(cfg.identity().pipelineName(), recipe, components, fingerprint),
                "text/markdown; charset=utf-8");
    }

    /**
     * Resolve every component the recipe's document-bearing keys name, keyed by the ref exactly as the
     * recipe spells it. Two spellings resolve, mirroring {@link RecipeConverter}'s own distinction:
     * a <b>registry ref</b> ({@code schemas/foo}) through the {@link ComponentStore}, and a
     * <b>plain path</b> (the legacy pre-Phase-1 {@code schema_file}) only when the pipeline's own
     * {@link PipelineConfig#referencedFiles()} declares it — never an arbitrary path off the config,
     * so a document can't be used to read files the engine doesn't already parse for this pipeline.
     * Anything unresolved maps to an empty map: the document reports it rather than failing, and it
     * still participates in the fingerprint so a ref that later resolves changes the hash.
     *
     * <p><b>{@code connections/*} is deliberately never resolved</b>: a Connection component holds
     * credentials, and neither the document nor the fingerprint has any business loading them. The
     * collect Step's connection ref still renders, and secret-shaped keys mask in
     * {@link PipelineDocument}.
     */
    private Map<String, Map<String, Object>> resolveDocumentRefs(ApiContext api, PipelineConfig cfg,
                                                                Map<String, Object> recipe) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        collectRefs(recipe.get("steps"), out);
        if (out.isEmpty()) return out;

        Path root = api.writeRoot();
        ComponentStore store = root == null ? null : new ComponentStore(root.resolve("registry"));
        Map<String, Path> declared = new LinkedHashMap<>();
        for (Path p : cfg.referencedFiles()) declared.put(p.toAbsolutePath().normalize().toString(), p);

        for (Map.Entry<String, Map<String, Object>> e : out.entrySet()) {
            String ref = e.getKey();
            int slash = ref.indexOf('/');
            String dir = slash < 0 ? "" : ref.substring(0, slash);
            String type = COMPONENT_DIRS.get(dir);
            try {
                if (type != null) {
                    if (store != null) store.get(type, ref.substring(slash + 1))
                            .ifPresent(c -> e.setValue(c.content() == null ? Map.of() : c.content()));
                } else {
                    Path p = declared.get(Path.of(ref).toAbsolutePath().normalize().toString());
                    if (p != null && Files.isRegularFile(p)) e.setValue(decodeReferenced(p));
                }
            } catch (RuntimeException | IOException ignored) {
                // an unreadable or malformed component is reported as unresolved, never a failed document
            }
        }
        return out;
    }

    /** Decode a declared referenced file by suffix — mapping CSV rows, or an ordinary TOON component. */
    private static Map<String, Object> decodeReferenced(Path p) throws IOException {
        if (p.getFileName().toString().endsWith(".csv")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rules", MappingCsv.parse(Files.readString(p, StandardCharsets.UTF_8), p.toString()));
            return m;
        }
        return ConfigLoader.filesystem().decode(p.toString());
    }

    /** Recipe ref dir → component type. A value not starting with one of these is a plain path, not a ref. */
    private static final Map<String, String> COMPONENT_DIRS = Map.of(
            "grammars", "grammar", "schemas", "schema", "mappings", "mapping", "references", "reference");

    /** Recipe keys whose value the document renders a component from. */
    private static final Set<String> DOCUMENT_REF_KEYS = Set.of("grammar", "schema", "mapping", "join");

    /** Walk the Step list (through {@code route} branches) collecting every renderable component ref. */
    private void collectRefs(Object steps, Map<String, Map<String, Object>> out) {
        if (!(steps instanceof List<?> list)) return;
        for (Object s : list) {
            if (!(s instanceof Map<?, ?> step)) continue;
            for (Map.Entry<?, ?> verb : step.entrySet()) {
                if (!(verb.getValue() instanceof Map<?, ?> cfg)) continue;
                for (String key : DOCUMENT_REF_KEYS)
                    if (cfg.get(key) instanceof String ref && !ref.isBlank()) out.putIfAbsent(ref, Map.of());
                if (cfg.get("branches") instanceof Map<?, ?> branches)
                    for (Object b : branches.values())
                        if (b instanceof Map<?, ?> branch) collectRefs(branch.get("steps"), out);
            }
        }
    }

    /**
     * {@code GET /pipelines/{name}/graph/raw} — the <b>lossless editable</b> graph (W5): lifted for
     * topology, node configs verbatim in the config-file vocabulary ({@link PipelineEditable#toMap}),
     * plus a synthesized node per registered {@code *_enrich.toon} companion whose
     * {@code triggers.on_pipeline} names this pipeline (W4b — the node carries only
     * {@code use: enrichment/<name>}, never a config mirror). 404 if no such registered pipeline.
     */
    private Object editableGraph(ApiContext api, String name) throws IOException {
        PipelineConfig cfg = api.service().configFor(name)
                .orElseThrow(() -> new ApiException(404, "no pipeline named '" + name + "'"));
        Path file = api.service().pathFor(name)
                .orElseThrow(() -> new ApiException(404, "no config file for pipeline '" + name + "'"));
        Map<String, Object> raw = ConfigLoader.filesystem().decode(file.toString());
        Map<String, Object> editable = PipelineEditable.toMap(cfg, raw);
        attachCompanionEnrichments(api, cfg.identity().pipelineName(), editable);
        return editable;
    }

    /**
     * {@code PUT /pipelines/{name}/graph} — <b>lower the graph to the canonical config</b> (W5, U-A):
     * decode + structurally validate the posted graph (URL name authoritative), lower it over the
     * pipeline's <b>existing registered file</b> ({@link PipelineEditable#lower} — verbatim sections,
     * unmodeled keys preserved), run the SAME spec + safety gate as {@code POST /config/write}, and
     * write atomically. An {@code active} graph (or a brand-new file) must be complete; an inactive
     * draft may be partial. Unrepresentable topologies 422 with named {@code refusals[]} instead of
     * being silently truncated.
     *
     * <p>The target file prefers the pipeline's registered path ({@link CollectorService#pathFor})
     * over assuming {@code <name>_pipeline.toon} at the config root — a pipeline registered from a
     * legacy or differently-named path under the write root (e.g.
     * {@code subscriber/subscriber_pipeline.toon}) must be overwritten in place. Guessing the
     * canonical name here previously created a second, shadow file: the save looked successful but
     * the running pipeline (still bound to its original file) never saw the edit, and a later
     * restart found two files claiming the same pipeline id. The registered path is only trusted
     * when it is ITSELF inside the write root — a pipeline registered from outside it (a read-only
     * seed/fixture living elsewhere) is not writable there, so a save falls back to the canonical
     * path the same as a brand-new pipeline, same as before this fix. Whether to overlay existing
     * content is decided by whether {@code target} already exists on disk, not by registration —
     * a pipeline's first save (nothing registered yet) still overlays its own just-written file on
     * every save after the first.
     */
    private Object saveGraph(ApiContext api, HttpExchange e, String name, Map<String, Object> body) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "pipeline write");
        Map<String, Object> withId = new LinkedHashMap<>(body);
        withId.put("name", name);   // the URL name wins over any name in the body
        PipelineGraph g = parseAndValidateFlow(api, withId);

        Optional<Path> registered = api.service().pathFor(name).map(Path::normalize)
                .filter(p -> p.startsWith(writeRoot));
        Path target = WriteGates.jail(writeRoot,
                registered.orElseGet(() -> writeRoot.resolve(WriteGates.safeName(name, "pipeline name") + "_pipeline.toon")),
                "resolved path");
        Map<String, Object> existing = Files.exists(target)
                ? ConfigLoader.filesystem().decode(target.toString()) : new LinkedHashMap<>();

        Map<String, Object> lowered;
        try {
            lowered = PipelineEditable.lower(g, existing, g.active() || existing.isEmpty());
        } catch (PipelineCompileException ex) {
            List<Map<String, Object>> refusals = new ArrayList<>();
            for (PipelineCompileException.Refusal r : ex.refusals()) {
                Map<String, Object> rm = new LinkedHashMap<>();
                rm.put("code", r.code());
                if (r.nodeId() != null) rm.put("nodeId", r.nodeId());
                rm.put("message", r.message());
                refusals.add(rm);
            }
            return ApiContext.respondJson(e, 422, Map.of("written", false, "refusals", refusals));
        }

        // The same gate POST /config/write runs — the graph editor is a caller, not a second pipe.
        List<Finding> findings = new ArrayList<>(ConfigLoader.filesystem().validate(ConfigSpecs.pipeline(), lowered));
        // ⚠ The config's OWN directory goes in: a config ref resolves config-relative first (W1b), and
        // without it this ERROR gate resolved the portable bare `<name>.toon` against the working
        // directory and refused a schema sitting right beside its pipeline — short-circuiting the save
        // before the two checks below, which resolve correctly, could run.
        findings.addAll(ConfigSafetyValidator.check("pipeline", lowered, SafetyPolicy.defaultPolicy(),
                target.getParent()));
        // W3: against the file's OWN directory — a reference resolves config-relative first, so the
        // portable bare `<name>.toon` the Parse drawer writes would otherwise warn on every save.
        findings.addAll(ConfigRoutes.schemaFileFindings("pipeline", lowered, Severity.WARNING, target.getParent()));
        // The arming pre-checks /config/write and /config/patch already run (ERROR when active,
        // WARNING on an inactive draft). Without them this route answered 200 written:true for a
        // config that then failed to arm at the next ConfigRegistry.rebuild — one WARN log, the
        // pipeline silently skipped every cycle.
        findings.addAll(ConfigRoutes.armedWithoutSchemaFindings("pipeline", lowered));
        findings.addAll(ConfigRoutes.routeArmingFindings("pipeline", lowered));
        findings.addAll(ConfigRoutes.stepDisableFindings("pipeline", lowered));
        findings.addAll(ConfigRoutes.dedupWindowFindings("pipeline", lowered));
        if (findings.stream().anyMatch(f -> f.severity() == Severity.ERROR))
            return ApiContext.respondJson(e, 422, Map.of("written", false,
                    "error", "config has ERROR-level findings; not written", "findings", findings));

        byte[] bytes = ConfigCodec.toToon(lowered).getBytes(StandardCharsets.UTF_8);
        AtomicFiles.write(target, bytes, ".cfg-");
        log.info("[PIPELINE-WRITE] lowered graph '{}' to {} ({} bytes)", name, target.getFileName(), bytes.length);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("written", true);
        r.put("path", writeRoot.relativize(target).toString().replace('\\', '/'));
        r.put("name", name);
        r.put("findings", findings);
        return r;
    }

    /** Synthesize the companion-enrichment nodes for {@code editableGraph} (read-only projection). */
    @SuppressWarnings("unchecked")
    private void attachCompanionEnrichments(ApiContext api, String pipeline, Map<String, Object> editable) {
        Path root = api.writeRoot();
        if (root == null || !Files.isDirectory(root)) return;
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) editable.get("nodes");
        List<Map<String, Object>> edges = (List<Map<String, Object>>) editable.get("edges");
        String sinkId = nodes.stream()
                .filter(n -> "sink.persistent".equals(n.get("type")))
                .filter(n -> n.get("config") instanceof Map<?, ?> c && c.get("database") != null)
                .map(n -> String.valueOf(n.get("id"))).findFirst().orElse(null);
        try (var files = Files.list(root)) {
            for (Path p : files.filter(f -> f.getFileName().toString().endsWith("_enrich.toon")).toList()) {
                Map<String, Object> enrich;
                try {
                    enrich = ConfigLoader.filesystem().decode(p.toString());
                } catch (Exception ex) {
                    continue;   // an unreadable companion never breaks the pipeline's own projection
                }
                if (!(enrich.get("triggers") instanceof Map<?, ?> t)
                        || !pipeline.equalsIgnoreCase(String.valueOf(t.get("on_pipeline")))) continue;
                String ename = enrich.get("name") instanceof String s && !s.isBlank()
                        ? s : p.getFileName().toString().replaceFirst("_enrich\\.toon$", "");
                Map<String, Object> nm = new LinkedHashMap<>();
                nm.put("id", ename);
                nm.put("type", "enrichment");
                nm.put("name", ename);
                nm.put("use", "enrichment/" + ename);
                nodes.add(nm);
                if (sinkId != null) {
                    Map<String, Object> em = new LinkedHashMap<>();
                    em.put("from", sinkId);
                    em.put("rel", "data");
                    em.put("to", ename);
                    edges.add(em);
                }
            }
        } catch (IOException ignored) {
            // listing failed — serve the pipeline's own graph without companions
        }
    }

    /** Parse a flow definition (400 on a malformed shape) and validate it (422 on validation errors). */
    private PipelineGraph parseAndValidateFlow(ApiContext api, Map<String, Object> body) {
        PipelineGraph g;
        try {
            g = PipelineCodec.fromMap(body);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        validatePipeline(api, g);
        return g;
    }

    /**
     * Validate a graph, including that every {@code use:} binding names a component that EXISTS — so a
     * typo'd or unregistered reference is refused here (422) instead of degrading to the node's local
     * config and surfacing much later, wherever the missing key is read.
     *
     * <p>The existence check is skipped when the space has no write root: {@link #componentRegistry}
     * yields an EMPTY registry there, against which every binding would look dangling. A read-only space
     * cannot save anyway, and a draft-validate over one must not invent refusals.
     */
    private void validatePipeline(ApiContext api, PipelineGraph g) {
        ComponentRegistry registry = api.writeRoot() == null ? null : componentRegistry(api);
        PipelineValidator.Result r = PipelineValidator.validate(g, registry);
        if (!r.ok())
            throw new ApiException(422, "pipeline validation failed: " + r.errors().stream()
                    .map(i -> i.code() + " — " + i.message()).toList());
    }

    /**
     * {@code POST /pipelines/authored/{id}/dry-run} — run a bounded sample through an authored flow's
     * transform→sink subgraph on a throwaway DuckDB (T18, §7.2); per-node + per-sink row counts. 404 if the
     * flow is absent, 400 on a bad sample, 422 on a validation/SQL error. Never touches production output.
     *
     * <p>A {@code pipeline} body key dry-runs that <b>candidate</b> graph instead of the stored one, so the
     * editor can preview an edit before saving it — and diff the two by running once with the key and once
     * without. The candidate is never written anywhere: it is parsed, validated and executed on the scratch
     * database like any other graph. With the key present the stored flow is not consulted at all, so a
     * draft of a pipeline that does not exist yet previews too (no 404).
     */
    private Object dryRunFlow(ApiContext api, String id, Map<String, Object> body) {
        PipelineGraph g = candidateGraph(api, body);
        if (g == null) {
            Path root = PipelineSupport.pipelinesRootOrNull(api);
            try {
                g = root == null ? null : new PipelineStore(root).get(id).orElse(null);
            } catch (IllegalArgumentException e) {
                throw new ApiException(400, e.getMessage());
            }
            // W5: the editor now edits registered pipelines too — fall back to the lifted config.
            if (g == null) g = api.service().configFor(id).map(PipelineLift::lift).orElse(null);
            if (g == null) throw new ApiException(404, "no authored pipeline '" + id + "'");
        }
        try {
            return PipelineDryRun.run(componentRegistry(api).effectiveGraph(g), ApiContext.sampleRows(body),
                    dryRunReferences(api));
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        } catch (Exception e) {
            throw new ApiException(422, "dry-run failed: " + e.getMessage());
        }
    }

    /**
     * How many parsed rows are fed to the graph preview. Bounded because {@link PipelineDryRun} works
     * in memory — a picked file can be arbitrarily large. When the parse produced more than this, the
     * response says so in {@code warnings} rather than quietly reporting sample counts as totals.
     */
    private static final int TEST_RUN_SEED_ROWS = 1000;

    /**
     * {@code POST /pipelines/authored/{id}/run?to={nodeId}} — <b>run-to-here</b>: parse the caller's
     * <em>real</em> inbox files through the real ingest path into a scratch root, then preview the graph
     * over the parsed rows. Build→Test→Run Step 5c. Never writes outside the scratch root and never fires
     * a production run — see {@link PipelineTestRun} for the two containments that guarantee that.
     *
     * <p><b>The {@code files} body is caller-supplied, so it is jailed.</b> Entries are
     * <em>connection-relative</em> (the picker fills them from {@code GET /connections/{id}/explore},
     * whose {@code ResourceNode.path} is relativized against the profile's {@code base_path}). The jail
     * root is <b>derived here from the pipeline's own config</b> — its {@code source.connection} profile,
     * or {@code dirs.poll} when it binds none — and is <b>never</b> taken from the request, which is why
     * the body carries no connection id. Containment reuses {@link LocalConnectionWorkbench#jail}, the
     * same primitive the picker uses, so the two cannot disagree about what is reachable. An escape is
     * {@code PathEscape} → <b>403</b>.
     *
     * <p>404 unknown pipeline · 400 empty/absent {@code files} · 403 path escape · 422 parse or preview
     * failure · 501 a non-local connection (nothing local to stage from; those files reach the inbox via
     * acquisition first).
     */
    private Object testRun(ApiContext api, String id, String to, Map<String, Object> body) {
        PipelineConfig cfg = api.service().configFor(id)
                .orElseThrow(() -> new ApiException(404, "no authored pipeline '" + id + "'"));
        List<String> files = fileList(body);
        Path jailRoot = testRunRoot(api, cfg);

        List<Path> picked = new ArrayList<>();
        for (String f : files) {
            try {
                Path p = LocalConnectionWorkbench.jail(jailRoot, f);
                if (!Files.isRegularFile(p)) throw new ApiException(404, "no such file: " + f);
                picked.add(p);
            } catch (ConnectionWorkbench.PathEscape e) {
                throw new ApiException(403, "file '" + f + "' escapes the pipeline's source root");
            }
        }

        PipelineGraph g = graphFor(api, id);
        Path scratch;
        try {
            scratch = Files.createTempDirectory("inspecto_testrun_");
        } catch (IOException e) {
            throw new ApiException(500, "could not create a scratch root: " + e.getMessage());
        }
        try {
            PipelineTestRun.Result parsed = PipelineTestRun.run(cfg, picked, scratch);
            List<Map<String, Object>> seed =
                    PipelineTestRun.sampleRows(parsed, cfg.output().format(), TEST_RUN_SEED_ROWS);

            List<String> warnings = new ArrayList<>();
            if (parsed.totalInputRows() > seed.size())
                warnings.add("per-step row counts are over a sample of " + seed.size() + " of "
                        + parsed.totalInputRows() + " parsed rows; the output row count is the full figure");
            for (PipelineTestRun.FileResult f : parsed.files())
                if (!"SUCCESS".equals(f.status()))
                    warnings.add("file '" + f.filename() + "' was " + f.status().toLowerCase()
                            + (f.error() == null || f.error().isBlank() ? "" : ": " + f.error()));

            if (seed.isEmpty()) {
                warnings.add("no rows were parsed from the chosen file(s), so no step could be previewed");
                return runResult(null, to, files, parsed, cfg, warnings);
            }
            // `to` bounds the graph preview only: the picked files are always parsed in full, because the
            // parse is what seeds the walk. So a cutoff makes the answer narrower, never the work smaller.
            PipelineDryRun.Result preview = PipelineDryRun.run(
                    componentRegistry(api).effectiveGraph(g), seed, dryRunReferences(api),
                    to == null || to.isBlank() ? null : to);
            warnings.addAll(preview.warnings());
            return runResult(preview, to, files, parsed, cfg, warnings);
        } catch (ApiException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        } catch (Exception e) {
            throw new ApiException(422, "test run failed: " + e.getMessage());
        } finally {
            PipelineTestRun.deleteScratch(scratch);
        }
    }

    /**
     * The jail root for a run-to-here, derived from the pipeline itself: its bound {@code source.connection}
     * profile's {@code base_path}, or {@code dirs.poll} when it binds no connection (plain local inbox).
     * A non-{@code local} connector has no local path to stage from — 501 rather than resolving to
     * something surprising.
     */
    private Path testRunRoot(ApiContext api, PipelineConfig cfg) {
        if (!cfg.collector().hasConnection())
            return Paths.get(cfg.dirs().poll()).toAbsolutePath().normalize();
        String connId = cfg.collector().connection();
        ConnectionProfile p = api.service().connection(connId)
                .orElseThrow(() -> new ApiException(404, "pipeline's connection '" + connId + "' is not registered"));
        if (!"local".equalsIgnoreCase(p.connector()))
            throw new ApiException(501, "run-to-here supports local sources only; connection '" + connId
                    + "' is '" + p.connector() + "' — those files reach the inbox via acquisition first");
        if (p.basePath() == null || p.basePath().isBlank())
            throw new ApiException(422, "connection '" + connId + "' has no base_path configured");
        return Paths.get(p.basePath().trim()).toAbsolutePath().normalize();
    }

    /** The authored graph for a run-to-here: the stored pipeline, else the lifted config. 404 if neither. */
    private PipelineGraph graphFor(ApiContext api, String id) {
        Path root = PipelineSupport.pipelinesRootOrNull(api);
        PipelineGraph g = null;
        try {
            g = root == null ? null : new PipelineStore(root).get(id).orElse(null);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        if (g == null) g = api.service().configFor(id).map(PipelineLift::lift).orElse(null);
        if (g == null) throw new ApiException(404, "no authored pipeline '" + id + "'");
        return g;
    }

    /** The {@code files} body key — a non-empty list of connection-relative paths. */
    private static List<String> fileList(Map<String, Object> body) {
        Object raw = body == null ? null : body.get("files");
        if (!(raw instanceof List<?> l) || l.isEmpty())
            throw new ApiException(400, "body must include a non-empty 'files' list");
        List<String> out = new ArrayList<>();
        for (Object o : l) {
            String s = o == null ? null : String.valueOf(o).trim();
            if (s == null || s.isEmpty()) throw new ApiException(400, "'files' contains a blank entry");
            out.add(s);
        }
        return out;
    }

    /**
     * Project to the UI's {@code PipelineRunResult}. ⚠ Two grains meet here deliberately: {@code relations}
     * count the <b>seeded sample</b>, while {@code output.rowCount} is the <b>full</b> parse — a warning
     * names the difference whenever they can disagree.
     */
    private static Map<String, Object> runResult(PipelineDryRun.Result preview, String to, List<String> files,
                                                 PipelineTestRun.Result parsed, PipelineConfig cfg,
                                                 List<String> warnings) {
        List<Map<String, Object>> relations = new ArrayList<>();
        if (preview != null)
            for (PipelineDryRun.NodeDryRun n : preview.nodes())
                for (PipelineDryRun.RelationCount r : n.relations())
                    relations.add(Map.of("node", n.node(), "rel", r.rel(),
                            "rowCount", r.rowCount(), "rows", r.rows()));
        Map<String, Object> out = null;
        if (!parsed.outputs().isEmpty())
            out = Map.of("store", "scratch",
                    "format", String.valueOf(cfg.output().format()),
                    "path", parsed.outputs().get(0).outputFile(),
                    "rowCount", parsed.rowsWritten());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seedNode", preview == null ? "" : preview.seedNode());
        m.put("toNode", to == null ? "" : to);
        m.put("files", files);
        m.put("relations", relations);
        m.put("output", out);
        m.put("warnings", List.copyOf(warnings));
        return m;
    }

    /** View-name prefix for a reference resolved during a dry-run (its own scratch database). */
    private static final String DRYRUN_REF_VIEW_PREFIX = "dryrun_ref";

    /**
     * <b>DRYRUN-1 — the dry-run's {@link RowShaper.ReferenceResolver}.</b> Without one, every
     * {@code transform.join} pipeline was un-dry-runnable: the walk reached the join node and
     * {@link RowShaper.ReferenceResolver#NONE} refused, 422-ing the whole preview. Resolution goes through
     * the shared {@link ReferenceReader} — the same one the production join executor
     * ({@code PipelineJobRunner.references}) and the Stage-2 {@code EnrichmentEngine} use — so a versioned
     * reference store's current/as-of view cannot mean one thing in a preview and another in a real run.
     * The view is created on the throwaway dry-run connection and dies with it.
     *
     * <p>⚠ <b>Deliberately NOT path-jailed</b>, against the backlog row's own stated constraint. A
     * {@code path:} reference names a <em>data</em> file, which routinely lives outside the config write
     * root, so jailing it there would refuse legitimate references — and it would buy nothing, because
     * {@code POST /enrichment/preview} already resolves the very same {@code path:} references through the
     * very same reader with no jail and no write root at all. If arbitrary-path reads through a preview are
     * a concern, they are a concern about <em>both</em> surfaces and need one deliberate answer; a jail on
     * this route alone would be security theatre that breaks working configs.
     */
    private static RowShaper.ReferenceResolver dryRunReferences(ApiContext api) {
        return (conn, reference) -> {
            String sql;
            try {
                sql = ReferenceReader.sqlFor(ReferenceReader.parse(reference), api.service().loadedPipelines());
            } catch (RuntimeException unresolvable) {
                throw new ApiException(422, "dry-run cannot resolve reference '" + reference + "': "
                        + unresolvable.getMessage());
            }
            String view = DRYRUN_REF_VIEW_PREFIX + "_" + reference.replaceAll("[^A-Za-z0-9._-]", "_");
            try (java.sql.Statement st = conn.createStatement()) {
                st.execute("CREATE OR REPLACE VIEW \"" + view + "\" AS SELECT * FROM " + sql);
            }
            return view;
        };
    }

    /**
     * The candidate graph in a dry-run body's {@code pipeline} key, or {@code null} when the caller wants the
     * stored flow. Goes through the same {@link #parseAndValidateFlow} the save route uses — a draft that
     * could not be saved must not preview as if it could (400 malformed / 422 invalid, identically).
     */
    private PipelineGraph candidateGraph(ApiContext api, Map<String, Object> body) {
        if (body == null || !(body.get("pipeline") instanceof Map<?, ?>)) return null;
        return parseAndValidateFlow(api, mapAt(body, "pipeline"));
    }

    /**
     * The component registry backing {@code use:} resolution for a run. Reads only, and a space with writes
     * disabled simply resolves nothing — the same null-tolerant read-store shape the other routes use, since a
     * dry-run over a graph with no references must still work.
     */
    private static ComponentRegistry componentRegistry(ApiContext api) {
        Path root = api.writeRoot();
        return root == null ? ComponentRegistry.empty() : ComponentRegistry.scan(root.resolve("registry"));
    }

    /**
     * {@code POST /pipelines/authored/{id}/trigger} — run an authored flow for real, once, config-less (T32
     * follow-up): no {@code type: pipeline} {@code *_job.toon} needed. The fire goes through
     * {@link com.gamma.job.JobService#triggerPipelineRun} so it gets the full registered-run lifecycle
     * (deletion-fence tracking, non-overlap, durable run ledger) without registering a job. Async:
     * {@code 202} + {@code {runId,...}} + a {@code Location} to poll ({@code GET /jobs/runs/{runId}});
     * optional {@code ?actor=} attributes the fire. 503 without a write root, 404 if the flow is absent.
     */
    private Object runPipeline(ApiContext api, HttpExchange e, String id) throws IOException {
        Path root = SpaceRoot.pipelinesSubdir(WriteGates.requireWriteRoot(api, "pipeline run"));
        if (!new PipelineStore(root).exists(id)) throw new ApiException(404, "no authored pipeline '" + id + "'");
        String runId;
        try {
            runId = api.service().jobServiceOrCreate().triggerPipelineRun(id, ApiContext.query(e, "actor"));
        } catch (IllegalStateException ex) {
            // the service booted without a write root, so its flow store never opened — same gate as above
            throw new ApiException(503, ex.getMessage());
        }
        log.info("[PIPELINE-RUN] ad-hoc run {} of authored pipeline {}", runId, id);
        e.getResponseHeaders().set("Location", (ApiContext.v1(e) ? "/api/v1" : "") + "/jobs/runs/" + runId);
        return ApiContext.respondJson(e, 202, Map.of("runId", runId, "pipeline", id, "status", "running"));
    }
}
