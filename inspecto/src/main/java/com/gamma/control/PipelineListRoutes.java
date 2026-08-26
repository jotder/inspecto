package com.gamma.control;

import com.gamma.pipeline.PipelineCodec;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineLift;
import com.gamma.pipeline.PipelineProjection;
import com.gamma.pipeline.PipelineStore;
import com.gamma.service.CollectorService;
import com.gamma.service.SpaceRoot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pipeline listing and authored-flow read/delete routes ({@code /pipelines}, {@code /pipelines/node-types},
 * {@code /pipelines/step-types}, {@code /pipelines/combined}, {@code /pipelines/authored*}): the read-only
 * lifted-pipeline projections (T31) and the grandfathered {@code *_flow.toon} reads (W5, plan U-A).
 * Extracted verbatim from {@code PipelineRoutes}: identical routes, order, HTTP statuses and validation.
 */
final class PipelineListRoutes implements RouteModule {

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
                // Same conditional style: absent ⇒ the payload is byte-identical to before.
                if (!c.description().isEmpty()) s.put("description", c.description());
                out.add(s);
            });
        }
        return out;
    }

    /** Lift every registered pipeline and project the combined pipeline+job topology (GET /pipelines/combined, T24). */
    private Object combinedPipelines(ApiContext api) {
        return PipelineProjection.combined(PipelineSupport.liftedPipelines(api.service()));
    }

    private PipelineStore pipelineStore(ApiContext api) {
        return new PipelineStore(SpaceRoot.pipelinesSubdir(WriteGates.requireWriteRoot(api, "pipeline write")));
    }

    /** {@code GET /pipelines/authored} — summaries of every authored flow (empty when no write root). */
    private Object authoredPipelineList(ApiContext api) {
        Path root = PipelineSupport.pipelinesRootOrNull(api);
        if (root == null) return List.of();
        return new PipelineStore(root).list().stream().map(PipelineProjection::summary).toList();
    }

    /** {@code GET /pipelines/authored/{id}} — one authored flow's graph projection; 404 if absent. */
    private Object authoredPipeline(ApiContext api, String id) {
        Path root = PipelineSupport.pipelinesRootOrNull(api);
        PipelineGraph g = root == null ? null : new PipelineStore(root).get(id).orElse(null);
        if (g == null) throw new ApiException(404, "no authored pipeline '" + id + "'");
        return PipelineProjection.graph(g);
    }

    /**
     * {@code GET /pipelines/authored/{id}/raw} — the <b>lossless</b> authored definition ({@link PipelineCodec#toMap},
     * nodes with their config) so the editor can round-trip a flow without dropping node config; the
     * {@link #authoredPipeline} projection is structural-only. 404 if absent.
     */
    private Object authoredPipelineRaw(ApiContext api, String id) {
        Path root = PipelineSupport.pipelinesRootOrNull(api);
        PipelineGraph g = root == null ? null : new PipelineStore(root).get(id).orElse(null);
        if (g == null) throw new ApiException(404, "no authored pipeline '" + id + "'");
        return PipelineCodec.toMap(g);
    }

    /** {@code DELETE /pipelines/authored/{id}} — remove an authored flow; 404 if absent. */
    private Object deletePipeline(ApiContext api, String id) throws IOException {
        PipelineStore store = pipelineStore(api);
        if (!pipelineExists(store, id)) throw new ApiException(404, "no authored pipeline '" + id + "'");
        boolean removed;
        try {
            removed = store.delete(id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        return Map.of("id", id, "deleted", true, "fileRemoved", removed);
    }

    private static boolean pipelineExists(PipelineStore store, String id) {
        try {
            return store.exists(id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, e.getMessage());
        }
    }
}
