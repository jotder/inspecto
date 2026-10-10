package com.gamma.anomaly;

/**
 * This module's dotted Signal types (per-module constants, operator 2026-10-09). Pinned byte-for-byte by
 * {@code AnomalySignalsTest}. A cross-module contract since the deferred Alert Rule seed (D-AD7): it aliases core
 * {@code SignalType.ANOMALY_SCORE_PRODUCED}, which {@code CollectorService} matches, so the two cannot drift.
 */
public final class AnomalySignals {

    private AnomalySignals() {}

    /** Signal type {@code anomaly.score.produced}: payload model, run, scored, elevated, high, insufficient. */
    public static final String ANOMALY_SCORE_PRODUCED = com.gamma.signal.SignalType.ANOMALY_SCORE_PRODUCED;
}
