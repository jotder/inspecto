package com.gamma.pipeline.exec;

import com.gamma.pipeline.ExecutionMode;
import com.gamma.pipeline.PipelineEdge;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.PipelineNodeType;
import com.gamma.pipeline.PipelineNodeTypes;
import com.gamma.pipeline.PipelineRel;
import com.gamma.pipeline.PipelineStores;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * X5 — what the walk tells a {@link PipelineExecutor.WalkObserver} when it fails, at the grain the job lane's
 * receipts are built from: which Step is FAILED, wherever in the walk the throw happens (inside a Step, before a
 * Step started, in a sink's branch record), and that a cyclic cause chain never hangs the walk.
 */
class PipelineExecutorReceiptTest {

    private static final String OWNER = "receipt-walk.test.jar";

    private Connection conn;

    @BeforeEach
    void open() throws Exception {
        conn = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE parsed AS SELECT * FROM (VALUES (1, 150), (2, 50), (3, 200)) t(id, amt)");
        }
    }

    @AfterEach
    void close() throws Exception {
        StepExecutors.deregister(OWNER);
        PipelineNodeTypes.deregister(OWNER);
        conn.close();
    }

    private record Type(String type) implements PipelineNodeType {
        @Override public Optional<ExecutionMode> mode() { return Optional.of(ExecutionMode.EXECUTED); }
    }

    /** A contributed Step of {@code type} that throws {@code t}. */
    private static void registerThrowing(String type, RuntimeException t) {
        PipelineNodeTypes.register(new Type(type), OWNER);
        StepExecutors.register(new StepExecutor() {
            @Override public String type() { return type; }
            @Override public void execute(StepContext ctx) { throw t; }
        }, OWNER, StepExecutors.Grant.NONE);
    }

    private static PipelineGraph filterThen(String type, String... sinks) {
        List<PipelineNode> nodes = new java.util.ArrayList<>(List.of(PipelineNode.of("parse", "parser"),
                PipelineNode.of("flt", "transform.filter", Map.of("where", "amt >= 100"))));
        List<PipelineEdge> edges = new java.util.ArrayList<>(List.of(PipelineEdge.data("parse", "flt")));
        String from = "flt";
        if (type != null) {
            nodes.add(PipelineNode.of("n", type, Map.of()));
            edges.add(PipelineEdge.data("flt", "n"));
            from = "n";
        }
        for (String s : sinks) {
            nodes.add(PipelineNode.of(s, "sink.persistent", Map.of(PipelineStores.CONFIG_STORE, s)));
            edges.add(PipelineEdge.data(from, s));
        }
        return new PipelineGraph("RCPT", true, nodes, edges);
    }

    private static StepReceiptRecorder recorder(PipelineGraph g) {
        return new StepReceiptRecorder(g, "c-1", "RCPT", StepReceipt.LANE_JOB, "run-1", false, null);
    }

    private static StepReceipt receipt(StepReceiptRecorder rec, String step) {
        return rec.receipts().stream().filter(r -> r.step().equals(step)).findFirst()
                .orElseThrow(() -> new AssertionError("no receipt for " + step + " in " + rec.receipts()));
    }

    private static BranchCommitCoordinator coordinator(Path log) {
        return new BranchCommitCoordinator(new BranchCommitLog(log.toString()));
    }

    private ExecResultOrThrow walk(PipelineGraph g, Map<String, String> seeds, Path log,
                                   PipelineExecutor.SinkWriter writer, PipelineExecutor.WalkObserver obs) {
        try {
            PipelineExecutor.execute(conn, g, seeds, "b1", coordinator(log), writer, () -> {}, obs,
                    RowShaper.ReferenceResolver.NONE, null, RowShaper.ExecutionContext.NONE);
            return new ExecResultOrThrow(null);
        } catch (Exception e) {
            return new ExecResultOrThrow(e);
        }
    }

    private record ExecResultOrThrow(Exception thrown) {}

    /**
     * 🔴 A cause chain A → B → A. Before the fix the diagnosis looped forever inside the walk's failure handler, so
     * a failing run became a hung thread. Both through a receipt observer and through the pre-X5 overload.
     */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aCyclicCauseChainFailsTheWalkInsteadOfHangingIt(@TempDir Path work) {
        RuntimeException a = new IllegalStateException("a");
        RuntimeException b = new IllegalArgumentException("b");
        a.initCause(b);
        b.initCause(a);
        registerThrowing("transform.rcpt_cycle", a);
        PipelineGraph g = filterThen("transform.rcpt_cycle", "out");

        StepReceiptRecorder rec = recorder(g);
        assertNotNull(walk(g, Map.of("parse", "parsed"), work.resolve("c1.csv"), (s, t) -> {}, rec).thrown());
        assertEquals(StepReceipt.Outcome.FAILED, receipt(rec, "n").outcome());
        assertEquals(StepReceipt.STEP_FAILED, receipt(rec, "n").diag().code());

        // a fresh connection, so the old overload fails on the cycling Step, not on the first walk's relations
        try (Connection fresh = DriverManager.getConnection("jdbc:duckdb:"); Statement st = fresh.createStatement()) {
            st.execute("CREATE TABLE parsed AS SELECT * FROM (VALUES (1, 150), (2, 50), (3, 200)) t(id, amt)");
            Exception e = assertThrows(Exception.class, () -> PipelineExecutor.execute(fresh, g, "parse", "parsed",
                    "b2", coordinator(work.resolve("c2.csv")), (s, t) -> {}, () -> {}), "the pre-X5 overload fails too");
            Throwable root = e;
            while (root != a && root != b && root.getCause() != null && root.getCause() != root) root = root.getCause();
            assertTrue(root == a || root == b, "it fails on the cycling Step, not on something else: " + e);
        } catch (java.sql.SQLException e) {
            fail(e);
        }
        assertEquals(StepReceipt.STEP_FAILED, StepReceipt.Diagnostics.of(a).code(), "and the diagnosis terminates");
    }

    /** A throw before any Step started — here the seed's own count — is still that Step's failure. */
    @Test
    void aFailureBeforeAStepStartsIsStillThatStepsFailure(@TempDir Path work) {
        PipelineGraph g = filterThen(null, "out");
        StepReceiptRecorder rec = recorder(g);
        assertNotNull(walk(g, Map.of("parse", "no_such_table"), work.resolve("c.csv"), (s, t) -> {}, rec).thrown());

        assertEquals(StepReceipt.Outcome.FAILED, receipt(rec, "parse").outcome(), "the seed's count threw");
        assertEquals(StepReceipt.SQL_ERROR, receipt(rec, "parse").diag().code());
        assertEquals(StepReceipt.Outcome.NOT_REACHED, receipt(rec, "flt").outcome());
        assertEquals(StepReceipt.Outcome.NOT_REACHED, receipt(rec, "out").outcome());
    }

    /**
     * A sink is OK only once its branch is recorded durable. The write returns, then the branch record fails: the
     * sink is FAILED (IO_ERROR), not OK — the branch is not committed.
     */
    @Test
    void aFailedBranchRecordFailsItsSink(@TempDir Path work) {
        PipelineGraph g = filterThen(null, "out");
        Path log = work.resolve("commit.csv");
        StepReceiptRecorder rec = recorder(g);
        // the write succeeds, then the commit log becomes unwritable, so recordBranch throws
        Exception thrown = walk(g, Map.of("parse", "parsed"), log, (sink, table) -> {
            Files.delete(log);
            Files.createDirectory(log);
        }, rec).thrown();
        assertNotNull(thrown);

        assertEquals(StepReceipt.Outcome.OK, receipt(rec, "flt").outcome());
        StepReceipt out = receipt(rec, "out");
        assertEquals(StepReceipt.Outcome.FAILED, out.outcome(), "its branch never became durable");
        assertEquals(StepReceipt.IO_ERROR, out.diag().code());
    }

    /** The first branch committed; the second's write threw: the first stays OK, the second is FAILED. */
    @Test
    void aSecondBranchFailingLeavesTheCommittedFirstBranchOk(@TempDir Path work) {
        PipelineGraph g = filterThen(null, "out_a", "out_b");
        StepReceiptRecorder rec = recorder(g);
        assertNotNull(walk(g, Map.of("parse", "parsed"), work.resolve("c.csv"), (sink, table) -> {
            if (sink.id().equals("out_b")) throw new java.io.IOException("disk full");
        }, rec).thrown());

        assertEquals(StepReceipt.Outcome.OK, receipt(rec, "out_a").outcome());
        assertEquals(StepReceipt.Outcome.FAILED, receipt(rec, "out_b").outcome());
        assertEquals(StepReceipt.IO_ERROR, receipt(rec, "out_b").diag().code());
        assertEquals(2L, receipt(rec, "out_a").in());
    }

    /** A clean walk through the receipt observer still reports the counts the provenance collector always saw. */
    @Test
    void theProvenanceCollectorSeesEveryCountThroughTheRecorder(@TempDir Path work) {
        PipelineGraph g = filterThen(null, "out");
        Map<String, Long> seen = new java.util.LinkedHashMap<>();
        StepReceiptRecorder rec = new StepReceiptRecorder(g, "c-1", "RCPT", StepReceipt.LANE_JOB, "run-1", false,
                (node, rel, rows) -> seen.put(node + "|" + rel, rows));
        assertNull(walk(g, Map.of("parse", "parsed"), work.resolve("c.csv"), (s, t) -> {}, rec).thrown());
        assertEquals(Map.of("parse|data", 3L, "flt|data", 2L, "flt|" + PipelineRel.DROPPED, 1L, "out|data", 2L), seen);
        assertTrue(rec.receipts().stream().allMatch(r -> r.outcome() == StepReceipt.Outcome.OK));
        assertTrue(rec.receipts().stream().noneMatch(r -> Set.of("FAILED", "NOT_REACHED").contains(r.outcome().name())));
    }
}
