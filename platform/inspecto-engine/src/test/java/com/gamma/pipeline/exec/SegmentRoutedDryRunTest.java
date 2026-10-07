package com.gamma.pipeline.exec;

import com.gamma.pipeline.PipelineEdge;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.PipelineRel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code route:<segment>} edge out of a parser is WALKED — {@code WB-08} / decision {@code D5}.
 *
 * <p>🔴 <b>The defect.</b> A segment-routed frontend (ASN.1 BER; the plugin ingester over XML) lifts as
 * {@code parse →(route:<segment>)→ map_<segment> → sink_<segment>}, plus {@code unmatched → quarantine}.
 * {@link PipelineExecutor#liveInbound} follows an edge only when the upstream node's produced-map holds a
 * key equal to the edge's {@code rel}, and the dry-run seeded exactly one {@code data} table — so the walk
 * could never leave such a parser. <em>Run to here</em> decoded records and then returned
 * {@code relations: []} with an honest <em>"the sample reached no node past the seed"</em> warning. Two of
 * eight parse frontends, and every multi-record-type feed, had no test instrument past the decoder
 * ({@code TESTRUN-SEGMENT-ROUTE-NO-FLOW-1}).
 *
 * <p>⛔ <b>The walker is deliberately NOT special-cased</b> (D5): the fix is the SEED SHAPE. These tests
 * pin that — they assert the ordinary walk carries per-segment seeds, and that an unseeded branch stays
 * unvisited rather than being invented.
 */
class SegmentRoutedDryRunTest {

    /** {@code parse} routing two segments, each to its own transform, as the lift builds them. */
    private static PipelineGraph segmentRouted() {
        return new PipelineGraph("seg", false,
                List.of(PipelineNode.of("parse", "parser"),
                        PipelineNode.of("map_moCallRecord", "transform.filter", Map.of("where", "1=1")),
                        PipelineNode.of("map_smsRecord", "transform.filter", Map.of("where", "1=1"))),
                List.of(new PipelineEdge("parse", PipelineRel.route("moCallRecord"), "map_moCallRecord"),
                        new PipelineEdge("parse", PipelineRel.route("smsRecord"), "map_smsRecord")));
    }

    @Test
    @DisplayName("each segment's rows reach ONLY its own branch")
    void eachSegmentReachesItsOwnBranch() throws Exception {
        PipelineDryRun.Result r = PipelineDryRun.runSeeded(
                segmentRouted(),
                Map.of(PipelineRel.route("moCallRecord"),
                        List.of(Map.of("IMSI", "1", "DURATION", 10), Map.of("IMSI", "2", "DURATION", 20)),
                        PipelineRel.route("smsRecord"),
                        List.of(Map.of("IMSI", "3", "DURATION", 0))),
                RowShaper.ReferenceResolver.NONE, null);

        Map<String, Long> rows = rowsByNode(r);
        assertEquals(2L, rows.get("map_moCallRecord"),
                "the mo-call branch must receive its own two records, and only those: " + rows);
        assertEquals(1L, rows.get("map_smsRecord"),
                "the sms branch must receive its own single record: " + rows);
    }

    @Test
    @DisplayName("a branch with no seeded segment is NOT visited — absent, not zero")
    void anUnseededBranchIsNotVisited() throws Exception {
        PipelineDryRun.Result r = PipelineDryRun.runSeeded(
                segmentRouted(),
                Map.of(PipelineRel.route("moCallRecord"), List.of(Map.of("IMSI", "1", "DURATION", 10))),
                RowShaper.ReferenceResolver.NONE, null);

        Map<String, Long> rows = rowsByNode(r);
        assertTrue(rows.containsKey("map_moCallRecord"), "the seeded branch ran: " + rows);
        // ⛔ "did not run" and "ran and produced nothing" are different answers and the canvas renders
        // them differently — seeding an empty table for an unrouted segment would collapse the two.
        assertFalse(rows.containsKey("map_smsRecord"),
                "an unseeded segment's branch must be ABSENT, not present with zero: " + rows);
    }

    @Test
    @DisplayName("the single-schema case still seeds one plain data relation")
    void theSingleSchemaCaseIsUnchanged() throws Exception {
        PipelineGraph linear = new PipelineGraph("lin", false,
                List.of(PipelineNode.of("parse", "parser"),
                        PipelineNode.of("flt", "transform.filter", Map.of("where", "1=1"))),
                List.of(new PipelineEdge("parse", PipelineRel.DATA, "flt")));

        PipelineDryRun.Result r = PipelineDryRun.run(
                linear, List.of(Map.of("ID", "1"), Map.of("ID", "2")),
                RowShaper.ReferenceResolver.NONE, null);

        assertEquals(2L, rowsByNode(r).get("flt"),
                "the ordinary data path must be untouched by the segment seeding");
    }

    /** node id → the row count of its first produced relation. */
    private static Map<String, Long> rowsByNode(PipelineDryRun.Result r) {
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        for (PipelineDryRun.NodeDryRun n : r.nodes())
            out.put(n.node(), n.relations().isEmpty() ? 0L : (long) n.relations().getFirst().rowCount());
        return out;
    }
}
