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
import java.util.List;
import java.util.Map;

/**
 * The {@code predict.forecast} Job Type (PREDICTIVE-FORECAST-1): forecasts every series of one saved
 * {@code forecast-model} from its history ending at the last complete bucket on or before {@code as_of} (default
 * yesterday, UTC), with an interval and the method's backtest error, and writes {@code forecasts_<model>} +
 * {@code _latest}. Trigger it on a schedule or {@code on_pipeline:} the Pipeline that lands its Dataset.
 */
public final class ForecastJobType implements JobTypeProvider {

    static final JobTypeDescriptor DESCRIPTOR = new JobTypeDescriptor("predict.forecast", "Forecasts",
            "Forecasts every series a saved Forecast Model names: seasonal-naive or Holt-Winters per future bucket, "
                    + "with a prediction interval and the backtest error; a series with under two seasons of history "
                    + "is insufficient.",
            List.of(ParameterDecl.required("model", ParamType.STRING, "Saved forecast-model component id").asConfigOnly(),
                    ParameterDecl.optional("as_of", ParamType.STRING, null,
                            "Last day of the history (YYYY-MM-DD, inclusive); default yesterday (UTC)")),
            List.of(),
            List.of(ArtifactDecl.dataset("forecasts")));

    private final String dataDir;

    public ForecastJobType() { this(null); }

    ForecastJobType(String dataDir) { this.dataDir = dataDir; }

    @Override public JobTypeDescriptor descriptor() { return DESCRIPTOR; }

    @Override public Job create(JobConfig config) { return new ForecastJob(config, dataDir); }

    static final class ForecastJob implements Job {
        private final JobConfig cfg;
        private final String dataDir;

        ForecastJob(JobConfig cfg, String dataDir) {
            this.cfg = cfg;
            this.dataDir = dataDir;
        }

        @Override public String name() { return cfg.name(); }
        @Override public String type() { return "predict.forecast"; }

        @Override public JobResult run() {
            throw new UnsupportedOperationException("predict.forecast requires a JobContext");
        }

        @Override
        public JobResult run(JobContext ctx) throws Exception {
            long t0 = System.nanoTime();
            Path data = dataDir != null && !dataDir.isBlank() ? Path.of(dataDir) : SpaceConfigRoot.currentDataRoot();
            if (data == null) throw new IllegalStateException("predict.forecast needs a data root (space dataDir)");
            String modelId = cfg.require("model");
            Instant now = Instant.now();
            String rawAsOf = ctx.params() != null && ctx.params().get("as_of") != null
                    ? ctx.params().get("as_of") : cfg.params() == null ? null : cfg.params().get("as_of");
            LocalDate asOf;
            try {
                asOf = ChurnJobType.asOf(rawAsOf, now);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("predict.forecast as_of must be YYYY-MM-DD, got '" + rawAsOf + "'");
            }
            Path writeRoot = SpaceConfigRoot.requireCurrent("predict.forecast");
            ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
            Map<String, Object> content = store.get(ForecastModel.KIND, modelId)
                    .map(ComponentRegistry.Component::content)
                    .orElseThrow(() -> new IllegalArgumentException("unknown forecast-model '" + modelId + "'"));
            ForecastModel model = ForecastModel.fromMap(modelId, content);
            Map<String, Object> ds = store.get("dataset", model.dataset()).map(ComponentRegistry.Component::content)
                    .orElseThrow(() -> new IllegalArgumentException("forecast-model '" + modelId
                            + "' names unknown dataset '" + model.dataset() + "'"));
            String relation = DatasetRelation.relationSql(ds, data, new ViewStore(writeRoot.resolve("views")));

            List<Forecaster.Forecast> forecasts = ForecastEvaluator.evaluate(model, asOf, relation);
            String version = ForecastEvaluator.version(content);
            ForecastEvaluator.write(data, model, version, ctx.runId(), now, asOf, forecasts);
            long insufficient = forecasts.stream().filter(Forecaster.Forecast::isInsufficient).count();
            long rows = forecasts.stream().mapToLong(f -> f.isInsufficient() ? 1 : f.points().size()).sum();
            ctx.artifacts().dataset("forecasts", model.forecastsDataset(), META, rows, now);
            ctx.log().info("forecast series", "model", modelId, "asOf", asOf.toString(), "series", forecasts.size(),
                    "insufficient", insufficient, "horizon", model.horizon(), "method", model.method().id,
                    "modelVersion", version);
            return JobResult.ok("predict.forecast: " + forecasts.size() + " series forecast " + model.horizon() + " "
                    + model.grain().id + "(s) ahead as of " + asOf + ", " + insufficient + " insufficient -> dataset '"
                    + model.forecastsDataset() + "'", (System.nanoTime() - t0) / 1_000_000L);
        }
    }

    /** The forecasts Dataset's fixed shape. */
    static final ResultSetMeta META = ScoreOutputWriter.meta(ForecastEvaluator.COLUMNS);
}
