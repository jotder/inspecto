package com.gamma.predictive;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.alert.ScoreOutputDirs;
import com.gamma.alert.ScoreOutputWriter;
import com.gamma.analytics.score.EntityCap;
import com.gamma.analytics.score.ScoreColumns;
import com.gamma.analytics.score.ScoreRunFiles;
import com.gamma.analytics.score.ScoreScale;
import com.gamma.query.MeasureCompiler;
import com.gamma.query.QueryExecutor;
import com.gamma.sql.SqlSandboxPolicy;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.gamma.util.SqlIdent.q;

/**
 * One {@code predict.churn} run: read every entity's first row up to {@code as_of} (the population and its history)
 * and its windowed input Measures (two bound statements through {@link MeasureCompiler}'s window, ANALYTICS-CORE-1
 * C1), score them ({@link ChurnScorer}) and write {@code churn_scores_<model>} + {@code _latest} through the score
 * output contract ({@link ScoreOutputWriter}).
 */
public final class ChurnEvaluator {
    private ChurnEvaluator() {}

    public static final String MAX_ENTITIES_PROPERTY = "predict.churn.maxEntities";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The cap a run of {@code model} uses: the model's own, else the system default. Past it the run fails. */
    public static int maxEntities(ChurnModel model) {
        return EntityCap.resolve(model.maxEntities(), MAX_ENTITIES_PROPERTY);
    }

    /** Score every entity with a row up to {@code asOf} (inclusive). */
    public static List<ChurnScorer.Scored> evaluate(ChurnModel model, LocalDate asOf, String relationSql) {
        return ChurnScorer.score(model, asOf, read(model, asOf, relationSql, maxEntities(model),
                SqlSandboxPolicy.defaultPolicy()));
    }

    /** The distinct Measures of the inputs, in first-use order (two inputs may share one, e.g. with two transforms). */
    static List<MeasureCompiler.Measure> measures(ChurnModel model) {
        List<MeasureCompiler.Measure> out = new ArrayList<>();
        for (ChurnModel.Input in : model.inputs()) {
            MeasureCompiler.Measure m = new MeasureCompiler.Measure(in.agg(), in.field());
            if (!out.contains(m)) out.add(m);
        }
        return out;
    }

    static MeasureCompiler.Spec spec(ChurnModel model, LocalDate asOf, Integer days, List<MeasureCompiler.Measure> ms,
                                     int limit) {
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("field", model.time());
        window.put("asOf", asOf.toString());
        if (days != null) window.put("days", days);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("dataset", model.dataset());
        b.put("window", window);
        b.put("measures", ms.stream().map(m -> m.field() == null ? Map.<String, Object>of("agg", m.agg())
                : Map.<String, Object>of("agg", m.agg(), "field", m.field())).toList());
        b.put("groupBy", List.of(model.key()));
        b.put("orderBy", List.of(Map.of("field", model.key())));
        return MeasureCompiler.parse(b, limit, limit);
    }

    static List<ChurnScorer.Observed> read(ChurnModel model, LocalDate asOf, String relationSql, int maxEntities,
                                           SqlSandboxPolicy policy) {
        // The population: every entity with a row up to as_of, and its first row's date (all history, not the window).
        MeasureCompiler.Spec first = spec(model, asOf, null, List.of(new MeasureCompiler.Measure("min", model.time())),
                maxEntities + 1);
        QueryExecutor.Result pop = run(model, relationSql, first, "CAST(CAST(" + q(first.measures().get(0).id())
                + " AS DATE) AS VARCHAR) AS first_seen", maxEntities, policy);
        if (pop.truncated())
            throw new IllegalStateException("churn-model '" + model.id() + "' names more than " + maxEntities
                    + " entities - refusing to score a subset; raise the cap with the model's maxEntities (up to "
                    + ChurnModel.MAX_ENTITIES_CEILING + ") or the system default -D" + MAX_ENTITIES_PROPERTY);
        // The inputs over the model's window: an entity with no row in it is absent here.
        List<MeasureCompiler.Measure> ms = measures(model);
        MeasureCompiler.Spec windowed = spec(model, asOf, model.window(), ms, maxEntities + 1);
        StringBuilder cols = new StringBuilder();
        for (int i = 0; i < ms.size(); i++)
            cols.append(i == 0 ? "" : ", ").append("CAST(").append(q(windowed.measures().get(i).id()))
                    .append(" AS DOUBLE) AS m").append(i);
        QueryExecutor.Result vals = run(model, relationSql, windowed, cols.toString(), maxEntities, policy);
        Map<String, Map<String, Object>> byKey = new HashMap<>();
        for (Map<String, Object> row : vals.rows()) byKey.put(String.valueOf(row.get("entity_key")), row);

        List<ChurnScorer.Observed> out = new ArrayList<>(pop.rows().size());
        for (Map<String, Object> row : pop.rows()) {
            String key = String.valueOf(row.get("entity_key"));
            Map<String, Object> v = byKey.get(key);
            List<Double> values = new ArrayList<>(model.inputs().size());
            for (ChurnModel.Input in : model.inputs()) {
                int i = ms.indexOf(new MeasureCompiler.Measure(in.agg(), in.field()));
                values.add(v != null && v.get("m" + i) instanceof Number n ? n.doubleValue() : null);
            }
            out.add(new ChurnScorer.Observed(key, LocalDate.parse(String.valueOf(row.get("first_seen"))), values));
        }
        return out;
    }

    private static QueryExecutor.Result run(ChurnModel model, String relationSql, MeasureCompiler.Spec spec,
                                            String columns, int maxEntities, SqlSandboxPolicy policy) {
        try {
            MeasureCompiler.Compiled inner = MeasureCompiler.compile(spec);
            String sql = "WITH b AS (" + inner.sql() + ") SELECT CAST(" + q(model.key()) + " AS VARCHAR) AS entity_key, "
                    + columns + " FROM b WHERE " + q(model.key()) + " IS NOT NULL ORDER BY 1";
            return QueryExecutor.run(new QueryExecutor.Request(model.dataset(), relationSql, sql, maxEntities, 0,
                    List.of(), List.of(), inner.params()), policy, java.time.ZoneId.of("UTC"));
        } catch (Exception e) {
            throw new IllegalStateException("churn-model '" + model.id() + "': the input query over dataset '"
                    + model.dataset() + "' failed (" + e.getClass().getSimpleName()
                    + "; details withheld because they may quote source values) - check the column types");
        }
    }

    /** A 12-hex hash of the stored model minus its envelope — the {@code model_version} on every row. */
    public static String version(Map<String, Object> modelContent) {
        return ScoreOutputWriter.version(modelContent, ChurnModel.ENVELOPE_KEYS, "churn-model");
    }

    /** Whether {@code dir} is an output directory THIS model created. */
    public static boolean ownedBy(Path dir, String modelId) {
        return ScoreOutputDirs.ownedBy(dir, ChurnModel.OWNER_MARKER, modelId);
    }

    /** This kind's own columns, between the contract's head and tail ({@link ScoreColumns#table}). */
    static final List<ScoreColumns.Column> COLUMNS = List.of(
            ScoreColumns.Column.temporal("as_of", "TIMESTAMP"),
            ScoreColumns.Column.measure("score", "DOUBLE"),
            ScoreColumns.Column.dimension("band", "VARCHAR"),
            ScoreColumns.Column.measure("logit", "DOUBLE"),
            ScoreColumns.Column.measure("history_days", "INTEGER"),
            ScoreColumns.Column.dimension("explanation", "VARCHAR"));

    /**
     * Append this run to the history store and swap {@code _latest} to it, into directories this model owns (marker
     * {@value ChurnModel#OWNER_MARKER}); see {@link ScoreOutputWriter#write}. No retention.
     */
    public static Path write(Path dataDir, ChurnModel model, String modelVersion, String runId, Instant at,
                             LocalDate asOf, List<ChurnScorer.Scored> scored) throws SQLException, IOException {
        List<ScoreOutputWriter.Row> rows = new ArrayList<>(scored.size());
        for (ChurnScorer.Scored s : scored)
            rows.add(new ScoreOutputWriter.Row(s.entityKey(), Arrays.asList(asOf.atStartOfDay(), s.score(), s.band(),
                    s.logit(), Math.toIntExact(s.historyDays()), json(s.explanation()))));
        return ScoreOutputWriter.write(dataDir, ChurnModel.SCORE_KIND, model.id(), model.entityType(), COLUMNS,
                rows, modelVersion, runId, at, ScoreRunFiles.Retention.OFF);
    }

    /** Entities per band ({@code elevated}, {@code high}, {@code normal}, every band present) plus {@code insufficient}. */
    static Map<String, Long> bands(List<ChurnScorer.Scored> scored) {
        Map<String, Long> out = ScoreScale.bandCounts(scored.stream().filter(s -> !s.insufficient())
                .map(ChurnScorer.Scored::band).toList());
        out.put(ChurnModel.INSUFFICIENT, scored.stream().filter(ChurnScorer.Scored::insufficient).count());
        return out;
    }

    private static String json(Map<String, Object> m) {
        try {
            return JSON.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise a churn explanation", e);
        }
    }
}
