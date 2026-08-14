package com.gamma.control;

import com.gamma.config.io.ConfigCodec;
import com.gamma.config.io.ConfigLoader;
import com.gamma.config.spec.ConfigSpecs;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.Severity;
import com.gamma.config.safety.ConfigSafetyValidator;
import com.gamma.config.safety.SafetyPolicy;
import com.gamma.etl.PipelineConfig;
import com.gamma.event.Event;
import com.gamma.event.EventType;
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
import com.gamma.etl.PipelineConfig;
import com.gamma.inspector.PipelineTestRun;
import com.gamma.pipeline.exec.PipelineDryRun;
import com.gamma.pipeline.exec.RowShaper;
import com.gamma.service.CollectorService;
import com.gamma.service.DbStatusStore;
import com.gamma.service.SpaceRoot;
import com.gamma.util.AtomicFiles;
import com.gamma.util.MappingCsv;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Flow graph routes ({@code /pipelines*}): the read-only lifted-pipeline projections (T31) and the
 * authored-flow topology CRUD (T19, §7.1) persisted as {@code *_flow.toon} under the write root.
 * Extracted verbatim from {@link ControlApi}: identical routes, order, HTTP statuses and validation.
 */
final class PipelineRoutes implements RouteModule {

    private static final Logger log = LoggerFactory.getLogger(PipelineRoutes.class);

    @Override
    public void register(ApiContext api) {
        api.get("/pipelines", (e, m) -> pipelineSummaries(api));
        api.get("/pipelines/node-types", (e, m) -> PipelineProjection.catalog());
        // The recipe-verb palette (ELT amendment Phase 5): seven verbs + route + discovered plugins,
        // each with served attribute specs. node-types stays for the canvas + old-server fallback.
        api.get("/pipelines/step-types", (e, m) -> PipelineProjection.stepCatalog());
        api.get("/pipelines/combined", (e, m) -> combinedPipelines(api));
        // *_flow.toon is GRANDFATHERED (W5, plan U-A): existing files stay readable / runnable /
        // deletable, but are never newly written — the authoring write routes are gone; the graph
        // editor now writes the canonical *_pipeline.toon via PUT /pipelines/{name}/graph below.
        api.get("/pipelines/authored", (e, m) -> authoredPipelineList(api));
        api.get("/pipelines/authored/([^/]+)", (e, m) -> authoredPipeline(api, ApiContext.name(m)));
        api.get("/pipelines/authored/([^/]+)/raw", (e, m) -> authoredPipelineRaw(api, ApiContext.name(m)));
        api.delete("/pipelines/authored/([^/]+)", ApiContext.withCapability("canAuthorWorkbench", (e, m) -> deletePipeline(api, ApiContext.name(m))));
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
        // Authoring, not operating: copying a pipeline into a template writes config and runs nothing.
        api.post("/pipelines/([^/]+)/save-as-template", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> saveAsTemplate(api, e, ApiContext.name(m), api.body(e))));
        api.post("/pipelines/([^/]+)/label", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> relabel(api, e, ApiContext.name(m), api.body(e))));
        // D8 (pipeline-graph backlog): the pipeline-level produces/reference block is opaque
        // passthrough to the graph editor (PipelineEditable never models it) — this pair of
        // routes is its own dedicated authoring surface, independent of PUT .../graph.
        api.get("/pipelines/([^/]+)/settings", (e, m) -> pipelineSettings(api, ApiContext.name(m)));
        api.post("/pipelines/([^/]+)/settings", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> savePipelineSettings(api, e, ApiContext.name(m), api.body(e))));
        // T3: the full identity migration `label` deliberately doesn't do — see `rename`'s javadoc.
        api.post("/pipelines/([^/]+)/rename", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> rename(api, e, ApiContext.name(m), api.body(e))));
        // The finishing move for an interrupted rename — reads rename.journal back (resumeRename's javadoc).
        // No {name} segment: after a mid-migration crash the pipeline may be registered under neither id.
        api.post("/pipelines/rename/resume", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> resumeRename(api, e, api.body(e))));
    }

    /** Lift every registered pipeline to a {@link PipelineGraph} and project a compact summary (GET /pipelines). */
    private Object pipelineSummaries(ApiContext api) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CollectorService.PipelineView pv : api.service().pipelines()) {
            api.service().configFor(pv.name()).ifPresent(c -> {
                Map<String, Object> s = PipelineProjection.summary(PipelineLift.lift(c));
                // Emitted only when set, so an ordinary pipeline's payload is unchanged. Kept out of
                // PipelineGraph/PipelineProjection deliberately: `template` is a config-level lifecycle
                // flag like `active`, not part of the structural graph the projection describes.
                if (c.template()) s.put("template", true);
                // `name` in the projection is the lifted IDENTITY (PipelineLift uses pipelineName), which is
                // what every other route keys on — so the display name is carried separately, and only when
                // it actually differs. Absent ⇒ the id is the label, exactly as before.
                if (!c.identity().name().equals(c.identity().pipelineName()))
                    s.put("displayName", c.identity().name());
                out.add(s);
            });
        }
        return out;
    }

    /** Lift every registered pipeline and project the combined pipeline+job topology (GET /pipelines/combined, T24). */
    private Object combinedPipelines(ApiContext api) {
        return PipelineProjection.combined(liftedPipelines(api.service()));
    }

    /** Every registered pipeline lifted to a {@link PipelineGraph} (shared with the component safe-delete check). */
    static List<PipelineGraph> liftedPipelines(CollectorService service) {
        List<PipelineGraph> graphs = new ArrayList<>();
        for (CollectorService.PipelineView pv : service.pipelines()) {
            service.configFor(pv.name()).ifPresent(c -> graphs.add(PipelineLift.lift(c)));
        }
        return graphs;
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

    private Path pipelinesRootOrNull(ApiContext api) {
        return api.writeRoot() == null ? null : SpaceRoot.pipelinesSubdir(api.writeRoot());
    }

    private PipelineStore pipelineStore(ApiContext api) {
        return new PipelineStore(SpaceRoot.pipelinesSubdir(WriteGates.requireWriteRoot(api, "pipeline write")));
    }

    /** {@code GET /pipelines/authored} — summaries of every authored flow (empty when no write root). */
    private Object authoredPipelineList(ApiContext api) {
        Path root = pipelinesRootOrNull(api);
        if (root == null) return List.of();
        return new PipelineStore(root).list().stream().map(PipelineProjection::summary).toList();
    }

    /** {@code GET /pipelines/authored/{id}} — one authored flow's graph projection; 404 if absent. */
    private Object authoredPipeline(ApiContext api, String id) {
        Path root = pipelinesRootOrNull(api);
        PipelineGraph g = root == null ? null : new PipelineStore(root).get(id).orElse(null);
        if (g == null) throw new ApiException(404, "no authored flow '" + id + "'");
        return PipelineProjection.graph(g);
    }

    /**
     * {@code GET /pipelines/authored/{id}/raw} — the <b>lossless</b> authored definition ({@link PipelineCodec#toMap},
     * nodes with their config) so the editor can round-trip a flow without dropping node config; the
     * {@link #authoredPipeline} projection is structural-only. 404 if absent.
     */
    private Object authoredPipelineRaw(ApiContext api, String id) {
        Path root = pipelinesRootOrNull(api);
        PipelineGraph g = root == null ? null : new PipelineStore(root).get(id).orElse(null);
        if (g == null) throw new ApiException(404, "no authored flow '" + id + "'");
        return PipelineCodec.toMap(g);
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
        findings.addAll(ConfigSafetyValidator.check("pipeline", lowered, SafetyPolicy.defaultPolicy()));
        findings.addAll(ConfigRoutes.schemaFileFindings("pipeline", lowered, Severity.WARNING));
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

    /** Identity of a {@link Finding} for before/after comparison — its anchor plus its message. */
    private static String findingKey(Finding f) {
        return f.fieldPath() + "|" + f.message();
    }

    /**
     * {@code POST /pipelines/{name}/label} — change a pipeline's <b>display name</b> while its identity
     * stays exactly where it is (v5.4.0). The cheap, safe half of "rename".
     *
     * <p>{@code ConfigSpecs.pipeline()} splits {@code name} (display) from {@code id} (stable identity), but
     * every config written so far omits {@code id}, so identity is <em>derived</em> from the name — and that
     * derived string is embedded in the config filename, the {@code <id>_commits.log}, the run-timestamped
     * audit CSVs {@code FileStatusStore.readRuns} globs by id prefix, the acquisition ledger's
     * {@code source_id} and the Catalog Stream. Editing {@code name} on such a config would silently move
     * all of it — which is why the parser warns never to re-derive identity from a changed name.
     *
     * <p>So this route <b>stamps {@code id} with today's derived value first</b>, pinning the identity, and
     * only then sets the new {@code name}. Nothing observable changes except the label: the file keeps its
     * {@code <id>_pipeline.toon} name, the dirs, audit trail, ledger keys and Stream are untouched, and no
     * dependent config needs rewriting. Stamping is idempotent — a config that already declares {@code id}
     * is relabelled without touching it.
     *
     * <p>Moving the identity itself (renaming the file, the audit trail and the ledger keys, and rewriting
     * every dependent reference) is the separate migration tracked in
     * {@code docs/superpower/pipeline-rename-and-template-plan.md} §3, deliberately not done here.
     *
     * <p>Fail-closed gate order: write-root 503 → unknown pipeline 404 → path jail 403 → missing
     * {@code name} 400 → spec + safety findings 422 → write atomically in place.
     */
    private Object relabel(ApiContext api, HttpExchange e, String source, Map<String, Object> body)
            throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "pipeline write");
        Path srcPath = api.service().pathFor(source)
                .orElseThrow(() -> new ApiException(404, "no pipeline named '" + source + "'"));
        WriteGates.jail(writeRoot, srcPath, "config path");   // refuse a config outside the write root
        PipelineConfig live = api.service().configFor(source)
                .orElseThrow(() -> new ApiException(404, "no pipeline named '" + source + "'"));

        String raw = ApiContext.str(body, "name");
        if (raw == null || raw.isBlank())
            throw new ApiException(400, "body must include 'name' (the new display name)");
        String label = raw.trim();

        Map<String, Object> src = ConfigLoader.filesystem().decode(srcPath.toString());
        String id = live.identity().pipelineName();
        boolean stampedId = !(src.get("id") instanceof String s && !s.isBlank());

        // Rebuild with name + id first so the stamped identity reads as a header rather than a trailing key.
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", label);
        out.put("id", id);
        src.forEach((k, v) -> {
            if (!"name".equals(k) && !"id".equals(k)) out.put(k, v);
        });

        // The full gate still runs, but a relabel may only be blocked by findings it INTRODUCES. This route
        // rewrites no paths, and a config already on disk was never subjected to the write-time safety policy
        // — so re-punishing it here would make any pipeline whose data lives outside the default allowed
        // roots impossible to rename, which is most of them in a real deployment.
        List<Finding> findings = new ArrayList<>(ConfigLoader.filesystem().validate(ConfigSpecs.pipeline(), out));
        findings.addAll(ConfigSafetyValidator.check("pipeline", out, SafetyPolicy.defaultPolicy()));
        Set<String> preExisting = new HashSet<>();
        ConfigLoader.filesystem().validate(ConfigSpecs.pipeline(), src).forEach(f -> preExisting.add(findingKey(f)));
        ConfigSafetyValidator.check("pipeline", src, SafetyPolicy.defaultPolicy())
                .forEach(f -> preExisting.add(findingKey(f)));
        List<Finding> introduced = findings.stream()
                .filter(f -> f.severity() == Severity.ERROR)
                .filter(f -> !preExisting.contains(findingKey(f)))
                .toList();
        if (!introduced.isEmpty())
            return ApiContext.respondJson(e, 422, Map.of("written", false,
                    "error", "the new name introduces ERROR-level findings; not written",
                    "findings", introduced));

        byte[] bytes = ConfigCodec.toToon(out).getBytes(StandardCharsets.UTF_8);
        AtomicFiles.write(srcPath, bytes, ".cfg-");
        log.info("[PIPELINE-LABEL] pipeline '{}' relabelled to '{}'{}",
                id, label, stampedId ? " (identity stamped as id: " + id + ")" : "");

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("written", true);
        r.put("path", writeRoot.relativize(srcPath).toString().replace('\\', '/'));
        r.put("id", id);
        r.put("name", label);
        r.put("stampedId", stampedId);
        r.put("findings", findings);
        return r;
    }

    /**
     * {@code GET /pipelines/{name}/settings} — the pipeline-level {@code produces}/{@code reference}
     * block, read straight off the config file rather than through {@link PipelineEditable}, which
     * never models it (D8, pipeline-graph backlog). {@code produces} defaults to {@code "stream"}
     * (the parser's own default) so a config that omits the key round-trips as an explicit choice.
     */
    private Object pipelineSettings(ApiContext api, String name) throws IOException {
        Path srcPath = api.service().pathFor(name)
                .orElseThrow(() -> new ApiException(404, "no pipeline named '" + name + "'"));
        Map<String, Object> raw = ConfigLoader.filesystem().decode(srcPath.toString());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("produces", raw.getOrDefault("produces", "stream"));
        out.put("reference", raw.get("reference"));
        return out;
    }

    /**
     * {@code POST /pipelines/{name}/settings} — write the {@code produces}/{@code reference} block.
     * Mirrors {@link #relabel}'s gate order and "only ERROR findings the write itself introduces
     * block it" policy, since this route also edits an on-disk config that was never subjected to the
     * write-time safety gate. {@code reference}, if given, REPLACES the block wholesale — there is no
     * partial-field patch, so a caller wanting to keep {@code refresh_seconds} must resend it.
     */
    private Object savePipelineSettings(ApiContext api, HttpExchange e, String name, Map<String, Object> body)
            throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "pipeline write");
        Path srcPath = api.service().pathFor(name)
                .orElseThrow(() -> new ApiException(404, "no pipeline named '" + name + "'"));
        WriteGates.jail(writeRoot, srcPath, "config path");

        Map<String, Object> src = ConfigLoader.filesystem().decode(srcPath.toString());
        Map<String, Object> out = new LinkedHashMap<>(src);
        if (body.containsKey("produces")) out.put("produces", body.get("produces"));
        if (body.containsKey("reference")) out.put("reference", body.get("reference"));

        List<Finding> findings = new ArrayList<>(ConfigLoader.filesystem().validate(ConfigSpecs.pipeline(), out));
        findings.addAll(ConfigSafetyValidator.check("pipeline", out, SafetyPolicy.defaultPolicy()));
        Set<String> preExisting = new HashSet<>();
        ConfigLoader.filesystem().validate(ConfigSpecs.pipeline(), src).forEach(f -> preExisting.add(findingKey(f)));
        ConfigSafetyValidator.check("pipeline", src, SafetyPolicy.defaultPolicy())
                .forEach(f -> preExisting.add(findingKey(f)));
        List<Finding> introduced = findings.stream()
                .filter(f -> f.severity() == Severity.ERROR)
                .filter(f -> !preExisting.contains(findingKey(f)))
                .toList();
        if (!introduced.isEmpty())
            return ApiContext.respondJson(e, 422, Map.of("written", false,
                    "error", "the new settings introduce ERROR-level findings; not written",
                    "findings", introduced));

        byte[] bytes = ConfigCodec.toToon(out).getBytes(StandardCharsets.UTF_8);
        AtomicFiles.write(srcPath, bytes, ".cfg-");
        log.info("[PIPELINE-SETTINGS] pipeline '{}' produces/reference block updated", name);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("written", true);
        r.put("path", writeRoot.relativize(srcPath).toString().replace('\\', '/'));
        r.put("produces", out.getOrDefault("produces", "stream"));
        r.put("reference", out.get("reference"));
        r.put("findings", findings);
        return r;
    }

    /**
     * {@code POST /pipelines/{name}/rename} — <b>full identity migration</b> (T3, plan §3): moves the id
     * itself, not just the display name. {@link #relabel} deliberately stops short of this because most
     * renames don't need it; this route is for the rest — every artifact keyed by the old id moves too, so
     * a re-scan under the new id still recognises files it already ingested (the acquisition ledger, S5/S6)
     * and the run history under the new id includes everything recorded under the old one (the commit log
     * and audit CSVs, S2/S3; the DuckDB status mirror, S4).
     *
     * <p>{@code dirs.*} are deliberately left pointing where they already do (plan §1.1) — relocating a
     * Stage-1 output tree is a bulk data move with real blast radius, not this route's job; a caller that
     * asks for it ({@code relocateDirs: true}) gets a 422 rather than a silently-ignored request.
     *
     * <p>Steps 2–7 below are not one transaction and cannot be (DuckDB, the filesystem and the config write
     * are three different failure domains) — see the {@code catch} block for the failure posture this
     * implies. Body: {@code { newId, newName?, relocateDirs?: false, rewriteDependents?: true }}.
     *
     * <p>Fail-closed gate order: write-root 503 → source unknown 404 → path jail 403 → {@code newId} shape
     * 400/422 → source active 409 → source running 409 → {@code newId} taken 409 → {@code relocateDirs}
     * unsupported 422 → migrate.
     */
    private Object rename(ApiContext api, HttpExchange e, String source, Map<String, Object> body)
            throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "pipeline write");
        Path srcPath = api.service().pathFor(source)
                .orElseThrow(() -> new ApiException(404, "no pipeline named '" + source + "'"));
        WriteGates.jail(writeRoot, srcPath, "config path");
        PipelineConfig live = api.service().configFor(source)
                .orElseThrow(() -> new ApiException(404, "no pipeline named '" + source + "'"));

        String rawId = ApiContext.str(body, "newId");
        if (rawId == null || rawId.isBlank())
            throw new ApiException(400, "body must include 'newId'");
        String newId = rawId.trim().toLowerCase();
        if (!newId.matches("[a-z0-9][a-z0-9_]*"))
            throw new ApiException(422, "newId '" + newId
                    + "' must match [a-z0-9][a-z0-9_]* (lowercase letters, digits and underscores)");

        String oldId = live.identity().pipelineName();
        WriteGates.conflictIf(live.active(),
                "pipeline '" + oldId + "' is active; deactivate (active: false) before renaming");
        WriteGates.conflictIf(api.service().isRunning(oldId),
                "pipeline '" + oldId + "' is currently running; wait for it to finish before renaming");
        WriteGates.conflictIf(api.service().pathFor(newId).isPresent(),
                "pipeline id '" + newId + "' is already registered");
        String newFileName = WriteGates.safeName(newId, "pipeline id") + "_pipeline.toon";
        Path newPath = WriteGates.jail(writeRoot, writeRoot.resolve(newFileName), "resolved path");
        WriteGates.conflictIf(Files.exists(newPath), "file exists: " + newFileName);

        if (Boolean.parseBoolean(String.valueOf(body.getOrDefault("relocateDirs", "false"))))
            throw new ApiException(422, "relocateDirs is not yet supported — rename leaves dirs.* pointing "
                    + "where they already do (plan §1.1); relocate the data tree manually if that's needed");
        boolean rewriteDependents = !"false".equalsIgnoreCase(
                String.valueOf(body.getOrDefault("rewriteDependents", "true")));

        List<String> journal = new ArrayList<>();
        Path journalFile = writeRoot.resolve("rename.journal");
        String newNameRaw = ApiContext.str(body, "newName");

        // Step 0: bracket the migration in the journal BEFORE any state moves. `begin` records the source
        // file name and the request parameters; `completed` (after step 9) closes the bracket. A begin with
        // no completed is what POST /pipelines/rename/resume looks for — and the params recorded here are
        // what let it finish the job. newName stays last on the line: it may contain spaces.
        journalStep(journalFile, oldId, newId, "begin src=" + srcPath.getFileName()
                + " rewriteDependents=" + rewriteDependents
                + (newNameRaw == null || newNameRaw.isBlank() ? "" : " newName=" + newNameRaw.trim()), journal);

        // Step 1 (S9): evict per-pipeline bookkeeping + the run registry. Cheaply reversible on failure —
        // re-registering the same path restores exactly what this undid.
        api.service().unregisterPipeline(srcPath);
        journalStep(journalFile, oldId, newId, "unregistered source", journal);

        try {
            // Step 2 (S5, S6): ledger fingerprints + DB-export watermark.
            int ledgerRows = com.gamma.acquire.AcquisitionLedgers.shared().renameSource(oldId, newId);
            journalStep(journalFile, oldId, newId, "ledger rows moved: " + ledgerRows, journal);

            // Step 3 (S2, S3): the persistent commit log + run-timestamped audit CSVs.
            int auditFiles = renameAuditFiles(live, oldId, newId);
            journalStep(journalFile, oldId, newId, "audit files renamed: " + auditFiles, journal);

            // Step 4 (S4): the DuckDB status mirror, when DB-backed; no-op for the file-only default.
            if (api.service().statusStore() instanceof DbStatusStore db) {
                db.renamePipeline(oldId, newId);
                journalStep(journalFile, oldId, newId, "status DB rows updated", journal);
            }

            // Step 6: write the new config — the SAME spec + safety gate POST /config/write runs — then
            // remove the old file. Landing this after the state moves (not before) is deliberate: a crash
            // here leaves the old config's file in place, so the catch block's recovery has something to
            // re-register.
            ConfigWrite cw = writeRenamedConfig(api, e, srcPath, newPath, newFileName,
                    oldId, newId, newNameRaw, journalFile, journal);
            if (cw.refused() != null) return cw.refused();
            String label = cw.label();
            List<Finding> findings = cw.findings();

            // Step 7: dependent configs (plan §1 table) — enrich/job triggers, expectation/decision-rule
            // targets, dataset store references. Best-effort per file (see rewriteDependents); never
            // throws, so it never leaves the migration stuck between a written config and registration.
            int dependents = rewriteDependents ? rewriteDependents(writeRoot, oldId, newId) : 0;
            if (rewriteDependents)
                journalStep(journalFile, oldId, newId, "dependents rewritten: " + dependents, journal);

            // Step 8 (S7): re-register under the new identity — fires catalog invalidation as a side effect.
            api.service().registerPipeline(newPath);
            journalStep(journalFile, oldId, newId, "registered " + newId, journal);

            // Step 9 (S10 stays untouched — history keeps recording what was true then).
            api.service().eventLog().emit(Event.builder(EventType.PIPELINE_RENAMED)
                    .source(PipelineRoutes.class.getName()).pipeline(newId)
                    .message("Pipeline '" + oldId + "' renamed to '" + newId + "'")
                    .attr("oldId", oldId).attr("newId", newId));
            journalStep(journalFile, oldId, newId, "completed", journal);
            log.info("[PIPELINE-RENAME] '{}' -> '{}' ({} ledger row(s), {} audit file(s), {} dependent(s))",
                    oldId, newId, ledgerRows, auditFiles, dependents);

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("written", true);
            r.put("oldId", oldId);
            r.put("id", newId);
            r.put("name", label);
            r.put("path", writeRoot.relativize(newPath).toString().replace('\\', '/'));
            r.put("ledgerRowsMoved", ledgerRows);
            r.put("auditFilesRenamed", auditFiles);
            r.put("dependentsRewritten", dependents);
            r.put("findings", findings);
            r.put("journal", journal);
            return r;
        } catch (RuntimeException | IOException ex) {
            // Steps 2-7 are not one transaction (plan §3.3 "Failure posture"). The old config file is still
            // on disk in every failure path except the one already handled above (which restores it
            // itself), so re-registering it keeps the pipeline reachable rather than silently vanishing
            // from the registry — though state already moved under steps completed before the failure
            // (named in `journal`) stays moved; this is the plan's documented residual risk, not a bug.
            if (Files.exists(srcPath)) api.service().registerPipeline(srcPath);
            log.warn("[PIPELINE-RENAME] '{}' -> '{}' failed after {}", oldId, newId, journal, ex);
            throw new ApiException(500, "rename of '" + oldId + "' to '" + newId + "' failed after "
                    + journal.size() + " step(s) — see server log / rename.journal for detail: " + ex.getMessage());
        }
    }

    /** One incomplete migration recovered from {@code rename.journal}: a {@code begin} line (which records
     *  the source file name and the request parameters) with no matching {@code completed}. */
    private record PendingRename(String oldId, String newId, String srcFileName,
                                 boolean rewriteDependents, String newName) {}

    private static final java.util.regex.Pattern JOURNAL_LINE =
            java.util.regex.Pattern.compile("^\\S+ (\\S+) -> (\\S+) : (.*)$");
    private static final java.util.regex.Pattern BEGIN_STEP =
            java.util.regex.Pattern.compile("^begin src=(\\S+) rewriteDependents=(true|false)(?: newName=(.*))?$");

    /**
     * Read {@code rename.journal} back into the still-open migrations, in journal order. A later
     * {@code begin} for the same id pair supersedes an earlier open one (a retried rename); a
     * {@code completed} closes the pair. Lines from before the begin/completed bracket existed never open a
     * bracket, so pre-bracket-era migrations are invisible here — they stay manual-reconciliation cases.
     */
    private List<PendingRename> readPendingRenames(Path journalFile) throws IOException {
        if (!Files.exists(journalFile)) return List.of();
        Map<String, PendingRename> open = new LinkedHashMap<>();
        for (String line : Files.readAllLines(journalFile, StandardCharsets.UTF_8)) {
            java.util.regex.Matcher m = JOURNAL_LINE.matcher(line);
            if (!m.matches()) continue;
            String key = m.group(1) + " -> " + m.group(2);
            String step = m.group(3);
            java.util.regex.Matcher b = BEGIN_STEP.matcher(step);
            if (b.matches())
                open.put(key, new PendingRename(m.group(1), m.group(2), b.group(1),
                        Boolean.parseBoolean(b.group(2)), b.group(3)));
            else if ("completed".equals(step))
                open.remove(key);
        }
        return List.copyOf(open.values());
    }

    /**
     * {@code POST /pipelines/rename/resume} — finish an interrupted identity migration. {@code rename}'s
     * steps 2–7 are not one transaction (see its javadoc), and two of its failure windows a plain retry
     * cannot heal: a crash between writing the new config and deleting the old leaves both files on disk
     * (retry → 409 file-exists), and a failure after the old config is deleted leaves the pipeline
     * registered under neither id (retry → 404). This route reads {@code rename.journal} back — a
     * {@code begin} with no {@code completed} is an incomplete migration — and re-runs the remaining steps.
     * Every step is idempotent (the ledger/status-mirror renames match zero rows once moved; the audit-file
     * and dependent rewrites match nothing once rewritten), so resuming after ANY failure point is safe and
     * a resume that itself fails can be resumed again. The journal supplies discovery + the recorded
     * parameters; the on-disk file state decides what the config-write step still owes.
     *
     * <p>Deliberately an explicit operator action, never a startup hook — an automatic state migration at
     * boot would act without operator intent. The {@code PIPELINE_RENAMED} event is at-least-once: a crash
     * between the emit and the {@code completed} line duplicates it on the next resume — history noise,
     * never state corruption.
     *
     * <p>Body (optional): {@code { oldId, newId }} to pick one migration when several are incomplete.
     */
    private Object resumeRename(ApiContext api, HttpExchange e, Map<String, Object> body) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "pipeline write");
        Path journalFile = writeRoot.resolve("rename.journal");

        String selOld = ApiContext.str(body, "oldId");
        String selNew = ApiContext.str(body, "newId");
        List<PendingRename> pending = readPendingRenames(journalFile).stream()
                .filter(p -> selOld == null || selOld.isBlank() || p.oldId().equals(selOld.trim().toLowerCase()))
                .filter(p -> selNew == null || selNew.isBlank() || p.newId().equals(selNew.trim().toLowerCase()))
                .toList();
        if (pending.isEmpty())
            throw new ApiException(404, "no incomplete rename found in rename.journal"
                    + (selOld != null || selNew != null ? " matching the given oldId/newId" : ""));
        if (pending.size() > 1)
            throw new ApiException(409, "several incomplete renames — specify {oldId, newId}: "
                    + pending.stream().map(p -> p.oldId() + " -> " + p.newId()).toList());
        PendingRename p = pending.get(0);
        String oldId = p.oldId(), newId = p.newId();

        Path srcPath = WriteGates.jail(writeRoot, writeRoot.resolve(p.srcFileName()), "source path");
        String newFileName = WriteGates.safeName(newId, "pipeline id") + "_pipeline.toon";
        Path newPath = WriteGates.jail(writeRoot, writeRoot.resolve(newFileName), "resolved path");
        boolean srcExists = Files.exists(srcPath);
        boolean newExists = Files.exists(newPath);
        if (!srcExists && !newExists)
            throw new ApiException(409, "cannot resume '" + oldId + "' -> '" + newId + "': neither "
                    + p.srcFileName() + " nor " + newFileName + " exists — manual reconciliation needed");

        // Fail-closed identity checks before touching anything: each surviving file must still be the
        // migration's own — the operator may have replaced either since the failed attempt.
        if (newExists) {
            Map<String, Object> chk = ConfigLoader.filesystem().decode(newPath.toString());
            if (!newId.equals(chk.get("id")))
                throw new ApiException(409, newFileName + " exists but is not this rename's product "
                        + "(id: " + chk.get("id") + ") — manual reconciliation needed");
        }
        PipelineConfig srcCfg = null;
        if (srcExists) {
            srcCfg = PipelineConfig.load(srcPath.toString());
            if (!oldId.equals(srcCfg.identity().pipelineName()))
                throw new ApiException(409, p.srcFileName() + " no longer carries id '" + oldId
                        + "' (now '" + srcCfg.identity().pipelineName() + "') — manual reconciliation needed");
            // The failed attempt's recovery re-registers the source, and it may have been reactivated or
            // started since — the same lifecycle gates a fresh rename runs.
            WriteGates.conflictIf(srcCfg.active(), "pipeline '" + oldId
                    + "' is active; deactivate (active: false) before resuming the rename");
            WriteGates.conflictIf(api.service().isRunning(oldId), "pipeline '" + oldId
                    + "' is currently running; wait for it to finish before resuming the rename");
        }
        Optional<Path> registeredNew = api.service().pathFor(newId);
        if (registeredNew.isPresent()
                && !registeredNew.get().toAbsolutePath().normalize().equals(newPath.toAbsolutePath().normalize()))
            throw new ApiException(409, "pipeline id '" + newId + "' is registered to a different config ("
                    + registeredNew.get().getFileName() + ") — manual reconciliation needed");

        List<String> journal = new ArrayList<>();
        journalStep(journalFile, oldId, newId, "resume", journal);
        if (srcExists) api.service().unregisterPipeline(srcPath);
        try {
            int ledgerRows = com.gamma.acquire.AcquisitionLedgers.shared().renameSource(oldId, newId);
            journalStep(journalFile, oldId, newId, "ledger rows moved: " + ledgerRows, journal);

            // dirs.* are identical on both sides (rename never relocates them), so whichever config file
            // survives supplies the audit-file locations; renameAuditFiles derives file NAMES from oldId.
            PipelineConfig cfgForDirs = srcCfg != null ? srcCfg : PipelineConfig.load(newPath.toString());
            int auditFiles = renameAuditFiles(cfgForDirs, oldId, newId);
            journalStep(journalFile, oldId, newId, "audit files renamed: " + auditFiles, journal);

            if (api.service().statusStore() instanceof DbStatusStore db) {
                db.renamePipeline(oldId, newId);
                journalStep(journalFile, oldId, newId, "status DB rows updated", journal);
            }

            String label;
            List<Finding> findings = List.of();
            if (srcExists && !newExists) {
                ConfigWrite cw = writeRenamedConfig(api, e, srcPath, newPath, newFileName,
                        oldId, newId, p.newName(), journalFile, journal);
                if (cw.refused() != null) return cw.refused();
                label = cw.label();
                findings = cw.findings();
            } else {
                if (srcExists) {
                    // The crash window between AtomicFiles.write(newPath) and deleting the source: the new
                    // config is already written (identity verified above) — only the delete is owed.
                    Files.deleteIfExists(srcPath);
                    journalStep(journalFile, oldId, newId,
                            "removed source config (new config already written)", journal);
                }
                Map<String, Object> written = ConfigLoader.filesystem().decode(newPath.toString());
                label = String.valueOf(written.getOrDefault("name", newId));
            }

            int dependents = p.rewriteDependents() ? rewriteDependents(writeRoot, oldId, newId) : 0;
            if (p.rewriteDependents())
                journalStep(journalFile, oldId, newId, "dependents rewritten: " + dependents, journal);

            api.service().registerPipeline(newPath);
            journalStep(journalFile, oldId, newId, "registered " + newId, journal);

            api.service().eventLog().emit(Event.builder(EventType.PIPELINE_RENAMED)
                    .source(PipelineRoutes.class.getName()).pipeline(newId)
                    .message("Pipeline '" + oldId + "' renamed to '" + newId + "' (resumed)")
                    .attr("oldId", oldId).attr("newId", newId));
            journalStep(journalFile, oldId, newId, "completed", journal);
            log.info("[PIPELINE-RENAME] resumed '{}' -> '{}' ({} ledger row(s), {} audit file(s), {} dependent(s))",
                    oldId, newId, ledgerRows, auditFiles, dependents);

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("written", true);
            r.put("resumed", true);
            r.put("oldId", oldId);
            r.put("id", newId);
            r.put("name", label);
            r.put("path", writeRoot.relativize(newPath).toString().replace('\\', '/'));
            r.put("ledgerRowsMoved", ledgerRows);
            r.put("auditFilesRenamed", auditFiles);
            r.put("dependentsRewritten", dependents);
            r.put("findings", findings);
            r.put("journal", journal);
            return r;
        } catch (RuntimeException | IOException ex) {
            // Same posture as rename's catch: keep the pipeline reachable if its old config survives; the
            // bracket stays open, so the NEXT resume picks up from here.
            if (Files.exists(srcPath)) api.service().registerPipeline(srcPath);
            log.warn("[PIPELINE-RENAME] resume '{}' -> '{}' failed after {}", oldId, newId, journal, ex);
            throw new ApiException(500, "resume of '" + oldId + "' to '" + newId + "' failed after "
                    + journal.size() + " step(s) — see server log / rename.journal for detail: " + ex.getMessage());
        }
    }

    /** Outcome of {@link #writeRenamedConfig}: on success {@code label} + {@code findings}; on refusal only
     *  {@code refused} — the 422 (findings included) has already been sent on the exchange. */
    private record ConfigWrite(String label, List<Finding> findings, Object refused) {}

    /**
     * Step 6 of the identity migration, shared by {@code rename} and {@code resume}: build the renamed
     * config from the source file, gate on ERROR findings the rewrite <em>introduces</em> (as in
     * {@code relabel}: never pre-existing ones — {@code dirs.*} are untouched, so a config whose data lives
     * outside the default allowed roots was never subject to the write-time policy, and re-punishing it
     * here would make any such deployment unrenameable), write atomically to {@code newPath}, then delete
     * the source file.
     */
    private ConfigWrite writeRenamedConfig(ApiContext api, HttpExchange e, Path srcPath, Path newPath,
            String newFileName, String oldId, String newId, String newNameRaw,
            Path journalFile, List<String> journal) throws IOException {
        Map<String, Object> src = ConfigLoader.filesystem().decode(srcPath.toString());
        String label = (newNameRaw == null || newNameRaw.isBlank())
                ? String.valueOf(src.getOrDefault("name", oldId)) : newNameRaw.trim();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", label);
        out.put("id", newId);
        src.forEach((k, v) -> {
            if (!"name".equals(k) && !"id".equals(k)) out.put(k, v);
        });

        List<Finding> findings = new ArrayList<>(ConfigLoader.filesystem().validate(ConfigSpecs.pipeline(), out));
        findings.addAll(ConfigSafetyValidator.check("pipeline", out, SafetyPolicy.defaultPolicy()));
        Set<String> preExisting = new HashSet<>();
        ConfigLoader.filesystem().validate(ConfigSpecs.pipeline(), src).forEach(f -> preExisting.add(findingKey(f)));
        ConfigSafetyValidator.check("pipeline", src, SafetyPolicy.defaultPolicy())
                .forEach(f -> preExisting.add(findingKey(f)));
        List<Finding> introduced = findings.stream()
                .filter(f -> f.severity() == Severity.ERROR)
                .filter(f -> !preExisting.contains(findingKey(f)))
                .toList();
        if (!introduced.isEmpty()) {
            journalStep(journalFile, oldId, newId,
                    "refused: renamed config introduces ERROR findings — source restored", journal);
            api.service().registerPipeline(srcPath);   // the config write never happened — restore visibility
            return new ConfigWrite(null, null, ApiContext.respondJson(e, 422, Map.of("written", false,
                    "error", "the renamed config introduces ERROR-level findings; not written",
                    "findings", introduced, "journal", journal)));
        }
        byte[] bytes = ConfigCodec.toToon(out).getBytes(StandardCharsets.UTF_8);
        AtomicFiles.write(newPath, bytes, ".cfg-");
        Files.deleteIfExists(srcPath);
        journalStep(journalFile, oldId, newId, "wrote " + newFileName + "; removed source config", journal);
        return new ConfigWrite(label, findings, null);
    }

    /** Append one line to {@code <writeRoot>/rename.journal} (plan §3.3) — best-effort; a journal write
     *  failure must never abort a migration step that already succeeded. */
    private void journalStep(Path journalFile, String oldId, String newId, String step, List<String> journal) {
        journal.add(step);
        try {
            Files.writeString(journalFile, Instant.now() + " " + oldId + " -> " + newId + " : " + step + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            log.warn("[PIPELINE-RENAME] could not append to rename.journal: {}", ex.getMessage());
        }
    }

    /**
     * Rename the persistent commit log and every run-timestamped audit CSV from an {@code oldId} prefix to
     * a {@code newId} prefix, in place (S2, S3) — {@code dirs.*} themselves are untouched (plan §1.1), only
     * each filename's identity prefix moves. Mirrors the glob {@link com.gamma.service.FileStatusStore}
     * itself uses to read them, so a rename here is exactly what makes them findable under the new id
     * afterwards. Returns the count of files renamed.
     */
    private int renameAuditFiles(PipelineConfig cfg, String oldId, String newId) throws IOException {
        int count = 0;
        Path statusParent = null;
        String commitLogPath = cfg.dirs().commitLogPath();
        if (commitLogPath != null && !commitLogPath.isBlank()) {
            // The commit-log FILE name is derived from oldId, not taken from cfg: commitLogPath is always
            // <parent>/<pipelineName>_commits.log (PipelineConfigParser), and resume may only have the NEW
            // config to read dirs from — whose own commitLogPath already carries the new id.
            statusParent = Path.of(commitLogPath).getParent();
            Path oldLog = statusParent.resolve(oldId + "_commits.log");
            if (Files.exists(oldLog)) {
                Files.move(oldLog, statusParent.resolve(newId + "_commits.log"));
                count++;
            }
        }
        if (statusParent == null) {
            String statusFile = cfg.dirs().statusFilePath();
            if (statusFile == null || statusFile.isBlank()) return count;
            statusParent = Path.of(statusFile).toAbsolutePath().getParent();
        }
        if (statusParent == null || !Files.isDirectory(statusParent)) return count;
        for (String infix : List.of("_status_", "_batches_", "_lineage_")) {
            List<Path> matches = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(statusParent, oldId + infix + "*.csv")) {
                ds.forEach(matches::add);
            }
            for (Path p : matches) {
                String name = p.getFileName().toString();
                Files.move(p, statusParent.resolve(newId + name.substring(oldId.length())));
                count++;
            }
        }
        return count;
    }

    /**
     * Rewrite every dependent config's reference to {@code oldId} into {@code newId} (plan §1's dependent
     * table): {@code *_enrich.toon} triggers, {@code *_job.toon} triggers, {@code expectation}/
     * {@code decision-rule} pipeline targets, and {@code dataset} store references. Best-effort per file —
     * one malformed sibling must never abort a rename whose state-moving steps already committed. Returns
     * the total count of files rewritten.
     */
    private int rewriteDependents(Path writeRoot, String oldId, String newId) {
        int count = rewriteEnrichTriggers(writeRoot, oldId, newId);
        count += rewriteJobTriggers(writeRoot, oldId, newId);
        count += rewriteComponentTargets(writeRoot, "expectation", oldId, newId);
        count += rewriteComponentTargets(writeRoot, "decision-rule", oldId, newId);
        count += rewriteDatasetRefs(writeRoot, oldId, newId);
        return count;
    }

    /** {@code triggers.on_pipeline} in every {@code *_enrich.toon} directly under the write root. */
    @SuppressWarnings("unchecked")
    private int rewriteEnrichTriggers(Path writeRoot, String oldId, String newId) {
        if (!Files.isDirectory(writeRoot)) return 0;
        int count = 0;
        try (Stream<Path> files = Files.list(writeRoot)) {
            for (Path p : files.filter(f -> f.getFileName().toString().endsWith("_enrich.toon")).toList()) {
                try {
                    Map<String, Object> raw = ConfigLoader.filesystem().decode(p.toString());
                    if (!(raw.get("triggers") instanceof Map<?, ?> t)
                            || !oldId.equalsIgnoreCase(String.valueOf(t.get("on_pipeline")))) continue;
                    Map<String, Object> triggers = new LinkedHashMap<>((Map<String, Object>) t);
                    triggers.put("on_pipeline", newId);
                    Map<String, Object> out = new LinkedHashMap<>(raw);
                    out.put("triggers", triggers);
                    AtomicFiles.write(p, ConfigCodec.toToon(out).getBytes(StandardCharsets.UTF_8), ".enr-");
                    count++;
                } catch (Exception ex) {
                    log.warn("[PIPELINE-RENAME] skipping unreadable enrichment {}: {}", p, ex.getMessage());
                }
            }
        } catch (IOException ex) {
            log.warn("[PIPELINE-RENAME] could not list enrichments under {}: {}", writeRoot, ex.getMessage());
        }
        return count;
    }

    /** Top-level {@code on_pipeline} in every {@code jobs/*_job.toon} under the write root. */
    private int rewriteJobTriggers(Path writeRoot, String oldId, String newId) {
        Path jobsDir = writeRoot.resolve("jobs");
        if (!Files.isDirectory(jobsDir)) return 0;
        int count = 0;
        try (Stream<Path> files = Files.list(jobsDir)) {
            for (Path p : files.filter(f -> f.getFileName().toString().endsWith("_job.toon")).toList()) {
                try {
                    Map<String, Object> raw = ConfigLoader.filesystem().decode(p.toString());
                    if (!oldId.equalsIgnoreCase(String.valueOf(raw.get("on_pipeline")))) continue;
                    Map<String, Object> out = new LinkedHashMap<>(raw);
                    out.put("on_pipeline", newId);
                    AtomicFiles.write(p, ConfigCodec.toToon(out).getBytes(StandardCharsets.UTF_8), ".job-");
                    count++;
                } catch (Exception ex) {
                    log.warn("[PIPELINE-RENAME] skipping unreadable job {}: {}", p, ex.getMessage());
                }
            }
        } catch (IOException ex) {
            log.warn("[PIPELINE-RENAME] could not list jobs under {}: {}", jobsDir, ex.getMessage());
        }
        return count;
    }

    /**
     * {@code target} on every {@code type} component (expectation / decision-rule) whose {@code targetType}
     * is {@code pipeline} (the default when absent) and whose {@code target} names {@code oldId} —
     * mirrors {@code DataSourceBundleResolver.ruleTargets}'s matching rule.
     */
    private int rewriteComponentTargets(Path writeRoot, String type, String oldId, String newId) {
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        int count = 0;
        for (ComponentRegistry.Component c : store.list(type)) {
            Map<String, Object> content = c.content();
            String targetType = String.valueOf(content.getOrDefault("targetType", "pipeline"));
            if (!"pipeline".equalsIgnoreCase(targetType)) continue;
            String target = content.get("target") == null ? "" : String.valueOf(content.get("target")).trim();
            if (!oldId.equalsIgnoreCase(target)) continue;
            Map<String, Object> updated = new LinkedHashMap<>(content);
            updated.put("target", newId);
            try {
                store.write(type, c.name(), updated);
                count++;
            } catch (IOException ex) {
                log.warn("[PIPELINE-RENAME] could not rewrite {} '{}': {}", type, c.name(), ex.getMessage());
            }
        }
        return count;
    }

    /**
     * {@code sourceName} and/or the first path segment of {@code physicalRef} on every {@code dataset}
     * component that reads {@code oldId}'s store — mirrors
     * {@code DataSourceBundleResolver.datasetReadsStore}'s {@code physicalRef} rule, widened to
     * {@code sourceName} (a {@code kind: virtual} dataset's direct store reference, which that resolver
     * does not need to check but a rename does — an unrewritten one would silently start reading nothing).
     */
    private int rewriteDatasetRefs(Path writeRoot, String oldId, String newId) {
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        int count = 0;
        for (ComponentRegistry.Component c : store.list("dataset")) {
            Map<String, Object> content = c.content();
            Map<String, Object> updated = new LinkedHashMap<>(content);
            boolean changed = false;

            Object sn = content.get("sourceName");
            if (sn != null && oldId.equalsIgnoreCase(String.valueOf(sn).trim())) {
                updated.put("sourceName", newId);
                changed = true;
            }

            Object ref = content.get("physicalRef");
            String refStr = ref == null ? "" : String.valueOf(ref).trim();
            if (!refStr.isEmpty() && !"null".equals(refStr)) {
                int slash = refStr.indexOf('/');
                String head = slash < 0 ? refStr : refStr.substring(0, slash);
                if (head.equalsIgnoreCase(oldId)) {
                    updated.put("physicalRef", newId + (slash < 0 ? "" : refStr.substring(slash)));
                    changed = true;
                }
            }

            if (!changed) continue;
            try {
                store.write("dataset", c.name(), updated);
                count++;
            } catch (IOException ex) {
                log.warn("[PIPELINE-RENAME] could not rewrite dataset '{}': {}", c.name(), ex.getMessage());
            }
        }
        return count;
    }

    /**
     * {@code POST /pipelines/{name}/save-as-template} — copy a pipeline into a non-runnable authoring
     * <b>template</b> (v5.4.0): a starting point for standing up a <em>similar</em> stream that cannot
     * touch the pipeline it was copied from, even if someone tries to run it.
     *
     * <p>Two halves make that true. The written config carries {@code template: true}, which
     * {@link com.gamma.service.CollectorService} refuses to run and {@code PipelineScheduler} skips; and
     * every <em>environment binding</em> is repointed by {@link #neutralizeForTemplate} into a
     * {@code templates/<id>/} sandbox, so even after the flag is cleared the copy reads its own inbox and
     * writes its own output, audit trail and ledger keys. Belt (the flag) and braces (the bindings) —
     * because the flag is what the operator clears when promoting, and at that moment the bindings are all
     * that stands between a fresh pipeline and the original's data.
     *
     * <p>Companion configs ({@code *_enrich.toon}, {@code *_job.toon}) and anything targeting the source
     * (expectations, alert rules, datasets) are deliberately <b>not</b> copied: a template describes one
     * pipeline, and wiring it up is an explicit act, not a side effect of copying.
     *
     * <p>Fail-closed gate order: write-root 503 → unknown source 404 → missing/invalid {@code id} 400/422
     * → id taken 409 → path jail 403 → file exists 409 → spec + safety findings 422 → write atomically.
     */
    private Object saveAsTemplate(ApiContext api, HttpExchange e, String source, Map<String, Object> body)
            throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "pipeline write");
        Path srcPath = api.service().pathFor(source)
                .orElseThrow(() -> new ApiException(404, "no pipeline named '" + source + "'"));

        String rawId = ApiContext.str(body, "id");
        if (rawId == null || rawId.isBlank())
            throw new ApiException(400, "body must include 'id' (the new template's pipeline id)");
        String id = rawId.trim().toLowerCase();
        if (!id.matches("[a-z0-9][a-z0-9_]*"))
            throw new ApiException(422, "id '" + id
                    + "' must match [a-z0-9][a-z0-9_]* (lowercase letters, digits and underscores)");

        // The id must be free as a live pipeline AND on disk — either would collide at registration.
        WriteGates.conflictIf(api.service().pathFor(id).isPresent(),
                "pipeline id '" + id + "' is already registered");
        String fileName = WriteGates.safeName(id, "pipeline id") + "_pipeline.toon";
        Path target = WriteGates.jail(writeRoot, writeRoot.resolve(fileName), "resolved path");
        WriteGates.conflictIf(Files.exists(target), "file exists: " + fileName);

        String displayName = ApiContext.str(body, "name");
        List<String> notes = new ArrayList<>();
        Map<String, Object> tpl = neutralizeForTemplate(
                ConfigLoader.filesystem().decode(srcPath.toString()), id,
                (displayName == null || displayName.isBlank()) ? id : displayName.trim(),
                srcPath, writeRoot, notes);

        // The same gate POST /config/write runs — a template is still a real config and must be safe.
        List<Finding> findings = new ArrayList<>(ConfigLoader.filesystem().validate(ConfigSpecs.pipeline(), tpl));
        findings.addAll(ConfigSafetyValidator.check("pipeline", tpl, SafetyPolicy.defaultPolicy()));
        findings.addAll(ConfigRoutes.schemaFileFindings("pipeline", tpl, Severity.WARNING));
        if (findings.stream().anyMatch(f -> f.severity() == Severity.ERROR))
            return ApiContext.respondJson(e, 422, Map.of("written", false,
                    "error", "config has ERROR-level findings; not written", "findings", findings));

        byte[] bytes = ConfigCodec.toToon(tpl).getBytes(StandardCharsets.UTF_8);
        AtomicFiles.write(target, bytes, ".tpl-");
        log.info("[PIPELINE-TEMPLATE] copied '{}' to template '{}' at {} ({} bytes)",
                source, id, fileName, bytes.length);

        // Register it so it shows up in GET /pipelines for editing/promotion. Safe: every run path
        // (trigger, reprocess, poll cycle) refuses a template, so being in the registry cannot run it.
        api.service().registerPipeline(target);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("written", true);
        r.put("path", writeRoot.relativize(target).toString().replace('\\', '/'));
        r.put("id", id);
        r.put("source", source);
        r.put("template", true);
        r.put("notes", notes);
        r.put("findings", findings);
        return r;
    }

    /**
     * Build the template config from {@code src}: a verbatim copy with its identity replaced and every
     * <b>environment binding</b> repointed into a {@code templates/<id>/} sandbox.
     *
     * <p>Repointed (each one is a way a naive copy would collide with the original): {@code dirs.*} —
     * chiefly {@code poll}, whose reuse would make two pipelines race for the same inbox, and
     * {@code database}, which also determines where the audit CSVs and the persistent
     * {@code <id>_commits.log} land · {@code stream}, which defaults to the pipeline id and would
     * otherwise silently join the source's Catalog Stream · the collector's {@code id}, which is the
     * acquisition ledger's {@code source_id} and therefore the dedup + watermark key · the DuckLake
     * {@code data_path} · and {@code processing.schema_file}, copied so editing the template's schema
     * cannot edit the source's.
     *
     * <p>Carried verbatim: everything else — parsing/CSV settings, threads, dedup policy, output format,
     * connector/discovery/post-action, {@code produces}/{@code reference}, and {@code trigger} (inert
     * while the config is a template, and the operator's intent worth preserving).
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> neutralizeForTemplate(Map<String, Object> src, String id,
                                                             String displayName, Path srcPath,
                                                             Path writeRoot, List<String> notes)
            throws IOException {
        Map<String, Object> t = new LinkedHashMap<>(src);
        String sandbox = "templates/" + id;

        t.put("name", displayName);
        t.put("id", id);            // explicit identity, so a later display-name edit is a relabel
        t.put("template", true);
        t.put("active", false);     // `template: true` + `active: true` is refused by the parser
        t.put("stream", id);        // never inherit the source's Catalog Stream

        // dirs: every path the engine reads or writes moves into the sandbox. The well-known keys get
        // their conventional leaf name; any other key present is mapped under the sandbox by its own name
        // so a key added later still lands inside it rather than being left pointing at the source.
        Map<String, String> leaf = Map.of("poll", "inbox", "status_dir", "status", "log_dir", "logs");
        Map<String, Object> dirs = new LinkedHashMap<>();
        if (src.get("dirs") instanceof Map<?, ?> sd) {
            for (Map.Entry<?, ?> en : sd.entrySet()) {
                String k = String.valueOf(en.getKey());
                // a literal status CSV path, not a directory — keep it a file path inside the sandbox
                dirs.put(k, "status_file".equals(k)
                        ? sandbox + "/status/" + id + "_status.csv"
                        : sandbox + "/" + leaf.getOrDefault(k, k));
            }
        }
        dirs.putIfAbsent("poll", sandbox + "/inbox");         // spec-required
        dirs.putIfAbsent("database", sandbox + "/database");  // spec-required
        t.put("dirs", dirs);

        // The collector's id is the acquisition ledger's source_id; `source:` is the legacy spelling.
        String colKey = src.containsKey("collector") || !src.containsKey("source") ? "collector" : "source";
        if (src.get(colKey) instanceof Map<?, ?> sc) {
            Map<String, Object> col = new LinkedHashMap<>((Map<String, Object>) sc);
            col.put("id", id);
            t.put(colKey, col);
        } else {
            t.put(colKey, new LinkedHashMap<>(Map.of("id", id)));
        }

        if (src.get("output") instanceof Map<?, ?> so) {
            Map<String, Object> out = new LinkedHashMap<>((Map<String, Object>) so);
            if (out.get("ducklake") instanceof Map<?, ?> dl) {
                Map<String, Object> lake = new LinkedHashMap<>((Map<String, Object>) dl);
                if (lake.containsKey("data_path")) lake.put("data_path", sandbox + "/ducklake");
                out.put("ducklake", lake);
            }
            t.put("output", out);
        }

        copySchemaFile(src, t, id, srcPath, writeRoot, notes);
        return t;
    }

    /**
     * Copy the source's {@code processing.schema_file} next to the template and repoint at the copy, so the
     * two configs never share a schema an operator might edit. A relative reference resolves against the
     * source config's own directory first and the working directory second — the same order
     * {@link PipelineConfig#load} uses. When it cannot be resolved or read the original value is left
     * alone (harmless: the parser reads it, never writes it) and a note explains why.
     */
    @SuppressWarnings("unchecked")
    private static void copySchemaFile(Map<String, Object> src, Map<String, Object> t, String id,
                                       Path srcPath, Path writeRoot, List<String> notes) throws IOException {
        if (!(src.get("processing") instanceof Map<?, ?> sp)) return;
        Map<String, Object> processing = new LinkedHashMap<>((Map<String, Object>) sp);
        Object ref = processing.get("schema_file");
        if (ref == null || String.valueOf(ref).isBlank()) return;   // inline schemas / segments: nothing to copy

        Path from = Path.of(String.valueOf(ref));
        if (!from.isAbsolute()) {
            Path here = srcPath.toAbsolutePath().getParent();
            Path beside = here == null ? null : here.resolve(from);
            from = (beside != null && Files.isReadable(beside)) ? beside : from.toAbsolutePath();
        }
        if (!Files.isReadable(from)) {
            notes.add("schema_file '" + ref + "' could not be read, so it still points at the source's schema"
                    + " — repoint it before editing the schema");
            return;
        }
        Path schemaTarget = WriteGates.jail(writeRoot, writeRoot.resolve(id + "_schema.toon"), "schema path");
        if (Files.exists(schemaTarget)) {
            notes.add("schema_file left as '" + ref + "': " + schemaTarget.getFileName() + " already exists");
            return;
        }
        AtomicFiles.write(schemaTarget, Files.readAllBytes(from), ".sch-");
        processing.put("schema_file", writeRoot.relativize(schemaTarget).toString().replace('\\', '/'));
        t.put("processing", processing);
        notes.add("copied the schema to " + schemaTarget.getFileName());
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

    /** {@code DELETE /pipelines/authored/{id}} — remove an authored flow; 404 if absent. */
    private Object deletePipeline(ApiContext api, String id) throws IOException {
        PipelineStore store = pipelineStore(api);
        if (!pipelineExists(store, id)) throw new ApiException(404, "no authored flow '" + id + "'");
        boolean removed;
        try {
            removed = store.delete(id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        return Map.of("id", id, "deleted", true, "fileRemoved", removed);
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
            throw new ApiException(422, "flow validation failed: " + r.errors().stream()
                    .map(i -> i.code() + " — " + i.message()).toList());
    }

    private static boolean pipelineExists(PipelineStore store, String id) {
        try {
            return store.exists(id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, e.getMessage());
        }
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
            Path root = pipelinesRootOrNull(api);
            try {
                g = root == null ? null : new PipelineStore(root).get(id).orElse(null);
            } catch (IllegalArgumentException e) {
                throw new ApiException(400, e.getMessage());
            }
            // W5: the editor now edits registered pipelines too — fall back to the lifted config.
            if (g == null) g = api.service().configFor(id).map(PipelineLift::lift).orElse(null);
            if (g == null) throw new ApiException(404, "no authored flow '" + id + "'");
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
        Path root = pipelinesRootOrNull(api);
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
    @SuppressWarnings("unchecked")
    private PipelineGraph candidateGraph(ApiContext api, Map<String, Object> body) {
        if (body == null || !(body.get("pipeline") instanceof Map<?, ?> candidate)) return null;
        return parseAndValidateFlow(api, (Map<String, Object>) candidate);
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
        if (!new PipelineStore(root).exists(id)) throw new ApiException(404, "no authored flow '" + id + "'");
        String runId;
        try {
            runId = api.service().jobServiceOrCreate().triggerPipelineRun(id, ApiContext.query(e, "actor"));
        } catch (IllegalStateException ex) {
            // the service booted without a write root, so its flow store never opened — same gate as above
            throw new ApiException(503, ex.getMessage());
        }
        log.info("[PIPELINE-RUN] ad-hoc run {} of authored flow {}", runId, id);
        e.getResponseHeaders().set("Location", (ApiContext.v1(e) ? "/api/v1" : "") + "/jobs/runs/" + runId);
        return ApiContext.respondJson(e, 202, Map.of("runId", runId, "pipeline", id, "status", "running"));
    }
}
