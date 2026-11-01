package com.gamma.predictive;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.alert.ScoreOutputDirs;
import com.gamma.alert.ScoreOutputWriter;
import com.gamma.analytics.score.EntityCap;
import com.gamma.analytics.score.ScoreColumns;
import com.gamma.analytics.score.ScoreRunFiles;
import com.gamma.query.MeasureCompiler;
import com.gamma.query.QueryExecutor;
import com.gamma.sql.SqlSandboxPolicy;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.gamma.util.SqlIdent.q;

/**
 * One {@code predict.segment} run: read each entity's windowed RFM Measures as of a date (one bound statement through
 * {@link MeasureCompiler}'s window, ANALYTICS-CORE-1 C1), segment them ({@link RfmSegmenter}) and write
 * {@code customer_segments_<model>} + {@code _latest} through the score output contract ({@link ScoreOutputWriter}).
 */
public final class SegmentEvaluator {
    private SegmentEvaluator() {}

    public static final String MAX_ENTITIES_PROPERTY = "predict.segment.maxEntities";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The cap a run of {@code model} uses: the model's own, else the system default. Past it the run fails. */
    public static int maxEntities(SegmentationModel model) {
        return EntityCap.resolve(model.maxEntities(), MAX_ENTITIES_PROPERTY);
    }

    /** Assign every entity with a row in the model's window ending at {@code asOf} (inclusive). */
    public static List<RfmSegmenter.Assignment> evaluate(SegmentationModel model, LocalDate asOf, String relationSql)
            throws SQLException, IOException {
        return evaluate(model, asOf, relationSql, maxEntities(model), SqlSandboxPolicy.defaultPolicy());
    }

    static List<RfmSegmenter.Assignment> evaluate(SegmentationModel model, LocalDate asOf, String relationSql,
                                                  int maxEntities, SqlSandboxPolicy policy) throws SQLException, IOException {
        return RfmSegmenter.assign(model, read(model, asOf, relationSql, maxEntities, policy));
    }

    /** The windowed spec: recency, frequency and monetary per key, ordered by key, as of {@code asOf}. */
    static MeasureCompiler.Spec spec(SegmentationModel model, LocalDate asOf, int limit) {
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("field", model.time());
        window.put("asOf", asOf.toString());
        if (model.window() != null) window.put("days", model.window());
        List<Map<String, Object>> measures = new ArrayList<>();
        measures.add(Map.of("agg", "recency", "field", model.time()));
        measures.add(body(model.frequencyMeasure()));
        measures.add(body(model.monetaryMeasure()));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("dataset", model.dataset());
        b.put("window", window);
        b.put("measures", measures);
        b.put("groupBy", List.of(model.key()));
        b.put("orderBy", List.of(Map.of("field", model.key())));
        return MeasureCompiler.parse(b, limit, limit);
    }

    private static Map<String, Object> body(MeasureCompiler.Measure m) {
        return m.field() == null ? Map.of("agg", m.agg()) : Map.of("agg", m.agg(), "field", m.field());
    }

    static List<RfmSegmenter.Rfm> read(SegmentationModel model, LocalDate asOf, String relationSql, int maxEntities,
                                       SqlSandboxPolicy policy) {
        QueryExecutor.Result r;
        MeasureCompiler.Spec spec = spec(model, asOf, maxEntities + 1);
        String rId = spec.measures().get(0).id(), fId = spec.measures().get(1).id(), mId = spec.measures().get(2).id();
        try {
            MeasureCompiler.Compiled inner = MeasureCompiler.compile(spec);
            String sql = "WITH b AS (" + inner.sql() + ") SELECT CAST(" + q(model.key()) + " AS VARCHAR) AS entity_key, "
                    + "CAST(" + q(rId) + " AS BIGINT) AS r, CAST(" + q(fId) + " AS DOUBLE) AS f, "
                    + "CAST(" + q(mId) + " AS DOUBLE) AS m FROM b WHERE " + q(model.key()) + " IS NOT NULL ORDER BY 1";
            r = QueryExecutor.run(new QueryExecutor.Request(model.dataset(), relationSql, sql, maxEntities, 0, List.of(),
                    List.of(), inner.params()), policy, java.time.ZoneId.of("UTC"));
        } catch (Exception e) {
            throw new IllegalStateException("segmentation-model '" + model.id() + "': the RFM query over dataset '"
                    + model.dataset() + "' failed (" + e.getClass().getSimpleName()
                    + "; details withheld because they may quote source values) - check the column types");
        }
        if (r.truncated())
            throw new IllegalStateException("segmentation-model '" + model.id() + "' names more than " + maxEntities
                    + " entities - refusing to segment a subset; raise the cap with the model's maxEntities (up to "
                    + SegmentationModel.MAX_ENTITIES_CEILING + ") or the system default -D" + MAX_ENTITIES_PROPERTY);
        List<RfmSegmenter.Rfm> out = new ArrayList<>(r.rows().size());
        for (Map<String, Object> row : r.rows())
            out.add(new RfmSegmenter.Rfm(String.valueOf(row.get("entity_key")), ((Number) row.get("r")).longValue(),
                    ((Number) row.get("f")).doubleValue(),
                    row.get("m") instanceof Number m ? m.doubleValue() : 0.0));   // SUM of only NULLs: nothing spent
        return out;
    }

    /** A 12-hex hash of the stored model minus its envelope — the {@code model_version} on every row. */
    public static String version(Map<String, Object> modelContent) {
        return ScoreOutputWriter.version(modelContent, SegmentationModel.ENVELOPE_KEYS, "segmentation-model");
    }

    /** Whether {@code dir} is an output directory THIS model created. */
    public static boolean ownedBy(Path dir, String modelId) {
        return ScoreOutputDirs.ownedBy(dir, SegmentationModel.OWNER_MARKER, modelId);
    }

    /** This kind's own columns, between the contract's head and tail ({@link ScoreColumns#table}). */
    static final List<ScoreColumns.Column> COLUMNS = List.of(
            ScoreColumns.Column.temporal("as_of", "TIMESTAMP"),
            ScoreColumns.Column.dimension("segment", "VARCHAR"),
            ScoreColumns.Column.measure("recency_score", "INTEGER"),
            ScoreColumns.Column.measure("frequency_score", "INTEGER"),
            ScoreColumns.Column.measure("monetary_score", "INTEGER"),
            ScoreColumns.Column.measure("recency_days", "INTEGER"),
            ScoreColumns.Column.measure("frequency", "DOUBLE"),
            ScoreColumns.Column.measure("monetary", "DOUBLE"),
            ScoreColumns.Column.dimension("explanation", "VARCHAR"));

    /**
     * Append this run to the history store and swap {@code _latest} to it, into directories this model owns (marker
     * {@value SegmentationModel#OWNER_MARKER}); see {@link ScoreOutputWriter#write}. No retention.
     */
    public static Path write(Path dataDir, SegmentationModel model, String modelVersion, String runId, Instant at,
                             LocalDate asOf, List<RfmSegmenter.Assignment> assigned) throws SQLException, IOException {
        List<ScoreOutputWriter.Row> rows = new ArrayList<>(assigned.size());
        for (RfmSegmenter.Assignment a : assigned)
            rows.add(new ScoreOutputWriter.Row(a.input().entityKey(), List.of(asOf.atStartOfDay(), a.segment(),
                    a.recency().score(), a.frequency().score(), a.monetary().score(),
                    Math.toIntExact(a.input().recencyDays()), a.input().frequency(), a.input().monetary(),
                    json(a.explanation()))));
        return ScoreOutputWriter.write(dataDir, SegmentationModel.SCORE_KIND, model.id(), model.entityType(), COLUMNS,
                rows, modelVersion, runId, at, ScoreRunFiles.Retention.OFF);
    }

    /** Entity counts per segment label, in label order. */
    static Map<String, Long> counts(List<RfmSegmenter.Assignment> assigned) {
        Map<String, Long> out = new TreeMap<>();
        for (RfmSegmenter.Assignment a : assigned) out.merge(a.segment(), 1L, Long::sum);
        return out;
    }

    private static String json(Map<String, Object> m) {
        try {
            return JSON.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise a segment explanation", e);
        }
    }
}
