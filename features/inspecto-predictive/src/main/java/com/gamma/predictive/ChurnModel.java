package com.gamma.predictive;

import com.gamma.alert.ChurnScoreOutputs;
import com.gamma.analytics.score.EntityCap;
import com.gamma.analytics.score.ScoreKind;
import com.gamma.analytics.score.ScoreScale;
import com.gamma.query.MeasureCompiler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * A <b>Churn Model</b> (PREDICTIVE-CHURN-DECLARED-1, the Type; a Churn Score is its Instance): a declared-coefficient
 * logistic score over one Dataset, stored as the {@code churn-model} component kind at
 * {@code registry/churn-models/<id>.toon} and run by the {@code predict.churn} Job. Nothing is fitted by the platform:
 * the intercept and coefficients are authored (OD-4, OD-6).
 *
 * <pre>
 * entityType: customer
 * dataset: orders            key: customer_id            time: order_date (DATE or TIMESTAMP)
 * window: 90                 (the inputs' trailing days as of the run's as_of, 1..3660; absent = all history)
 * intercept: -1.0
 * inputs[2]:                 (1..32, in order; each a windowed Measure, a transform and a coefficient)
 *   - name: days_quiet       measure: recency(order_date)   transform: cap   cap: 180   missing: 180   coefficient: 0.02
 *   - name: orders           measure: count                 transform: log                          coefficient: -0.8
 * minHistoryDays: 30         (1..3660, default 30: an entity first seen fewer days before as_of is insufficient)
 * elevatedThreshold: 60      highThreshold: 80     (0 &lt; elevated &lt; high &lt;= 100)
 * maxEntities: 500000        (1..2000000; default -Dpredict.churn.maxEntities, else 200000)
 * </pre>
 *
 * {@code score = 100 · σ(intercept + Σ coefficient · transform(value))}; each term is the input's contribution.
 * Measures: {@code count}, {@code countDistinct(f)}, {@code sum(f)}, {@code avg(f)}, {@code min(f)}, {@code max(f)},
 * {@code recency(f)} (whole days since the latest {@code f} in the window). Transforms: {@code none} (default),
 * {@code log} ({@code ln(1 + max(x, 0))}), {@code cap} ({@code min(x, cap)}), {@code z} ({@code (x − mean) / scale},
 * {@code scale > 0}); a transform's parameters are refused on any other transform. {@code missing} is the value an
 * input takes when the entity has none in the window; without it such an entity is insufficient — except count,
 * countDistinct and sum, whose value over no rows is 0. Validated fail closed: every problem is an
 * {@link IllegalArgumentException} naming the field. Unknown keys are refused, not ignored.
 */
public record ChurnModel(String id, String entityType, String dataset, String key, String time, Integer window,
                         double intercept, List<Input> inputs, int minHistoryDays, double elevatedThreshold,
                         double highThreshold, Integer maxEntities, String description) {

    public static final String KIND = ChurnScoreOutputs.KIND;
    public static final String SCORES_PREFIX = ChurnScoreOutputs.SCORES_PREFIX;
    public static final String LATEST_SUFFIX = ChurnScoreOutputs.LATEST_SUFFIX;
    public static final String OWNER_MARKER = ChurnScoreOutputs.OWNER_MARKER;
    public static final ScoreKind SCORE_KIND = ChurnScoreOutputs.SCORE_KIND;

    public static final int MAX_INPUTS = 32;
    public static final int MAX_WINDOW_DAYS = 3660;
    public static final int DEFAULT_MIN_HISTORY_DAYS = 30;
    public static final int MAX_ENTITIES_CEILING = EntityCap.CEILING;
    /** A coefficient, intercept or transform parameter beyond this is a typo, not a model. */
    public static final double MAX_ABS_NUMBER = 1e6;
    public static final Set<String> ENVELOPE_KEYS = Set.of("name", "owner", "shares");
    /** Band of an entity that could not be scored honestly; its score is NULL, never 0. */
    public static final String INSUFFICIENT = "insufficient";

    /** The aggregates an input may use; {@link #ZERO_AGGS} are 0 over no rows, the rest are missing. */
    static final Set<String> AGGS = Set.of("count", "countDistinct", "sum", "avg", "min", "max", "recency");
    static final Set<String> ZERO_AGGS = Set.of("count", "countDistinct", "sum");
    /** The aggregates whose field must be numeric (recency's must be a DATE or TIMESTAMP). */
    static final Set<String> NUMERIC_AGGS = Set.of("sum", "avg", "min", "max");

    private static final Pattern SAFE_IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");
    private static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_]*");
    private static final Set<String> MODEL_KEYS = Set.of("name", "owner", "shares", "id", "entityType", "dataset",
            "key", "time", "window", "intercept", "inputs", "minHistoryDays", "elevatedThreshold", "highThreshold",
            "maxEntities", "description");
    private static final Set<String> INPUT_KEYS = Set.of("name", "measure", "transform", "cap", "mean", "scale",
            "missing", "coefficient");

    /** How an input's value is transformed before its coefficient applies. */
    public enum Transform { NONE, LOG, CAP, Z }

    /** One declared input: a windowed Measure, a transform, an optional value for "none in the window", a coefficient. */
    public record Input(String name, String agg, String field, Transform transform, Double cap, Double mean,
                        Double scale, Double missing, double coefficient) {

        /** The Measure shorthand as declared ({@code count}, {@code sum(amount)}). */
        public String measure() { return field == null ? agg : agg + "(" + field + ")"; }

        /** The value this input takes when the entity has no row in the window, or {@code null} (insufficient). */
        public Double valueWhenAbsent() { return ZERO_AGGS.contains(agg) ? Double.valueOf(0.0) : missing; }

        /** {@code x} through this input's transform. */
        public double transform(double x) {
            return switch (transform) {
                case NONE -> x;
                case LOG -> Math.log1p(Math.max(x, 0.0));
                case CAP -> Math.min(x, cap);
                case Z -> (x - mean) / scale;
            };
        }
    }

    public ChurnModel { inputs = List.copyOf(inputs); }

    public String scoresDataset() { return SCORES_PREFIX + id; }
    public String latestDataset() { return scoresDataset() + LATEST_SUFFIX; }

    /** {@code high} / {@code elevated} / {@code normal} for a score (never {@link #INSUFFICIENT}: that has no score). */
    public String band(double score) { return ScoreScale.band(score, elevatedThreshold, highThreshold); }

    /** Every column the Dataset must carry: key, time and the inputs' fields. */
    public Set<String> referencedColumns() {
        Set<String> out = new LinkedHashSet<>(List.of(key, time));
        for (Input in : inputs) if (in.field() != null) out.add(in.field());
        return out;
    }

    /** Parse and validate a stored model; {@code id} is the component id. */
    public static ChurnModel fromMap(String id, Map<String, Object> content) {
        if (content == null) throw new IllegalArgumentException("churn-model content is required");
        if (id == null || !MODEL_ID.matcher(id).matches() || id.length() > 64)
            throw new IllegalArgumentException("churn-model id must match " + MODEL_ID.pattern()
                    + " (at most 64 characters): it names the derived output Datasets");
        Set<String> unknown = new TreeSet<>(content.keySet());
        unknown.removeAll(MODEL_KEYS);
        if (!unknown.isEmpty()) throw new IllegalArgumentException("churn-model has unknown key(s) " + unknown);
        String entityType = requireText(content, "entityType", SAFE_ID);
        String dataset = requireText(content, "dataset", SAFE_ID);
        String key = requireText(content, "key", SAFE_IDENT);
        String time = requireText(content, "time", SAFE_IDENT);
        Integer window = optionalInt(content, "window", 1, MAX_WINDOW_DAYS);
        if (content.get("intercept") == null) throw new IllegalArgumentException("churn-model.intercept is required");
        double intercept = number("churn-model.intercept", content.get("intercept"));
        Integer minHistory = optionalInt(content, "minHistoryDays", 1, MAX_WINDOW_DAYS);
        double elevated = content.get("elevatedThreshold") == null ? 60
                : number("churn-model.elevatedThreshold", content.get("elevatedThreshold"));
        double high = content.get("highThreshold") == null ? 80
                : number("churn-model.highThreshold", content.get("highThreshold"));
        if (!(elevated > 0 && elevated < high && high <= 100))
            throw new IllegalArgumentException("churn-model thresholds need 0 < elevatedThreshold < highThreshold <= 100, got "
                    + elevated + " and " + high);
        Integer maxEntities = optionalInt(content, "maxEntities", 1, MAX_ENTITIES_CEILING);
        String description = content.get("description") == null ? null : String.valueOf(content.get("description"));
        if (!(content.get("inputs") instanceof List<?> raw) || raw.isEmpty())
            throw new IllegalArgumentException("churn-model.inputs must be a non-empty list");
        if (raw.size() > MAX_INPUTS)
            throw new IllegalArgumentException("churn-model.inputs has " + raw.size() + " entries; at most " + MAX_INPUTS);
        List<Input> inputs = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            String p = "churn-model.inputs[" + i + "]";
            if (!(raw.get(i) instanceof Map<?, ?> row))
                throw new IllegalArgumentException(p + " must be an object {name, measure, transform, coefficient, ...}");
            Input in = input(p, row);
            if (!names.add(in.name())) throw new IllegalArgumentException(p + ".name '" + in.name() + "' is not unique");
            inputs.add(in);
        }
        return new ChurnModel(id, entityType, dataset, key, time, window, intercept, inputs,
                minHistory == null ? DEFAULT_MIN_HISTORY_DAYS : minHistory, elevated, high, maxEntities, description);
    }

    private static Input input(String p, Map<?, ?> row) {
        Set<String> extra = new TreeSet<>();
        for (Object k : row.keySet()) if (!INPUT_KEYS.contains(String.valueOf(k))) extra.add(String.valueOf(k));
        if (!extra.isEmpty()) throw new IllegalArgumentException(p + " has unknown key(s) " + extra);
        String name = row.get("name") == null ? "" : String.valueOf(row.get("name")).trim();
        if (!SAFE_IDENT.matcher(name).matches() || name.length() > 64)
            throw new IllegalArgumentException(p + ".name must match " + SAFE_IDENT.pattern() + " (at most 64 characters)");
        String measure = row.get("measure") == null ? "" : String.valueOf(row.get("measure")).trim();
        Map<String, Object> m;
        try {
            m = MeasureCompiler.splitShorthand(List.of(measure), null).get(0);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(p + ".measure: " + e.getMessage(), e);
        }
        String agg = String.valueOf(m.get("agg"));
        String field = m.get("field") == null ? null : String.valueOf(m.get("field")).trim();
        if (!AGGS.contains(agg) || ("count".equals(agg) != (field == null)))
            throw new IllegalArgumentException(p + ".measure must be count or one of " + new TreeSet<>(AGGS)
                    + " over a field, got '" + measure + "'");
        if (field != null && !SAFE_IDENT.matcher(field).matches())
            throw new IllegalArgumentException(p + ".measure field '" + field + "' must match " + SAFE_IDENT.pattern());
        String t = row.get("transform") == null ? "none" : String.valueOf(row.get("transform")).trim();
        Transform transform = switch (t) {
            case "none" -> Transform.NONE;
            case "log" -> Transform.LOG;
            case "cap" -> Transform.CAP;
            case "z" -> Transform.Z;
            default -> throw new IllegalArgumentException(p + ".transform must be none, log, cap or z, got '" + t + "'");
        };
        Double cap = optionalNumber(p + ".cap", row.get("cap"));
        Double mean = optionalNumber(p + ".mean", row.get("mean"));
        Double scale = optionalNumber(p + ".scale", row.get("scale"));
        if ((transform == Transform.CAP) != (cap != null))
            throw new IllegalArgumentException(p + ".cap is required by transform cap and refused on any other");
        if ((transform == Transform.Z) != (mean != null) || (transform == Transform.Z) != (scale != null))
            throw new IllegalArgumentException(p + ".mean and .scale are required by transform z and refused on any other");
        if (scale != null && !(scale > 0)) throw new IllegalArgumentException(p + ".scale must be > 0, got " + scale);
        Double missing = optionalNumber(p + ".missing", row.get("missing"));
        if (missing != null && ZERO_AGGS.contains(agg))
            throw new IllegalArgumentException(p + ".missing is refused on " + agg + ": its value over no rows is 0");
        if (row.get("coefficient") == null) throw new IllegalArgumentException(p + ".coefficient is required");
        double coefficient = number(p + ".coefficient", row.get("coefficient"));
        return new Input(name, agg, field, transform, cap, mean, scale, missing, coefficient);
    }

    private static Double optionalNumber(String path, Object raw) {
        return raw == null ? null : number(path, raw);
    }

    private static double number(String path, Object raw) {
        double d;
        try {
            d = raw instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(path + " must be a number, got '" + raw + "'");
        }
        if (!Double.isFinite(d) || Math.abs(d) > MAX_ABS_NUMBER)
            throw new IllegalArgumentException(path + " must be a finite number within ±" + (long) MAX_ABS_NUMBER
                    + ", got '" + raw + "'");
        return d;
    }

    private static String requireText(Map<String, Object> content, String field, Pattern pattern) {
        Object v = content.get(field);
        String s = v == null ? "" : String.valueOf(v).trim();
        if (s.isEmpty()) throw new IllegalArgumentException("churn-model." + field + " is required");
        if (!pattern.matcher(s).matches())
            throw new IllegalArgumentException("churn-model." + field + " '" + s + "' must match " + pattern.pattern());
        return s;
    }

    private static Integer optionalInt(Map<String, Object> content, String field, int min, int max) {
        Object v = content.get(field);
        if (v == null) return null;
        Integer n = null;
        try {
            java.math.BigDecimal d = new java.math.BigDecimal(String.valueOf(v).trim());
            if (d.stripTrailingZeros().scale() <= 0) n = d.intValueExact();
        } catch (NumberFormatException | ArithmeticException ignored) {
            // not a whole number in int range: refused below
        }
        if (n == null || n < min || n > max)
            throw new IllegalArgumentException("churn-model." + field + " must be a whole number in " + min + ".."
                    + max + ", got '" + v + "'");
        return n;
    }
}
