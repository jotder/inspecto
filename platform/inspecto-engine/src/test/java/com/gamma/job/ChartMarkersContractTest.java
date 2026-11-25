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
        assertEquals("0", ChartMarkers.plain(-0.001));
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
                "an absent value is left out of the average, never read as 0");
    }
}
