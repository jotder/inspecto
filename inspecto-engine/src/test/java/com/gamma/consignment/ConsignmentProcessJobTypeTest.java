package com.gamma.consignment;

import com.gamma.job.ArtifactRecorder;
import com.gamma.job.Job;
import com.gamma.job.JobConfig;
import com.gamma.job.JobContext;
import com.gamma.job.JobResult;
import com.gamma.job.RunLog;
import com.gamma.job.TriggerInfo;
import com.gamma.signal.Severity;
import com.gamma.signal.SignalEmitter;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §14.4 step 3 — the adapter. Proves the "author doesn't think about it" promise from the <em>processor's</em>
 * side: a {@link ConsignmentProcessor} that mentions nothing about Jobs, Signals or parameters receives a
 * context already narrowed to one Consignment, and everything it emits is stamped with that Consignment.
 *
 * <p>The signal→parameter half of the criterion is proved against the real resolver in
 * {@code com.gamma.job.ConsignmentProcessParameterTest}; here the resolved parameters are the starting point.
 */
class ConsignmentProcessJobTypeTest {

    @AfterEach
    void clearRegistry() {
        ConsignmentOutputStores.use(null);
    }

    // ── the reference processor (§14.4 step 3's "with a reference processor") ────

    /**
     * Deliberately minimal and Job-agnostic: it names no Job type, no Signal and no parameter. Everything it
     * needs arrives on the context.
     */
    private static final class RowCounter implements ConsignmentProcessor {
        ProcessorContext seen;

        @Override public String id() { return "row-counter"; }

        @Override
        public ProcessorResult process(ProcessorContext ctx) throws Exception {
            seen = ctx;
            if (ctx.dryRun()) return ProcessorResult.skipped("dry run — counted nothing");

            long total = 0;
            for (String relation : ctx.read().relations()) {
                long n = ((Number) ctx.read().query("SELECT COUNT(*) AS n FROM \"" + relation + "\"")
                        .get(0).get("n")).longValue();
                total += n;
                ctx.summaries().emit(new SummaryRow(relation, Map.of(),
                        List.of(Measure.additive(SummaryEmitter.COUNT, n))));
            }
            ctx.log().info("counted", "rows", total);
            ctx.signals().emit("consignment.counted", Severity.INFO, Map.of("rows", total));
            return ProcessorResult.ok(total + " row(s) counted");
        }
    }

    // ── a JobContext stub: only what the adapter actually reads ──────────────────

    private static final class FakeJobContext implements JobContext {
        final Map<String, String> params;
        final boolean dryRun;
        final List<String> warnings = new ArrayList<>();
        final List<Map<String, Object>> signals = new ArrayList<>();

        FakeJobContext(Map<String, String> params, boolean dryRun) {
            this.params = params;
            this.dryRun = dryRun;
        }

        @Override public String runId() { return "run-1"; }
        @Override public String spaceId() { return "default"; }
        @Override public TriggerInfo trigger() { return null; }
        @Override public Map<String, String> config() { return Map.of(); }
        @Override public Map<String, String> params() { return params; }
        @Override public ArtifactRecorder artifacts() { return null; }
        @Override public boolean dryRun() { return dryRun; }

        @Override
        public RunLog log() {
            return new RunLog() {
                @Override public void info(String message, Object... kv) { }
                @Override public void warn(String message, Object... kv) { warnings.add(message); }
                @Override public void error(String message, Throwable t, Object... kv) { }
            };
        }

        @Override
        public SignalEmitter signals() {
            return (type, severity, payload) -> {
                Map<String, Object> got = new LinkedHashMap<>(payload);
                got.put("__type", type);
                signals.add(got);
            };
        }
    }

    // ── harness ──────────────────────────────────────────────────────────────────

    private static Path writeParquet(Path root, int n) throws Exception {
        Path dir = root.resolve("year=2026/month=07/day=01");
        Files.createDirectories(dir);
        Path file = dir.resolve("b1_out.parquet");
        File db = DuckDbUtil.tempDbFile("cpj_seed_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT i AS id FROM range(" + n + ") t(i)) TO '"
                    + file.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
        return file;
    }

    private static ConsignmentOutput out(Path path, long rows) {
        return new ConsignmentOutput("c1", null, "cdr", "year=2026/month=07/day=01", "2026-07-01",
                path.toString(), rows, 100L, "2026-08-04T10:00:00Z", 0, ConsignmentOutput.State.LIVE);
    }

    private static Job job(ConsignmentProcessor processor) {
        return job(processor, null);
    }

    private static Job job(ConsignmentProcessor processor, String dataDir) {
        AtomicReference<ConsignmentProcessor> ref = new AtomicReference<>(processor);
        return new ConsignmentProcessJobType(
                id -> (processor != null && id.equals(processor.id())) ? ref.get() : null, dataDir)
                .create(new JobConfig("count_job", ConsignmentProcessJobType.TYPE_ID, null, null,
                        true, false, Map.of(), null, null));
    }

    private static Map<String, String> params(String consignmentId, String processorId) {
        Map<String, String> p = new LinkedHashMap<>();
        if (consignmentId != null) p.put("consignment_id", consignmentId);
        if (processorId != null) p.put("processor", processorId);
        return p;
    }

    // ── the run ──────────────────────────────────────────────────────────────────

    @Test
    void handsTheProcessorAContextScopedToTheConsignment(@TempDir Path dir) throws Exception {
        Path f = writeParquet(dir, 7);
        try (DbConsignmentOutputStore store = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            store.record(List.of(out(f, 7)));
            ConsignmentOutputStores.use(store);

            RowCounter processor = new RowCounter();
            FakeJobContext ctx = new FakeJobContext(params("c1", "row-counter"), false);
            JobResult result = job(processor).run(ctx);

            assertTrue(result.success(), result.message());
            assertTrue(result.message().contains("7 row(s)"), result.message());

            assertNotNull(processor.seen, "the processor ran");
            assertEquals("c1", processor.seen.consignmentId(), "resolved by the framework, not the author");
            assertEquals(1, processor.seen.outputs().size(), "outputs come from the §11.3 registry");
            assertEquals(7L, processor.seen.outputs().get(0).rows());
            assertEquals(List.of("cdr"), processor.seen.read().relations());
        }
    }

    /** The stamping promise: the processor emitted no consignment id, and the Signal carries one anyway. */
    @Test
    void stampsTheConsignmentIdIntoEverySignal(@TempDir Path dir) throws Exception {
        Path f = writeParquet(dir, 3);
        try (DbConsignmentOutputStore store = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            store.record(List.of(out(f, 3)));
            ConsignmentOutputStores.use(store);

            FakeJobContext ctx = new FakeJobContext(params("c1", "row-counter"), false);
            job(new RowCounter()).run(ctx);

            assertEquals(1, ctx.signals.size());
            assertEquals("consignment.counted", ctx.signals.get(0).get("__type"));
            assertEquals("c1", ctx.signals.get(0).get("consignment_id"),
                    "stamped by the adapter — the processor never set it");
            assertEquals(3L, ((Number) ctx.signals.get(0).get("rows")).longValue(),
                    "the author's own payload survives alongside the stamp");
        }
    }

    @Test
    void delegatesDryRunSoAProcessorCanRefuseToAct(@TempDir Path dir) throws Exception {
        Path f = writeParquet(dir, 3);
        try (DbConsignmentOutputStore store = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            store.record(List.of(out(f, 3)));
            ConsignmentOutputStores.use(store);

            JobResult result = job(new RowCounter()).run(new FakeJobContext(params("c1", "row-counter"), true));
            assertEquals("SKIPPED", result.status());
            assertTrue(result.message().contains("dry run"), result.message());
        }
    }

    /** §7.2's free reconciliation is reported, not thrown — a filtered summary is legal. */
    @Test
    void warnsWhenSummariesDoNotReconcileAgainstDetail(@TempDir Path dir) throws Exception {
        Path f = writeParquet(dir, 3);
        try (DbConsignmentOutputStore store = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            store.record(List.of(out(f, 999)));   // registry claims 999 detail rows; the file holds 3
            ConsignmentOutputStores.use(store);

            FakeJobContext ctx = new FakeJobContext(params("c1", "row-counter"), false);
            JobResult result = job(new RowCounter()).run(ctx);

            assertTrue(result.success(), "a mismatch is a signal for the operator, not a failure");
            assertTrue(ctx.warnings.stream().anyMatch(w -> w.contains("reconcile")),
                    "expected a reconciliation warning, got " + ctx.warnings);
        }
    }

    // ── the failure modes, each with an actionable message ───────────────────────

    @Test
    void failsClearlyWhenNoProcessorIsRegisteredWithThatId(@TempDir Path dir) throws Exception {
        try (DbConsignmentOutputStore store = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            ConsignmentOutputStores.use(store);
            JobResult result = job(new RowCounter()).run(new FakeJobContext(params("c1", "nope"), false));

            assertEquals("FAILED", result.status());
            assertTrue(result.message().contains("nope"), result.message());
            assertTrue(result.message().contains("META-INF/services"), result.message());
        }
    }

    @Test
    void failsClearlyWhenNoConsignmentIdResolved() throws Exception {
        JobResult result = job(new RowCounter()).run(new FakeJobContext(params(null, "row-counter"), false));
        assertEquals("FAILED", result.status());
        assertTrue(result.message().contains("$signal.batchId"), result.message());
    }

    /** Default-off registry: the run still completes, and says why there was nothing to read. */
    @Test
    void warnsAndStillRunsWhenTheRegistryIsDisabled() throws Exception {
        assertNull(ConsignmentOutputStores.shared(), "precondition: no registry installed");

        RowCounter processor = new RowCounter();
        FakeJobContext ctx = new FakeJobContext(params("c1", "row-counter"), false);
        JobResult result = job(processor).run(ctx);

        assertTrue(result.success(), result.message());
        assertEquals(List.of(), processor.seen.read().relations(), "nothing readable without the registry");
        assertTrue(ctx.warnings.stream().anyMatch(w -> w.contains("registry is disabled")),
                "expected a disabled-registry warning, got " + ctx.warnings);
    }

    // ── §7.3: the emitted summaries actually land ────────────────────────────────

    /** A processor that summarises at a record-day grain, so the §7.3 partitioned path is exercised. */
    private static final class DailyCounter implements ConsignmentProcessor {
        @Override public String id() { return "daily-counter"; }

        @Override
        public ProcessorResult process(ProcessorContext ctx) {
            ctx.summaries().emit(new SummaryRow("cdr", Map.of(SummaryWriter.RECORD_DAY, "2026-07-01"),
                    List.of(Measure.additive(SummaryEmitter.COUNT, 3))));
            return ProcessorResult.ok("summarised");
        }
    }

    @Test
    void persistsEmittedSummariesAndRegistersThem(@TempDir Path dir) throws Exception {
        Path f = writeParquet(dir.resolve("detail"), 3);
        Path data = dir.resolve("data");
        try (DbConsignmentOutputStore store = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            store.record(List.of(out(f, 3)));
            ConsignmentOutputStores.use(store);

            JobResult result = job(new DailyCounter(), data.toString())
                    .run(new FakeJobContext(params("c1", "daily-counter"), false));
            assertTrue(result.success(), result.message());

            Path expected = data.resolve("_summaries").resolve("cdr").resolve("record_day=2026-07-01")
                    .resolve("c1_summary_out.parquet");
            assertTrue(Files.exists(expected), "expected a summary file at " + expected);

            List<ConsignmentOutput> summaries = store.outputs("c1").stream()
                    .filter(o -> "cdr__summary".equals(o.tableName())).toList();
            assertEquals(1, summaries.size(), "the summary file is registered (§11.3)");
            assertEquals(1L, summaries.get(0).rows(), "one summary row was written");
            assertEquals("2026-07-01", summaries.get(0).recordDay());
        }
    }

    /** Dry run must validate and report but never write — a dry run that leaves files is not a dry run. */
    @Test
    void writesNoSummaryFilesOnADryRun(@TempDir Path dir) throws Exception {
        Path data = dir.resolve("data");
        try (DbConsignmentOutputStore store = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            ConsignmentOutputStores.use(store);

            job(new DailyCounter(), data.toString())
                    .run(new FakeJobContext(params("c1", "daily-counter"), true));

            assertFalse(Files.exists(data.resolve("_summaries")), "nothing may be written on a dry run");
            assertTrue(store.outputs("c1").isEmpty(), "and nothing registered");
        }
    }

    /** No data root ⇒ §7.3 storage is off. The guardrail still ran, so say so rather than failing silently. */
    @Test
    void warnsWhenSummariesWereGuardedButCannotBeStored() throws Exception {
        try (DbConsignmentOutputStore store = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            ConsignmentOutputStores.use(store);

            FakeJobContext ctx = new FakeJobContext(params("c1", "daily-counter"), false);
            JobResult result = job(new DailyCounter(), null).run(ctx);

            assertTrue(result.success(), result.message());
            assertTrue(ctx.warnings.stream().anyMatch(w -> w.contains("not stored")),
                    "expected a not-stored warning, got " + ctx.warnings);
        }
    }

    /** The legacy no-arg entry point cannot carry parameters, so it must refuse rather than half-run. */
    @Test
    void refusesTheNoArgEntryPoint() throws Exception {
        JobResult result = job(new RowCounter()).run();
        assertEquals("FAILED", result.status());
        assertTrue(result.message().contains("requires a JobContext"), result.message());
    }
}
