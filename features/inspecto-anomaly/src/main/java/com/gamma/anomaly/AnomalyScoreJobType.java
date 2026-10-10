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

import com.gamma.entitylist.WatchListFeed;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

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

            AnomalyLists lists = AnomalyLists.of(content);
            Predicate<String> exclusion = exclusion(WatchListFeed.installed(), writeRoot, model, lists);
            AnomalyScoreEvaluator.Run run = AnomalyScoreEvaluator.evaluate(model, asOf, datasetId -> {
                Map<String, Object> ds = store.get("dataset", datasetId).map(ComponentRegistry.Component::content)
                        .orElseThrow(() -> new IllegalArgumentException("anomaly-model '" + modelId
                                + "' names unknown dataset '" + datasetId + "'"));
                return DatasetRelation.relationSql(ds, data, views);
            }, exclusion);
            int excluded = run.excluded();
            String version = AnomalyScoreEvaluator.version(content);
            AnomalyScoreEvaluator.write(data, model, version, ctx.runId(), now, run);
            int fed = feedWatchList(WatchListFeed.installed(), writeRoot, data, model, lists, ctx.runId(), now, run.scored());
            if (lists.exclusionList() != null)
                ctx.log().info("excluded entities", "model", modelId, "list", lists.exclusionList(), "excluded", excluded);
            if (lists.watchList() != null)
                ctx.log().info("fed watch list", "model", modelId, "list", lists.watchList().list(), "written", fed);

            Map<String, Long> bands = AnomalyScoreEvaluator.bands(run.scored());
            long insufficient = run.scored().stream().filter(s -> s.insufficientCount() == model.features().size()).count();
            ctx.artifacts().dataset("scores", model.scoresDataset(), META, run.scored().size(), now);
            ctx.signals().emit(AnomalySignals.ANOMALY_SCORE_PRODUCED, Severity.INFO, Map.of("model", modelId,
                    "run", ctx.runId(), "scored", run.scored().size(), "elevated", bands.get("elevated"),
                    "high", bands.get("high"), "insufficient", insufficient, "excluded", excluded));
            ctx.log().info("scored entities", "model", modelId, "period", run.period().toString(),
                    "entities", run.scored().size(), "elevated", bands.get("elevated"), "high", bands.get("high"),
                    "insufficient", insufficient, "excluded", excluded, "modelVersion", version);
            return JobResult.ok("anomaly.score: " + run.scored().size() + " entit(ies) scored for " + run.period()
                    + ", " + bands.get("high") + " high -> dataset '" + model.scoresDataset() + "'",
                    (System.nanoTime() - t0) / 1_000_000L);
        }
    }

    /**
     * Design §9: the live-membership test of the model's {@code exclusion} Entity List, which
     * {@link AnomalyScoreEvaluator#evaluate} applies BEFORE the entity cap, the baselines and the peer-cohort medians
     * (the caller logs the count as {@code excluded} — never silent). No {@code exclusionList} excludes nothing; one
     * with no installed provider (Personal) or naming a list that is not a live exclusion list fails the run, before
     * anything is read or written.
     */
    static Predicate<String> exclusion(Optional<WatchListFeed> provider, Path writeRoot, AnomalyModel model,
                                       AnomalyLists lists) throws IOException {
        if (lists.exclusionList() == null) return k -> false;
        WatchListFeed feed = provider.orElseThrow(() -> new IllegalStateException("anomaly-model '" + model.id()
                + "' excludes through list '" + lists.exclusionList() + "', but Entity Lists are not installed in this edition"));
        return feed.check(writeRoot, lists.exclusionList(), WatchListFeed.EXCLUSION);
    }

    /**
     * Design §9: add every {@code high} entity to the model's {@code watch} Entity List, expiring {@code ttlHours}
     * after {@code now}. No {@code watchList} is a no-op; one with no installed provider (Personal) fails the run —
     * the scores are already written, and the failure says the feed did not happen.
     */
    static int feedWatchList(Optional<WatchListFeed> provider, Path writeRoot, Path dataDir, AnomalyModel model,
                             AnomalyLists lists, String runId, Instant now, List<AnomalyScorer.Scored> scored)
            throws IOException {
        AnomalyLists.WatchList w = lists.watchList();
        if (w == null) return 0;
        WatchListFeed feed = provider.orElseThrow(() -> new IllegalStateException("anomaly-model '" + model.id()
                + "' feeds watch list '" + w.list() + "', but Entity Lists are not installed in this edition"));
        List<String> keys = scored.stream().filter(s -> "high".equals(s.band())).map(AnomalyScorer.Scored::entityKey).toList();
        if (keys.isEmpty()) return 0;
        return feed.feed(writeRoot, dataDir, w.list(), keys, now.plus(Duration.ofHours(w.ttlHours())),
                "job:anomaly.score:" + model.id(), "anomaly.score " + model.id() + " run " + runId + ": band high (score >= "
                        + model.highThreshold() + ")");
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
