package com.gamma.analytics.drivers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * "Explain this change" for an additive Measure (BI-INSIGHTS-1 I-4): which values of a dimension carry the change of a
 * Measure between two periods.
 *
 * <ul>
 *   <li><b>Contribution</b> of a value c: {@code after(c) − before(c)}; its <b>share</b> is the contribution ÷ the total
 *       change Δ. Contributions of one dimension sum exactly to Δ, because the Measure is additive (sum, count).</li>
 *   <li><b>Surprise</b> of a value: the Jensen-Shannon term between its share of the total before ({@code p}) and after
 *       ({@code q}), natural log: {@code ½·(p·ln(2p/(p+q)) + q·ln(2q/(p+q)))}, 0 when both are 0. A dimension's surprise is
 *       the sum over its values — the Jensen-Shannon divergence of the two distributions (bounded by ln 2). It says how
 *       much the dimension's make-up moved, whatever the size of Δ (Adtributor). Shares are undefined when a total is not
 *       positive or a value is negative; the surprise is then {@code NaN} and the ranking falls back to the cover.</li>
 *   <li><b>Minimal cover</b>: the fewest values whose shares add up to at least {@code coverTarget} of Δ — the values
 *       sorted by share, largest first, taken until the target is met (for a sum threshold this greedy order is the
 *       smallest possible set). A dimension whose values cannot reach the target does not explain the change.</li>
 * </ul>
 * Δ = 0 explains nothing: every share is {@code NaN} and no dimension explains.
 */
public final class Drivers {

    private Drivers() {}

    /** One value of a dimension with the Measure in the two periods. */
    public record Element(String value, double before, double after) {}

    /** One value with its contribution, share of Δ and surprise. */
    public record Scored(String value, double before, double after, double contribution, double share, double surprise) {}

    /**
     * One dimension: its values (largest share first), its surprise, its minimal cover and how much of Δ that cover
     * explains; {@code explains} = the cover reached the target.
     */
    public record Dimension(String name, double before, double after, double delta, List<Scored> elements,
                            double surprise, List<Scored> cover, double covered, boolean explains) {}

    /** The Jensen-Shannon term of one value with shares {@code p} (before) and {@code q} (after). */
    public static double jsTerm(double p, double q) {
        double m = (p + q) / 2;
        if (m == 0) return 0;
        double a = p == 0 ? 0 : p * Math.log(p / m);
        double b = q == 0 ? 0 : q * Math.log(q / m);
        return (a + b) / 2;
    }

    /** Scores one dimension; {@code coverTarget} in (0, 1]. */
    public static Dimension explain(String name, List<Element> elements, double coverTarget) {
        if (!(coverTarget > 0 && coverTarget <= 1))
            throw new IllegalArgumentException("cover target must be in (0, 1]: " + coverTarget);
        double before = 0, after = 0;
        boolean negative = false;
        for (Element e : elements) {
            before += e.before();
            after += e.after();
            if (e.before() < 0 || e.after() < 0) negative = true;
        }
        double delta = after - before;
        // rounding residue of summed doubles is no change: it would inflate every share
        if (Math.abs(delta) <= 1e-9 * Math.max(Math.abs(before), Math.abs(after))) delta = 0;
        boolean shares = !negative && before > 0 && after > 0;
        List<Scored> scored = new ArrayList<>();
        double surprise = shares ? 0 : Double.NaN;
        for (Element e : elements) {
            double c = e.after() - e.before();
            double s = shares ? jsTerm(e.before() / before, e.after() / after) : Double.NaN;
            if (shares) surprise += s;
            scored.add(new Scored(e.value(), e.before(), e.after(), c, delta == 0 ? Double.NaN : c / delta, s));
        }
        scored.sort(Comparator.comparingDouble((Scored s) -> Double.isNaN(s.share()) ? Double.NEGATIVE_INFINITY : s.share())
                .reversed().thenComparing(Scored::value, Comparator.nullsFirst(Comparator.naturalOrder())));
        List<Scored> cover = new ArrayList<>();
        double covered = 0;
        if (delta != 0)
            for (Scored s : scored) {
                if (covered >= coverTarget || s.share() <= 0) break;
                cover.add(s);
                covered += s.share();
            }
        boolean explains = delta != 0 && covered >= coverTarget;
        return new Dimension(name, before, after, delta, List.copyOf(scored), surprise, List.copyOf(cover), covered, explains);
    }

    /**
     * The dimensions in reading order: those that explain first, the most surprising first (a {@code NaN} surprise last),
     * then the smaller cover; the rest after, by how much of Δ their cover reached.
     */
    public static List<Dimension> rank(List<Dimension> dims) {
        Comparator<Dimension> bySurprise = Comparator.comparingDouble(
                (Dimension d) -> Double.isNaN(d.surprise()) ? Double.NEGATIVE_INFINITY : d.surprise()).reversed();
        List<Dimension> out = new ArrayList<>(dims);
        out.sort(Comparator.comparing((Dimension d) -> !d.explains())
                .thenComparing((a, b) -> a.explains()
                        ? bySurprise.thenComparingInt((Dimension d) -> d.cover().size()).compare(a, b)
                        : Double.compare(b.covered(), a.covered()))
                .thenComparing(Dimension::name));
        return out;
    }
}
