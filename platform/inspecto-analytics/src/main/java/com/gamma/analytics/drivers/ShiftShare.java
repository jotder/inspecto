package com.gamma.analytics.drivers;

import java.util.ArrayList;
import java.util.List;

/**
 * Mix vs rate for a ratio Measure R = N ÷ D (BI-INSIGHTS-1 I-5): the exact shift-share identity
 * <pre>
 *   ΔR = Σ_c (w_c,1 − w_c,0)·r_c,0   [mix]   +   Σ_c w_c,1·(r_c,1 − r_c,0)   [rate]
 * </pre>
 * with {@code w_c = D_c ÷ D} (the slice's weight) and {@code r_c = N_c ÷ D_c} (its own rate), period 0 = before,
 * 1 = after. Because {@code Σ_c w_c·r_c = N ÷ D = R} in each period, mix + rate equals ΔR exactly.
 *
 * <p>A slice with no denominator in one period has no rate there. It is given the other period's rate, which keeps
 * the identity exact and puts the whole effect where it belongs: a slice that APPEARS ({@code D_c,0 = 0}) is all mix
 * ({@code w_c,1·r_c,1}), one that DISAPPEARS ({@code D_c,1 = 0}) is all mix ({@code −w_c,0·r_c,0}). A period whose total
 * denominator is 0, or a negative denominator, has no ratio to decompose → {@link IllegalArgumentException}.
 */
public final class ShiftShare {

    private ShiftShare() {}

    /** One slice: numerator and denominator before (0) and after (1). */
    public record Slice(String value, double num0, double den0, double num1, double den1) {}

    /** One slice's weights, rates and its parts of the mix and rate effects. */
    public record Part(String value, double w0, double w1, double r0, double r1, double mix, double rate) {}

    /** The ratio before and after, its change, and the two effects (mix + rate = delta), with every slice's part. */
    public record Result(double before, double after, double delta, double mix, double rate, List<Part> parts) {}

    public static Result decompose(List<Slice> slices) {
        double n0 = 0, d0 = 0, n1 = 0, d1 = 0;
        for (Slice s : slices) {
            if (s.den0() < 0 || s.den1() < 0)
                throw new IllegalArgumentException("a negative denominator has no mix and rate (slice '" + s.value() + "')");
            if ((s.den0() == 0 && s.num0() != 0) || (s.den1() == 0 && s.num1() != 0))
                throw new IllegalArgumentException("a value has a numerator but no denominator in one period, so the ratio"
                        + " cannot be split exactly into mix and rate");
            n0 += s.num0();
            d0 += s.den0();
            n1 += s.num1();
            d1 += s.den1();
        }
        if (d0 == 0 || d1 == 0)
            throw new IllegalArgumentException("the denominator is 0 in " + (d0 == 0 ? "the earlier" : "the later")
                    + " period, so the ratio has no value to decompose");
        List<Part> parts = new ArrayList<>();
        double mix = 0, rate = 0;
        for (Slice s : slices) {
            double w0 = s.den0() / d0, w1 = s.den1() / d1;
            double r0 = s.den0() > 0 ? s.num0() / s.den0() : Double.NaN;
            double r1 = s.den1() > 0 ? s.num1() / s.den1() : Double.NaN;
            if (Double.isNaN(r0)) r0 = Double.isNaN(r1) ? 0 : r1;
            if (Double.isNaN(r1)) r1 = r0;
            double m = (w1 - w0) * r0, r = w1 * (r1 - r0);
            mix += m;
            rate += r;
            parts.add(new Part(s.value(), w0, w1, r0, r1, m, r));
        }
        double before = n0 / d0, after = n1 / d1;
        return new Result(before, after, after - before, mix, rate, List.copyOf(parts));
    }
}
