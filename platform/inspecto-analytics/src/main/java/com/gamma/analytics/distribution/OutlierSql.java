package com.gamma.analytics.distribution;

import com.gamma.analytics.ColumnProfiler;
import com.gamma.analytics.QuantileMode;
import com.gamma.analytics.Stats;
import com.gamma.util.SqlIdent;

import java.util.List;
import java.util.Objects;

/**
 * Row-level outlier flags in DuckDB SQL (ANALYTICS-CORE-1 S3, Step {@code transform.stats.outlier}), built ON the
 * {@link Stats} definitions — no statistic of its own. Every row of the relation is kept and gains its group's figures
 * and a flag; the statistics are computed per group (window {@code PARTITION BY}, a NULL key is a group of its own) over
 * the group's non-missing values ({@link DistributionSql#numeric}: NULL and NaN are missing).
 *
 * <ul>
 *   <li>{@link Method#IQR} — {@link Stats#tukeyFences(double[], double, QuantileMode)}: lower = {@code Q1 − k·(Q3 − Q1)},
 *       upper = {@code Q3 + k·(Q3 − Q1)}, the quartiles {@code quantile_cont} ({@link QuantileMode#CONTINUOUS}, the
 *       default) or {@code quantile_disc}; flag = the value is <b>strictly</b> outside ({@link Stats.Fences#outside}).</li>
 *   <li>{@link Method#ROBUST_Z} — {@link Stats#robustZ(double, double, double, double)}: score =
 *       {@code (x − median) / max(1.4826 · mad, max(unit, 0.05 · |median|))} (DuckDB {@code median}, {@code mad};
 *       the floor is {@link Stats#robustZFloor}); flag = {@code |score| > threshold} (strictly).</li>
 * </ul>
 * A missing value gets a NULL flag (and score); the group figures are still written. A group with no value has NULL
 * figures. {@code OutlierDuckDbParityTest} holds this equal to the JVM definitions.
 */
public final class OutlierSql {

    private OutlierSql() {}

    /** Scratch column names the statement uses internally; the inbound relation must not carry them. */
    public static final List<String> SCRATCH = List.of("__dist_v", "__dist_a", "__dist_b");

    /** How a value is judged an outlier. */
    public enum Method { IQR, ROBUST_Z }

    /**
     * One outlier pass. {@code k} and {@code quantileMode} serve {@link Method#IQR}; {@code threshold} and {@code unit}
     * (&gt; 0, the smallest change that matters) serve {@link Method#ROBUST_Z}. Output names: {@code flagColumn} always;
     * {@code lowerColumn} + {@code upperColumn} (IQR) or {@code scoreColumn} (robust z).
     */
    public record Spec(String column, List<String> groupBy, Method method, double k, QuantileMode quantileMode,
                       double threshold, double unit, String flagColumn, String lowerColumn, String upperColumn,
                       String scoreColumn) {
        public Spec {
            Objects.requireNonNull(column, "column");
            groupBy = List.copyOf(groupBy);
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(quantileMode, "quantileMode");
            if (!(k >= 0) || !Double.isFinite(k)) throw new IllegalArgumentException("fence multiplier must be >= 0, got " + k);
            if (!(threshold > 0) || !Double.isFinite(threshold))
                throw new IllegalArgumentException("robust z threshold must be > 0, got " + threshold);
            if (!(unit > 0) || !Double.isFinite(unit)) throw new IllegalArgumentException("robust z unit must be > 0, got " + unit);
        }

        /** The columns this pass adds, in output order. */
        public List<String> addedColumns() {
            return method == Method.IQR ? List.of(lowerColumn, upperColumn, flagColumn) : List.of(scoreColumn, flagColumn);
        }
    }

    /** Every row of {@code relation} (the caller's already-safe SQL: a quoted table, or a subquery with its alias). */
    public static String select(String relation, Spec spec) {
        String v = SqlIdent.q(SCRATCH.get(0)), a = SqlIdent.q(SCRATCH.get(1)), b = SqlIdent.q(SCRATCH.get(2));
        String over = DistributionSql.over(spec.groupBy());
        String read = "SELECT *, " + DistributionSql.numeric(spec.column()) + " AS " + v + " FROM " + relation;
        String windowed, added;
        if (spec.method() == Method.IQR) {
            // a = Q1, b = Q3; lower and upper exactly as Stats.tukeyFences writes them
            windowed = "SELECT *, " + ColumnProfiler.quantile(v, 0.25, spec.quantileMode()) + over + " AS " + a + ", "
                    + ColumnProfiler.quantile(v, 0.75, spec.quantileMode()) + over + " AS " + b + " FROM (" + read + ")";
            String k = DistributionSql.number(spec.k());
            String lower = "(" + a + " - " + k + " * (" + b + " - " + a + "))";
            String upper = "(" + b + " + " + k + " * (" + b + " - " + a + "))";
            added = lower + " AS " + SqlIdent.q(spec.lowerColumn()) + ", " + upper + " AS " + SqlIdent.q(spec.upperColumn())
                    + ", CASE WHEN " + v + " IS NULL THEN NULL ELSE (" + v + " < " + lower + " OR " + v + " > " + upper
                    + ") END AS " + SqlIdent.q(spec.flagColumn());
        } else {
            // a = median, b = raw MAD; score exactly as Stats.robustZ with Stats.robustZFloor
            windowed = "SELECT *, median(" + v + ")" + over + " AS " + a + ", mad(" + v + ")" + over + " AS " + b
                    + " FROM (" + read + ")";
            String floor = "greatest(" + DistributionSql.number(spec.unit()) + ", "
                    + DistributionSql.number(Stats.ROBUST_Z_FLOOR_SHARE) + " * abs(" + a + "))";
            String score = "((" + v + " - " + a + ") / greatest(" + DistributionSql.number(Stats.MAD_SCALE) + " * " + b
                    + ", " + floor + "))";
            added = score + " AS " + SqlIdent.q(spec.scoreColumn()) + ", CASE WHEN " + v + " IS NULL THEN NULL ELSE abs("
                    + score + ") > " + DistributionSql.number(spec.threshold()) + " END AS " + SqlIdent.q(spec.flagColumn());
        }
        return "SELECT * EXCLUDE (" + v + ", " + a + ", " + b + "), " + added + " FROM (" + windowed + ")";
    }
}
