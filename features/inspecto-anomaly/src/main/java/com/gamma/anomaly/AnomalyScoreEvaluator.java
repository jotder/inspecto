package com.gamma.anomaly;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.gamma.alert.ScoreOutputDirs;
import com.gamma.anomaly.baseline.Baseline;
import com.gamma.anomaly.baseline.BaselineStatistic;
import com.gamma.anomaly.baseline.BaselineStatistics;
import com.gamma.anomaly.baseline.PeerBaseline;
import com.gamma.anomaly.baseline.RobustStats;
import com.gamma.query.MeasureCompiler;
import com.gamma.query.QueryExecutor;
import com.gamma.sql.SqlSandboxPolicy;
import com.gamma.util.DuckDbUtil;

import java.io.IOException;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Evaluates an {@link AnomalyModel} over its Datasets and writes {@code anomaly_scores_<id>} (history) and
 * {@code anomaly_scores_<id>_latest} (this run only, the relation an Alert Rule watches).
 *
 * <p>Per feature ONE grouped DuckDB statement: the feature's {@link MeasureCompiler} Measure grouped by key and day
 * over {@code [asOf − window − 1 day, asOf)}, folded to one row per entity; from the entity's first bucket in that
 * range an empty count/sum day is 0 (D-AD8), an empty avg/min/max day stays absent. The JVM then splits off the scored day (the last one) and asks the
 * {@link BaselineStatistic} for the baseline of the rest — so the scored period is never in its own baseline.
 * Days are UTC.
 *
 * <p>Caps fail closed (D-AD10): more entities than the cap, or more bucket rows than {@link #MAX_BUCKET_ROWS}, fail
 * the run naming the cap; a query failure names the model, feature, Dataset and error class only.
 */
public final class AnomalyScoreEvaluator {

    public static final int MAX_ENTITIES = 200_000;
    public static final String MAX_ENTITIES_PROPERTY = "anomaly.score.maxEntities";
    public static final int MAX_BUCKET_ROWS = 50_000_000;

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private AnomalyScoreEvaluator() {}

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
    public static int maxEntities(AnomalyModel model) {
        return model.maxEntities() != null ? model.maxEntities()
                : defaultMaxEntities(System.getProperty(MAX_ENTITIES_PROPERTY));
    }

    /** The outcome of a run: the scored day and one score per entity. */
    public record Run(LocalDate period, List<AnomalyScorer.Scored> scored, int excluded) {
        public Run(LocalDate period, List<AnomalyScorer.Scored> scored) { this(period, scored, 0); }
    }

    /** Score every entity any feature names, for the day before {@code asOf} (a day boundary, UTC). */
    public static Run evaluate(AnomalyModel model, LocalDate asOf, Function<String, String> relationSql)
            throws SQLException, IOException {
        return evaluate(model, asOf, relationSql, k -> false);
    }

    /**
     * As {@link #evaluate(AnomalyModel, LocalDate, Function)}, dropping every entity {@code excluded} accepts (the
     * model's exclusion Entity List, design §9) BEFORE the entity cap, the baselines and the peer-cohort medians: an
     * excluded entity never shapes its cohort's baseline. The count is {@link Run#excluded()}.
     */
    public static Run evaluate(AnomalyModel model, LocalDate asOf, Function<String, String> relationSql,
                               Predicate<String> excluded) throws SQLException, IOException {
        return evaluate(model, asOf, relationSql, maxEntities(model), MAX_BUCKET_ROWS, SqlSandboxPolicy.defaultPolicy(), excluded);
    }

    static Run evaluate(AnomalyModel model, LocalDate asOf, Function<String, String> relationSql, int maxEntities,
                        int maxBucketRows, SqlSandboxPolicy policy) throws SQLException, IOException {
        return evaluate(model, asOf, relationSql, maxEntities, maxBucketRows, policy, k -> false);
    }

    static Run evaluate(AnomalyModel model, LocalDate asOf, Function<String, String> relationSql, int maxEntities,
                        int maxBucketRows, SqlSandboxPolicy policy, Predicate<String> excluded)
            throws SQLException, IOException {
        java.util.Set<String> dropped = new java.util.HashSet<>();
        LocalDate period = asOf.minusDays(1);
        LocalDate from = period.minusDays(model.window());
        BaselineStatistic statistic = BaselineStatistics.forModel(model.seasonality(), model.minBaselinePoints());
        Map<String, Map<String, Map<LocalDateTime, Double>>> series = new TreeMap<>();   // entity → feature → day → value
        for (AnomalyModel.Feature f : model.features()) {
            QueryExecutor.Result r = run(model, f, relationSql.apply(f.dataset()), from, asOf, maxEntities, maxBucketRows, policy);
            if (r.truncated())
                throw new IllegalStateException(capMessage(model, f, maxEntities));
            for (Map<String, Object> row : r.rows()) {
                if (row.get("bucket_rows") instanceof Number n && n.longValue() > maxBucketRows)
                    throw new IllegalStateException("anomaly-model '" + model.id() + "' feature '" + f.id()
                            + "' reads more than " + maxBucketRows + " bucket rows - refusing to score a subset");
                Map<LocalDateTime, Double> days = new TreeMap<>();
                String pts = (String) row.get("pts");
                if (pts != null && !pts.isEmpty())
                    for (String p : pts.split(",")) {
                        int eq = p.indexOf('=');
                        days.put(LocalDate.parse(p.substring(0, eq)).atStartOfDay(), Double.parseDouble(p.substring(eq + 1)));
                    }
                String entityKey = String.valueOf(row.get("entity_key"));
                if (excluded.test(entityKey)) { dropped.add(entityKey); continue; }
                series.computeIfAbsent(entityKey, k -> new LinkedHashMap<>()).put(f.id(), days);
            }
            if (series.size() > maxEntities)
                throw new IllegalStateException(capMessage(model, f, maxEntities));
        }
        LocalDateTime scoredDay = period.atStartOfDay();
        // Pass 1: each entity's zero-filled history and scored-day observation per feature.
        Map<String, Map<String, Map<LocalDateTime, Double>>> histories = new TreeMap<>();
        Map<String, Map<String, Double>> observedByFeature = new LinkedHashMap<>();   // feature → entity → observed
        for (Map.Entry<String, Map<String, Map<LocalDateTime, Double>>> e : series.entrySet()) {
            // The entity's history starts at its FIRST bucket in the window (any feature): an entity first seen two days
            // ago has two days of history, not 26 zeros before them. From there an empty count/sum day is 0 (D-AD8).
            LocalDateTime first = e.getValue().values().stream().flatMap(m -> m.keySet().stream())
                    .min(LocalDateTime::compareTo).orElse(scoredDay);
            Map<String, Map<LocalDateTime, Double>> perFeature = new LinkedHashMap<>();
            for (AnomalyModel.Feature f : model.features()) {
                Map<LocalDateTime, Double> history = new TreeMap<>(e.getValue().getOrDefault(f.id(), Map.of()));
                if (f.zeroFilled())
                    for (LocalDateTime d = first; !d.isAfter(scoredDay); d = d.plusDays(1)) history.putIfAbsent(d, 0.0);
                Double observed = history.remove(scoredDay);
                perFeature.put(f.id(), history);
                observedByFeature.computeIfAbsent(f.id(), k -> new LinkedHashMap<>()).put(e.getKey(), observed);
            }
            histories.put(e.getKey(), perFeature);
        }
        PeerSide peers = model.peers() == null ? null
                : new PeerSide(model, cohorts(model, relationSql, policy), histories, observedByFeature, statistic, scoredDay);
        // Pass 2: baselines and the score.
        List<AnomalyScorer.Scored> scored = new ArrayList<>(series.size());
        for (Map.Entry<String, Map<String, Map<LocalDateTime, Double>>> e : histories.entrySet()) {
            Map<String, AnomalyScorer.Input> inputs = new LinkedHashMap<>();
            for (AnomalyModel.Feature f : model.features()) {
                Double observed = observedByFeature.get(f.id()).get(e.getKey());
                Baseline self = statistic.compute(e.getValue().get(f.id()), scoredDay);
                if (peers == null) {
                    inputs.put(f.id(), new AnomalyScorer.Input(observed, self));
                } else {
                    PeerSide.Cohort c = peers.of(f, e.getKey());
                    inputs.put(f.id(), new AnomalyScorer.Input(observed, self, c.baseline(), c.shift()));
                }
            }
            scored.add(AnomalyScorer.score(model, e.getKey(), period.toString(), inputs));
        }
        return new Run(period, List.copyOf(scored), dropped.size());
    }

    /**
     * The peer side of a run (design §4.3): per feature and cohort, the {@link PeerBaseline} of the scored-day
     * observations, and the <b>cohort shift</b> {@code k = peer median today / the cohort's usual daily median} (the
     * model's own {@link BaselineStatistic} over the cohort's daily medians). Each member's self baseline is scaled by
     * {@code k}, so a day on which the whole cohort moves (a promotion) does not read as every member's own anomaly,
     * while a member that moves alone still does. {@code k = 1} when the cohort is insufficient or its usual is not > 0.
     */
    private static final class PeerSide {
        record Cohort(Baseline baseline, double shift) {}

        private final PeerBaseline peer;
        private final Map<String, String> cohortOf;
        private final Map<String, Map<String, Map<LocalDateTime, Double>>> histories;
        private final Map<String, Map<String, Double>> observedByFeature;
        private final BaselineStatistic statistic;
        private final LocalDateTime scoredDay;
        private final Map<String, Cohort> cache = new LinkedHashMap<>();

        PeerSide(AnomalyModel model, Map<String, String> cohortOf, Map<String, Map<String, Map<LocalDateTime, Double>>> histories,
              Map<String, Map<String, Double>> observedByFeature, BaselineStatistic statistic, LocalDateTime scoredDay) {
            this.peer = new PeerBaseline(model.peers().minGroupSize(), model.peers().populationFallback());
            this.cohortOf = cohortOf;
            this.histories = histories;
            this.observedByFeature = observedByFeature;
            this.statistic = statistic;
            this.scoredDay = scoredDay;
        }

        Cohort of(AnomalyModel.Feature f, String entity) {
            String cohort = cohortOf.get(entity);
            return cache.computeIfAbsent(f.id() + "\u0000" + cohort, k -> compute(f, cohort));
        }

        private Cohort compute(AnomalyModel.Feature f, String cohort) {
            Baseline b = peer.compute(observedByFeature.get(f.id()), cohortOf, cohort);
            if (b.insufficient()) return new Cohort(b, 1.0);
            boolean population = PeerBaseline.POPULATION.equals(b.basis());
            Map<LocalDateTime, List<Double>> byDay = new TreeMap<>();
            histories.forEach((entity, perFeature) -> {
                if (!population && !cohort.equals(cohortOf.get(entity))) return;
                perFeature.get(f.id()).forEach((d, v) -> {
                    if (v != null && !v.isNaN()) byDay.computeIfAbsent(d, x -> new ArrayList<>()).add(v);
                });
            });
            Map<LocalDateTime, Double> medians = new TreeMap<>();
            byDay.forEach((d, vs) -> medians.put(d, RobustStats.median(vs)));
            Baseline usual = statistic.compute(medians, scoredDay);
            double k = usual.insufficient() || !(usual.median() > 0) ? 1.0 : b.median() / usual.median();
            return new Cohort(b, k);
        }
    }

    /**
     * Entity → cohort key: ONE grouped statement over the peers Dataset, {@code max(concat_ws('|', by...))} per join
     * key (a key with several cohort values gets the greatest, deterministically). A key with no cohort value is in
     * the population only. Fails closed past {@link AnomalyModel#MAX_ENTITIES_CEILING} keys.
     */
    private static Map<String, String> cohorts(AnomalyModel model, Function<String, String> relationSql,
                                               SqlSandboxPolicy policy) {
        AnomalyModel.Peers p = model.peers();
        StringBuilder by = new StringBuilder();
        for (String c : p.by()) by.append(by.isEmpty() ? "" : ", ").append("CAST(").append(q(c)).append(" AS VARCHAR)");
        String sql = "SELECT CAST(" + q(p.key()) + " AS VARCHAR) AS entity_key, max(concat_ws('|', " + by + ")) AS cohort "
                + "FROM " + q(p.dataset()) + " WHERE " + q(p.key()) + " IS NOT NULL GROUP BY 1";
        QueryExecutor.Result r;
        try {
            r = QueryExecutor.run(new QueryExecutor.Request(p.dataset(), relationSql.apply(p.dataset()), sql,
                    AnomalyModel.MAX_ENTITIES_CEILING, 0, List.of(), List.of()), policy, java.time.ZoneId.of("UTC"));
        } catch (Exception e) {
            throw new IllegalStateException("anomaly-model '" + model.id() + "' peers: the cohort query over dataset '"
                    + p.dataset() + "' failed (" + e.getClass().getSimpleName()
                    + "; details withheld because they may quote source values) - check the column types");
        }
        if (r.truncated())
            throw new IllegalStateException("anomaly-model '" + model.id() + "' peers: dataset '" + p.dataset()
                    + "' has more than " + AnomalyModel.MAX_ENTITIES_CEILING + " keys - refusing to score a subset");
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, Object> row : r.rows())
            if (row.get("cohort") != null && !String.valueOf(row.get("cohort")).isEmpty())
                out.put(String.valueOf(row.get("entity_key")), String.valueOf(row.get("cohort")));
        return out;
    }

    private static String capMessage(AnomalyModel model, AnomalyModel.Feature f, int maxEntities) {
        return "anomaly-model '" + model.id() + "' feature '" + f.id() + "' names more than " + maxEntities
                + " entities - refusing to score a subset; raise the cap with the model's maxEntities (up to "
                + AnomalyModel.MAX_ENTITIES_CEILING + ") or the system default -D" + MAX_ENTITIES_PROPERTY;
    }

    /** The per-feature statement: bucket, fold to one row per entity. Only validated identifiers reach the text. */
    static String sql(AnomalyModel.Feature f, MeasureCompiler.Compiled inner, String valueId) {
        String key = q(f.key()), time = q(f.time()), v = q(valueId);
        return "WITH b AS (" + inner.sql() + "), n AS (SELECT count(*) AS c FROM b) "
                + "SELECT CAST(" + key + " AS VARCHAR) AS entity_key, "
                + "string_agg(" + time + " || '=' || CAST(CAST(" + v + " AS DOUBLE) AS VARCHAR), ',' ORDER BY " + time + ") AS pts, "
                + "(SELECT c FROM n) AS bucket_rows FROM b WHERE " + key + " IS NOT NULL AND " + v + " IS NOT NULL "
                + "GROUP BY 1 ORDER BY 1";
    }

    private static QueryExecutor.Result run(AnomalyModel model, AnomalyModel.Feature f, String relation, LocalDate from,
                                            LocalDate to, int maxEntities, int maxBucketRows, SqlSandboxPolicy policy) {
        try {
            MeasureCompiler.Spec spec = f.bucketSpec(from + " 00:00:00", to + " 00:00:00", maxBucketRows + 1);
            MeasureCompiler.Compiled inner = MeasureCompiler.compile(spec);
            String valueId = spec.measures().get(0).id();
            return QueryExecutor.run(new QueryExecutor.Request(f.dataset(), relation, sql(f, inner, valueId),
                    maxEntities, 0, List.of(), List.of(), inner.params()), policy, java.time.ZoneId.of("UTC"));
        } catch (Exception e) {
            throw new IllegalStateException("anomaly-model '" + model.id() + "' feature '" + f.id() + "': the bucket "
                    + "query over dataset '" + f.dataset() + "' failed (" + e.getClass().getSimpleName()
                    + "; details withheld because they may quote source values) - check the column types");
        }
    }

    /** A 12-hex hash of the stored model minus its envelope — the {@code model_version} on every row. */
    public static String version(Map<String, Object> modelContent) {
        try {
            Map<String, Object> scored = new TreeMap<>(modelContent);
            scored.keySet().removeAll(AnomalyModel.ENVELOPE_KEYS);
            byte[] canonical = JSON.writeValueAsBytes(scored);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical)).substring(0, 12);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("cannot hash anomaly-model", e);
        }
    }

    public static String featuresJson(List<AnomalyScorer.FeatureResult> features) {
        try {
            List<Map<String, Object>> out = new ArrayList<>(features.size());
            for (AnomalyScorer.FeatureResult f : features) out.add(f.toMap());
            return new ObjectMapper().writeValueAsString(out);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise features", e);
        }
    }

    /** Whether {@code dir} is an output directory THIS model created. */
    public static boolean ownedBy(Path dir, String modelId) {
        return ScoreOutputDirs.ownedBy(dir, AnomalyModel.OWNER_MARKER, modelId);
    }

    /**
     * Append this run to the history store and swap {@code _latest} to it, both via {@code *.tmp} + atomic move,
     * into directories this model owns (marker {@value AnomalyModel#OWNER_MARKER}).
     */
    public static Path write(Path dataDir, AnomalyModel model, String modelVersion, String runId, Instant scoredAt,
                             Run run) throws SQLException, IOException {
        Path history = dataDir.resolve(model.scoresDataset()).normalize();
        Path latest = dataDir.resolve(model.latestDataset()).normalize();
        if (!history.startsWith(dataDir.normalize()) || !latest.startsWith(dataDir.normalize()))
            throw new IllegalArgumentException("scores Dataset escapes the data root");
        ScoreOutputDirs.claim(history, AnomalyModel.OWNER_MARKER, AnomalyModel.KIND, model.id());
        ScoreOutputDirs.claim(latest, AnomalyModel.OWNER_MARKER, AnomalyModel.KIND, model.id());
        String stamp = scoredAt.toEpochMilli() + "-" + runId.replaceAll("[^A-Za-z0-9_-]", "_");
        String name = "scores-" + stamp + ".parquet";
        Path histTmp = history.resolve(name + ".tmp");
        Path latestTmp = latest.resolve(name + ".tmp");
        try (Connection c = DuckDbUtil.openInMemory(DuckDbUtil.spillDirUnder(dataDir), List.of(history, latest));
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE s (model VARCHAR, entity_type VARCHAR, entity_key VARCHAR, period_start TIMESTAMP, "
                    + "score DOUBLE, band VARCHAR, raw DOUBLE, features VARCHAR, insufficient_count INTEGER, "
                    + "model_version VARCHAR, run_id VARCHAR, scored_at TIMESTAMP)");
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO s VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                Timestamp ts = Timestamp.from(scoredAt);
                Timestamp period = Timestamp.valueOf(run.period().atStartOfDay());
                for (AnomalyScorer.Scored s : run.scored()) {
                    ps.setString(1, model.id());
                    ps.setString(2, model.entityType());
                    ps.setString(3, s.entityKey());
                    ps.setTimestamp(4, period);
                    ps.setDouble(5, s.score());
                    ps.setString(6, s.band());
                    ps.setDouble(7, s.raw());
                    ps.setString(8, featuresJson(s.features()));
                    ps.setInt(9, s.insufficientCount());
                    ps.setString(10, modelVersion);
                    ps.setString(11, runId);
                    ps.setTimestamp(12, ts);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            st.execute("COPY s TO " + sqlStr(histTmp) + " (FORMAT PARQUET)");
            st.execute("COPY s TO " + sqlStr(latestTmp) + " (FORMAT PARQUET)");
        }
        Files.move(histTmp, history.resolve(name), StandardCopyOption.ATOMIC_MOVE);
        ScoreOutputDirs.swapIn(latest, latestTmp, name, "scores-*.parquet");
        return history;
    }

    /** Entity counts per band, for the run log and the Signal. */
    static Map<String, Long> bands(List<AnomalyScorer.Scored> scored) {
        Map<String, Long> out = new TreeMap<>();
        for (String b : new TreeSet<>(List.of("normal", "elevated", "high"))) out.put(b, 0L);
        for (AnomalyScorer.Scored s : scored) out.merge(s.band(), 1L, Long::sum);
        return out;
    }

    private static String q(String ident) { return "\"" + ident.replace("\"", "\"\"") + "\""; }

    private static String sqlStr(Path p) {
        return "'" + p.toString().replace('\\', '/').replace("'", "''") + "'";
    }
}
