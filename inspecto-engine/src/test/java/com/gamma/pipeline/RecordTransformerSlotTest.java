package com.gamma.pipeline;

import com.gamma.etl.PipelineConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 3 — the projection SLOT may be authored as a Record Transformer, and survives a lift/lower
 * round-trip as one.
 *
 * <p>🔴 The round-trip is the whole point. {@code transform.sql} is ALSO a chain kind with a
 * {@code STEP_KIND} entry, so without {@link PipelineEditable#isProjectionSlot} a slot node would lower
 * into {@code steps:} instead of back into {@code processing.map} — the projection would migrate into
 * the chain on every save, changing what executes and when.
 */
class RecordTransformerSlotTest {

    @TempDir Path tmp;

    private PipelineConfig configWith(String processingMap) throws Exception {
        Path schema = tmp.resolve("orders_schema.toon");
        Files.writeString(schema, """
                raw:
                  name: orders
                  format: CSV
                  fields[2]{name,selector,type}:
                    ORDER_ID,"0",VARCHAR
                    AMOUNT,"1",DOUBLE
                mapping:
                  canonicalName: orders
                  rules[2]{targetColumn,sourceExpression,transformType}:
                    ORDER_ID,ORDER_ID,DIRECT
                    AMOUNT,AMOUNT,DIRECT
                """);
        Path flat = tmp.resolve("orders_pipeline.toon");
        Files.writeString(flat, """
                name: orders
                active: false
                dirs:
                  poll: in
                  database: out
                processing:
                  threads: 1
                  schema_file: %s
                %s
                """.formatted(schema.toString().replace('\\', '/'), processingMap));
        return PipelineConfig.load(flat.toString());
    }

    @Test
    void authoredFieldsMakeTheSlotARecordTransformer() throws Exception {
        PipelineConfig cfg = configWith("""
                  map:
                    fields[1]{name,from,fn}:
                      order_ref,ORDER_ID,text.trim
                """);

        assertEquals(1, cfg.mapConfig().fields().size(), "processing.map.fields must parse");

        PipelineGraph g = PipelineLift.lift(cfg);
        PipelineNode slot = g.byId().get("map");
        assertNotNull(slot, "the projection slot keeps its id grammar");
        assertEquals(BuiltinNodeType.TRANSFORM_SQL.type(), slot.type(),
                "authored fields[] make the slot a Record Transformer");
        assertTrue(PipelineEditable.isProjectionSlot(slot));
    }

    /** No fields[] ⇒ the slot is STILL a Record Transformer: transform.map is gone (2026-09-05). */
    @Test
    void withoutFieldsTheSlotIsStillARecordTransformer() throws Exception {
        PipelineGraph g = PipelineLift.lift(configWith(""));
        PipelineNode slot = g.byId().get("map");
        assertEquals(BuiltinNodeType.TRANSFORM_SQL.type(), slot.type());
        assertTrue(PipelineEditable.isProjectionSlot(slot));
    }

    /**
     * The load-bearing assertion: a Record Transformer slot lowers back into {@code processing.map},
     * NOT into {@code steps:}.
     */
    @Test
    void theSlotLowersBackIntoProcessingMapRatherThanTheChain() throws Exception {
        PipelineConfig cfg = configWith("""
                  map:
                    fields[1]{name,from,fn}:
                      order_ref,ORDER_ID,text.trim
                """);
        PipelineGraph g = PipelineLift.lift(cfg);

        Map<String, Object> lowered = PipelineEditable.lower(g, Map.of(), false);

        @SuppressWarnings("unchecked")
        Map<String, Object> processing = (Map<String, Object>) lowered.get("processing");
        assertNotNull(processing, "lower must emit processing");
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) processing.get("map");
        assertNotNull(map, "the Record Transformer slot belongs in processing.map");
        assertTrue(map.containsKey("fields"), "its fields[] must survive the round trip: " + map);
        assertNull(lowered.get("steps"),
                "the projection must NOT migrate into the steps: chain — that would change what executes");
    }

    /** A chain sql step keeps its own id grammar and is NOT mistaken for the slot. */
    @Test
    void aChainSqlStepIsNotTheProjectionSlot() {
        PipelineNode chainStep = PipelineNode.of("sql", BuiltinNodeType.TRANSFORM_SQL.type(),
                Map.of("sql", "SELECT * FROM input"));
        assertFalse(PipelineEditable.isProjectionSlot(chainStep));

        PipelineNode repeated = PipelineNode.of("sql__s2", BuiltinNodeType.TRANSFORM_SQL.type(),
                Map.of("sql", "SELECT * FROM input"));
        assertFalse(PipelineEditable.isProjectionSlot(repeated));
    }
}
