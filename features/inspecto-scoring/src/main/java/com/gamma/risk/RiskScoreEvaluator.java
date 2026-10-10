package com.gamma.risk;

import com.gamma.mask.EvidenceMasker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.gamma.query.MeasureCompiler;
import com.gamma.query.QueryExecutor;
import com.gamma.sql.SqlSandboxPolicy;
import com.gamma.util.DuckDbUtil;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
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
 */
public final class RiskScoreEvaluator {

    /**
     * Default ceiling on scored entities per run: past the cap the run FAILS rather than scoring a silent subset.
     * Operator decision 2026-10-06: configurable, never disabled — {@code -D}{@value #MAX_ENTITIES_PROPERTY} sets the
     * system default (malformed or non-positive keeps this one), a model's {@code maxEntities} overrides it.
     */
    public static final int MAX_ENTITIES = 200_000;
    public static final String MAX_ENTITIES_PROPERTY = "risk.score.maxEntities";

    /** The system default cap: the property when it is a positive whole number, else {@link #MAX_ENTITIES}. */
    static int defaultMaxEntities(String raw) {
        if (raw == null) return MAX_ENTITIES;
        try {
            int v = Integer.parseInt(raw.trim());
            return v > 0 ? v : MAX_ENTITIES;
        } catch (NumberFormatException e) {
            return MAX_ENTITIES;
        }
    }

    /** The cap a run of {@code model} uses: the model's own, else the system default. */
    public static int maxEntities(RiskScoreModel model) {
        return model.maxEntities() != null ? model.maxEntities()
                : defaultMaxEntities(System.getProperty(MAX_ENTITIES_PROPERTY));
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
    public static final String OWNER_MARKER = com.gamma.alert.RiskScoreOutputs.OWNER_MARKER;

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

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
        try {
            Map<String, Object> scored = new TreeMap<>(modelContent);
            scored.keySet().removeAll(RiskScoreModel.ENVELOPE_KEYS);
            byte[] canonical = JSON.writeValueAsBytes(scored);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical)).substring(0, 12);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("cannot hash risk-score model", e);
        }
    }

    /** The {@code factors} column's JSON. */
    public static String factorsJson(List<RiskScorer.FactorResult> factors) {
        try {
            List<Map<String, Object>> out = new ArrayList<>(factors.size());
            for (RiskScorer.FactorResult f : factors) out.add(f.toMap());
            return new ObjectMapper().writeValueAsString(out);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise factors", e);
        }
    }

    /**
     * Append this run to the history Dataset and swap the {@code _latest} Dataset to it. Both files land as
     * {@code *.tmp} and are revealed by an atomic move, so a reader never sees a half-written file.
     *
     * @return the history directory written
     */
    public static Path write(Path dataDir, RiskScoreModel model, String modelVersion, String runId,
                             Instant scoredAt, List<RiskScorer.Scored> scored) throws SQLException, IOException {
        Path history = dataDir.resolve(model.scoresDataset()).normalize();
        Path latest = dataDir.resolve(model.latestDataset()).normalize();
        if (!history.startsWith(dataDir.normalize()) || !latest.startsWith(dataDir.normalize()))
            throw new IllegalArgumentException("scores Dataset escapes the data root");
        claim(history, model.id());
        claim(latest, model.id());
        String stamp = scoredAt.toEpochMilli() + "-" + runId.replaceAll("[^A-Za-z0-9_-]", "_");
        Path histTmp = history.resolve("scores-" + stamp + ".parquet.tmp");
        Path latestTmp = latest.resolve("scores-" + stamp + ".parquet.tmp");
        try (Connection c = DuckDbUtil.openInMemory(DuckDbUtil.spillDirUnder(dataDir), java.util.List.of(history, latest));
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE s (model VARCHAR, entity_type VARCHAR, entity_key VARCHAR, score DOUBLE, "
                    + "high BOOLEAN, factors VARCHAR, model_version VARCHAR, run_id VARCHAR, scored_at TIMESTAMP)");
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO s VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                Timestamp ts = Timestamp.from(scoredAt);
                for (RiskScorer.Scored s : scored) {
                    ps.setString(1, model.id());
                    ps.setString(2, model.entityType());
                    ps.setString(3, s.entityKey());
                    ps.setDouble(4, s.score());
                    ps.setBoolean(5, s.high());
                    ps.setString(6, factorsJson(s.factors()));
                    ps.setString(7, modelVersion);
                    ps.setString(8, runId);
                    ps.setTimestamp(9, ts);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            st.execute("COPY s TO " + sqlStr(histTmp) + " (FORMAT PARQUET)");
            st.execute("COPY s TO " + sqlStr(latestTmp) + " (FORMAT PARQUET)");
        }
        Files.move(histTmp, history.resolve("scores-" + stamp + ".parquet"), StandardCopyOption.ATOMIC_MOVE);
        swapIn(latest, latestTmp, "scores-" + stamp + ".parquet");
        prune(history, model, scoredAt, "scores-" + stamp + ".parquet");
        return history;
    }

    /**
     * Opt-in retention: delete whole older history run files ({@code retainDays} by file stamp, or all but the newest
     * {@code retainRuns}). Never touches {@code _latest} or the run just written. A no-op unless the model opts in.
     * ⚠ A pruned run's factors are no longer reproducible: that is the audit-vs-storage trade the operator chose.
     */
    static void prune(Path history, RiskScoreModel model, Instant now, String keep) throws IOException {
        if (model.retainDays() == null && model.retainRuns() == null) return;
        java.util.List<Path> runs = new java.util.ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(history, "scores-*.parquet")) {
            for (Path p : ds) runs.add(p);
        }
        runs.sort(java.util.Comparator.comparingLong(RiskScoreEvaluator::stampOf));
        long cutoff = model.retainDays() == null ? Long.MIN_VALUE
                : now.minus(java.time.Duration.ofDays(model.retainDays())).toEpochMilli();
        int excess = model.retainRuns() == null ? 0 : Math.max(0, runs.size() - model.retainRuns());
        for (int i = 0; i < runs.size(); i++) {
            Path p = runs.get(i);
            if (p.getFileName().toString().equals(keep)) continue;
            if (model.retainDays() != null ? stampOf(p) < cutoff : i < excess) Files.deleteIfExists(p);
        }
    }

    private static long stampOf(Path p) {
        String n = p.getFileName().toString().substring("scores-".length());
        try { return Long.parseLong(n.substring(0, n.indexOf('-'))); } catch (RuntimeException e) { return Long.MAX_VALUE; }
    }

    /** Whether {@code dir} is a scores directory THIS model created (its marker names the model). */
    public static boolean ownedBy(Path dir, String modelId) {
        return com.gamma.alert.RiskScoreOutputs.ownedBy(dir, modelId);
    }

    /**
     * Create {@code dir} with this model's marker, or accept it when the marker already names this model.
     * Anything else — a directory with no marker, or another model's — is refused, untouched.
     */
    static void claim(Path dir, String modelId) throws IOException {
        com.gamma.alert.ScoreOutputDirs.claim(dir, OWNER_MARKER, "risk-score", modelId);
    }

    private static void swapIn(Path dir, Path tmp, String name) throws IOException {
        com.gamma.alert.ScoreOutputDirs.swapIn(dir, tmp, name, "scores-*.parquet");
    }

    private static String sqlStr(Path p) {
        return "'" + p.toString().replace('\\', '/').replace("'", "''") + "'";
    }
}
