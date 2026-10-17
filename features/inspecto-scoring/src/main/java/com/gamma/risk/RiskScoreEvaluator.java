package com.gamma.risk;

import com.gamma.alert.RiskScoreOutputs;
import com.gamma.alert.ScoreOutputWriter;
import com.gamma.analytics.score.EntityCap;
import com.gamma.analytics.score.ScoreColumns;
import com.gamma.analytics.score.ScoreRunFiles;
import com.gamma.mask.EvidenceMasker;
import com.gamma.query.MeasureCompiler;
import com.gamma.query.QueryExecutor;
import com.gamma.sql.SqlSandboxPolicy;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Evaluates a {@link RiskScoreModel} over its Datasets and writes the scores Dataset.
 *
 * <p>Each indicator is its factor's {@link MeasureCompiler} Measure grouped by the key column, run through
 * {@link QueryExecutor} (the ephemeral DuckDB sandbox) over the Dataset's trusted relation — the same path
 * {@code DatasetMeasureProbe} takes for an Alert Rule. No author text reaches the statement except through
 * the compiler's validated identifiers and typed literals.
 *
 * <h3>Output</h3>
 * {@code <dataDir>/<scoresDataset>/} gains one Parquet file per run (history: one row per entity per run), and
 * {@code <dataDir>/<scoresDataset>_latest/} is swapped to this run's rows alone — the relation an Alert Rule
 * watches, so an entity whose score falls back below the threshold HEALS instead of staying breached on history.
 * Columns: {@code model, entity_type, entity_key, score, high, factors (JSON), model_version, run_id, scored_at}.
 * Written through the score output contract ({@code com.gamma.analytics.score}, ANALYTICS-CORE-1 M7, D-AN6) by
 * {@link ScoreOutputWriter}, the one writer every score kind shares.
 */
public final class RiskScoreEvaluator {

    /**
     * Default ceiling on scored entities per run: past the cap the run FAILS rather than scoring a silent subset.
     * Operator decision 2026-10-06: configurable, never disabled — {@code -D}{@value #MAX_ENTITIES_PROPERTY} sets the
     * system default (malformed or non-positive keeps this one), a model's {@code maxEntities} overrides it.
     */
    public static final int MAX_ENTITIES = EntityCap.DEFAULT;
    public static final String MAX_ENTITIES_PROPERTY = "risk.score.maxEntities";

    /** The system default cap: the property when it is a positive whole number, else {@link #MAX_ENTITIES}. */
    static int defaultMaxEntities(String raw) {
        return EntityCap.systemDefault(raw);
    }

    /** The cap a run of {@code model} uses: the model's own, else the system default. */
    public static int maxEntities(RiskScoreModel model) {
        return EntityCap.resolve(model.maxEntities(), MAX_ENTITIES_PROPERTY);
    }
    /** Evidence rows read per factor, and kept per entity. */
    static final int MAX_EVIDENCE_ROWS = 20_000;
    static final int EVIDENCE_PER_ENTITY = 3;
    public static final String LATEST_SUFFIX = RiskScoreModel.LATEST_SUFFIX;
    /**
     * The ownership marker in each scores directory, holding the model id. The evaluator writes only into a
     * directory it created (marker present and naming THIS model) and deletes only its own {@code scores-*.parquet}
     * there — so a scores name that happens to equal a real store can never be wiped.
     */
    public static final String OWNER_MARKER = RiskScoreOutputs.OWNER_MARKER;

    /** This kind's own columns, between the contract's head and tail ({@link ScoreColumns#table}). */
    static final List<ScoreColumns.Column> COLUMNS = List.of(
            ScoreColumns.Column.measure("score", "DOUBLE"),
            ScoreColumns.Column.dimension("high", "BOOLEAN"),
            ScoreColumns.Column.dimension("factors", "VARCHAR"));

    private RiskScoreEvaluator() {}

    /** The outcome of a run. */
    public record Run(List<RiskScorer.Scored> scored, boolean evidenceTruncated) {}

    /**
     * Score every entity any factor names.
     *
     * @param relationSql Dataset id → its trusted relation SQL ({@code DatasetRelation.relationSql}); throws
     *                    {@link IllegalArgumentException} for an unknown Dataset
     * @param masker      masks classified evidence values before they are stored ({@link EvidenceMasker})
     */
    public static Run evaluate(RiskScoreModel model, Function<String, String> relationSql, EvidenceMasker masker)
            throws SQLException, IOException {
        return evaluate(model, relationSql, masker, maxEntities(model), MAX_EVIDENCE_ROWS, SqlSandboxPolicy.defaultPolicy());
    }

    /** The preview's statement fence: a tighter timeout and memory cap than a Job run (S3). */
    public static final SqlSandboxPolicy PREVIEW_POLICY = SqlSandboxPolicy.withCaps("512MB", 2, 10);

    /**
     * Score ONE entity without writing anything (ASSURE-RISK-SCORE-RESIDUALS-1 S3, {@code POST /risk-scores/preview}).
     * Every factor is narrowed to {@code entityKey} ({@link RiskScoreModel#forEntity}), each indicator query may return
     * at most one row, evidence at most {@link #EVIDENCE_PER_ENTITY} rows per factor, under {@link #PREVIEW_POLICY}.
     * Classified evidence is masked exactly as a Job run masks it. An entity no factor names scores with every
     * indicator missing ({@code found = false}).
     */
    public static Preview preview(RiskScoreModel model, String entityKey, Function<String, String> relationSql,
                                  EvidenceMasker masker) throws SQLException, IOException {
        Run run = evaluate(model.forEntity(entityKey), relationSql, masker, 1, EVIDENCE_PER_ENTITY, PREVIEW_POLICY);
        for (RiskScorer.Scored s : run.scored())
            if (entityKey.equals(s.entityKey())) return new Preview(s, true);
        return new Preview(RiskScorer.score(model, entityKey, Map.of(), null), false);
    }

    /** A preview's outcome: the scored entity, and whether any factor named it. */
    public record Preview(RiskScorer.Scored scored, boolean found) {}

    /** Package-visible so a test can drive a small evidence cap without 20,000 rows. */
    static Run evaluate(RiskScoreModel model, Function<String, String> relationSql, EvidenceMasker masker,
                                int maxEntities, int maxEvidenceRows, SqlSandboxPolicy policy)
            throws SQLException, IOException {
        Map<String, Map<String, Double>> values = new TreeMap<>();              // entity → factor → value
        Map<String, Map<String, List<Map<String, Object>>>> evidence = new LinkedHashMap<>();
        boolean evidenceTruncated = false;
        for (RiskScoreModel.Factor f : model.factors()) {
            String relation = relationSql.apply(f.dataset());
            // One row past the cap: the compiled statement carries its own LIMIT, so asking for exactly the cap could
            // never come back truncated and a run past it silently scored a subset (found by the S3 preview).
            MeasureCompiler.Spec spec = f.valueSpec(maxEntities + 1);
            String valueId = spec.measures().get(0).id();
            MeasureCompiler.Compiled valueSql = MeasureCompiler.compile(spec);
            QueryExecutor.Result r = run(model, f, "indicator", new QueryExecutor.Request(
                    f.dataset(), relation, valueSql.sql(), maxEntities, 0, List.of(), List.of(), valueSql.params()), policy);
            if (r.truncated())
                throw new IllegalStateException("risk-score '" + model.id() + "' factor '" + f.id()
                        + "' names more than " + maxEntities + " entities — refusing to score a subset; raise the cap "
                        + "with the model's maxEntities (up to " + RiskScoreModel.MAX_ENTITIES_CEILING + ") or the system "
                        + "default -D" + MAX_ENTITIES_PROPERTY);
            for (Map<String, Object> row : r.rows()) {
                Object k = row.get(f.key());
                if (k == null) continue;
                Object v = row.get(valueId);
                Map<String, Double> perEntity = values.computeIfAbsent(String.valueOf(k), x -> new LinkedHashMap<>());
                if (v instanceof Number n) perEntity.put(f.id(), n.doubleValue());
            }
            if (!f.evidence().isEmpty()) {
                // One row past the cap, for the same reason as the indicator read: the compiled statement carries its
                // own LIMIT, so asking for exactly the cap could never come back truncated (BI-QUERY-TRUNCATION-1).
                MeasureCompiler.Compiled evidenceSql = MeasureCompiler.compile(f.evidenceSpec(maxEvidenceRows + 1));
                QueryExecutor.Result ev = run(model, f, "evidence", new QueryExecutor.Request(f.dataset(), relation,
                        evidenceSql.sql(), maxEvidenceRows, 0, List.of(), List.of(), evidenceSql.params()), policy);
                evidenceTruncated |= ev.truncated();
                for (Map<String, Object> row : ev.rows()) {
                    Object k = row.get(f.key());
                    if (k == null) continue;
                    List<Map<String, Object>> rows = evidence.computeIfAbsent(String.valueOf(k), x -> new LinkedHashMap<>())
                            .computeIfAbsent(f.id(), x -> new ArrayList<>());
                    if (rows.size() >= EVIDENCE_PER_ENTITY) continue;
                    Map<String, Object> shown = new LinkedHashMap<>();
                    // Masked HERE, before anything is stored: a classified value never reaches the scores Dataset.
                    for (String c : f.evidence()) shown.put(c, masker.mask(f.dataset(), c, row.get(c)));
                    rows.add(shown);
                }
            }
        }
        List<RiskScorer.Scored> scored = new ArrayList<>(values.size());
        for (Map.Entry<String, Map<String, Double>> e : values.entrySet())
            scored.add(RiskScorer.score(model, e.getKey(), e.getValue(), evidence.get(e.getKey())));
        return new Run(List.copyOf(scored), evidenceTruncated);
    }

    /**
     * Run one factor query with a GENERIC failure. A DuckDB error can quote a raw cell value (a cast error names
     * the value it could not convert), and a Job failure's message lands in the run ledger, the logs and the UI —
     * so neither the message nor the cause is carried; the error CLASS and the factor are enough to act on.
     */
    private static QueryExecutor.Result run(RiskScoreModel model, RiskScoreModel.Factor f, String what,
                                            QueryExecutor.Request req, SqlSandboxPolicy policy) {
        try {
            return QueryExecutor.run(req, policy);
        } catch (Exception e) {
            throw new IllegalStateException("risk-score '" + model.id() + "' factor '" + f.id() + "': the " + what
                    + " query over dataset '" + f.dataset() + "' failed (" + e.getClass().getSimpleName()
                    + "; details withheld because they may quote source values) - check the column types");
        }
    }

    /**
     * A short, stable content hash of the model as stored — the {@code model_version} every row carries. The
     * component envelope ({@link RiskScoreModel#ENVELOPE_KEYS}) is left out: re-sharing a model does not
     * change how it scores.
     */
    public static String version(Map<String, Object> modelContent) {
        return ScoreOutputWriter.version(modelContent, RiskScoreModel.ENVELOPE_KEYS, "risk-score model");
    }

    /** The {@code factors} column's JSON. */
    public static String factorsJson(List<RiskScorer.FactorResult> factors) {
        return ScoreOutputWriter.explanationJson(factors, "factors");
    }

    /**
     * Append this run to the history Dataset and swap the {@code _latest} Dataset to it, then apply the model's opt-in
     * retention ({@code retainDays} / {@code retainRuns}; a pruned run's factors are no longer reproducible: the
     * audit-vs-storage trade the operator chose). See {@link ScoreOutputWriter#write}.
     *
     * @return the history directory written
     */
    public static Path write(Path dataDir, RiskScoreModel model, String modelVersion, String runId,
                             Instant scoredAt, List<RiskScorer.Scored> scored) throws SQLException, IOException {
        List<ScoreOutputWriter.Row> rows = new ArrayList<>(scored.size());
        for (RiskScorer.Scored s : scored)
            rows.add(new ScoreOutputWriter.Row(s.entityKey(), List.of(s.score(), s.high(), factorsJson(s.factors()))));
        return ScoreOutputWriter.write(dataDir, RiskScoreOutputs.SCORE_KIND, model.id(), model.entityType(), COLUMNS, rows,
                modelVersion, runId, scoredAt, new ScoreRunFiles.Retention(model.retainDays(), model.retainRuns()));
    }

    /** Whether {@code dir} is a scores directory THIS model created (its marker names the model). */
    public static boolean ownedBy(Path dir, String modelId) {
        return RiskScoreOutputs.ownedBy(dir, modelId);
    }
}
