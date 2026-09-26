package com.gamma.risk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Risk Score arithmetic — pure, no I/O, so every score can be recomputed from the factors it carries.
 *
 * <p>Per factor: {@code contribution = weight × indicator}, then held to at most {@code cap} when the factor
 * sets one. A missing indicator (the entity has no rows for that factor, or the aggregate is NULL) counts as
 * {@code 0} and is flagged {@code missing: true} — never silently dropped. The score is
 * {@code Σ contribution} clamped to {@code [0, 100]}.
 *
 * <p>⚠ The cap bounds the CONTRIBUTION, not the raw indicator: "this factor adds at most 40 points" is the
 * sentence an author means, and it is independent of the indicator's unit.
 */
public final class RiskScorer {

    private RiskScorer() {}

    /** One factor's evaluated line, in the shape the {@code factors} JSON column carries. */
    public record FactorResult(String indicator, String label, Double value, boolean missing, double weight,
                               Double cap, boolean capped, double contribution, List<Map<String, Object>> evidence) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("indicator", indicator);
            if (label != null) m.put("label", label);
            m.put("value", value);
            m.put("missing", missing);
            m.put("weight", weight);
            if (cap != null) m.put("cap", cap);
            m.put("capped", capped);
            m.put("contribution", contribution);
            m.put("evidence", evidence);
            return m;
        }
    }

    /** An entity's score and the factors that produced it. */
    public record Scored(String entityKey, double score, boolean high, List<FactorResult> factors) {}

    /** One factor's contribution: {@code weight × value}, held to {@code cap}; a {@code null} value is 0. */
    public static FactorResult factor(RiskScoreModel.Factor f, Double value, List<Map<String, Object>> evidence) {
        boolean missing = value == null || !Double.isFinite(value);
        double v = missing ? 0.0 : value;
        double raw = f.weight() * v;
        boolean capped = f.cap() != null && raw > f.cap();
        double contribution = capped ? f.cap() : raw;
        return new FactorResult(f.id(), f.label(), missing ? null : v, missing, f.weight(), f.cap(), capped,
                contribution, evidence == null ? List.of() : List.copyOf(evidence));
    }

    /** Score one entity from its per-factor indicator values (factor id → value; absent = missing). */
    public static Scored score(RiskScoreModel model, String entityKey, Map<String, Double> values,
                               Map<String, List<Map<String, Object>>> evidence) {
        List<FactorResult> out = new ArrayList<>(model.factors().size());
        for (RiskScoreModel.Factor f : model.factors())
            out.add(factor(f, values.get(f.id()), evidence == null ? null : evidence.get(f.id())));
        double score = total(out);
        return new Scored(entityKey, score, score >= model.highThreshold(), List.copyOf(out));
    }

    /** {@code Σ contribution}, clamped to {@code [0, 100]} — the same sum a reader recomputes from the JSON. */
    public static double total(List<FactorResult> factors) {
        double sum = 0;
        for (FactorResult r : factors) sum += r.contribution();
        return clamp(sum);
    }

    /**
     * Recompute a score from a stored {@code factors} JSON array — reproducibility's check. Reads only
     * {@code contribution}s after re-deriving each from {@code weight}, {@code value} and {@code cap}, so a
     * row whose contributions disagree with its own inputs recomputes to a DIFFERENT score.
     */
    public static double recompute(List<Map<String, Object>> factors) {
        double sum = 0;
        for (Map<String, Object> f : factors) {
            double weight = ((Number) f.get("weight")).doubleValue();
            double value = f.get("value") instanceof Number n ? n.doubleValue() : 0.0;
            double raw = weight * value;
            if (f.get("cap") instanceof Number c && raw > c.doubleValue()) raw = c.doubleValue();
            sum += raw;
        }
        return clamp(sum);
    }

    static double clamp(double s) {
        return Math.max(0.0, Math.min(100.0, s));
    }
}
