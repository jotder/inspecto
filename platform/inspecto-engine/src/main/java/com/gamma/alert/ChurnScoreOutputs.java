package com.gamma.alert;

import com.gamma.analytics.score.ScoreKind;

import java.nio.file.Path;

/**
 * The naming and ownership facts of a Churn Score's output stores (PREDICTIVE-CHURN-DECLARED-1), kept in the core
 * beside {@link AnomalyScoreOutputs} because the core's deferred Alert Rule seed ({@code afterScore: {kind, model}})
 * reads a model's {@code _latest} store whether or not the optional predictive module is installed. The predictive
 * module ({@code com.gamma.predictive.ChurnModel}) writes through the same constants, so the two cannot drift.
 */
public final class ChurnScoreOutputs {
    private ChurnScoreOutputs() {}

    /** The component kind of a saved Churn Model. */
    public static final String KIND = "churn-model";
    /** The prefix of every Churn Score store — the output name is derived, never authored. */
    public static final String SCORES_PREFIX = "churn_scores_";
    public static final String LATEST_SUFFIX = ScoreKind.LATEST_SUFFIX;
    /** The ownership marker in each scores directory, holding the model id. */
    public static final String OWNER_MARKER = ".churn-model-output";

    /** This kind's output identity in the score output contract (ANALYTICS-CORE-1 M7). */
    public static final ScoreKind SCORE_KIND = new ScoreKind(KIND, SCORES_PREFIX, OWNER_MARKER);

    /** Whether {@code dir} is a scores directory THIS model created (its marker names the model). */
    public static boolean ownedBy(Path dir, String modelId) {
        return ScoreOutputDirs.ownedBy(dir, OWNER_MARKER, modelId);
    }
}
