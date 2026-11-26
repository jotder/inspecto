package com.gamma.analytics.insight;

import com.gamma.analytics.score.ScoreScale;

/**
 * The interestingness score 0–100 of an Insight (BI-INSIGHTS-1 I-8, §6): impact × surprise × novelty, each held to
 * [0, 1], so an Insight scores high only when all three are — a large but expected move, a surprising move in a
 * negligible slice, and a finding already reported all score low.
 *
 * <ul>
 *   <li><b>impact</b> = {@code saturating(|delta| ÷ |Measure total|, IMPACT_SCALE)}: the share of the whole Measure the
 *       move represents (5 % of the total → about 63).</li>
 *   <li><b>surprise</b> = {@code saturating(−log10 q, SURPRISE_SCALE)} over the BH q-value ({@link FalseDiscovery}).</li>
 *   <li><b>novelty</b> = {@code 1 ÷ (1 + k)}, k = how many of the last N runs already reported the same Insight; 0 when
 *       a reader marked it not useful or expected.</li>
 * </ul>
 * Bands use the score contract ({@link ScoreScale#band}) at {@link #ELEVATED} and {@link #HIGH}.
 */
public final class InsightScore {

    private InsightScore() {}

    public static final double IMPACT_SCALE = 0.05, SURPRISE_SCALE = 2.0, ELEVATED = 40, HIGH = 70;

    /** The three factors and their product as a score. */
    public record Parts(double impact, double surprise, double novelty, double score, String band) {}

    public static Parts of(double delta, double measureTotal, double q, double novelty) {
        double share = measureTotal == 0 ? (delta == 0 ? 0 : 1) : Math.abs(delta / measureTotal);
        double impact = ScoreScale.saturating(share, IMPACT_SCALE) / ScoreScale.MAX;
        double surprise = ScoreScale.saturating(-Math.log10(Math.max(q, 1e-12)), SURPRISE_SCALE) / ScoreScale.MAX;
        double nov = Math.max(0, Math.min(1, novelty));
        double score = ScoreScale.clamp(ScoreScale.MAX * impact * surprise * nov);
        return new Parts(impact, surprise, nov, score, ScoreScale.band(score, ELEVATED, HIGH));
    }

    /** {@code 1 ÷ (1 + reportedBefore)}. */
    public static double novelty(int reportedBefore) {
        return 1.0 / (1 + Math.max(0, reportedBefore));
    }
}
