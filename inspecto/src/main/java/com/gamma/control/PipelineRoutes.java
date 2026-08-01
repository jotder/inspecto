package com.gamma.control;

import com.gamma.config.io.ConfigCodec;
import com.gamma.config.io.ConfigLoader;
import com.gamma.config.spec.ConfigSpecs;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.Severity;
import com.gamma.config.safety.ConfigSafetyValidator;
import com.gamma.config.safety.SafetyPolicy;
import com.gamma.etl.PipelineConfig;
import com.gamma.pipeline.PipelineCodec;
import com.gamma.pipeline.PipelineCompileException;
import com.gamma.pipeline.PipelineEditable;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineProjection;
import com.gamma.pipeline.PipelineStore;
import com.gamma.pipeline.PipelineValidator;
import com.gamma.pipeline.PipelineLift;
import com.gamma.pipeline.exec.PipelineDryRun;
import com.gamma.service.CollectorService;
import com.gamma.util.AtomicFiles;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Flow graph routes ({@code /pipelines*}): the read-only lifted-pipeline projections (T31) and the
 * authored-flow topology CRUD (T19, §7.1) persisted as {@code *_flow.toon} under the write root.
 * Extracted verbatim from {@link ControlApi}: identical routes, order, HTTP statuses and validation.
 */
final class PipelineRoutes implements RouteModule {

    private static final Logger log = LoggerFactory.getLogger(PipelineRoutes.class);

    @Override
    public void register(ApiContext api) {
        api.get("/pipelines", (e, m) -> flowSummaries(api));
        api.get("/pipelines/node-types", (e, m) -> PipelineProjection.catalog());
        api.get("/pipelines/combined", (e, m) -> combinedFlows(api));
        // *_flow.toon is GRANDFATHERED (W5, plan U-A): existing files stay readable / runnable /
        // deletable, but are never newly written — the authoring write routes are gone; the graph
        // editor now writes the canonical *_pipeline.toon via PUT /pipelines/{name}/graph below.
        api.get("/pipelines/authored", (e, m) -> authoredFlowList(api));
        api.get("/pipelines/authored/([^/]+)", (e, m) -> authoredFlow(api, ApiContext.name(m)));
        api.get("/pipelines/authored/([^/]+)/raw", (e, m) -> authoredFlowRaw(api, ApiContext.name(m)));
        api.delete("/pipelines/authored/([^/]+)", ApiContext.withCapability("canAuthorWorkbench", (e, m) -> deleteFlow(api, ApiContext.name(m))));
        api.post("/pipelines/authored/([^/]+)/dry-run", (e, m) -> dryRunFlow(api, ApiContext.name(m), api.body(e)));
        // A real run is an operational verb (canOperateRuns) and mirrors POST /jobs/{name}/trigger — deliberately
        // NOT ".../run": that path is the editor's scratch-only run-to-here contract (POST …/run?to={nodeId},
        // pipelines.service.ts, mock-only today) and must never fire a production run.
        api.post("/pipelines/authored/([^/]+)/trigger", ApiContext.withCapability("canOperateRuns", (e, m) -> runFlow(api, e, ApiContext.name(m))));
        api.get("/pipelines/([^/]+)/graph", (e, m) -> graphForPipeline(api, ApiContext.name(m)));
        // W5 (plan U-A): the editable round-trip over the canonical *_pipeline.toon.
        api.get("/pipelines/([^/]+)/graph/raw", (e, m) -> editableGraph(api, ApiContext.name(m)));
        api.put("/pipelines/([^/]+)/graph", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> saveGraph(api, e, ApiContext.name(m), api.body(e))));
    }

    /** Lift every registered pipeline to a {@link PipelineGraph} and project a compact summary (GET /pipelines). */
    private Object flowSummaries(ApiContext api) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CollectorService.PipelineView pv : api.service().pipelines()) {
            api.service().configFor(pv.name())
                    .ifPresent(c -> out.add(PipelineProjection.summary(PipelineLift.lift(c))));
        }
        return out;
    }

    /** Lift every registered pipeline and project the combined pipeline+job topology (GET /pipelines/combined, T24). */
    private Object combinedFlows(ApiContext api) {
        return PipelineProjection.combined(liftedFlows(api.service()));
    }

    /** Every registered pipeline lifted to a {@link PipelineGraph} (shared with the component safe-delete check). */
    static List<PipelineGraph> liftedFlows(CollectorService service) {
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

    private Path flowsRootOrNull(ApiContext api) {
        return api.writeRoot() == null ? null : api.writeRoot().resolve("flows");
    }

    private PipelineStore flowStore(ApiContext api) {
        return new PipelineStore(WriteGates.requireWriteRoot(api, "pipeline write").resolve("flows"));
    }

    /** {@code GET /pipelines/authored} — summaries of every authored flow (empty when no write root). */
    private Object authoredFlowList(ApiContext api) {
        Path root = flowsRootOrNull(api);
        if (root == null) return List.of();
        return new PipelineStore(root).list().stream().map(PipelineProjection::summary).toList();
    }

    /** {@code GET /pipelines/authored/{id}} — one authored flow's graph projection; 404 if absent. */
    private Object authoredFlow(ApiContext api, String id) {
        Path root = flowsRootOrNull(api);
        PipelineGraph g = root == null ? null : new PipelineStore(root).get(id).orElse(null);
        if (g == null) throw new ApiException(404, "no authored flow '" + id + "'");
        return PipelineProjection.graph(g);
    }

    /**
     * {@code GET /pipelines/authored/{id}/raw} — the <b>lossless</b> authored definition ({@link PipelineCodec#toMap},
     * nodes with their config) so the editor can round-trip a flow without dropping node config; the
     * {@link #authoredFlow} projection is structural-only. 404 if absent.
     */
    private Object authoredFlowRaw(ApiContext api, String id) {
        Path root = flowsRootOrNull(api);
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
     * existing {@code <name>_pipeline.toon} ({@link PipelineEditable#lower} — verbatim sections,
     * unmodeled keys preserved), run the SAME spec + safety gate as {@code POST /config/write}, and
     * write atomically. An {@code active} graph (or a brand-new file) must be complete; an inactive
     * draft may be partial. Unrepresentable topologies 422 with named {@code refusals[]} instead of
     * being silently truncated.
     */
    private Object saveGraph(ApiContext api, HttpExchange e, String name, Map<String, Object> body) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "pipeline write");
        Map<String, Object> withId = new LinkedHashMap<>(body);
        withId.put("name", name);   // the URL name wins over any name in the body
        PipelineGraph g = parseAndValidateFlow(withId);

        String fileName = WriteGates.safeName(name, "pipeline name") + "_pipeline.toon";
        Path target = WriteGates.jail(writeRoot, writeRoot.resolve(fileName), "resolved path");
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
        log.info("[PIPELINE-WRITE] lowered graph '{}' to {} ({} bytes)", name, fileName, bytes.length);

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

    /** {@code DELETE /pipelines/authored/{id}} — remove an authored flow; 404 if absent. */
    private Object deleteFlow(ApiContext api, String id) throws IOException {
        PipelineStore store = flowStore(api);
        if (!flowExists(store, id)) throw new ApiException(404, "no authored flow '" + id + "'");
        boolean removed;
        try {
            removed = store.delete(id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        return Map.of("id", id, "deleted", true, "fileRemoved", removed);
    }

    /** Parse a flow definition (400 on a malformed shape) and validate it (422 on validation errors). */
    private PipelineGraph parseAndValidateFlow(Map<String, Object> body) {
        PipelineGraph g;
        try {
            g = PipelineCodec.fromMap(body);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        validateFlow(g);
        return g;
    }

    private void validateFlow(PipelineGraph g) {
        PipelineValidator.Result r = PipelineValidator.validate(g);
        if (!r.ok())
            throw new ApiException(422, "flow validation failed: " + r.errors().stream()
                    .map(i -> i.code() + " — " + i.message()).toList());
    }

    private static boolean flowExists(PipelineStore store, String id) {
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
     */
    private Object dryRunFlow(ApiContext api, String id, Map<String, Object> body) {
        Path root = flowsRootOrNull(api);
        PipelineGraph g;
        try {
            g = root == null ? null : new PipelineStore(root).get(id).orElse(null);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        // W5: the editor now edits registered pipelines too — fall back to the lifted config.
        if (g == null) g = api.service().configFor(id).map(PipelineLift::lift).orElse(null);
        if (g == null) throw new ApiException(404, "no authored flow '" + id + "'");
        try {
            return PipelineDryRun.run(g, ApiContext.sampleRows(body));
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        } catch (Exception e) {
            throw new ApiException(422, "dry-run failed: " + e.getMessage());
        }
    }

    /**
     * {@code POST /pipelines/authored/{id}/trigger} — run an authored flow for real, once, config-less (T32
     * follow-up): no {@code type: pipeline} {@code *_job.toon} needed. The fire goes through
     * {@link com.gamma.job.JobService#triggerFlowRun} so it gets the full registered-run lifecycle
     * (deletion-fence tracking, non-overlap, durable run ledger) without registering a job. Async:
     * {@code 202} + {@code {runId,...}} + a {@code Location} to poll ({@code GET /jobs/runs/{runId}});
     * optional {@code ?actor=} attributes the fire. 503 without a write root, 404 if the flow is absent.
     */
    private Object runFlow(ApiContext api, HttpExchange e, String id) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "pipeline run").resolve("flows");
        if (!new PipelineStore(root).exists(id)) throw new ApiException(404, "no authored flow '" + id + "'");
        String runId;
        try {
            runId = api.service().jobServiceOrCreate().triggerFlowRun(id, ApiContext.query(e, "actor"));
        } catch (IllegalStateException ex) {
            // the service booted without a write root, so its flow store never opened — same gate as above
            throw new ApiException(503, ex.getMessage());
        }
        log.info("[PIPELINE-RUN] ad-hoc run {} of authored flow {}", runId, id);
        e.getResponseHeaders().set("Location", (ApiContext.v1(e) ? "/api/v1" : "") + "/jobs/runs/" + runId);
        return ApiContext.respondJson(e, 202, Map.of("runId", runId, "pipeline", id, "status", "running"));
    }
}
