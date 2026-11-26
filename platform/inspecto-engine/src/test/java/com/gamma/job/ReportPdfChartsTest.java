package com.gamma.job;

import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REPORT-PDF-CHARTS-1: a {@code format: pdf} report Job with {@code widgets:} carries each bar / line / KPI Widget as a
 * chart page, drawn on the server from the Widget's own query, before the table page. The assertions read the PDF the
 * Job wrote — its pages and the pixels of each page image — not the renderer's word for it.
 */
class ReportPdfChartsTest {

    @BeforeEach
    void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @AfterEach
    void clearRoot() {
        System.clearProperty("assist.write.root");
        AttachApprovals.installVerifier(null);
    }

    private static void seed(Path writeRoot) throws Exception {
        new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("sales_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES ('EU','2026-11-01',10.0),('EU','2026-11-02',30.0),('US','2026-11-01',5.0)) "
                        + "AS t(region,day,amount)", "2026-07-08T00:00:00Z"));
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        store.write("dataset", "sales_ds", Map.of("view", "sales_view"));
        store.write("widget", "amount_by_region", widget("bar", Map.of(
                "x", List.of(Map.of("field", "region")),
                "y", List.of(Map.of("field", "amount", "agg", "sum"))), "Amount by region"));
        store.write("widget", "daily_amount", widget("line", Map.of(
                "x", List.of(Map.of("field", "day")),
                "y", List.of(Map.of("field", "amount", "agg", "sum")),
                "series", List.of(Map.of("field", "region"))), "Daily amount"));
        store.write("widget", "sales_count", widget("kpi", Map.of(
                "value", List.of(Map.of("field", "amount", "agg", "count"))), "Sales"));
        System.setProperty("assist.write.root", writeRoot.toString());
    }

    private static Map<String, Object> widget(String vizType, Map<String, Object> controls, String title) {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("vizType", vizType);
        w.put("datasetId", "sales_ds");
        w.put("controls", controls);
        w.put("options", Map.of("title", title));
        return w;
    }

    private static JobConfig job(Map<String, String> params) {
        return new JobConfig("weekly_sales", JobType.REPORT, null, null, true, false, params);
    }

    private static Map<String, String> pdfParams(Path outDir, String widgets) {
        Map<String, String> p = new LinkedHashMap<>(Map.of("scope", "dataset", "dataset", "sales_ds", "format", "pdf",
                "measures", "sum(amount),count", "group_by", "region", "out_dir", outDir.toString()));
        if (widgets != null) p.put("widgets", widgets);
        return p;
    }

    private static byte[] delivered(Path outDir) throws Exception {
        try (Stream<Path> files = Files.list(outDir)) {
            return Files.readAllBytes(files.findFirst().orElseThrow());
        }
    }

    // ── the chart pages ─────────────────────────────────────────────────────────────────────────

    @Test
    void aPdfWithABarWidgetCarriesTheChartPageBeforeTheTable(@TempDir Path writeRoot, @TempDir Path outDir)
            throws Exception {
        seed(writeRoot);
        JobResult r = new ReportJob(job(pdfParams(outDir, "amount_by_region")), null).run();
        assertEquals("SUCCESS", r.status(), r.message());

        byte[] pdf = delivered(outDir);
        assertEquals(2, pages(pdf), "one chart page + the table page");
        List<Img> images = images(pdf);
        assertEquals(2, images.size());
        Img chart = images.get(0), table = images.get(1);

        int bar = WidgetChartRenderer.PALETTE[0].getRGB() & 0xFFFFFF;
        assertEquals(0, table.count(bar), "the table page has no chart ink: the chart is not the table");
        // Two bars, EU (40) then US (5): the drawing follows the Widget's own query, not a fixed picture.
        List<int[]> bars = chart.columnRuns(bar);
        assertEquals(2, bars.size(), "one bar per region");
        int eu = bars.get(0)[2], us = bars.get(1)[2];
        assertTrue(eu > 6 * us && eu < 10 * us, "EU (40) is eight times US (5): heights " + eu + " / " + us);
    }

    @Test
    void lineAndKpiWidgetsEachGetAPageInTheOrderListed(@TempDir Path writeRoot, @TempDir Path outDir) throws Exception {
        seed(writeRoot);
        JobResult r = new ReportJob(job(pdfParams(outDir, "sales_count, daily_amount, amount_by_region")), null).run();
        assertEquals("SUCCESS", r.status(), r.message());

        byte[] pdf = delivered(outDir);
        assertEquals(4, pages(pdf), "three chart pages + the table page");
        List<Img> images = images(pdf);
        assertEquals(WidgetChartRenderer.KPI_W, images.get(0).w, "the KPI tile comes first, as listed");
        assertEquals(WidgetChartRenderer.CHART_W, images.get(1).w);
        int second = WidgetChartRenderer.PALETTE[1].getRGB() & 0xFFFFFF;
        assertTrue(images.get(1).count(second) > 0, "the line chart draws its second series (one per region)");
    }

    @Test
    void theChartModelPivotsTheWidgetQuery(@TempDir Path writeRoot) throws Exception {
        seed(writeRoot);
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));

        ReportWidget line = ReportWidget.of("daily_amount", store.get("widget", "daily_amount").orElseThrow().content());
        WidgetChartRenderer.Model m = WidgetChartRenderer.model(line, List.of(
                row("day", "2026-11-01", "region", "EU", "sum_amount", 10.0),
                row("day", "2026-11-01", "region", "US", "sum_amount", 5.0),
                row("day", "2026-11-02", "region", "EU", "sum_amount", 30.0)));
        assertEquals(List.of("2026-11-01", "2026-11-02"), m.categories());
        assertEquals(List.of("EU", "US"), m.seriesNames());
        assertArrayEquals(new double[] {10.0, 30.0}, m.values().get(0));
        assertEquals(5.0, m.values().get(1)[0]);
        assertTrue(Double.isNaN(m.values().get(1)[1]), "US has no 2026-11-02 point: a gap, never a zero");

        ReportWidget kpi = ReportWidget.of("sales_count", store.get("widget", "sales_count").orElseThrow().content());
        assertEquals(List.of(), kpi.body().get("groupBy"), "a KPI is one aggregate row");
        assertEquals(3.0, WidgetChartRenderer.model(kpi, List.of(row("count", 3L))).values().get(0)[0]);
        assertEquals("Sales", kpi.title());
    }

    /** Review finding 1: a value near Double.MAX_VALUE made the axis top Infinity and the tick loop spin forever. */
    @Test
    void valuesAtTheEdgeOfADoubleStillDrawAndFinish() {
        ReportWidget bar = ReportWidget.of("edge", widget("bar", Map.of(
                "x", List.of(Map.of("field", "region")),
                "y", List.of(Map.of("field", "amount", "agg", "sum"))), "Edge"));
        // one huge positive value: the axis top rounded up past Double.MAX_VALUE, and t += step never got past Infinity
        java.awt.image.BufferedImage up = assertTimeoutPreemptively(java.time.Duration.ofSeconds(20), () ->
                WidgetChartRenderer.render(bar, List.of(row("region", "EU", "sum_amount", 1.7e308),
                        row("region", "US", "sum_amount", 5.0)), false));
        int[] upRgb = up.getRGB(0, 0, up.getWidth(), up.getHeight(), null, 0, up.getWidth());
        for (int i = 0; i < upRgb.length; i++) upRgb[i] &= 0xFFFFFF;
        assertFalse(new Img(up.getWidth(), up.getHeight(), upRgb).columnRuns(WidgetChartRenderer.PALETTE[0].getRGB() & 0xFFFFFF)
                .isEmpty(), "the huge bar is drawn");
        // both edges at once: the range itself overflowed to Infinity and nothing was drawn
        java.awt.image.BufferedImage img = assertTimeoutPreemptively(java.time.Duration.ofSeconds(20), () ->
                WidgetChartRenderer.render(bar, List.of(row("region", "EU", "sum_amount", 1.7e308),
                        row("region", "US", "sum_amount", -1.7e308)), false));
        int[] rgb = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
        for (int i = 0; i < rgb.length; i++) rgb[i] &= 0xFFFFFF;
        List<int[]> bars = new Img(img.getWidth(), img.getHeight(), rgb).columnRuns(WidgetChartRenderer.PALETTE[0].getRGB() & 0xFFFFFF);
        assertEquals(2, bars.size(), "both bars drawn, one up and one down");
        assertTrue(bars.get(0)[2] > 50 && bars.get(1)[2] > 50, "each a real bar: " + bars.get(0)[2] + " / " + bars.get(1)[2]);
    }

    /** Review finding 3: the dashboard draws a KPI tile naming a KPI definition from the definition, not its Dataset. */
    @Test
    void aKpiWidgetNamingAKpiDefinitionIsRefusedEvenWithADataset() {
        Map<String, Object> w = widget("kpi", Map.of("value", List.of(Map.of("field", "amount", "agg", "sum"))), "Revenue");
        w.put("options", Map.of("title", "Revenue", "kpi", Map.of("kpiId", "monthly_revenue")));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ReportWidget.of("rev_tile", w));
        assertTrue(e.getMessage().contains("rev_tile") && e.getMessage().contains("KPI definition 'monthly_revenue'"),
                e.getMessage());
        w.put("options", Map.of("title", "Revenue", "kpi", Map.of("target", 10)));
        assertEquals("Revenue", ReportWidget.of("rev_tile", w).title(), "KPI options without a definition still draw");
    }

    /** BI-INSIGHTS-1: an Insight Feed tile (view-bound, no Dataset) is refused in words, never drawn as an empty page. */
    @Test
    void anInsightFeedWidgetIsRefusedInWords() {
        Map<String, Object> w = new java.util.HashMap<>(Map.of("vizType", "insight-feed", "viewId", "sales_watch"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ReportWidget.of("feed_tile", w));
        assertTrue(e.getMessage().contains("feed_tile") && e.getMessage().contains("insight-feed")
                && e.getMessage().contains("a report PDF draws bar, line and kpi Widgets"), e.getMessage());
    }

    /** Review finding 4: a time grain on the series channel is kept, as the SPA's channelGrains keeps it. */
    @Test
    void aGrainOnEitherDimensionChannelIsKept() {
        ReportWidget w = ReportWidget.of("by_month", widget("line", Map.of(
                "x", List.of(Map.of("field", "region")),
                "y", List.of(Map.of("field", "amount", "agg", "sum")),
                "series", List.of(Map.of("field", "day", "grain", "month"))), "By month"));
        assertEquals(Map.of("day", "month"), w.body().get("grains"));
        ReportWidget auto = ReportWidget.of("by_day", widget("line", Map.of(
                "x", List.of(Map.of("field", "day", "grain", "week")),
                "y", List.of(Map.of("field", "amount", "agg", "sum")),
                "series", List.of(Map.of("field", "region", "grain", "auto"))), "By day"));
        assertEquals(Map.of("day", "week"), auto.body().get("grains"), "auto means no grain");
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void aPdfWithoutWidgetsIsStillTheOneTablePage(@TempDir Path writeRoot, @TempDir Path outDir) throws Exception {
        seed(writeRoot);
        JobResult r = new ReportJob(job(pdfParams(outDir, null)), null).run();
        assertEquals("SUCCESS", r.status(), r.message());
        assertEquals(1, pages(delivered(outDir)));
    }

    // ── refusals: the Run fails loudly, nothing is delivered ─────────────────────────────────────

    @Test
    void anUnsupportedVizTypeFailsTheRunNamingTheWidget(@TempDir Path writeRoot, @TempDir Path outDir) throws Exception {
        seed(writeRoot);
        new ComponentStore(writeRoot.resolve("registry")).write("widget", "share_pie", widget("pie", Map.of(
                "x", List.of(Map.of("field", "region")), "y", List.of(Map.of("field", "amount"))), "Share"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new ReportJob(job(pdfParams(outDir, "amount_by_region,share_pie")), null).run());
        assertTrue(e.getMessage().contains("share_pie") && e.getMessage().contains("bar, line and kpi"), e.getMessage());
        assertEmpty(outDir);
    }

    @Test
    void anUnknownOrUnboundWidgetFailsTheRun(@TempDir Path writeRoot, @TempDir Path outDir) throws Exception {
        seed(writeRoot);
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> new ReportJob(job(pdfParams(outDir, "no_such_widget")), null).run());
        assertTrue(unknown.getMessage().contains("unknown widget 'no_such_widget'"), unknown.getMessage());

        Map<String, Object> viewBound = new LinkedHashMap<>(Map.of("vizType", "bar", "viewId", "some_view",
                "controls", Map.of("x", List.of(Map.of("field", "region")), "y", List.of(Map.of("field", "amount")))));
        new ComponentStore(writeRoot.resolve("registry")).write("widget", "view_bar", viewBound);
        IllegalArgumentException unbound = assertThrows(IllegalArgumentException.class,
                () -> new ReportJob(job(pdfParams(outDir, "view_bar")), null).run());
        assertTrue(unbound.getMessage().contains("view_bar") && unbound.getMessage().contains("Dataset-bound"),
                unbound.getMessage());
        assertEmpty(outDir);
    }

    @Test
    void widgetsOnANonPdfFormatAreRefusedNotIgnored(@TempDir Path writeRoot, @TempDir Path outDir) throws Exception {
        seed(writeRoot);
        Map<String, String> p = pdfParams(outDir, "amount_by_region");
        p.put("format", "csv");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new ReportJob(job(p), null).run());
        assertTrue(e.getMessage().contains("widgets") && e.getMessage().contains("pdf"), e.getMessage());
        assertEmpty(outDir);
    }

    private static void assertEmpty(Path dir) throws Exception {
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(0, files.count(), "nothing delivered");
        }
    }

    // ── the attachment approval covers what the charts draw ──────────────────────────────────────

    @Test
    void anApprovedAttachJobRefusesOnceAWidgetItDrawsIsEdited(@TempDir Path writeRoot, @TempDir Path outDir)
            throws Exception {
        seed(writeRoot);
        Map<String, String> p = pdfParams(outDir, "amount_by_region");
        p.put("recipients", "ops@example.com");
        p.put("attach", "true");
        JobConfig cfg = job(p);
        String before = AttachApprovals.fingerprint(cfg, writeRoot, null);

        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        Map<String, Object> edited = widget("bar", Map.of("x", List.of(Map.of("field", "day")),
                "y", List.of(Map.of("field", "amount", "agg", "sum"))), "Amount by region");
        store.write("widget", "amount_by_region", edited);
        assertNotEquals(before, AttachApprovals.fingerprint(cfg, writeRoot, null),
                "a Widget the attachment draws is part of the approved version");

        store.delete("widget", "amount_by_region");
        assertThrows(IllegalArgumentException.class, () -> AttachApprovals.fingerprint(cfg, writeRoot, null),
                "a missing Widget is not approvable");
    }

    // ── a minimal reader for the PDFs PdfRenderer writes ─────────────────────────────────────────

    private static int pages(byte[] pdf) {
        Matcher m = Pattern.compile("/Type /Page ").matcher(new String(pdf, StandardCharsets.ISO_8859_1));
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    private record Img(int w, int h, int[] rgb) {
        int count(int color) {
            int n = 0;
            for (int px : rgb) if (px == color) n++;
            return n;
        }

        /** Contiguous x-runs of columns holding {@code color}: {startX, endX, tallest column height}. */
        List<int[]> columnRuns(int color) {
            List<int[]> out = new ArrayList<>();
            int start = -1, tallest = 0;
            for (int x = 0; x <= w; x++) {
                int height = 0;
                if (x < w) for (int y = 0; y < h; y++) if (rgb[y * w + x] == color) height++;
                if (height > 0) {
                    if (start < 0) { start = x; tallest = 0; }
                    tallest = Math.max(tallest, height);
                } else if (start >= 0) {
                    out.add(new int[] {start, x - 1, tallest});
                    start = -1;
                }
            }
            return out;
        }
    }

    private static List<Img> images(byte[] pdf) throws Exception {
        String text = new String(pdf, StandardCharsets.ISO_8859_1);
        Matcher m = Pattern.compile("/Subtype /Image /Width (\\d+) /Height (\\d+) .*?/Length (\\d+) >>\nstream\n")
                .matcher(text);
        List<Img> out = new ArrayList<>();
        while (m.find()) {
            int w = Integer.parseInt(m.group(1)), h = Integer.parseInt(m.group(2)), len = Integer.parseInt(m.group(3));
            Inflater inf = new Inflater();
            inf.setInput(pdf, m.end(), len);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                bos.write(buf, 0, n);
            }
            inf.end();
            byte[] raw = bos.toByteArray();
            assertEquals(w * h * 3, raw.length, "a whole DeviceRGB image");
            int[] rgb = new int[w * h];
            for (int i = 0; i < rgb.length; i++)
                rgb[i] = ((raw[3 * i] & 0xFF) << 16) | ((raw[3 * i + 1] & 0xFF) << 8) | (raw[3 * i + 2] & 0xFF);
            out.add(new Img(w, h, rgb));
        }
        return out;
    }
}
