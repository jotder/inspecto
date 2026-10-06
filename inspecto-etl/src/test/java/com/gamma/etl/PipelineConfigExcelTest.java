package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The flat home of the {@code sink.excel} node — the top-level {@code excel:} block (operator 2026-10-06).
 * Every refusal is paired with the nearest VALID value, so a red here is the rule, not the fixture.
 */
class PipelineConfigExcelTest {

    @TempDir Path dir;

    private static Map<String, Object> base(Map<String, Object> extra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "EXCEL_ETL");
        m.put("dirs", Map.of("poll", "in", "database", "out"));
        m.put("processing", new LinkedHashMap<String, Object>(Map.of("threads", 1)));
        m.putAll(extra);
        return m;
    }

    private Map<String, Object> active(Map<String, Object> extra) throws Exception {
        Path schema = dir.resolve("mini_schema.toon");
        Files.writeString(schema, PipelineConfigBatchTest.miniSchema());
        Map<String, Object> m = base(extra);
        m.put("active", true);
        m.put("processing", new LinkedHashMap<String, Object>(Map.of("threads", 1,
                "schema_file", schema.toString().replace('\\', '/'))));
        return m;
    }

    private static Map<String, Object> block(String path, List<?> sheets) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("path", path);
        b.put("sheets", sheets);
        return b;
    }

    private static Map<String, Object> sheet(String name) { return Map.of("name", name); }

    private static String refused(Map<String, Object> block) {
        Exception e = assertThrows(IllegalArgumentException.class, () -> PipelineConfig.fromMap(base(Map.of("excel", block))));
        return e.getMessage();
    }

    private static void accepted(Map<String, Object> block) throws Exception {
        assertNotNull(PipelineConfig.fromMap(base(Map.of("excel", block))).excel());
    }

    @Test
    void parsesTheBlockAndKeepsItVerbatim() throws Exception {
        Map<String, Object> b = block("reports/orders.xlsx", List.of(sheet("Orders"),
                Map.of("name", "By region", "sql", "SELECT region FROM input")));
        b.put("max_rows", 500);
        PipelineConfig cfg = PipelineConfig.fromMap(base(Map.of("excel", b)));
        assertEquals("reports/orders.xlsx", cfg.excel().path());
        assertEquals(List.of("Orders", "By region"), cfg.excel().sheets().stream().map(s -> s.name()).toList());
        assertNull(cfg.excel().sheets().get(0).sql(), "no sql = every row");
        assertEquals("SELECT region FROM input", cfg.excel().sheets().get(1).sql());
        assertEquals(500, cfg.excel().maxRows());
        assertEquals(b, cfg.excelConfig(), "verbatim, so lift/lower round-trips it");
        assertEquals(PipelineConfig.Excel.DEFAULT_MAX_ROWS,
                PipelineConfig.fromMap(base(Map.of("excel", block("a.xlsx", List.of(sheet("A")))))).excel().maxRows());
    }

    @Test
    void sheetNamesFollowExcelsRule() throws Exception {
        String max = "x".repeat(31);
        accepted(block("a.xlsx", List.of(sheet(max))));
        assertTrue(refused(block("a.xlsx", List.of(sheet(max + "y")))).contains("at most 31"));
        for (String bad : List.of("a[b", "a]b", "a:b", "a*b", "a?b", "a/b", "a\\b")) {
            String msg = refused(block("a.xlsx", List.of(sheet(bad))));
            assertTrue(msg.contains("Excel forbids"), bad + " -> " + msg);
        }
        accepted(block("a.xlsx", List.of(sheet("it's fine"))));
        assertTrue(refused(block("a.xlsx", List.of(sheet("'quoted")))).contains("apostrophe"));
        assertTrue(refused(block("a.xlsx", List.of(sheet("history")))).contains("reserved"));
        assertTrue(refused(block("a.xlsx", List.of(sheet("  ")))).contains("blank"));
        assertTrue(refused(block("a.xlsx", List.of(sheet("Orders"), sheet("ORDERS")))).contains("used twice"));
        accepted(block("a.xlsx", List.of(sheet("Orders"), sheet("Orders 2"))));
        assertTrue(refused(block("a.xlsx", List.of())).contains("non-empty 'sheets'"));
        assertTrue(refused(block("a.xlsx", List.of("Orders"))).contains("must be a map"));
    }

    @Test
    void thePathStaysUnderTheDataRoot() throws Exception {
        accepted(block("reports/2026/a.xlsx", List.of(sheet("A"))));
        for (String bad : List.of("../a.xlsx", "reports/../../a.xlsx", "/abs/a.xlsx", "C:/a.xlsx", "\\\\host\\share\\a.xlsx")) {
            String msg = refused(block(bad, List.of(sheet("A"))));
            assertTrue(msg.contains("data root"), bad + " -> " + msg);
        }
        assertTrue(refused(block("reports/a.csv", List.of(sheet("A")))).contains(".xlsx"));
        assertTrue(refused(block(" ", List.of(sheet("A")))).contains("'path'"));
    }

    @Test
    void theRowCapIsBoundedAndUnknownKeysAreRefused() throws Exception {
        Map<String, Object> b = block("a.xlsx", List.of(sheet("A")));
        b.put("max_rows", PipelineConfig.Excel.MAX_ROWS);
        accepted(b);
        b.put("max_rows", PipelineConfig.Excel.MAX_ROWS + 1);
        assertTrue(refused(b).contains("max_rows"));
        b.put("max_rows", 0);
        assertTrue(refused(b).contains("max_rows"));

        Map<String, Object> typo = block("a.xlsx", List.of(sheet("A")));
        typo.put("maxrows", 10);
        assertTrue(refused(typo).contains("'maxrows'"));
        assertTrue(refused(block("a.xlsx", List.of(Map.of("name", "A", "query", "SELECT 1")))).contains("'query'"));
    }

    // ── arming: at-rest lane only, like webhook: ─────────────────────────────

    @Test
    void anActiveExcelWithoutOutputStoreRefusesToArm() throws Exception {
        PipelineConfig cfg = PipelineConfig.fromMap(active(Map.of("excel", block("a.xlsx", List.of(sheet("A"))))));
        IllegalStateException e = assertThrows(IllegalStateException.class, cfg::prepare);
        assertTrue(e.getMessage().contains("excel") && e.getMessage().contains("output_store"), e.getMessage());
        PipelineConfig.fromMap(active(Map.of("excel", block("a.xlsx", List.of(sheet("A"))), "output_store", "o"))).prepare();
        PipelineConfig.fromMap(base(Map.of("excel", block("a.xlsx", List.of(sheet("A")))))).prepare();   // a draft loads
    }

    @Test
    void anActiveExcelBesideARouteRefuses() throws Exception {
        Map<String, Object> m = active(Map.of("excel", block("a.xlsx", List.of(sheet("A"))), "output_store", "o",
                "route", Map.of("branches", List.of(Map.of("key", "a", "where", "true", "database", "out"))),
                "sinks", List.of(Map.of("database", "out"))));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> PipelineConfig.fromMap(m).prepare());
        assertTrue(e.getMessage().contains("excel"), e.getMessage());
    }
}
