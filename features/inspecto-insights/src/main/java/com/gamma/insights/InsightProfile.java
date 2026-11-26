package com.gamma.insights;

import com.gamma.analytics.insight.Concentration;
import com.gamma.analytics.insight.DistributionShift;
import com.gamma.analytics.insight.Surprise;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An Insight Profile (component kind {@code insight-profile}, BI-INSIGHTS-1 I-8, plan §11.9): which Measure of which
 * Dataset the {@code insight.scan} Job looks across, by which dimensions, with which tests and knobs. Parsing is the
 * save-time validation: every refusal is an {@link IllegalArgumentException} in words (422).
 */
public record InsightProfile(String id, String dataset, String agg, String measureField, String field, String grain,
                             List<String> dimensions, Object filters, Object when, List<String> types,
                             double alpha, double minImpact, double minSupport, int maxValues, int lookback,
                             int season, int noveltyRuns, int maxInsights, List<CalendarEvent> calendar) {

    public static final String KIND = "insight-profile";
    public static final String PREFIX = "insights_";
    static final List<String> GRAINS = List.of("day", "week", "month");
    /** The tests over one slice's series. */
    static final List<String> SERIES_TYPES = List.of(Surprise.SPIKE, Surprise.CHANGEPOINT, Surprise.DRIFT);
    /** The tests over one dimension's mix of values (I-9, I-10); opt-in — the default stays {@link #SERIES_TYPES}. */
    static final List<String> DIMENSION_TYPES = List.of(Concentration.TYPE, DistributionShift.TYPE);
    static final List<String> TYPES = List.of(Surprise.SPIKE, Surprise.CHANGEPOINT, Surprise.DRIFT, Concentration.TYPE,
            DistributionShift.TYPE);
    static final Set<String> ADDITIVE = Set.of("sum", "count");
    static final int MAX_DIMENSIONS = 12, MAX_VALUES = 200, MIN_LOOKBACK = 12, MAX_LOOKBACK = 120;
    /** The scan's statements share the BI row ceiling ({@code ListLimitSettings.MAX_ROWS}). */
    static final int MAX_ROWS = 10_000;

    /** A business calendar day or span: {@code exclude} drops it from every series, {@code expected} drops findings dated in it. */
    public record CalendarEvent(LocalDate from, LocalDate to, String label, String mode) {
        boolean covers(LocalDate bucketStart, LocalDate bucketEnd) {   // [start, end) overlaps [from, to]
            return !from.isAfter(bucketEnd.minusDays(1)) && !to.isBefore(bucketStart);
        }
    }

    /** The Measure's id as {@code /bi/query} names its column ({@code sum_amount}, {@code count}). */
    public String measureId() {
        return new com.gamma.query.MeasureCompiler.Measure(agg, measureField).id();
    }

    public Map<String, Object> measureMap() {
        return measureField == null ? Map.of("agg", agg) : Map.of("agg", agg, "field", measureField);
    }

    static IllegalArgumentException bad(String id, String msg) {
        return new IllegalArgumentException("insight-profile '" + id + "': " + msg);
    }

    public static InsightProfile parse(String id, Map<String, Object> c) {
        if (id == null || !id.matches("[A-Za-z0-9_][A-Za-z0-9_-]{0,63}"))
            throw new IllegalArgumentException("insight-profile id must be 1-64 letters, digits, '_' or '-'");
        String dataset = str(c, "dataset");
        if (dataset == null) throw bad(id, "needs 'dataset'");
        if (!(c.get("measure") instanceof Map<?, ?> m)) throw bad(id, "needs 'measure' {agg, field}");
        String agg = m.get("agg") == null ? null : String.valueOf(m.get("agg")).trim();
        if (agg == null || !ADDITIVE.contains(agg))
            throw bad(id, "'measure.agg' must be sum or count: the impact of a slice is its share of the whole, which "
                    + "only an additive Measure has (an average is a ratio, a distinct count is not the sum of its parts)");
        String mf = m.get("field") == null ? null : String.valueOf(m.get("field")).trim();
        if (mf == null && !"count".equals(agg)) throw bad(id, "'measure.field' is needed for " + agg);
        String field = str(c, "field");
        if (field == null) throw bad(id, "needs 'field' (the date column)");
        String grain = str(c, "grain");
        if (grain == null || !GRAINS.contains(grain)) throw bad(id, "'grain' must be one of " + GRAINS);
        List<String> dims = new ArrayList<>(new LinkedHashSet<>(strings(c.get("dimensions"))));
        if (dims.size() > MAX_DIMENSIONS) throw bad(id, "at most " + MAX_DIMENSIONS + " 'dimensions'");
        if (dims.contains(field)) throw bad(id, "the date column '" + field + "' cannot also be a dimension");
        List<String> types = c.get("types") == null ? SERIES_TYPES : new ArrayList<>(new LinkedHashSet<>(strings(c.get("types"))));
        if (types.isEmpty() || !TYPES.containsAll(types)) throw bad(id, "'types' must be a non-empty subset of " + TYPES);
        for (String t : types)
            if (DIMENSION_TYPES.contains(t) && dims.isEmpty())
                throw bad(id, "'" + t + "' compares the values of a dimension; name at least one in 'dimensions'");
        String sens = c.get("sensitivity") == null ? "normal" : String.valueOf(c.get("sensitivity"));
        double alpha = switch (sens) {
            case "low" -> 0.01;
            case "normal" -> 0.05;
            case "high" -> 0.10;
            default -> throw bad(id, "'sensitivity' must be low, normal or high");
        };
        double minImpact = num(id, c, "minImpact", 0.01, 0, 1);
        double minSupport = num(id, c, "minSupport", 0.01, 0, 1);
        int maxValues = (int) num(id, c, "maxValues", 50, 1, MAX_VALUES);
        int defLookback = switch (grain) { case "day" -> 56; case "week" -> 26; default -> 24; };
        int lookback = (int) num(id, c, "lookback", defLookback, MIN_LOOKBACK, MAX_LOOKBACK);
        if (!dims.isEmpty() && (long) lookback * maxValues > MAX_ROWS)
            throw bad(id, "'lookback' × 'maxValues' = " + (long) lookback * maxValues + " exceeds " + MAX_ROWS
                    + " rows per dimension; lower one of them");
        Object s = c.get("season");
        int season;
        if (s == null || "auto".equals(s)) season = switch (grain) { case "day" -> 7; case "week" -> 52; default -> 12; };
        else if ("none".equals(s)) season = 0;
        else if (s instanceof Number n && n.intValue() >= 2 && n.intValue() <= 366 && n.doubleValue() == n.intValue()) season = n.intValue();
        else throw bad(id, "'season' must be auto, none or a whole number of buckets 2-366");
        int noveltyRuns = (int) num(id, c, "noveltyRuns", 4, 0, 52);
        int maxInsights = (int) num(id, c, "maxInsights", 50, 1, 200);
        List<CalendarEvent> cal = new ArrayList<>();
        if (c.get("calendar") != null) {
            if (!(c.get("calendar") instanceof List<?> l)) throw bad(id, "'calendar' must be a list");
            for (Object o : l) cal.add(event(id, o));
        }
        return new InsightProfile(id, dataset, agg, mf, field, grain, List.copyOf(dims), c.get("filters"), c.get("when"),
                List.copyOf(types), alpha, minImpact, minSupport, maxValues, lookback, season, noveltyRuns, maxInsights,
                List.copyOf(cal));
    }

    private static CalendarEvent event(String id, Object o) {
        if (!(o instanceof Map<?, ?> m)) throw bad(id, "a 'calendar' entry must be {from, to?, label, mode}");
        LocalDate from, to;
        try {
            from = LocalDate.parse(String.valueOf(m.get("from")));
            to = m.get("to") == null ? from : LocalDate.parse(String.valueOf(m.get("to")));
        } catch (DateTimeParseException e) {
            throw bad(id, "a 'calendar' entry's 'from' / 'to' must be dates YYYY-MM-DD");
        }
        if (to.isBefore(from)) throw bad(id, "a 'calendar' entry ends before it starts");
        String label = m.get("label") == null ? null : String.valueOf(m.get("label")).trim();
        if (label == null || label.isEmpty()) throw bad(id, "a 'calendar' entry needs a 'label' (why the day is special)");
        String mode = m.get("mode") == null ? "expected" : String.valueOf(m.get("mode"));
        if (!mode.equals("exclude") && !mode.equals("expected")) throw bad(id, "a 'calendar' entry's 'mode' is exclude or expected");
        return new CalendarEvent(from, to, label, mode);
    }

    private static double num(String id, Map<String, Object> c, String k, double def, double min, double max) {
        Object v = c.get(k);
        if (v == null) return def;
        if (!(v instanceof Number n) || !(n.doubleValue() >= min && n.doubleValue() <= max))
            throw bad(id, "'" + k + "' must be a number " + (min == (int) min ? (int) min : min) + "-" + (max == (int) max ? (int) max : max));
        return n.doubleValue();
    }

    static String str(Map<String, Object> c, String k) {
        Object v = c.get(k);
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    static List<String> strings(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> l) for (Object o : l) if (o != null && !String.valueOf(o).isBlank()) out.add(String.valueOf(o).trim());
        return out;
    }
}
