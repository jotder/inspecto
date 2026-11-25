package com.gamma.query;

import com.gamma.analytics.accuracy.Accuracy;
import com.gamma.analytics.accuracy.Backtest;
import com.gamma.analytics.baseline.Baseline;
import com.gamma.analytics.baseline.SeasonalBaseline;
import com.gamma.analytics.baseline.Seasonality;
import com.gamma.analytics.forecast.ForecastMethod;
import com.gamma.analytics.forecast.Forecaster;
import com.gamma.analytics.time.SeasonalityDetector;
import com.gamma.analytics.time.TimeGrain;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import static com.gamma.util.Values.trimToNull;

/**
 * Time-series overlays on a Widget's result (BI-INSIGHTS-1 P1): a <b>forecast band</b> with its backtest accuracy (I-11)
 * and <b>anomaly scores</b> against a seasonal baseline (I-7). D-BI7: computed at query time from the rows the Widget's
 * own sandboxed statement returned — after masking, under the same row cap — with no second query and no storage. Every
 * number comes from the Analytics Kernel: {@link Forecaster} (Holt-Winters additive, the JVM twin of {@code ForecastSql},
 * parity-tested) with its {@code PredictionInterval}, {@link Backtest} + {@link Accuracy}, {@link SeasonalityDetector},
 * {@link SeasonalBaseline} and its robust z.
 *
 * <p>Per Measure / ratio {@code m} (and per value of the other grouped columns — one series each):
 * <ul>
 *   <li><b>forecast</b> adds {@code m__fc}, {@code m__fc_lo}, {@code m__fc_hi} on {@code horizon} new rows after the last
 *       bucket (the Measure itself NULL there). The period is the one {@link SeasonalityDetector} finds among the grain's
 *       calendar cycles, else the grain's first calendar cycle (7 days, 52 weeks, 12 months). <b>Honesty rules</b>: fewer
 *       than two seasons, a missing bucket, a bucket with no value, a cut result or a non-number → no forecast and the
 *       reason, never a guessed band; a missing value is never read as 0. The rolling-origin backtest (origins from two
 *       seasons on, at most {@link #MAX_BACKTEST_ORIGINS}) scores the same model on the same series.</li>
 *   <li><b>anomaly</b> adds {@code m__expected} (the baseline's median) and {@code m__z} (the robust z) on every row
 *       with a value: a {@link SeasonalBaseline} over the series' other buckets in the result (the scored one excluded),
 *       by weekday when the grain is a day and the detector finds a 7-day cycle, else over all buckets; fewer than
 *       {@link SeasonalBaseline#DEFAULT_MIN_POINTS} points → no score. The sensitivity (the |z| that counts as an
 *       anomaly) is the renderer's: the score is the same at every setting.</li>
 * </ul>
 * The summary ({@link Outcome#summary()}) states, per Measure and series, the method, the period and where it came from,
 * the backtest's measures, or the reason there is no overlay.
 */
public final class ChartOverlays {

    private ChartOverlays() {}

    public static final String FC = "__fc", FC_LO = "__fc_lo", FC_HI = "__fc_hi", EXPECTED = "__expected", Z = "__z";
    public static final int MAX_HORIZON = 60, DEFAULT_MAX_HORIZON = 24, MAX_SERIES = 20, MAX_BACKTEST_ORIGINS = 60;
    public static final double DEFAULT_LEVEL = 0.8, MIN_LEVEL = 0.5, MAX_LEVEL = 0.99;

    /** {@code horizon == null}: the period, at most {@link #DEFAULT_MAX_HORIZON}. */
    public record Forecast(Integer horizon, double level) {}

    /** What the body asked for: the time column and which overlays ({@code forecast} may be null; {@code anomaly} a flag). */
    public record Request(String field, Forecast forecast, boolean anomaly) {}

    /** The result with the overlay columns (and forecast rows), and one summary entry per overlay, Measure and series. */
    public record Outcome(QueryExecutor.Result result, List<Map<String, Object>> summary) {}

    /**
     * The body's {@code overlay}: {@code {forecast?: {horizon?, level?}, anomaly?: {}, field?}}; unknown keys refused;
     * {@code null} = none. {@code field} defaults to the one grained {@code groupBy} column.
     */
    public static Request parse(Object raw, MeasureCompiler.Spec spec) {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> o)) throw new IllegalArgumentException("'overlay' must be an object {forecast?, anomaly?, field?}");
        for (Object k : o.keySet())
            if (!"forecast".equals(k) && !"anomaly".equals(k) && !"field".equals(k))
                throw new IllegalArgumentException("unknown overlay key '" + k + "' (forecast, anomaly, field)");
        Forecast forecast = null;
        if (o.get("forecast") != null) {
            if (!(o.get("forecast") instanceof Map<?, ?> f))
                throw new IllegalArgumentException("overlay 'forecast' must be an object {horizon?, level?}");
            for (Object k : f.keySet())
                if (!"horizon".equals(k) && !"level".equals(k))
                    throw new IllegalArgumentException("unknown overlay forecast key '" + k + "' (horizon, level)");
            Integer horizon = null;
            if (f.get("horizon") != null) {
                if (!(f.get("horizon") instanceof Number n) || n.doubleValue() != n.intValue() || n.intValue() < 1 || n.intValue() > MAX_HORIZON)
                    throw new IllegalArgumentException("overlay forecast 'horizon' must be a whole number 1.." + MAX_HORIZON + ": " + f.get("horizon"));
                horizon = n.intValue();
            }
            double level = DEFAULT_LEVEL;
            if (f.get("level") != null) {
                if (!(f.get("level") instanceof Number n) || !(n.doubleValue() >= MIN_LEVEL && n.doubleValue() <= MAX_LEVEL))
                    throw new IllegalArgumentException("overlay forecast 'level' must be a number " + MIN_LEVEL + ".." + MAX_LEVEL + ": " + f.get("level"));
                level = n.doubleValue();
            }
            forecast = new Forecast(horizon, level);
        }
        boolean anomaly = false;
        if (o.get("anomaly") != null) {
            if (!(o.get("anomaly") instanceof Map<?, ?> a) || !a.isEmpty())
                throw new IllegalArgumentException("overlay 'anomaly' must be an empty object {} (the sensitivity is the chart's)");
            anomaly = true;
        }
        if (forecast == null && !anomaly) throw new IllegalArgumentException("overlay needs 'forecast' or 'anomaly'");
        if (spec.measures().isEmpty() && spec.ratios().isEmpty())
            throw new IllegalArgumentException("an overlay needs at least one measure or ratio");
        List<String> grained = spec.groupBy().stream().filter(spec.grains()::containsKey).toList();
        String field = trimToNull(o.get("field"));
        if (field == null) {
            if (grained.size() != 1)
                throw new IllegalArgumentException("an overlay needs one time dimension with a grain"
                        + (grained.isEmpty() ? "" : " - name it as 'field' (one of " + grained + ")"));
            field = grained.get(0);
        } else if (!grained.contains(field))
            throw new IllegalArgumentException("overlay field '" + field + "' must be a groupBy column with a time grain");
        Request req = new Request(field, forecast, anomaly);
        List<String> taken = new ArrayList<>(spec.groupBy());
        taken.addAll(valueIds(spec));
        taken.addAll(MeasureCompiler.compareColumns(spec).keySet());
        for (String col : columns(req, spec).keySet())
            if (taken.stream().anyMatch(g -> g.equalsIgnoreCase(col)))
                throw new IllegalArgumentException("result column '" + col + "' clashes with an overlay column name");
        return req;
    }

    /** The overlay columns a request adds, by name, in order: the Measure / ratio they belong to and their mark. */
    static Map<String, ResultSetDescriptor.Overlay> columns(Request req, MeasureCompiler.Spec spec) {
        Map<String, ResultSetDescriptor.Overlay> out = new LinkedHashMap<>();
        for (String id : valueIds(spec)) {
            if (req.forecast() != null) {
                String method = ForecastMethod.HOLT_WINTERS.wire();
                out.put(id + FC, new ResultSetDescriptor.Overlay("forecast", method, id, "point"));
                out.put(id + FC_LO, new ResultSetDescriptor.Overlay("forecast", method, id, "lower"));
                out.put(id + FC_HI, new ResultSetDescriptor.Overlay("forecast", method, id, "upper"));
            }
            if (req.anomaly()) {
                out.put(id + EXPECTED, new ResultSetDescriptor.Overlay("anomaly", "robust_z", id, "expected"));
                out.put(id + Z, new ResultSetDescriptor.Overlay("anomaly", "robust_z", id, "z"));
            }
        }
        return out;
    }

    private static List<String> valueIds(MeasureCompiler.Spec spec) {
        List<String> ids = new ArrayList<>();
        for (MeasureCompiler.Measure m : spec.measures()) ids.add(m.id());
        for (MeasureCompiler.Ratio r : spec.ratios()) ids.add(r.id());
        return ids;
    }

    /** One bucket of one series: the date, the index of its row in the result. */
    private record Point(LocalDate date, int row) {}

    /** Apply {@code req} to {@code shown} (the masked result of {@code spec}'s statement). */
    public static Outcome apply(Request req, MeasureCompiler.Spec spec, QueryExecutor.Result shown) {
        TimeGrain grain = TimeGrain.parse(spec.grains().get(req.field()));
        List<String> others = spec.groupBy().stream().filter(g -> !g.equals(req.field())).toList();
        Map<String, String> types = new HashMap<>();
        for (ResultSetDescriptor.Column c : shown.columns()) types.put(c.name(), c.type());

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : shown.rows()) rows.add(new LinkedHashMap<>(r));
        Map<String, ResultSetDescriptor.Overlay> overlayCols = columns(req, spec);
        for (Map<String, Object> r : rows) for (String c : overlayCols.keySet()) r.put(c, null);

        // One series per value of the other grouped columns, in first-seen order; a row with no readable bucket is left out.
        Map<List<Object>, List<Point>> series = new LinkedHashMap<>();
        int unreadable = 0;
        for (int i = 0; i < rows.size(); i++) {
            LocalDate d = bucket(rows.get(i).get(req.field()));
            if (d == null) { unreadable++; continue; }
            List<Object> key = new ArrayList<>();
            for (String o : others) key.add(rows.get(i).get(o));
            series.computeIfAbsent(key, k -> new ArrayList<>()).add(new Point(d, i));
        }
        for (List<Point> pts : series.values()) pts.sort(Comparator.comparing(Point::date));

        List<Map<String, Object>> summary = new ArrayList<>();
        // future rows, by series then step, filled per Measure
        Map<List<Object>, List<Map<String, Object>>> future = new LinkedHashMap<>();
        String refusal = shown.truncated() ? "the result was cut at the row limit; an overlay over part of a series would mislead"
                : series.size() > MAX_SERIES ? series.size() + " series exceed the limit of " + MAX_SERIES + " for overlays"
                : series.isEmpty() ? "the result has no time buckets" : null;
        for (String id : valueIds(spec)) {
            for (Map.Entry<List<Object>, List<Point>> e : series.entrySet()) {
                Map<String, Object> seriesKey = new LinkedHashMap<>();
                for (int k = 0; k < others.size(); k++) seriesKey.put(others.get(k), e.getKey().get(k));
                String why = refusal != null ? refusal
                        : !"number".equals(types.get(id)) ? "'" + id + "' is not a number here (masked or not numeric)" : null;
                if (req.forecast() != null)
                    summary.add(forecast(req, grain, id, seriesKey, e.getValue(), rows, future.computeIfAbsent(e.getKey(), k -> new ArrayList<>()),
                            others, e.getKey(), why));
                if (req.anomaly())
                    summary.add(anomaly(grain, id, seriesKey, e.getValue(), rows, why));
            }
            if (refusal != null && series.isEmpty()) {
                if (req.forecast() != null) summary.add(entry("forecast", id, Map.of(), refusal));
                if (req.anomaly()) summary.add(entry("anomaly", id, Map.of(), refusal));
            }
        }
        if (unreadable > 0)
            for (Map<String, Object> s : summary) s.put("unreadableBuckets", unreadable);
        // Forecast rows after the history, step by step, every series at each step (the category order a chart reads).
        int steps = future.values().stream().mapToInt(List::size).max().orElse(0);
        for (int h = 0; h < steps; h++)
            for (List<Map<String, Object>> f : future.values()) if (h < f.size()) rows.add(f.get(h));

        List<ResultSetDescriptor.Column> cols = new ArrayList<>(shown.columns());
        overlayCols.forEach((name, mark) -> cols.add(new ResultSetDescriptor.Column(name, "number", "measure", null, mark)));
        return new Outcome(new QueryExecutor.Result(cols, rows, rows.size(), shown.truncated(), shown.elapsedMs()), summary);
    }

    private static Map<String, Object> entry(String overlay, String id, Map<String, Object> seriesKey, String reason) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("overlay", overlay);
        s.put("of", id);
        s.put("series", seriesKey);
        if (reason != null) s.put("reason", reason);
        return s;
    }

    /** I-11 for one Measure and series: fills the future rows' forecast columns; returns the summary entry. */
    private static Map<String, Object> forecast(Request req, TimeGrain grain, String id, Map<String, Object> seriesKey, List<Point> pts,
                                                List<Map<String, Object>> rows, List<Map<String, Object>> future, List<String> others,
                                                List<Object> key, String why) {
        if (why != null) return entry("forecast", id, seriesKey, why);
        int gaps = 0;
        for (int i = 1; i < pts.size(); i++)
            if (!next(grain, pts.get(i - 1).date()).equals(pts.get(i).date())) gaps++;
        if (gaps > 0)
            return entry("forecast", id, seriesKey, gaps + " gap(s) between buckets; a forecast needs every bucket, and a missing one is never read as 0");
        double[] y = new double[pts.size()];
        int empty = 0;
        for (int i = 0; i < y.length; i++) {
            Double v = number(rows.get(pts.get(i).row()).get(id));
            if (v == null) empty++;
            else y[i] = v;
        }
        if (empty > 0)
            return entry("forecast", id, seriesKey, empty + " bucket(s) have no value; a forecast needs a value in every bucket");
        int[] candidates = SeasonalityDetector.candidatesFor(grain);
        OptionalInt detected = SeasonalityDetector.detect(y, candidates).period();
        int period = detected.orElse(candidates[0]);
        String source = detected.isPresent() ? "detected" : "calendar";
        Forecaster.Spec fs = new Forecaster.Spec(ForecastMethod.HOLT_WINTERS, period, Forecaster.DEFAULT_ALPHA, Forecaster.DEFAULT_BETA,
                Forecaster.DEFAULT_GAMMA, req.forecast().horizon() != null ? req.forecast().horizon() : Math.min(period, DEFAULT_MAX_HORIZON),
                List.of(req.forecast().level()));
        Map<String, Object> s = entry("forecast", id, seriesKey, null);
        s.put("method", ForecastMethod.HOLT_WINTERS.wire());
        s.put("period", period);
        s.put("periodSource", source);
        s.put("level", req.forecast().level());
        s.put("horizon", fs.horizon());
        s.put("points", y.length);
        if (y.length < fs.minPoints()) {
            s.put("reason", "a forecast needs two seasons (" + fs.minPoints() + " " + grain.wire() + "s of a " + period + "-" + grain.wire()
                    + " season); the series has " + y.length);
            return s;
        }
        Forecaster.Result fit = Forecaster.fit(y, fs);
        double level = req.forecast().level();
        double[] lo = fit.lowerAhead(level), hi = fit.upperAhead(level);
        LocalDate at = pts.get(pts.size() - 1).date();
        for (int h = 0; h < fs.horizon(); h++) {
            at = next(grain, at);
            if (future.size() <= h) {
                Map<String, Object> row = new LinkedHashMap<>(rows.get(pts.get(0).row()));
                row.replaceAll((k, v) -> null);
                row.put(req.field(), label(at, grain));
                for (int k = 0; k < others.size(); k++) row.put(others.get(k), key.get(k));
                future.add(row);
            }
            Map<String, Object> row = future.get(h);
            row.put(id + FC, fit.ahead()[h]);
            row.put(id + FC_LO, lo[h]);
            row.put(id + FC_HI, hi[h]);
        }
        s.put("backtest", backtest(y, fs, level));
        return s;
    }

    /**
     * The rolling-origin backtest of the same model on the same series: origins from two seasons on, every
     * {@code step} buckets so there are at most {@link #MAX_BACKTEST_ORIGINS}, each forecasting the next
     * {@code min(horizon, buckets left)}. A series of exactly two seasons has no origin: {@code {reason}}.
     */
    private static Map<String, Object> backtest(double[] y, Forecaster.Spec fs, double level) {
        Map<String, Object> b = new LinkedHashMap<>();
        int minTrain = fs.minPoints();
        int h = Math.min(fs.horizon(), y.length - minTrain);
        if (h < 1) {
            b.put("reason", "no bucket after the first two seasons to test against");
            return b;
        }
        int all = Backtest.originCount(y.length, new Backtest.Spec(minTrain, h, 1));
        int step = Math.max(1, (all + MAX_BACKTEST_ORIGINS - 1) / MAX_BACKTEST_ORIGINS);
        Forecaster.Spec at = fs.withHorizon(h);
        Backtest.Result r = Backtest.run(y, new Backtest.Spec(minTrain, h, step, MAX_BACKTEST_ORIGINS), (history, hh) -> {
            Forecaster.Result f = Forecaster.fit(history, at);
            return new Backtest.Forecast(f.ahead(), f.lowerAhead(level), f.upperAhead(level));
        });
        Accuracy.Summary o = r.overall();
        b.put("origins", r.origins().size());
        b.put("horizon", h);
        b.put("pairs", o.n());
        b.put("mape", o.mape().isPresent() ? o.mape().getAsDouble() : null);
        b.put("smape", o.smape());
        b.put("mae", o.mae());
        b.put("coverage", o.coverage().isPresent() ? o.coverage().getAsDouble() : null);
        return b;
    }

    /** I-7 for one Measure and series: fills {@code m__expected} / {@code m__z} on its rows; returns the summary entry. */
    private static Map<String, Object> anomaly(TimeGrain grain, String id, Map<String, Object> seriesKey, List<Point> pts,
                                               List<Map<String, Object>> rows, String why) {
        if (why != null) return entry("anomaly", id, seriesKey, why);
        Map<LocalDateTime, Double> history = new LinkedHashMap<>();
        boolean regular = true;
        for (int i = 0; i < pts.size(); i++) {
            Double v = number(rows.get(pts.get(i).row()).get(id));
            if (v == null) regular = false;
            else history.put(pts.get(i).date().atStartOfDay(), v);
            if (i > 0 && !next(grain, pts.get(i - 1).date()).equals(pts.get(i).date())) regular = false;
        }
        Seasonality seasonality = Seasonality.NONE;
        if (grain == TimeGrain.DAY && regular && history.size() > 1) {
            double[] y = history.values().stream().mapToDouble(Double::doubleValue).toArray();
            OptionalInt p = SeasonalityDetector.detect(y, new int[] {7}).period();
            if (p.isPresent()) seasonality = Seasonality.WEEKDAY;
        }
        SeasonalBaseline baseline = new SeasonalBaseline(seasonality, SeasonalBaseline.DEFAULT_MIN_POINTS);
        int scored = 0;
        for (Point p : pts) {
            Map<String, Object> row = rows.get(p.row());
            Double v = number(row.get(id));
            if (v == null) continue;
            Baseline b = baseline.compute(history, p.date().atStartOfDay());
            if (b.insufficient()) continue;
            double z = b.z(v, 0);
            row.put(id + EXPECTED, b.median());
            if (Double.isFinite(z)) {
                row.put(id + Z, z);
                scored++;
            }
        }
        Map<String, Object> s = entry("anomaly", id, seriesKey, null);
        s.put("method", "robust_z");
        s.put("seasonality", seasonality.name().toLowerCase(java.util.Locale.ROOT));
        s.put("minPoints", SeasonalBaseline.DEFAULT_MIN_POINTS);
        s.put("points", history.size());
        s.put("scored", scored);
        if (scored == 0)
            s.put("reason", "no bucket could be scored: a baseline needs " + SeasonalBaseline.DEFAULT_MIN_POINTS
                    + " other buckets with a value and some spread");
        return s;
    }

    /** A cell as a finite number, or {@code null} (absent, text, NaN). */
    private static Double number(Object v) {
        if (v instanceof Number n && Double.isFinite(n.doubleValue())) return n.doubleValue();
        return null;
    }

    /** A bucket label as the compiler formats it ({@code YYYY-MM-DD}, a month {@code YYYY-MM}) → its start date. */
    static LocalDate bucket(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        try {
            if (s.length() == 7) return LocalDate.parse(s + "-01");
            return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static LocalDate next(TimeGrain grain, LocalDate d) {
        return grain.plus(d.atStartOfDay(), 1).toLocalDate();
    }

    /** The label a new bucket carries: the compiler's own format. */
    private static String label(LocalDate d, TimeGrain grain) {
        return grain == TimeGrain.MONTH ? d.toString().substring(0, 7) : d.toString();
    }
}
