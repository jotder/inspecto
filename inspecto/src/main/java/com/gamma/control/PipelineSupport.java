package com.gamma.control;

import com.gamma.config.spec.Finding;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineLift;
import com.gamma.service.CollectorService;
import com.gamma.service.PipelineView;
import com.gamma.service.SpaceRoot;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Statics shared by the pipeline route modules ({@link PipelineListRoutes}, {@link PipelineGraphRoutes},
 * {@link PipelineSettingsRoutes}, {@link PipelineRenameRoutes}) split out of the former
 * {@code PipelineRoutes}, and by {@link ComponentRoutes}' safe-delete check. Pure relocation —
 * bodies verbatim.
 */
final class PipelineSupport {

    private PipelineSupport() {}

    /** Every registered pipeline lifted to a {@link PipelineGraph} (shared with the component safe-delete check). */
    static List<PipelineGraph> liftedPipelines(CollectorService service) {
        List<PipelineGraph> graphs = new ArrayList<>();
        for (PipelineView pv : service.pipelines()) {
            service.configFor(pv.name()).ifPresent(c -> graphs.add(PipelineLift.lift(c)));
        }
        return graphs;
    }

    static Path pipelinesRootOrNull(ApiContext api) {
        return api.writeRoot() == null ? null : SpaceRoot.pipelinesSubdir(api.writeRoot());
    }

    /** Identity of a {@link Finding} for before/after comparison — its anchor plus its message. */
    static String findingKey(Finding f) {
        return f.fieldPath() + "|" + f.message();
    }
}
