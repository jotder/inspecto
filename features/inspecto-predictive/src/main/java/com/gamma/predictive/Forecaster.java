package com.gamma.predictive;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The pure maths of a Forecast Model run (PREDICTIVE-FORECAST-1). Both methods reduce to one {@link Fit}: a level, a
 * trend and one seasonal value per slot, so step {@code h} forecasts {@code level + h·trend + season[(h−1) mod m]}.
 * <ul>
 *   <li><b>seasonal-naive</b>: level 0, trend 0, the season is the last {@code m} buckets (the benchmark).</li>
 *   <li><b>holt-winters</b> (additive, authored α β γ): initialised from the first season (level and seasonals) and,
 *       when there are two, the second (trend = mean difference / m; else 0); the level is placed at the end of the
 *       first season, so a series that is exactly trend + season is reproduced exactly. Then for every later bucket
 *       {@code L = α(y − S) + (1−α)(L + T)}, {@code T = β(L − L') + (1−β)T}, {@code S = γ(y − L) + (1−γ)S}.</li>
 * </ul>
 * <b>Backtest</b>: the last {@code b = min(horizon, m)} buckets are held out, the method is fitted on the rest and
 * forecasts them; the stored errors are MAE, sMAPE (percent; a bucket where actual and forecast are both 0 counts 0)
 * and MAE relative to seasonal-naive on the same hold-out (NULL when seasonal-naive's MAE is 0).
 * <b>Interval</b>: {@code forecast ± z · σ · √k}, σ the backtest RMSE and {@code k = ⌊(h−1)/m⌋ + 1} the seasons
 * ahead — the seasonal-naive variance growth, applied to both methods as an approximation.
 *
 * <p>A series is <b>insufficient</b> — no forecast, never 0 — below two seasons of buckets. Every forecast row's
 * explanation carries what {@link #recompute} needs to rebuild the row from itself.
 */
public final class Forecaster {
    private Forecaster() {}

    /** One series: its first bucket and one value per bucket up to the last complete one (empty buckets are 0). */
    public record Observed(String seriesKey, LocalDate firstBucket, double[] values) {}

    /** The fitted state a forecast reads. {@code season[j]} is the seasonal value of step {@code j + 1}. */
    public record Fit(double level, double trend, double[] season) {
        public double at(int step) { return level + step * trend + season[(step - 1) % season.length]; }
    }

    /** The method's error over the hold-out. */
    public record Backtest(int holdout, double mae, double rmse, double smape, Double relMae) {}

    /** One future bucket. */
    public record Point(int step, LocalDate bucket, double forecast, double lower, double upper,
                        Map<String, Object> explanation) {}

    /** One series' forecast; {@code backtest} is null and {@code points} empty when insufficient. */
    public record Forecast(String seriesKey, int historyBuckets, Backtest backtest, List<Point> points,
                           Map<String, Object> insufficient) {
        public boolean isInsufficient() { return insufficient != null; }
    }

    /** The forecast of every observed series, in the input order. */
    public static List<Forecast> forecast(ForecastModel model, List<Observed> observed) {
        List<Forecast> out = new ArrayList<>(observed.size());
        for (Observed o : observed) out.add(forecast(model, o));
        return out;
    }

    static Forecast forecast(ForecastModel model, Observed o) {
        double[] y = o.values();
        int n = y.length, m = model.season();
        if (n < model.minHistory()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("insufficient", "history");
            e.put("historyBuckets", n);
            e.put("minHistoryBuckets", model.minHistory());
            return new Forecast(o.seriesKey(), n, null, List.of(), e);
        }
        Backtest bt = backtest(model, y);
        Fit fit = fit(model, y, n);
        double sigma = bt.rmse(), z = model.z();
        List<Point> points = new ArrayList<>(model.horizon());
        LocalDate last = o.firstBucket().plusDays((long) (n - 1) * model.grain().days());
        for (int h = 1; h <= model.horizon(); h++) {
            double f = fit.at(h), s = fit.season()[(h - 1) % m];
            int k = (h - 1) / m + 1;
            double half = z * sigma * Math.sqrt(k);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("method", model.method().id);
            e.put("level", fit.level());
            e.put("trend", fit.trend());
            e.put("season", s);
            e.put("sigma", sigma);
            e.put("z", z);
            e.put("seasonsAhead", k);
            points.add(new Point(h, last.plusDays((long) h * model.grain().days()), f, f - half, f + half, e));
        }
        return new Forecast(o.seriesKey(), n, bt, List.copyOf(points), null);
    }

    /** {@code model}'s method fitted on the first {@code n} values of {@code y}. */
    static Fit fit(ForecastModel model, double[] y, int n) {
        return model.method() == ForecastModel.Method.HOLT_WINTERS
                ? holtWinters(y, n, model.season(), model.alpha(), model.beta(), model.gamma())
                : seasonalNaive(y, n, model.season());
    }

    static Fit seasonalNaive(double[] y, int n, int m) {
        double[] season = new double[m];
        System.arraycopy(y, n - m, season, 0, m);
        return new Fit(0, 0, season);
    }

    /** Additive Holt-Winters over {@code y[0..n)}, {@code n >= m}. */
    static Fit holtWinters(double[] y, int n, int m, double alpha, double beta, double gamma) {
        double mean1 = mean(y, 0, m);
        double trend = n >= 2 * m ? (mean(y, m, 2 * m) - mean1) / m : 0;
        double level = mean1 + trend * (m - 1) / 2.0;   // the level at the end of the first season
        double[] s = new double[m];                      // s[i]: the seasonal of slot i (t ≡ i mod m)
        for (int i = 0; i < m; i++) s[i] = y[i] - (mean1 + trend * (i - (m - 1) / 2.0));
        for (int t = m; t < n; t++) {
            int i = t % m;
            double previous = level;
            level = alpha * (y[t] - s[i]) + (1 - alpha) * (level + trend);
            trend = beta * (level - previous) + (1 - beta) * trend;
            s[i] = gamma * (y[t] - level) + (1 - gamma) * s[i];
        }
        double[] season = new double[m];
        for (int j = 0; j < m; j++) season[j] = s[(n + j) % m];
        return new Fit(level, trend, season);
    }

    /** Hold out the last {@code min(horizon, m)} buckets, fit on the rest, and measure the method and seasonal-naive. */
    static Backtest backtest(ForecastModel model, double[] y) {
        int n = y.length, m = model.season();
        int b = Math.min(model.horizon(), m), train = n - b;
        Fit method = fit(model, y, train);
        Fit naive = seasonalNaive(y, train, m);
        double abs = 0, sq = 0, smape = 0, naiveAbs = 0;
        for (int j = 0; j < b; j++) {
            double actual = y[train + j], f = method.at(j + 1), e = actual - f;
            abs += Math.abs(e);
            sq += e * e;
            double denom = Math.abs(actual) + Math.abs(f);
            smape += denom == 0 ? 0 : 2 * Math.abs(e) / denom;
            naiveAbs += Math.abs(actual - naive.at(j + 1));
        }
        double mae = abs / b, naiveMae = naiveAbs / b;
        return new Backtest(b, mae, Math.sqrt(sq / b), 100 * smape / b, naiveMae == 0 ? null : mae / naiveMae);
    }

    private static double mean(double[] y, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) sum += y[i];
        return sum / (to - from);
    }

    /** What a stored row recomputes to: its forecast and interval. */
    public record Recomputed(double forecast, double lower, double upper) {}

    /** Rebuild a row's forecast and interval from its step and its stored explanation. */
    public static Recomputed recompute(int step, Map<String, Object> explanation) {
        double level = num(explanation, "level"), trend = num(explanation, "trend"), season = num(explanation, "season");
        double half = num(explanation, "z") * num(explanation, "sigma") * Math.sqrt(num(explanation, "seasonsAhead"));
        double f = level + step * trend + season;
        return new Recomputed(f, f - half, f + half);
    }

    private static double num(Map<String, Object> e, String key) {
        if (!(e.get(key) instanceof Number n)) throw new IllegalArgumentException("explanation has no number '" + key + "'");
        return n.doubleValue();
    }
}
