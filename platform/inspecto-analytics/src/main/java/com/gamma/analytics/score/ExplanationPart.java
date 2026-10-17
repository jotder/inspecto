package com.gamma.analytics.score;

import java.util.Map;

/**
 * One element of a score's explanation: a Risk Score factor, an Anomaly Score feature. A stored row's explanation
 * column is the JSON array of {@link #toMap()} of its parts, in the kind's order, and every part carries its
 * {@code weight} and {@code contribution}. The score is recomputable from the explanation alone (each kind's
 * {@code recompute}), so a row whose numbers disagree with their own inputs recomputes to a different score.
 */
public interface ExplanationPart {

    /** This part as stored: an ordered map that serialises to one JSON object. */
    Map<String, Object> toMap();

    double weight();

    double contribution();
}
