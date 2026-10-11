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
import com.gamma.mask.EvidenceMasker;
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
import java.util.HashMap;
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
    /** Evidence rows read per feature (design section 11), and kept per entity (design section 5). */
    static final int MAX_EVIDENCE_ROWS = 20_000;
    static final int EVIDENCE_PER_ENTITY = 3;

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    /** Excludes nothing; the one predicate for which no per-feature key read is made. */
    public static final Predicate<String> NONE = k -> false;

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
    public record Run(LocalDate period, List<AnomalyScorer.Scored> scored, int excluded, boolean evidenceTruncated,
                      Map<String, Map<String, Object>> series) {
        public Run(LocalDate period, List<AnomalyScorer.Scored> scored, int excluded, boolean evidenceTruncated) {
            this(period, scored, excluded, evidenceTruncated, Map.of());
        }
        public Run(LocalDate period, List<AnomalyScorer.Scored> scored, int excluded) { this(period, scored, excluded, false); }
        public Run(LocalDate period, List<AnomalyScorer.Scored> scored) { this(period, scored, 0, false); }
    }

    /** Half-width of the preview's band, in spreads: {@code median ± BAND_SPREADS × max(scaled MAD, floor)}. */
    static final double BAND_SPREADS = 3.0;

    /** Score every entity any feature names, for the day before {@code asOf} (a day boundary, UTC). */
    public static Run evaluate(AnomalyModel model, LocalDate asOf, Function<String, String> relationSql)
            throws SQLException, IOException {
        return evaluate(model, asOf, relationSql, NONE);
    }

    /**
     * As {@link #evaluate(AnomalyModel, LocalDate, Function)}, dropping every entity {@code excluded} accepts (the
     * model's exclusion Entity List, design §9) BEFORE the entity cap, the baselines and the peer-cohort medians: an
     * excluded entity never shapes its cohort's baseline. The count is {@link Run#excluded()}.
     */
    public static Run evaluate(AnomalyModel model, LocalDate asOf, Function<String, String> relationSql,
                               Predicate<String> excluded) throws SQLException, IOException {
        return evaluate(model, asOf, relationSql, excluded, null);
    }

    /**
     * As above, also reading the origin rows of each entity's top feature when the model's features declare
     * {@code evidence} columns, masked by {@code masker} BEFORE they are stored (fail closed: a model with evidence
     * columns and no masker is refused, never stored raw).
     */
    public static Run evaluate(AnomalyModel model, LocalDate asOf, Function<String, String> relationSql,
                               Predicate<String> excluded, EvidenceMasker masker) throws SQLException, IOException {
        return evaluate(model, asOf, relationSql, maxEntities(model), MAX_BUCKET_ROWS, SqlSandboxPolicy.defaultPolicy(),
                excluded, null, masker, null, MAX_EVIDENCE_ROWS);
    }

    static Run evaluate(AnomalyModel model, LocalDate asOf, Function<String, String> relationSql, int maxEntities,
                        int maxBucketRows, SqlSandboxPolicy policy) throws SQLException, IOException {
        return evaluate(model, asOf, relationSql, maxEntities, maxBucketRows, policy, NONE);
    }

    static Run evaluate(AnomalyModel model, LocalDate asOf, Function<String, String> relationSql, int maxEntities,
                        int maxBucketRows, SqlSandboxPolicy policy, Predicate<String> excluded)
            throws SQLException, IOException {
        return evaluate(model, asOf, relationSql, maxEntities, maxBucketRows, policy, excluded, null, null, null,
                MAX_EVIDENCE_ROWS);
    }

    /**
     * {@code POST /anomaly-scores/preview}: score ONE entity under {@code policy} and write nothing. Without peers every
     * feature is narrowed to the entity (a bound filter, cap 1). With peers the cohort medians and the cohort shift
     * need the whole cohort, so the features are read in full (the model's own entity cap) and only {@code entityKey}
     * is scored; a statement past the policy's caps fails the preview (fail closed, never a narrowed cohort).
     */
    static Run preview(AnomalyModel model, LocalDate asOf, Function<String, String> relationSql, String entityKey,
                       SqlSandboxPolicy policy) throws SQLException, IOException {
        return preview(model, asOf, relationSql, entityKey, policy, null);
    }

    /** As above; classified evidence is masked by {@code masker} exactly as a Job run masks it. */
    static Run preview(AnomalyModel model, LocalDate asOf, Function<String, String> relationSql, String entityKey,
                       SqlSandboxPolicy policy, EvidenceMasker masker) throws SQLException, IOException {
        if (model.peers() == null)
            return evaluate(model.forEntity(entityKey), asOf, relationSql, 1, MAX_BUCKET_ROWS, policy, NONE, null, masker,
                    entityKey, EVIDENCE_PER_ENTITY);
        return evaluate(model, asOf, relationSql, maxEntities(model), MAX_BUCKET_ROWS, policy, NONE, entityKey::equals,
                masker, entityKey, EVIDENCE_PER_ENTITY);
    }

    /** Package-visible so a test can drive a small evidence cap without 20,000 rows. */
    static Run evaluate(AnomalyModel model, LocalDate asOf, Function<String, String> relationSql, EvidenceMasker masker,
                        int maxEvidenceRows) throws SQLException, IOException {
        return evaluate(model, asOf, relationSql, maxEntities(model), MAX_BUCKET_ROWS, SqlSandboxPolicy.defaultPolicy(),
                NONE, null, masker, null, maxEvidenceRows);
    }

    /** As above; {@code scoreOnly} (null = everyone) limits which entities are SCORED, not which shape the baselines. */
    private static Run evaluate(AnomalyModel model, LocalDate asOf, Function<String, String> relationSql, int maxEntities,
                                int maxBucketRows, SqlSandboxPolicy policy, Predicate<String> excluded,
                                Predicate<String> scoreOnly, EvidenceMasker masker, String previewEntity,
                                int maxEvidenceRows) throws SQLException, IOException {
        boolean wantsEvidence = model.features().stream().anyMatch(f -> !f.evidence().isEmpty());
        if (wantsEvidence && masker == null)
            throw new IllegalStateException("anomaly-model '" + model.id() + "' declares evidence columns but no masker "
                    + "was supplied - refusing to store evidence unmasked");
        java.util.Set<String> dropped = new java.util.HashSet<>();
        LocalDate period = asOf.minusDays(1);
        LocalDate from = period.minusDays(model.window());
        BaselineStatistic statistic = BaselineStatistics.forModel(model.seasonality(), model.minBaselinePoints());
        Map<String, Map<String, Map<LocalDateTime, Double>>> series = new TreeMap<>();   // entity → feature → day → value
        for (AnomalyModel.Feature f : model.features()) {
            String relation = relationSql.apply(f.dataset());
            List<String> excludedKeys = excludedKeys(model, f, relation, from, asOf, maxBucketRows, policy, excluded);
            dropped.addAll(excludedKeys);
            QueryExecutor.Result r = run(model, f, relation, from, asOf, maxEntities, maxBucketRows, policy, excludedKeys);
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
                : new PeerSide(new PeerBaseline(model.peers().minGroupSize(), model.peers().populationFallback()),
                        cohorts(model, relationSql, policy), histories, observedByFeature, statistic, scoredDay);
        // Pass 2: baselines and the score.
        List<AnomalyScorer.Scored> scored = new ArrayList<>(series.size());
        Map<String, Map<String, Object>> daily = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Map<LocalDateTime, Double>>> e : histories.entrySet()) {
            if (scoreOnly != null && !scoreOnly.test(e.getKey())) continue;
            Map<String, AnomalyScorer.Input> inputs = new LinkedHashMap<>();
            for (AnomalyModel.Feature f : model.features()) {
                Double observed = observedByFeature.get(f.id()).get(e.getKey());
                Baseline self = statistic.compute(e.getValue().get(f.id()), scoredDay);
                if (previewEntity != null && previewEntity.equals(e.getKey()))
                    daily.put(f.id(), dailySeries(e.getValue().get(f.id()), scoredDay, observed, self, f.unit()));
                if (peers == null) {
                    inputs.put(f.id(), new AnomalyScorer.Input(observed, self));
                } else {
                    PeerSide.Cohort c = peers.of(f, e.getKey());
                    inputs.put(f.id(), new AnomalyScorer.Input(observed, self, c.baseline(), c.shift()));
                }
            }
            scored.add(AnomalyScorer.score(model, e.getKey(), period.toString(), inputs));
        }
        boolean evidenceTruncated = wantsEvidence
                && attachEvidence(model, period, asOf, relationSql, policy, masker, previewEntity, maxEvidenceRows, scored);
        return new Run(period, List.copyOf(scored), dropped.size(), evidenceTruncated, daily);
    }

    /**
     * The preview's per-day series of ONE entity and feature: the zero-filled history, then the scored day, and the
     * self baseline's {@code median} with a band {@code median ± }{@link #BAND_SPREADS}{@code × max(scaled MAD, floor)}.
     * Numbers only - never a key or evidence value. The baseline is one level for the scored day's slot: with weekday
     * seasonality it is the median of the same weekday, so the band is a reference line, not a per-day forecast.
     * {@code median} / {@code low} / {@code high} are null when the baseline is insufficient.
     */
    private static Map<String, Object> dailySeries(Map<LocalDateTime, Double> history, LocalDateTime scoredDay,
                                                   Double observed, Baseline self, double unit) {
        List<Map<String, Object>> points = new ArrayList<>();
        for (Map.Entry<LocalDateTime, Double> d : new TreeMap<>(history).entrySet())
            points.add(point(d.getKey(), d.getValue()));
        points.add(point(scoredDay, observed));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("points", points);
        boolean usable = !self.insufficient() && Double.isFinite(self.median());
        double spread = usable ? Math.max(Double.isFinite(self.scaledMad()) ? self.scaledMad() : 0,
                RobustStats.floor(self.median(), unit)) : 0;
        out.put("median", usable ? self.median() : null);
        out.put("low", usable ? self.median() - BAND_SPREADS * spread : null);
        out.put("high", usable ? self.median() + BAND_SPREADS * spread : null);
        out.put("bandSpreads", BAND_SPREADS);
        return out;
    }

    private static Map<String, Object> point(LocalDateTime day, Double value) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("day", day.toLocalDate().toString());
        p.put("value", value != null && Double.isFinite(value) ? value : null);
        return p;
    }

    /**
     * Design section 5: for each entity's TOP feature (largest contribution, not insufficient) that declares
     * {@code evidence} columns, up to {@link #EVIDENCE_PER_ENTITY} origin rows of the scored day. ONE statement per such
     * feature, read to {@code maxEvidenceRows} (one past it, so truncation is detectable); entities past the cap carry
     * no evidence and their scores are unaffected. Every value is masked HERE, before anything is stored. Returns
     * whether a read was truncated.
     */
    private static boolean attachEvidence(AnomalyModel model, LocalDate period, LocalDate asOf,
                                          Function<String, String> relationSql, SqlSandboxPolicy policy,
                                          EvidenceMasker masker, String previewEntity, int maxEvidenceRows,
                                          List<AnomalyScorer.Scored> scored) {
        Map<String, java.util.Set<String>> wanted = new LinkedHashMap<>();   // feature id -> entities it is the top of
        for (AnomalyScorer.Scored s : scored) {
            if (s.features().isEmpty()) continue;
            AnomalyScorer.FeatureResult top = s.features().get(0);
            if (top.insufficient() || !(top.contribution() > 0)) continue;
            wanted.computeIfAbsent(top.feature(), x -> new java.util.HashSet<>()).add(s.entityKey());
        }
        Map<String, Map<String, List<Map<String, Object>>>> rowsOf = new HashMap<>();   // feature -> entity -> rows
        boolean truncated = false;
        for (AnomalyModel.Feature f0 : model.features()) {
            java.util.Set<String> entities = wanted.get(f0.id());
            if (f0.evidence().isEmpty() || entities == null) continue;
            AnomalyModel.Feature f = previewEntity != null ? f0.forEntity(previewEntity) : f0;
            QueryExecutor.Result ev;
            try {
                MeasureCompiler.Compiled sql = MeasureCompiler.compile(
                        f.evidenceSpec(period + " 00:00:00", asOf + " 00:00:00", maxEvidenceRows + 1));
                ev = QueryExecutor.run(new QueryExecutor.Request(f.dataset(), relationSql.apply(f.dataset()), sql.sql(),
                        maxEvidenceRows, 0, List.of(), List.of(), sql.params()), policy, java.time.ZoneId.of("UTC"));
            } catch (Exception e) {
                throw new IllegalStateException("anomaly-model '" + model.id() + "' feature '" + f.id() + "': the evidence "
                        + "query over dataset '" + f.dataset() + "' failed (" + e.getClass().getSimpleName()
                        + "; details withheld because they may quote source values) - check the column types");
            }
            truncated |= ev.truncated();
            Map<String, List<Map<String, Object>>> mine = rowsOf.computeIfAbsent(f.id(), x -> new HashMap<>());
            for (Map<String, Object> row : ev.rows()) {
                Object k = row.get(f.key());
                if (k == null || !entities.contains(String.valueOf(k))) continue;
                List<Map<String, Object>> rows = mine.computeIfAbsent(String.valueOf(k), x -> new ArrayList<>());
                if (rows.size() >= EVIDENCE_PER_ENTITY) continue;
                Map<String, Object> shown = new LinkedHashMap<>();
                for (String c : f.evidence()) shown.put(c, masker.mask(f.dataset(), c, row.get(c)));
                rows.add(shown);
            }
        }
        for (int i = 0; i < scored.size(); i++) {
            AnomalyScorer.Scored s = scored.get(i);
            if (s.features().isEmpty()) continue;
            AnomalyScorer.FeatureResult top = s.features().get(0);
            List<Map<String, Object>> rows = rowsOf.getOrDefault(top.feature(), Map.of()).get(s.entityKey());
            if (rows == null || rows.isEmpty()) continue;
            List<AnomalyScorer.FeatureResult> replaced = new ArrayList<>(s.features());
            replaced.set(0, top.withEvidence(rows));
            scored.set(i, s.withFeatures(replaced));
        }
        return truncated;
    }

    /**
     * The peer side of a run (design §4.3): per feature and cohort, the {@link PeerBaseline} of the scored-day
     * observations, and the <b>cohort shift</b> {@code k = peer median today / the cohort's usual daily median} (the
     * model's own {@link BaselineStatistic} over the cohort's daily medians). Each member's self baseline is scaled by
     * {@code k}, so a day on which the whole cohort moves (a promotion) does not read as every member's own anomaly,
     * while a member that moves alone still does. {@code k = 1} when the cohort is insufficient or its usual is not > 0.
     */
    static final class PeerSide {
        record Cohort(Baseline baseline, double shift) {}

        /** One Feature's grouping, built in a single pass over the population: O(entities x days), not per cohort. */
        private static final class Grouped {
            final Map<String, List<Double>> membersByCohort = new HashMap<>();      // usable observations per cohort
            final Map<String, Map<LocalDateTime, List<Double>>> daysByCohort = new HashMap<>();
            final List<Double> population = new ArrayList<>();                      // every usable observation
            final Map<LocalDateTime, List<Double>> populationDays = new TreeMap<>();
            PeerBaseline.Population populationBaseline;                              // lazy, once per Feature
            Baseline populationUsual;                                                // lazy, once per Feature
        }

        private final PeerBaseline peer;
        private final Map<String, String> cohortOf;
        private final Map<String, String> labelOf;
        private final Map<String, Map<String, Map<LocalDateTime, Double>>> histories;
        private final Map<String, Map<String, Double>> observedByFeature;
        private final BaselineStatistic statistic;
        private final LocalDateTime scoredDay;
        private final Map<String, Grouped> grouped = new HashMap<>();
        private final Map<String, Cohort> cache = new LinkedHashMap<>();

        PeerSide(PeerBaseline peer, Cohorts cohorts, Map<String, Map<String, Map<LocalDateTime, Double>>> histories,
              Map<String, Map<String, Double>> observedByFeature, BaselineStatistic statistic, LocalDateTime scoredDay) {
            this.peer = peer;
            this.cohortOf = cohorts.keyOf();
            this.labelOf = cohorts.labelOf();
            this.histories = histories;
            this.observedByFeature = observedByFeature;
            this.statistic = statistic;
            this.scoredDay = scoredDay;
        }

        Cohort of(AnomalyModel.Feature f, String entity) {
            String cohort = cohortOf.get(entity);
            return cache.computeIfAbsent(f.id() + "\0" + cohort, k -> compute(f, cohort));
        }

        private static boolean usable(Double v) {
            return v != null && !v.isNaN();
        }

        private Grouped group(String feature) {
            return grouped.computeIfAbsent(feature, fid -> {
                Grouped g = new Grouped();
                observedByFeature.get(fid).forEach((e, v) -> {
                    if (!usable(v)) return;
                    g.population.add(v);
                    String c = cohortOf.get(e);
                    if (c != null) g.membersByCohort.computeIfAbsent(c, x -> new ArrayList<>()).add(v);
                });
                histories.forEach((entity, perFeature) -> {
                    String c = cohortOf.get(entity);
                    Map<LocalDateTime, List<Double>> mine = c == null ? null
                            : g.daysByCohort.computeIfAbsent(c, x -> new TreeMap<>());
                    perFeature.get(fid).forEach((d, v) -> {
                        if (!usable(v)) return;
                        g.populationDays.computeIfAbsent(d, x -> new ArrayList<>()).add(v);
                        if (mine != null) mine.computeIfAbsent(d, x -> new ArrayList<>()).add(v);
                    });
                });
                return g;
            });
        }

        private Cohort compute(AnomalyModel.Feature f, String cohort) {
            Grouped g = group(f.id());
            List<Double> members = cohort == null ? List.of() : g.membersByCohort.getOrDefault(cohort, List.of());
            Baseline b = peer.compute(members, () -> {
                if (g.populationBaseline == null) g.populationBaseline = PeerBaseline.Population.of(g.population);
                return g.populationBaseline;
            }, labelOf.get(cohort));
            if (b.insufficient()) return new Cohort(b, 1.0);
            boolean population = b.fellBack();   // not basis == "population": a cohort label may read "population"
            Baseline usual;
            if (population && g.populationUsual != null) usual = g.populationUsual;   // the same for every fallback
            else {
                Map<LocalDateTime, List<Double>> byDay = population ? g.populationDays : g.daysByCohort.get(cohort);
                Map<LocalDateTime, Double> medians = new TreeMap<>();
                byDay.forEach((d, vs) -> medians.put(d, RobustStats.median(vs)));
                usual = statistic.compute(medians, scoredDay);
                if (population) g.populationUsual = usual;
            }
            double k = usual.insufficient() || !(usual.median() > 0) ? 1.0 : b.median() / usual.median();
            return new Cohort(b, k);
        }
    }

    /** The display form of a NULL {@code by} value in a cohort label (the SPA's null marker, recon grain table). */
    private static final String NULL_COHORT_VALUE = "∅";

    /**
     * Entity → cohort: {@code keyOf} for equality, {@code labelOf} (cohort key → label) for display only. The key is
     * collision-free whatever the values hold; the label is the values joined with {@code '|'}, NULL shown as
     * {@link #NULL_COHORT_VALUE}, and may coincide for two distinct keys (a value containing {@code '|'}).
     */
    record Cohorts(Map<String, String> keyOf, Map<String, String> labelOf) {}

    /**
     * Entity → cohort: ONE grouped statement over the peers Dataset. Each {@code by} position contributes a token —
     * {@code 'n'} for NULL, else {@code 'v' || length || ':' || value} — so NULL is a cohort value of its own in every
     * position and the concatenated key is injective (no separator a value could contain). A join key with several
     * cohort values gets the greatest key, deterministically. A key with no row in the Dataset is in the population
     * only. Fails closed past {@link AnomalyModel#MAX_ENTITIES_CEILING} keys.
     */
    private static Cohorts cohorts(AnomalyModel model, Function<String, String> relationSql,
                                   SqlSandboxPolicy policy) {
        AnomalyModel.Peers p = model.peers();
        StringBuilder key = new StringBuilder(), label = new StringBuilder();
        for (String c : p.by()) {
            String v = "CAST(" + q(c) + " AS VARCHAR)";
            key.append(key.isEmpty() ? "" : " || ").append("CASE WHEN ").append(v).append(" IS NULL THEN 'n' ELSE 'v' || ")
                    .append("CAST(length(").append(v).append(") AS VARCHAR) || ':' || ").append(v).append(" END");
            label.append(label.isEmpty() ? "" : " || '|' || ").append("coalesce(").append(v).append(", chr(")
                    .append(NULL_COHORT_VALUE.codePointAt(0)).append("))");
        }
        String sql = "SELECT CAST(" + q(p.key()) + " AS VARCHAR) AS entity_key, max(" + key + ") AS cohort, "
                + "arg_max(" + label + ", " + key + ") AS label "
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
        Map<String, String> keyOf = new LinkedHashMap<>(), labelOf = new LinkedHashMap<>();
        for (Map<String, Object> row : r.rows()) {
            String cohort = String.valueOf(row.get("cohort"));
            keyOf.put(String.valueOf(row.get("entity_key")), cohort);
            labelOf.put(cohort, String.valueOf(row.get("label")));
        }
        return new Cohorts(keyOf, labelOf);
    }

    private static String capMessage(AnomalyModel model, AnomalyModel.Feature f, int maxEntities) {
        return "anomaly-model '" + model.id() + "' feature '" + f.id() + "' names more than " + maxEntities
                + " entities - refusing to score a subset; raise the cap with the model's maxEntities (up to "
                + AnomalyModel.MAX_ENTITIES_CEILING + ") or the system default -D" + MAX_ENTITIES_PROPERTY;
    }

    /** The per-feature statement: bucket, fold to one row per entity. Only validated identifiers reach the text. */
    static String sql(AnomalyModel.Feature f, MeasureCompiler.Compiled inner, String valueId) {
        return sql(f, inner, valueId, 0);
    }

    /**
     * As above, dropping {@code excluded} keys (one bound {@code ?} each, after the inner params) BEFORE the grouping
     * and so before the entity LIMIT (design §16.4): a source over the cap only by its excluded keys is scored.
     */
    static String sql(AnomalyModel.Feature f, MeasureCompiler.Compiled inner, String valueId, int excluded) {
        String key = q(f.key()), time = q(f.time()), v = q(valueId);
        String b = excluded == 0 ? "b AS (" + inner.sql() + ")"
                : "b0 AS (" + inner.sql() + "), b AS (SELECT * FROM b0 WHERE CAST(" + key + " AS VARCHAR) NOT IN ("
                        + String.join(", ", java.util.Collections.nCopies(excluded, "?")) + "))";
        return "WITH " + b + ", n AS (SELECT count(*) AS c FROM b) "
                + "SELECT CAST(" + key + " AS VARCHAR) AS entity_key, "
                + "string_agg(" + time + " || '=' || CAST(CAST(" + v + " AS DOUBLE) AS VARCHAR), ',' ORDER BY " + time + ") AS pts, "
                + "(SELECT c FROM n) AS bucket_rows FROM b WHERE " + key + " IS NOT NULL AND " + v + " IS NOT NULL "
                + "GROUP BY 1 ORDER BY 1";
    }

    /**
     * The feature's keys in range that {@code excluded} accepts. An exclusion Entity List's ranges and normaliser live
     * in the predicate, so the distinct candidate keys are read first and tested in the JVM; the hits then reach the
     * bucket statement as bound parameters. {@link #NONE} makes no read. Bounded by
     * {@link AnomalyModel#MAX_ENTITIES_CEILING}, failing closed past it.
     */
    private static List<String> excludedKeys(AnomalyModel model, AnomalyModel.Feature f, String relation, LocalDate from,
                                             LocalDate to, int maxBucketRows, SqlSandboxPolicy policy,
                                             Predicate<String> excluded) {
        if (excluded == null || excluded == NONE) return List.of();
        QueryExecutor.Result r;
        try {
            MeasureCompiler.Spec spec = f.bucketSpec(from + " 00:00:00", to + " 00:00:00", maxBucketRows + 1);
            MeasureCompiler.Compiled inner = MeasureCompiler.compile(spec);
            String key = q(f.key());
            r = QueryExecutor.run(new QueryExecutor.Request(f.dataset(), relation, "WITH b AS (" + inner.sql()
                    + ") SELECT DISTINCT CAST(" + key + " AS VARCHAR) AS entity_key FROM b WHERE " + key + " IS NOT NULL",
                    AnomalyModel.MAX_ENTITIES_CEILING, 0, List.of(), List.of(), inner.params()), policy, java.time.ZoneId.of("UTC"));
        } catch (Exception e) {
            throw new IllegalStateException("anomaly-model '" + model.id() + "' feature '" + f.id() + "': the key "
                    + "query over dataset '" + f.dataset() + "' failed (" + e.getClass().getSimpleName()
                    + "; details withheld because they may quote source values) - check the column types");
        }
        if (r.truncated())
            throw new IllegalStateException("anomaly-model '" + model.id() + "' feature '" + f.id() + "' names more than "
                    + AnomalyModel.MAX_ENTITIES_CEILING + " keys - refusing to score a subset");
        List<String> out = new ArrayList<>();
        for (Map<String, Object> row : r.rows()) {
            String k = String.valueOf(row.get("entity_key"));
            if (excluded.test(k)) out.add(k);
        }
        return out;
    }

    private static QueryExecutor.Result run(AnomalyModel model, AnomalyModel.Feature f, String relation, LocalDate from,
                                            LocalDate to, int maxEntities, int maxBucketRows, SqlSandboxPolicy policy,
                                            List<String> excludedKeys) {
        try {
            MeasureCompiler.Spec spec = f.bucketSpec(from + " 00:00:00", to + " 00:00:00", maxBucketRows + 1);
            MeasureCompiler.Compiled inner = MeasureCompiler.compile(spec);
            String valueId = spec.measures().get(0).id();
            List<Object> binds = new ArrayList<>(inner.params());
            binds.addAll(excludedKeys);
            return QueryExecutor.run(new QueryExecutor.Request(f.dataset(), relation, sql(f, inner, valueId, excludedKeys.size()),
                    maxEntities, 0, List.of(), List.of(), binds), policy, java.time.ZoneId.of("UTC"));
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
