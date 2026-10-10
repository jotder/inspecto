package com.gamma.anomaly;

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
import com.gamma.signal.Severity;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * The {@code anomaly.score} Job Type (ANOMALY-DETECTION-1): scores every entity of one saved {@code anomaly-model}
 * for the day before {@code as_of} (default today, UTC) against its own baseline and writes
 * {@code anomaly_scores_<model>} + {@code _latest}. Trigger it on a schedule or {@code on_pipeline:} the Pipeline
 * that lands its feature Datasets.
 */
public final class AnomalyScoreJobType implements JobTypeProvider {

    static final JobTypeDescriptor DESCRIPTOR = new JobTypeDescriptor("anomaly.score", "Anomaly Score",
            "Scores every entity a saved Anomaly Model names against its own robust baseline (median / MAD, 0-100, "
                    + "with a per-feature explanation) and writes the scores Dataset.",
            List.of(ParameterDecl.required("model", ParamType.STRING, "Saved anomaly-model component id"),
                    ParameterDecl.optional("as_of", ParamType.STRING, null,
                            "Score the day before this date (YYYY-MM-DD, UTC); default today")),
            List.of(AnomalySignals.ANOMALY_SCORE_PRODUCED),
            List.of(ArtifactDecl.dataset("scores")));

    private final String dataDir;

    public AnomalyScoreJobType() { this(null); }

    AnomalyScoreJobType(String dataDir) { this.dataDir = dataDir; }

    @Override public JobTypeDescriptor descriptor() { return DESCRIPTOR; }

    @Override public Job create(JobConfig config) { return new AnomalyScoreJob(config, dataDir); }

    static LocalDate asOf(String raw, Instant now) {
        if (raw == null || raw.isBlank()) return now.atZone(ZoneOffset.UTC).toLocalDate();
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("anomaly.score as_of must be YYYY-MM-DD, got '" + raw + "'");
        }
    }

    static final class AnomalyScoreJob implements Job {
        private final JobConfig cfg;
        private final String dataDir;

        AnomalyScoreJob(JobConfig cfg, String dataDir) {
            this.cfg = cfg;
            this.dataDir = dataDir;
        }

        @Override public String name() { return cfg.name(); }
        @Override public String type() { return "anomaly.score"; }

        @Override public JobResult run() {
            throw new UnsupportedOperationException("anomaly.score requires a JobContext");
        }

        @Override
        public JobResult run(JobContext ctx) throws Exception {
            long t0 = System.nanoTime();
            Path data = dataDir != null && !dataDir.isBlank() ? Path.of(dataDir) : SpaceConfigRoot.currentDataRoot();
            if (data == null) throw new IllegalStateException("anomaly.score needs a data root (space dataDir)");
            String modelId = cfg.require("model");
            Instant now = Instant.now();
            LocalDate asOf = asOf(cfg.params() == null ? null : cfg.params().get("as_of"), now);
            Path writeRoot = SpaceConfigRoot.requireCurrent("anomaly.score");
            ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
            Map<String, Object> content = store.get(AnomalyModel.KIND, modelId)
                    .map(ComponentRegistry.Component::content)
                    .orElseThrow(() -> new IllegalArgumentException("unknown anomaly-model '" + modelId + "'"));
            AnomalyModel model = AnomalyModel.fromMap(modelId, content);
            ViewStore views = new ViewStore(writeRoot.resolve("views"));

            AnomalyScoreEvaluator.Run run = AnomalyScoreEvaluator.evaluate(model, asOf, datasetId -> {
                Map<String, Object> ds = store.get("dataset", datasetId).map(ComponentRegistry.Component::content)
                        .orElseThrow(() -> new IllegalArgumentException("anomaly-model '" + modelId
                                + "' names unknown dataset '" + datasetId + "'"));
                return DatasetRelation.relationSql(ds, data, views);
            });
            String version = AnomalyScoreEvaluator.version(content);
            AnomalyScoreEvaluator.write(data, model, version, ctx.runId(), now, run);

            Map<String, Long> bands = AnomalyScoreEvaluator.bands(run.scored());
            long insufficient = run.scored().stream().filter(s -> s.insufficientCount() == model.features().size()).count();
            ctx.artifacts().dataset("scores", model.scoresDataset(), META, run.scored().size(), now);
            ctx.signals().emit(AnomalySignals.ANOMALY_SCORE_PRODUCED, Severity.INFO, Map.of("model", modelId,
                    "run", ctx.runId(), "scored", run.scored().size(), "elevated", bands.get("elevated"),
                    "high", bands.get("high"), "insufficient", insufficient));
            ctx.log().info("scored entities", "model", modelId, "period", run.period().toString(),
                    "entities", run.scored().size(), "elevated", bands.get("elevated"), "high", bands.get("high"),
                    "insufficient", insufficient, "modelVersion", version);
            return JobResult.ok("anomaly.score: " + run.scored().size() + " entit(ies) scored for " + run.period()
                    + ", " + bands.get("high") + " high -> dataset '" + model.scoresDataset() + "'",
                    (System.nanoTime() - t0) / 1_000_000L);
        }
    }

    /** The scores Dataset's fixed shape. */
    static final ResultSetMeta META = new ResultSetMeta(List.of(
            new ResultSetMeta.Column("model", "VARCHAR", ResultSetMeta.Role.DIMENSION),
            new ResultSetMeta.Column("entity_type", "VARCHAR", ResultSetMeta.Role.DIMENSION),
            new ResultSetMeta.Column("entity_key", "VARCHAR", ResultSetMeta.Role.DIMENSION),
            new ResultSetMeta.Column("period_start", "TIMESTAMP", ResultSetMeta.Role.TEMPORAL),
            new ResultSetMeta.Column("score", "DOUBLE", ResultSetMeta.Role.MEASURE),
            new ResultSetMeta.Column("band", "VARCHAR", ResultSetMeta.Role.DIMENSION),
            new ResultSetMeta.Column("raw", "DOUBLE", ResultSetMeta.Role.MEASURE),
            new ResultSetMeta.Column("features", "VARCHAR", ResultSetMeta.Role.DIMENSION),
            new ResultSetMeta.Column("insufficient_count", "INTEGER", ResultSetMeta.Role.MEASURE),
            new ResultSetMeta.Column("model_version", "VARCHAR", ResultSetMeta.Role.DIMENSION),
            new ResultSetMeta.Column("run_id", "VARCHAR", ResultSetMeta.Role.DIMENSION),
            new ResultSetMeta.Column("scored_at", "TIMESTAMP", ResultSetMeta.Role.TEMPORAL)));
}
