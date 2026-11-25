package com.gamma.job;

import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REPORT-CHART-MARKERS-1: a report PDF's chart page draws the Widget's Chart Markers (BI-INSIGHTS-1 I-2) and its
 * forecast band and anomaly markers (I-11 / I-7) with the screen's meaning — from the Widget's own query, the overlay
 * computed by the same {@code ChartOverlays} over the same rows, the bound KPI read from the registry.
 */
class ReportChartMarkersTest {

    @BeforeEach
    void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @AfterEach
    void clearRoot() {
        System.clearProperty("assist.write.root");
    }

    /** {@code days} daily values with a weekly shape, one spike on day 20 (when there are that many). */
    private static void seed(Path writeRoot, int days, Map<String, Object> options) throws Exception {
        new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("daily_view", "flow-x", List.of(),
                "SELECT CAST(DATE '2026-10-01' + i * INTERVAL 1 DAY AS DATE) AS day, "
                        + "CAST(CASE WHEN i = 20 THEN 400 ELSE 100 + 10 * (i % 7) + 3 * ((i * 37) % 11 - 5) END AS DOUBLE) AS amount "
                        + "FROM range(" + days + ") t(i)", "2026-11-25T00:00:00Z"));
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        store.write("dataset", "daily_ds", Map.of("view", "daily_view"));
        store.write("kpi", "daily_kpi", new LinkedHashMap<>(Map.of("dataset", "daily_ds", "measure", "sum(amount)",
                "timeField", "day", "grain", "month", "direction", "up", "target", 150,
                "bands", Map.of("green", 140, "amber", 110))));
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("vizType", "line");
        w.put("datasetId", "daily_ds");
        w.put("controls", Map.of("x", List.of(Map.of("field", "day", "grain", "day")),
                "y", List.of(Map.of("field", "amount", "agg", "sum"))));
        Map<String, Object> o = new LinkedHashMap<>(Map.of("title", "Daily amount"));
        o.putAll(options);
        w.put("options", o);
        store.write("widget", "daily", w);
        System.setProperty("assist.write.root", writeRoot.toString());
    }

    private static final Map<String, Object> ALL = Map.of(
            "markers", List.of(Map.of("kind", "target"), Map.of("kind", "rag"), Map.of("kind", "average")),
            "kpi", Map.of("kpiId", "daily_kpi", "target", 1),
            "overlay", Map.of("forecast", Map.of("horizon", 7), "anomaly", Map.of()));

    private static JobConfig job(Path outDir) {
        return new JobConfig("daily_report", JobType.REPORT, null, null, true, false, Map.of("scope", "dataset",
                "dataset", "daily_ds", "format", "pdf", "measures", "count", "out_dir", outDir.toString(), "widgets", "daily"));
    }

    private record Drawn(ReportWidget widget, WidgetChartRenderer.Marks marks, BufferedImage image, ReportJob.ChartRows rows) {}

    private static Drawn draw(Path writeRoot, Path outDir) throws Exception {
        ReportWidget w = ReportWidget.of("daily", new ComponentStore(writeRoot.resolve("registry")).get("widget", "daily")
                .orElseThrow().content());
        ReportJob job = new ReportJob(job(outDir), null);
        ReportJob.ChartRows rows = job.chart(w);
        Map<?, ?> kpi = ReportJob.boundKpi(writeRoot, w);
        WidgetChartRenderer.Model m = WidgetChartRenderer.model(w, rows.result().rows());
        return new Drawn(w, WidgetChartRenderer.marks(w, m, rows.overlays(), kpi),
                WidgetChartRenderer.render(w, rows.result().rows(), rows.result().truncated(), rows.overlays(), kpi), rows);
    }

    private static int count(BufferedImage img, Color c) {
        int want = c.getRGB() & 0xFFFFFF, n = 0;
        for (int y = 0; y < img.getHeight(); y++)
            for (int x = 0; x < img.getWidth(); x++) if ((img.getRGB(x, y) & 0xFFFFFF) == want) n++;
        return n;
    }

    private static List<String> texts(WidgetChartRenderer.Marks marks) {
        return marks.markers().stream().map(ChartMarkers.Resolved::text).toList();
    }

    @Test
    void theChartPageDrawsTheMarkersAndOverlaysTheScreenDraws(@TempDir Path writeRoot, @TempDir Path outDir) throws Exception {
        seed(writeRoot, 28, ALL);
        Drawn d = draw(writeRoot, outDir);

        assertNotNull(d.widget().body().get("overlay"), "the overlay is asked for, as buildXyQuery asks for it");
        List<String> words = texts(d.marks());
        // the bound KPI's target (150) wins over the Widget's own (1), as on screen
        assertTrue(words.contains("Target 150"), words.toString());
        assertTrue(words.containsAll(List.of("Red below 110", "Amber 110–140", "Green at or above 140")), words.toString());
        assertTrue(words.stream().anyMatch(t -> t.startsWith("Average ")), words.toString());
        assertTrue(words.stream().anyMatch(t -> t.startsWith("Forecast 2026-10-29 to 2026-11-04, 80 % band")), words.toString());
        assertTrue(words.stream().anyMatch(t -> t.startsWith("Unusual value") && t.contains("2026-10-21 (400, expected")), words.toString());
        assertTrue(d.marks().notes().stream().anyMatch(n -> n.startsWith("Forecast of the next 7 days, 80 % band (Holt-Winters")),
                d.marks().notes().toString());

        BufferedImage img = d.image();
        assertTrue(img.getHeight() > WidgetChartRenderer.CHART_H, "the overlays' words are listed under the chart");
        assertTrue(count(img, WidgetChartRenderer.TONE.get("info")) > 200, "the target line is drawn in the info tone");
        assertTrue(count(img, WidgetChartRenderer.FORECAST) > 20, "the dashed point forecast is drawn");
        assertTrue(count(img, WidgetChartRenderer.TONE.get("error")) > 20, "the anomaly ring is drawn");
    }

    /** Falsifier: the same Widget with no markers and no overlay draws none of their ink, and asks for no overlay. */
    @Test
    void aWidgetWithoutMarkersDrawsNone(@TempDir Path writeRoot, @TempDir Path outDir) throws Exception {
        seed(writeRoot, 28, Map.of());
        Drawn d = draw(writeRoot, outDir);
        assertNull(d.widget().body().get("overlay"));
        assertTrue(d.marks().markers().isEmpty());
        assertEquals(WidgetChartRenderer.CHART_H, d.image().getHeight());
        assertEquals(0, count(d.image(), WidgetChartRenderer.TONE.get("info")));
        assertEquals(0, count(d.image(), WidgetChartRenderer.FORECAST));
        assertEquals(0, count(d.image(), WidgetChartRenderer.TONE.get("error")));
    }

    /** Fewer than two seasons: no band, and the page says why — as the screen's caption does. */
    @Test
    void fewerThanTwoSeasonsDrawsNoBandAndSaysWhy(@TempDir Path writeRoot, @TempDir Path outDir) throws Exception {
        seed(writeRoot, 10, Map.of("overlay", Map.of("forecast", Map.of())));
        Drawn d = draw(writeRoot, outDir);
        assertTrue(d.marks().markers().stream().noneMatch(m -> "interval".equals(m.type())), texts(d.marks()).toString());
        assertTrue(d.marks().notes().stream().anyMatch(n -> n.startsWith("No forecast: ")), d.marks().notes().toString());
        assertEquals(0, count(d.image(), WidgetChartRenderer.FORECAST));
    }

    /** A stacked or sorted chart draws neither overlay and says so; its markers still draw. */
    @Test
    void aSortedChartDrawsNoOverlayButKeepsItsMarkers(@TempDir Path writeRoot, @TempDir Path outDir) throws Exception {
        Map<String, Object> o = new LinkedHashMap<>(ALL);
        o.put("sort", "desc");
        seed(writeRoot, 28, o);
        Drawn d = draw(writeRoot, outDir);
        assertTrue(d.marks().notes().contains(ChartMarkers.HIDDEN_NOTE), d.marks().notes().toString());
        assertTrue(d.marks().markers().stream().noneMatch(m -> "interval".equals(m.type()) || "points".equals(m.type())));
        assertTrue(texts(d.marks()).contains("Target 150"));
    }

    /** No KPI in the registry: the RAG bands are dropped and the target line falls back to the Widget's own target. */
    @Test
    void anUnreadableKpiDropsTheRagAndFallsBack(@TempDir Path writeRoot, @TempDir Path outDir) throws Exception {
        Map<String, Object> o = new LinkedHashMap<>(ALL);
        o.put("kpi", Map.of("kpiId", "no_such_kpi", "target", 120));
        seed(writeRoot, 28, o);
        Drawn d = draw(writeRoot, outDir);
        List<String> words = texts(d.marks());
        assertTrue(words.contains("Target 120"), words.toString());
        assertTrue(words.stream().noneMatch(t -> t.startsWith("Red ") || t.startsWith("Green ") || t.startsWith("Amber ")), words.toString());
    }

    /** The whole Job: the PDF is written and its chart page carries the marker ink. */
    @Test
    void theReportJobWritesThePdfWithTheMarkers(@TempDir Path writeRoot, @TempDir Path outDir) throws Exception {
        seed(writeRoot, 28, ALL);
        JobResult r = new ReportJob(job(outDir), null).run();
        assertEquals("SUCCESS", r.status(), r.message());
        // the page image, kept under target/ for a reviewer to look at
        java.nio.file.Files.createDirectories(Path.of("target"));
        javax.imageio.ImageIO.write(draw(writeRoot, outDir).image(), "png", Path.of("target", "report-markers-sample.png").toFile());
    }
}
