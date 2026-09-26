package com.gamma.risk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.gamma.query.MeasureCompiler;
import com.gamma.query.QueryExecutor;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
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

    /** Hard ceiling on scored entities per run: past it the run FAILS rather than scoring a silent subset. */
    public static final int MAX_ENTITIES = 200_000;
    /** Evidence rows read per factor, and kept per entity. */
    static final int MAX_EVIDENCE_ROWS = 20_000;
    static final int EVIDENCE_PER_ENTITY = 3;
    public static final String LATEST_SUFFIX = RiskScoreModel.LATEST_SUFFIX;
    /**
     * The ownership marker in each scores directory, holding the model id. The evaluator writes only into a
     * directory it created (marker present and naming THIS model) and deletes only its own {@code scores-*.parquet}
     * there — so a scores name that happens to equal a real store can never be wiped.
     */
    public static final String OWNER_MARKER = ".risk-score-output";

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
     */
    public static Run evaluate(RiskScoreModel model, Function<String, String> relationSql)
            throws SQLException, IOException {
        Map<String, Map<String, Double>> values = new TreeMap<>();              // entity → factor → value
        Map<String, Map<String, List<Map<String, Object>>>> evidence = new LinkedHashMap<>();
        boolean evidenceTruncated = false;
        for (RiskScoreModel.Factor f : model.factors()) {
            String relation = relationSql.apply(f.dataset());
            MeasureCompiler.Spec spec = f.valueSpec(MAX_ENTITIES);
            String valueId = spec.measures().get(0).id();
            QueryExecutor.Result r = QueryExecutor.run(new QueryExecutor.Request(
                    f.dataset(), relation, MeasureCompiler.compile(spec), MAX_ENTITIES, 0, List.of(), List.of()));
            if (r.truncated())
                throw new IllegalStateException("risk-score '" + model.id() + "' factor '" + f.id()
                        + "' names more than " + MAX_ENTITIES + " entities — refusing to score a subset");
            for (Map<String, Object> row : r.rows()) {
                Object k = row.get(f.key());
                if (k == null) continue;
                Object v = row.get(valueId);
                Map<String, Double> perEntity = values.computeIfAbsent(String.valueOf(k), x -> new LinkedHashMap<>());
                if (v instanceof Number n) perEntity.put(f.id(), n.doubleValue());
            }
            if (!f.evidence().isEmpty()) {
                QueryExecutor.Result ev = QueryExecutor.run(new QueryExecutor.Request(f.dataset(), relation,
                        MeasureCompiler.compile(f.evidenceSpec(MAX_EVIDENCE_ROWS)), MAX_EVIDENCE_ROWS, 0,
                        List.of(), List.of()));
                evidenceTruncated |= ev.truncated();
                for (Map<String, Object> row : ev.rows()) {
                    Object k = row.get(f.key());
                    if (k == null) continue;
                    List<Map<String, Object>> rows = evidence.computeIfAbsent(String.valueOf(k), x -> new LinkedHashMap<>())
                            .computeIfAbsent(f.id(), x -> new ArrayList<>());
                    if (rows.size() >= EVIDENCE_PER_ENTITY) continue;
                    Map<String, Object> shown = new LinkedHashMap<>();
                    for (String c : f.evidence()) shown.put(c, row.get(c));
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
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
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
        return history;
    }

    /** Whether {@code dir} is a scores directory THIS model created (its marker names the model). */
    public static boolean ownedBy(Path dir, String modelId) {
        Path marker = dir.resolve(OWNER_MARKER);
        try {
            return Files.isRegularFile(marker) && modelId.equals(Files.readString(marker).trim());
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Create {@code dir} with this model's marker, or accept it when the marker already names this model.
     * Anything else — a directory with no marker, or another model's — is refused, untouched.
     */
    static void claim(Path dir, String modelId) throws IOException {
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(OWNER_MARKER), modelId);
            return;
        }
        if (!ownedBy(dir, modelId))
            throw new IllegalStateException("risk-score '" + modelId + "' refuses to write into '" + dir.getFileName()
                    + "': the directory exists and was not created by this model");
    }

    private static void swapIn(Path dir, Path tmp, String name) throws IOException {
        List<Path> stale = new ArrayList<>();
        try (DirectoryStream<Path> old = Files.newDirectoryStream(dir, "scores-*.parquet")) {
            for (Path p : old) {
                Path hidden = p.resolveSibling(p.getFileName() + ".stale");
                Files.move(p, hidden, StandardCopyOption.ATOMIC_MOVE);
                stale.add(hidden);
            }
        }
        Files.move(tmp, dir.resolve(name), StandardCopyOption.ATOMIC_MOVE);
        for (Path p : stale) Files.deleteIfExists(p);
    }

    private static String sqlStr(Path p) {
        return "'" + p.toString().replace('\\', '/').replace("'", "''") + "'";
    }
}
