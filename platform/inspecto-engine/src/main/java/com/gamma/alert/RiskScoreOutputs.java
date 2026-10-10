package com.gamma.alert;

import java.nio.file.Path;

/**
 * The naming and ownership facts of a Risk Score's output stores, kept in the core because the core's pending
 * Alert Rule feature (TEMPLATE-RISK-SCORE-ALERT-RULE-1) reads a model's {@code _latest} store whether or not the
 * optional Scoring module is installed. The scoring module itself ({@code com.gamma.risk}) writes through the same
 * constants, so the two cannot drift.
 */
public final class RiskScoreOutputs {
    private RiskScoreOutputs() {}

    /** The component kind of a saved Risk Score model. */
    public static final String KIND = "risk-score";
    /** The prefix of every scores Dataset — the output name is derived, never authored. */
    public static final String SCORES_PREFIX = "risk_scores_";
    public static final String LATEST_SUFFIX = "_latest";
    /** The ownership marker in each scores directory, holding the model id. */
    public static final String OWNER_MARKER = ".risk-score-output";

    /** Whether {@code dir} is a scores directory THIS model created (its marker names the model). */
    public static boolean ownedBy(Path dir, String modelId) {
        return ScoreOutputDirs.ownedBy(dir, OWNER_MARKER, modelId);
    }
}
