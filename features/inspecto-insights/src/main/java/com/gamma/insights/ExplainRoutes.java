package com.gamma.insights;

import com.gamma.analytics.drivers.Drivers;
import com.gamma.analytics.drivers.MovedWith;
import com.gamma.analytics.drivers.ShiftShare;
import com.gamma.access.ComponentAccess;
import com.gamma.access.WriteGates;
import com.gamma.control.BiSpecRunner;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.query.MeasureCompiler;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Explain this change" (BI-INSIGHTS-1 P2, D-BI7 depth 1 at query time) — {@code POST /insights/explain}, open to anyone who
 * may view the Widget's Dataset (operator 2026-11-26; it runs as the caller through {@code /bi/query}'s gates).
 *
 * <p>Body: {@code dataset}, optional {@code filters} / {@code when} (the Widget's own, as {@code /bi/query} reads them),
 * exactly one of {@code measure {agg, field}} or {@code ratio {num, den}}, {@code field} (the date column), {@code grain}
 * ({@code day | week | month}), {@code at} (a date inside the bucket to explain; it is compared with the bucket before),
 * {@code dimensions} (1–12 columns to explain by), optional {@code cover} (0.5–1, default 0.8), {@code related} (0–10
 * Measures for "moved with it") and {@code lookback} (8–60 buckets, default 24).
 *
 * <p>Statements, each through {@link BiSpecRunner} (the gates, guard, sandbox and masking of {@code /bi/query}, as the
 * caller): one per dimension — the Measure grouped by the dimension and the bucket, over the two buckets — and one for
 * the series — the Measure and the related Measures per bucket over {@code lookback} buckets. No SQL is built here.
 *
 * <ul>
 *   <li><b>I-4 drivers</b>: {@link Drivers} per dimension — contribution, share of Δ, Jensen-Shannon surprise, the
 *       minimal cover of {@code cover} × Δ; dimensions ranked by {@link Drivers#rank}.</li>
 *   <li><b>I-5 mix vs rate</b> (ratio only): {@link ShiftShare} per dimension; the drivers then use each slice's term
 *       {@code N_c ÷ D} so contributions add up to ΔR exactly.</li>
 *   <li><b>I-6 moved with it</b>: {@link MovedWith} — correlation of the bucket-to-bucket changes, at least
 *       {@link #MIN_PAIRS} pairs; worded as moving together, never as a cause.</li>
 * </ul>
 * Refused in words (422): an aggregate that is not additive (median, distinct count, percentiles, …) — never
 * approximated; a Measure that reaches the caller masked. A dimension with more than {@link #MAX_VALUES} values is
 * refused on its own, in {@code refused[]}. Depth 2 (pairs of dimensions, {@code depth: 2}) is not computed here (D-BI7):
 * it reads what the {@code insight.scan} Job stored for a Profile with {@code pairs: true} ({@link PairExplain}).
 */
public final class ExplainRoutes implements RouteModule {

    static final int MAX_DIMENSIONS = 12, MAX_RELATED = 10, MAX_VALUES = 200, MIN_PAIRS = 8;
    static final double MOVED_R = 0.5;
    private static final Set<String> ADDITIVE = Set.of("sum", "count");
    private static final List<String> GRAINS = List.of("day", "week", "month");
    /** Why an aggregate cannot be split by slices, in words; any other non-additive one gets the generic reason. */
    private static final Map<String, String> WHY_NOT_ADDITIVE = Map.of(
            "avg", "an average is a ratio: explain it as ratio {num: sum, den: count} to see mix and rate",
            "countDistinct", "a distinct count of a whole is not the sum of its parts' distinct counts",
            "min", "a minimum moves with one row, not with the slices",
            "max", "a maximum moves with one row, not with the slices");

    @Override
    public Set<String> featureIds() { return Set.of("insights"); }

    @Override
    public void register(ApiContext api) {
        api.post("/insights/explain", (e, m) -> explain(api, e, api.body(e)));
    }

    /** The two buckets compared and the series window. */
    record Periods(String grain, LocalDate prevStart, LocalDate curStart, LocalDate curEnd, List<String> series) {
        String prev() { return series.get(series.size() - 2); }
        String cur() { return series.get(series.size() - 1); }
    }

    static Periods periods(String grain, LocalDate at, int lookback) {
        LocalDate cur = switch (grain) {
            case "week" -> at.minusDays(at.getDayOfWeek().getValue() - 1);
            case "month" -> at.withDayOfMonth(1);
            default -> at;
        };
        List<String> labels = new ArrayList<>();
        for (int i = lookback - 1; i >= 0; i--) labels.add(label(grain, step(grain, cur, -i)));
        return new Periods(grain, step(grain, cur, -1), cur, step(grain, cur, 1), labels);
    }

    private static LocalDate step(String grain, LocalDate d, int n) {
        return switch (grain) {
            case "week" -> d.plusWeeks(n);
            case "month" -> d.plusMonths(n);
            default -> d.plusDays(n);
        };
    }

    /** The bucket label {@code /bi/query} returns: {@code YYYY-MM} for a month, else {@code YYYY-MM-DD}. */
    private static String label(String grain, LocalDate d) {
        return "month".equals(grain) ? d.toString().substring(0, 7) : d.toString();
    }

    private static ApiException bad(String msg) {
        return new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, msg);
    }

    private Object explain(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        // ── parse ──
        String dataset = ApiContext.str(body, "dataset");
        if (dataset == null) throw bad("body must include 'dataset'");
        String field = ApiContext.str(body, "field");
        if (field == null) throw bad("body must include 'field' (the date column)");
        String grain = ApiContext.str(body, "grain");
        if (grain == null || !GRAINS.contains(grain)) throw bad("'grain' must be one of " + GRAINS);
        LocalDate at;
        try {
            at = LocalDate.parse(String.valueOf(body.get("at")));
        } catch (DateTimeParseException e) {
            throw bad("'at' must be a date YYYY-MM-DD inside the bucket to explain");
        }
        boolean hasMeasure = body.get("measure") instanceof Map<?, ?>, hasRatio = body.get("ratio") instanceof Map<?, ?>;
        if (hasMeasure == hasRatio) throw bad("give exactly one of 'measure' {agg, field} or 'ratio' {num, den}");
        List<Map<String, Object>> targets = new ArrayList<>();   // the measure, or num then den
        if (hasMeasure) targets.add(measure(body.get("measure"), "measure"));
        else {
            Map<?, ?> r = (Map<?, ?>) body.get("ratio");
            targets.add(measure(r.get("num"), "ratio num"));
            targets.add(measure(r.get("den"), "ratio den"));
        }
        for (Map<String, Object> t : targets) requireAdditive(String.valueOf(t.get("agg")));
        List<String> dims = new ArrayList<>(new LinkedHashSet<>(strings(body.get("dimensions"))));
        if (dims.isEmpty() || dims.size() > MAX_DIMENSIONS)
            throw bad("'dimensions' must name 1-" + MAX_DIMENSIONS + " columns to explain by");
        if (dims.contains(field)) throw bad("the date column '" + field + "' cannot also be a dimension");
        double cover = body.get("cover") instanceof Number n ? n.doubleValue() : 0.8;
        if (!(cover >= 0.5 && cover <= 1)) throw bad("'cover' must be between 0.5 and 1");
        List<Map<String, Object>> related = new ArrayList<>();
        if (body.get("related") instanceof List<?> rl) for (Object o : rl) related.add(measure(o, "related"));
        if (related.size() > MAX_RELATED) throw bad("at most " + MAX_RELATED + " 'related' Measures");
        int lookback = body.get("lookback") instanceof Number n ? n.intValue() : 24;
        if (lookback < MIN_PAIRS || lookback > 60) throw bad("'lookback' must be " + MIN_PAIRS + "-60 buckets");
        Periods p = periods(grain, at, lookback);
        Object dp = body.get("depth") == null ? 1 : body.get("depth");
        if (!(dp instanceof Number dn) || (dn.doubleValue() != 1 && dn.doubleValue() != 2))
            throw bad("'depth' is 1 (each dimension) or 2 (also pairs of dimensions, precomputed by the insight.scan Job)");
        boolean depth2 = dn.doubleValue() == 2;
        if (depth2 && hasRatio)
            throw bad("depth 2 explains a sum or count: pairs of dimensions are precomputed for an Insight Profile's "
                    + "Measure, and a Profile's Measure is never a ratio. Ask depth 1 for the ratio's mix and rate.");

        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> masking = null;

        // ── the series: totals of the two buckets (the change) and I-6 ──
        List<Map<String, Object>> seriesMeasures = new ArrayList<>(targets);
        for (Map<String, Object> r : related)
            if (!seriesMeasures.contains(r)) seriesMeasures.add(r);
        LocalDate seriesStart = LocalDate.parse(p.series().get(0).length() == 7 ? p.series().get(0) + "-01" : p.series().get(0));
        Map<String, Object> seriesBody = base(body, dataset, seriesMeasures, List.of(field), field, grain,
                seriesStart, p.curEnd(), lookback + 1);
        BiSpecRunner.Run series = BiSpecRunner.run(api, ex, BiSpecRunner.parse(api, seriesBody));
        if (series.masking() != null) masking = series.masking();
        Map<String, Map<String, Object>> byBucket = new HashMap<>();
        for (Map<String, Object> row : series.rows()) byBucket.put(String.valueOf(row.get(field)), row);
        String targetId = id(targets.get(0));
        Double[] target = new Double[lookback];
        for (int i = 0; i < lookback; i++) target[i] = value(byBucket.get(p.series().get(i)), targets);
        Double before = target[lookback - 2], after = target[lookback - 1];
        if (before == null || after == null)
            throw bad("no " + (hasRatio ? "ratio value" : "data") + " in the " + (before == null ? "earlier bucket " + p.prev()
                    : "bucket " + p.cur()) + ", so there is no change to explain");
        String measureId = hasRatio ? id(targets.get(0)) + "_per_" + id(targets.get(1)) : targetId;
        out.put("measure", measureId);
        out.put("kind", hasRatio ? "ratio" : "additive");
        out.put("grain", grain);
        out.put("previous", p.prev());
        out.put("current", p.cur());
        out.put("before", before);
        out.put("after", after);
        out.put("delta", after - before);
        out.put("pct", before == 0 ? null : (after - before) / Math.abs(before) * 100);
        out.put("cover", cover);

        // ── I-4 drivers (and I-5 per dimension for a ratio) ──
        List<Drivers.Dimension> scored = new ArrayList<>();
        List<Map<String, Object>> mixRate = new ArrayList<>();
        List<Map<String, Object>> refused = new ArrayList<>();
        for (String dim : dims) {
            Map<String, Object> b = base(body, dataset, targets, List.of(dim, field), field, grain,
                    p.prevStart(), p.curEnd(), 2 * MAX_VALUES);
            BiSpecRunner.Run run = BiSpecRunner.run(api, ex, BiSpecRunner.parse(api, b));
            if (run.masking() != null && masking == null) masking = run.masking();
            if (run.truncated()) {
                refused.add(Map.of("dimension", dim, "reason", "more than " + MAX_VALUES + " values; explain by a coarser column"));
                continue;
            }
            // value → [num0, den0, num1, den1] (an additive Measure uses num only)
            Map<String, double[]> acc = new LinkedHashMap<>();
            for (Map<String, Object> row : run.rows()) {
                String bucket = String.valueOf(row.get(field));
                int at0 = bucket.equals(p.prev()) ? 0 : bucket.equals(p.cur()) ? 2 : -1;
                if (at0 < 0) continue;
                Object key = row.get(dim);
                double[] a = acc.computeIfAbsent(key == null ? null : String.valueOf(key), k -> new double[4]);
                a[at0] += number(row.get(id(targets.get(0))), id(targets.get(0)));
                if (hasRatio) a[at0 + 1] += number(row.get(id(targets.get(1))), id(targets.get(1)));
            }
            List<Drivers.Element> elements = new ArrayList<>();
            if (hasRatio) {
                List<ShiftShare.Slice> slices = new ArrayList<>();
                for (Map.Entry<String, double[]> e : acc.entrySet())
                    slices.add(new ShiftShare.Slice(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2], e.getValue()[3]));
                ShiftShare.Result ss;
                try {
                    ss = ShiftShare.decompose(slices);
                } catch (IllegalArgumentException no) {
                    refused.add(Map.of("dimension", dim, "reason", no.getMessage()));
                    continue;
                }
                double d0 = 0, d1 = 0;
                for (ShiftShare.Slice s : slices) { d0 += s.den0(); d1 += s.den1(); }
                for (ShiftShare.Slice s : slices) elements.add(new Drivers.Element(s.value(), s.num0() / d0, s.num1() / d1));
                mixRate.add(mixRate(dim, ss));
            } else {
                for (Map.Entry<String, double[]> e : acc.entrySet())
                    elements.add(new Drivers.Element(e.getKey(), e.getValue()[0], e.getValue()[2]));
            }
            scored.add(Drivers.explain(dim, elements, cover));
        }
        List<Map<String, Object>> drivers = new ArrayList<>();
        for (Drivers.Dimension d : Drivers.rank(scored)) drivers.add(dimension(d));
        out.put("drivers", drivers);
        if (hasRatio) out.put("mixRate", mixRate);
        out.put("refused", refused);

        // ── I-6 moved with it ──
        List<Map<String, Object>> moved = new ArrayList<>();
        for (Map<String, Object> r : related) {
            Double[] other = new Double[lookback];
            for (int i = 0; i < lookback; i++) other[i] = value(byBucket.get(p.series().get(i)), List.of(r));
            MovedWith.Result c = MovedWith.ofChanges(target, other);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("measure", id(r));
            m.put("before", other[lookback - 2]);
            m.put("after", other[lookback - 1]);
            m.put("pairs", c.pairs());
            boolean enough = c.pairs() >= MIN_PAIRS && !Double.isNaN(c.r());
            m.put("r", enough ? c.r() : null);
            m.put("movedWith", enough && Math.abs(c.r()) >= MOVED_R);
            if (!enough) m.put("reason", c.pairs() < MIN_PAIRS
                    ? "only " + c.pairs() + " bucket-to-bucket changes (at least " + MIN_PAIRS + ")"
                    : "one of the two did not change, so there is no correlation");
            moved.add(m);
        }
        moved.sort((a, b) -> Double.compare(abs(b.get("r")), abs(a.get("r"))));
        out.put("movedWith", moved);
        out.put("movedWithNote", "Moved together over the last " + lookback + " " + grain
                + "s (correlation of the changes). This does not say one causes the other.");
        if (depth2) pairs(api, ex, body, dataset, targets.get(0), field, grain, p, before, after, out);
        if (masking != null) out.put("masking", masking);
        return out;
    }

    /**
     * Depth 2 (BI-EXPLAIN-DEPTH2-1, plan §11.12): the pairs the {@code insight.scan} Job stored for a Profile with
     * {@code pairs: true} over the same Dataset, Measure, date column, grain, filters and condition, for the explained
     * bucket — only from a Profile whose Insights Dataset the caller may view (else as if none exists).
     */
    private static void pairs(ApiContext api, HttpExchange ex, Map<String, Object> req, String dataset,
                              Map<String, Object> measure, String field, String grain, Periods p, double before,
                              double after, Map<String, Object> out) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "Insights");
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        boolean anyProfile = false;
        for (ComponentRegistry.Component c : store.list(InsightProfile.KIND)) {
            InsightProfile prof;
            try {
                prof = InsightProfile.parse(c.name(), c.content());
            } catch (IllegalArgumentException invalid) {
                continue;
            }
            if (!prof.pairs() || !prof.dataset().equals(dataset) || !prof.field().equals(field) || !prof.grain().equals(grain)
                    || !id(measure).equals(prof.measureId()) || !same(prof.filters(), req.get("filters"))
                    || !same(prof.when(), req.get("when")))
                continue;
            Map<String, Object> ds = store.get("dataset", InsightStore.store(prof.id()))
                    .map(ComponentRegistry.Component::content).orElse(null);
            if (ds == null || !ComponentAccess.canView(ex, ds)) continue;
            anyProfile = true;
            Path dir = api.dataRoot() == null ? null : InsightStore.existing(api.dataRoot(), prof.id());
            if (dir == null) continue;
            Map<String, Object> entry;
            synchronized (InsightStore.lock(dir)) {
                entry = PairExplain.read(dir).get(p.cur());
            }
            if (entry == null || !p.prev().equals(entry.get("previous"))) continue;
            Map<String, Object> e = new LinkedHashMap<>(entry);
            e.put("stale", !(close(entry.get("before"), before) && close(entry.get("after"), after)));
            out.put("pairs", e);
            return;
        }
        out.put("pairs", null);
        out.put("pairsNote", (anyProfile
                ? "The insight.scan Job has not stored pairs of dimensions for " + p.cur() + " yet."
                : "No precomputed pairs of dimensions exist for this Measure, grain and filters.")
                + " Pairs are computed by the insight.scan Job, not per click: save an Insight Profile over dataset '"
                + dataset + "' with this Measure, date column '" + field + "', grain '" + grain + "', the same filters"
                + " and condition, at least two dimensions and pairs: true, then run its insight.scan Job after the"
                + " bucket completes.");
    }

    /** The stored total equals the live one (data not restated since the Job ran). */
    private static boolean close(Object stored, double live) {
        if (!(stored instanceof Number n)) return false;
        return Math.abs(n.doubleValue() - live) <= 1e-9 * Math.max(1, Math.abs(live));
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /** Filters / conditions compared as JSON trees (key order does not matter). */
    private static boolean same(Object a, Object b) {
        return JSON.valueToTree(a).equals(JSON.valueToTree(b));
    }

    private static double abs(Object r) {
        return r instanceof Number n ? Math.abs(n.doubleValue()) : -1;
    }

    /** A {@code /bi/query} body over the request's dataset, filters and condition, windowed to {@code [from, to)}. */
    private static Map<String, Object> base(Map<String, Object> req, String dataset, List<Map<String, Object>> measures,
                                            List<String> groupBy, String field, String grain, LocalDate from, LocalDate to, int limit) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("dataset", dataset);
        b.put("measures", measures);
        b.put("groupBy", groupBy);
        b.put("grains", Map.of(field, grain));
        if (req.get("filters") != null) b.put("filters", req.get("filters"));
        if (req.get("when") != null) b.put("when", req.get("when"));
        // (asOf − days, asOf] in whole days = [from, to)
        b.put("window", Map.of("field", field, "asOf", to.minusDays(1).toString(),
                "days", (int) ChronoUnit.DAYS.between(from, to)));
        b.put("limit", limit);
        return b;
    }

    private static Map<String, Object> measure(Object raw, String what) {
        if (!(raw instanceof Map<?, ?> m)) throw bad("'" + what + "' must be {agg, field}");
        String agg = m.get("agg") == null ? null : String.valueOf(m.get("agg"));
        if (agg == null) throw bad("'" + what + "' needs 'agg'");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("agg", agg);
        if (m.get("field") != null) out.put("field", String.valueOf(m.get("field")));
        else if (!"count".equals(agg)) throw bad("'" + what + "' needs 'field'");
        return out;
    }

    /** Non-decomposable aggregates are refused in words, never approximated. */
    static void requireAdditive(String agg) {
        if (ADDITIVE.contains(agg)) return;
        String why = WHY_NOT_ADDITIVE.getOrDefault(agg, "a " + agg + " of a whole is not built from its parts' values");
        throw bad("'" + agg + "' cannot be explained by slices: " + why
                + ". Explain works on sum and count, and on a ratio of them.");
    }

    private static String id(Map<String, Object> m) {
        return new MeasureCompiler.Measure(String.valueOf(m.get("agg")), m.get("field") == null ? null : String.valueOf(m.get("field"))).id();
    }

    /** The target's value in a bucket row: the Measure, or num ÷ den; {@code null} when absent (never 0). */
    private static Double value(Map<String, Object> row, List<Map<String, Object>> targets) {
        if (row == null) return null;
        Object v = row.get(id(targets.get(0)));
        if (v == null) return null;
        double n = number(v, id(targets.get(0)));
        if (targets.size() == 1) return n;
        Object d = row.get(id(targets.get(1)));
        if (d == null) return null;
        double dv = number(d, id(targets.get(1)));
        return dv == 0 ? null : n / dv;
    }

    private static double number(Object v, String id) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.doubleValue();
        throw bad("the Measure '" + id + "' reaches you masked, so its change cannot be explained");
    }

    private static List<String> strings(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> l) for (Object o : l) if (o != null) out.add(String.valueOf(o).trim());
        return out;
    }

    static Map<String, Object> dimension(Drivers.Dimension d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dimension", d.name());
        m.put("explains", d.explains());
        m.put("covered", finite(d.covered()));
        m.put("surprise", finite(d.surprise()));
        m.put("cover", d.cover().stream().map(Drivers.Scored::value).toList());
        List<Map<String, Object>> els = new ArrayList<>();
        for (Drivers.Scored s : d.elements().subList(0, Math.min(20, d.elements().size()))) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("value", s.value());
            e.put("before", s.before());
            e.put("after", s.after());
            e.put("contribution", s.contribution());
            e.put("share", finite(s.share()));
            e.put("surprise", finite(s.surprise()));
            els.add(e);
        }
        m.put("elements", els);
        m.put("valueCount", d.elements().size());
        return m;
    }

    private static Map<String, Object> mixRate(String dim, ShiftShare.Result r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dimension", dim);
        m.put("before", r.before());
        m.put("after", r.after());
        m.put("mix", r.mix());
        m.put("rate", r.rate());
        List<Map<String, Object>> parts = new ArrayList<>();
        for (ShiftShare.Part p : r.parts()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("value", p.value());
            e.put("w0", p.w0());
            e.put("w1", p.w1());
            e.put("r0", p.r0());
            e.put("r1", p.r1());
            e.put("mix", p.mix());
            e.put("rate", p.rate());
            parts.add(e);
        }
        m.put("parts", parts);
        return m;
    }

    private static Double finite(double v) {
        return Double.isFinite(v) ? v : null;
    }
}
