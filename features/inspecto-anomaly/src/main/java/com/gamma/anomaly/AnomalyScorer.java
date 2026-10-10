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

    /** One feature's input: the scored bucket's value ({@code null} = absent) and its self baseline. */
    public record Input(Double observed, Baseline self) {}

    /** One feature's explanation. */
    public record FeatureResult(String feature, String label, String bucket, Double observed, Baseline baseline,
                                double unit, double zSelf, double deviation, String direction, double weight,
                                double contribution, double share, boolean insufficient, String reason) {

        public Map<String, Object> toMap() {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("kind", baseline.kind());
            b.put("median", finite(baseline.median()));
            b.put("mad", finite(baseline.scaledMad()));
            b.put("points", baseline.points());
            b.put("season", baseline.requested());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("feature", feature);
            m.put("label", label);
            m.put("bucket", bucket);
            m.put("observed", observed);
            m.put("baseline", b);
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

    /** Score one entity; {@code inputs} is in the model's feature order (every feature present). */
    public static Scored score(AnomalyModel model, String entityKey, String bucket, Map<String, Input> inputs) {
        List<Object[]> devs = new ArrayList<>();
        double sumW = 0, sumWd2 = 0;
        for (AnomalyModel.Feature f : model.features()) {
            Input in = inputs.get(f.id());
            if (in == null) throw new IllegalArgumentException("no input for feature '" + f.id() + "'");
            boolean insufficient = in.self().insufficient() || in.observed() == null;
            double z = insufficient ? Double.NaN : BaselineScore.of(in.observed(), in.self(), null, f.unit()).zSelf();
            double dev = insufficient ? 0 : deviation(z, f.direction(), model.zCap());
            if (!insufficient) {
                sumW += f.weight();
                sumWd2 += f.weight() * dev * dev;
            }
            devs.add(new Object[]{f, in, insufficient, z, dev});
        }
        double raw = sumW == 0 ? 0 : Math.sqrt(sumWd2 / sumW);
        double score = score(raw, model.scale());
        List<FeatureResult> out = new ArrayList<>();
        int insufficientCount = 0;
        for (Object[] d : devs) {
            AnomalyModel.Feature f = (AnomalyModel.Feature) d[0];
            Input in = (Input) d[1];
            boolean insufficient = (Boolean) d[2];
            double z = (Double) d[3], dev = (Double) d[4];
            if (insufficient) insufficientCount++;
            double contribution = insufficient || sumW == 0 ? 0 : f.weight() * dev * dev / sumW;
            double share = sumWd2 == 0 ? 0 : contribution / (sumWd2 / sumW);
            out.add(new FeatureResult(f.id(), f.displayLabel(), bucket, in.observed(), in.self(), f.unit(), z, dev,
                    f.direction().name().toLowerCase(Locale.ROOT), f.weight(), contribution, share, insufficient,
                    reason(f, in, z, model.window())));
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
     * Re-derive {@code {raw, score}} from a stored explanation ({@link FeatureResult#toMap} elements): each z from
     * observed / median / mad / unit, its deviation from direction and {@code zCap}, then the weighted RMS.
     */
    public static double[] recompute(List<Map<String, Object>> features, double zCap, double scale) {
        double sumW = 0, sumWd2 = 0;
        for (Map<String, Object> f : features) {
            if (Boolean.TRUE.equals(f.get("insufficient"))) continue;
            Map<?, ?> b = (Map<?, ?>) f.get("baseline");
            double median = num(b.get("median")), mad = num(b.get("mad")), unit = num(f.get("unit"));
            double z = RobustStats.z(num(f.get("observed")), median, mad, RobustStats.floor(median, unit));
            double dev = deviation(z, AnomalyModel.Direction.valueOf(String.valueOf(f.get("direction")).toUpperCase(Locale.ROOT)), zCap);
            double w = num(f.get("weight"));
            sumW += w;
            sumWd2 += w * dev * dev;
        }
        double raw = sumW == 0 ? 0 : Math.sqrt(sumWd2 / sumW);
        return new double[]{raw, score(raw, scale)};
    }

    /** Generated from the numbers only — never a key or evidence value. */
    static String reason(AnomalyModel.Feature f, Input in, double z, int window) {
        Baseline b = in.self();
        if (b.insufficient())
            return f.displayLabel() + ": " + b.points() + " baseline point(s) in " + window + " days, too few to score";
        if (in.observed() == null) return f.displayLabel() + ": no value in the scored period, not scored";
        String basis = b.fellBack() || !"none".equals(b.requested()) ? b.reason() : b.points() + " days";
        String side = z >= 0 ? "above" : "below";
        return f.displayLabel() + " " + fmt(in.observed()) + " vs a usual " + fmt(b.median()) + " (" + basis + ") — "
                + fmt(Math.abs(z)) + " MADs " + side;
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
