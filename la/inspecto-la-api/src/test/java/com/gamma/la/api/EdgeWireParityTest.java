package com.gamma.la.api;

import com.gamma.spi.http.ApiContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PERF-P10-1 parity: the compact edge rows ({@link InvRoutes#edgeArrays}), decoded the way the SPA decodes them
 * ({@code edge-wire.ts}), equal the pre-P10-1 object rows ({@link InvRoutes#edgeRow} + {@link InvRoutes#maskEnds}) for the
 * same statement rows - with masking off and on, with and without a Measure and attribute columns.
 */
class EdgeWireParityTest {

    /** Statement rows as {@code edgeSql} / the index return them: source, target, [kind], [attr_i], [total], cnt. */
    private static List<Map<String, Object>> fixture(int n, boolean kind, boolean total, int attrs) {
        List<Map<String, Object>> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Map<String, Object> r = new HashMap<>();
            r.put("source", "9997" + (i % 97));
            r.put("target", "9998" + (i * 7 % 389));
            if (kind) r.put("kind", i % 5 == 0 ? null : "voice" + (i % 3));
            for (int a = 0; a < attrs; a++) r.put("attr_" + a, i % 4 == 0 ? null : "v" + a + "_" + (i % 11));
            if (total) r.put("total", i % 6 == 0 ? null : i * 1.5);
            r.put("cnt", (long) (n - i));
            out.add(r);
        }
        return out;
    }

    /** The SPA's decodeEdges, in Java. */
    private static List<Map<String, Object>> decode(List<String> columns, List<String> attrCols, List<Object[]> rows) {
        boolean total = columns.size() == 5 + attrCols.size();
        int first = total ? 5 : 4;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] a : rows) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("source", a[0]);
            t.put("target", a[1]);
            t.put("kind", a[2]);
            t.put("count", a[3]);
            if (total) t.put("total", a[4]);
            if (!attrCols.isEmpty()) {
                Map<String, Object> at = new LinkedHashMap<>();
                for (int i = 0; i < attrCols.size(); i++) at.put(attrCols.get(i), a[first + i]);
                t.put("attrs", at);
            }
            out.add(t);
        }
        return out;
    }

    private static void assertParity(Path root, boolean kind, boolean total, List<String> attrCols) {
        List<Map<String, Object>> rows = fixture(400, kind, total, attrCols.size());
        ExplorationMasking mask = ExplorationMasking.of(root, "d", Map.of("sourceCol", "s", "targetCol", "t"));
        List<Map<String, Object>> old = new ArrayList<>();
        for (Map<String, Object> r : rows) old.add(InvRoutes.maskEnds(mask, InvRoutes.edgeRow(r, kind ? r.get("kind") : null, attrCols)));
        List<String> columns = new ArrayList<>(InvRoutes.EDGE_COLUMNS);
        if (total) columns.add("total");
        columns.addAll(attrCols);
        assertEquals(old, decode(columns, attrCols, InvRoutes.edgeArrays(rows, kind, total, attrCols, mask)),
                "kind=" + kind + " total=" + total + " attrs=" + attrCols + " masked=" + mask.active());
    }

    @Test
    void decodedCompactRowsEqualTheOldRowsWithMaskingOff(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("link-analysis.toon"), "masking_mode: none\n");
        for (boolean kind : new boolean[] {true, false})
            for (boolean total : new boolean[] {true, false})
                for (List<String> attrs : List.of(List.<String>of(), List.of("via"), List.of("via", "cell"), List.of("total")))
                    assertParity(root, kind, total, attrs);
    }

    @Test
    void decodedCompactRowsEqualTheOldRowsWithMaskingOn(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("link-analysis.toon"), "masking_mode: all\n");
        assertTrue(ExplorationMasking.of(root, "d", Map.of("sourceCol", "s", "targetCol", "t")).active());
        assertParity(root, true, true, List.of("via"));
        assertParity(root, false, false, List.of());
        ExplorationMasking mask = ExplorationMasking.of(root, "d", Map.of("sourceCol", "s", "targetCol", "t"));
        Object[] a = InvRoutes.edgeArrays(fixture(1, true, false, 0), true, false, List.of(), mask).get(0);
        assertNotEquals("99970", a[0], "a masked read must not carry the raw id");
    }

    /**
     * PERF-P10-1 evidence (printed, not asserted): JSON bytes and Jackson serialisation time of a 4,000-row body in both
     * shapes, one attribute column (the showcase's {@code via}).
     */
    @Test
    void serialisationCostOfBothShapes(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("link-analysis.toon"), "masking_mode: none\n");
        ExplorationMasking mask = ExplorationMasking.of(root, "d", Map.of("sourceCol", "s", "targetCol", "t"));
        List<Map<String, Object>> rows = fixture(4_000, true, false, 1);
        List<String> attrs = List.of("via");
        long[] oldNs = new long[25], newNs = new long[25];
        int oldBytes = 0, newBytes = 0;
        for (int run = 0; run < 25; run++) {
            long t0 = System.nanoTime();
            List<Map<String, Object>> old = new ArrayList<>(rows.size());
            for (Map<String, Object> r : rows) old.add(InvRoutes.maskEnds(mask, InvRoutes.edgeRow(r, r.get("kind"), attrs)));
            oldBytes = ApiContext.JSON.writeValueAsBytes(Map.of("rows", old)).length;
            long t1 = System.nanoTime();
            List<String> columns = new ArrayList<>(InvRoutes.EDGE_COLUMNS);
            columns.addAll(attrs);
            newBytes = ApiContext.JSON.writeValueAsBytes(Map.of("columns", columns, "attrCols", attrs,
                    "rows", InvRoutes.edgeArrays(rows, true, false, attrs, mask))).length;
            long t2 = System.nanoTime();
            oldNs[run] = t1 - t0;
            newNs[run] = t2 - t1;
        }
        java.util.Arrays.sort(oldNs);
        java.util.Arrays.sort(newNs);
        System.out.printf("PERF-P10-1 serialise 4000 rows: old %d B %.2f ms, compact %d B %.2f ms (median of 25, warm)%n",
                oldBytes, oldNs[12] / 1e6, newBytes, newNs[12] / 1e6);
        assertTrue(newBytes < oldBytes);
    }
}
