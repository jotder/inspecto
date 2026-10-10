package com.gamma.anomaly;

import com.gamma.anomaly.baseline.Baseline;
import com.gamma.anomaly.baseline.BaselineScore;
import com.gamma.anomaly.baseline.RobustStats;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Combines one entity's per-feature baselines into an <b>Anomaly Score</b> (design §4.4, D-AD6): pure, no DuckDB.
 *
 * <ul>
 *   <li>per feature: {@code z = (observed − median) / max(scaledMAD, unit, 5 % |median|)}; {@code dev} is z kept to
 *       the declared direction ({@code up}: max(z,0), {@code down}: max(−z,0), {@code both}: |z|), held to {@code zCap};</li>
 *   <li>{@code raw = sqrt(Σ w·dev² / Σ w)} over the features that are not insufficient;</li>
 *   <li>{@code score = 100 · (1 − exp(−raw / scale))}; the band from the model's thresholds.</li>
 * </ul>
 * An insufficient feature (too little history, or no observation of an avg/min/max feature) contributes 0, is
 * flagged, and is left out of {@code Σ w}. {@link #recompute} re-derives score and raw from a stored explanation.
 */
public final class AnomalyScorer {
    private AnomalyScorer() {}

    /**
     * One feature's input: the scored bucket's value ({@code null} = absent), its self baseline, its peer baseline
     * ({@code null} when the model declares no peers) and the cohort shift {@code k} the self baseline is scaled by
     * (1 = none; see {@link AnomalyScoreEvaluator}).
     */
    public record Input(Double observed, Baseline self, Baseline peer, double cohortShift) {
        public Input(Double observed, Baseline self) { this(observed, self, null, 1.0); }

        boolean selfUsable() { return !self.insufficient(); }
        boolean peerUsable() { return peer != null && !peer.insufficient(); }
        /** The self baseline as scored: centre and spread scaled by the cohort shift. */
        Baseline shiftedSelf() {
            if (cohortShift == 1.0 || self.insufficient()) return self;
            return new Baseline(self.kind(), self.median() * cohortShift, self.scaledMad() * cohortShift, self.points(),
                    self.basis(), self.requested(), self.fellBack(), false, self.reason());
        }
    }

    /** One feature's explanation. */
    public record FeatureResult(String feature, String label, String bucket, Double observed, Baseline baseline,
                                Baseline peerBaseline, double cohortShift, double unit, double zSelf, double zPeer,
                                double deviation, String direction, double weight, double contribution, double share,
                                boolean peersOnly, boolean insufficient, String reason) {

        public Map<String, Object> toMap() {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("kind", baseline.kind());
            b.put("median", finite(baseline.median()));
            b.put("mad", finite(baseline.scaledMad()));
            b.put("points", baseline.points());
            b.put("season", baseline.requested());
            b.put("basis", baseline.basis());
            b.put("fellBack", baseline.fellBack());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("feature", feature);
            m.put("label", label);
            m.put("bucket", bucket);
            m.put("observed", observed);
            m.put("baseline", b);
            if (peerBaseline != null) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("cohort", peerBaseline.requested());
                p.put("basis", peerBaseline.basis());
                p.put("median", finite(peerBaseline.median()));
                p.put("mad", finite(peerBaseline.scaledMad()));
                p.put("size", peerBaseline.points());
                p.put("fellBack", peerBaseline.fellBack());
                p.put("insufficient", peerBaseline.insufficient());
                m.put("peerBaseline", p);
                m.put("cohortShift", cohortShift);
                m.put("zPeer", finite(zPeer));
                m.put("peersOnly", peersOnly);
            }
            m.put("unit", unit);
            m.put("zSelf", finite(zSelf));
            m.put("deviation", deviation);
            m.put("direction", direction);
            m.put("weight", weight);
            m.put("contribution", contribution);
            m.put("share", share);
            m.put("insufficient", insufficient);
            m.put("reason", reason);
            return m;
        }
    }

    /** One entity's score. */
    public record Scored(String entityKey, double score, String band, double raw, List<FeatureResult> features,
                         int insufficientCount) {}

    /**
     * Score one entity; {@code inputs} is in the model's feature order (every feature present). Per feature
     * {@code dev = max(dev(zSelf), dev(zPeer))} (design §4.4); a feature is insufficient only when it has no
     * observation, or neither a self nor a peer baseline (D-AD12: peers alone score it, flagged {@code peersOnly}).
     */
    public static Scored score(AnomalyModel model, String entityKey, String bucket, Map<String, Input> inputs) {
        List<Object[]> devs = new ArrayList<>();
        double sumW = 0, sumWd2 = 0;
        for (AnomalyModel.Feature f : model.features()) {
            Input in = inputs.get(f.id());
            if (in == null) throw new IllegalArgumentException("no input for feature '" + f.id() + "'");
            boolean insufficient = in.observed() == null || (!in.selfUsable() && !in.peerUsable());
            double zs = Double.NaN, zp = Double.NaN, dev = 0;
            if (!insufficient) {
                BaselineScore bs = BaselineScore.of(in.observed(), in.shiftedSelf(), in.peer(), f.unit());
                zs = bs.zSelf();
                zp = bs.zPeer();
                dev = Math.max(Double.isNaN(zs) ? 0 : deviation(zs, f.direction(), model.zCap()),
                        Double.isNaN(zp) ? 0 : deviation(zp, f.direction(), model.zCap()));
                sumW += f.weight();
                sumWd2 += f.weight() * dev * dev;
            }
            devs.add(new Object[]{f, in, insufficient, zs, zp, dev});
        }
        double raw = sumW == 0 ? 0 : Math.sqrt(sumWd2 / sumW);
        double score = score(raw, model.scale());
        List<FeatureResult> out = new ArrayList<>();
        int insufficientCount = 0;
        for (Object[] d : devs) {
            AnomalyModel.Feature f = (AnomalyModel.Feature) d[0];
            Input in = (Input) d[1];
            boolean insufficient = (Boolean) d[2];
            double zs = (Double) d[3], zp = (Double) d[4], dev = (Double) d[5];
            if (insufficient) insufficientCount++;
            double contribution = insufficient || sumW == 0 ? 0 : f.weight() * dev * dev / sumW;
            double share = sumWd2 == 0 ? 0 : contribution / (sumWd2 / sumW);
            boolean peersOnly = !insufficient && !in.selfUsable();
            out.add(new FeatureResult(f.id(), f.displayLabel(), bucket, in.observed(), in.self(), in.peer(),
                    in.cohortShift(), f.unit(), zs, zp, dev, f.direction().name().toLowerCase(Locale.ROOT), f.weight(),
                    contribution, share, peersOnly, insufficient, reason(f, in, zs, zp, model.window())));
        }
        out.sort((a, b) -> Double.compare(b.contribution(), a.contribution()));
        return new Scored(entityKey, score, model.band(score), raw, List.copyOf(out), insufficientCount);
    }

    static double deviation(double z, AnomalyModel.Direction direction, double zCap) {
        double d = switch (direction) {
            case UP -> Math.max(z, 0);
            case DOWN -> Math.max(-z, 0);
            case BOTH -> Math.abs(z);
        };
        return Math.min(d, zCap);
    }

    /** {@code 100 · (1 − exp(−raw / scale))} (D-AD6). */
    public static double score(double raw, double scale) {
        return 100.0 * (1.0 - Math.exp(-raw / scale));
    }

    /**
     * Re-derive {@code {raw, score}} from a stored explanation ({@link FeatureResult#toMap} elements): the self z from
     * observed / median·k / mad·k / unit ({@code k} = {@code cohortShift}), the peer z from {@code peerBaseline}, the
     * deviation as {@link #score} takes it, then the weighted RMS.
     */
    public static double[] recompute(List<Map<String, Object>> features, double zCap, double scale) {
        double sumW = 0, sumWd2 = 0;
        for (Map<String, Object> f : features) {
            if (Boolean.TRUE.equals(f.get("insufficient"))) continue;
            AnomalyModel.Direction dir = AnomalyModel.Direction.valueOf(String.valueOf(f.get("direction")).toUpperCase(Locale.ROOT));
            double observed = num(f.get("observed")), unit = num(f.get("unit"));
            double k = f.get("cohortShift") == null ? 1.0 : num(f.get("cohortShift"));
            double dev = 0;
            Map<?, ?> b = (Map<?, ?>) f.get("baseline");
            if (b.get("median") != null) {
                double median = num(b.get("median")) * k, mad = num(b.get("mad")) * k;
                dev = deviation(RobustStats.z(observed, median, mad, RobustStats.floor(median, unit)), dir, zCap);
            }
            if (f.get("peerBaseline") instanceof Map<?, ?> p && p.get("median") != null) {
                double median = num(p.get("median")), mad = num(p.get("mad"));
                dev = Math.max(dev, deviation(RobustStats.z(observed, median, mad, RobustStats.floor(median, unit)), dir, zCap));
            }
            double w = num(f.get("weight"));
            sumW += w;
            sumWd2 += w * dev * dev;
        }
        double raw = sumW == 0 ? 0 : Math.sqrt(sumWd2 / sumW);
        return new double[]{raw, score(raw, scale)};
    }

    /** Generated from the numbers only — never a key or evidence value. */
    static String reason(AnomalyModel.Feature f, Input in, double zs, double zp, int window) {
        Baseline b = in.self();
        if (in.observed() == null) return f.displayLabel() + ": no value in the scored period, not scored";
        String peer = in.peer() == null ? "" : in.peerUsable()
                ? " vs peers' " + fmt(in.peer().median()) + " (" + in.peer().reason() + ") — " + mads(zp)
                : " (" + in.peer().reason() + ")";
        if (b.insufficient()) {
            String self = f.displayLabel() + ": " + b.points() + " baseline point(s) in " + window + " days";
            return in.peerUsable()
                    ? self + ", scored on peers only: " + fmt(in.observed()) + peer
                    : self + ", too few to score" + peer;
        }
        String basis = b.fellBack() || !"none".equals(b.requested()) ? b.reason() : b.points() + " days";
        String shift = in.cohortShift() == 1.0 ? ""
                : ", x" + fmt(in.cohortShift()) + " cohort shift = " + fmt(b.median() * in.cohortShift());
        return f.displayLabel() + " " + fmt(in.observed()) + " vs a usual " + fmt(b.median()) + " (" + basis + shift
                + ") — " + mads(zs) + (peer.isEmpty() ? "" : ";" + peer);
    }

    private static String mads(double z) {
        return fmt(Math.abs(z)) + " MADs " + (z >= 0 ? "above" : "below");
    }

    static String fmt(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return String.valueOf((long) v);
        return new BigDecimal(v).round(new MathContext(3)).stripTrailingZeros().toPlainString();
    }

    private static Double finite(double v) { return Double.isFinite(v) ? v : null; }

    private static double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        throw new IllegalArgumentException("explanation value is not numeric: " + o);
    }
}
