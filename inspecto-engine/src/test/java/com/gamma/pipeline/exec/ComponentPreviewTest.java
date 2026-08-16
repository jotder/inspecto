package com.gamma.pipeline.exec;

import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.PipelineRel;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ComponentPreview} (T18): dry-run a transform over sample rows through the production
 * {@link RowShaper} on a throwaway DuckDB — the same logic a real run uses, scratch-only.
 */
class ComponentPreviewTest {

    /** Ordered rows (a real JSON body decodes to an ordered map), so the input column order is deterministic. */
    private static Map<String, Object> row(String id, String grp, String amt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("grp", grp);
        m.put("amt", amt);
        return m;
    }

    private static final List<Map<String, Object>> SAMPLE = List.of(
            row("1", "a", "150"), row("2", "b", "50"), row("3", "a", "200"));

    private static ComponentPreview.RelationPreview rel(ComponentPreview.Result r, String rel) {
        return r.relations().stream().filter(p -> p.rel().equals(rel)).findFirst().orElseThrow();
    }

    @Test
    void filterPreviewSplitsKeptAndDropped() throws Exception {
        PipelineNode node = PipelineNode.of("f", "transform.filter", Map.of("where", "CAST(amt AS INT) >= 100"));
        ComponentPreview.Result r = ComponentPreview.transform(node, SAMPLE);

        assertEquals(List.of("id", "grp", "amt"), r.inputColumns());
        assertEquals(2, rel(r, PipelineRel.DATA).rowCount());        // ids 1, 3
        assertEquals(1, rel(r, PipelineRel.DROPPED).rowCount());     // id 2
        assertEquals("1", rel(r, PipelineRel.DATA).rows().get(0).get("id").toString());
    }

    @Test
    void mapPreviewProjectsColumns() throws Exception {
        PipelineNode node = PipelineNode.of("m", "transform.map",
                Map.of("columns", List.of(Map.of("name", "ident", "expr", "id"),
                        Map.of("name", "double_amt", "expr", "CAST(amt AS INT) * 2"))));
        ComponentPreview.Result r = ComponentPreview.transform(node, SAMPLE);

        ComponentPreview.RelationPreview data = rel(r, PipelineRel.DATA);
        assertEquals(3, data.rowCount());
        Map<String, Object> row0 = data.rows().get(0);
        assertTrue(row0.containsKey("ident"));
        assertTrue(row0.containsKey("double_amt"));
    }

    @Test
    void emptySampleAndUnsupportedTypeAreRejected() {
        PipelineNode filter = PipelineNode.of("f", "transform.filter", Map.of("where", "true"));
        assertThrows(IllegalArgumentException.class, () -> ComponentPreview.transform(filter, List.of()));
        // merge is multi-input → not previewable via the single-input shaper
        PipelineNode merge = PipelineNode.of("g", "transform.merge", Map.of("type", "union"));
        assertThrows(IllegalArgumentException.class, () -> ComponentPreview.transform(merge, SAMPLE));
    }

    // ── grammar ──────────────────────────────────────────────────────────────────

    @Test
    void grammarPreviewParsesRawTextWithDialect() throws Exception {
        Map<String, Object> grammar = Map.of("delimiter", "|", "has_header", true);
        ComponentPreview.GrammarResult r =
                ComponentPreview.grammar(grammar, "a|b|c\n1|2|3\n4|5|6\n");

        assertEquals(List.of("a", "b", "c"), r.columns());
        assertEquals(2, r.rowCount());
        assertEquals(0, r.rejectedRows());
        assertEquals("1", r.rows().get(0).get("a").toString());
    }

    @Test
    void grammarPreviewRejectsEmptyText() {
        assertThrows(IllegalArgumentException.class,
                () -> ComponentPreview.grammar(Map.of("delimiter", ","), "   "));
    }

    // ── schema ───────────────────────────────────────────────────────────────────

    @Test
    void schemaPreviewSplitsDataVsRejectedByCast() throws Exception {
        Map<String, Object> schema = Map.of("fields", List.of(
                Map.of("name", "id", "type", "integer"),
                Map.of("name", "amt", "type", "double")));
        List<Map<String, Object>> sample = List.of(
                two("id", "1", "amt", "150"),     // ok
                two("id", "x", "amt", "50"),       // id not int → rejected
                two("id", "3", "amt", "abc"));     // amt not double → rejected

        ComponentPreview.Result r = ComponentPreview.schema(schema, sample);
        assertEquals(1, rel(r, "data").rowCount());
        assertEquals(2, rel(r, "rejected").rowCount());
        assertEquals("1", rel(r, "data").rows().get(0).get("id").toString());
    }

    // ── schema: the mapped relation (B1, definition-surface unification P4) ──────

    /** A draft in the shape {@code DataTransformer.dataColumns} needs: {@code raw.fields} + {@code mapping.rules}. */
    private static Map<String, Object> draft(List<Map<String, Object>> fields, List<Map<String, Object>> rules) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("raw", Map.of("fields", fields));
        m.put("mapping", Map.of("rules", rules));
        return m;
    }

    @Test
    void schemaPreviewCompilesMappingRulesIntoAMappedRelation() throws Exception {
        Map<String, Object> schema = draft(
                List.of(Map.of("name", "id", "type", "integer"),
                        Map.of("name", "amt", "type", "double")),
                List.of(Map.of("targetColumn", "account", "sourceExpression", "id",
                               "transformType", "DIRECT"),
                        Map.of("targetColumn", "amount", "sourceExpression", "amt",
                               "transformType", "DIRECT")));
        List<Map<String, Object>> sample = List.of(
                two("id", "1", "amt", "150"),
                two("id", "x", "amt", "50"));    // rejected by the cast, so never reaches the mapping

        ComponentPreview.Result r = ComponentPreview.schema(schema, sample);
        ComponentPreview.RelationPreview mapped = rel(r, "mapped");
        assertEquals(1, mapped.rowCount(), "only the cast-passing row is mapped");
        // The projection renames: target columns, not the input's.
        assertEquals(List.of("account", "amount"), List.copyOf(mapped.rows().get(0).keySet()));
        assertEquals("1", mapped.rows().get(0).get("account").toString());
    }

    /** The reason B1 exists at all: an EXPR rule's effect is invisible until the rule is compiled. */
    @Test
    void schemaPreviewAppliesAnExprRuleVerbatim() throws Exception {
        Map<String, Object> schema = draft(
                List.of(Map.of("name", "code", "type", "VARCHAR")),
                List.of(Map.of("targetColumn", "shout", "sourceExpression", "UPPER(code)",
                               "transformType", "EXPR")));
        ComponentPreview.Result r = ComponentPreview.schema(
                schema, List.of(Map.of("code", "ab"), Map.of("code", "cd")));

        ComponentPreview.RelationPreview mapped = rel(r, "mapped");
        assertEquals(2, mapped.rowCount());
        assertEquals("AB", mapped.rows().get(0).get("shout").toString());
    }

    /** Additive: the cast-only callers (the onboarding pane posts no rules) are untouched. */
    @Test
    void schemaPreviewWithoutMappingRulesProducesNoMappedRelation() throws Exception {
        Map<String, Object> schema = Map.of("raw", Map.of("fields",
                List.of(Map.of("name", "id", "type", "integer"))));
        ComponentPreview.Result r = ComponentPreview.schema(schema, List.of(Map.of("id", "1")));

        assertEquals(List.of("data", "rejected"),
                r.relations().stream().map(ComponentPreview.RelationPreview::rel).toList());
    }

    /**
     * A draft using the looser top-level {@code fields} that {@link ComponentPreview#schema} accepts is not
     * the shape {@code dataColumns} reads — it must skip the mapped relation, not fail the whole preview.
     */
    @Test
    void schemaPreviewSkipsMappingForADraftWithoutRawFields() throws Exception {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("fields", List.of(Map.of("name", "id", "type", "integer")));
        schema.put("mapping", Map.of("rules", List.of(
                Map.of("targetColumn", "account", "sourceExpression", "id", "transformType", "DIRECT"))));

        ComponentPreview.Result r = ComponentPreview.schema(schema, List.of(Map.of("id", "1")));
        assertEquals(1, rel(r, "data").rowCount());
        assertTrue(r.relations().stream().noneMatch(p -> p.rel().equals("mapped")));
    }

    // ── sink ─────────────────────────────────────────────────────────────────────

    @Test
    void sinkPreviewValidatesStoreFormatAndPartitions() {
        Map<String, Object> sink = Map.of("store", "out", "format", "parquet",
                "partitions", List.of("year"));
        ComponentPreview.SinkResult r = ComponentPreview.sink(sink, SAMPLE);

        assertEquals("out", r.store());
        assertEquals(3, r.rowCount());
        // "year" is not a sample column → exactly one partition warning, nothing else
        assertEquals(1, r.warnings().size());
        assertTrue(r.warnings().get(0).contains("year"));
    }

    @Test
    void sinkPreviewWarnsOnPartitionSourceMissingFromSample() {
        // 'grp' is a sample column, so the partition itself is fine; the typo'd source is not
        Map<String, Object> sink = Map.of("store", "out", "format", "parquet",
                "partitions", List.of(two("column", "grp", "source", "amount")));
        ComponentPreview.SinkResult r = ComponentPreview.sink(sink, SAMPLE);

        assertEquals(1, r.warnings().size());
        assertTrue(r.warnings().get(0).contains("source"));
        assertTrue(r.warnings().get(0).contains("amount"));
    }

    @Test
    void sinkPreviewAcceptsPartitionSourcePresentInSample() {
        Map<String, Object> sink = Map.of("store", "out", "format", "parquet",
                "partitions", List.of(two("column", "grp", "source", "amt")));
        assertEquals(List.of(), ComponentPreview.sink(sink, SAMPLE).warnings());
    }

    @Test
    void sinkPreviewWarnsOnAPartitionEntryWithNoColumn() {
        // the writer throws on this one, so the preview has to say so while the author can still fix it
        Map<String, Object> sink = Map.of("store", "out", "format", "parquet",
                "partitions", List.of(Map.of("source", "amt")));
        ComponentPreview.SinkResult r = ComponentPreview.sink(sink, SAMPLE);

        assertEquals(1, r.warnings().size());
        assertTrue(r.warnings().get(0).contains("declares no 'column'"));
    }

    @Test
    void sinkPreviewWarnsOnBlankPartitionSource() {
        // declaredEventTimeSource returns null outright on a present-but-empty source → no bounds at all
        Map<String, Object> sink = Map.of("store", "out", "format", "parquet",
                "partitions", List.of(two("column", "grp", "source", "   ")));
        ComponentPreview.SinkResult r = ComponentPreview.sink(sink, SAMPLE);

        assertEquals(1, r.warnings().size());
        assertTrue(r.warnings().get(0).contains("empty 'source'"));
    }

    @Test
    void sinkPreviewWarnsOnPartitionSourceThatIsNotAPlainIdentifier() {
        // the column really is in the relation, so presence alone would pass it — but the writer's
        // SAFE_COLUMN check rejects it and records no bounds, so the author must still hear about it
        List<Map<String, Object>> sample = List.of(Map.of("TXN DATE", "2026-01-01"));
        Map<String, Object> sink = Map.of("store", "out", "format", "parquet",
                "partitions", List.of(two("column", "TXN DATE", "source", "TXN DATE")));
        ComponentPreview.SinkResult r = ComponentPreview.sink(sink, sample);

        assertEquals(1, r.warnings().size());
        assertTrue(r.warnings().get(0).contains("not a plain column identifier"));
    }

    @Test
    void sinkPreviewWarnsWhenPartitionsDisagreeOnSource() {
        // both sources exist, but PartitionSinkWriter identifies no single event time → no bounds recorded
        Map<String, Object> sink = Map.of("store", "out", "format", "parquet",
                "partitions", List.of(two("column", "grp", "source", "amt"),
                                      two("column", "id", "source", "grp")));
        ComponentPreview.SinkResult r = ComponentPreview.sink(sink, SAMPLE);

        assertEquals(1, r.warnings().size());
        assertTrue(r.warnings().get(0).contains("more than one 'source'"));
    }

    @Test
    void sinkPreviewWarnsOnMissingStoreAndBadFormat() {
        Map<String, Object> sink = Map.of("format", "xlsx");   // no store, unknown format
        ComponentPreview.SinkResult r = ComponentPreview.sink(sink, SAMPLE);
        assertNull(r.store());
        assertEquals(2, r.warnings().size());
    }

    private static Map<String, Object> two(String k1, String v1, String k2, String v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }
}
