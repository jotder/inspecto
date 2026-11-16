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
 * and {@code kpi} Widgets are drawn; anything else is refused by name, never skipped.
 *
 * @param measureIds the result columns holding the measures, in channel order ({@link MeasureCompiler.Measure#id})
 * @param body       the {@code POST /bi/query}-shaped body {@link MeasureCompiler#parse} reads
 */
record ReportWidget(String id, String title, String vizType, String datasetId, String x, String series,
                    List<String> measureIds, List<String> measureLabels, Map<String, Object> body) {

    static final Set<String> DRAWN = Set.of("bar", "line", "kpi");
    /** Rows a chart query may return; a cut result says so on the page. */
    static final int MAX_ROWS = 1000;

    static ReportWidget of(String id, Map<String, Object> w) {
        String vizType = str(w.get("vizType"));
        if (!DRAWN.contains(vizType))
            throw new IllegalArgumentException("widget '" + id + "' is a " + vizType
                    + " Widget; a report PDF draws bar, line and kpi Widgets");
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
        return new ReportWidget(id, title, vizType, datasetId, x, series, ids, labels, body);
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
