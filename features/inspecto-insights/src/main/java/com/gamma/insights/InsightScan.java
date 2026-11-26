package com.gamma.insights;

import com.gamma.analytics.Stats;
import com.gamma.analytics.insight.Concentration;
import com.gamma.analytics.insight.DistributionShift;
import com.gamma.analytics.insight.FalseDiscovery;
import com.gamma.analytics.insight.InsightScore;
import com.gamma.analytics.insight.Surprise;
import com.gamma.control.BiSpecRunner;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToIntFunction;

/**
 * The Insight scan (BI-INSIGHTS-1 I-8, plan §11.9), shared by the {@code insight.scan} Job and the replay preview.
 * {@link #fetch} reads the Profile's Measure per bucket — the total series and one statement per dimension — through a
 * {@link Fetcher} (the Job's {@code BiSpecRunner.runAsJob}, the preview's {@code BiSpecRunner.run}); it never builds
 * SQL. {@link #scan} then tests every slice's series as of one bucket, controls the false-discovery rate across every
 * test of the run (Benjamini-Hochberg) and ranks what is left by impact × surprise × novelty.
 */
final class InsightScan {

    private InsightScan() {}

    static final String TOTAL = "*";

    /** Runs one {@code /bi/query}-shaped body. */
    @FunctionalInterface
    interface Fetcher {
        BiSpecRunner.Run fetch(Map<String, Object> body) throws Exception;
    }

    /** The buckets read, oldest first, ending with the last COMPLETE bucket before {@code asOf}'s. */
    record Buckets(String grain, List<LocalDate> starts) {
        int size() { return starts.size(); }
        LocalDate start(int i) { return starts.get(i); }
        LocalDate end(int i) { return step(grain, starts.get(i), 1); }
        String label(int i) { return "month".equals(grain) ? starts.get(i).toString().substring(0, 7) : starts.get(i).toString(); }
    }

    static Buckets buckets(String grain, LocalDate asOf, int count) {
        LocalDate current = switch (grain) {
            case "week" -> asOf.minusDays(asOf.getDayOfWeek().getValue() - 1);
            case "month" -> asOf.withDayOfMonth(1);
            default -> asOf;
        };
        List<LocalDate> s = new ArrayList<>();
        for (int i = count; i >= 1; i--) s.add(step(grain, current, -i));
        return new Buckets(grain, List.copyOf(s));
    }

    static LocalDate step(String grain, LocalDate d, int n) {
        return switch (grain) {
            case "week" -> d.plusWeeks(n);
            case "month" -> d.plusMonths(n);
            default -> d.plusDays(n);
        };
    }

    /** One slice's series over the buckets; {@code dimension} {@link #TOTAL} is the whole Measure. */
    record Series(String dimension, String value, Double[] values) {
        String key(String type) { return type + "|" + dimension + "|" + value; }
    }

    /** What {@link #fetch} read: the total, every slice, the dimensions skipped (with why) and each dimension's body. */
    record Fetched(Buckets buckets, Series total, List<Series> slices, List<Map<String, Object>> skipped,
                   Map<String, Map<String, Object>> queries, Map<String, Object> masking) {}

    static Fetched fetch(InsightProfile p, Buckets b, Fetcher f) throws Exception {
        Map<String, Object> masking = null;
        Map<String, Map<String, Object>> queries = new LinkedHashMap<>();
        Map<String, Object> totalBody = body(p, List.of(p.field()), b, b.size() + 1);
        queries.put(TOTAL, totalBody);
        BiSpecRunner.Run t = f.fetch(totalBody);
        if (t.masking() != null) masking = t.masking();
        Series total = new Series(TOTAL, TOTAL, new Double[b.size()]);
        Map<String, Integer> at = new HashMap<>();
        for (int i = 0; i < b.size(); i++) at.put(b.label(i), i);
        for (Map<String, Object> row : t.rows()) {
            Integer i = at.get(String.valueOf(row.get(p.field())));
            if (i != null) total.values()[i] = number(row.get(p.measureId()), p.measureId());
        }
        List<Series> slices = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (String dim : p.dimensions()) {
            int limit = (int) Math.min((long) b.size() * p.maxValues(), InsightProfile.MAX_ROWS);
            Map<String, Object> db = body(p, List.of(dim, p.field()), b, limit);
            queries.put(dim, db);
            BiSpecRunner.Run r = f.fetch(db);
            if (r.masking() != null && masking == null) masking = r.masking();
            Map<String, Double[]> byValue = new LinkedHashMap<>();
            for (Map<String, Object> row : r.rows()) {
                Integer i = at.get(String.valueOf(row.get(p.field())));
                if (i == null) continue;
                Object k = row.get(dim);
                String key = k == null ? "(empty)" : String.valueOf(k);
                Double[] s = byValue.computeIfAbsent(key, x -> new Double[b.size()]);
                double v = number(row.get(p.measureId()), p.measureId());
                s[i] = s[i] == null ? v : s[i] + v;   // two raw keys masked to one alias add up
            }
            if (r.truncated() || byValue.size() > p.maxValues()) {
                skipped.add(Map.of("dimension", dim, "reason", "more than " + p.maxValues()
                        + " values (maxValues); scan it by a coarser column or raise maxValues"));
                continue;
            }
            for (Map.Entry<String, Double[]> e : byValue.entrySet()) slices.add(new Series(dim, e.getKey(), e.getValue()));
        }
        return new Fetched(b, total, slices, skipped, queries, masking);
    }

    /** A {@code /bi/query} body: the Profile's Measure, filters and condition, grouped, windowed to the buckets. */
    static Map<String, Object> body(InsightProfile p, List<String> groupBy, Buckets b, int limit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dataset", p.dataset());
        m.put("measures", List.of(p.measureMap()));
        m.put("groupBy", groupBy);
        m.put("grains", Map.of(p.field(), p.grain()));
        if (p.filters() != null) m.put("filters", p.filters());
        if (p.when() != null) m.put("when", p.when());
        LocalDate from = b.start(0), to = b.end(b.size() - 1);
        m.put("window", Map.of("field", p.field(), "asOf", to.minusDays(1).toString(),
                "days", (int) ChronoUnit.DAYS.between(from, to)));
        m.put("limit", limit);
        return m;
    }

    private static double number(Object v, String id) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.doubleValue();
        throw new SecurityException("the Measure '" + id + "' reaches the scan masked; a scan never stores a masked "
                + "column's values — refused");
    }

    // ── the scan ──

    /** One ranked finding. {@code key} = type × slice (what novelty and feedback follow); {@code id} adds the period. */
    record Insight(String id, String key, String type, String dimension, String value, LocalDate period,
                   double observed, double expected, double delta, double impact, double p, double q,
                   double score, String band, Map<String, Object> explanation, Map<String, Object> query, String status) {}

    /** The run's Insights (best first) and what it counted on the way. */
    record Result(List<Insight> insights, int tests, int belowSupport, int expectedDropped, int suppressed) {}

    /**
     * Scans the window {@code [from, to)} of the fetched buckets as of its last bucket. {@code reportedBefore} counts how
     * many recent runs reported a key; {@code feedback} maps a key to the status a reader gave it.
     */
    static Result scan(InsightProfile p, Fetched f, int from, int to, ToIntFunction<String> reportedBefore,
                       Map<String, String> feedback) {
        Buckets b = f.buckets();
        int n = to - from;
        boolean[] excluded = new boolean[n];
        for (int i = 0; i < n; i++)
            for (InsightProfile.CalendarEvent e : p.calendar())
                if ("exclude".equals(e.mode()) && e.covers(b.start(from + i), b.end(from + i))) excluded[i] = true;
        Double[] total = window(f.total().values(), from, to, excluded);
        double totalSum = 0;
        int totalN = 0;
        for (Double v : total) if (v != null) { totalSum += v; totalN++; }
        double meanAbs = totalN == 0 ? 0 : Math.abs(totalSum) / totalN;

        record Test(Series s, Surprise.Finding f, double shareMove) {}
        List<Test> tests = new ArrayList<>();
        int belowSupport = 0;
        List<Series> all = new ArrayList<>();
        all.add(f.total());
        all.addAll(f.slices());
        int minSeg = Math.max(3, n / 8);
        for (Series s : all) {
            Double[] w = window(s.values(), from, to, excluded);
            if (s != f.total()) {
                double sum = 0;
                for (Double v : w) if (v != null) sum += v;
                if (totalSum == 0 || Math.abs(sum / totalSum) < p.minSupport()) { belowSupport++; continue; }
            }
            for (String type : p.types()) {
                if (!InsightProfile.SERIES_TYPES.contains(type)) continue;
                Surprise.Finding fd = switch (type) {
                    case Surprise.SPIKE -> Surprise.spike(w, p.season(), 4, unit(p));
                    case Surprise.CHANGEPOINT -> Surprise.changepoint(w, minSeg, 2 * minSeg, unit(p));
                    default -> Surprise.drift(w, 8);
                };
                if (fd != null && Double.isFinite(fd.p())) tests.add(new Test(s, fd, Double.NaN));
            }
        }
        Map<String, List<Series>> byDimension = new LinkedHashMap<>();
        for (Series s : f.slices()) byDimension.computeIfAbsent(s.dimension(), x -> new ArrayList<>()).add(s);
        for (Map.Entry<String, List<Series>> e : byDimension.entrySet()) {
            double[][] parts = parts(e.getValue(), from, to, excluded);
            if (parts == null) continue;
            Series whole = new Series(e.getKey(), TOTAL, null);
            List<String> values = new ArrayList<>();
            for (Series s : e.getValue()) values.add(s.value());
            for (String type : p.types()) {
                Surprise.Finding fd = switch (type) {
                    case Concentration.TYPE -> concentration(parts, values, p.season());
                    case DistributionShift.TYPE -> distributionShift(parts, values, "count".equals(p.agg()));
                    default -> null;
                };
                if (fd != null && Double.isFinite(fd.p()))
                    tests.add(new Test(whole, fd, ((Number) fd.detail().get("shareMoved")).doubleValue()));
            }
        }
        double[] ps = new double[tests.size()];
        for (int i = 0; i < ps.length; i++) ps[i] = tests.get(i).f().p();
        double[] qs = FalseDiscovery.adjusted(ps);

        List<Insight> out = new ArrayList<>();
        int expectedDropped = 0, suppressed = 0;
        for (int i = 0; i < tests.size(); i++) {
            if (qs[i] > p.alpha()) continue;
            Test t = tests.get(i);
            Surprise.Finding fd = t.f();
            int atBucket = from + fd.at();
            boolean expected = false;
            for (InsightProfile.CalendarEvent e : p.calendar())
                if ("expected".equals(e.mode()) && e.covers(b.start(atBucket), b.end(atBucket))) expected = true;
            if (expected) { expectedDropped++; continue; }
            // a series test's move is in the Measure's unit, so its impact is a share of the period's total; a dimension
            // test's move is already a share of the whole (share points for concentration, total variation for a shift)
            boolean shares = !Double.isNaN(t.shareMove());
            Double totalAt = total[fd.at()];
            double denom = shares ? 1 : totalAt != null && totalAt != 0 ? totalAt : meanAbs;
            double move = shares ? t.shareMove() : fd.delta();
            double impact = denom == 0 ? 0 : Math.abs(move / denom);
            if (impact < p.minImpact()) continue;
            String key = t.s().key(fd.type());
            String status = feedback.getOrDefault(key, "new");
            double novelty = ("not_useful".equals(status) || "expected".equals(status)) ? 0
                    : InsightScore.novelty(reportedBefore.applyAsInt(key));
            if (novelty == 0) { suppressed++; continue; }
            InsightScore.Parts sc = InsightScore.of(move, denom, qs[i], novelty);
            LocalDate period = b.start(atBucket);
            Map<String, Object> ex = new LinkedHashMap<>();
            ex.put("test", fd.type());
            ex.put("statistic", fd.statistic());
            ex.put("p", fd.p());
            ex.put("q", qs[i]);
            ex.put("tests", tests.size());
            ex.putAll(fd.detail());
            ex.put("impact", sc.impact());
            ex.put("surprise", sc.surprise());
            ex.put("novelty", sc.novelty());
            ex.put("sentence", sentence(p, t.s(), fd, b.label(atBucket)));
            String id = UUID.nameUUIDFromBytes((p.id() + "|" + key + "|" + period).getBytes(StandardCharsets.UTF_8)).toString();
            out.add(new Insight(id, key, fd.type(), t.s().dimension(), t.s().value(), period, fd.observed(), fd.expected(),
                    fd.delta(), impact, fd.p(), qs[i], sc.score(), sc.band(), ex, f.queries().get(t.s().dimension()), status));
        }
        out.sort(Comparator.comparingDouble(Insight::score).reversed().thenComparing(Insight::id));
        if (out.size() > p.maxInsights()) out = new ArrayList<>(out.subList(0, p.maxInsights()));
        return new Result(out, tests.size(), belowSupport, expectedDropped, suppressed);
    }

    /** How many of a dimension's largest values the concentration test follows. */
    static final int TOP_N = 3;
    /** A dimension test needs at least this many earlier usable buckets to know its usual level. */
    static final int MIN_BASELINE = 4;

    /**
     * One dimension's window as amounts: {@code [bucket][value]}, a missing cell = 0 (no rows of that value in that
     * bucket), a bucket that is excluded or sums to 0 = {@code null}. {@code null} for fewer than two values, a negative
     * amount anywhere (a share of a sum with negative parts is not a share) or no usable last bucket.
     */
    static double[][] parts(List<Series> slices, int from, int to, boolean[] excluded) {
        if (slices.size() < 2) return null;
        double[][] out = new double[to - from][];
        for (int i = 0; i < out.length; i++) {
            if (excluded[i]) continue;
            double[] row = new double[slices.size()];
            double sum = 0;
            for (int j = 0; j < row.length; j++) {
                Double v = slices.get(j).values()[from + i];
                row[j] = v == null ? 0 : v;
                if (row[j] < 0) return null;
                sum += row[j];
            }
            if (sum > 0) out[i] = row;
        }
        return out[out.length - 1] == null ? null : out;
    }

    /**
     * I-9: the share held by the {@link #TOP_N} largest values (re-ranked in every bucket) as a series, and the robust z
     * of the last bucket's share against the earlier ones ({@link Surprise#spike}, one share point the smallest change
     * that matters). Needs more than {@link #TOP_N} values — with fewer, the top always holds everything.
     */
    static Surprise.Finding concentration(double[][] parts, List<String> values, int season) {
        if (values.size() <= TOP_N) return null;
        Double[] share = new Double[parts.length];
        for (int i = 0; i < parts.length; i++) if (parts[i] != null) share[i] = Concentration.topShare(parts[i], TOP_N);
        Surprise.Finding z = Surprise.spike(share, season, MIN_BASELINE, 0.01);
        if (z == null) return null;
        double[] now = parts[parts.length - 1], base = baseline(parts);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("topN", TOP_N);
        d.put("topValues", top(now, values, TOP_N));
        d.put("topShare", z.observed());
        d.put("topShareUsual", z.expected());
        d.put("shareMoved", z.delta());
        d.put("hhi", Concentration.hhi(now));
        d.put("hhiBaseline", Concentration.hhi(base));
        d.put("gini", Concentration.gini(now));
        d.put("paretoCount", (int) Concentration.paretoCount(now, 0.8));
        d.put("values", values.size());
        d.putAll(z.detail());
        return new Surprise.Finding(Concentration.TYPE, z.observed(), z.expected(), z.statistic(), z.p(), z.at(), d);
    }

    /**
     * I-10: the PSI of the last bucket's mix of values against the baseline (every earlier usable bucket summed), tested
     * against how far each earlier bucket's mix sits from the rest of the baseline (leave-one-out PSI): p = the empirical
     * rank (# references ≥ now + 1) ÷ (references + 1), so only a mix that moved more than buckets usually do is a
     * finding; z stays in the explanation. The KS distance (values ordered by baseline
     * share) and, for a count Measure, its nominal p are explanation only — they assume independent units, which a sum
     * Measure does not have and a count only approximates.
     */
    static Surprise.Finding distributionShift(double[][] parts, List<String> values, boolean counts) {
        int last = parts.length - 1;
        double[] base = baseline(parts), now = parts[last];
        List<Double> refs = new ArrayList<>();
        for (int i = 0; i < last; i++) {
            if (parts[i] == null) continue;
            double[] rest = new double[base.length];
            double sum = 0;
            for (int j = 0; j < rest.length; j++) { rest[j] = Math.max(0, base[j] - parts[i][j]); sum += rest[j]; }
            if (sum > 0) refs.add(DistributionShift.psi(rest, parts[i]));
        }
        if (refs.size() < MIN_BASELINE) return null;
        double[] r = refs.stream().mapToDouble(Double::doubleValue).toArray();
        double median = Stats.median(r), smad = Stats.scaledMad(r, median), floor = Stats.robustZFloor(median, 0.01);
        double psi = DistributionShift.psi(base, now);
        double z = Stats.robustZ(psi, median, smad, floor);
        // PSI across buckets is right-skewed (a small bucket's rare value hits the share floor), so a normal tail of z
        // would be far too optimistic; the p is the empirical rank of now among the leave-one-out references instead
        int atLeast = 0;
        for (double x : r) if (x >= psi) atLeast++;
        double p = (atLeast + 1.0) / (r.length + 1);
        Integer[] order = new Integer[base.length];
        for (int j = 0; j < order.length; j++) order[j] = j;
        java.util.Arrays.sort(order, Comparator.<Integer>comparingDouble(j -> -base[j]).thenComparing(values::get));
        double[] bo = new double[order.length], no = new double[order.length];
        double bt = 0, nt = 0;
        for (int j = 0; j < order.length; j++) { bo[j] = base[order[j]]; no[j] = now[order[j]]; bt += bo[j]; nt += no[j]; }
        int mover = 0;
        double moverGap = -1;
        for (int j = 0; j < base.length; j++) {
            double gap = Math.abs(now[j] / nt - base[j] / bt);
            if (gap > moverGap) { moverGap = gap; mover = j; }
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("psi", psi);
        d.put("psiUsual", median);
        d.put("z", z);
        d.put("baselinePoints", r.length);
        d.put("shareMoved", DistributionShift.totalVariation(base, now));
        double ks = DistributionShift.ksStatistic(bo, no);
        d.put("ksD", ks);
        if (counts) d.put("ksNominalP", DistributionShift.ksPValue(ks, bt, nt));
        d.put("moverValue", values.get(mover));
        d.put("moverShareBefore", base[mover] / bt);
        d.put("moverShareNow", now[mover] / nt);
        d.put("values", values.size());
        return new Surprise.Finding(DistributionShift.TYPE, psi, median, z, p, last, d);
    }

    private static double[] baseline(double[][] parts) {
        double[] b = new double[parts[parts.length - 1].length];
        for (int i = 0; i < parts.length - 1; i++)
            if (parts[i] != null) for (int j = 0; j < b.length; j++) b[j] += parts[i][j];
        return b;
    }

    private static List<String> top(double[] now, List<String> values, int n) {
        Integer[] o = new Integer[now.length];
        for (int j = 0; j < o.length; j++) o[j] = j;
        java.util.Arrays.sort(o, Comparator.<Integer>comparingDouble(j -> -now[j]).thenComparing(values::get));
        List<String> out = new ArrayList<>();
        for (int j = 0; j < Math.min(n, o.length); j++) out.add(values.get(o[j]));
        return out;
    }

    /** The smallest change that matters: 1 for a count, otherwise the 5 %-of-median floor alone. */
    private static double unit(InsightProfile p) {
        return "count".equals(p.agg()) ? 1 : 0;
    }

    private static Double[] window(Double[] s, int from, int to, boolean[] excluded) {
        Double[] w = new Double[to - from];
        for (int i = 0; i < w.length; i++) w[i] = excluded[i] ? null : s[from + i];
        return w;
    }

    /** A templated sentence (D-BI5: no language model in the first release). */
    static String sentence(InsightProfile p, Series s, Surprise.Finding f, String bucket) {
        Map<String, Object> d = f.detail();
        if (Concentration.TYPE.equals(f.type()))
            return "the top " + d.get("topN") + " " + s.dimension() + " values " + d.get("topValues") + " held "
                    + share(f.observed()) + " of " + p.measureId() + " in " + bucket + ", against a usual "
                    + share(f.expected()) + String.format(java.util.Locale.ROOT, " (%+.1f points)", f.delta() * 100);
        if (DistributionShift.TYPE.equals(f.type()))
            return "the mix of " + p.measureId() + " across " + s.dimension() + " shifted in " + bucket + ": "
                    + share((Double) d.get("shareMoved")) + " of it sits on different values than in the baseline (PSI "
                    + fmt(f.observed()) + ", usually " + fmt(f.expected()) + "); most: " + d.get("moverValue") + " "
                    + share((Double) d.get("moverShareBefore")) + " → " + share((Double) d.get("moverShareNow"));
        String what = p.measureId() + (TOTAL.equals(s.dimension()) ? "" : " for " + s.dimension() + " = " + s.value());
        return switch (f.type()) {
            case Surprise.SPIKE -> what + " was " + fmt(f.observed()) + " in " + bucket + ", against a usual "
                    + fmt(f.expected()) + " (" + pct(f) + ")";
            case Surprise.CHANGEPOINT -> what + " moved from about " + fmt(f.expected()) + " to about "
                    + fmt(f.observed()) + " per " + p.grain() + " from " + bucket + " on (" + pct(f) + ")";
            default -> what + " has been " + (f.delta() >= 0 ? "climbing" : "sliding") + " steadily: from about "
                    + fmt(f.expected()) + " to about " + fmt(f.observed()) + " per " + p.grain() + " (" + pct(f) + ")";
        };
    }

    private static String pct(Surprise.Finding f) {
        if (f.expected() == 0) return f.delta() >= 0 ? "up from zero" : "down from zero";
        return String.format(java.util.Locale.ROOT, "%+.1f %%", f.delta() / Math.abs(f.expected()) * 100);
    }

    private static String share(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f %%", v * 100);
    }

    private static String fmt(double v) {
        return Math.abs(v) >= 100 ? String.format(java.util.Locale.ROOT, "%,.0f", v)
                : String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
