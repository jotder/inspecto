package com.gamma.predictive;

import com.gamma.analytics.model.LogisticRegression;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The pure maths of a Churn Model run (PREDICTIVE-CHURN-DECLARED-1): per input, {@code x} (the windowed value, or the
 * declared value when the entity has none), {@code t = transform(x)} and the contribution {@code coefficient · t};
 * {@code logit = intercept + Σ contributions} and {@code score = 100 · σ(logit)}
 * ({@link LogisticRegression#recompute}, the Analytics Kernel's stable sigmoid).
 *
 * <p>An entity is <b>insufficient</b> — score NULL, band {@value ChurnModel#INSUFFICIENT}, never 0 — when its history
 * (whole days from its first row up to {@code as_of}) is shorter than {@code minHistoryDays}, or when an input has no
 * value in the window and declares no {@code missing}. Every row's explanation carries what {@link #recompute} needs to
 * rebuild the score from the row alone, with the model it names.
 */
public final class ChurnScorer {
    private ChurnScorer() {}

    /** One entity's inputs: its first row's date (up to {@code as_of}) and one value per model input (null = none). */
    public record Observed(String entityKey, LocalDate firstSeen, List<Double> values) {}

    /** One input's part of a score. */
    public record Part(String name, double value, boolean imputed, double transformed, double coefficient,
                       double contribution) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("value", value);
            if (imputed) m.put("imputed", true);
            m.put("transformed", transformed);
            m.put("coefficient", coefficient);
            m.put("contribution", contribution);
            return m;
        }
    }

    /** One Churn Score. {@code score}, {@code logit} and {@code parts} are null / empty when insufficient. */
    public record Scored(String entityKey, Double score, String band, Double logit, long historyDays, List<Part> parts,
                         Map<String, Object> explanation) {
        public boolean insufficient() { return score == null; }
    }

    /** The score of every observed entity as of {@code asOf}, in the input order. */
    public static List<Scored> score(ChurnModel model, LocalDate asOf, List<Observed> observed) {
        List<Scored> out = new ArrayList<>(observed.size());
        for (Observed o : observed) out.add(score(model, asOf, o));
        return out;
    }

    static Scored score(ChurnModel model, LocalDate asOf, Observed o) {
        long history = ChronoUnit.DAYS.between(o.firstSeen(), asOf);
        if (history < model.minHistoryDays()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("insufficient", "history");
            e.put("historyDays", history);
            e.put("minHistoryDays", model.minHistoryDays());
            return new Scored(o.entityKey(), null, ChurnModel.INSUFFICIENT, null, history, List.of(), e);
        }
        List<Part> parts = new ArrayList<>();
        List<String> absent = new ArrayList<>();
        for (int i = 0; i < model.inputs().size(); i++) {
            ChurnModel.Input in = model.inputs().get(i);
            Double v = o.values().get(i);
            boolean imputed = v == null;
            if (imputed) v = in.valueWhenAbsent();
            if (v == null) { absent.add(in.name()); continue; }
            double t = in.transform(v);
            parts.add(new Part(in.name(), v, imputed, t, in.coefficient(), in.coefficient() * t));
        }
        if (!absent.isEmpty()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("insufficient", "missing");
            e.put("inputs", List.copyOf(absent));
            return new Scored(o.entityKey(), null, ChurnModel.INSUFFICIENT, null, history, List.of(), e);
        }
        double[] contributions = parts.stream().mapToDouble(Part::contribution).toArray();
        double logit = model.intercept();
        for (double c : contributions) logit += c;
        double score = LogisticRegression.recompute(model.intercept(), contributions);
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("intercept", model.intercept());
        e.put("inputs", parts.stream().map(Part::toMap).toList());
        e.put("logit", logit);
        return new Scored(o.entityKey(), score, model.band(score), logit, history, List.copyOf(parts), e);
    }

    /** What a stored row recomputes to: its score (null when insufficient) and band. */
    public record Recomputed(Double score, String band) {}

    /**
     * Rebuild a row's score and band from its stored explanation and the model it names: each input's value goes
     * through the model's transform and coefficient again, so a row whose numbers disagree with their own inputs (or
     * with the model) recomputes to something else.
     */
    @SuppressWarnings("unchecked")
    public static Recomputed recompute(ChurnModel model, Map<String, Object> explanation) {
        if (explanation.containsKey("insufficient")) return new Recomputed(null, ChurnModel.INSUFFICIENT);
        List<Map<String, Object>> parts = (List<Map<String, Object>>) explanation.get("inputs");
        if (parts == null || parts.size() != model.inputs().size())
            throw new IllegalArgumentException("explanation does not carry one part per model input");
        double[] contributions = new double[parts.size()];
        for (int i = 0; i < parts.size(); i++) {
            ChurnModel.Input in = model.inputs().get(i);
            if (!in.name().equals(parts.get(i).get("name")))
                throw new IllegalArgumentException("explanation part " + i + " is not input '" + in.name() + "'");
            contributions[i] = in.coefficient() * in.transform(((Number) parts.get(i).get("value")).doubleValue());
        }
        double score = LogisticRegression.recompute(model.intercept(), contributions);
        return new Recomputed(score, model.band(score));
    }
}
