package com.gamma.predictive;

import com.gamma.analytics.QuantileMode;
import com.gamma.analytics.distribution.Binning;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The pure maths of a Segmentation Model run (PREDICTIVE-SEGMENT-RFM-1): quantile bins per dimension over the run's
 * entity population, a 1..bins score per dimension, and the label of the first matching row of the model's table.
 *
 * <p>Bins are the Analytics Kernel's quantile bins ({@link Binning#quantileEdges} in {@link QuantileMode#CONTINUOUS},
 * {@link Binning#quantileIndex}): deterministic, ties always share a bin and a value equal to an edge goes to the
 * higher bin (never {@code ntile}, which splits ties). Score = bin + 1, so higher is better on every dimension:
 * frequency and monetary are binned on their own value, recency on <b>minus</b> its days (fewer days since the last
 * activity is better). The edges of the dimension are stored in every row's explanation, so
 * {@link #recompute} re-derives the scores and the label from a stored row alone.
 */
public final class RfmSegmenter {
    private RfmSegmenter() {}

    /** One entity's windowed inputs: days since its last activity, its frequency and its monetary value. */
    public record Rfm(String entityKey, long recencyDays, double frequency, double monetary) {}

    /** One dimension's part of an assignment: the binned quantity, its score and the run's edges. */
    public record Part(double value, int score, double[] edges) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("value", value);
            m.put("score", score);
            m.put("edges", Arrays.stream(edges).boxed().toList());
            return m;
        }
    }

    /** One Customer Segment assignment. {@code recency}'s binned value is {@code -recencyDays}. */
    public record Assignment(Rfm input, Part recency, Part frequency, Part monetary, String segment) {
        /** The stored explanation: each dimension's {value, score, edges}, in R, F, M order. */
        public Map<String, Object> explanation() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("recency", recency.toMap());
            m.put("frequency", frequency.toMap());
            m.put("monetary", monetary.toMap());
            return m;
        }
    }

    /** Assign every entity of {@code population}, in its order. An empty population assigns nothing. */
    public static List<Assignment> assign(SegmentationModel model, List<Rfm> population) {
        if (population.isEmpty()) return List.of();
        int n = population.size();
        double[] r = new double[n], f = new double[n], m = new double[n];
        for (int i = 0; i < n; i++) {
            Rfm x = population.get(i);
            r[i] = -x.recencyDays();
            f[i] = x.frequency();
            m[i] = x.monetary();
        }
        double[] re = edges(r, model.bins()), fe = edges(f, model.bins()), me = edges(m, model.bins());
        List<Assignment> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Part rp = part(r[i], re), fp = part(f[i], fe), mp = part(m[i], me);
            out.add(new Assignment(population.get(i), rp, fp, mp, model.labelOf(rp.score(), fp.score(), mp.score())));
        }
        return out;
    }

    /** The {@code bins + 1} quantile edges of {@code values}. */
    static double[] edges(double[] values, int bins) {
        return Binning.quantileEdges(values, bins, QuantileMode.CONTINUOUS);
    }

    static Part part(double value, double[] edges) {
        return new Part(value, Binning.quantileIndex(value, edges) + 1, edges);
    }

    /**
     * Re-derive the three scores and the label from a stored explanation ({@link Assignment#explanation()}, read back
     * from JSON) and the model: {@code [segment, recencyScore, frequencyScore, monetaryScore]}. A stored row whose
     * scores or label disagree with its own values and edges recomputes to something else.
     */
    public static List<Object> recompute(SegmentationModel model, Map<String, Object> explanation) {
        int r = rescore(explanation, "recency"), f = rescore(explanation, "frequency"), m = rescore(explanation, "monetary");
        return List.of(model.labelOf(r, f, m), r, f, m);
    }

    private static int rescore(Map<String, Object> explanation, String dimension) {
        if (!(explanation.get(dimension) instanceof Map<?, ?> part)
                || !(part.get("value") instanceof Number v) || !(part.get("edges") instanceof List<?> raw))
            throw new IllegalArgumentException("explanation has no " + dimension + " {value, edges}");
        double[] edges = new double[raw.size()];
        for (int i = 0; i < edges.length; i++) edges[i] = ((Number) raw.get(i)).doubleValue();
        return Binning.quantileIndex(v.doubleValue(), edges) + 1;
    }
}
