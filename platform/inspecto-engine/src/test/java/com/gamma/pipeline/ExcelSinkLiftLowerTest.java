package com.gamma.pipeline;

import com.gamma.etl.PipelineConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code excel:} block's graph homes (operator 2026-10-06), mirroring {@code WebhookSinkLiftLowerTest}: the
 * at-rest lift runs it as a second commit branch beside the {@code output_store:} sink; the editor lift/lower
 * round-trips it verbatim; the recipe verb compiles to it and the converter projects it back.
 */
class ExcelSinkLiftLowerTest {

    private static final Map<String, Object> BOOK = Map.of("path", "reports/orders.xlsx", "max_rows", 1000,
            "sheets", List.of(Map.of("name", "Orders"),
                    Map.of("name", "By region", "sql", "SELECT region, count(*) AS n FROM input GROUP BY region")));

    private static Map<String, Object> flat(Map<String, Object> extra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "BOOK_ETL");
        m.put("dirs", Map.of("poll", "in", "database", "out"));
        m.put("processing", Map.of("threads", 1));
        m.putAll(extra);
        return m;
    }

    @Test
    void stageTwoHangsTheWorkbookBesideTheOutputStoreSink() throws Exception {
        PipelineGraph g = PipelineLift.stageTwo(PipelineConfig.fromMap(flat(Map.of("output_store", "shaped",
                "steps", List.of(Map.of("dedup", Map.of("keys", List.of("id")))), "excel", BOOK))));
        assertEquals(List.of("acquisition", "transform.dedup", "sink.persistent", "sink.excel"),
                g.nodes().stream().map(PipelineNode::type).toList());
        assertEquals(List.of("src>dedup", "dedup>sink", "dedup>excel"),
                g.edges().stream().map(e -> e.from() + ">" + e.to()).toList());
        assertEquals(BOOK, g.byId().get("excel").config());
        assertTrue(PipelineValidator.validate(g).ok(), () -> PipelineValidator.validate(g).errors().toString());
        assertEquals(java.util.Set.of("shaped"), PipelineStores.produced(g), "a workbook declares no store");
    }

    @Test
    void aWorkbookWithNoStepsStillLiftsAtRest() throws Exception {
        PipelineGraph g = PipelineLift.stageTwo(PipelineConfig.fromMap(flat(Map.of("output_store", "copy", "excel", BOOK))));
        assertEquals(List.of("src>sink", "src>excel"), g.edges().stream().map(e -> e.from() + ">" + e.to()).toList());
    }

    @Test
    void lowerWritesTheBlockVerbatimAndAStrictLowerWithoutTheNodeDeletesIt() throws Exception {
        Map<String, Object> raw = flat(Map.of("excel", BOOK));
        PipelineGraph g = PipelineCodec.fromMap(PipelineEditable.toMap(PipelineConfig.fromMap(raw), raw));
        assertEquals(1, g.nodes().stream().filter(n -> "sink.excel".equals(n.type())).count());
        String sinkFeed = g.edges().stream().filter(e -> e.to().equals("sink")).findFirst().orElseThrow().from();
        assertTrue(g.edges().stream().anyMatch(e -> e.from().equals(sinkFeed) && e.to().equals("excel")));

        assertEquals(BOOK, PipelineEditable.lower(g, raw, false).get("excel"), "round trip is verbatim");

        List<PipelineNode> without = new ArrayList<>();
        for (PipelineNode n : g.nodes()) {
            if ("sink.excel".equals(n.type())) continue;
            if (!"parse".equals(n.id())) { without.add(n); continue; }
            Map<String, Object> c = new LinkedHashMap<>(n.config());
            c.put("schema_file", "orders_schema.toon");
            without.add(new PipelineNode(n.id(), n.type(), n.name(), n.description(), c, n.use()));
        }
        PipelineGraph deleted = new PipelineGraph(g.name(), g.active(), without,
                g.edges().stream().filter(e -> !e.to().equals("excel")).toList());
        assertFalse(PipelineEditable.lower(deleted, raw, true).containsKey("excel"));
        assertEquals(BOOK, PipelineEditable.lower(deleted, raw, false).get("excel"));
    }

    @Test
    void lowerRefusesASecondWorkbookABadSheetNameAndANonSelectSheet() throws Exception {
        Map<String, Object> raw = flat(Map.of());
        PipelineGraph base = PipelineCodec.fromMap(PipelineEditable.toMap(PipelineConfig.fromMap(raw), raw));

        List<PipelineNode> two = new ArrayList<>(base.nodes());
        two.add(PipelineNode.of("x1", "sink.excel", new LinkedHashMap<>(BOOK)));
        two.add(PipelineNode.of("x2", "sink.excel", new LinkedHashMap<>(BOOK)));
        var multi = assertThrows(PipelineCompileException.class,
                () -> PipelineEditable.lower(new PipelineGraph(base.name(), false, two, base.edges()), raw, false));
        assertTrue(multi.refusals().stream().anyMatch(r -> PipelineEditable.MULTI_EXCEL.equals(r.code())
                && "x2".equals(r.nodeId())), multi.refusals().toString());

        for (Map<String, Object> bad : List.of(
                Map.<String, Object>of("path", "a.xlsx", "sheets", List.of(Map.of("name", "a/b"))),
                Map.<String, Object>of("path", "../a.xlsx", "sheets", List.of(Map.of("name", "A"))),
                Map.<String, Object>of("path", "a.xlsx", "sheets", List.of(Map.of("name", "A", "sql", "DELETE FROM input"))))) {
            List<PipelineNode> nodes = new ArrayList<>(base.nodes());
            nodes.add(PipelineNode.of("x", "sink.excel", new LinkedHashMap<>(bad)));
            var invalid = assertThrows(PipelineCompileException.class,
                    () -> PipelineEditable.lower(new PipelineGraph(base.name(), false, nodes, base.edges()), raw, false));
            assertTrue(invalid.refusals().stream().anyMatch(r -> PipelineEditable.EXCEL_INVALID.equals(r.code())),
                    bad + " -> " + invalid.refusals());
        }
        List<PipelineNode> good = new ArrayList<>(base.nodes());
        good.add(PipelineNode.of("x", "sink.excel", new LinkedHashMap<>(BOOK)));
        assertEquals(BOOK, PipelineEditable.lower(new PipelineGraph(base.name(), false, good, base.edges()), raw, false)
                .get("excel"), "the same probe with a valid block lowers");
        assertTrue(PipelineEditable.isLowerable("sink.excel"));
    }

    private static Map<String, Object> recipe(Map<String, Object> book) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", "book_recipe");
        r.put("active", false);
        r.put("steps", List.of(
                Map.of("collect", Map.of("poll", "in")),
                Map.of("parse", Map.of("schema_file", "schemas/orders_schema.toon")),
                Map.of("sink", Map.of("database", "out")),
                Map.of("excel", book)));
        return r;
    }

    @Test
    void theRecipeVerbCompilesAndTheConverterProjectsItBack() {
        Map<String, Object> flat = RecipeCompiler.compile(recipe(BOOK));
        assertEquals(BOOK, flat.get("excel"));
        assertTrue(((List<?>) RecipeConverter.toRecipe(flat).get("steps")).contains(Map.of("excel", BOOK)));
        var e = assertThrows(PipelineCompileException.class, () -> RecipeCompiler.compile(recipe(
                Map.of("path", "a.xlsx", "sheets", List.of(Map.of("name", "History"))))));
        assertTrue(e.refusals().stream().anyMatch(r -> "excel-4".equals(r.nodeId())
                && r.message().contains("reserved")), e.refusals().toString());
    }
}
