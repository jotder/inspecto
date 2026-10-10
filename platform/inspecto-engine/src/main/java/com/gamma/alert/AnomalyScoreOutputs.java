package com.gamma.alert;

import java.nio.file.Path;

/**
 * The naming and ownership facts of an Anomaly Score's output stores (ANOMALY-DETECTION-1), kept in the core beside
 * {@link RiskScoreOutputs} because the core's deferred Alert Rule seed (D-AD7, {@code afterScore: {kind, model}})
 * reads a model's {@code _latest} store whether or not the optional anomaly module is installed. The anomaly module
 * ({@code com.gamma.anomaly.AnomalyModel}) writes through the same constants, so the two cannot drift.
 */
public final class AnomalyScoreOutputs {
    private AnomalyScoreOutputs() {}

    /** The component kind of a saved Anomaly Model. */
    public static final String KIND = "anomaly-model";
    /** The prefix of every Anomaly Score store — the output name is derived, never authored. */
    public static final String SCORES_PREFIX = "anomaly_scores_";
    public static final String LATEST_SUFFIX = "_latest";
    /** The ownership marker in each scores directory, holding the model id. */
    public static final String OWNER_MARKER = ".anomaly-score-output";

    /** Whether {@code dir} is a scores directory THIS model created (its marker names the model). */
    public static boolean ownedBy(Path dir, String modelId) {
        return ScoreOutputDirs.ownedBy(dir, OWNER_MARKER, modelId);
    }
}
