package com.gamma.job;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleFunction;

/**
 * Chart Markers and overlays for a report PDF (REPORT-CHART-MARKERS-1): the server twin of the SPA's
 * {@code resolveMarkers} / {@code kpiBands} ({@code inspecto-ui/src/app/inspecto/viz/chart-markers.ts}, BI-INSIGHTS-1 I-2)
 * and {@code resolveOverlays} / {@code overlayNote} ({@code chart-overlays.ts}, I-11 / I-7). Same inputs, same lines,
 * bands and words: {@code inspecto-ui/src/app/inspecto/contracts/chart-markers.contract.json} pins both sides
 * ({@code ChartMarkersContractTest}, {@code chart-markers.contract.spec.ts}). A marker that cannot be placed is dropped,
 * never guessed. Pure: no I/O, no drawing ({@link WidgetChartRenderer} draws what this returns).
 */
final class ChartMarkers {

    /** The Visualization Types a Chart Marker draws on ({@code MARKER_VIZ_TYPES}; the PDF draws bar and line). */
    static final Set<String> MARKER_VIZ_TYPES = Set.of("line", "bar", "area", "combo");
    /** The Visualization Types the overlays draw on ({@code OVERLAY_VIZ_TYPES}). */
    static final Set<String> OVERLAY_VIZ_TYPES = Set.of("line", "bar", "area");
    /** The |z| that counts as an anomaly at each sensitivity, 1 … 5 ({@code SENSITIVITY_Z}). */
    static final double[] SENSITIVITY_Z = {5, 4, 3.5, 3, 2.5};
    static final int DEFAULT_SENSITIVITY = 3;
    static final double DEFAULT_LEVEL = 0.8;
    static final String HIDDEN_NOTE = "The forecast band and anomaly markers are not drawn on a stacked or sorted chart.";

    private ChartMarkers() {}

    /** One forecast point (by category index) or one flagged point ({@code lower} / {@code upper} NaN). */
    record Point(int index, double value, double lower, double upper) {}

    /**
     * A marker ready to draw — {@code type} is {@code line} ({@code value}), {@code band} ({@code from} / {@code to},
     * {@code null} = the chart's edge), {@code interval} or {@code points} ({@code points}).
     */
    record Resolved(String type, Double value, Double from, Double to, String text, String tone, boolean dashed,
                    List<Point> points) {
        static Resolved line(double value, String text, String tone, boolean dashed) {
            return new Resolved("line", value, null, null, text, tone, dashed, List.of());
        }

        static Resolved band(Double from, Double to, String text, String tone) {
            return new Resolved("band", null, from, to, text, tone, false, List.of());
        }

        Resolved withText(String t) {
            return new Resolved(type, value, from, to, t, tone, dashed, points);
        }
    }

    /** A series' overlay cells, aligned with its categories; {@code NaN} = absent (never 0). */
    record Cells(double[] fc, double[] lo, double[] hi, double[] expected, double[] z) {}

    /** One drawn series with its overlay cells ({@code null} = the rows carry none for it). */
    record Series(String label, double[] data, Cells overlay) {}

    // ── formatting: the SPA's formatNumber with no configured format (en, grouped, at most 2 decimals) ──

    /** {@code formatNumber(v)}: grouped, up to two decimals, a non-finite value an em dash. */
    static String plain(double v) {
        return fixed(v, "#,##0.##");
    }

    /** {@code formatNumber(v, {decimals: 1})}. */
    static String oneDecimal(double v) {
        return fixed(v, "#,##0.0");
    }

    private static String fixed(double v, String pattern) {
        if (!Double.isFinite(v)) return "—";
        DecimalFormat f = new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.ROOT));
        // Intl rounds the shortest decimal that reads back as v (2.675 → 2.68), half away from zero, and keeps a
        // negative sign on a value that rounds to zero (-0.001 → "-0"): round the same decimal the same way
        f.setRoundingMode(RoundingMode.HALF_UP);
        String s = f.format(new java.math.BigDecimal(Double.toString(v)));
        return (v < 0 || 1 / v < 0) && !s.startsWith("-") ? "-" + s : s;
    }

    // ── statistics ──

    /** The {@code p}th percentile (0–100), linear between the closest ranks — DuckDB's {@code quantile_cont}. */
    static Double percentile(double[] values, double p) {
        double[] xs = Arrays.stream(values).filter(Double::isFinite).toArray();
        if (xs.length == 0 || !Double.isFinite(p)) return null;
        return com.gamma.analytics.Stats.quantile(xs, Math.min(100, Math.max(0, p)) / 100);   // the kernel's quantile_cont
    }

    static Double average(double[] values) {
        double[] xs = Arrays.stream(values).filter(Double::isFinite).toArray();
        if (xs.length == 0) return null;
        double sum = 0;
        for (double x : xs) sum += x;   // left to right, as the SPA's reduce
        return sum / xs.length;
    }

    /** "90th", "1st", "22nd", "13th". */
    static String ordinal(double n) {
        long v = Math.round(n);
        boolean teen = v % 100 >= 11 && v % 100 <= 13;
        int last = (int) (v % 10);
        String suffix = teen ? "th" : last >= 0 && last <= 3 ? new String[] {"th", "st", "nd", "rd"}[last] : "th";
        return v + suffix;
    }

    /**
     * The population the statistics read, as the SPA's {@code staticMarkers} builds it for one stack (bar / line):
     * every series' values, or — when {@code stacked} — the category totals (an absent value adds 0).
     */
    static double[] population(List<double[]> series, int categories, boolean stacked) {
        if (!stacked) return series.stream().flatMapToDouble(Arrays::stream).toArray();
        if (series.isEmpty()) return new double[0];
        double[] totals = new double[categories];
        for (int i = 0; i < categories; i++)
            for (double[] s : series) totals[i] += i < s.length && Double.isFinite(s[i]) ? s[i] : 0;
        return totals;
    }

    // ── Chart Markers (I-2) ──

    /** The bound KPI's RAG zones as bands ({@code kpiBands}); {@code []} when the KPI has no usable bands. */
    static List<Resolved> kpiBands(Map<?, ?> kpi, DoubleFunction<String> f) {
        if (kpi == null || !(kpi.get("bands") instanceof Map<?, ?> b)) return List.of();
        Object direction = kpi.get("direction");
        List<Resolved> out = new ArrayList<>();
        if ("band".equals(direction)) {
            double[] g = pair(b.get("green")), a = pair(b.get("amber"));
            if (g == null || a == null) return List.of();
            out.add(zone(null, a[0], "error", "Red", f));
            out.add(zone(a[0], g[0], "warning", "Amber", f));
            out.add(zone(g[0], g[1], "success", "Green", f));
            out.add(zone(g[1], a[1], "warning", "Amber", f));
            out.add(zone(a[1], null, "error", "Red", f).withText("Red above " + f.apply(a[1])));
        } else {
            Double green = finite(b.get("green")), amber = finite(b.get("amber"));
            if (green == null || amber == null) return List.of();
            if ("down".equals(direction)) {
                out.add(zone(null, green, "success", "Green", f).withText("Green at or below " + f.apply(green)));
                out.add(zone(green, amber, "warning", "Amber", f));
                out.add(zone(amber, null, "error", "Red", f).withText("Red above " + f.apply(amber)));
            } else {
                out.add(zone(null, amber, "error", "Red", f));
                out.add(zone(amber, green, "warning", "Amber", f));
                out.add(zone(green, null, "success", "Green", f).withText("Green at or above " + f.apply(green)));
            }
        }
        // a zone of no width (the server allows green = amber) is nothing to draw
        return out.stream().filter(m -> m.from() == null || m.to() == null || m.from() < m.to()).toList();
    }

    private static Resolved zone(Double from, Double to, String tone, String word, DoubleFunction<String> f) {
        return Resolved.band(from, to, word + " " + range(from, to, f), tone);
    }

    private static String range(Double lo, Double hi, DoubleFunction<String> f) {
        return lo == null ? "below " + f.apply(hi) : hi == null ? "from " + f.apply(lo) : f.apply(lo) + "–" + f.apply(hi);
    }

    /**
     * The stored markers ({@code options.markers}) as lines and bands with their words ({@code resolveMarkers}).
     *
     * @param values the population the statistics read ({@link #population})
     * @param target the Widget's own target ({@code options.kpi.target}); may be null
     * @param kpi    the bound KPI's definition as stored ({@code direction}, {@code target}, {@code bands}); null when
     *               none is bound or it could not be read — a {@code rag} then draws nothing, a target falls back
     */
    static List<Resolved> resolve(List<?> markers, double[] values, Object target, Map<?, ?> kpi, DoubleFunction<String> f) {
        List<Resolved> out = new ArrayList<>();
        if (markers == null) return out;
        for (Object o : markers) {
            if (!(o instanceof Map<?, ?> m)) continue;
            String label = m.get("label") instanceof String s && !s.trim().isEmpty() ? s.trim() : null;
            switch (String.valueOf(m.get("kind"))) {
                case "constant" -> line(out, finite(m.get("value")), "Reference", label, true, null, f);
                case "target" -> {
                    Double v = finite(m.get("value"));
                    if (v == null) v = kpi == null ? null : finite(kpi.get("target"));
                    if (v == null) v = finite(target);
                    line(out, v, "Target", label, false, "info", f);
                }
                case "average" -> line(out, average(values), "Average", label, true, null, f);
                case "percentile" -> {
                    // a whole percentile only: its words ("90th") must name the value drawn
                    Double p = finite(m.get("p"));
                    if (p != null && p == Math.rint(p) && p > 0 && p < 100)
                        line(out, percentile(values, p), ordinal(p) + " percentile", label, true, null, f);
                }
                case "band" -> {
                    Double from = finite(m.get("from")), to = finite(m.get("to"));
                    if ((from == null && to == null) || (from != null && from.equals(to))) break;
                    Double lo = from, hi = to;
                    if (from != null && to != null && from > to) { lo = to; hi = from; }
                    out.add(Resolved.band(lo, hi, (label != null ? label : "Band") + " " + range(lo, hi, f),
                            m.get("tone") == null ? null : String.valueOf(m.get("tone"))));
                }
                case "rag" -> {
                    for (Resolved b : kpiBands(kpi, f)) out.add(label == null ? b : b.withText(label + ": " + b.text()));
                }
                default -> { }   // an unknown kind: nothing to place
            }
        }
        return out;
    }

    private static void line(List<Resolved> out, Double value, String word, String label, boolean dashed, String tone,
                             DoubleFunction<String> f) {
        if (value == null || !Double.isFinite(value)) return;
        out.add(Resolved.line(value, (label != null ? label : word) + " " + f.apply(value), tone, dashed));
    }

    // ── overlays (I-11 / I-7) ──

    /** The |z| threshold of a sensitivity (clamped to 1 … 5; null = the default). */
    static double anomalyThreshold(Object sensitivity) {
        Double s = finite(sensitivity);
        long i = Math.round(Math.min(5, Math.max(1, s == null ? DEFAULT_SENSITIVITY : s)));
        return SENSITIVITY_Z[(int) i - 1];
    }

    /**
     * The forecast band ({@code interval}) and flagged points ({@code points}) per series ({@code resolveOverlays});
     * {@code overlay} is the Widget's {@code options.overlay}. A series with no forecast cell draws no band.
     */
    static List<Resolved> resolveOverlays(List<Series> series, List<String> labels, Map<?, ?> overlay,
                                          DoubleFunction<String> f) {
        List<Resolved> out = new ArrayList<>();
        if (overlay == null) return out;
        boolean forecast = overlay.get("forecast") != null, anomaly = overlay.get("anomaly") != null;
        boolean many = series.stream().filter(s -> s.overlay() != null).count() > 1;
        Object sensitivity = overlay.get("anomaly") instanceof Map<?, ?> a ? a.get("sensitivity") : null;
        double threshold = anomalyThreshold(sensitivity);
        Double lv = overlay.get("forecast") instanceof Map<?, ?> fc ? finite(fc.get("level")) : null;
        long level = Math.round((lv == null ? DEFAULT_LEVEL : lv) * 100);
        for (Series s : series) {
            Cells c = s.overlay();
            if (c == null) continue;
            String prefix = many ? s.label() + ": " : "";
            if (forecast) {
                List<Point> pts = new ArrayList<>();
                for (int i = 0; i < c.fc().length; i++)
                    if (Double.isFinite(c.fc()[i]) && Double.isFinite(c.lo()[i]) && Double.isFinite(c.hi()[i]))
                        pts.add(new Point(i, c.fc()[i], c.lo()[i], c.hi()[i]));
                if (!pts.isEmpty()) {
                    double min = pts.stream().mapToDouble(Point::lower).min().orElseThrow();
                    double max = pts.stream().mapToDouble(Point::upper).max().orElseThrow();
                    out.add(new Resolved("interval", null, null, null, prefix + "Forecast " + labels.get(pts.get(0).index())
                            + " to " + labels.get(pts.get(pts.size() - 1).index()) + ", " + level + " % band "
                            + f.apply(min) + "–" + f.apply(max), null, true, List.copyOf(pts)));
                }
            }
            if (anomaly) {
                List<Point> pts = new ArrayList<>();
                List<String> words = new ArrayList<>();
                for (int i = 0; i < c.z().length; i++) {
                    double v = i < s.data().length ? s.data()[i] : Double.NaN;
                    if (Double.isFinite(c.z()[i]) && Math.abs(c.z()[i]) >= threshold && Double.isFinite(v)) {
                        pts.add(new Point(i, v, Double.NaN, Double.NaN));
                        words.add(labels.get(i) + " (" + f.apply(v) + ", expected " + f.apply(c.expected()[i]) + ")");
                    }
                }
                if (!pts.isEmpty())
                    out.add(new Resolved("points", null, null, null, prefix + "Unusual "
                            + (pts.size() == 1 ? "value" : "values") + " at " + String.join(", ", words), "error", false,
                            List.copyOf(pts)));
            }
        }
        return out;
    }

    /** What one bucket of the time axis is called ({@code grainUnit}): a day, a week, a month, else a bucket. */
    static String grainUnit(List<String> labels) {
        if (labels.stream().anyMatch(l -> l.matches("\\d{4}-\\d{2}"))) return "month";
        List<java.time.LocalDate> days = new ArrayList<>();
        for (String l : labels)
            if (l.matches("\\d{4}-\\d{2}-\\d{2}")) days.add(java.time.LocalDate.parse(l));
        if (days.size() >= 2 && java.time.temporal.ChronoUnit.DAYS.between(days.get(0), days.get(1)) >= 7) return "week";
        return days.isEmpty() ? "bucket" : "day";
    }

    /**
     * The caption under the chart ({@code overlayNote}): the forecast and its tested accuracy, the flagged count — or why
     * there is none. {@code summary} is {@code ChartOverlays.Outcome#summary()}.
     */
    static List<String> overlayNote(List<Map<String, Object>> summary, int flagged, Object sensitivity, String unit) {
        List<String> lines = new ArrayList<>();
        if (summary == null || summary.isEmpty()) return lines;
        List<Map<String, Object>> fc = summary.stream().filter(s -> "forecast".equals(s.get("overlay"))).toList();
        for (Map<String, Object> s : fc.subList(0, Math.min(3, fc.size()))) {
            String series = "";
            if (s.get("series") instanceof Map<?, ?> m && !m.isEmpty() && fc.size() > 1)
                series = String.join(" · ", m.values().stream().map(String::valueOf).toList()) + ": ";
            if (s.get("reason") != null) {
                lines.add(series + "No forecast: " + s.get("reason") + ".");
                continue;
            }
            Object period = s.get("period");
            String season = "detected".equals(s.get("periodSource"))
                    ? "a " + period + "-" + unit + " season found in the data"
                    : "a " + period + "-" + unit + " calendar season";
            Double h = finite(s.get("horizon")), lv = finite(s.get("level"));
            String horizon = h == null ? String.valueOf(s.get("horizon")) : plainInt(h);
            StringBuilder text = new StringBuilder(series + "Forecast of the next " + horizon + " "
                    + (h != null && h == 1 ? unit : unit + "s") + ", " + Math.round((lv == null ? DEFAULT_LEVEL : lv) * 100)
                    + " % band (Holt-Winters, " + season + ").");
            if (s.get("backtest") instanceof Map<?, ?> b) {
                Double origins = finite(b.get("origins"));
                if (b.get("reason") == null && origins != null && origins != 0) {
                    Double mape = finite(b.get("mape")), smape = finite(b.get("smape")), cov = finite(b.get("coverage"));
                    String err = mape != null ? "average error " + oneDecimal(mape) + " %"
                            : "symmetric error " + oneDecimal(smape == null ? Double.NaN : smape) + " %";
                    String cover = cov != null ? ", " + oneDecimal(cov * 100) + " % of actual values inside the band" : "";
                    text.append(" Tested on this history from ").append(plainInt(origins)).append(" starting ")
                            .append(origins == 1 ? "point" : "points").append(": ").append(err).append(cover).append('.');
                } else if (b.get("reason") != null) text.append(" Not tested: ").append(b.get("reason")).append('.');
            }
            lines.add(text.toString());
        }
        List<Map<String, Object>> an = summary.stream().filter(s -> "anomaly".equals(s.get("overlay"))).toList();
        if (!an.isEmpty()) {
            Map<String, Object> why = an.stream().filter(s -> s.get("reason") != null && !scored(s)).findFirst().orElse(null);
            if (why != null && an.stream().noneMatch(ChartMarkers::scored))
                lines.add("No anomaly markers: " + why.get("reason") + ".");
            else {
                String basis = "weekday".equals(an.get(0).get("seasonality")) ? "the same weekday" : "the other values shown";
                Double sv = finite(sensitivity);
                long s = Math.round(Math.min(5, Math.max(1, sv == null ? DEFAULT_SENSITIVITY : sv)));
                lines.add((flagged == 0 ? "No" : String.valueOf(flagged)) + " unusual " + (flagged == 1 ? "value" : "values")
                        + " at sensitivity " + s + " of 5 (at least " + oneDecimal(anomalyThreshold(s))
                        + " robust deviations from " + basis + ").");
            }
        }
        return lines;
    }

    private static boolean scored(Map<String, Object> s) {
        Double n = finite(s.get("scored"));
        return n != null && n != 0;
    }

    private static String plainInt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private static double[] pair(Object v) {
        if (!(v instanceof List<?> l) || l.size() != 2) return null;
        // the SPA reads the pair as stored; a non-number end reads as no end there too (formatted "—"): drop the RAG
        Double lo = finite(l.get(0)), hi = finite(l.get(1));
        return lo == null || hi == null ? null : new double[] {lo, hi};
    }

    /** The SPA's {@code finite}: a JSON number that is finite; anything else (text, null) is no value. */
    static Double finite(Object o) {
        return o instanceof Number n && Double.isFinite(n.doubleValue()) ? n.doubleValue() : null;
    }
}
