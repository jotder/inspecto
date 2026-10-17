package com.gamma.analytics;

/**
 * The DuckDB SQL forms of the {@link Stats} definitions a caller computes set-based, over data too large to bring into
 * the JVM. Each method names the one DuckDB function that matches its {@link Stats} twin ({@code StatsDuckDbParityTest}
 * proves the pair agree). {@code expr} is the caller's already-quoted column or expression; the result is an aggregate
 * (or, for {@link #zScore}, a row-level) expression the caller places in its own statement.
 */
public final class StatsSql {

    private StatsSql() {}

    /** {@link Stats#mean}: {@code avg(expr)}. */
    public static String mean(String expr) {
        return "avg(" + expr + ")";
    }

    /** {@link Stats#stddevPopulation}: {@code stddev_pop(expr)} — divides by n. */
    public static String stddevPopulation(String expr) {
        return "stddev_pop(" + expr + ")";
    }

    /** {@link Stats#stddevSample}: {@code stddev_samp(expr)} — divides by n − 1; NULL below two values. */
    public static String stddevSample(String expr) {
        return "stddev_samp(" + expr + ")";
    }

    /**
     * {@link Stats#zScore}: {@code ((expr - mean) / stddev)}, the two figures rendered as SQL number literals. Same rule
     * as the JVM form: {@code mean} finite, {@code stddev} finite and &gt; 0, otherwise {@link IllegalArgumentException}.
     */
    public static String zScore(String expr, double mean, double stddev) {
        Stats.checkZ(mean, stddev);
        return "((" + expr + " - " + mean + ") / " + stddev + ")";
    }
}
