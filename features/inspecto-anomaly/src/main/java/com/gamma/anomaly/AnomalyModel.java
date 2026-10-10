package com.gamma.anomaly;

import com.gamma.anomaly.baseline.Seasonality;
import com.gamma.anomaly.baseline.SeasonalBaseline;
import com.gamma.query.MeasureCompiler;
import com.gamma.util.Values;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * An <b>Anomaly Model</b> (ANOMALY-DETECTION-1, the Type; an Anomaly Score is its Instance): per-entity robust
 * baselines over Measure features, stored as the {@code anomaly-model} component kind at
 * {@code registry/anomaly-models/<id>.toon} and scored by the {@code anomaly.score} Job.
 *
 * <pre>
 * entityType: subscriber
 * bucket: day                    (day only so far)
 * window: 28                     (baseline window in days, 1..90; the scored day is never in it)
 * seasonality: none              (none | weekday)
 * minBaselinePoints: 7           elevatedThreshold: 60   highThreshold: 80
 * zCap: 10   scale: 3            maxEntities: 500000 (1..2000000; default -Danomaly.score.maxEntities, else 200000)
 * dataScope: fraud
 * features[n]: {id, label, dataset, key, time, measure (count | agg(field)), filters, direction (up|down|both),
 *               weight, unit (the absolute spread floor, default 1)}
 * </pre>
 *
 * Validated fail closed: every problem is an {@link IllegalArgumentException} naming the field (422 at the route).
 * Keys of later slices ({@code peers}, {@code watchList}, {@code exclusionList}, {@code scoredPeriod} other than 1,
 * {@code bucket: hour}) are refused, not ignored.
 */
public record AnomalyModel(String id, String entityType, int window, Seasonality seasonality, int minBaselinePoints,
                           double elevatedThreshold, double highThreshold, double zCap, double scale,
                           Integer maxEntities, String dataScope, String description, List<Feature> features) {

    public static final String KIND = "anomaly-model";
    public static final String SCORES_PREFIX = "anomaly_scores_";
    public static final String LATEST_SUFFIX = "_latest";
    public static final String OWNER_MARKER = ".anomaly-score-output";
    public static final int MAX_FEATURES = 16;
    public static final int MAX_WINDOW_DAYS = 90;
    public static final int MAX_ENTITIES_CEILING = 2_000_000;
    public static final Set<String> ENVELOPE_KEYS = Set.of("name", "owner", "shares");
    /** Aggregations whose empty bucket is a real 0 (D-AD8); every other one leaves it absent. */
    public static final Set<String> ZERO_FILLED_AGGS = Set.of("count", "sum", "countDistinct");

    private static final Pattern SAFE_IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");
    private static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_]*");
    private static final Set<String> MODEL_KEYS = Set.of("name", "owner", "shares", "id", "entityType", "bucket",
            "window", "scoredPeriod", "seasonality", "minBaselinePoints", "elevatedThreshold", "highThreshold", "zCap",
            "scale", "maxEntities", "dataScope", "description", "features");
    private static final Set<String> LATER_KEYS = Set.of("peers", "watchList", "exclusionList");
    private static final Set<String> FEATURE_KEYS = Set.of("id", "label", "dataset", "key", "time", "measure",
            "filters", "direction", "weight", "unit");

    public enum Direction { UP, DOWN, BOTH }

    /** One feature: a Measure over one Dataset, grouped by its key column and by the day of its {@code time} column. */
    public record Feature(String id, String label, String dataset, String key, String time, String measure,
                          List<Map<String, Object>> filters, Direction direction, double weight, double unit) {

        public MeasureCompiler.Measure compiledMeasure() {
            Map<String, Object> m = MeasureCompiler.splitShorthand(List.of(measure), null).get(0);
            return new MeasureCompiler.Measure(String.valueOf(m.get("agg")),
                    m.get("field") == null ? null : String.valueOf(m.get("field")));
        }

        /** Whether an empty bucket counts as 0 (count/sum) rather than absent (D-AD8). */
        public boolean zeroFilled() { return ZERO_FILLED_AGGS.contains(compiledMeasure().agg()); }

        public String displayLabel() { return label != null ? label : id; }

        /**
         * The bucket spec: the Measure grouped by key and day, over {@code [from, to)} of {@code time}. The range
         * bounds are bound parameters like every authored filter value.
         */
        public MeasureCompiler.Spec bucketSpec(String from, String to, int limit) {
            List<Map<String, Object>> fl = new ArrayList<>(filters);
            fl.add(Map.of("field", time, "op", ">=", "value", from));
            fl.add(Map.of("field", time, "op", "<", "value", to));
            MeasureCompiler.Measure m = compiledMeasure();
            Map<String, Object> mb = new LinkedHashMap<>();
            mb.put("agg", m.agg());
            if (m.field() != null) mb.put("field", m.field());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("dataset", dataset);
            body.put("measures", List.of(mb));
            body.put("groupBy", List.of(key, time));
            body.put("grains", Map.of(time, "day"));
            body.put("filters", fl);
            body.put("orderBy", List.of(Map.of("field", key), Map.of("field", time)));
            return MeasureCompiler.parse(body, limit, limit);
        }
    }

    public AnomalyModel { features = List.copyOf(features); }

    public String scoresDataset() { return SCORES_PREFIX + id; }
    public String latestDataset() { return scoresDataset() + LATEST_SUFFIX; }

    public Set<String> datasetIds() {
        Set<String> out = new LinkedHashSet<>();
        for (Feature f : features) out.add(f.dataset());
        return out;
    }

    /** Every column each Dataset must carry: key, time, measure field, filter fields. */
    public Map<String, Set<String>> referencedColumns() {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (Feature f : features) {
            Set<String> cols = out.computeIfAbsent(f.dataset(), d -> new LinkedHashSet<>());
            cols.add(f.key());
            cols.add(f.time());
            if (f.compiledMeasure().field() != null) cols.add(f.compiledMeasure().field());
            for (Map<String, Object> flt : f.filters()) cols.add(String.valueOf(flt.get("field")));
        }
        return out;
    }

    public String band(double score) {
        return score >= highThreshold ? "high" : score >= elevatedThreshold ? "elevated" : "normal";
    }

    public static AnomalyModel fromMap(String id, Map<String, Object> m) {
        if (id == null || !MODEL_ID.matcher(id).matches())
            throw new IllegalArgumentException("anomaly-model id '" + id + "' must be letters, digits and '_'");
        if (m.containsKey("scoresDataset"))
            throw new IllegalArgumentException("anomaly-model.scoresDataset is not authorable: the scores Dataset is "
                    + "always '" + SCORES_PREFIX + id + "' (and '" + SCORES_PREFIX + id + LATEST_SUFFIX + "')");
        for (String k : m.keySet()) {
            if (LATER_KEYS.contains(k))
                throw new IllegalArgumentException("anomaly-model." + k + " is not built yet (a later slice of ANOMALY-DETECTION-1)");
            if (!MODEL_KEYS.contains(k))
                throw new IllegalArgumentException("anomaly-model: unknown key '" + k + "' (expected " + new TreeSet<>(MODEL_KEYS) + ")");
        }
        String entityType = Values.trimToNull(m.get("entityType"));
        if (entityType == null || !SAFE_IDENT.matcher(entityType).matches())
            throw new IllegalArgumentException("anomaly-model.entityType is required and must be a plain token");
        String bucket = Values.trimToNull(m.get("bucket"));
        if (bucket != null && !"day".equals(bucket))
            throw new IllegalArgumentException("anomaly-model.bucket must be 'day' ('hour' is not built yet), got '" + bucket + "'");
        int window = whole(m.get("window"), "anomaly-model.window", 1, MAX_WINDOW_DAYS, null);
        whole(m.get("scoredPeriod"), "anomaly-model.scoredPeriod", 1, 1, 1);
        String season = Values.trimToNull(m.get("seasonality"));
        Seasonality seasonality = switch (season == null ? "none" : season) {
            case "none" -> Seasonality.NONE;
            case "weekday" -> Seasonality.WEEKDAY;
            default -> throw new IllegalArgumentException("anomaly-model.seasonality must be none or weekday for a day "
                    + "bucket, got '" + season + "'");
        };
        int minPoints = whole(m.get("minBaselinePoints"), "anomaly-model.minBaselinePoints", 1, MAX_WINDOW_DAYS,
                SeasonalBaseline.DEFAULT_MIN_POINTS);
        if (minPoints > window)
            throw new IllegalArgumentException("anomaly-model.minBaselinePoints (" + minPoints + ") cannot exceed window (" + window + ")");
        double elevated = m.get("elevatedThreshold") == null ? 60 : number(m.get("elevatedThreshold"), "anomaly-model.elevatedThreshold");
        double high = m.get("highThreshold") == null ? 80 : number(m.get("highThreshold"), "anomaly-model.highThreshold");
        if (elevated <= 0 || high > 100 || elevated >= high)
            throw new IllegalArgumentException("anomaly-model thresholds need 0 < elevatedThreshold < highThreshold <= 100, got "
                    + elevated + " / " + high);
        double zCap = m.get("zCap") == null ? 10 : number(m.get("zCap"), "anomaly-model.zCap");
        if (zCap <= 0 || zCap > 100) throw new IllegalArgumentException("anomaly-model.zCap must be in (0, 100], got " + zCap);
        double scale = m.get("scale") == null ? 3 : number(m.get("scale"), "anomaly-model.scale");
        if (scale <= 0 || scale > 100) throw new IllegalArgumentException("anomaly-model.scale must be in (0, 100], got " + scale);
        Integer maxEntities = m.get("maxEntities") == null ? null
                : whole(m.get("maxEntities"), "anomaly-model.maxEntities", 1, MAX_ENTITIES_CEILING, null);
        String scope = Values.trimToNull(m.get("dataScope"));
        if (scope != null && !SAFE_IDENT.matcher(scope).matches())
            throw new IllegalArgumentException("anomaly-model.dataScope '" + scope + "' must be a plain token");

        if (!(m.get("features") instanceof List<?> raw) || raw.isEmpty())
            throw new IllegalArgumentException("anomaly-model.features must list at least one feature");
        if (raw.size() > MAX_FEATURES)
            throw new IllegalArgumentException("anomaly-model.features: at most " + MAX_FEATURES + " features, got " + raw.size());
        List<Feature> features = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            if (!(raw.get(i) instanceof Map<?, ?> fm))
                throw new IllegalArgumentException("anomaly-model.features[" + i + "] must be an object");
            Feature f = feature(i, fm);
            if (!seen.add(f.id()))
                throw new IllegalArgumentException("anomaly-model.features: duplicate feature id '" + f.id() + "'");
            features.add(f);
        }
        return new AnomalyModel(id, entityType, window, seasonality, minPoints, elevated, high, zCap, scale,
                maxEntities, scope, Values.trimToNull(m.get("description")), features);
    }

    private static Feature feature(int i, Map<?, ?> fm) {
        String at = "anomaly-model.features[" + i + "]";
        for (Object k : fm.keySet())
            if (!FEATURE_KEYS.contains(String.valueOf(k)))
                throw new IllegalArgumentException(at + ": unknown key '" + k + "' (expected " + new TreeSet<>(FEATURE_KEYS) + ")");
        String id = ident(fm.get("id"), at + ".id");
        String dataset = Values.trimToNull(fm.get("dataset"));
        if (dataset == null || !SAFE_ID.matcher(dataset).matches())
            throw new IllegalArgumentException(at + ".dataset is required and must be a Dataset id");
        String key = ident(fm.get("key"), at + ".key");
        String time = ident(fm.get("time"), at + ".time");
        if (time.equals(key)) throw new IllegalArgumentException(at + ".time must differ from key");
        String measure = Values.trimToNull(fm.get("measure"));
        if (measure == null) throw new IllegalArgumentException(at + ".measure is required (count or agg(field))");
        String dir = Values.trimToNull(fm.get("direction"));
        Direction direction;
        try {
            direction = dir == null ? Direction.BOTH : Direction.valueOf(dir.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(at + ".direction must be up, down or both, got '" + dir + "'");
        }
        double weight = fm.get("weight") == null ? 1 : number(fm.get("weight"), at + ".weight");
        if (weight <= 0 || weight > 1000) throw new IllegalArgumentException(at + ".weight must be in (0, 1000], got " + weight);
        double unit = fm.get("unit") == null ? 1 : number(fm.get("unit"), at + ".unit");
        if (unit <= 0) throw new IllegalArgumentException(at + ".unit must be > 0, got " + unit);
        List<Map<String, Object>> filters = new ArrayList<>();
        if (fm.get("filters") != null) {
            if (!(fm.get("filters") instanceof List<?> fl))
                throw new IllegalArgumentException(at + ".filters must be a list of {field, op, value}");
            for (Object o : fl) {
                if (!(o instanceof Map<?, ?> f))
                    throw new IllegalArgumentException(at + ".filters entries must be {field, op, value}");
                Map<String, Object> copy = new LinkedHashMap<>();
                f.forEach((k, v) -> copy.put(String.valueOf(k), v));
                filters.add(copy);
            }
        }
        Feature f = new Feature(id, Values.trimToNull(fm.get("label")), dataset, key, time, measure,
                List.copyOf(filters), direction, weight, unit);
        try {
            MeasureCompiler.render(f.bucketSpec("2000-01-01", "2000-01-02", 1));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(at + ": " + e.getMessage(), e);
        }
        return f;
    }

    private static String ident(Object v, String what) {
        String s = Values.trimToNull(v);
        if (s == null || !SAFE_IDENT.matcher(s).matches())
            throw new IllegalArgumentException(what + " '" + s + "' must be a plain column/name token");
        return s;
    }

    private static int whole(Object raw, String what, int min, int max, Integer dflt) {
        if (raw == null) {
            if (dflt == null) throw new IllegalArgumentException(what + " is required");
            return dflt;
        }
        double d = number(raw, what);
        if (d != Math.rint(d) || d < min || d > max)
            throw new IllegalArgumentException(what + " must be a whole number " + min + ".." + max + ", got " + raw);
        return (int) d;
    }

    static double number(Object v, String what) {
        double d;
        if (v instanceof Number n) d = n.doubleValue();
        else if (v instanceof String s && !s.isBlank()) {
            try { d = Double.parseDouble(s.trim()); }
            catch (NumberFormatException e) { throw new IllegalArgumentException(what + " must be numeric, got '" + s + "'"); }
        } else throw new IllegalArgumentException(what + " is required and must be numeric");
        if (!Double.isFinite(d)) throw new IllegalArgumentException(what + " must be a finite number, got " + v);
        return d;
    }
}
