package com.gamma.risk;

/**
 * This module's dotted Signal types (operator, 2026-10-09: per-module constants; core
 * {@code com.gamma.signal.SignalType} keeps only core names). Pinned byte-for-byte by {@code ScoringSignalsTest}.
 */
public final class ScoringSignals {

    private ScoringSignals() {}

    /** Signal type {@code risk.score.produced} — a cross-module contract, so it aliases the core constant. */
    public static final String RISK_SCORE_PRODUCED = com.gamma.signal.SignalType.RISK_SCORE_PRODUCED;
}
