package com.gamma.la.api;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code format=html} rendering of a Dossier (LA-DOSSIER-OUTPUT-1): one self-contained, printable page — inline
 * CSS with a print stylesheet, no scripts, no external fetches — over the SAME already-masked map the json rendering
 * answers, so masking is applied exactly once, upstream ({@link DossierRoutes#maskedDossier}).
 *
 * <p>⛔ Every interpolated value goes through {@link #esc}: titles, notes and masked keys are analyst- and
 * data-controlled. The route also sends {@code Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'}.
 */
final class DossierHtml {

    private DossierHtml() {}

    private static final String CSS = """
            body{font:13px/1.45 system-ui,sans-serif;color:#111;background:#fff;margin:24px;max-width:1100px}
            h1{font-size:20px;margin:0 0 4px}h2{font-size:15px;margin:22px 0 6px;border-bottom:1px solid #999}
            table{border-collapse:collapse;width:100%;margin:4px 0}th,td{border:1px solid #bbb;padding:3px 6px;
            text-align:left;vertical-align:top;word-break:break-word}th{background:#eee}
            pre{white-space:pre-wrap;word-break:break-word;font:12px/1.4 ui-monospace,monospace}
            .meta{color:#444}.hash{font-family:ui-monospace,monospace;font-size:11px;word-break:break-all}
            ul{margin:2px 0;padding-left:18px}
            @media print{body{margin:0;max-width:none}h2{break-after:avoid}tr,pre{break-inside:avoid}
            th{background:#eee;-webkit-print-color-adjust:exact;print-color-adjust:exact}@page{margin:14mm}}
            """;

    /** Render the dossier envelope ({@code id}, {@code generatedAt}, sections, {@code masking}) as one HTML page. */
    @SuppressWarnings("unchecked")
    static String render(Map<String, Object> d) {
        Map<String, Object> summary = (Map<String, Object>) d.get("summary");
        Map<String, Object> renderings = (Map<String, Object>) d.get("renderings");
        Map<String, Object> manifest = (Map<String, Object>) d.get("manifest");
        Object title = summary == null ? null : summary.get("title");
        StringBuilder b = new StringBuilder(8192);
        b.append("<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\"><title>Dossier ")
         .append(esc(d.get("id"))).append("</title><style>").append(CSS).append("</style></head><body>\n");
        b.append("<h1>Dossier — ").append(esc(title == null ? d.get("id") : title)).append("</h1>\n")
         .append("<p class=\"meta\">Investigation ").append(esc(d.get("id"))).append(" · generated ")
         .append(esc(d.get("generatedAt"))).append(" · manifest root <span class=\"hash\">")
         .append(esc(manifest == null ? null : manifest.get("root"))).append("</span></p>\n");
        section(b, "Summary", value(summary));
        section(b, "Masking", value(d.get("masking")));
        section(b, "Steps", renderings == null ? "" : value(renderings.get("steps")));
        section(b, "Method", "<pre>" + esc(renderings == null ? null : renderings.get("method")) + "</pre>");
        section(b, "Ledger", value(d.get("ledger")));
        section(b, "Negative space", value(d.get("negativeSpace")));
        section(b, "Topology", value(d.get("topology")));
        section(b, "Scores", value(d.get("scores")));
        section(b, "Working set", value(d.get("workingSet")));
        // Sealed op sections exist only when the log has such an op, so a Dossier without one renders byte-identically.
        if (d.get("comparisons") != null) section(b, "Comparisons", value(d.get("comparisons")));
        if (d.get("temporalFindings") != null) section(b, "Temporal findings", value(d.get("temporalFindings")));
        section(b, "Integrity", value(d.get("integrity")));
        section(b, "Manifest", value(manifest));
        return b.append("</body></html>\n").toString();
    }

    private static void section(StringBuilder b, String heading, String body) {
        b.append("<h2>").append(heading).append("</h2>\n").append(body).append('\n');
    }

    /** A value of any JSON shape: map → key/value table, list of maps → table, list → list, scalar → text. */
    private static String value(Object v) {
        if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) return "<p class=\"meta\">(none)</p>";
            StringBuilder b = new StringBuilder("<table>");
            for (Map.Entry<?, ?> e : m.entrySet())
                b.append("<tr><th>").append(esc(e.getKey())).append("</th><td>").append(value(e.getValue())).append("</td></tr>");
            return b.append("</table>").toString();
        }
        if (v instanceof List<?> l) {
            if (l.isEmpty()) return "<p class=\"meta\">(none)</p>";
            if (l.stream().allMatch(o -> o instanceof Map<?, ?>)) {
                Set<Object> cols = new LinkedHashSet<>();
                for (Object o : l) cols.addAll(((Map<?, ?>) o).keySet());
                StringBuilder b = new StringBuilder("<table><tr>");
                for (Object c : cols) b.append("<th>").append(esc(c)).append("</th>");
                b.append("</tr>");
                for (Object o : l) {
                    b.append("<tr>");
                    for (Object c : cols) b.append("<td>").append(value(((Map<?, ?>) o).get(c))).append("</td>");
                    b.append("</tr>");
                }
                return b.append("</table>").toString();
            }
            StringBuilder b = new StringBuilder("<ul>");
            for (Object o : l) b.append("<li>").append(value(o)).append("</li>");
            return b.append("</ul>").toString();
        }
        return v == null ? "" : esc(v);
    }

    /** HTML-escape for text AND attribute context; {@code &} first so nothing is double-decoded. */
    static String esc(Object o) {
        if (o == null) return "";
        String s = String.valueOf(o);
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }
}
