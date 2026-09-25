package com.gamma.control;

import com.gamma.config.io.ConfigCodec;
import com.gamma.config.io.ConfigLoader;
import com.gamma.config.spec.Finding;
import com.gamma.etl.PipelineConfig;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.PipelineCodec;
import com.gamma.pipeline.PipelineDocument;
import com.gamma.pipeline.PipelineDocumentXlsx;
import com.gamma.pipeline.PipelineCompileException;
import com.gamma.pipeline.PipelineEditable;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineProjection;
import com.gamma.pipeline.PipelineStore;
import com.gamma.pipeline.PipelineValidator;
import com.gamma.pipeline.RecipeConverter;
import com.gamma.pipeline.PipelineLift;
import com.gamma.pipeline.PipelineRel;
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
import com.gamma.util.DuckDbUtil;
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
        api.get("/pipelines/([^/]+)/graph", (e, m) -> graphForPipeline(api, e, ApiContext.name(m)));
        // The Pipeline Document (ELT amendment §5.1): a read-only Markdown projection of config for
        // business verification and sign-off. A read, not an authoring action — no capability gate.
        api.get("/pipelines/([^/]+)/document", (e, m) -> document(api, e, ApiContext.name(m)));
        // W5 (plan U-A): the editable round-trip over the canonical *_pipeline.toon.
        api.get("/pipelines/([^/]+)/graph/raw", (e, m) -> editableGraph(api, e, ApiContext.name(m)));
        api.put("/pipelines/([^/]+)/graph", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> saveGraph(api, e, ApiContext.name(m), api.body(e))));
    }

    /**
     * {@code GET /pipelines/{name}/graph} — lift one registered pipeline to its graph; 404 if no such
     * pipeline.
     *
     * <p>🔴 <b>This is the READ-ONLY projection, and the response now says so</b> (D9 / {@code WB-18},
     * 2026-09-22). PUTting this body back is refused — measured across all 26 shipped Pipelines, always
     * 422 and always {@code written:false}, so it fails closed — but it is the natural wrong guess for any
     * client, and nothing at the route said otherwise ({@code GRAPH-READ-SHAPE-NOT-WRITE-SHAPE-1}). The
     * authoring pair is {@code GET …/graph/raw} + {@code PUT …/graph}.
     *
     * <p>⛔ <b>Renaming it to {@code /graph/view} was considered and DECLINED</b> (D9): a rename breaks
     * every client for a discoverability gain that a link provides.
     *
     * <p>⚠ The link is derived from the REQUEST path, never composed from a constant, because this route
     * is also reached under the {@code /spaces/{id}} prefix — a hardcoded {@code /api/v1/pipelines/…}
     * would hand a space-scoped caller a URL pointing outside its own space. ⚠ And the flag sits on the
     * RESOURCE rather than under a {@code metadata} key as D9's text sketched: the v1 envelope already
     * owns {@code metadata} and {@code links}, and shadowing them inside {@code data} would be two things
     * with one name — the very defect this plan spent its Sprint A closing.
     */
    private Object graphForPipeline(ApiContext api, HttpExchange ex, String name) {
        PipelineConfig c = api.service().configFor(name)
                .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "no pipeline named '" + name + "'"));
        Map<String, Object> out = new LinkedHashMap<>(PipelineProjection.graph(PipelineLift.lift(c)));
        out.put("readOnlyProjection", true);
        out.put("links", Map.of("roundTrip", ex.getRequestURI().getPath() + "/raw"));
        return out;
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
                .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "no pipeline named '" + name + "'"));
        Path file = api.service().pathFor(name)
                .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "no config file for pipeline '" + name + "'"));

        Map<String, Object> recipe = RecipeConverter.toRecipe(ConfigLoader.filesystem().decode(file.toString()));
        Map<String, Map<String, Object>> components = resolveDocumentRefs(api, cfg, recipe);

        Map<String, Object> hashed = new LinkedHashMap<>();
        hashed.put("recipe", recipe);
        hashed.put("components", components);
        String fingerprint = ContentHash.of(hashed);

        ex.getResponseHeaders().set("X-Config-Fingerprint", fingerprint);

        // D-8: `?format=xlsx` renders the SAME model as a workbook, for the sign-off surface that wants
        // one. ⛔ Both renderings take every value - and every mask - from PipelineDocumentModel; the
        // fingerprint above is unaffected because it hashes the recipe and components, never the rendering.
        if ("xlsx".equalsIgnoreCase(ApiContext.query(ex, "format"))) {
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile("pipeline-document-", ".xlsx");
            try {
                PipelineDocumentXlsx.write(cfg.identity().pipelineName(), recipe, components, fingerprint, tmp);
                return ApiContext.respondBinary(ex, java.nio.file.Files.readAllBytes(tmp),
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        cfg.identity().pipelineName() + ".xlsx");
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new ApiException(500, ErrorCodes.INTERNAL, "could not render the workbook: " + e.getMessage());
            } finally {
                java.nio.file.Files.deleteIfExists(tmp);
            }
        }
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
     *
     * <p><b>STORE-CONFLICT-DETECTION-1:</b> carries a strong {@code ETag} over the on-disk config
     * ({@code raw}) — the same bytes {@link #saveGraph} hashes for its {@code If-Match} check — so an
     * editor that reads here and later saves through {@code PUT .../graph} can detect a concurrent
     * edit exactly like {@code ComponentRoutes}' component CRUD (W3 optimistic locking).
     */
    private Object editableGraph(ApiContext api, HttpExchange ex, String name) throws IOException {
        PipelineConfig cfg = api.service().configFor(name)
                .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "no pipeline named '" + name + "'"));
        Path file = api.service().pathFor(name)
                .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "no config file for pipeline '" + name + "'"));
        Map<String, Object> raw = ConfigLoader.filesystem().decode(file.toString());
        ETags.set(ex, ETags.of(ContentHash.of(raw)));
        Map<String, Object> editable = PipelineEditable.toMap(cfg, raw);
        attachCompanionEnrichments(api, cfg.identity().pipelineName(), editable);
        return editable;
    }

    /**
     * {@code PUT /pipelines/{name}/graph} — <b>lower the graph to the canonical config</b> (W5, U-A):
     * decode + structurally validate the posted graph (URL name authoritative), lower it over the
     * pipeline's <b>existing registered file</b> ({@link PipelineEditable#lower} — verbatim sections,
     * unmodeled keys preserved), run the one content gate every save path runs ({@link SaveGate}), and
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
     *
     * <p><b>STORE-CONFLICT-DETECTION-1:</b> honours an optional {@code If-Match} precondition against
     * the existing file's content hash — the same {@code ETags} pattern {@code ComponentRoutes} uses
     * for the component registry — so two editors racing to save the same pipeline get a {@code 409
     * CONFLICT_STALE_VERSION} instead of a silent last-write-wins clobber. A brand-new pipeline (no
     * existing file) has nothing to be stale against, so the precondition is only checked on an update.
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
        boolean existsOnDisk = Files.exists(target);
        Map<String, Object> existing = existsOnDisk
                ? ConfigLoader.filesystem().decode(target.toString()) : new LinkedHashMap<>();
        if (existsOnDisk) ETags.requireMatch(e, ETags.of(ContentHash.of(existing)));

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

        // The ONE content gate every save path runs (SaveGate) — the graph editor is a caller, not a
        // second pipe. 🔴 Until G3 (2026-09-23) this was a hand-kept copy that claimed to be "the same
        // gate" /config/write ran, and was not: it lacked the unknown-Connection and unknown-key checks.
        // Against the file's OWN directory, so the portable bare `<name>.toon` resolves beside it.
        List<Finding> findings = SaveGate.check(api, "pipeline", lowered, writeRoot, target.getParent(),
                SaveGate.Referents.MUST_EXIST);
        if (SaveGate.refuses(findings))
            return ApiContext.respondJson(e, 422, Map.of("written", false,
                    "error", "config has ERROR-level findings; not written", "findings", findings));

        byte[] bytes = ConfigCodec.toToon(lowered).getBytes(StandardCharsets.UTF_8);
        AtomicFiles.write(target, bytes, ".cfg-");
        PipelineHistory.record(writeRoot, target);   // PIPELINE-CONFIG-HISTORY-1
        // GET …/graph/raw lifts the REGISTERED config, so without this a reopen straight after Save served
        // the pre-save graph until the next poll cycle (the history restore route does the same).
        api.service().refreshConfigs();
        log.info("[PIPELINE-WRITE] lowered graph '{}' to {} ({} bytes)", name, target.getFileName(), bytes.length);
        // The etag a next save (or a re-read) must accept — the same bytes just written, not the pre-save hash.
        ETags.set(e, ETags.of(ContentHash.of(lowered)));

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("written", true);
        r.put("path", writeRoot.relativize(target).toString().replace('\\', '/'));
        r.put("name", name);
        r.put("findings", findings);
        return r;
    }

    /**
     * The rel of the <b>derived, display-only</b> edge {@link #attachCompanionEnrichments} draws from the
     * pipeline's persistent sink to each companion enrichment node ({@code {from, rel: "companion", to,
     * derived: true}}). It is not a data-flow relationship: the companion runs AFTER the pipeline's commit,
     * off its own {@code *_enrich.toon} ({@code triggers.on_pipeline}), so no rel the validator knows fits it
     * — a {@code data} edge out of a sink is {@code ILLEGAL_EMIT} (GRAPH-RAW-COMPANION-ENRICHMENT-ILLEGAL-EMIT-1).
     * {@link #withoutDerivedEdges} drops it again before any save or candidate dry run parses the graph.
     */
    static final String COMPANION_REL = "companion";

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
                    em.put("rel", COMPANION_REL);
                    em.put("to", ename);
                    em.put("derived", true);
                    edges.add(em);
                }
            }
        } catch (IOException ignored) {
            // listing failed — serve the pipeline's own graph without companions
        }
    }

    /**
     * {@code body} minus every derived display-only edge ({@code rel: companion, derived: true}) that
     * {@code GET .../graph/raw} synthesizes — so an untouched open → save or candidate dry run never
     * persists or validates it as data flow. Only that exact kind is dropped: a hand-authored {@code data}
     * edge out of a sink still reaches the validator and is still refused {@code ILLEGAL_EMIT}.
     */
    static Map<String, Object> withoutDerivedEdges(Map<String, Object> body) {
        if (!(body.get("edges") instanceof List<?> edges)) return body;
        List<Object> kept = new ArrayList<>();
        for (Object o : edges)
            if (!(o instanceof Map<?, ?> em && Boolean.TRUE.equals(em.get("derived"))
                    && COMPANION_REL.equals(em.get("rel")))) kept.add(o);
        if (kept.size() == edges.size()) return body;
        Map<String, Object> out = new LinkedHashMap<>(body);
        out.put("edges", kept);
        return out;
    }

    /** Parse a pipeline definition (400 on a malformed shape) and validate it (422 on validation errors). */
    private PipelineGraph parseAndValidateFlow(ApiContext api, Map<String, Object> body) {
        PipelineGraph g;
        try {
            g = PipelineCodec.fromMap(withoutDerivedEdges(body));
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, e.getMessage());
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
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "pipeline validation failed: " + r.errors().stream()
                    .map(i -> i.code() + " — " + i.message()).toList());
        if (registry != null) checkJoinReferences(api, g);
    }

    /** The refusal code for a {@code transform.join} whose {@code reference} names nothing that exists. */
    static final String UNKNOWN_JOIN_REFERENCE = "UNKNOWN_JOIN_REFERENCE";

    /**
     * AUTHORING-REDESIGN-1 (f): a {@code transform.join}'s {@code reference/<pipeline>} binding is not a
     * {@code use:} ref (no component dir to scan), so {@link PipelineValidator}'s {@code UNKNOWN_USE_REF}
     * never saw it and a typo surfaced at the first row of the first run. Resolve it here exactly as the
     * run does — {@link ReferenceReader#parse} then the loaded-pipeline context — and 422 at save instead.
     * A {@code path:} reference names a data file that may legitimately not exist yet; only its SYNTAX is
     * checked. Which columns the reference carries is still the dry-run's question (it reads the store).
     */
    private static void checkJoinReferences(ApiContext api, PipelineGraph g) {
        for (com.gamma.pipeline.PipelineNode n : g.nodes()) {
            if (!com.gamma.pipeline.BuiltinNodeType.TRANSFORM_JOIN.type().equals(n.type())) continue;
            Object raw = n.cfg("reference");
            if (raw == null || raw.toString().isBlank()) continue;   // JOIN_REFERENCE_MISSING already refused it
            com.gamma.enrich.EnrichmentConfig.Reference ref;
            try {
                ref = ReferenceReader.parse(raw.toString());
            } catch (IllegalArgumentException e) {
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "pipeline validation failed: [" + UNKNOWN_JOIN_REFERENCE
                        + " — Node '" + n.id() + "' (transform.join): " + e.getMessage() + "]");
            }
            if (!ref.byName()) continue;
            PipelineConfig target = api.service().loadedPipelines().stream()
                    .filter(p -> p.identity().pipelineName().equals(ref.ref()))
                    .findFirst().orElse(null);
            if (target == null)
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "pipeline validation failed: [" + UNKNOWN_JOIN_REFERENCE
                        + " — Node '" + n.id() + "' (transform.join) joins '" + raw + "' but no pipeline named '"
                        + ref.ref() + "' is loaded; a by-name reference must be a loaded pipeline that declares "
                        + "produces: reference (use a path: for a plain file).]");
            if (!target.producesReference())
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "pipeline validation failed: [" + UNKNOWN_JOIN_REFERENCE
                        + " — Node '" + n.id() + "' (transform.join) joins '" + raw + "' but pipeline '"
                        + ref.ref() + "' does not declare produces: reference.]");
        }
    }

    /**
     * {@code POST /pipelines/authored/{id}/dry-run} — run a bounded sample through an authored pipeline's
     * transform→sink subgraph on a throwaway DuckDB (T18, §7.2); per-node + per-sink row counts. 404 if the
     * pipeline is absent, 400 on a bad sample, 422 on a validation/SQL error. Never touches production output.
     *
     * <p>A {@code pipeline} body key dry-runs that <b>candidate</b> graph instead of the stored one, so the
     * editor can preview an edit before saving it — and diff the two by running once with the key and once
     * without. The candidate is never written anywhere: it is parsed, validated and executed on the scratch
     * database like any other graph. With the key present the stored pipeline is not consulted at all, so a
     * draft of a pipeline that does not exist yet previews too (no 404).
     */
    private Object dryRunFlow(ApiContext api, String id, Map<String, Object> body) {
        PipelineGraph g = candidateGraph(api, body);
        if (g == null) {
            // BUNDLE-AUTHORED-PIPELINE-STORE-1: the REGISTERED pipeline wins — a PipelineStore graph under the
            // same id must never shadow it. The store answers only for a grandfathered, unregistered id.
            g = api.service().configFor(id).map(PipelineLift::lift).orElse(null);
            Path root = PipelineSupport.pipelinesRootOrNull(api);
            try {
                if (g == null && root != null) g = new PipelineStore(root).get(id).orElse(null);
            } catch (IllegalArgumentException e) {
                throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, e.getMessage());
            }
            if (g == null) throw new ApiException(404, ErrorCodes.NOT_FOUND, "no authored pipeline '" + id + "'");
        }
        try {
            return PipelineDryRun.run(componentRegistry(api).effectiveGraph(g), ApiContext.sampleRows(body),
                    dryRunReferences(api));
        } catch (ApiException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, e.getMessage());
        } catch (Exception e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "dry-run failed: " + DuckDbUtil.withoutPendingQueryPreamble(e.getMessage()));
        }
    }

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
     * <p>404 unknown pipeline · 400 empty/absent {@code files} · 403 path escape · 422 a FAILED batch, parse or preview
     * failure · 501 a non-local connection (nothing local to stage from; those files reach the inbox via
     * acquisition first).
     */
    private Object testRun(ApiContext api, String id, String to, Map<String, Object> body) {
        PipelineConfig cfg = api.service().configFor(id)
                .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "no authored pipeline '" + id + "'"));
        List<String> files = fileList(body);
        Path jailRoot = testRunRoot(api, cfg);

        List<Path> picked = new ArrayList<>();
        for (String f : files) {
            try {
                Path p = LocalConnectionWorkbench.jail(jailRoot, f);
                if (!Files.isRegularFile(p)) throw new ApiException(404, ErrorCodes.NOT_FOUND, "no such file: " + f);
                picked.add(p);
            } catch (ConnectionWorkbench.PathEscape e) {
                throw new ApiException(403, ErrorCodes.PATH_JAIL_VIOLATION, "file '" + f + "' escapes the pipeline's source root");
            }
        }

        // BUNDLE-AUTHORED-PIPELINE-STORE-1: the REGISTERED config is what runs, never a PipelineStore graph
        // under the same id — this route already requires the registration, so the store has no say here.
        PipelineGraph g = PipelineLift.lift(cfg);
        Path scratch;
        try {
            // Short prefix: every partition path is written under this root, and Windows refuses a path
            // past ~260 chars (WINDOWS-LONG-SCRATCH-PATH-QUARANTINES-1).
            scratch = Files.createTempDirectory("itr_");
        } catch (IOException e) {
            throw new ApiException(500, ErrorCodes.INTERNAL, "could not create a scratch root: " + e.getMessage());
        }
        try {
            PipelineTestRun.Result parsed = PipelineTestRun.run(cfg, picked, scratch);
            // 🔴 A FAILED batch is the answer, not an empty one (TESTRUN-FAILED-BATCH-REPORTED-EMPTY-1). It
            // used to fall through to "no rows were parsed" — false, the file had parsed — or, where a raw
            // sample existed, to a clean preview of a failure the preview's steps do not reproduce. Either
            // way the batch's own error, the one message that explains it, never reached the author.
            if ("FAILED".equals(parsed.status()))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "test run failed: the batch FAILED after "
                        + parsed.totalInputRows() + " row(s) parsed: "
                        + DuckDbUtil.withoutPendingQueryPreamble(parsed.error()));
            // 🔴 Seeded with the PARSER's rows, captured before mapping (TESTRUN-SEED-IS-MAPPED-OUTPUT-1,
            // operator decision 2026-09-23) — never the rows the ingest wrote, which are already mapped and
            // made the preview's map re-apply itself to canonical columns. Grouped by SEGMENT (WB-08): a
            // segment-routed frontend lifts as `parse →(route:<segment>)→ map_<segment> → sink_<segment>`,
            // and the walk follows an edge only when the parse node produced that edge's relation
            // (TESTRUN-SEGMENT-ROUTE-NO-FLOW-1).
            Map<String, List<Map<String, Object>>> seedRelations = seedRelations(parsed.rawRows());
            List<Map<String, Object>> seed =
                    seedRelations.values().stream().flatMap(List::stream).toList();

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
            PipelineDryRun.Result preview = PipelineDryRun.runSeeded(
                    componentRegistry(api).effectiveGraph(g), seedRelations, dryRunReferences(api),
                    to == null || to.isBlank() ? null : to);
            warnings.addAll(preview.warnings());
            return runResult(preview, to, files, parsed, cfg, warnings);
        } catch (ApiException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, e.getMessage());
        } catch (Exception e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "test run failed: " + DuckDbUtil.withoutPendingQueryPreamble(e.getMessage()));
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
        // 🔴 A Dataset-fed Pipeline has no inbox to pick files from, and must SAY so (WB-07, 2026-09-22).
        // orders_by_region_feed (collector: dataset) used to answer a {files:[…]} test run with 200 and
        // "no rows were parsed from the chosen file(s)" — an empty SUCCESS for a request that can never
        // succeed, which reads as "your file is bad" rather than "this instrument does not apply here"
        // (TESTRUN-DATASET-COLLECTOR-SILENT-1). The connection branch below already refuses non-local
        // connectors 501; a Dataset feed is not local either, so it gets the same answer in the same words.
        if (cfg.collector().hasDataset())
            throw new ApiException(501, ErrorCodes.NOT_SUPPORTED, "run-to-here supports file-shaped sources only; this pipeline is fed "
                    + "by the Dataset '" + cfg.collector().dataset() + "' — there is no inbox file to pick. "
                    + "Preview the Dataset, or dry-run the Steps over sample rows.");
        if (!cfg.collector().hasConnection())
            return Paths.get(cfg.dirs().poll()).toAbsolutePath().normalize();
        String connId = cfg.collector().connection();
        ConnectionProfile p = api.service().connection(connId)
                .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "pipeline's connection '" + connId + "' is not registered"));
        if (!"local".equalsIgnoreCase(p.connector()))
            throw new ApiException(501, ErrorCodes.NOT_SUPPORTED, "run-to-here supports local sources only; connection '" + connId
                    + "' is '" + p.connector() + "' — those files reach the inbox via acquisition first");
        if (p.basePath() == null || p.basePath().isBlank())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "connection '" + connId + "' has no base_path configured");
        return Paths.get(p.basePath().trim()).toAbsolutePath().normalize();
    }

    /**
     * Turn {@code segment → rows} into {@code relation → rows}, using the graph's OWN branch-key rule.
     *
     * <p>⚠ The relation is {@link PipelineRel#route(String)} over {@link PipelineLift#routeKey} — the one
     * definition the lift itself used when it built the edges. The walk matches a seed to an edge by
     * exact string, so re-deriving the sanitisation here would be a name free to drift from the name it
     * has to equal.
     *
     * <p>⛔ An un-attributed group (key {@code null}) stays plain {@code data}: that is a single-schema
     * Pipeline, whose parse node has exactly one outgoing {@code data} edge. Guessing a branch for it
     * would invent a topology the author never wrote.
     */
    private static Map<String, List<Map<String, Object>>> seedRelations(
            Map<String, List<Map<String, Object>>> bySegment) {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : bySegment.entrySet()) {
            String rel = e.getKey() == null
                    ? PipelineRel.DATA
                    : PipelineRel.route(PipelineLift.routeKey(e.getKey(), 0));
            out.merge(rel, e.getValue(), (a, b) -> {
                List<Map<String, Object>> both = new ArrayList<>(a);
                both.addAll(b);
                return both;
            });
        }
        return out;
    }

    /** The {@code files} body key — a non-empty list of connection-relative paths. */
    private static List<String> fileList(Map<String, Object> body) {
        Object raw = body == null ? null : body.get("files");
        if (!(raw instanceof List<?> l) || l.isEmpty())
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "body must include a non-empty 'files' list");
        List<String> out = new ArrayList<>();
        for (Object o : l) {
            String s = o == null ? null : String.valueOf(o).trim();
            if (s == null || s.isEmpty()) throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "'files' contains a blank entry");
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
     * <p><b>A {@code path:} reference is jailed to {@link com.gamma.config.safety.PathJail#allowedRoots()}</b>
     * ({@code PREVIEW-REFERENCE-PATH-UNJAILED-1}, operator 2026-09-24) — NOT the write root, since a data file
     * routinely lives outside it. Outside the roots → 403 before the file is opened. This one resolver serves
     * both {@code POST …/dry-run} and {@code POST …/run?to=}; {@code POST /enrichment/preview} applies the same
     * jail through {@link WriteGates#jailToAllowedRoots}.
     */
    private static RowShaper.ReferenceResolver dryRunReferences(ApiContext api) {
        return (conn, reference) -> {
            String sql;
            com.gamma.enrich.EnrichmentConfig.Reference parsed;
            try {
                parsed = ReferenceReader.parse(reference);
            } catch (RuntimeException unresolvable) {
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "dry-run cannot resolve reference '" + reference + "': "
                        + unresolvable.getMessage());
            }
            if (!parsed.byName()) WriteGates.jailToAllowedRoots(parsed.path(), "transform.join.reference");
            try {
                sql = ReferenceReader.sqlFor(parsed, api.service().loadedPipelines());
            } catch (RuntimeException unresolvable) {
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "dry-run cannot resolve reference '" + reference + "': "
                        + unresolvable.getMessage());
            }
            String view = DRYRUN_REF_VIEW_PREFIX + "_" + reference.replaceAll("[^A-Za-z0-9._-]", "_");
            // 🔴 The view creation is INSIDE the guard (WB-05, 2026-09-22). A reference that parses but
            // whose file is not there fails HERE, and the raw DuckDB text used to reach the author:
            // "Invalid Input Error: Attempting to execute an unsuccessful or closed pending query result".
            // The SAVE path names the identical condition JOIN_REFERENCE_MISSING. One condition must not
            // have two vocabularies, and the engine-internal one is not actionable by an author
            // (TESTRUN-REFERENCE-REFUSAL-UNNAMED-1). The resolved target is carried, because "which file
            // did you actually look for" is the next question every time.
            try (java.sql.Statement st = conn.createStatement()) {
                st.execute("CREATE OR REPLACE VIEW \"" + view + "\" AS SELECT * FROM " + sql);
            } catch (java.sql.SQLException missing) {
                throw new ApiException(422, PipelineValidator.JOIN_REFERENCE_MISSING,
                        "reference '" + reference + "' could not be read — resolved to " + sql
                                + ". Seed the reference file, or point the node at one that exists.");
            }
            return view;
        };
    }

    /**
     * The candidate graph in a dry-run body's {@code pipeline} key, or {@code null} when the caller wants the
     * stored pipeline. Goes through the same {@link #parseAndValidateFlow} the save route uses — a draft that
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
     * {@code POST /pipelines/authored/{id}/trigger} — run an authored pipeline for real, once, config-less (T32
     * follow-up): no {@code type: pipeline} {@code *_job.toon} needed. The fire goes through
     * {@link com.gamma.job.JobService#triggerPipelineRun} so it gets the full registered-run lifecycle
     * (deletion-fence tracking, non-overlap, durable run ledger) without registering a job. Async:
     * {@code 202} + {@code {runId,...}} + a {@code Location} to poll ({@code GET /jobs/runs/{runId}});
     * optional {@code ?actor=} attributes the fire. 503 without a write root, 404 if the pipeline is absent.
     */
    private Object runPipeline(ApiContext api, HttpExchange e, String id) throws IOException {
        Path root = SpaceRoot.pipelinesSubdir(WriteGates.requireWriteRoot(api, "pipeline run"));
        if (!new PipelineStore(root).exists(id)) throw new ApiException(404, ErrorCodes.NOT_FOUND, "no authored pipeline '" + id + "'");
        String runId;
        try {
            runId = api.service().jobServiceOrCreate().triggerPipelineRun(id, ApiContext.query(e, "actor"));
        } catch (IllegalStateException ex) {
            // the service booted without a write root, so its pipeline store never opened — same gate as above
            throw new ApiException(503, ErrorCodes.CONTROL_PLANE_READ_ONLY, ex.getMessage());
        }
        log.info("[PIPELINE-RUN] ad-hoc run {} of authored pipeline {}", runId, id);
        e.getResponseHeaders().set("Location", (ApiContext.v1(e) ? "/api/v1" : "") + "/jobs/runs/" + runId);
        return ApiContext.respondJson(e, 202, Map.of("runId", runId, "pipeline", id, "status", "running"));
    }
}
