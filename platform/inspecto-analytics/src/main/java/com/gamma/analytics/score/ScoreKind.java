package com.gamma.analytics.score;

/**
 * One score kind's output identity: the component kind of its saved model, the prefix every output name derives from
 * (never authored), and the ownership marker file in each output directory, holding the model id.
 *
 * <p>A model {@code m} writes two Datasets: the history {@code <scoresPrefix>m} (one Parquet file per run, one row per
 * entity per run) and {@code <scoresPrefix>m_latest} (swapped to the newest run alone: the relation an Alert Rule
 * watches, so an entity that falls back below a threshold heals instead of staying breached on history).
 *
 * @param kind         the saved model's component kind ({@code risk-score}, {@code anomaly-model})
 * @param scoresPrefix the prefix of every output name ({@code risk_scores_}, {@code anomaly_scores_})
 * @param ownerMarker  the marker file in each output directory ({@code .risk-score-output})
 */
public record ScoreKind(String kind, String scoresPrefix, String ownerMarker) {

    /** The suffix of the newest-run-only Dataset. */
    public static final String LATEST_SUFFIX = "_latest";

    /** The history Dataset of {@code modelId}. */
    public String scores(String modelId) { return scoresPrefix + modelId; }

    /** The newest-run-only Dataset of {@code modelId}. */
    public String latest(String modelId) { return scores(modelId) + LATEST_SUFFIX; }
}
