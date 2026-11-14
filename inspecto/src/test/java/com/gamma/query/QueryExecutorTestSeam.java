package com.gamma.query;

/** Lets another module's tests set {@link QueryExecutor}'s package-private {@code beforeScan} seam; {@code null} resets it. */
public final class QueryExecutorTestSeam {

    private QueryExecutorTestSeam() { }

    public static void beforeScan(Runnable run) {
        QueryExecutor.beforeScan = run == null ? () -> { } : run;
    }
}
