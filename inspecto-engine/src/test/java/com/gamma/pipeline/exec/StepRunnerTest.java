package com.gamma.pipeline.exec;

import com.gamma.etl.TypeFlow;
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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S2-3 — the engine side of a contributed {@code EXECUTED} Step: {@link StepRunner} through the real
 * {@link PipelineExecutor} walk. The Steps here are in-process (registered straight into the overlays under a
 * test owner); the pack-loaded path — the grant, the ceiling, D-2 — is {@code JobPackManagerTest}'s.
 */
class StepRunnerTest {

    private static final String OWNER = "steprunner.test.jar";

    /** A contributed {@code EXECUTED} node type emitting {@code emits}. */
    private record Type(String type, Set<String> emits) implements PipelineNodeType {
        @Override public Optional<ExecutionMode> mode() { return Optional.of(ExecutionMode.EXECUTED); }
    }

    @FunctionalInterface
    private interface Body { void run(StepContext ctx) throws Exception; }

    private record Step(String type, Duration timeout, Body body) implements StepExecutor {
        @Override public void execute(StepContext ctx) throws Exception { body.run(ctx); }
    }

    private Connection conn;

    @BeforeEach
    void open() throws Exception {
        conn = DriverManager.getConnection("jdbc:duckdb:");
        exec("CREATE TABLE parsed AS SELECT * FROM (VALUES (1, 150), (2, 50), (3, 200)) t(id, amt)");
    }

    @AfterEach
    void close() throws Exception {
        StepExecutors.deregister(OWNER);
        PipelineNodeTypes.deregister(OWNER);
        conn.close();
    }

    private static void register(String type, Set<String> emits, Duration timeout, Body body) {
        PipelineNodeTypes.register(new Type(type, emits), OWNER);
        StepExecutors.register(new Step(type, timeout, body), OWNER, StepExecutors.Grant.NONE);
    }

    private static void register(String type, Body body) {
        register(type, Set.of(PipelineRel.DATA), StepRunner.DEFAULT_TIMEOUT, body);
    }

    /** Every input row to {@code data}. */
    private static final Body COPY = ctx -> {
        StepInput in = ctx.in();
        StepOutput out = ctx.emit(PipelineRel.DATA);
        while (in.next()) out.copyRow(in);
    };

    // ── armed and dry ────────────────────────────────────────────────────────────────────────

    @Test
    void aStepRunsArmedAndInADryRunAndKnowsWhich(@TempDir Path work) throws Exception {
        register("transform.srt_mode", ctx -> {
            StepInput in = ctx.in();
            StepOutput out = ctx.emit(PipelineRel.DATA,
                    List.of(new TypeFlow.Column("id", "INTEGER"), new TypeFlow.Column("dry", "BOOLEAN")));
            while (in.next()) out.beginRow().append(in.getInt(in.column("id"))).append(ctx.dryRun()).endRow();
        });
        PipelineGraph g = linear("transform.srt_mode", Map.of());

        var dry = PipelineExecutor.dryRun(conn, g, "parse", "parsed");
        assertEquals(List.of("true"), column(dry.produced().get("n").get(PipelineRel.DATA), "dry"));
        exec("DROP TABLE \"" + dry.produced().get("n").get(PipelineRel.DATA) + "\"");   // the preview's scratch

        List<String> written = new ArrayList<>();
        PipelineExecutor.execute(conn, g, "parse", "parsed", "b1", coordinator(work),
                (sink, table) -> written.add(table), () -> {});
        assertEquals(List.of("false"), column(written.get(0), "dry"));
        assertEquals(List.of("1", "2", "3"), column(written.get(0), "id"), "typed values written straight through");
    }

    // ── failure grain ────────────────────────────────────────────────────────────────────────

    @Test
    void aThrowingStepLeavesNoNodeTablesAndFailsTheBatch(@TempDir Path work) throws Exception {
        register("transform.srt_throw", ctx -> {
            StepInput in = ctx.in();
            StepOutput out = ctx.emit(PipelineRel.DATA);
            in.next();
            out.copyRow(in);                       // a half-written node…
            throw new IllegalStateException("boom");
        });
        List<String> written = new ArrayList<>();

        StepFailure f = assertThrows(StepFailure.class, () -> PipelineExecutor.execute(conn,
                linear("transform.srt_throw", Map.of()), "parse", "parsed", "b1", coordinator(work),
                (sink, table) -> written.add(table), () -> {}));

        assertEquals(StepFailure.STEP_FAILED, f.code());
        assertTrue(f.getMessage().contains("boom"), f.getMessage());
        assertEquals(List.of(), nodeTables("n"), "…leaves no table behind");
        assertTrue(written.isEmpty(), "the batch failed before any sink wrote");
    }

    @Test
    void emittingARelationTheNodeTypeDoesNotDeclareFailsTheStep() throws Exception {
        register("transform.srt_undeclared", ctx -> ctx.emit("reject:nope"));

        StepFailure f = assertThrows(StepFailure.class, () -> shape("transform.srt_undeclared", Map.of()));
        assertEquals(StepFailure.STEP_FAILED, f.code());
        assertTrue(f.getMessage().contains("does not declare"), f.getMessage());
    }

    // ── reject streams ───────────────────────────────────────────────────────────────────────

    @Test
    void aRejectEmitIsTaggedAsARejectStreamByConservationCheck(@TempDir Path work) throws Exception {
        String low = PipelineRel.reject("low_amount");
        register("transform.srt_reject", Set.of(PipelineRel.DATA, low), StepRunner.DEFAULT_TIMEOUT, ctx -> {
            StepInput in = ctx.in();
            int amt = in.column("amt");
            while (in.next()) ctx.emit(in.getInt(amt) >= 100 ? PipelineRel.DATA : low).copyRow(in);
        });
        PipelineGraph g = new PipelineGraph("REJ", true,
                List.of(PipelineNode.of("parse", "parser"),
                        PipelineNode.of("n", "transform.srt_reject", Map.of()),
                        PipelineNode.of("ok", "sink.persistent", Map.of(PipelineStores.CONFIG_STORE, "ok")),
                        PipelineNode.of("bad", "sink.persistent", Map.of(PipelineStores.CONFIG_STORE, "bad"))),
                List.of(PipelineEdge.data("parse", "n"), PipelineEdge.data("n", "ok"),
                        new PipelineEdge("n", low, "bad")));
        Map<String, Long> counts = new LinkedHashMap<>();

        PipelineExecutor.execute(conn, g, Map.of("parse", "parsed"), "b1", coordinator(work),
                (sink, table) -> {}, () -> {}, (node, rel, rows) -> counts.put(node + "|" + rel, rows));

        assertEquals(2L, counts.get("n|data"));
        assertEquals(1L, counts.get("n|" + low));
        Map<String, Boolean> diverted = new LinkedHashMap<>();
        for (ConservationCheck.RelCount c : ConservationCheck.relCounts(counts))
            if (c.node().equals("n")) diverted.put(c.rel(), c.diverted());
        assertEquals(Map.of(PipelineRel.DATA, false, low, true), diverted, "reject:<reason> is a reject stream");
    }

    @Test
    void aDeclaredRelationTheStepNeverEmittedIsProducedEmpty() throws Exception {
        String low = PipelineRel.reject("low_amount");
        register("transform.srt_quiet", Set.of(PipelineRel.DATA, low), StepRunner.DEFAULT_TIMEOUT, COPY);

        Map<String, String> rels = new LinkedHashMap<>();
        for (RowShaper.Relation r : shape("transform.srt_quiet", Map.of())) rels.put(r.rel(), r.table());

        assertEquals(Set.of(PipelineRel.DATA, low), rels.keySet());
        assertEquals(3, count(rels.get(PipelineRel.DATA)));
        assertEquals(0, count(rels.get(low)), "a zero count, not a missing branch");
        assertEquals(List.of("id", "amt"), columnNames(rels.get(low)), "with the input's columns");
    }

    // ── watchdog ─────────────────────────────────────────────────────────────────────────────

    @Test
    void aSleepingStepIsKilledAtItsDeadlineWithStepTimeout() throws Exception {
        register("transform.srt_sleep", ctx -> {
            StepInput in = ctx.in();
            in.next();
            ctx.emit(PipelineRel.DATA).copyRow(in);
            Thread.sleep(60_000);
        });

        long t0 = System.nanoTime();
        StepFailure f = assertTimeoutPreemptively(Duration.ofSeconds(15), () -> assertThrows(StepFailure.class,
                () -> shape("transform.srt_sleep", Map.of("timeout_seconds", 0.3))));
        long ms = (System.nanoTime() - t0) / 1_000_000;

        assertEquals(StepFailure.STEP_TIMEOUT, f.code());
        assertTrue(ms >= 250 && ms < 5_000, "killed at its 0.3 s deadline, took " + ms + " ms");
        assertEquals(List.of(), nodeTables("n"), "a timed-out Step leaves no tables either");
        assertTrue(StepExecutors.disabledReason("transform.srt_sleep").isEmpty(),
                "it honoured the interrupt, so its kind stays enabled");
    }

    @Test
    void aStepThatIgnoresTheInterruptIsAbandonedAndItsKindDisabledUntilThePackIsReplaced() throws Exception {
        AtomicBoolean release = new AtomicBoolean();
        register("transform.srt_spin", ctx -> {
            while (!release.get()) Thread.onSpinWait();   // ignores interrupts: this JVM cannot kill it
        });
        try {
            StepFailure f = assertTimeoutPreemptively(Duration.ofSeconds(15), () -> assertThrows(StepFailure.class,
                    () -> shape("transform.srt_spin", Map.of("timeout_seconds", 0.2))));
            assertEquals(StepFailure.STEP_TIMEOUT, f.code());
            assertTrue(f.getMessage().contains("abandoned"), f.getMessage());
            assertTrue(StepExecutors.disabledReason("transform.srt_spin").isPresent(), "D-7: disable-on-abandon");

            StepFailure again = assertThrows(StepFailure.class, () -> shape("transform.srt_spin", Map.of()));
            assertEquals(StepFailure.STEP_DISABLED, again.code(), "a disabled kind refuses at once");

            StepExecutors.deregister(OWNER);   // the pack is replaced (or removed)
            assertTrue(StepExecutors.disabledReason("transform.srt_spin").isEmpty(), "replacing the pack re-enables");
        } finally {
            release.set(true);
        }
    }

    @Test
    void theDeadlineIsTheKindsDefaultOverriddenByTheNodeAndCappedByTheCeiling() {
        Step step = new Step("transform.srt_any", Duration.ofSeconds(42), COPY);

        assertEquals(Duration.ofSeconds(42), StepRunner.deadline(step, PipelineNode.of("n", "transform.srt_any")));
        assertEquals(Duration.ofMillis(1500), StepRunner.deadline(step,
                PipelineNode.of("n", "transform.srt_any", Map.of("timeout_seconds", "1.5"))));
        assertEquals(Duration.ofMinutes(30), StepRunner.deadline(step,
                PipelineNode.of("n", "transform.srt_any", Map.of("timeout_seconds", 99_999))), "the 30 min ceiling");
        assertEquals(Duration.ofMinutes(5), StepRunner.deadline(new Step("t", null, COPY),
                PipelineNode.of("n", "t")), "the 5 min default");
        assertThrows(IllegalArgumentException.class, () -> StepRunner.deadline(step,
                PipelineNode.of("n", "transform.srt_any", Map.of("timeout_seconds", 0))));
    }

    // ── the context ──────────────────────────────────────────────────────────────────────────

    @Test
    void theContextIsClosedOnceTheStepReturns() throws Exception {
        StepInput[] leaked = new StepInput[1];
        register("transform.srt_leak", ctx -> {
            leaked[0] = ctx.in();
            COPY.run(ctx);
        });
        shape("transform.srt_leak", Map.of());

        assertThrows(IllegalStateException.class, () -> leaked[0].next(), "a Step cannot read after it returned");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private List<RowShaper.Relation> shape(String type, Map<String, Object> config) throws Exception {
        return RowShaper.shape(conn, PipelineNode.of("n", type, config), "parsed", "n");
    }

    private static PipelineGraph linear(String type, Map<String, Object> config) {
        return new PipelineGraph("STEP", true,
                List.of(PipelineNode.of("parse", "parser"), PipelineNode.of("n", type, config),
                        PipelineNode.of("sink", "sink.persistent", Map.of(PipelineStores.CONFIG_STORE, "out"))),
                List.of(PipelineEdge.data("parse", "n"), PipelineEdge.data("n", "sink")));
    }

    private static BranchCommitCoordinator coordinator(Path work) {
        return new BranchCommitCoordinator(new BranchCommitLog(work.resolve("commit.csv").toString()));
    }

    private void exec(String sql) throws Exception {
        try (Statement st = conn.createStatement()) { st.execute(sql); }
    }

    private List<String> nodeTables(String prefix) throws Exception {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_name LIKE '"
                     + prefix + "\\_\\_%' ESCAPE '\\'")) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private List<String> column(String table, String column) throws Exception {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT DISTINCT CAST(\"" + column + "\" AS VARCHAR) v FROM \"" + table
                     + "\" ORDER BY v")) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private List<String> columnNames(String table) throws Exception {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM \"" + table + "\" LIMIT 0")) {
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) out.add(rs.getMetaData().getColumnLabel(i));
        }
        return out;
    }

    private int count(String table) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM \"" + table + "\"")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
