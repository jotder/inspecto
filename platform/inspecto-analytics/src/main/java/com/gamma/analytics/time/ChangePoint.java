package com.gamma.analytics.time;

import com.gamma.util.SqlIdent;
import java.util.List;
import java.util.Objects;

/**
 * Change-point and regime detection over a residual series (ANALYTICS-CORE-1 C6): the regime-change / drift logic of the
 * Business Assurance forecast template, lifted out of its Holt-Winters recursion into a function of the residuals
 * {@code e_t = actual_t − expected_t} that any model can feed. A JVM function ({@link #detect}) and a DuckDB SQL builder
 * ({@link #sql}) that share one rule, held equal by {@code ChangePointDuckDbParityTest}.
 *
 * <p><b>Rule</b> — per point, in order, with {@code a_t = e_t − offset} (offset 0 unless {@link Params#rebase}), σ the
 * residual scale the caller states:
 * <ol>
 *   <li><b>outside</b>: |a_t| &gt; z·σ.</li>
 *   <li><b>run</b>: the count of consecutive outside points ({@code run_at}); a non-outside point ends it.</li>
 *   <li><b>regime change</b>: {@code run_at ≥ regimeRun} — raised once, on the K-th consecutive outside point, then the
 *       run count restarts at 0.</li>
 *   <li><b>CUSUM</b> (two-sided, over inside points only — an outside point leaves the sums untouched, so a spike or a
 *       level shift never feeds them): {@code S⁺ ← max(0, S⁺ + a/σ − k)}, {@code S⁻ ← max(0, S⁻ − a/σ − k)}.</li>
 *   <li><b>drift</b>: an inside point, not a regime change, with {@code S⁺ > h} or {@code S⁻ > h}.</li>
 *   <li>After a regime change or a drift: run and both sums restart at 0 (and with {@link Params#rebase}, the offset
 *       moves by {@code a_t}, so the new level reads as residual 0 — the level reset the template's forecaster does
 *       itself).</li>
 * </ol>
 * <b>outside_band</b> (the reported band breach) is an outside point in a run of consecutive outside points whose longest
 * {@code run_at} is below {@code regimeRun} — a run that became a regime change is that one event, not K−1 band breaches
 * plus one. It looks at the whole run, so it is a retrospective flag (as the template's). Points before
 * {@link Params#warmup()} update the state but are reported {@code warmup} with no flag.
 *
 * <p>The defaults ({@code z = 4}, {@code regimeRun = 3}, {@code cusumK = 0.25}, {@code cusumH = 8}) are the template's,
 * tuned on its corpus. Without {@code rebase}, a model that does not re-level after a regime change keeps producing
 * large residuals, and the rule raises another regime change every {@code regimeRun} points: either pass residuals
 * from a model that re-levels (as the template does) or set {@code rebase}.
 *
 * <p>Two-sided only; a drift's slope is not estimated (the template's forecaster re-trends itself). The residual series
 * is evenly spaced with no gaps and no NaN. σ is the caller's, e.g. {@link #scaleOfDifferences}.
 */
public final class ChangePoint {

    /** Longest series accepted (fails closed beyond it). */
    public static final int MAX_SERIES_LENGTH = 1_000_000;

    private ChangePoint() {}

    /**
     * @param sigma     the residual scale σ, &gt; 0
     * @param z         the band half-width in σ, &gt; 0
     * @param regimeRun consecutive outside points that make a regime change, ≥ 2
     * @param cusumK    the CUSUM allowance k in σ, ≥ 0
     * @param cusumH    the CUSUM decision limit h in σ, &gt; 0
     * @param warmup    leading points that update the state but carry no flag, ≥ 0
     * @param rebase    after a regime change or drift, measure later residuals from the new level
     */
    public record Params(double sigma, double z, int regimeRun, double cusumK, double cusumH, int warmup, boolean rebase) {
        public Params {
            if (!(sigma > 0) || Double.isInfinite(sigma)) throw new IllegalArgumentException("sigma must be > 0: " + sigma);
            if (!(z > 0)) throw new IllegalArgumentException("z must be > 0: " + z);
            if (regimeRun < 2) throw new IllegalArgumentException("regimeRun must be >= 2: " + regimeRun);
            if (!(cusumK >= 0)) throw new IllegalArgumentException("cusumK must be >= 0: " + cusumK);
            if (!(cusumH > 0)) throw new IllegalArgumentException("cusumH must be > 0: " + cusumH);
            if (warmup < 0) throw new IllegalArgumentException("warmup must be >= 0: " + warmup);
        }

        /** The template's constants for a given σ: z 4, K 3, k 0.25, h 8, no warm-up, no rebase. */
        public static Params of(double sigma) {
            return new Params(sigma, 4.0, 3, 0.25, 8.0, 0, false);
        }

        public Params withRebase(boolean r) {
            return new Params(sigma, z, regimeRun, cusumK, cusumH, warmup, r);
        }

        public Params withWarmup(int w) {
            return new Params(sigma, z, regimeRun, cusumK, cusumH, w, rebase);
        }
    }

    /** Per point: the four template flags (all false while {@code warmup}). Arrays are parallel to the input. */
    public record Result(boolean[] outsideBand, boolean[] regimeChange, boolean[] driftChange, boolean[] warmup) {}

    /** Detects regime changes and drift in {@code residuals} (each finite). */
    public static Result detect(double[] residuals, Params p) {
        Objects.requireNonNull(residuals, "residuals");
        Objects.requireNonNull(p, "p");
        int n = residuals.length;
        if (n > MAX_SERIES_LENGTH)
            throw new IllegalArgumentException("change point: series of " + n + " values exceeds the cap of " + MAX_SERIES_LENGTH);
        boolean[] outside = new boolean[n], regime = new boolean[n], drift = new boolean[n];
        int[] runAt = new int[n];
        double offset = 0, sp = 0, sn = 0;
        int run = 0;
        for (int t = 0; t < n; t++) {
            if (!Double.isFinite(residuals[t])) throw new IllegalArgumentException("non-finite residual at " + t);
            double a = residuals[t] - offset;
            boolean o = Math.abs(a) > p.z() * p.sigma();
            int at = o ? run + 1 : 0;
            double sp1 = o ? sp : Math.max(0.0, sp + a / p.sigma() - p.cusumK());
            double sn1 = o ? sn : Math.max(0.0, sn - a / p.sigma() - p.cusumK());
            boolean reg = at >= p.regimeRun();
            boolean dr = !o && !reg && (sp1 > p.cusumH() || sn1 > p.cusumH());
            outside[t] = o;
            regime[t] = reg;
            drift[t] = dr;
            runAt[t] = at;
            if (reg || dr) {
                run = 0;
                sp = 0;
                sn = 0;
                if (p.rebase()) offset += a;
            } else {
                run = at;
                sp = sp1;
                sn = sn1;
            }
        }
        // a run of consecutive outside points is reported as a band breach only if its longest run_at stayed below K
        boolean[] band = new boolean[n], warm = new boolean[n];
        for (int s = 0; s < n; ) {
            if (!outside[s]) {
                s++;
                continue;
            }
            int e = s, longest = 0;
            while (e < n && outside[e]) longest = Math.max(longest, runAt[e++]);
            if (longest < p.regimeRun()) for (int i = s; i < e; i++) band[i] = true;
            s = e;
        }
        for (int t = 0; t < n; t++) {
            if (t < p.warmup()) {
                warm[t] = true;
                band[t] = false;
                regime[t] = false;
                drift[t] = false;
            }
        }
        return new Result(band, regime, drift, warm);
    }

    /**
     * σ of a series' residuals estimated from its own {@code lag}-differences, the way the template does:
     * {@code 1.4826 · MAD(d) / √2} with {@code d_t = x_t − x_{t−lag}} (the difference of two independent equal-variance
     * errors has variance 2σ²; MAD is the median absolute deviation from the median). Differencing removes a trend
     * (lag 1) or a season (lag = the period) so the scale is not inflated by them. Needs more than {@code lag} points.
     */
    public static double scaleOfDifferences(double[] x, int lag) {
        Objects.requireNonNull(x, "x");
        if (lag < 1) throw new IllegalArgumentException("lag must be >= 1: " + lag);
        if (x.length <= lag) throw new IllegalArgumentException("need more than " + lag + " points, got " + x.length);
        java.util.List<Double> d = new java.util.ArrayList<>(x.length - lag);
        for (int t = lag; t < x.length; t++) d.add(x[t] - x[t - lag]);
        double med = com.gamma.analytics.baseline.RobustStats.median(d);
        return com.gamma.analytics.baseline.RobustStats.scaledMad(d, med) / Math.sqrt(2.0);
    }

    // ── DuckDB SQL ───────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * DuckDB SQL for {@link #detect} per key: columns {@code <keys…>, <orderColumn>, <residualColumn>, outside_band,
     * regime_change, drift_change, warmup} (the flags 0/1), ordered by keys then {@code orderColumn}. A series is the
     * key's rows ordered by {@code orderColumn} (unique per key, evenly spaced); a row with a NULL residual is left out.
     * {@code sigmaColumn} holds σ on every row of a key (constant per key, &gt; 0); {@code p.sigma()} is not used here.
     * A recursive CTE, one iteration per point: keep a series to the {@link #MAX_SERIES_LENGTH}-scale it is run for.
     */
    public static String sql(String relation, String orderColumn, String residualColumn, String sigmaColumn,
                             List<String> keys, Params p) {
        Objects.requireNonNull(relation, "relation");
        Objects.requireNonNull(orderColumn, "orderColumn");
        Objects.requireNonNull(residualColumn, "residualColumn");
        Objects.requireNonNull(sigmaColumn, "sigmaColumn");
        keys = List.copyOf(keys);
        String ord = SqlIdent.q(orderColumn), res = SqlIdent.q(residualColumn);
        String k = list(keys, "", ", "), part = keys.isEmpty() ? "" : "PARTITION BY " + list(keys, "", ", ").replaceAll(", $", "") + " ";
        String joinOn = "";
        for (String key : keys) joinOn += " AND s." + SqlIdent.q(key) + " IS NOT DISTINCT FROM st." + SqlIdent.q(key);
        String z = lit(p.z()), kk = lit(p.cusumK()), h = lit(p.cusumH());
        int regimeRun = p.regimeRun();

        String s = "SELECT " + k + ord + ", " + res + ", row_number() OVER (" + part + "ORDER BY " + ord + ") AS __t, CAST("
                + res + " AS DOUBLE) AS __e, CAST(" + SqlIdent.q(sigmaColumn) + " AS DOUBLE) AS __sg FROM "
                + SqlIdent.q(relation) + " WHERE " + res + " IS NOT NULL";
        String base = "SELECT " + list(keys, "", ", ") + "CAST(0 AS BIGINT) AS __t, 0 AS __o, 0 AS __run_at, 0 AS __reg, 0 AS __dr, "
                + "CAST(0 AS DOUBLE) AS __off, 0 AS __run, CAST(0 AS DOUBLE) AS __sp, CAST(0 AS DOUBLE) AS __sn FROM (SELECT DISTINCT "
                + (keys.isEmpty() ? "1 AS __one" : list(keys, "", ", ").replaceAll(", $", "")) + " FROM __s)";
        String q0 = "SELECT " + list(keys, "st.", ", ") + "s.__t AS __t, s.__e - st.__off AS __a, s.__sg AS __sg, st.__off AS __poff, "
                + "st.__run AS __prun, st.__sp AS __psp, st.__sn AS __psn FROM __st st JOIN __s s ON s.__t = st.__t + 1" + joinOn;
        String q1 = "SELECT *, CASE WHEN abs(__a) > " + z + " * __sg THEN 1 ELSE 0 END AS __o FROM (" + q0 + ") q0";
        String q2 = "SELECT *, CASE WHEN __o = 1 THEN __prun + 1 ELSE 0 END AS __run_at, CASE WHEN __o = 1 THEN __psp ELSE "
                + "greatest(CAST(0 AS DOUBLE), __psp + __a / __sg - " + kk + ") END AS __sp1, CASE WHEN __o = 1 THEN __psn ELSE "
                + "greatest(CAST(0 AS DOUBLE), __psn - __a / __sg - " + kk + ") END AS __sn1 FROM (" + q1 + ") q1";
        String q3 = "SELECT *, CASE WHEN __run_at >= " + regimeRun + " THEN 1 ELSE 0 END AS __reg, CASE WHEN __o = 0 AND __run_at < "
                + regimeRun + " AND (__sp1 > " + h + " OR __sn1 > " + h + ") THEN 1 ELSE 0 END AS __dr FROM (" + q2 + ") q2";
        String step = "SELECT " + list(keys, "", ", ") + "__t, __o, __run_at, __reg, __dr, CASE WHEN __reg = 1 OR __dr = 1 THEN "
                + (p.rebase() ? "__poff + __a" : "__poff") + " ELSE __poff END AS __off, CASE WHEN __reg = 1 OR __dr = 1 THEN 0 ELSE "
                + "__run_at END AS __run, CASE WHEN __reg = 1 OR __dr = 1 THEN CAST(0 AS DOUBLE) ELSE __sp1 END AS __sp, "
                + "CASE WHEN __reg = 1 OR __dr = 1 THEN CAST(0 AS DOUBLE) ELSE __sn1 END AS __sn FROM (" + q3 + ") q3";

        String grp = "SELECT *, sum(CASE WHEN __o = 0 THEN 1 ELSE 0 END) OVER (" + part + "ORDER BY __t) AS __grp FROM __st WHERE __t > 0";
        // run_len partitions by keys AND the run group
        String partGrp = "PARTITION BY " + list(keys, "", ", ") + "__grp";
        String len = "SELECT *, max(__run_at) OVER (" + partGrp + ") AS __run_len FROM __g";

        String joinBack = "";
        for (String key : keys) joinBack += " AND l." + SqlIdent.q(key) + " IS NOT DISTINCT FROM s." + SqlIdent.q(key);
        String live = "s.__t > " + p.warmup();
        return "WITH RECURSIVE __s AS (" + s + "), __st AS (" + base + " UNION ALL " + step + "), __g AS (" + grp + "), __l AS ("
                + len + ") SELECT " + list(keys, "s.", ", ") + "s." + ord + ", s." + res + ", CASE WHEN " + live
                + " AND l.__o = 1 AND l.__run_len < " + regimeRun + " THEN 1 ELSE 0 END AS outside_band, CASE WHEN " + live
                + " AND l.__reg = 1 THEN 1 ELSE 0 END AS regime_change, CASE WHEN " + live
                + " AND l.__dr = 1 THEN 1 ELSE 0 END AS drift_change, CASE WHEN s.__t > " + p.warmup()
                + " THEN 0 ELSE 1 END AS warmup FROM __s s JOIN __l l ON l.__t = s.__t" + joinBack + " ORDER BY "
                + list(keys, "s.", ", ") + "s." + ord;
    }

    private static String list(List<String> keys, String prefix, String sep) {
        StringBuilder b = new StringBuilder();
        for (String k : keys) b.append(prefix).append(SqlIdent.q(k)).append(sep);
        return b.toString();
    }

    /** A finite double as a SQL DOUBLE literal (Java's shortest round-trip form, so the two forms read the same value). */
    private static String lit(double v) {
        return "CAST(" + Double.toString(v) + " AS DOUBLE)";
    }
}
