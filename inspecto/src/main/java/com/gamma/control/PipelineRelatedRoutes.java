package com.gamma.control;

import com.gamma.etl.PipelineConfig;
import com.gamma.service.PipelineDependents;
import com.gamma.service.PipelineRelated;
import com.sun.net.httpserver.HttpExchange;

import java.nio.file.Path;

/**
 * {@code GET /pipelines/{name}/related} — everything related to one pipeline (pipeline spec §12 gap 5,
 * decision D9): what it points OUT to (its schemas, mapping, grammar and other shared components) and
 * what points IN at it (enrichments, jobs, datasets and the Studio hops beyond them).
 *
 * <p><b>Why this exists as one route.</b> §4's complaint is that reference direction is inconsistent:
 * a pipeline names its schema, while an enrichment or job names the pipeline. Every caller that wanted
 * "everything belonging to this pipeline" — bundle export first among them — had to know that and scan
 * both ways itself. This is the server-side closure, so the answer is computed once, where the rules
 * live.
 *
 * <p><b>Gate order</b> is the read side's, matching {@code GET /config/pipeline/{name}/impact}:
 * write-root 503 → unsafe name 422 → unknown pipeline 404. ⚠ The 503 is <b>not</b> a write gate
 * dressed up as a read one — the inward half must walk the configs under the write root, so without
 * one there is no corpus to scan. Answering with only the outward half would be a partial closure
 * presented as a whole, which is precisely the trap for a caller asking "what does an import need".
 *
 * <p>{@code ?limit=} bounds the inward list, which is hard-capped by
 * {@link PipelineDependents#MAX_DEPENDENTS} regardless and always reports the TRUE total.
 */
final class PipelineRelatedRoutes implements RouteModule {

    @Override
    public void register(ApiContext api) {
        api.get("/pipelines/([^/]+)/related", (e, m) -> related(api, e, ApiContext.name(m)));
    }

    private Object related(ApiContext api, HttpExchange ex, String name) {
        Path writeRoot = WriteGates.requireWriteRoot(api, "pipeline related");
        String id = WriteGates.safeName(name, "pipeline name");
        PipelineConfig cfg = api.service().configFor(id)
                .orElseThrow(() -> new ApiException(404, "no pipeline named '" + id + "'"));
        return PipelineRelated.toJson(PipelineRelated.of(writeRoot, cfg, limit(ex)));
    }

    /** {@code ?limit=} for the inward list — a positive integer, or the default cap. */
    private static int limit(HttpExchange ex) {
        String raw = ApiContext.query(ex, "limit");
        if (raw == null || raw.isBlank()) return PipelineDependents.MAX_DEPENDENTS;
        int limit;
        try {
            limit = Integer.parseInt(raw.trim());
        } catch (NumberFormatException nfe) {
            throw new ApiException(400, "limit must be an integer");
        }
        if (limit < 1) throw new ApiException(400, "limit must be positive");
        return limit;
    }
}
