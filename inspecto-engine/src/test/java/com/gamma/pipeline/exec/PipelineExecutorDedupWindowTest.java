package com.gamma.pipeline.exec;

import com.gamma.consignment.DbDedupLedger;
import com.gamma.pipeline.PipelineEdge;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineNode;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-9 — windowed record dedup through the {@link RowShaper.ExecutionContext} seam, end-to-end over
 * embedded DuckDB and a real {@link DbDedupLedger}: two Consignments run the same
 * {@code scope: window(P4D)} dedup graph through {@link PipelineExecutor#execute}; the second run's
 * duplicate row leaves on the {@code duplicate} relation instead of reaching the sink, a
 * {@code retract} (the reprocess path) re-admits it, and the no-context default refuses loudly.
 */
class PipelineExecutorDedupWindowTest {

    private static final String STORE = com.gamma.pipeline.PipelineStores.CONFIG_STORE;

    @TempDir Path dir;
    private DbDedupLedger ledger;

    @BeforeEach
    void open() throws Exception {
        ledger = new DbDedupLedger(DriverManager.getConnection("jdbc:duckdb:"));
    }

    @AfterEach
    void close() {
        if (ledger != null) ledger.close();
    }

    /** What one batch produced: the sink's key column values + the duplicate relation's. */
    private record Batch(List<String> sinkKeys, List<String> duplicateKeys) {}

    private PipelineGraph windowedGraph(String scope, String orderBy) {
        Map<String, Object> cfg = new java.util.LinkedHashMap<>();
        cfg.put("keys", List.of("msisdn"));
        if (orderBy != null) cfg.put("order_by", orderBy);
        if (scope != null) cfg.put("scope", scope);
        return new PipelineGraph("DEDUP_WIN", true,
                List.of(PipelineNode.of("parse", "parser"),
                        PipelineNode.of("d", "transform.dedup", cfg),
                        PipelineNode.of("sink", "sink.persistent", Map.of(STORE, "out"))),
                List.of(PipelineEdge.data("parse", "d"), PipelineEdge.data("d", "sink")));
    }

    /**
     * Run one Consignment on its OWN scratch connection (exactly as production does — each batch gets a
     * fresh DuckDB), seeded with {@code valuesSql} rows of {@code (msisdn, event_time)}.
     */
    private Batch runBatch(PipelineGraph g, String valuesSql, String batchId,
                           RowShaper.ExecutionContext ctx) throws Exception {
        File db = DuckDbUtil.tempDbFile("dwx_");
        try (Connection conn = DuckDbUtil.openConnection(db)) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE seed AS SELECT * FROM (VALUES " + valuesSql + ") t(msisdn, event_time)");
            }
            BranchCommitCoordinator coordinator = new BranchCommitCoordinator(
                    new BranchCommitLog(dir.resolve("bc_" + batchId + ".csv").toString()));
            PipelineExecutor.ExecResult res = PipelineExecutor.execute(conn, g, Map.of("parse", "seed"),
                    batchId, coordinator, (sink, table) -> {}, () -> {},
                    PipelineExecutor.ProvenanceCollector.NONE, RowShaper.ReferenceResolver.NONE, null, ctx);
            return new Batch(col(conn, res.sinkInputs().get("sink"), "msisdn"),
                    col(conn, res.produced().get("d").get("duplicate"), "msisdn"));
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    private static List<String> col(Connection conn, String table, String column) throws Exception {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT " + column + " FROM \"" + table + "\" ORDER BY 1")) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    @Test
    void aSecondConsignmentInsideTheWindowLosesTheKeyToTheDuplicateRelation() throws Exception {
        PipelineGraph g = windowedGraph("window(P4D)", "event_time DESC");

        Batch first = runBatch(g, "('A', DATE '2026-08-02'), ('B', DATE '2026-08-02')", "c1",
                new RowShaper.ExecutionContext("pipe1", "c1", ledger));
        assertEquals(List.of("A", "B"), first.sinkKeys(), "the first Consignment wins every key");
        assertEquals(2, ledger.size(), "both keys claimed durably");

        // A arrives again with the SAME event date — same epoch-anchored window by construction.
        // (An event date near a window boundary may legitimately open a new window; that is the
        // window advancing, not a duplicate.)
        Batch second = runBatch(g, "('A', DATE '2026-08-02'), ('C', DATE '2026-08-02')", "c2",
                new RowShaper.ExecutionContext("pipe1", "c2", ledger));
        assertEquals(List.of("C"), second.sinkKeys(),
                "A was admitted by c1 inside the window and must not reach the sink again");
        assertEquals(List.of("A"), second.duplicateKeys(),
                "the cross-Consignment loser leaves on the duplicate relation, exactly like an in-batch loser");
        assertEquals(3, ledger.size(), "c2 claimed only the key it won");
    }

    @Test
    void retractingASupersededConsignmentReAdmitsItsKeys() throws Exception {
        PipelineGraph g = windowedGraph("window(P4D)", "event_time DESC");

        runBatch(g, "('A', DATE '2026-08-02')", "c1", new RowShaper.ExecutionContext("pipe1", "c1", ledger));
        // The reprocess path: registry.supersede(batchId) is accompanied by ledger.retract(batchId)
        // (ReprocessCommand step 4b) — without it the re-ingest below would drop the row forever.
        assertEquals(1, ledger.retract("c1"));

        Batch redo = runBatch(g, "('A', DATE '2026-08-02')", "c1r",
                new RowShaper.ExecutionContext("pipe1", "c1r", ledger));
        assertEquals(List.of("A"), redo.sinkKeys(),
                "after retract the re-ingested row must come back — the D-9 correctness risk");
    }

    @Test
    void aWindowedScopeRefusesLoudlyWithoutExecutionContext() {
        PipelineGraph g = windowedGraph("window(P4D)", "event_time DESC");
        Exception e = assertThrows(Exception.class,
                () -> runBatch(g, "('A', DATE '2026-08-02')", "cn", RowShaper.ExecutionContext.NONE));
        assertTrue(String.valueOf(e.getMessage()).contains("ExecutionContext"),
                "the refusal must name the missing seam: " + e.getMessage());
    }

    @Test
    void aWindowedScopeWithoutOrderByRefusesAtRun() {
        PipelineGraph g = windowedGraph("window(P4D)", null);
        Exception e = assertThrows(Exception.class, () -> runBatch(g, "('A', DATE '2026-08-02')", "co",
                new RowShaper.ExecutionContext("pipe1", "co", ledger)));
        assertTrue(String.valueOf(e.getMessage()).contains("order_by"), e.getMessage());
    }

    @Test
    void aMalformedScopeSpellingRefuses() {
        PipelineGraph g = windowedGraph("window(4 days)", "event_time DESC");
        Exception e = assertThrows(Exception.class, () -> runBatch(g, "('A', DATE '2026-08-02')", "cm",
                new RowShaper.ExecutionContext("pipe1", "cm", ledger)));
        assertTrue(String.valueOf(e.getMessage()).contains("ISO-8601"), e.getMessage());
    }

    @Test
    void aPlainDedupIsUntouchedByTheSeam() throws Exception {
        PipelineGraph g = windowedGraph(null, "event_time DESC");
        Batch res = runBatch(g, "('A', DATE '2026-08-02'), ('A', DATE '2026-08-01')", "cp",
                RowShaper.ExecutionContext.NONE);
        assertEquals(List.of("A"), res.sinkKeys(),
                "scope-less dedup stays within-Consignment and needs no context");
        assertEquals(List.of("A"), res.duplicateKeys(), "the in-batch loser still leaves on duplicate");
        assertEquals(0, ledger.size(), "no scope: nothing ever touches the ledger");
    }
}
