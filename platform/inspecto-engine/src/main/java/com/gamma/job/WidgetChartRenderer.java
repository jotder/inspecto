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
    private static final int MARGIN = 20, AXIS_W = 72, AXIS_H = 56, TICKS = 5;
    /** The widest value the axis spans: past it a bar is drawn at the bound, so the axis arithmetic stays finite. */
    static final double AXIS_BOUND = 1e300;
    /** A hard cap on axis ticks, whatever the arithmetic does. */
    private static final int MAX_TICKS = 50;

    private WidgetChartRenderer() {}

    /** The pivoted result: one value array per series, aligned to {@code categories}; {@code NaN} = no point. */
    record Model(List<String> categories, List<String> seriesNames, List<double[]> values) {}

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
        for (Map<String, Object> r : rows) {
            int c = cats.get(String.valueOf(r.get(w.x())));
            if (w.series() != null) {
                double[] s = series.computeIfAbsent(String.valueOf(r.get(w.series())), k -> nans(cats.size()));
                s[c] = number(r.get(w.measureIds().get(0)));
            } else {
                for (int i = 0; i < w.measureIds().size(); i++)
                    series.computeIfAbsent(w.measureLabels().get(i), k -> nans(cats.size()))[c] =
                            number(r.get(w.measureIds().get(i)));
            }
        }
        return new Model(new ArrayList<>(cats.keySet()), new ArrayList<>(series.keySet()), new ArrayList<>(series.values()));
    }

    /** The page image; {@code truncated} adds a "first N rows" note. */
    static BufferedImage render(ReportWidget w, List<Map<String, Object>> rows, boolean truncated) {
        Model m = model(w, rows);
        boolean kpi = w.x() == null;
        BufferedImage img = new BufferedImage(kpi ? KPI_W : CHART_W, kpi ? KPI_H : CHART_H, BufferedImage.TYPE_INT_RGB);
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
                g.drawString("first " + rows.size() + " rows of the Widget's query", MARGIN, img.getHeight() - 8);
            }
            if (kpi) drawKpi(g, m);
            else drawXy(g, m, "line".equals(w.vizType()));
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

    private static void drawXy(Graphics2D g, Model m, boolean line) {
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
