package com.gamma.job;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws a {@link ReportWidget}'s query result as a chart image for a report PDF (REPORT-PDF-CHARTS-1): a grouped bar
 * chart, a line chart or a KPI tile. JDK-native only — {@link Graphics2D} on a {@link BufferedImage}, headless-safe,
 * like {@link TablePngRenderer}. A print snapshot of what the dashboard tile shows, not a re-implementation of the
 * SPA's Chart.js options: no palette choice, sort or category-order option is applied beyond the axis order.
 *
 * <p>REPORT-CHART-MARKERS-1: a bar or line Widget's Chart Markers ({@code options.markers}, BI-INSIGHTS-1 I-2) and its
 * forecast band and anomaly markers ({@code options.overlay}, I-11 / I-7) are drawn with the meaning the screen gives
 * them, resolved by {@link ChartMarkers} (the contract-tested twin of the SPA's resolvers): bands shaded behind the
 * data, lines over it, each with its words on the chart, and the overlays' words and caption listed under it.
 */
final class WidgetChartRenderer {

    static final int CHART_W = 960, CHART_H = 520;
    static final int KPI_W = 480, KPI_H = 240;
    /** Series colours, in series order. */
    static final Color[] PALETTE = {
            new Color(0x2E, 0x6F, 0xB7), new Color(0xE0, 0x7B, 0x24), new Color(0x3A, 0x9E, 0x5A),
            new Color(0xC2, 0x3B, 0x3B), new Color(0x7E, 0x57, 0xB0), new Color(0x8C, 0x6D, 0x3F),
            new Color(0xD1, 0x5F, 0xA6), new Color(0x5F, 0x8F, 0x9E)};

    private static final Color TEXT = new Color(0x33, 0x33, 0x33);
    private static final Color MUTED = new Color(0x77, 0x77, 0x77);
    private static final Color GRID = new Color(0xE2, 0xE2, 0xE2);
    /** A marker line with no tone of its own (the SPA's CHART_MARKER_LINE). */
    static final Color MARKER_LINE = new Color(0x4A, 0x4A, 0x4A);
    /** The status tones (the SPA's CHART_TONE): success, warning, error, info. */
    static final Map<String, Color> TONE = Map.of(
            "success", new Color(0x2E, 0x7D, 0x32), "warning", new Color(0xB2, 0x6A, 0x00),
            "error", new Color(0xC6, 0x28, 0x28), "info", new Color(0x15, 0x65, 0xC0));
    /** The forecast band and its dashed point forecast. */
    static final Color FORECAST = new Color(0x5E, 0x35, 0xB1);
    private static final int NOTE_LINE = 16;
    private static final int MARGIN = 20, AXIS_W = 72, AXIS_H = 56, TICKS = 5;
    /** The widest value the axis spans: past it a bar is drawn at the bound, so the axis arithmetic stays finite. */
    static final double AXIS_BOUND = 1e300;
    /** A hard cap on axis ticks, whatever the arithmetic does. */
    private static final int MAX_TICKS = 50;

    private WidgetChartRenderer() {}

    /** The pivoted result: one value array per series, aligned to {@code categories}; {@code NaN} = no point. */
    record Model(List<String> categories, List<String> seriesNames, List<double[]> values,
                 List<ChartMarkers.Cells> overlays) {
        Model(List<String> categories, List<String> seriesNames, List<double[]> values) {
            this(categories, seriesNames, values, java.util.Collections.nCopies(values.size(), null));
        }
    }

    /**
     * Pivot the rows the way the SPA's {@code transformXy} does: categories from {@code x} in row order; with a
     * {@code series} field one series per value of it (the first measure), else one series per measure.
     */
    static Model model(ReportWidget w, List<Map<String, Object>> rows) {
        if (w.x() == null) {   // KPI: the one aggregate row
            double v = rows.isEmpty() ? Double.NaN : number(rows.get(0).get(w.measureIds().get(0)));
            return new Model(List.of(), List.of(w.measureLabels().get(0)), List.of(new double[] {v}));
        }
        Map<String, Integer> cats = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) cats.putIfAbsent(String.valueOf(r.get(w.x())), cats.size());
        Map<String, double[]> series = new LinkedHashMap<>();
        // each series' overlay cells (ChartOverlays' m__fc ... m__z), only where the rows carry them (the SPA's hasOverlay)
        Map<String, double[][]> cells = new LinkedHashMap<>();
        int n = cats.size();
        for (Map<String, Object> r : rows) {
            int c = cats.get(String.valueOf(r.get(w.x())));
            if (w.series() != null) {
                String key = String.valueOf(r.get(w.series()));
                double[] s = series.computeIfAbsent(key, k -> nans(n));
                s[c] = number(r.get(w.measureIds().get(0)));
                cell(cells, key, r, w.measureIds().get(0), c, n, rows);
            } else {
                for (int i = 0; i < w.measureIds().size(); i++) {
                    String key = w.measureLabels().get(i);
                    series.computeIfAbsent(key, k -> nans(n))[c] = number(r.get(w.measureIds().get(i)));
                    cell(cells, key, r, w.measureIds().get(i), c, n, rows);
                }
            }
        }
        List<ChartMarkers.Cells> overlays = new ArrayList<>();
        for (String key : series.keySet()) {
            double[][] x = cells.get(key);
            overlays.add(x == null ? null : new ChartMarkers.Cells(x[0], x[1], x[2], x[3], x[4]));
        }
        return new Model(new ArrayList<>(cats.keySet()), new ArrayList<>(series.keySet()), new ArrayList<>(series.values()),
                overlays);
    }

    private static final String[] OVERLAY_SUFFIXES = {
            com.gamma.query.ChartOverlays.FC, com.gamma.query.ChartOverlays.FC_LO, com.gamma.query.ChartOverlays.FC_HI,
            com.gamma.query.ChartOverlays.EXPECTED, com.gamma.query.ChartOverlays.Z};

    private static void cell(Map<String, double[][]> cells, String key, Map<String, Object> r, String mId, int c, int n,
                             List<Map<String, Object>> rows) {
        if (!cells.containsKey(key)) {
            boolean has = rows.stream().anyMatch(x -> x.containsKey(mId + OVERLAY_SUFFIXES[0]) || x.containsKey(mId + OVERLAY_SUFFIXES[4]));
            cells.put(key, has ? new double[][] {nans(n), nans(n), nans(n), nans(n), nans(n)} : null);
        }
        double[][] x = cells.get(key);
        if (x != null) for (int i = 0; i < OVERLAY_SUFFIXES.length; i++) x[i][c] = number(r.get(mId + OVERLAY_SUFFIXES[i]));
    }

    /** The page image; {@code truncated} adds a "first N rows" note. */
    static BufferedImage render(ReportWidget w, List<Map<String, Object>> rows, boolean truncated) {
        return render(w, rows, truncated, List.of(), null);
    }

    /** What a chart draws over its data, and the words listed under it. */
    record Marks(List<ChartMarkers.Resolved> markers, List<String> notes) {}

    /**
     * The Widget's Chart Markers and overlays, resolved as the SPA's viz-render resolves them: markers on a marker type,
     * the statistics over every drawn value (the category totals when stacked); overlays on an overlay type, none on a
     * stacked or sorted chart (which says so).
     */
    static Marks marks(ReportWidget w, Model m, List<Map<String, Object>> overlaySummary, Map<?, ?> kpi) {
        List<ChartMarkers.Resolved> out = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Map<?, ?> o = w.options();
        boolean stacked = truthy(o.get("stacked"));
        if (ChartMarkers.MARKER_VIZ_TYPES.contains(w.vizType()) && o.get("markers") instanceof List<?> markers && !markers.isEmpty()) {
            Object target = o.get("kpi") instanceof Map<?, ?> k ? k.get("target") : null;
            out.addAll(ChartMarkers.resolve(markers, ChartMarkers.population(m.values(), m.categories().size(), stacked),
                    target, kpi, ChartMarkers::plain));
        }
        if (ChartMarkers.OVERLAY_VIZ_TYPES.contains(w.vizType()) && o.get("overlay") instanceof Map<?, ?> ov
                && (ov.get("forecast") != null || ov.get("anomaly") != null)) {
            if (stacked || truthy(o.get("sort"))) {
                if (overlaySummary != null && !overlaySummary.isEmpty()) notes.add(ChartMarkers.HIDDEN_NOTE);
            } else {
                List<ChartMarkers.Series> series = new ArrayList<>();
                for (int i = 0; i < m.values().size(); i++)
                    series.add(new ChartMarkers.Series(m.seriesNames().get(i), m.values().get(i), m.overlays().get(i)));
                List<ChartMarkers.Resolved> ovs = ChartMarkers.resolveOverlays(series, m.categories(), ov, ChartMarkers::plain);
                out.addAll(ovs);
                for (ChartMarkers.Resolved r : ovs) notes.add(r.text() + ".");
                int flagged = ovs.stream().filter(r -> "points".equals(r.type())).mapToInt(r -> r.points().size()).sum();
                Object sensitivity = ov.get("anomaly") instanceof Map<?, ?> a ? a.get("sensitivity") : null;
                notes.addAll(ChartMarkers.overlayNote(overlaySummary, flagged, sensitivity, ChartMarkers.grainUnit(m.categories())));
            }
        }
        return new Marks(out, notes);
    }

    private static boolean truthy(Object v) {
        return v != null && !Boolean.FALSE.equals(v) && !"".equals(v) && !(v instanceof Number n && n.doubleValue() == 0);
    }

    /**
     * The page image with the Widget's Chart Markers and overlays ({@code overlaySummary} = the query's
     * {@code ChartOverlays} summary; {@code kpiDef} = the bound KPI's stored definition, or null).
     */
    static BufferedImage render(ReportWidget w, List<Map<String, Object>> rows, boolean truncated,
                                List<Map<String, Object>> overlaySummary, Map<?, ?> kpiDef) {
        Model m = model(w, rows);
        boolean kpi = w.x() == null;
        Marks marks = kpi ? new Marks(List.of(), List.of()) : marks(w, m, overlaySummary, kpiDef);
        List<String> noteLines = new ArrayList<>();
        Graphics2D probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
        probe.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        for (String note : marks.notes()) noteLines.addAll(wrap(note, probe.getFontMetrics(), CHART_W - 2 * MARGIN));
        probe.dispose();
        int height = kpi ? KPI_H : CHART_H + (noteLines.isEmpty() ? 0 : noteLines.size() * NOTE_LINE + 12);
        BufferedImage img = new BufferedImage(kpi ? KPI_W : CHART_W, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            g.setColor(TEXT);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
            g.drawString(w.title(), MARGIN, MARGIN + g.getFontMetrics().getAscent());
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            if (truncated) {
                g.setColor(MUTED);
                g.drawString("first " + rows.size() + " rows of the Widget's query", MARGIN, (kpi ? KPI_H : CHART_H) - 8);
            }
            if (kpi) drawKpi(g, m);
            else drawXy(g, m, "line".equals(w.vizType()), marks.markers());
            g.setColor(TEXT);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            for (int i = 0; i < noteLines.size(); i++)
                g.drawString(noteLines.get(i), MARGIN, CHART_H + (i + 1) * NOTE_LINE);
        } finally {
            g.dispose();
        }
        return img;
    }

    private static void drawKpi(Graphics2D g, Model m) {
        double v = m.values().get(0)[0];
        g.setColor(PALETTE[0]);
        g.fillRect(MARGIN, 56, 6, 120);
        g.setColor(TEXT);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 56));
        g.drawString(Double.isNaN(v) ? "—" : format(v), MARGIN + 24, 140);
        g.setColor(MUTED);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        g.drawString(m.seriesNames().get(0), MARGIN + 26, 170);
    }

    private static void drawXy(Graphics2D g, Model m, boolean line, List<ChartMarkers.Resolved> markers) {
        int left = MARGIN + AXIS_W, right = CHART_W - MARGIN, top = MARGIN + 52, bottom = CHART_H - AXIS_H;
        int n = Math.max(1, m.categories().size());
        // REPORT-PDF-CHARTS-1 review: a value near Double.MAX_VALUE made max - min (or the rounded-up top) Infinity and the
        // tick loop below never ended. Every plotted value is held to ±AXIS_BOUND, so the range and its step stay finite.
        List<double[]> plotted = new ArrayList<>();
        for (double[] s : m.values()) {
            double[] c = s.clone();
            for (int i = 0; i < c.length; i++) if (!Double.isNaN(c[i])) c[i] = Math.max(-AXIS_BOUND, Math.min(AXIS_BOUND, c[i]));
            plotted.add(c);
        }
        double max = 0, min = 0;
        for (double[] s : plotted)
            for (double v : s) if (!Double.isNaN(v)) { max = Math.max(max, v); min = Math.min(min, v); }
        // the axis spans every marker too (the SPA plugin's afterDataLimits): a target above the bars is still drawn
        for (double v : markerValues(markers)) { max = Math.max(max, v); min = Math.min(min, v); }
        double step = niceStep((max - min) / TICKS);
        double lo = Math.floor(min / step) * step, hi = Math.max(lo + step, Math.ceil(max / step) * step);
        if (!Double.isFinite(lo) || !Double.isFinite(hi) || !(hi > lo)) { lo = min; hi = max > min ? max : min + 1; step = (hi - lo) / TICKS; }
        FontMetrics fm = g.getFontMetrics();

        // legend (only when there is more than one series to tell apart)
        if (m.seriesNames().size() > 1) {
            int lx = left;
            for (int s = 0; s < m.seriesNames().size(); s++) {
                String name = fit(m.seriesNames().get(s), fm, 160);
                g.setColor(PALETTE[s % PALETTE.length]);
                g.fillRect(lx, top - 26, 12, 12);
                g.setColor(TEXT);
                g.drawString(name, lx + 16, top - 16);
                lx += 16 + fm.stringWidth(name) + 18;
                if (lx > right - 80) break;
            }
        }
        // y axis: gridlines + labels
        for (int i = 0; i <= MAX_TICKS; i++) {   // by index, never t += step: a step lost to rounding cannot spin
            double t = lo + i * step;
            if (!(t <= hi + step / 2)) break;
            int y = yOf(t, lo, hi, top, bottom);
            g.setColor(GRID);
            g.drawLine(left, y, right, y);
            g.setColor(MUTED);
            String label = format(t);
            g.drawString(label, left - 8 - fm.stringWidth(label), y + fm.getAscent() / 2 - 1);
        }
        // x labels, thinned so they never overlap
        double slot = (double) (right - left) / n;
        int every = Math.max(1, (int) Math.ceil(n * 110.0 / (right - left)));
        for (int c = 0; c < m.categories().size(); c += every) {
            String label = fit(m.categories().get(c), fm, (int) Math.max(40, slot * every - 6));
            int cx = (int) (left + slot * (c + 0.5));
            g.setColor(MUTED);
            g.drawString(label, cx - fm.stringWidth(label) / 2, bottom + 18);
        }
        int zero = yOf(Math.max(lo, 0), lo, hi, top, bottom);
        int seriesCount = m.values().size();
        drawBehind(g, markers, lo, hi, left, right, top, bottom, slot);
        for (int s = 0; s < seriesCount; s++) {
            double[] vals = plotted.get(s);
            g.setColor(PALETTE[s % PALETTE.length]);
            if (line) {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int px = -1, py = -1;
                for (int c = 0; c < vals.length; c++) {
                    if (Double.isNaN(vals[c])) { px = -1; continue; }   // a gap, never a zero
                    int cx = (int) (left + slot * (c + 0.5)), cy = yOf(vals[c], lo, hi, top, bottom);
                    if (px >= 0) g.drawLine(px, py, cx, cy);
                    g.fillOval(cx - 4, cy - 4, 8, 8);
                    px = cx;
                    py = cy;
                }
            } else {
                double group = slot * 0.8, bar = group / seriesCount;
                for (int c = 0; c < vals.length; c++) {
                    if (Double.isNaN(vals[c])) continue;
                    int x0 = (int) Math.round(left + slot * c + slot * 0.1 + bar * s);
                    int x1 = (int) Math.round(left + slot * c + slot * 0.1 + bar * (s + 1)) - 1;
                    int y = yOf(vals[c], lo, hi, top, bottom);
                    g.fillRect(x0, Math.min(y, zero), Math.max(1, x1 - x0), Math.max(1, Math.abs(zero - y)));
                }
            }
        }
        g.setColor(MUTED);
        g.setStroke(new BasicStroke(1f));
        g.drawLine(left, zero, right, zero);
        drawOver(g, markers, lo, hi, left, right, top, bottom, slot);
    }

    /** Every finite value a marker places, held to the axis bound like the data. */
    private static double[] markerValues(List<ChartMarkers.Resolved> markers) {
        List<Double> out = new ArrayList<>();
        for (ChartMarkers.Resolved r : markers) {
            if (r.value() != null) out.add(r.value());
            if (r.from() != null) out.add(r.from());
            if (r.to() != null) out.add(r.to());
            for (ChartMarkers.Point p : r.points()) {
                out.add(p.value());
                out.add(p.lower());
                out.add(p.upper());
            }
        }
        return out.stream().mapToDouble(Double::doubleValue).filter(Double::isFinite)
                .map(v -> Math.max(-AXIS_BOUND, Math.min(AXIS_BOUND, v))).toArray();
    }

    private static int yClamped(double v, double lo, double hi, int top, int bottom) {
        return Math.max(top, Math.min(bottom, yOf(Math.max(-AXIS_BOUND, Math.min(AXIS_BOUND, v)), lo, hi, top, bottom)));
    }

    private static Color tone(String tone, Color fallback) {
        return tone == null ? fallback : TONE.getOrDefault(tone, TONE.get("info"));
    }

    private static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    /** Bands and forecast bands, shaded behind the data; a band's words at its top left. */
    private static void drawBehind(Graphics2D g, List<ChartMarkers.Resolved> markers, double lo, double hi,
                                   int left, int right, int top, int bottom, double slot) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        for (ChartMarkers.Resolved r : markers) {
            if ("band".equals(r.type())) {
                int y0 = r.to() == null ? top : yClamped(r.to(), lo, hi, top, bottom);
                int y1 = r.from() == null ? bottom : yClamped(r.from(), lo, hi, top, bottom);
                Color c = tone(r.tone(), TONE.get("info"));
                g.setColor(alpha(c, 34));
                g.fillRect(left, y0, right - left, Math.max(1, y1 - y0));
            } else if ("interval".equals(r.type()) && !r.points().isEmpty()) {
                List<ChartMarkers.Point> pts = r.points();
                g.setColor(alpha(FORECAST, 46));
                if (pts.size() == 1) {   // one bucket: a block of half the slot, so a one-point band is still seen
                    ChartMarkers.Point p = pts.get(0);
                    int y0 = yClamped(p.upper(), lo, hi, top, bottom), y1 = yClamped(p.lower(), lo, hi, top, bottom);
                    g.fillRect((int) (left + slot * (p.index() + 0.25)), y0, (int) Math.max(2, slot * 0.5), Math.max(1, y1 - y0));
                    continue;
                }
                java.awt.geom.Path2D.Double band = new java.awt.geom.Path2D.Double();
                for (int i = 0; i < pts.size(); i++) {
                    double x = left + slot * (pts.get(i).index() + 0.5);
                    double y = yClamped(pts.get(i).upper(), lo, hi, top, bottom);
                    if (i == 0) band.moveTo(x, y);
                    else band.lineTo(x, y);
                }
                for (int i = pts.size() - 1; i >= 0; i--)
                    band.lineTo(left + slot * (pts.get(i).index() + 0.5), yClamped(pts.get(i).lower(), lo, hi, top, bottom));
                band.closePath();
                g.fill(band);
            }
        }
    }

    /** Lines, the dashed point forecast and the anomaly rings over the data; a line's words at its right end. */
    private static void drawOver(Graphics2D g, List<ChartMarkers.Resolved> markers, double lo, double hi,
                                 int left, int right, int top, int bottom, double slot) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        BasicStroke dash = new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[] {7f, 5f}, 0f);
        for (ChartMarkers.Resolved r : markers) {
            switch (r.type()) {
                case "line" -> {
                    int y = yClamped(r.value(), lo, hi, top, bottom);
                    Color c = tone(r.tone(), MARKER_LINE);
                    g.setColor(c);
                    g.setStroke(r.dashed() ? dash : new BasicStroke(2f));
                    g.drawLine(left, y, right, y);
                }
                case "interval" -> {
                    g.setColor(FORECAST);
                    g.setStroke(dash);
                    int px = -1, py = -1;
                    for (ChartMarkers.Point p : r.points()) {
                        int x = (int) (left + slot * (p.index() + 0.5)), y = yClamped(p.value(), lo, hi, top, bottom);
                        if (px >= 0) g.drawLine(px, py, x, y);
                        g.fillOval(x - 3, y - 3, 6, 6);
                        px = x;
                        py = y;
                    }
                }
                case "points" -> {
                    g.setColor(tone(r.tone(), TONE.get("error")));
                    g.setStroke(new BasicStroke(2.5f));
                    for (ChartMarkers.Point p : r.points()) {
                        int x = (int) (left + slot * (p.index() + 0.5)), y = yClamped(p.value(), lo, hi, top, bottom);
                        g.drawOval(x - 9, y - 9, 18, 18);
                    }
                }
                default -> { }
            }
        }
        g.setStroke(new BasicStroke(1f));
        // the words last, over the data: a band's at its top left, a line's at its right end — moved up past one
        // already placed, so two close lines (a target beside the average) never print over each other
        FontMetrics fm = g.getFontMetrics();
        int step = fm.getAscent() + fm.getDescent() + 2;
        List<Integer> left0 = new ArrayList<>(), right0 = new ArrayList<>();
        for (ChartMarkers.Resolved r : markers) {
            boolean band = "band".equals(r.type());
            if (!band && !"line".equals(r.type())) continue;
            String text = fit(r.text(), fm, (right - left) / 2 - 8);
            int base = band ? (r.to() == null ? top : yClamped(r.to(), lo, hi, top, bottom)) + fm.getAscent() + 2
                    : yClamped(r.value(), lo, hi, top, bottom) - 5;
            List<Integer> used = band ? left0 : right0;
            for (int guard = 0; guard < 40 && clashes(used, base, step); guard++) base += band ? step : -step;
            used.add(base);
            Color c = band ? tone(r.tone(), TONE.get("info")) : tone(r.tone(), MARKER_LINE);
            label(g, text, band ? left + 4 : right - 4 - fm.stringWidth(text), base, c);
        }
    }

    private static boolean clashes(List<Integer> used, int base, int step) {
        return used.stream().anyMatch(u -> Math.abs(u - base) < step);
    }

    /** Words on the chart, on a white plate so a gridline or bar never runs through them. */
    private static void label(Graphics2D g, String text, int x, int baseline, Color c) {
        FontMetrics fm = g.getFontMetrics();
        g.setColor(new Color(255, 255, 255, 215));
        g.fillRect(x - 2, baseline - fm.getAscent(), fm.stringWidth(text) + 4, fm.getAscent() + fm.getDescent());
        g.setColor(c.darker());
        g.drawString(text, x, baseline);
    }

    /** Word-wrap one note to {@code max} pixels. */
    private static List<String> wrap(String text, FontMetrics fm, int max) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (fm.stringWidth(next) > max && !line.isEmpty()) {
                out.add(line.toString());
                line = new StringBuilder(word);
            } else line = new StringBuilder(next);
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }


    private static int yOf(double v, double lo, double hi, int top, int bottom) {
        return (int) Math.round(bottom - (v - lo) / (hi - lo) * (bottom - top));
    }

    /** 1, 2 or 5 × a power of ten, at least {@code raw}. */
    private static double niceStep(double raw) {
        if (!(raw > 0)) return 1;
        double p = Math.pow(10, Math.floor(Math.log10(raw)));
        double f = raw / p;
        return (f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10) * p;
    }

    /** Compact: 1.2K / 3.4M / 5.6B above a thousand, at most two decimals below. */
    static String format(double v) {
        double a = Math.abs(v);
        if (a >= 1e15) return String.format(java.util.Locale.ROOT, "%.3g", v);   // past what a long holds exactly
        if (a >= 1e9) return trim(v / 1e9) + "B";
        if (a >= 1e6) return trim(v / 1e6) + "M";
        if (a >= 1e4) return trim(v / 1e3) + "K";
        return trim(v);
    }

    private static String trim(double v) {
        if (v == Math.rint(v)) return String.valueOf((long) v);
        String s = String.format(java.util.Locale.ROOT, "%.2f", v);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static double number(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o == null) return Double.NaN;
        try {
            return Double.parseDouble(String.valueOf(o).trim());
        } catch (NumberFormatException notANumber) {
            return Double.NaN;
        }
    }

    private static double[] nans(int n) {
        double[] a = new double[n];
        Arrays.fill(a, Double.NaN);
        return a;
    }

    private static String fit(String s, FontMetrics fm, int max) {
        if (fm.stringWidth(s) <= max) return s;
        int len = s.length();
        while (len > 0 && fm.stringWidth(s.substring(0, len) + "…") > max) len--;
        return s.substring(0, len) + "…";
    }
}
