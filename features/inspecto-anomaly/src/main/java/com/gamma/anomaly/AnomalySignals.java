package com.gamma.anomaly;

/**
 * This module's dotted Signal types (per-module constants, operator 2026-10-09). Pinned byte-for-byte by
 * {@code AnomalySignalsTest}. D-AD7 moves it to core {@code SignalType} when the deferred Alert Rule seed lands.
 */
public final class AnomalySignals {

    private AnomalySignals() {}

    /** Signal type {@code anomaly.score.produced}: payload model, run, scored, elevated, high, insufficient. */
    public static final String ANOMALY_SCORE_PRODUCED = "anomaly.score.produced";
}
