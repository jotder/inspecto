package com.gamma.analytics.time;

import com.gamma.util.SqlIdent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.TreeSet;
import java.util.function.IntFunction;

/**
 * Seasonality detection (ANALYTICS-CORE-1 S5c): the dominant period of an evenly spaced series, found from the data among
 * candidate periods by autocorrelation — deterministic and explainable (every candidate's ACF and verdict is returned).
 *
 * <p><b>ACF</b> — the standard (Box–Jenkins) <i>biased</i> estimator, denominator the whole-series sum of squares:
 * {@code r(k) = Σ_{t=0}^{n−k−1} (x_t − x̄)(x_{t+k} − x̄) / Σ_{t=0}^{n−1} (x_t − x̄)²}. It is always in [−1, 1] and
 * shrinks with the lag by (n − k)/n, so of two equally strong periods the shorter one scores higher (14 never beats 7
 * on a weekly signal). Undefined (empty) for a constant series; 0 at a lag ≥ n.
 *
 * <p><b>Rule</b> — a candidate period k (≥ 2) is <i>eligible</i> when all of:
 * <ol>
 *   <li><b>minimum cycles</b>: n ≥ {@code minCycles} · k (default 3 — at least three whole cycles observed);</li>
 *   <li><b>significant</b>: r(k) ≥ max({@code minAcf}, 1.96 / √n) — above the 95 % white-noise band (Bartlett) and
 *       above a floor (default 0.3) that a long series' tiny band cannot undercut;</li>
 *   <li><b>a peak</b>: r(k) &gt; r(k−1) and r(k) &gt; r(k+1) — a trend's slowly decaying ACF has no peak, so trend is
 *       not mistaken for a season.</li>
 * </ol>
 * The detected period is the eligible candidate with the highest r(k); a tie goes to the shorter period. No eligible
 * candidate → no period ({@link Detection#period()} empty).
 *
 * <p>The series is evenly spaced with no gaps (resample it first — M5); NaN is refused. Detrending is the caller's
 * choice (e.g. a first difference through {@code transform.timeseries.shift}).
 *
 * <p><b>SQL form</b> — {@link #acfSql} computes r(k) per key in DuckDB for the lags {@link #lagsFor} names, and
 * {@link #decide} applies the same rule to those rows, so the two forms share one decision; {@code
 * SeasonalityDuckDbParityTest} holds the ACF together.
 */
public final class SeasonalityDetector {

    /** The 95 % two-sided normal quantile of the white-noise band 1.96 / √n. */
    public static final double Z_95 = 1.96;
    /** Longest series accepted (fails closed beyond it). */
    public static final int MAX_SERIES_LENGTH = 1_000_000;

    private SeasonalityDetector() {}

    /**
     * @param minAcf    the floor under the significance threshold, in (0, 1]
     * @param minCycles whole cycles that must be observed, ≥ 2
     */
    public record Params(double minAcf, int minCycles) {
        public static final Params DEFAULT = new Params(0.3, 3);

        public Params {
            if (!(minAcf > 0 && minAcf <= 1)) throw new IllegalArgumentException("minAcf must be in (0, 1]: " + minAcf);
            if (minCycles < 2) throw new IllegalArgumentException("minCycles must be >= 2: " + minCycles);
        }
    }

    /** Why a candidate was or was not chosen, checked in this order. */
    public enum Verdict { TOO_FEW_CYCLES, CONSTANT_SERIES, NOT_SIGNIFICANT, NOT_A_PEAK, ELIGIBLE, CHOSEN }

    /** One candidate's evidence: its ACF and its neighbours' (empty when not computed or undefined). */
    public record Evidence(int period, OptionalDouble acf, OptionalDouble acfBefore, OptionalDouble acfAfter, Verdict verdict) {}

    /** The result: the period (empty = none), the series length, the threshold applied, every candidate's evidence. */
    public record Detection(OptionalInt period, int n, double threshold, List<Evidence> evidence) {}

    /** The default candidate periods of a grain: the calendar cycles one bucket of it can show. */
    public static int[] candidatesFor(TimeGrain grain) {
        return switch (grain) {
            case SECOND -> new int[] {60};
            case MINUTE -> new int[] {60, 1440};
            case HOUR -> new int[] {24, 168};
            case DAY -> new int[] {7, 365};
            case WEEK -> new int[] {52};
            case MONTH -> new int[] {12};
            case QUARTER -> new int[] {4};
            case YEAR -> new int[] {};
        };
    }

    /** r(lag) as defined above; empty for a constant (or empty) series. */
    public static OptionalDouble acf(double[] x, int lag) {
        Objects.requireNonNull(x, "x");
        if (lag < 0) throw new IllegalArgumentException("lag must be >= 0: " + lag);
        for (double v : x) if (Double.isNaN(v)) throw new IllegalArgumentException("NaN in series - gap-fill it first");
        int n = x.length;
        if (n == 0) return OptionalDouble.empty();
        double mean = 0;
        for (double v : x) mean += v;
        mean /= n;
        double den = 0, num = 0;
        for (double v : x) den += (v - mean) * (v - mean);
        if (den == 0) return OptionalDouble.empty();
        for (int t = 0; t + lag < n; t++) num += (x[t] - mean) * (x[t + lag] - mean);
        return OptionalDouble.of(num / den);
    }

    /** {@link #detect(double[], int[], Params)} with {@link Params#DEFAULT}. */
    public static Detection detect(double[] x, int[] candidates) {
        return detect(x, candidates, Params.DEFAULT);
    }

    /** Detects the dominant period of {@code x} among {@code candidates} (each ≥ 2). */
    public static Detection detect(double[] x, int[] candidates, Params params) {
        Objects.requireNonNull(x, "x");
        if (x.length > MAX_SERIES_LENGTH)
            throw new IllegalArgumentException("seasonality: series of " + x.length + " values exceeds the cap of " + MAX_SERIES_LENGTH);
        return decide(x.length, candidates, params, lag -> acf(x, lag));
    }

    /** The lags {@link #decide} reads for these candidates: k − 1, k, k + 1 of each, ascending, distinct. */
    public static int[] lagsFor(int[] candidates) {
        TreeSet<Integer> lags = new TreeSet<>();
        for (int k : checked(candidates)) {
            lags.add(k - 1);
            lags.add(k);
            lags.add(k + 1);
        }
        return lags.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * The rule over precomputed ACF values — the JVM form passes {@link #acf}, a SQL caller the {@link #acfSql} rows
     * of one key ({@code acfAt} returns empty for a lag with an undefined ACF).
     *
     * @param n the series length
     */
    public static Detection decide(int n, int[] candidates, Params params, IntFunction<OptionalDouble> acfAt) {
        Objects.requireNonNull(params, "params");
        int[] ks = checked(candidates);
        double threshold = Math.max(params.minAcf(), n == 0 ? Double.POSITIVE_INFINITY : Z_95 / Math.sqrt(n));
        List<Evidence> evidence = new ArrayList<>(ks.length);
        int best = -1;
        for (int i = 0; i < ks.length; i++) {
            int k = ks[i];
            if ((long) n < (long) params.minCycles() * k) {
                evidence.add(new Evidence(k, OptionalDouble.empty(), OptionalDouble.empty(), OptionalDouble.empty(), Verdict.TOO_FEW_CYCLES));
                continue;
            }
            OptionalDouble r = acfAt.apply(k), before = acfAt.apply(k - 1), after = acfAt.apply(k + 1);
            Verdict v;
            if (r.isEmpty() || before.isEmpty() || after.isEmpty()) v = Verdict.CONSTANT_SERIES;
            else if (r.getAsDouble() < threshold) v = Verdict.NOT_SIGNIFICANT;
            else if (!(r.getAsDouble() > before.getAsDouble() && r.getAsDouble() > after.getAsDouble())) v = Verdict.NOT_A_PEAK;
            else {
                v = Verdict.ELIGIBLE;
                if (best < 0 || r.getAsDouble() > evidence.get(best).acf().getAsDouble()) best = i;
            }
            evidence.add(new Evidence(k, r, before, after, v));
        }
        if (best < 0) return new Detection(OptionalInt.empty(), n, threshold, List.copyOf(evidence));
        Evidence c = evidence.get(best);
        evidence.set(best, new Evidence(c.period(), c.acf(), c.acfBefore(), c.acfAfter(), Verdict.CHOSEN));
        return new Detection(OptionalInt.of(c.period()), n, threshold, List.copyOf(evidence));
    }

    /**
     * DuckDB SQL for r(k) at each of {@code lags}, per key: columns {@code <keys…>, n, lag, acf}, ordered by keys then
     * lag. A series is the key's rows ordered by {@code orderColumn} (which must be unique per key — one row per bucket);
     * a row whose value is NULL is left out, so gap-fill first. {@code acf} is NULL for a constant series and 0 at a lag
     * ≥ n, as {@link #acf}.
     */
    public static String acfSql(String relation, String orderColumn, String valueColumn, List<String> keys, int[] lags) {
        Objects.requireNonNull(relation, "relation");
        Objects.requireNonNull(orderColumn, "orderColumn");
        Objects.requireNonNull(valueColumn, "valueColumn");
        keys = List.copyOf(keys);
        if (lags.length == 0) throw new IllegalArgumentException("acfSql needs at least one lag");
        StringBuilder lagList = new StringBuilder();
        for (int lag : lags) {
            if (lag < 0) throw new IllegalArgumentException("lag must be >= 0: " + lag);
            lagList.append(lagList.length() == 0 ? "" : ", ").append(lag);
        }
        String part = keys.isEmpty() ? "" : "PARTITION BY " + cols(keys, "") + " ";
        String keySel = keys.isEmpty() ? "" : cols(keys, "") + ", ";
        StringBuilder on = new StringBuilder();
        for (String k : keys) on.append(" AND b.").append(SqlIdent.q(k)).append(" IS NOT DISTINCT FROM a.").append(SqlIdent.q(k));
        StringBuilder onDen = new StringBuilder();
        for (String k : keys) onDen.append(" AND p.").append(SqlIdent.q(k)).append(" IS NOT DISTINCT FROM d.").append(SqlIdent.q(k));

        return "WITH __s AS (SELECT " + keySel + "row_number() OVER (" + part + "ORDER BY " + SqlIdent.q(orderColumn)
                + ") - 1 AS __t, CAST(" + SqlIdent.q(valueColumn) + " AS DOUBLE) AS __x FROM " + SqlIdent.q(relation)
                + " WHERE " + SqlIdent.q(valueColumn) + " IS NOT NULL)"
                + ", __c AS (SELECT " + keySel + "__t, __x - avg(__x) OVER (" + part.trim() + ") AS __d FROM __s)"
                + ", __den AS (SELECT " + keySel + "count(*) AS n, sum(__d * __d) AS __ss FROM __c" + groupBy(keys) + ")"
                + ", __lags AS (SELECT UNNEST([" + lagList + "]) AS lag)"
                + ", __p AS (SELECT " + cols(keys, "a.") + (keys.isEmpty() ? "" : ", ") + "l.lag, sum(a.__d * b.__d) AS __num"
                + " FROM __c a CROSS JOIN __lags l JOIN __c b ON b.__t = a.__t + l.lag" + on
                + " GROUP BY ALL)"
                + " SELECT " + cols(keys, "d.") + (keys.isEmpty() ? "" : ", ") + "d.n, l.lag, COALESCE(p.__num, 0) / NULLIF(d.__ss, 0) AS acf"
                + " FROM __den d CROSS JOIN __lags l LEFT JOIN __p p ON p.lag = l.lag" + onDen
                + " ORDER BY " + (keys.isEmpty() ? "" : cols(keys, "d.") + ", ") + "l.lag";
    }

    private static String cols(List<String> keys, String prefix) {
        StringBuilder s = new StringBuilder();
        for (String k : keys) s.append(s.length() == 0 ? "" : ", ").append(prefix).append(SqlIdent.q(k));
        return s.toString();
    }

    private static String groupBy(List<String> keys) {
        return keys.isEmpty() ? "" : " GROUP BY " + cols(keys, "");
    }

    private static int[] checked(int[] candidates) {
        Objects.requireNonNull(candidates, "candidates");
        for (int k : candidates) if (k < 2) throw new IllegalArgumentException("a candidate period must be >= 2: " + k);
        int[] sorted = candidates.clone();
        Arrays.sort(sorted);
        for (int i = 1; i < sorted.length; i++)
            if (sorted[i] == sorted[i - 1]) throw new IllegalArgumentException("duplicate candidate period " + sorted[i]);
        return sorted;
    }
}
