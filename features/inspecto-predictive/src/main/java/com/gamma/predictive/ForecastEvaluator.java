package com.gamma.predictive;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.alert.ScoreOutputDirs;
import com.gamma.alert.ScoreOutputWriter;
import com.gamma.analytics.score.ScoreColumns;
import com.gamma.analytics.score.ScoreRunFiles;
import com.gamma.query.MeasureCompiler;
import com.gamma.query.QueryExecutor;
import com.gamma.sql.SqlSandboxPolicy;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.gamma.util.SqlIdent.q;

/**
 * One {@code predict.forecast} run: read every series' bucketed Measure over the model's history ending at the last
 * complete bucket on or before {@code as_of} (one bound statement through {@link MeasureCompiler}'s window and grain,
 * folded to one row per series), forecast them ({@link Forecaster}) and write {@code forecasts_<model>} +
 * {@code _latest} through the score output contract ({@link ScoreOutputWriter}): one row per series per future
 * bucket, or one {@value ForecastModel#INSUFFICIENT} row for a series too short to forecast.
 */
public final class ForecastEvaluator {
    private ForecastEvaluator() {}

    public static final String MAX_SERIES_PROPERTY = "predict.forecast.maxSeries";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The cap a run of {@code model} uses: the model's own, else the system default. Past it the run fails. */
    public static int maxSeries(ForecastModel model) {
        if (model.maxSeries() != null) return model.maxSeries();
        String raw = System.getProperty(MAX_SERIES_PROPERTY);
        try {
            int v = raw == null ? 0 : Integer.parseInt(raw.trim());
            return v > 0 ? Math.min(v, ForecastModel.MAX_SERIES_CEILING) : ForecastModel.DEFAULT_MAX_SERIES;
        } catch (NumberFormatException e) {
            return ForecastModel.DEFAULT_MAX_SERIES;
        }
    }

    /** The first day of the last complete bucket on or before {@code asOf}: the day itself, or the week's Monday. */
    static LocalDate lastBucket(ForecastModel model, LocalDate asOf) {
        if (model.grain() == ForecastModel.Grain.DAY) return asOf;
        LocalDate sunday = asOf.minusDays(asOf.getDayOfWeek().getValue() % 7);
        return sunday.with(DayOfWeek.MONDAY);
    }

    /** Forecast every series with a row in the history ending at {@code asOf}'s last complete bucket. */
    public static List<Forecaster.Forecast> evaluate(ForecastModel model, LocalDate asOf, String relationSql) {
        return Forecaster.forecast(model, read(model, asOf, relationSql, maxSeries(model),
                SqlSandboxPolicy.defaultPolicy()));
    }

    static List<Forecaster.Observed> read(ForecastModel model, LocalDate asOf, String relationSql, int maxSeries,
                                          SqlSandboxPolicy policy) {
        LocalDate last = lastBucket(model, asOf);
        int days = model.grain().days();
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("field", model.time());
        window.put("asOf", last.plusDays(days - 1).toString());
        window.put("days", model.history() * days);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("dataset", model.dataset());
        b.put("window", window);
        b.put("measures", List.of(model.field() == null ? Map.<String, Object>of("agg", model.agg())
                : Map.<String, Object>of("agg", model.agg(), "field", model.field())));
        b.put("groupBy", List.of(model.key(), model.time()));
        b.put("grains", Map.of(model.time(), model.grain().id));
        // At most history buckets per series: a series past the cap still shows in the folded rows.
        int limit = Math.multiplyExact(maxSeries + 1, model.history());
        MeasureCompiler.Spec spec = MeasureCompiler.parse(b, limit, limit);
        QueryExecutor.Result result;
        try {
            MeasureCompiler.Compiled inner = MeasureCompiler.compile(spec);
            String sql = "WITH b AS (" + inner.sql() + ") SELECT CAST(" + q(model.key()) + " AS VARCHAR) AS entity_key, "
                    // a sum over only NULL values is NULL: that bucket is 0, like an empty one
                    + "STRING_AGG(" + q(model.time()) + " || '=' || CAST(COALESCE(CAST(" + q(spec.measures().get(0).id())
                    + " AS DOUBLE), 0) AS VARCHAR), ';' ORDER BY " + q(model.time()) + ") AS points FROM b WHERE "
                    + q(model.key()) + " IS NOT NULL GROUP BY 1 ORDER BY 1";
            result = QueryExecutor.run(new QueryExecutor.Request(model.dataset(), relationSql, sql, maxSeries, 0,
                    List.of(), List.of(), inner.params()), policy, java.time.ZoneId.of("UTC"));
        } catch (Exception e) {
            throw new IllegalStateException("forecast-model '" + model.id() + "': the series query over dataset '"
                    + model.dataset() + "' failed (" + e.getClass().getSimpleName()
                    + "; details withheld because they may quote source values) - check the column types");
        }
        if (result.truncated())
            throw new IllegalStateException("forecast-model '" + model.id() + "' names more than " + maxSeries
                    + " series - refusing to forecast a subset; raise the cap with the model's maxSeries (up to "
                    + ForecastModel.MAX_SERIES_CEILING + ") or the system default -D" + MAX_SERIES_PROPERTY);
        List<Forecaster.Observed> out = new ArrayList<>(result.rows().size());
        for (Map<String, Object> row : result.rows()) {
            Map<LocalDate, Double> byBucket = new HashMap<>();
            LocalDate first = null;
            for (String point : String.valueOf(row.get("points")).split(";")) {
                int eq = point.indexOf('=');
                LocalDate bucket = LocalDate.parse(point.substring(0, eq));
                byBucket.put(bucket, Double.parseDouble(point.substring(eq + 1)));
                if (first == null || bucket.isBefore(first)) first = bucket;
            }
            int n = Math.toIntExact(ChronoUnit.DAYS.between(first, last) / days + 1);
            double[] values = new double[n];
            for (int i = 0; i < n; i++) values[i] = byBucket.getOrDefault(first.plusDays((long) i * days), 0.0);
            out.add(new Forecaster.Observed(String.valueOf(row.get("entity_key")), first, values));
        }
        return out;
    }

    /** A 12-hex hash of the stored model minus its envelope — the {@code model_version} on every row. */
    public static String version(Map<String, Object> modelContent) {
        return ScoreOutputWriter.version(modelContent, ForecastModel.ENVELOPE_KEYS, "forecast-model");
    }

    /** Whether {@code dir} is an output directory THIS model created. */
    public static boolean ownedBy(Path dir, String modelId) {
        return ScoreOutputDirs.ownedBy(dir, ForecastModel.OWNER_MARKER, modelId);
    }

    /** This kind's own columns, between the contract's head and tail ({@link ScoreColumns#table}). */
    static final List<ScoreColumns.Column> COLUMNS = List.of(
            ScoreColumns.Column.temporal("as_of", "TIMESTAMP"),
            ScoreColumns.Column.dimension("status", "VARCHAR"),
            ScoreColumns.Column.dimension("step", "INTEGER"),
            ScoreColumns.Column.temporal("bucket", "TIMESTAMP"),
            ScoreColumns.Column.measure("forecast", "DOUBLE"),
            ScoreColumns.Column.measure("lower", "DOUBLE"),
            ScoreColumns.Column.measure("upper", "DOUBLE"),
            ScoreColumns.Column.measure("backtest_mae", "DOUBLE"),
            ScoreColumns.Column.measure("backtest_smape", "DOUBLE"),
            ScoreColumns.Column.measure("backtest_rel_mae", "DOUBLE"),
            ScoreColumns.Column.measure("history_buckets", "INTEGER"),
            ScoreColumns.Column.dimension("explanation", "VARCHAR"));

    /**
     * Append this run to the history store and swap {@code _latest} to it, into directories this model owns (marker
     * {@value ForecastModel#OWNER_MARKER}); see {@link ScoreOutputWriter#write}. No retention.
     */
    public static Path write(Path dataDir, ForecastModel model, String modelVersion, String runId, Instant at,
                             LocalDate asOf, List<Forecaster.Forecast> forecasts) throws SQLException, IOException {
        List<ScoreOutputWriter.Row> rows = new ArrayList<>();
        for (Forecaster.Forecast f : forecasts) {
            if (f.isInsufficient()) {
                rows.add(new ScoreOutputWriter.Row(f.seriesKey(), Arrays.asList(asOf.atStartOfDay(),
                        ForecastModel.INSUFFICIENT, null, null, null, null, null, null, null, null, f.historyBuckets(),
                        json(f.insufficient()))));
                continue;
            }
            Forecaster.Backtest bt = f.backtest();
            for (Forecaster.Point p : f.points())
                rows.add(new ScoreOutputWriter.Row(f.seriesKey(), Arrays.asList(asOf.atStartOfDay(), "ok", p.step(),
                        p.bucket().atStartOfDay(), p.forecast(), p.lower(), p.upper(), bt.mae(), bt.smape(),
                        bt.relMae(), f.historyBuckets(), json(p.explanation()))));
        }
        return ScoreOutputWriter.write(dataDir, ForecastModel.SCORE_KIND, model.id(), model.entityType(), COLUMNS,
                rows, modelVersion, runId, at, ScoreRunFiles.Retention.OFF);
    }

    private static String json(Map<String, Object> m) {
        try {
            return JSON.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise a forecast explanation", e);
        }
    }
}
