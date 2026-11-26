package com.gamma.analytics.insight;

import java.util.Arrays;
import java.util.Comparator;

/**
 * Benjamini-Hochberg false-discovery control (BI-INSIGHTS-1 I-8): the scan tests every slice × Measure × test, so
 * reporting each p &lt; 0.05 would report about one slice in twenty by chance alone. The BH-adjusted value (the
 * q-value) of the i-th smallest of m p-values is {@code min_{j≥i} p_(j)·m ÷ j}; keeping q ≤ α keeps the expected share
 * of false findings among those reported at α or below. Pure.
 */
public final class FalseDiscovery {

    private FalseDiscovery() {}

    /** The BH-adjusted p-values (q-values), in the input's order. A NaN p is treated as 1. */
    public static double[] adjusted(double[] p) {
        int m = p.length;
        double[] q = new double[m];
        if (m == 0) return q;
        Integer[] order = new Integer[m];
        for (int i = 0; i < m; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingDouble(i -> Double.isNaN(p[i]) ? 1 : p[i]));
        double running = 1;
        for (int r = m - 1; r >= 0; r--) {
            int i = order[r];
            double pi = Double.isNaN(p[i]) ? 1 : p[i];
            running = Math.min(running, pi * m / (r + 1));
            q[i] = Math.min(1, running);
        }
        return q;
    }
}
