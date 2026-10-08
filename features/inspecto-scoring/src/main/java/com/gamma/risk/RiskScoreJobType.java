package com.gamma.risk;

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
import com.gamma.query.DatasetRelation;
import com.gamma.pipeline.ViewStore;
import com.gamma.signal.Severity;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The {@code risk.score} Job Type (ASSURE-RISK-SCORE-1, WS-22): evaluates one saved {@code risk-score} model
 * over its Datasets and writes the scores Dataset — one row per entity per run, each row carrying the factors
 * its score is recomputable from. A Job, not a Step Processor: the operator's hold on new Step Processors
 * stands. Trigger it on a schedule or {@code on_pipeline:} the pipeline that lands its source Datasets.
 *
 * <p>Reads the component registry from {@link SpaceConfigRoot} (this Space's, never the JVM-wide property) and
 * writes under the injected Space {@code dataDir} — the pair must move together.
 */
public final class RiskScoreJobType implements JobTypeProvider {

    static final JobTypeDescriptor DESCRIPTOR = new JobTypeDescriptor("risk.score", "Risk Score",
            "Scores every entity a saved Risk Score model names (Σ weight × indicator, 0–100, with its factors) "
                    + "and writes the scores Dataset.",
            List.of(ParameterDecl.required("model", ParamType.STRING, "Saved risk-score component id")),
            List.of("risk.score.produced"),
            List.of(ArtifactDecl.dataset("scores")));

    private final String dataDir;

    /** The {@code ServiceLoader} constructor: the Job resolves the Space's data root at run time. */
    public RiskScoreJobType() { this(null); }

    RiskScoreJobType(String dataDir) { this.dataDir = dataDir; }

    @Override public JobTypeDescriptor descriptor() { return DESCRIPTOR; }

    @Override public Job create(JobConfig config) { return new RiskScoreJob(config, dataDir); }

    static final class RiskScoreJob implements Job {
        private final JobConfig cfg;
        private final String dataDir;

        RiskScoreJob(JobConfig cfg, String dataDir) {
            this.cfg = cfg;
            this.dataDir = dataDir;
        }

        @Override public String name() { return cfg.name(); }
        @Override public String type() { return "risk.score"; }

        @Override public JobResult run() {
            throw new UnsupportedOperationException("risk.score requires a JobContext");
        }

        @Override
        public JobResult run(JobContext ctx) throws Exception {
            long t0 = System.nanoTime();
            Path data = dataDir != null && !dataDir.isBlank() ? Path.of(dataDir) : SpaceConfigRoot.currentDataRoot();
            if (data == null)
                throw new IllegalStateException("risk.score needs a data root (space dataDir)");
            String modelId = cfg.require("model");
            Path writeRoot = SpaceConfigRoot.requireCurrent("risk.score");
            ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
            Map<String, Object> content = store.get("risk-score", modelId)
                    .map(ComponentRegistry.Component::content)
                    .orElseThrow(() -> new IllegalArgumentException("unknown risk-score model '" + modelId + "'"));
            RiskScoreModel model = RiskScoreModel.fromMap(modelId, content);
            ViewStore views = new ViewStore(writeRoot.resolve("views"));

            RiskScoreEvaluator.Run run = RiskScoreEvaluator.evaluate(model, datasetId -> {
                Map<String, Object> ds = store.get("dataset", datasetId).map(ComponentRegistry.Component::content)
                        .orElseThrow(() -> new IllegalArgumentException("risk-score '" + modelId
                                + "' names unknown dataset '" + datasetId + "'"));
                return DatasetRelation.relationSql(ds, data, views);
            }, com.gamma.mask.EvidenceMasker.of(store, writeRoot, model.datasetIds()));
            Instant now = Instant.now();
            String version = RiskScoreEvaluator.version(content);
            RiskScoreEvaluator.write(data, model, version, ctx.runId(), now, run.scored());

            long high = run.scored().stream().filter(RiskScorer.Scored::high).count();
            int fed = feedWatchList(writeRoot, data, model, ctx.runId(), now, run.scored());
            if (model.watchList() != null)
                ctx.log().info("fed watch list", "model", modelId, "list", model.watchList().list(), "written", fed);
            ctx.artifacts().dataset("scores", model.scoresDataset(), META, run.scored().size(), now);
            ctx.signals().emit("risk.score.produced", Severity.INFO, Map.of("model", modelId,
                    "dataset", model.scoresDataset(), "entities", run.scored().size(), "high", high));
            ctx.log().info("scored entities", "model", modelId, "entities", run.scored().size(), "high", high,
                    "modelVersion", version, "evidenceTruncated", run.evidenceTruncated());
            if (run.evidenceTruncated())
                ctx.log().warn("evidence read hit its cap - entities past it carry no evidence (their scores are unaffected)",
                        "model", modelId, "cap", RiskScoreEvaluator.MAX_EVIDENCE_ROWS);
            return JobResult.ok("risk.score: " + run.scored().size() + " entit(ies) scored, " + high
                    + " high → dataset '" + model.scoresDataset() + "'", (System.nanoTime() - t0) / 1_000_000L);
        }
    }

    /**
     * ASSURE-ENTITY-LISTS-1: add every {@code high} entity to the model's {@code watch} Entity List, expiring
     * {@code ttlHours} after {@code now}. No {@code watchList} is a no-op; one with no installed provider (Personal)
     * fails the run. The scores are already written, and the failure says the feed did not happen.
     */
    static int feedWatchList(Path writeRoot, Path dataDir, RiskScoreModel model, String runId, Instant now,
                             List<RiskScorer.Scored> scored) throws java.io.IOException {
        return feedWatchList(com.gamma.entitylist.WatchListFeed.installed(), writeRoot, dataDir, model, runId, now, scored);
    }

    /** {@link #feedWatchList(Path, Path, RiskScoreModel, String, Instant, List)} against an explicit provider (absent = none installed). */
    static int feedWatchList(java.util.Optional<com.gamma.entitylist.WatchListFeed> provider, Path writeRoot, Path dataDir,
                             RiskScoreModel model, String runId, Instant now, List<RiskScorer.Scored> scored)
            throws java.io.IOException {
        RiskScoreModel.WatchList w = model.watchList();
        if (w == null) return 0;
        com.gamma.entitylist.WatchListFeed feed = provider.orElseThrow(() ->
                new IllegalStateException("risk-score '" + model.id() + "' feeds watch list '" + w.list()
                        + "', but Entity Lists are not installed in this edition"));
        List<String> keys = scored.stream().filter(RiskScorer.Scored::high).map(RiskScorer.Scored::entityKey).toList();
        if (keys.isEmpty()) return 0;
        return feed.feed(writeRoot, dataDir, w.list(), keys, now.plus(java.time.Duration.ofHours(w.ttlHours())),
                "job:risk.score:" + model.id(), "risk.score " + model.id() + " run " + runId + ": score >= "
                        + model.highThreshold());
    }

    /** The scores Dataset's fixed shape. */
    static final ResultSetMeta META = new ResultSetMeta(List.of(
            new ResultSetMeta.Column("model", "VARCHAR", ResultSetMeta.Role.DIMENSION),
            new ResultSetMeta.Column("entity_type", "VARCHAR", ResultSetMeta.Role.DIMENSION),
            new ResultSetMeta.Column("entity_key", "VARCHAR", ResultSetMeta.Role.DIMENSION),
            new ResultSetMeta.Column("score", "DOUBLE", ResultSetMeta.Role.MEASURE),
            new ResultSetMeta.Column("high", "BOOLEAN", ResultSetMeta.Role.DIMENSION),
            new ResultSetMeta.Column("factors", "VARCHAR", ResultSetMeta.Role.DIMENSION),
            new ResultSetMeta.Column("model_version", "VARCHAR", ResultSetMeta.Role.DIMENSION),
            new ResultSetMeta.Column("run_id", "VARCHAR", ResultSetMeta.Role.DIMENSION),
            new ResultSetMeta.Column("scored_at", "TIMESTAMP", ResultSetMeta.Role.TEMPORAL)));
}
