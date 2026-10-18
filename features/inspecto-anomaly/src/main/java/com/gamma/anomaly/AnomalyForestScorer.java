package com.gamma.anomaly;

import com.gamma.analytics.model.IsolationForest;
import com.gamma.analytics.score.ScoreScale;
import com.gamma.graph.RunControl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The isolation-forest scoring option of an Anomaly Model (ANALYTICS-CORE-1 C4, ANOMALY-ML-SCORING-1): pure, no DuckDB.
 * It takes the entities already scored by {@link AnomalyScorer} — whose per-feature {@code deviation}s (the robust z kept
 * to the declared direction, held to {@code zCap}, self and peer baselines included) are the model's evidence — and
 * replaces the combination step: instead of the weighted root-mean-square, a seeded {@link IsolationForest} over the
 * population's deviation vectors says how easily each entity is isolated. A <b>multivariate</b> view: an entity that is
 * mildly off on several features at once, a combination few others show, can score higher than one that is far off on a
 * single feature that many entities share. The robust-statistical score is not changed by this class; it is the other
 * option, chosen per model ({@code method: isolation_forest}).
 *
 * <ul>
 *   <li>{@code raw} = the forest's {@linkplain IsolationForest.Path#excess() excess} over typical, in [0, 1];
 *       {@code score = 100 · raw}, so the model's elevated / high thresholds read on the same 0–100 scale as ever.</li>
 *   <li><b>Explanation kept, per feature</b> (the same {@link AnomalyScorer.FeatureResult} elements: observed, baseline,
 *       z, deviation, direction, weight, reason): {@code contribution = share · raw} where {@code share} is the feature's
 *       counterfactual share of the isolation ({@link IsolationForest#attribute}, reference = no deviation). The
 *       contributions sum to {@code raw}, so {@link #recompute} rebuilds {@code {raw, score}} from the stored explanation
 *       alone; the features are listed largest contribution first (evidence follows the top one).</li>
 *   <li>Feature weights steer which feature a split uses (weight-proportional), not the score's arithmetic.</li>
 *   <li>Fewer than two entities: nothing to isolate from — every score 0.</li>
 *   <li>Deterministic: entities are taken in key order, the forest is seeded ({@link AnomalyModel.Forest#seed()}); the
 *       seed and parameters are part of the model content, hence of its {@code model_version}.</li>
 * </ul>
 * Fitting happens in the Job only (W7): the preview route refuses an isolation-forest model.
 */
public final class AnomalyForestScorer {
    private AnomalyForestScorer() {}

    /** Re-score {@code robust} (one per entity, from {@link AnomalyScorer#score}) with the model's forest; {@code maxEntities} is the run's cap (fails closed past it). */
    public static List<AnomalyScorer.Scored> score(AnomalyModel model, List<AnomalyScorer.Scored> robust, int maxEntities,
                                                   RunControl control) {
        AnomalyModel.Forest spec = model.forest();
        if (spec == null) throw new IllegalArgumentException("anomaly-model '" + model.id() + "' has no isolation forest");
        List<AnomalyScorer.Scored> sorted = new ArrayList<>(robust);
        sorted.sort(Comparator.comparing(AnomalyScorer.Scored::entityKey));
        int d = model.features().size();
        double[] weights = new double[d];
        for (int j = 0; j < d; j++) weights[j] = model.features().get(j).weight();
        double[][] points = new double[sorted.size()][];
        for (int i = 0; i < points.length; i++) {
            Map<String, Double> dev = new HashMap<>();
            for (AnomalyScorer.FeatureResult f : sorted.get(i).features()) dev.put(f.feature(), f.deviation());
            points[i] = new double[d];
            for (int j = 0; j < d; j++) points[i][j] = dev.getOrDefault(model.features().get(j).id(), 0.0);
        }
        IsolationForest forest = sorted.size() < 2 ? null
                : IsolationForest.fit(points, weights, new IsolationForest.Spec(spec.trees(), spec.sampleSize(), spec.seed(),
                        maxEntities), control);
        double[] reference = new double[d];
        List<AnomalyScorer.Scored> out = new ArrayList<>(sorted.size());
        for (int i = 0; i < points.length; i++) {
            AnomalyScorer.Scored s = sorted.get(i);
            double raw = forest == null ? 0.0 : forest.path(points[i]).excess();
            double[] shares = forest == null ? new double[d] : forest.attribute(points[i], reference);
            Map<String, Double> shareOf = new HashMap<>();
            for (int j = 0; j < d; j++) shareOf.put(model.features().get(j).id(), shares[j]);
            List<AnomalyScorer.FeatureResult> features = new ArrayList<>(s.features().size());
            for (AnomalyScorer.FeatureResult f : s.features()) {
                double share = shareOf.getOrDefault(f.feature(), 0.0);
                features.add(new AnomalyScorer.FeatureResult(f.feature(), f.label(), f.bucket(), f.observed(), f.baseline(),
                        f.peerBaseline(), f.cohortShift(), f.unit(), f.zSelf(), f.zPeer(), f.deviation(), f.direction(),
                        f.weight(), share * raw, share, f.peersOnly(), f.insufficient(),
                        f.reason() + " | isolation share " + Math.round(share * 100) + " %", f.evidence()));
            }
            features.sort((a, b) -> Double.compare(b.contribution(), a.contribution()));
            double score = ScoreScale.clamp(100.0 * raw);
            out.add(new AnomalyScorer.Scored(s.entityKey(), score, model.band(score), raw, List.copyOf(features),
                    s.insufficientCount()));
        }
        return out;
    }

    /** Re-derive {@code {raw, score}} from a stored explanation ({@link AnomalyScorer.FeatureResult#toMap} elements): {@code raw = Σ contribution}, {@code score = 100 · raw}. */
    public static double[] recompute(List<Map<String, Object>> features) {
        double raw = 0;
        for (Map<String, Object> f : features) {
            if (!(f.get("contribution") instanceof Number n))
                throw new IllegalArgumentException("explanation value is not numeric: " + f.get("contribution"));
            raw += n.doubleValue();
        }
        return new double[]{raw, ScoreScale.clamp(100.0 * raw)};
    }

    /** The scoring method as the model names it ({@code robust_z} or {@code isolation_forest}). */
    public static String method(AnomalyModel model) {
        return (model.forest() == null ? "robust_z" : "isolation_forest").toLowerCase(Locale.ROOT);
    }
}
