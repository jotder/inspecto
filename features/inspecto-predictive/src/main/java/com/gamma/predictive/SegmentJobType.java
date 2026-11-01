package com.gamma.predictive;

import com.gamma.alert.ScoreOutputWriter;
import com.gamma.job.ArtifactDecl;
import com.gamma.job.Job;
import com.gamma.job.JobConfig;
import com.gamma.job.JobContext;
import com.gamma.job.JobResult;
import com.gamma.job.JobTypeDescriptor;
import com.gamma.job.JobTypeProvider;
import com.gamma.job.ParamType;
import com.gamma.job.ParameterDecl;
import com.gamma.job.ResultSetMeta;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.SpaceConfigRoot;
import com.gamma.pipeline.ViewStore;
import com.gamma.query.DatasetRelation;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * The {@code predict.segment} Job Type (PREDICTIVE-SEGMENT-RFM-1): assigns every entity of one saved
 * {@code segmentation-model} to a Customer Segment from its RFM Measures over the model's window ending at
 * {@code as_of} (inclusive; default yesterday, UTC — the last complete day) and writes
 * {@code customer_segments_<model>} + {@code _latest}. Trigger it on a schedule or {@code on_pipeline:} the Pipeline
 * that lands its Dataset.
 */
public final class SegmentJobType implements JobTypeProvider {

    static final JobTypeDescriptor DESCRIPTOR = new JobTypeDescriptor("predict.segment", "Customer Segments",
            "Assigns every entity a saved Segmentation Model names to a Customer Segment: recency, frequency and "
                    + "monetary over a trailing window, cut into quantile bins and looked up in the model's label table.",
            List.of(ParameterDecl.required("model", ParamType.STRING, "Saved segmentation-model component id").asConfigOnly(),
                    ParameterDecl.optional("as_of", ParamType.STRING, null,
                            "Last day of the window (YYYY-MM-DD, inclusive); default yesterday (UTC)")),
            List.of(),
            List.of(ArtifactDecl.dataset("segments")));

    private final String dataDir;

    public SegmentJobType() { this(null); }

    SegmentJobType(String dataDir) { this.dataDir = dataDir; }

    @Override public JobTypeDescriptor descriptor() { return DESCRIPTOR; }

    @Override public Job create(JobConfig config) { return new SegmentJob(config, dataDir); }

    static LocalDate asOf(String raw, Instant now) {
        if (raw == null || raw.isBlank()) return now.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1);
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("predict.segment as_of must be YYYY-MM-DD, got '" + raw + "'");
        }
    }

    static final class SegmentJob implements Job {
        private final JobConfig cfg;
        private final String dataDir;

        SegmentJob(JobConfig cfg, String dataDir) {
            this.cfg = cfg;
            this.dataDir = dataDir;
        }

        @Override public String name() { return cfg.name(); }
        @Override public String type() { return "predict.segment"; }

        @Override public JobResult run() {
            throw new UnsupportedOperationException("predict.segment requires a JobContext");
        }

        @Override
        public JobResult run(JobContext ctx) throws Exception {
            long t0 = System.nanoTime();
            Path data = dataDir != null && !dataDir.isBlank() ? Path.of(dataDir) : SpaceConfigRoot.currentDataRoot();
            if (data == null) throw new IllegalStateException("predict.segment needs a data root (space dataDir)");
            String modelId = cfg.require("model");
            Instant now = Instant.now();
            LocalDate asOf = asOf(ctx.params() != null && ctx.params().get("as_of") != null
                    ? ctx.params().get("as_of") : cfg.params() == null ? null : cfg.params().get("as_of"), now);
            Path writeRoot = SpaceConfigRoot.requireCurrent("predict.segment");
            ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
            Map<String, Object> content = store.get(SegmentationModel.KIND, modelId)
                    .map(ComponentRegistry.Component::content)
                    .orElseThrow(() -> new IllegalArgumentException("unknown segmentation-model '" + modelId + "'"));
            SegmentationModel model = SegmentationModel.fromMap(modelId, content);
            Map<String, Object> ds = store.get("dataset", model.dataset()).map(ComponentRegistry.Component::content)
                    .orElseThrow(() -> new IllegalArgumentException("segmentation-model '" + modelId
                            + "' names unknown dataset '" + model.dataset() + "'"));
            String relation = DatasetRelation.relationSql(ds, data, new ViewStore(writeRoot.resolve("views")));

            List<RfmSegmenter.Assignment> assigned = SegmentEvaluator.evaluate(model, asOf, relation);
            String version = SegmentEvaluator.version(content);
            SegmentEvaluator.write(data, model, version, ctx.runId(), now, asOf, assigned);
            Map<String, Long> counts = SegmentEvaluator.counts(assigned);
            ctx.artifacts().dataset("segments", model.segmentsDataset(), META, assigned.size(), now);
            ctx.log().info("segmented entities", "model", modelId, "asOf", asOf.toString(), "entities", assigned.size(),
                    "segments", counts.toString(), "modelVersion", version);
            return JobResult.ok("predict.segment: " + assigned.size() + " entit(ies) segmented as of " + asOf
                    + " -> dataset '" + model.segmentsDataset() + "'", (System.nanoTime() - t0) / 1_000_000L);
        }
    }

    /** The segments Dataset's fixed shape. */
    static final ResultSetMeta META = ScoreOutputWriter.meta(SegmentEvaluator.COLUMNS);
}
