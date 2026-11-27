package com.gamma.job;

import com.gamma.query.MeasureCompiler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A Studio Widget as a report PDF draws it (REPORT-PDF-CHARTS-1): its title, its type and the headless BI query its
 * field mapping compiles to — the server twin of the SPA's {@code buildXyQuery} / {@code buildValueQuery}
 * ({@code inspecto-ui/src/app/inspecto/viz/plugins/plugin-helpers.ts}). Only Dataset-bound {@code bar}, {@code line}
 * and {@code kpi} Widgets are drawn, and a view-bound {@code insight-brief} Widget is drawn as a Brief page
 * (BI-INSIGHTS-1 P4 BR-4, {@link BriefPageProvider}); anything else is refused by name, never skipped.
 *
 * @param measureIds the result columns holding the measures, in channel order ({@link MeasureCompiler.Measure#id})
 * @param body       the {@code POST /bi/query}-shaped body {@link MeasureCompiler#parse} reads
 * @param viewId     an {@code insight-brief} Widget's Insight Profile; {@code null} for every Dataset-bound Widget
 * @param options    the Widget's stored {@code options} — its Chart Markers, bound KPI and overlay
 *                   (REPORT-CHART-MARKERS-1, drawn by {@link WidgetChartRenderer})
 */
record ReportWidget(String id, String title, String vizType, String datasetId, String x, String series,
                    List<String> measureIds, List<String> measureLabels, Map<String, Object> body,
                    Map<?, ?> options, String viewId) {

    static final Set<String> DRAWN = Set.of("bar", "line", "kpi");
    /** The Dashboard Brief Widget: view-bound to an Insight Profile, drawn as a text page. */
    static final String BRIEF = "insight-brief";
    /** Rows a chart query may return; a cut result says so on the page. */
    static final int MAX_ROWS = 1000;

    static ReportWidget of(String id, Map<String, Object> w) {
        String vizType = str(w.get("vizType"));
        if (BRIEF.equals(vizType)) {
            String profile = str(w.get("viewId"));
            if (profile == null)
                throw new IllegalArgumentException("widget '" + id + "' is an insight-brief Widget with no Insight Profile bound to it");
            Map<?, ?> o = w.get("options") instanceof Map<?, ?> m ? m : Map.of();
            return new ReportWidget(id, str(o.get("title")) != null ? str(o.get("title")) : id, vizType, null, null, null,
                    List.of(), List.of(), Map.of(), o, profile);
        }
        if (!DRAWN.contains(vizType))
            throw new IllegalArgumentException("widget '" + id + "' is a " + vizType
                    + " Widget; a report PDF draws bar, line and kpi Widgets, and a Dashboard Brief (insight-brief)");
        String datasetId = str(w.get("datasetId"));
        if (datasetId == null || str(w.get("viewId")) != null || str(w.get("queryId")) != null)
            throw new IllegalArgumentException("widget '" + id + "' is not bound to a Dataset (a view-, query- or "
                    + "KPI-bound Widget); a report PDF draws Dataset-bound Widgets only");
        Map<?, ?> options = w.get("options") instanceof Map<?, ?> o ? o : Map.of();
        // The dashboard draws a KPI tile that names a KPI definition from the DEFINITION, whatever datasetId it also carries
        // (kpi-tile host); drawing the Dataset here would print a different number under the same title.
        if ("kpi".equals(vizType) && options.get("kpi") instanceof Map<?, ?> k && str(k.get("kpiId")) != null)
            throw new IllegalArgumentException("widget '" + id + "' is bound to the KPI definition '" + str(k.get("kpiId"))
                    + "'; a report PDF draws Dataset-bound Widgets only");
        Map<?, ?> controls = w.get("controls") instanceof Map<?, ?> c ? c : Map.of();
        String title = str(options.get("title")) != null ? str(options.get("title")) : id;

        List<Map<?, ?>> measures = new ArrayList<>();
        String x = null, series = null;
        Map<String, String> grains = new LinkedHashMap<>();
        if ("kpi".equals(vizType)) {
            Map<?, ?> value = first(controls, "value");
            if (value == null) value = first(controls, "y");
            if (value != null) measures.add(value);
        } else {
            Map<?, ?> xc = first(controls, "x"), sc = first(controls, "series");
            x = xc == null ? null : str(xc.get("field"));
            if (x == null) throw new IllegalArgumentException("widget '" + id + "' has no x field");
            series = sc == null ? null : str(sc.get("field"));
            // the SPA's channelGrains: a grain on either dimension channel, "auto" meaning none
            for (Map<?, ?> dim : java.util.Arrays.asList(xc, sc))
                if (dim != null && str(dim.get("field")) != null && str(dim.get("grain")) != null
                        && !"auto".equals(str(dim.get("grain"))))
                    grains.put(str(dim.get("field")), str(dim.get("grain")));
            if (controls.get("y") instanceof List<?> ys)
                for (Object y : ys) if (y instanceof Map<?, ?> m) measures.add(m);
        }
        if (measures.isEmpty()) throw new IllegalArgumentException("widget '" + id + "' has no measure");

        List<Map<String, Object>> specMeasures = new ArrayList<>();
        List<String> ids = new ArrayList<>(), labels = new ArrayList<>();
        for (Map<?, ?> m : measures) {
            if (m.get("expression") != null)
                throw new IllegalArgumentException("widget '" + id + "' uses a named measure, which a report PDF "
                        + "cannot draw; map the field with an aggregation");
            String agg = str(m.get("agg")) == null ? "sum" : str(m.get("agg"));   // the SPA's default, channelMeasure
            String field = str(m.get("field"));
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("agg", agg);
            if (field != null) sm.put("field", field);
            specMeasures.add(sm);
            ids.add(new MeasureCompiler.Measure(agg, field).id());
            labels.add("count".equals(agg) ? "count" : agg + "(" + field + ")");
        }

        List<String> groupBy = new ArrayList<>();
        if (x != null) groupBy.add(x);
        if (series != null && !series.equals(x)) groupBy.add(series);
        List<Map<String, Object>> orderBy = new ArrayList<>();
        // Categories in axis order, marked COSMETIC exactly as the SPA's categoryOrderBy does: for a masked dimension the
        // report masking drops the term (ordering by the first Measure instead) rather than refusing, as for the dashboard.
        for (String g : groupBy) orderBy.add(Map.of("field", g, "dir", "asc", "cosmetic", true));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dataset", datasetId);
        body.put("measures", specMeasures);
        body.put("groupBy", groupBy);
        if (!grains.isEmpty()) body.put("grains", grains);
        if (!orderBy.isEmpty()) body.put("orderBy", orderBy);
        body.put("limit", MAX_ROWS);
        // REPORT-CHART-MARKERS-1: the forecast band and anomaly scores (BI-INSIGHTS-1 I-11 / I-7) are asked for exactly as
        // the SPA's buildXyQuery asks — a line or bar Widget whose x channel picked a real grain — and computed by the same
        // ChartOverlays over this query's own masked rows (ReportJob). The sensitivity stays in the options: it is the renderer's.
        Map<?, ?> xc = first(controls, "x");
        Map<String, Object> overlay = overlayWire(options.get("overlay"));
        if (overlay != null && ChartMarkers.OVERLAY_VIZ_TYPES.contains(vizType) && xc != null
                && str(xc.get("grain")) != null && !"auto".equals(str(xc.get("grain"))))
            body.put("overlay", overlay);
        return new ReportWidget(id, title, vizType, datasetId, x, series, ids, labels, body, options, null);
    }

    /** The wire fragment {@code {forecast?: {horizon?, level?}, anomaly?: {}}} of {@code options.overlay} (the SPA's overlayFragment). */
    private static Map<String, Object> overlayWire(Object raw) {
        if (!(raw instanceof Map<?, ?> o) || (o.get("forecast") == null && o.get("anomaly") == null)) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        if (o.get("forecast") instanceof Map<?, ?> f) {
            Map<String, Object> fc = new LinkedHashMap<>();
            if (f.get("horizon") != null) fc.put("horizon", f.get("horizon"));
            if (f.get("level") != null) fc.put("level", f.get("level"));
            out.put("forecast", fc);
        }
        if (o.get("anomaly") != null) out.put("anomaly", Map.of());
        return out.isEmpty() ? null : out;
    }

    private static Map<?, ?> first(Map<?, ?> controls, String channel) {
        return controls.get(channel) instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> m ? m : null;
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}
