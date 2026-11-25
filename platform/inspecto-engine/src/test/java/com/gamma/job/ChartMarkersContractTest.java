package com.gamma.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REPORT-CHART-MARKERS-1: the report PDF's {@link ChartMarkers} against the committed contract the SPA's resolvers are
 * held to by {@code chart-markers.contract.spec.ts} — same stored Chart Markers and overlays, same lines, bands and words.
 * Both sides compare to ONE checked-in file, so a divergence lands as a reviewable diff on it.
 */
class ChartMarkersContractTest {

    private static final String CONTRACT = "inspecto-ui/src/app/inspecto/contracts/chart-markers.contract.json";

    @SuppressWarnings("unchecked")
    private static Map<String, Object> contract() throws Exception {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 4 && dir != null; up++, dir = dir.getParent()) {
            Path candidate = dir.resolve(CONTRACT);
            if (Files.exists(candidate)) return new ObjectMapper().readValue(candidate.toFile(), Map.class);
        }
        throw new AssertionError("cannot locate " + CONTRACT + " from " + Path.of("").toAbsolutePath());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cases(String key) throws Exception {
        List<Map<String, Object>> list = (List<Map<String, Object>>) contract().get(key);
        assertNotNull(list, "contract has no `" + key + "`");
        assertFalse(list.isEmpty());
        return list;
    }

    private static double[] doubles(Object list) {
        return ((List<?>) list).stream().mapToDouble(v -> v == null ? Double.NaN : ((Number) v).doubleValue()).toArray();
    }

    /** Each expected key, as the TS spec compares it: numbers within 1e-9, everything else equal, absent = null. */
    @SuppressWarnings("unchecked")
    private static void assertMatches(String name, List<ChartMarkers.Resolved> actual, List<Map<String, Object>> expected) {
        assertEquals(expected.size(), actual.size(), name + ": " + actual);
        for (int i = 0; i < expected.size(); i++) {
            ChartMarkers.Resolved a = actual.get(i);
            if (!expected.get(i).containsKey("tone")) assertNull(a.tone(), name + " [" + i + "]: a tone the contract does not state");
            for (Map.Entry<String, Object> e : expected.get(i).entrySet()) {
                String where = name + " [" + i + "]." + e.getKey();
                Object v = e.getValue();
                switch (e.getKey()) {
                    case "type" -> assertEquals(v, a.type(), where);
                    case "text" -> assertEquals(v, a.text(), where);
                    case "tone" -> assertEquals(v, a.tone(), where);
                    case "dashed" -> assertEquals(v, a.dashed(), where);
                    case "value" -> assertEquals(((Number) v).doubleValue(), a.value(), 1e-9, where);
                    case "from", "to" -> {
                        Double got = "from".equals(e.getKey()) ? a.from() : a.to();
                        if (v == null) assertNull(got, where);
                        else assertEquals(((Number) v).doubleValue(), got, 1e-9, where);
                    }
                    case "points" -> {
                        List<Map<String, Object>> pts = (List<Map<String, Object>>) v;
                        assertEquals(pts.size(), a.points().size(), where);
                        for (int p = 0; p < pts.size(); p++) {
                            ChartMarkers.Point got = a.points().get(p);
                            Map<String, Object> want = pts.get(p);
                            assertEquals(((Number) want.get("index")).intValue(), got.index(), where);
                            assertEquals(((Number) want.get("value")).doubleValue(), got.value(), 1e-9, where);
                            if (want.containsKey("lower")) assertEquals(((Number) want.get("lower")).doubleValue(), got.lower(), 1e-9, where);
                            if (want.containsKey("upper")) assertEquals(((Number) want.get("upper")).doubleValue(), got.upper(), 1e-9, where);
                        }
                    }
                    default -> fail(where + ": the contract names a key this test does not compare");
                }
            }
        }
    }

    @TestFactory
    @SuppressWarnings("unchecked")
    List<DynamicTest> markersResolveAsOnScreen() throws Exception {
        List<DynamicTest> out = new ArrayList<>();
        for (Map<String, Object> c : cases("markers"))
            out.add(DynamicTest.dynamicTest(String.valueOf(c.get("name")), () -> assertMatches(String.valueOf(c.get("name")),
                    ChartMarkers.resolve((List<?>) c.get("markers"), doubles(c.get("values")), c.get("target"),
                            (Map<?, ?>) c.get("kpi"), ChartMarkers::plain),
                    (List<Map<String, Object>>) c.get("expected"))));
        return out;
    }

    @TestFactory
    @SuppressWarnings("unchecked")
    List<DynamicTest> overlaysResolveAsOnScreen() throws Exception {
        List<DynamicTest> out = new ArrayList<>();
        for (Map<String, Object> c : cases("overlays"))
            out.add(DynamicTest.dynamicTest(String.valueOf(c.get("name")), () -> {
                List<ChartMarkers.Series> series = new ArrayList<>();
                for (Map<String, Object> s : (List<Map<String, Object>>) c.get("series")) {
                    ChartMarkers.Cells cells = null;
                    if (s.get("overlay") instanceof List<?> rows) {
                        double[][] x = new double[5][rows.size()];
                        for (double[] a : x) Arrays.fill(a, Double.NaN);
                        String[] keys = {"fc", "lo", "hi", "expected", "z"};
                        for (int i = 0; i < rows.size(); i++)
                            for (int k = 0; k < keys.length; k++)
                                if (((Map<?, ?>) rows.get(i)).get(keys[k]) instanceof Number n) x[k][i] = n.doubleValue();
                        cells = new ChartMarkers.Cells(x[0], x[1], x[2], x[3], x[4]);
                    }
                    series.add(new ChartMarkers.Series((String) s.get("label"), doubles(s.get("data")), cells));
                }
                assertMatches(String.valueOf(c.get("name")), ChartMarkers.resolveOverlays(series,
                                (List<String>) c.get("labels"), (Map<?, ?>) c.get("overlay"), ChartMarkers::plain),
                        (List<Map<String, Object>>) c.get("expected"));
            }));
        return out;
    }

    @TestFactory
    @SuppressWarnings("unchecked")
    List<DynamicTest> captionsReadAsOnScreen() throws Exception {
        List<DynamicTest> out = new ArrayList<>();
        for (Map<String, Object> c : cases("notes"))
            out.add(DynamicTest.dynamicTest(String.valueOf(c.get("name")), () -> assertEquals(c.get("expected"),
                    ChartMarkers.overlayNote((List<Map<String, Object>>) c.get("summary"),
                            ((Number) c.get("flagged")).intValue(), c.get("sensitivity"), (String) c.get("unit")))));
        return out;
    }

    /** The SPA's formatNumber with no format: grouped, at most two decimals, half away from zero, a gap an em dash. */
    @Test
    void numbersAreWordedAsTheScreenWordsThem() {
        assertEquals("1,234.57", ChartMarkers.plain(1234.567));
        assertEquals("-2.5", ChartMarkers.plain(-2.5));
        assertEquals("-0", ChartMarkers.plain(-0.001), "Intl keeps the sign of a negative that rounds to zero");
        assertEquals("2.68", ChartMarkers.plain(2.675), "the shortest decimal is rounded, not the binary value");
        assertEquals("1.01", ChartMarkers.plain(1.005));
        assertEquals("1,000,000", ChartMarkers.plain(1e6));
        assertEquals("—", ChartMarkers.plain(Double.NaN));
        assertEquals("3.0", ChartMarkers.oneDecimal(3));
    }

    /** The SPA's staticMarkers population: every drawn value, or per-category totals when stacked (a gap adds 0). */
    @Test
    void theStatisticsReadTheValuesAsDrawn() {
        List<double[]> series = List.of(new double[] {1, 2, Double.NaN}, new double[] {10, 20, 30});
        assertArrayEquals(new double[] {1, 2, Double.NaN, 10, 20, 30}, ChartMarkers.population(series, 3, false));
        assertArrayEquals(new double[] {11, 22, 30}, ChartMarkers.population(series, 3, true));
        assertEquals(16.5, ChartMarkers.average(new double[] {11, 22, Double.NaN}), 1e-9,
                "a non-finite value is left out of the population");
    }

    /**
     * Verification finding: the screen's transformXy draws a missing cell as 0 (a gap only on an overlaid chart) and its
     * sortedProps applies hideBlank, sort and limit before the statistics read the values.
     */
    @Test
    void theStatisticsReadTheScreensValueSet() {
        WidgetChartRenderer.Model m = new WidgetChartRenderer.Model(List.of("b", " ", "a", "c"), List.of("s"),
                List.of(new double[] {10, 99, Double.NaN, 40}));
        assertArrayEquals(new double[] {10, 99, 0, 40}, WidgetChartRenderer.screenValues(m, Map.of()).get(0),
                "a missing point counts as 0, as the screen draws it");
        assertArrayEquals(new double[] {40, 10}, WidgetChartRenderer.screenValues(m,
                Map.of("hideBlank", true, "sort", "desc", "limit", 2)).get(0), "blank dropped, sorted by value, cut to 2");
        WidgetChartRenderer.Model overlaid = new WidgetChartRenderer.Model(List.of("a", "b"), List.of("s"),
                List.of(new double[] {1, Double.NaN}), java.util.Collections.singletonList(new ChartMarkers.Cells(
                new double[2], new double[2], new double[2], new double[2], new double[2])));
        assertTrue(Double.isNaN(WidgetChartRenderer.screenValues(overlaid, Map.of()).get(0)[1]),
                "on an overlaid chart a forecast row carries no actual: a gap, not 0");
    }
}
