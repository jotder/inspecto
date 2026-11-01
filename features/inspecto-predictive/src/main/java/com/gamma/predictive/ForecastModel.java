package com.gamma.predictive;

import com.gamma.analytics.score.ScoreKind;
import com.gamma.query.MeasureCompiler;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * A <b>Forecast Model</b> (PREDICTIVE-FORECAST-1, the Type; a Forecast is its Instance): one Measure per series per
 * future bucket, with a prediction interval and the method's backtest error, stored as the {@code forecast-model}
 * component kind at {@code registry/forecast-models/<id>.toon} and run by the {@code predict.forecast} Job. Nothing is
 * fitted by the platform: Holt-Winters' smoothing parameters are authored (OD-6).
 *
 * <pre>
 * entityType: product
 * dataset: sales             key: sku (one series per value)     time: sold_at (DATE or TIMESTAMP)
 * measure: count             (count | countDistinct(field) | sum(field); an empty bucket is 0)
 * grain: day                 (day | week — the ISO week from Monday; the season is 7 days or 52 weeks)
 * method: holt-winters       (seasonal-naive | holt-winters, additive)
 * alpha: 0.5   beta: 0.1   gamma: 0.2      (each 0..1; required by holt-winters and refused on seasonal-naive)
 * horizon: 28                (future buckets, 1..90)
 * history: 364               (buckets read before as_of, 2 seasons..1095 days / 520 weeks; default 364 / 156)
 * interval: 95               (80 | 90 | 95 | 99, default 95)
 * maxSeries: 200000          (1..1000000; default -Dpredict.forecast.maxSeries, else 100000)
 * </pre>
 *
 * A series with fewer than two seasons of buckets is {@value #INSUFFICIENT}. Validated fail closed: every problem is
 * an {@link IllegalArgumentException} naming the field. Unknown keys are refused, not ignored.
 */
public record ForecastModel(String id, String entityType, String dataset, String key, String time, String agg,
                            String field, Grain grain, Method method, Double alpha, Double beta, Double gamma,
                            int horizon, int history, int interval, Integer maxSeries, String description) {

    public static final String KIND = "forecast-model";
    /** The prefix of every Forecast store — the output name is derived, never authored. */
    public static final String FORECASTS_PREFIX = "forecasts_";
    public static final String LATEST_SUFFIX = ScoreKind.LATEST_SUFFIX;
    /** The ownership marker in each output directory, holding the model id. */
    public static final String OWNER_MARKER = ".forecast-model-output";
    /** This kind's output identity in the score output contract (ANALYTICS-CORE-1 M7). */
    public static final ScoreKind SCORE_KIND = new ScoreKind(KIND, FORECASTS_PREFIX, OWNER_MARKER);

    public static final int MAX_HORIZON = 90;
    public static final int DEFAULT_MAX_SERIES = 100_000;
    /** The highest {@code maxSeries} a model may author: 10x the system default. */
    public static final int MAX_SERIES_CEILING = 1_000_000;
    public static final Set<String> ENVELOPE_KEYS = Set.of("name", "owner", "shares");
    /** Status of a series too short to forecast honestly; it has no forecast row, never a 0. */
    public static final String INSUFFICIENT = "insufficient";
    /** The two-sided normal quantile of each allowed interval level. */
    static final Map<Integer, Double> Z = Map.of(80, 1.2815515655446004, 90, 1.6448536269514722,
            95, 1.959963984540054, 99, 2.5758293035489004);

    static final Set<String> AGGS = Set.of("count", "countDistinct", "sum");

    private static final Pattern SAFE_IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");
    private static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_]*");
    private static final Set<String> MODEL_KEYS = Set.of("name", "owner", "shares", "id", "entityType", "dataset",
            "key", "time", "measure", "grain", "method", "alpha", "beta", "gamma", "horizon", "history", "interval",
            "maxSeries", "description");

    /** A bucket grain: its season length, the default and widest history, in buckets. */
    public enum Grain {
        DAY("day", 7, 364, 1095), WEEK("week", 52, 156, 520);

        public final String id;
        public final int season;
        public final int defaultHistory;
        public final int maxHistory;

        Grain(String id, int season, int defaultHistory, int maxHistory) {
            this.id = id;
            this.season = season;
            this.defaultHistory = defaultHistory;
            this.maxHistory = maxHistory;
        }

        /** Days in one bucket. */
        public int days() { return this == DAY ? 1 : 7; }
    }

    public enum Method {
        SEASONAL_NAIVE("seasonal-naive"), HOLT_WINTERS("holt-winters");

        public final String id;

        Method(String id) { this.id = id; }
    }

    public String forecastsDataset() { return FORECASTS_PREFIX + id; }
    public String latestDataset() { return forecastsDataset() + LATEST_SUFFIX; }

    /** Buckets per season. */
    public int season() { return grain.season; }

    /** Buckets a series needs to be forecast: two seasons. */
    public int minHistory() { return 2 * grain.season; }

    /** The Measure shorthand as declared ({@code count}, {@code sum(amount)}). */
    public String measure() { return field == null ? agg : agg + "(" + field + ")"; }

    /** The interval's normal quantile. */
    public double z() { return Z.get(interval); }

    /** Every column the Dataset must carry: key, time and the Measure's field. */
    public Set<String> referencedColumns() {
        Set<String> out = new LinkedHashSet<>(List.of(key, time));
        if (field != null) out.add(field);
        return out;
    }

    /** Parse and validate a stored model; {@code id} is the component id. */
    public static ForecastModel fromMap(String id, Map<String, Object> content) {
        if (content == null) throw new IllegalArgumentException("forecast-model content is required");
        if (id == null || !MODEL_ID.matcher(id).matches() || id.length() > 64)
            throw new IllegalArgumentException("forecast-model id must match " + MODEL_ID.pattern()
                    + " (at most 64 characters): it names the derived output Datasets");
        Set<String> unknown = new TreeSet<>(content.keySet());
        unknown.removeAll(MODEL_KEYS);
        if (!unknown.isEmpty()) throw new IllegalArgumentException("forecast-model has unknown key(s) " + unknown);
        String entityType = requireText(content, "entityType", SAFE_ID);
        String dataset = requireText(content, "dataset", SAFE_ID);
        String key = requireText(content, "key", SAFE_IDENT);
        String time = requireText(content, "time", SAFE_IDENT);

        String measure = content.get("measure") == null ? "" : String.valueOf(content.get("measure")).trim();
        Map<String, Object> m;
        try {
            m = MeasureCompiler.splitShorthand(List.of(measure), null).get(0);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("forecast-model.measure: " + e.getMessage(), e);
        }
        String agg = String.valueOf(m.get("agg"));
        String field = m.get("field") == null ? null : String.valueOf(m.get("field")).trim();
        if (!AGGS.contains(agg) || ("count".equals(agg) != (field == null)))
            throw new IllegalArgumentException("forecast-model.measure must be count, countDistinct(field) or "
                    + "sum(field) (an empty bucket is 0), got '" + measure + "'");
        if (field != null && !SAFE_IDENT.matcher(field).matches())
            throw new IllegalArgumentException("forecast-model.measure field '" + field + "' must match "
                    + SAFE_IDENT.pattern());

        String g = content.get("grain") == null ? "" : String.valueOf(content.get("grain")).trim();
        Grain grain = switch (g) {
            case "day" -> Grain.DAY;
            case "week" -> Grain.WEEK;
            default -> throw new IllegalArgumentException("forecast-model.grain must be day or week, got '" + g + "'");
        };
        String me = content.get("method") == null ? "" : String.valueOf(content.get("method")).trim();
        Method method = switch (me) {
            case "seasonal-naive" -> Method.SEASONAL_NAIVE;
            case "holt-winters" -> Method.HOLT_WINTERS;
            default -> throw new IllegalArgumentException("forecast-model.method must be seasonal-naive or "
                    + "holt-winters, got '" + me + "'");
        };
        Double alpha = smoothing(content, "alpha", method);
        Double beta = smoothing(content, "beta", method);
        Double gamma = smoothing(content, "gamma", method);

        if (content.get("horizon") == null) throw new IllegalArgumentException("forecast-model.horizon is required");
        int horizon = optionalInt(content, "horizon", 1, MAX_HORIZON);
        Integer history = optionalInt(content, "history", 2 * grain.season, grain.maxHistory);
        Integer interval = content.get("interval") == null ? Integer.valueOf(95)
                : optionalInt(content, "interval", 80, 99);
        if (!Z.containsKey(interval))
            throw new IllegalArgumentException("forecast-model.interval must be one of " + new TreeSet<>(Z.keySet())
                    + ", got '" + content.get("interval") + "'");
        Integer maxSeries = optionalInt(content, "maxSeries", 1, MAX_SERIES_CEILING);
        String description = content.get("description") == null ? null : String.valueOf(content.get("description"));
        return new ForecastModel(id, entityType, dataset, key, time, agg, field, grain, method, alpha, beta, gamma,
                horizon, history == null ? grain.defaultHistory : history, interval, maxSeries, description);
    }

    /** A smoothing parameter: required in 0..1 by holt-winters, refused on seasonal-naive. */
    private static Double smoothing(Map<String, Object> content, String field, Method method) {
        Object raw = content.get(field);
        if (method != Method.HOLT_WINTERS) {
            if (raw != null) throw new IllegalArgumentException("forecast-model." + field
                    + " is refused on method " + method.id + ": only holt-winters smooths");
            return null;
        }
        if (raw == null) throw new IllegalArgumentException("forecast-model." + field
                + " is required by method holt-winters (authored, never fitted)");
        double d;
        try {
            d = raw instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("forecast-model." + field + " must be a number, got '" + raw + "'");
        }
        if (!(d >= 0 && d <= 1))
            throw new IllegalArgumentException("forecast-model." + field + " must be in 0..1, got '" + raw + "'");
        return d;
    }

    private static String requireText(Map<String, Object> content, String field, Pattern pattern) {
        Object v = content.get(field);
        String s = v == null ? "" : String.valueOf(v).trim();
        if (s.isEmpty()) throw new IllegalArgumentException("forecast-model." + field + " is required");
        if (!pattern.matcher(s).matches())
            throw new IllegalArgumentException("forecast-model." + field + " '" + s + "' must match " + pattern.pattern());
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
            throw new IllegalArgumentException("forecast-model." + field + " must be a whole number in " + min + ".."
                    + max + ", got '" + v + "'");
        return n;
    }
}
