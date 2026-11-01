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
import com.gamma.signal.Severity;
import com.gamma.signal.SignalType;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * The {@code predict.churn} Job Type (PREDICTIVE-CHURN-DECLARED-1): scores every entity of one saved
 * {@code churn-model} with its declared logistic score as of {@code as_of} (inclusive; default yesterday, UTC), writes
 * {@code churn_scores_<model>} + {@code _latest} and emits {@code churn.score.produced}, which releases the pending
 * Alert Rules waiting on {@code afterScore: {kind: churn-model, model}}.
 */
public final class ChurnJobType implements JobTypeProvider {

    static final JobTypeDescriptor DESCRIPTOR = new JobTypeDescriptor("predict.churn", "Churn Scores",
            "Scores every entity a saved Churn Model names: a declared-coefficient logistic score (0-100) over windowed "
                    + "Measures, with each input's contribution; an entity with too little history is insufficient.",
            List.of(ParameterDecl.required("model", ParamType.STRING, "Saved churn-model component id").asConfigOnly(),
                    ParameterDecl.optional("as_of", ParamType.STRING, null,
                            "Last day of the window (YYYY-MM-DD, inclusive); default yesterday (UTC)")),
            List.of(SignalType.CHURN_SCORE_PRODUCED),
            List.of(ArtifactDecl.dataset("scores")));

    private final String dataDir;

    public ChurnJobType() { this(null); }

    ChurnJobType(String dataDir) { this.dataDir = dataDir; }

    @Override public JobTypeDescriptor descriptor() { return DESCRIPTOR; }

    @Override public Job create(JobConfig config) { return new ChurnJob(config, dataDir); }

    static LocalDate asOf(String raw, Instant now) {
        if (raw == null || raw.isBlank()) return now.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1);
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("predict.churn as_of must be YYYY-MM-DD, got '" + raw + "'");
        }
    }

    static final class ChurnJob implements Job {
        private final JobConfig cfg;
        private final String dataDir;

        ChurnJob(JobConfig cfg, String dataDir) {
            this.cfg = cfg;
            this.dataDir = dataDir;
        }

        @Override public String name() { return cfg.name(); }
        @Override public String type() { return "predict.churn"; }

        @Override public JobResult run() {
            throw new UnsupportedOperationException("predict.churn requires a JobContext");
        }

        @Override
        public JobResult run(JobContext ctx) throws Exception {
            long t0 = System.nanoTime();
            Path data = dataDir != null && !dataDir.isBlank() ? Path.of(dataDir) : SpaceConfigRoot.currentDataRoot();
            if (data == null) throw new IllegalStateException("predict.churn needs a data root (space dataDir)");
            String modelId = cfg.require("model");
            Instant now = Instant.now();
            LocalDate asOf = asOf(ctx.params() != null && ctx.params().get("as_of") != null
                    ? ctx.params().get("as_of") : cfg.params() == null ? null : cfg.params().get("as_of"), now);
            Path writeRoot = SpaceConfigRoot.requireCurrent("predict.churn");
            ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
            Map<String, Object> content = store.get(ChurnModel.KIND, modelId)
                    .map(ComponentRegistry.Component::content)
                    .orElseThrow(() -> new IllegalArgumentException("unknown churn-model '" + modelId + "'"));
            ChurnModel model = ChurnModel.fromMap(modelId, content);
            Map<String, Object> ds = store.get("dataset", model.dataset()).map(ComponentRegistry.Component::content)
                    .orElseThrow(() -> new IllegalArgumentException("churn-model '" + modelId
                            + "' names unknown dataset '" + model.dataset() + "'"));
            String relation = DatasetRelation.relationSql(ds, data, new ViewStore(writeRoot.resolve("views")));

            List<ChurnScorer.Scored> scored = ChurnEvaluator.evaluate(model, asOf, relation);
            String version = ChurnEvaluator.version(content);
            ChurnEvaluator.write(data, model, version, ctx.runId(), now, asOf, scored);
            Map<String, Long> bands = ChurnEvaluator.bands(scored);
            ctx.artifacts().dataset("scores", model.scoresDataset(), META, scored.size(), now);
            ctx.signals().emit(SignalType.CHURN_SCORE_PRODUCED, Severity.INFO, Map.of("model", modelId,
                    "run", ctx.runId(), "scored", scored.size(), "elevated", bands.get("elevated"),
                    "high", bands.get("high"), "insufficient", bands.get(ChurnModel.INSUFFICIENT)));
            ctx.log().info("scored entities", "model", modelId, "asOf", asOf.toString(), "entities", scored.size(),
                    "elevated", bands.get("elevated"), "high", bands.get("high"),
                    "insufficient", bands.get(ChurnModel.INSUFFICIENT), "modelVersion", version);
            return JobResult.ok("predict.churn: " + scored.size() + " entit(ies) scored as of " + asOf + ", "
                    + bands.get("high") + " high, " + bands.get(ChurnModel.INSUFFICIENT) + " insufficient -> dataset '"
                    + model.scoresDataset() + "'", (System.nanoTime() - t0) / 1_000_000L);
        }
    }

    /** The scores Dataset's fixed shape. */
    static final ResultSetMeta META = ScoreOutputWriter.meta(ChurnEvaluator.COLUMNS);
}
