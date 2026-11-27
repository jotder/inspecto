package com.gamma.job;

import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BI-INSIGHTS-1 P4 BR-4: an {@code insight-brief} Widget in a report Job's {@code widgets:} draws the Dashboard Brief as a
 * page, in the position the list gives it (D-BI24). The Insights module is stood in for by {@link FakeBriefPages}; the real one
 * is pinned in {@code inspecto-insights}.
 */
class ReportBriefPageTest {

    @BeforeEach
    void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @AfterEach
    void clearRoot() {
        System.clearProperty("assist.write.root");
    }

    private static void seed(Path writeRoot) throws Exception {
        new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("sales_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES ('EU','2026-11-01',10.0),('US','2026-11-01',5.0)) AS t(region,day,amount)", "2026-07-08T00:00:00Z"));
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        store.write("dataset", "sales_ds", Map.of("view", "sales_view"));
        Map<String, Object> bar = new LinkedHashMap<>();
        bar.put("vizType", "bar");
        bar.put("datasetId", "sales_ds");
        bar.put("controls", Map.of("x", List.of(Map.of("field", "region")), "y", List.of(Map.of("field", "amount", "agg", "sum"))));
        bar.put("options", Map.of("title", "Amount by region"));
        store.write("widget", "amount_by_region", bar);
        store.write("widget", "brief_sales", brief("sales", "Sales Brief"));
        store.write("widget", "brief_denied", brief("denied", "Denied"));
        System.setProperty("assist.write.root", writeRoot.toString());
    }

    private static Map<String, Object> brief(String profile, String title) {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("vizType", "insight-brief");
        w.put("viewId", profile);
        w.put("options", Map.of("title", title));
        return w;
    }

    private static Map<String, String> pdfParams(Path outDir, String widgets) {
        return new LinkedHashMap<>(Map.of("scope", "dataset", "dataset", "sales_ds", "format", "pdf",
                "measures", "sum(amount),count", "group_by", "region", "out_dir", outDir.toString(), "widgets", widgets));
    }

    private static ReportJob job(Map<String, String> params) {
        return new ReportJob(new JobConfig("weekly", JobType.REPORT, null, null, true, false, params), null);
    }

    private static int pages(Path outDir) throws Exception {
        try (Stream<Path> files = Files.list(outDir)) {
            String pdf = new String(Files.readAllBytes(files.findFirst().orElseThrow()), StandardCharsets.ISO_8859_1);
            Matcher m = Pattern.compile("/Type /Page ").matcher(pdf);
            int n = 0;
            while (m.find()) n++;
            return n;
        }
    }

    @Test
    void aBriefWidgetIsAcceptedWhenBoundToAProfileAndRefusedInWordsWhenNot() {
        ReportWidget w = ReportWidget.of("brief_sales", brief("sales", "Sales Brief"));
        assertEquals("sales", w.viewId());
        assertEquals("Sales Brief", w.title());
        Map<String, Object> unbound = new LinkedHashMap<>(Map.of("vizType", "insight-brief"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ReportWidget.of("brief_x", unbound));
        assertTrue(e.getMessage().contains("brief_x") && e.getMessage().contains("no Insight Profile"), e.getMessage());
    }

    @Test
    void theBriefPageSitsWhereTheWidgetsListPutsIt(@TempDir Path writeRoot, @TempDir Path outDir) throws Exception {
        seed(writeRoot);
        assertEquals("SUCCESS", job(pdfParams(outDir, "brief_sales,amount_by_region")).run().status());
        assertEquals(3, pages(outDir), "Brief page, chart page, table page");
    }

    @Test
    void aProfileTheAuthorMayNotViewFailsTheRunInWordsWithNothingDelivered(@TempDir Path writeRoot, @TempDir Path outDir) throws Exception {
        seed(writeRoot);
        SecurityException e = assertThrows(SecurityException.class, () -> job(pdfParams(outDir, "amount_by_region,brief_denied")).run());
        assertTrue(e.getMessage().contains("denied") && e.getMessage().contains("refused"), e.getMessage());
        try (Stream<Path> files = Files.list(outDir)) {
            assertEquals(0, files.count(), "nothing delivered");
        }
    }

    @Test
    void thePageIsDrawnAndItsFooterSaysWhoWroteIt() {
        BriefPageProvider.BriefPage templated = new FakeBriefPages().page("sales", null);
        BriefPageProvider.BriefPage model = new FakeBriefPages().page("model_sales", null);
        BufferedImage a = BriefPageRenderer.render(templated), b = BriefPageRenderer.render(model);
        assertTrue(ink(a, 0, a.getHeight()) > 500, "text was drawn");
        assertTrue(ink(b, 0, b.getHeight() - 30) > ink(a, 0, a.getHeight() - 30), "the narrated paragraph adds text above the lines");
        int stripA = ink(a, a.getHeight() - 30, a.getHeight()), stripB = ink(b, b.getHeight() - 30, b.getHeight());
        assertNotEquals(stripA, stripB, "the footer words differ");
        assertTrue(stripA > 0 && stripB > 0);
    }

    @Test
    void aVeryLongSliceValueIsWrappedNotCutOffThePage() {
        String longWord = "x".repeat(400);
        BriefPageProvider.BriefPage p = new BriefPageProvider.BriefPage("t", "h", null, null, List.of(),
                List.of(new BriefPageProvider.Line("high", "amount for region = " + longWord + " was 400")), false);
        BufferedImage img = BriefPageRenderer.render(p);
        assertEquals(0, ink(img, 0, img.getHeight(), BriefPageRenderer.W - 6, BriefPageRenderer.W), "no ink in the right margin");
    }

    private static int ink(BufferedImage img, int y0, int y1) {
        return ink(img, y0, y1, 0, img.getWidth());
    }

    private static int ink(BufferedImage img, int y0, int y1, int x0, int x1) {
        int n = 0;
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) if ((img.getRGB(x, y) & 0xFFFFFF) != 0xFFFFFF) n++;
        return n;
    }
}
