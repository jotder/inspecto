package com.gamma.job;

import com.gamma.consignment.ConsignmentOutput;
import com.gamma.consignment.DerivedTable;
import com.gamma.consignment.DerivedTableWriter;
import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.consignment.ConsignmentProcessor;
import com.gamma.consignment.DbConsignmentOutputStore;
import com.gamma.consignment.Measure;
import com.gamma.consignment.ProcessorContext;
import com.gamma.consignment.ProcessorResult;
import com.gamma.consignment.SummaryEmitter;
import com.gamma.consignment.SummaryRow;
import com.gamma.consignment.SummaryWriter;
import com.gamma.signal.Severity;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
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

    // The JobContext stub lives in com.gamma.job beside JobContext itself — one double, so a Job's
    // reporting behaviour is asserted the same way everywhere.

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

    /** A job whose lookup knows several processors, so a chain can resolve every step. */
    private static Job chainJob(String dataDir, ConsignmentProcessor... all) {
        Map<String, ConsignmentProcessor> byId = new java.util.LinkedHashMap<>();
        for (ConsignmentProcessor p : all) byId.put(p.id(), p);
        return new ConsignmentProcessJobType(byId::get, dataDir)
                .create(new JobConfig("chain_job", ConsignmentProcessJobType.TYPE_ID, null, null,
                        true, false, Map.of(), null, null));
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
            CapturingJobContext ctx = new CapturingJobContext(params("c1", "row-counter"), false);
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

            CapturingJobContext ctx = new CapturingJobContext(params("c1", "row-counter"), false);
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

            JobResult result = job(new RowCounter()).run(new CapturingJobContext(params("c1", "row-counter"), true));
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

            CapturingJobContext ctx = new CapturingJobContext(params("c1", "row-counter"), false);
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
            JobResult result = job(new RowCounter()).run(new CapturingJobContext(params("c1", "nope"), false));

            assertEquals("FAILED", result.status());
            assertTrue(result.message().contains("nope"), result.message());
            assertTrue(result.message().contains("META-INF/services"), result.message());
        }
    }

    @Test
    void failsClearlyWhenNoConsignmentIdResolved() throws Exception {
        JobResult result = job(new RowCounter()).run(new CapturingJobContext(params(null, "row-counter"), false));
        assertEquals("FAILED", result.status());
        assertTrue(result.message().contains("$signal.batchId"), result.message());
    }

    /** Default-off registry: the run still completes, and says why there was nothing to read. */
    @Test
    void warnsAndStillRunsWhenTheRegistryIsDisabled() throws Exception {
        assertNull(ConsignmentOutputStores.shared(), "precondition: no registry installed");

        RowCounter processor = new RowCounter();
        CapturingJobContext ctx = new CapturingJobContext(params("c1", "row-counter"), false);
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
                    .run(new CapturingJobContext(params("c1", "daily-counter"), false));
            assertTrue(result.success(), result.message());

            Path expected = data.resolve("_summaries").resolve("cdr").resolve("record_day=2026-07-01")
                    .resolve("c1_summary_out.parquet");
            assertTrue(Files.exists(expected), "expected a summary file at " + expected);

            List<ConsignmentOutput> summaries = store.outputs("c1").stream()
                    .filter(o -> "cdr__summary".equals(o.tableName())).toList();
            assertEquals(1, summaries.size(), "the summary file is registered (§11.3)");
            assertEquals(1L, summaries.get(0).rows(), "one summary row was written");
            assertEquals("2026-07-01", summaries.get(0).recordDay());
            // the PROCESSOR is the producer, not this Job Type: two processors can summarise one target,
            // and producerHighWater groups by producer. This is the end-to-end proof that the id the run
            // resolved is the id that reaches the row — SummaryWriter's own test only proves it carries one.
            assertEquals("daily-counter", summaries.get(0).producer(), "producer is the processor id");
        }
    }

    // ── derived tables: a step creating a new table from the base, per Consignment ──

    /** A processor that derives a table from its own Consignment's relation. */
    private static final class Deriver implements ConsignmentProcessor {
        @Override public String id() { return "deriver"; }

        @Override
        public ProcessorResult process(ProcessorContext ctx) {
            // The relation is the Consignment's own, resolved from the registry — the author names no path.
            ctx.tables().emit(new DerivedTable("high_ids", "SELECT id FROM cdr WHERE id >= 1"));
            return ProcessorResult.ok("derived");
        }
    }

    /**
     * 🔴 The end-to-end proof of the chain: what a step derives is written AND registered onto the SAME
     * Consignment, which is exactly what the next step's {@code outputs()} reads.
     */
    @Test
    void persistsDerivedTablesAndRegistersThemOnTheSameConsignment(@TempDir Path dir) throws Exception {
        Path f = writeParquet(dir.resolve("detail"), 3);
        Path data = dir.resolve("data");
        try (DbConsignmentOutputStore store = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            store.record(List.of(out(f, 3)));
            ConsignmentOutputStores.use(store);

            JobResult result = job(new Deriver(), data.toString())
                    .run(new CapturingJobContext(params("c1", "deriver"), false));
            assertTrue(result.success(), result.message());

            List<ConsignmentOutput> derived = store.outputs("c1").stream()
                    .filter(o -> o.tableName().endsWith(DerivedTableWriter.DERIVED_SUFFIX)).toList();
            assertEquals(1, derived.size(), "the derived file is registered on this Consignment");
            ConsignmentOutput o = derived.get(0);
            assertEquals("high_ids__derived", o.tableName());
            assertEquals("deriver", o.producer(), "the PROCESSOR is the producer, so the row is attributable");
            assertEquals(2, o.rows(), "ids 1,2 of 0,1,2");
            assertEquals(ConsignmentOutput.State.LIVE, o.state());
            assertTrue(Files.exists(Path.of(o.path())), "the file exists: " + o.path());
            assertNotNull(o.schemaFingerprint(), "the schema is derived, not authored");
        }
    }

    /** A reprocess supersedes the derivative WITH its base — supersede is keyed on the Consignment. */
    @Test
    void aReprocessSupersedesDerivedTablesWithTheirBase(@TempDir Path dir) throws Exception {
        Path f = writeParquet(dir.resolve("detail"), 3);
        Path data = dir.resolve("data");
        try (DbConsignmentOutputStore store = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            store.record(List.of(out(f, 3)));
            ConsignmentOutputStores.use(store);
            assertTrue(job(new Deriver(), data.toString())
                    .run(new CapturingJobContext(params("c1", "deriver"), false)).success());

            store.supersede("c1");

            assertTrue(store.outputs("c1").stream().allMatch(o -> o.state() == ConsignmentOutput.State.SUPERSEDED),
                    "base AND derivative are both superseded — no lineage edge needed");
        }
    }

    // ── stage 3: an ordered chain over one Consignment ───────────────────────────

    /** Step 1 of a chain: derives a table the NEXT step must be able to see. */
    private static final class FirstStep implements ConsignmentProcessor {
        @Override public String id() { return "first"; }

        @Override
        public ProcessorResult process(ProcessorContext ctx) {
            ctx.tables().emit(new DerivedTable("mid", "SELECT id FROM cdr WHERE id >= 1"));
            return ProcessorResult.ok("first");
        }
    }

    /**
     * Step 2: reads what step 1 registered. It asserts INSIDE the processor, because the whole point is
     * what the step could see at the moment it ran — a check afterwards would prove nothing about that.
     */
    private static final class SecondStep implements ConsignmentProcessor {
        static volatile List<String> sawRelations = List.of();
        static volatile long sawRows = -1;

        @Override public String id() { return "second"; }

        @Override
        public ProcessorResult process(ProcessorContext ctx) throws Exception {
            sawRelations = ctx.read().relations();
            // the previous step's table is readable, by name, through the ordinary read seam
            sawRows = (long) ctx.read().query("SELECT count(*) AS n FROM mid__derived").get(0).get("n");
            ctx.tables().emit(new DerivedTable("final_t", "SELECT * FROM mid__derived"));
            return ProcessorResult.ok("second");
        }
    }

    /**
     * 🔴 The chain property: step 2 sees the Consignment as step 1 LEFT it. If the registry were read once
     * outside the loop, step 2 would get the pre-chain view and the ordering would be meaningless.
     */
    @Test
    void aLaterStepSeesWhatTheEarlierStepRegistered(@TempDir Path dir) throws Exception {
        Path f = writeParquet(dir.resolve("detail"), 3);
        Path data = dir.resolve("data");
        try (DbConsignmentOutputStore store = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            store.record(List.of(out(f, 3)));
            ConsignmentOutputStores.use(store);
            SecondStep.sawRelations = List.of();
            SecondStep.sawRows = -1;

            JobResult result = chainJob(data.toString(), new FirstStep(), new SecondStep())
                    .run(new CapturingJobContext(params("c1", "first,second"), false));
            assertTrue(result.success(), result.message());

            assertTrue(SecondStep.sawRelations.contains("mid__derived"),
                    "step 2 should see step 1's table as a relation, saw: " + SecondStep.sawRelations);
            assertEquals(2L, SecondStep.sawRows, "and read its rows");

            // both steps' outputs are registered, each attributed to the step that made it
            List<ConsignmentOutput> derived = store.outputs("c1").stream()
                    .filter(o -> o.tableName().endsWith(DerivedTableWriter.DERIVED_SUFFIX)).toList();
            assertEquals(2, derived.size(), "both steps registered a table");
            assertEquals(List.of("first", "second"),
                    derived.stream().map(ConsignmentOutput::producer).sorted().toList());
            assertTrue(result.message().contains("first"), result.message());
        }
    }

    /**
     * ⚠ An unresolvable id stops the run BEFORE anything executes. A chain that failed half-way would have
     * already written and registered the earlier steps' tables, and nothing rolls those back.
     */
    @Test
    void anUnknownStepStopsTheChainBeforeAnythingRuns(@TempDir Path dir) throws Exception {
        Path f = writeParquet(dir.resolve("detail"), 3);
        Path data = dir.resolve("data");
        try (DbConsignmentOutputStore store = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            store.record(List.of(out(f, 3)));
            ConsignmentOutputStores.use(store);

            JobResult result = chainJob(data.toString(), new FirstStep())
                    .run(new CapturingJobContext(params("c1", "first,nope"), false));

            assertFalse(result.success());
            assertTrue(result.message().contains("nothing has run"), result.message());
            assertTrue(store.outputs("c1").stream().noneMatch(
                    o -> o.tableName().endsWith(DerivedTableWriter.DERIVED_SUFFIX)),
                    "the resolvable first step must NOT have written");
        }
    }

    /** Order is authored, never inferred — and a repeated id is kept, not de-duplicated. */
    @Test
    void theChainIsParsedInAuthoredOrder() {
        assertEquals(List.of("a"), ConsignmentProcessJobType.chainOf("a"));
        assertEquals(List.of("a", "b", "c"), ConsignmentProcessJobType.chainOf(" a , b ,c "));
        assertEquals(List.of("a", "a"), ConsignmentProcessJobType.chainOf("a,,a"));
        assertEquals(List.of(), ConsignmentProcessJobType.chainOf("  "));
    }

    /** Dry run must validate and report but never write — the derived tier follows the summary tier's rule. */
    @Test
    void writesNoDerivedFilesOnADryRun(@TempDir Path dir) throws Exception {
        Path f = writeParquet(dir.resolve("detail"), 3);
        Path data = dir.resolve("data");
        try (DbConsignmentOutputStore store = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            store.record(List.of(out(f, 3)));
            ConsignmentOutputStores.use(store);

            assertTrue(job(new Deriver(), data.toString())
                    .run(new CapturingJobContext(params("c1", "deriver"), true)).success());

            assertFalse(Files.exists(data.resolve("_derived")), "a dry run that leaves files is not a dry run");
            assertTrue(store.outputs("c1").stream().noneMatch(
                    o -> o.tableName().endsWith(DerivedTableWriter.DERIVED_SUFFIX)));
        }
    }

    /** Dry run must validate and report but never write — a dry run that leaves files is not a dry run. */
    @Test
    void writesNoSummaryFilesOnADryRun(@TempDir Path dir) throws Exception {
        Path data = dir.resolve("data");
        try (DbConsignmentOutputStore store = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            ConsignmentOutputStores.use(store);

            job(new DailyCounter(), data.toString())
                    .run(new CapturingJobContext(params("c1", "daily-counter"), true));

            assertFalse(Files.exists(data.resolve("_summaries")), "nothing may be written on a dry run");
            assertTrue(store.outputs("c1").isEmpty(), "and nothing registered");
        }
    }

    /** No data root ⇒ §7.3 storage is off. The guardrail still ran, so say so rather than failing silently. */
    @Test
    void warnsWhenSummariesWereGuardedButCannotBeStored() throws Exception {
        try (DbConsignmentOutputStore store = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            ConsignmentOutputStores.use(store);

            CapturingJobContext ctx = new CapturingJobContext(params("c1", "daily-counter"), false);
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
