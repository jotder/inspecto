package com.gamma.job;

import com.gamma.event.Event;
import com.gamma.event.EventLevel;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.pipeline.PipelineEdge;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.PipelineRel;
import com.gamma.pipeline.PipelineStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.pipeline.exec.DbProvenanceStore;
import com.gamma.pipeline.exec.PipelineExecutor;
import com.gamma.pipeline.exec.ProvenanceRow;
import com.gamma.etl.BatchEventBus;
import com.gamma.sql.SqlViews;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.RunLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T32 Phase A — {@link PipelineJobRunner} runs an authored flow for real over embedded DuckDB: it seeds a
 * {@code source_store} from a small on-disk Parquet dataset, executes the {@code transform → sink}
 * subgraph via the production {@link PipelineExecutor}, and writes each sink {@code store}. Covers the single
 * filter→sink path, idempotent re-run (same batch id skips the committed branch), a multi-branch route to
 * two stores, and a multi-{@code source_store} union (T32 Phase C).
 */
class PipelineJobRunnerTest {

    @TempDir Path tmp;

    @Test
    void runsFlowFromSourceStoreAndWritesSink() throws Exception {
        String dataDir = tmp.resolve("data").toString();
        String auditDir = tmp.resolve("audit").toString();
        seedParquet(dataDir, "events", "(1,150),(2,50),(3,200)");
        PipelineStore store = new PipelineStore(tmp.resolve("flows"));
        store.write("evt_rollup", new PipelineGraph("evt_rollup", true,
                List.of(PipelineNode.of("src", "acquisition", Map.of("source_store", "events")),
                        PipelineNode.of("flt", "transform.filter", Map.of("where", "amt >= 100")),
                        new PipelineNode("out", "sink.persistent", "Rollup", null, Map.of("store", "rollup"), null)),
                List.of(PipelineEdge.data("src", "flt"), PipelineEdge.data("flt", "out"))));

        JobConfig cfg = new JobConfig("nightly", JobType.PIPELINE, null, null, true, false,
                Map.of("flow", "evt_rollup", "data_dir", dataDir));
        JobResult res = new PipelineJobRunner(cfg, new BatchEventBus(), store, dataDir, auditDir).run();

        assertTrue(res.success(), res.message());
        assertEquals(List.of(1, 3), readIds(dataDir, "rollup"), "amt>=100 keeps id1(150) + id3(200), drops id2(50)");
    }

    /**
     * §5-B — this runner recorded no Run Artifact at all until 2026-08-10, so
     * {@code $upstream(<pipelineJob>).artifact(...)} could never resolve. One per store it wrote bytes to,
     * {@code ref}'d by the store name, because that string is also the Consignment registry's key.
     */
    @Test
    void recordsOneRunArtifactPerStoreItWroteRefdByTheStoreName() throws Exception {
        String dataDir = tmp.resolve("data").toString();
        String auditDir = tmp.resolve("audit").toString();
        seedParquet(dataDir, "events", "(1,150),(2,50),(3,200)");
        PipelineStore store = new PipelineStore(tmp.resolve("flows"));
        store.write("evt_split", new PipelineGraph("evt_split", true,
                List.of(PipelineNode.of("src", "acquisition", Map.of("source_store", "events")),
                        PipelineNode.of("big", "transform.filter", Map.of("where", "amt >= 100")),
                        PipelineNode.of("small", "transform.filter", Map.of("where", "amt < 100")),
                        new PipelineNode("hi", "sink.persistent", "High", null, Map.of("store", "high"), null),
                        new PipelineNode("lo", "sink.persistent", "Low", null, Map.of("store", "low"), null)),
                List.of(PipelineEdge.data("src", "big"), PipelineEdge.data("big", "hi"),
                        PipelineEdge.data("src", "small"), PipelineEdge.data("small", "lo"))));

        JobConfig cfg = new JobConfig("nightly", JobType.PIPELINE, null, null, true, false,
                Map.of("pipeline", "evt_split", "data_dir", dataDir));
        RecordingContext ctx = new RecordingContext();
        JobResult res = new PipelineJobRunner(cfg, new BatchEventBus(), store, dataDir, auditDir).run(ctx);

        assertTrue(res.success(), res.message());
        assertEquals(Map.of("high", 2L, "low", 1L), ctx.rows,
                "one artifact per store, each reporting the rows THAT store received — not the run total");
        assertEquals(ctx.rows.keySet(), ctx.refs.keySet());
        assertEquals("high", ctx.refs.get("high"),
                "ref must be the store name — it is the Consignment registry's table_name, which is what "
                        + "the event-time attrs key off");
    }

    /** The no-arg {@code run()} keeps working and records nothing — every other test in this file uses it. */
    @Test
    void theLegacyNoArgRunRecordsNoArtifacts() throws Exception {
        String dataDir = tmp.resolve("data").toString();
        seedParquet(dataDir, "events", "(1,150)");
        PipelineStore store = new PipelineStore(tmp.resolve("flows"));
        store.write("evt", new PipelineGraph("evt", true,
                List.of(PipelineNode.of("src", "acquisition", Map.of("source_store", "events")),
                        new PipelineNode("out", "sink.persistent", "Out", null, Map.of("store", "rollup"), null)),
                List.of(PipelineEdge.data("src", "out"))));
        JobConfig cfg = new JobConfig("nightly", JobType.PIPELINE, null, null, true, false,
                Map.of("pipeline", "evt", "data_dir", dataDir));

        assertTrue(new PipelineJobRunner(cfg, new BatchEventBus(), store, dataDir,
                tmp.resolve("audit").toString()).run().success());
    }

    /** A {@link JobContext} that only captures what the runner records — the rest is never touched here. */
    private static final class RecordingContext implements JobContext {
        private final Map<String, Long> rows = new java.util.LinkedHashMap<>();
        private final Map<String, String> refs = new java.util.LinkedHashMap<>();

        @Override public ArtifactRecorder artifacts() {
            return new ArtifactRecorder() {
                @Override public void dataset(String name, String ref, ResultSetMeta meta, long r,
                                              java.time.Instant watermark) {
                    rows.put(name, r);
                    refs.put(name, ref);
                }
                @Override public void file(String name, Path path, long bytes) {
                    throw new AssertionError("a pipeline sink is a dataset, not a file artifact");
                }
            };
        }

        @Override public String runId() { return "run-1"; }
        @Override public String spaceId() { return "default"; }
        @Override public TriggerInfo trigger() { return null; }
        @Override public Map<String, String> config() { return Map.of(); }
        @Override public Map<String, String> params() { return Map.of(); }
        @Override public RunLog log() { return null; }
        @Override public com.gamma.signal.SignalEmitter signals() { return null; }
    }

    @Test
    void runsFlowWithTheCanonicalPipelineKeyNotJustTheLegacyFlowKey() throws Exception {
        // Tier 3 dual-read (vocabulary plan §4): `pipeline:` is the canonical *_job.toon key going
        // forward; every other test in this file still uses the pre-rename `flow:` key deliberately, to
        // keep proving that read path too.
        String dataDir = tmp.resolve("data").toString();
        String auditDir = tmp.resolve("audit").toString();
        seedParquet(dataDir, "events", "(1,150),(2,50)");
        PipelineStore store = new PipelineStore(tmp.resolve("flows"));
        store.write("evt_rollup", new PipelineGraph("evt_rollup", true,
                List.of(PipelineNode.of("src", "acquisition", Map.of("source_store", "events")),
                        new PipelineNode("out", "sink.persistent", "Rollup", null, Map.of("store", "rollup"), null)),
                List.of(PipelineEdge.data("src", "out"))));

        JobConfig cfg = new JobConfig("nightly", JobType.PIPELINE, null, null, true, false,
                Map.of("pipeline", "evt_rollup", "data_dir", dataDir));
        JobResult res = new PipelineJobRunner(cfg, new BatchEventBus(), store, dataDir, auditDir).run();

        assertTrue(res.success(), res.message());
        assertEquals(List.of(1, 2), readIds(dataDir, "rollup"));
    }

    @Test
    void rerunWithSameBatchIdSkipsTheCommittedBranch() throws Exception {
        String dataDir = tmp.resolve("data").toString();
        String auditDir = tmp.resolve("audit").toString();
        seedParquet(dataDir, "events", "(1,150),(3,200)");
        PipelineStore store = new PipelineStore(tmp.resolve("flows"));
        store.write("evt_rollup", new PipelineGraph("evt_rollup", true,
                List.of(PipelineNode.of("src", "acquisition", Map.of("source_store", "events")),
                        new PipelineNode("out", "sink.persistent", "Rollup", null, Map.of("store", "rollup"), null)),
                List.of(PipelineEdge.data("src", "out"))));

        JobConfig cfg = new JobConfig("nightly", JobType.PIPELINE, null, null, true, false,
                Map.of("flow", "evt_rollup", "data_dir", dataDir, "batch_id", "fixed-1"));
        BatchEventBus bus = new BatchEventBus();

        JobResult first = new PipelineJobRunner(cfg, bus, store, dataDir, auditDir).run();
        assertTrue(first.message().contains("1 file"), first.message());

        // same batch id ⇒ the branch is already durable in the branch-commit log ⇒ nothing re-written
        JobResult second = new PipelineJobRunner(cfg, bus, store, dataDir, auditDir).run();
        assertTrue(second.success(), second.message());
        assertTrue(second.message().startsWith("0 file(s)"), "replay should write nothing: " + second.message());
        assertEquals(List.of(1, 3), readIds(dataDir, "rollup"), "output unchanged by the idempotent replay");
    }

    @Test
    void routesToTwoSinkStores() throws Exception {
        String dataDir = tmp.resolve("data").toString();
        String auditDir = tmp.resolve("audit").toString();
        seedParquet(dataDir, "events", "(1,150),(2,50),(3,200)");
        PipelineStore store = new PipelineStore(tmp.resolve("flows"));
        store.write("split_flow", new PipelineGraph("split_flow", true,
                List.of(PipelineNode.of("src", "acquisition", Map.of("source_store", "events")),
                        PipelineNode.of("r", "transform.route", Map.of("mode", "case", "branches",
                                List.of(Map.of("key", "hi", "where", "amt >= 200"),
                                        Map.of("key", "lo", "where", "amt < 200")))),
                        new PipelineNode("sink_hi", "sink.persistent", "Hi", null, Map.of("store", "hi"), null),
                        new PipelineNode("sink_lo", "sink.persistent", "Lo", null, Map.of("store", "lo"), null)),
                List.of(PipelineEdge.data("src", "r"),
                        new PipelineEdge("r", PipelineRel.route("hi"), "sink_hi"),
                        new PipelineEdge("r", PipelineRel.route("lo"), "sink_lo"))));

        JobConfig cfg = new JobConfig("splitjob", JobType.PIPELINE, null, null, true, false,
                Map.of("flow", "split_flow", "data_dir", dataDir));
        JobResult res = new PipelineJobRunner(cfg, new BatchEventBus(), store, dataDir, auditDir).run();

        assertTrue(res.success(), res.message());
        assertEquals(List.of(3), readIds(dataDir, "hi"), "amt>=200 → id3");
        assertEquals(List.of(1, 2), readIds(dataDir, "lo"), "amt<200 → id1(150), id2(50)");
    }

    @Test
    void rejectsFlowWithNoSourceStore() throws Exception {
        String dataDir = tmp.resolve("data").toString();
        PipelineStore store = new PipelineStore(tmp.resolve("flows"));
        store.write("no_src", new PipelineGraph("no_src", true,
                List.of(PipelineNode.of("acq", "acquisition"),
                        new PipelineNode("out", "sink.persistent", "O", null, Map.of("store", "o"), null)),
                List.of(PipelineEdge.data("acq", "out"))));

        JobConfig cfg = new JobConfig("j", JobType.PIPELINE, null, null, true, false,
                Map.of("flow", "no_src", "data_dir", dataDir));
        PipelineJobRunner runner = new PipelineJobRunner(cfg, new BatchEventBus(), store, dataDir, tmp.resolve("audit").toString());
        assertThrows(IllegalArgumentException.class, runner::run);
    }

    @Test
    void unionsTwoSourceStores() throws Exception {
        // T32 Phase C — a flow job seeds each source_store as its own view; a transform.merge unions them.
        String dataDir = tmp.resolve("data").toString();
        String auditDir = tmp.resolve("audit").toString();
        seedParquet(dataDir, "events_a", "(1,150),(3,200)");
        seedParquet(dataDir, "events_b", "(5,500),(2,50)");
        PipelineStore store = new PipelineStore(tmp.resolve("flows"));
        store.write("merged_flow", new PipelineGraph("merged_flow", true,
                List.of(PipelineNode.of("src_a", "acquisition", Map.of("source_store", "events_a")),
                        PipelineNode.of("src_b", "acquisition", Map.of("source_store", "events_b")),
                        PipelineNode.of("m", "transform.merge", Map.of("type", "union")),
                        new PipelineNode("out", "sink.persistent", "Combined", null, Map.of("store", "combined"), null)),
                List.of(PipelineEdge.data("src_a", "m"), PipelineEdge.data("src_b", "m"), PipelineEdge.data("m", "out"))));

        JobConfig cfg = new JobConfig("merge_job", JobType.PIPELINE, null, null, true, false,
                Map.of("flow", "merged_flow", "data_dir", dataDir));
        JobResult res = new PipelineJobRunner(cfg, new BatchEventBus(), store, dataDir, auditDir).run();

        assertTrue(res.success(), res.message());
        assertEquals(List.of(1, 2, 3, 5), readIds(dataDir, "combined"),
                "union of both source_stores — all 4 rows across the two stores");
    }

    @Test
    void registersASinkViewDefinitionWithoutWritingBytes() throws Exception {
        // T32 Phase C — a sink.view persists no bytes; the flow job records a durable view definition instead.
        Path wr = tmp.resolve("wr");
        String dataDir = tmp.resolve("data").toString();
        String auditDir = tmp.resolve("audit").toString();
        seedParquet(dataDir, "subs", "(1,150),(2,50),(3,200)");
        PipelineStore store = new PipelineStore(wr.resolve("flows"));
        store.write("subs_kpi", new PipelineGraph("subs_kpi", true,
                List.of(PipelineNode.of("src", "acquisition", Map.of("source_store", "subs")),
                        PipelineNode.of("flt", "transform.filter", Map.of("where", "amt >= 100")),
                        new PipelineNode("v", "sink.view", "ActiveSubs", null, Map.of("store", "active_subs"), null)),
                List.of(PipelineEdge.data("src", "flt"), PipelineEdge.data("flt", "v"))));

        JobConfig cfg = new JobConfig("kpi_job", JobType.PIPELINE, null, null, true, false,
                Map.of("flow", "subs_kpi", "data_dir", dataDir));
        JobResult res = new PipelineJobRunner(cfg, new BatchEventBus(), store, dataDir, auditDir).run();

        assertTrue(res.success(), res.message());
        assertFalse(Files.exists(Path.of(dataDir, "active_subs")), "a sink.view writes no data bytes");
        ViewDefinition def = new ViewStore(wr.resolve("views")).get("active_subs").orElseThrow();
        assertEquals("subs_kpi", def.flow(), "view definition records the producing flow");
        assertEquals(List.of("subs"), def.sourceStores(), "view definition records source-store lineage");
    }

    @Test
    void incrementalReadsOnlyNewRowsPastTheWatermark() throws Exception {
        // T32 Phase C — incremental_column: run 1 reads everything, run 2 reads only rows past the watermark.
        String dataDir = tmp.resolve("data").toString();
        String auditDir = tmp.resolve("audit").toString();
        seedParquetFile(dataDir, "events", "batch1", "(1,150),(2,50)");
        PipelineStore store = new PipelineStore(tmp.resolve("flows"));
        store.write("inc_flow", new PipelineGraph("inc_flow", true,
                List.of(PipelineNode.of("src", "acquisition", Map.of("source_store", "events")),
                        new PipelineNode("out", "sink.persistent", "Rollup", null, Map.of("store", "rollup"), null)),
                List.of(PipelineEdge.data("src", "out"))));

        // run 1 — no prior watermark ⇒ reads all of batch1 (ids 1,2)
        JobConfig r1 = new JobConfig("incjob", JobType.PIPELINE, null, null, true, false,
                Map.of("flow", "inc_flow", "data_dir", dataDir, "incremental_column", "id", "batch_id", "inc1"));
        assertTrue(new PipelineJobRunner(r1, new BatchEventBus(), store, dataDir, auditDir).run().success());
        assertEquals(List.of(1, 2), readIds(dataDir, "rollup"), "first run reads the whole store");

        // new data arrives, then run 2 — watermark=2 ⇒ reads only ids 3,4 and appends (output accumulates)
        seedParquetFile(dataDir, "events", "batch2", "(3,200),(4,300)");
        JobConfig r2 = new JobConfig("incjob", JobType.PIPELINE, null, null, true, false,
                Map.of("flow", "inc_flow", "data_dir", dataDir, "incremental_column", "id", "batch_id", "inc2"));
        assertTrue(new PipelineJobRunner(r2, new BatchEventBus(), store, dataDir, auditDir).run().success());
        assertEquals(List.of(1, 2, 3, 4), readIds(dataDir, "rollup"), "second run appends only the new rows");
    }

    @Test
    void incrementalMultiSourceAdvancesPerSourceWatermarks() throws Exception {
        // T32 follow-up — each source_store keeps its OWN watermark, so a multi-source incremental flow
        // re-reads only the rows newer than each source's last run.
        String dataDir = tmp.resolve("data").toString();
        String auditDir = tmp.resolve("audit").toString();
        seedParquetFile(dataDir, "events_a", "a1", "(1,10),(2,20)");
        seedParquetFile(dataDir, "events_b", "b1", "(3,15)");
        PipelineStore store = new PipelineStore(tmp.resolve("flows"));
        store.write("inc_merge", new PipelineGraph("inc_merge", true,
                List.of(PipelineNode.of("src_a", "acquisition", Map.of("source_store", "events_a")),
                        PipelineNode.of("src_b", "acquisition", Map.of("source_store", "events_b")),
                        PipelineNode.of("m", "transform.merge", Map.of("type", "union")),
                        new PipelineNode("out", "sink.persistent", "Combined", null, Map.of("store", "combined"), null)),
                List.of(PipelineEdge.data("src_a", "m"), PipelineEdge.data("src_b", "m"), PipelineEdge.data("m", "out"))));

        JobConfig run1 = new JobConfig("inc_job", JobType.PIPELINE, null, null, true, false,
                Map.of("flow", "inc_merge", "data_dir", dataDir, "incremental_column", "amt", "batch_id", "b1"));
        new PipelineJobRunner(run1, new BatchEventBus(), store, dataDir, auditDir).run();
        assertEquals(List.of(1, 2, 3), readIds(dataDir, "combined"), "run 1 (no watermark) reads every source in full");

        // new rows arrive in BOTH sources, each past that source's OWN amt watermark (events_a:20, events_b:15)
        seedParquetFile(dataDir, "events_a", "a2", "(4,30)");
        seedParquetFile(dataDir, "events_b", "b2", "(5,25)");
        JobConfig run2 = new JobConfig("inc_job", JobType.PIPELINE, null, null, true, false,
                Map.of("flow", "inc_merge", "data_dir", dataDir, "incremental_column", "amt", "batch_id", "b2"));
        new PipelineJobRunner(run2, new BatchEventBus(), store, dataDir, auditDir).run();

        // run 2 appended only id4 (events_a amt 30>20) and id5 (events_b amt 25>15) — no re-read of 1,2,3
        assertEquals(List.of(1, 2, 3, 4, 5), readIds(dataDir, "combined"),
                "per-source incremental: run 2 added only rows newer than each source's watermark");
    }

    @Test
    void sinkViewCapturesDerivedSqlForALinearPath() throws Exception {
        // T32 follow-up — a single-source, linear filter→sink.view path folds into one SELECT (derived_sql).
        Path wr = tmp.resolve("wr");
        String dataDir = tmp.resolve("data").toString();
        String auditDir = tmp.resolve("audit").toString();
        seedParquet(dataDir, "subs", "(1,150),(2,50),(3,200)");
        PipelineStore store = new PipelineStore(wr.resolve("flows"));
        store.write("subs_kpi", new PipelineGraph("subs_kpi", true,
                List.of(PipelineNode.of("src", "acquisition", Map.of("source_store", "subs")),
                        PipelineNode.of("flt", "transform.filter", Map.of("where", "amt >= 100")),
                        new PipelineNode("v", "sink.view", "ActiveSubs", null, Map.of("store", "active_subs"), null)),
                List.of(PipelineEdge.data("src", "flt"), PipelineEdge.data("flt", "v"))));
        JobConfig cfg = new JobConfig("kpi_job", JobType.PIPELINE, null, null, true, false,
                Map.of("flow", "subs_kpi", "data_dir", dataDir));
        assertTrue(new PipelineJobRunner(cfg, new BatchEventBus(), store, dataDir, auditDir).run().success());

        ViewDefinition def = new ViewStore(wr.resolve("views")).get("active_subs").orElseThrow();
        assertNotNull(def.derivedSql(), "a single-source linear filter path yields a derived_sql");
        // The persisted SQL TEMPLATES its source read (addressing §7-A) so the Consignment catalog can
        // subtract superseded files at read time; the runner records the ingredients to render it from.
        assertTrue(def.derivedSql().contains(com.gamma.query.ViewReaderSql.READER_TOKEN),
                "the persisted read is a template, not a baked-in glob: " + def.derivedSql());
        assertNotNull(def.readerRoot(), "the render needs the store read root");
        assertEquals("PARQUET", def.readerFormat());
        assertEquals(List.of(1, 3), runIds(com.gamma.query.ViewReaderSql.rendered(def)),
                "rendered derived_sql selects amt>=100 (id1, id3)");
    }

    @Test
    void sinkViewDerivedSqlIsNullForAMergedPath() throws Exception {
        // T32 follow-up — a merge (2 sources) feeding the view is NOT a single SELECT over one source → null.
        Path wr = tmp.resolve("wr");
        String dataDir = tmp.resolve("data").toString();
        String auditDir = tmp.resolve("audit").toString();
        seedParquet(dataDir, "events_a", "(1,150)");
        seedParquet(dataDir, "events_b", "(2,200)");
        PipelineStore store = new PipelineStore(wr.resolve("flows"));
        store.write("merge_view", new PipelineGraph("merge_view", true,
                List.of(PipelineNode.of("src_a", "acquisition", Map.of("source_store", "events_a")),
                        PipelineNode.of("src_b", "acquisition", Map.of("source_store", "events_b")),
                        PipelineNode.of("m", "transform.merge", Map.of("type", "union")),
                        new PipelineNode("v", "sink.view", "Merged", null, Map.of("store", "merged"), null)),
                List.of(PipelineEdge.data("src_a", "m"), PipelineEdge.data("src_b", "m"), PipelineEdge.data("m", "v"))));
        JobConfig cfg = new JobConfig("mv_job", JobType.PIPELINE, null, null, true, false,
                Map.of("flow", "merge_view", "data_dir", dataDir));
        assertTrue(new PipelineJobRunner(cfg, new BatchEventBus(), store, dataDir, auditDir).run().success());

        ViewDefinition def = new ViewStore(wr.resolve("views")).get("merged").orElseThrow();
        assertNull(def.derivedSql(), "a merged (multi-source) view path is not single-SELECT expressible → null");
    }

    @Test
    void incrementalWatermarkOnAVarcharColumnIsNotTruncated() throws Exception {
        // task #11 — DuckDB answers max() on a Parquet VARCHAR column from its writer-truncated min/max
        // statistics ('2020-01-02' -> '2020-01-'); a truncated (prefix) watermark would re-admit already-seen
        // rows on the next run. The fix forces a scanned max for string columns.
        String dataDir = tmp.resolve("data").toString();
        String auditDir = tmp.resolve("audit").toString();
        seedTsFile(dataDir, "events", "a1", "(1,'2020-01-01'),(2,'2020-01-02')");
        PipelineStore store = new PipelineStore(tmp.resolve("flows"));
        store.write("inc_str", new PipelineGraph("inc_str", true,
                List.of(PipelineNode.of("src", "acquisition", Map.of("source_store", "events")),
                        new PipelineNode("out", "sink.persistent", "Rollup", null, Map.of("store", "rollup"), null)),
                List.of(PipelineEdge.data("src", "out"))));
        JobConfig r1 = new JobConfig("istr", JobType.PIPELINE, null, null, true, false,
                Map.of("flow", "inc_str", "data_dir", dataDir, "incremental_column", "ts", "batch_id", "i1"));
        new PipelineJobRunner(r1, new BatchEventBus(), store, dataDir, auditDir).run();
        assertEquals(List.of(1, 2), readIds(dataDir, "rollup"), "run 1 reads the whole store");

        seedTsFile(dataDir, "events", "a2", "(3,'2020-01-03')");
        JobConfig r2 = new JobConfig("istr", JobType.PIPELINE, null, null, true, false,
                Map.of("flow", "inc_str", "data_dir", dataDir, "incremental_column", "ts", "batch_id", "i2"));
        new PipelineJobRunner(r2, new BatchEventBus(), store, dataDir, auditDir).run();
        // truncated watermark '2020-01-' would re-admit ids 1,2 (lexically > the prefix) → [1,1,2,2,3];
        // the fix stores the true max '2020-01-02', so run 2 appends only id 3.
        assertEquals(List.of(1, 2, 3), readIds(dataDir, "rollup"), "run 2 appends only the row past the true watermark");
    }

    @Test
    void persistsPerEdgeProvenanceWhenAStoreIsConfigured() throws Exception {
        // T21 — a flow run records its per-(node, relationship) record counts to the provenance store.
        String dataDir = tmp.resolve("data").toString();
        String auditDir = tmp.resolve("audit").toString();
        seedParquet(dataDir, "events", "(1,150),(2,50),(3,200)");
        PipelineStore store = new PipelineStore(tmp.resolve("flows"));
        store.write("prov_flow", new PipelineGraph("prov_flow", true,
                List.of(PipelineNode.of("src", "acquisition", Map.of("source_store", "events")),
                        PipelineNode.of("flt", "transform.filter", Map.of("where", "amt >= 100")),
                        new PipelineNode("out", "sink.persistent", "Rollup", null, Map.of("store", "rollup"), null)),
                List.of(PipelineEdge.data("src", "flt"), PipelineEdge.data("flt", "out"))));

        JobConfig cfg = new JobConfig("provjob", JobType.PIPELINE, null, null, true, false,
                Map.of("flow", "prov_flow", "data_dir", dataDir, "batch_id", "prov1"));

        String url = "jdbc:duckdb:" + tmp.resolve("prov.duckdb").toString().replace("\\", "/");
        try (DbProvenanceStore prov = DbProvenanceStore.open(url)) {
            assertTrue(new PipelineJobRunner(cfg, new BatchEventBus(), store, dataDir, auditDir, prov).run().success());

            Map<String, Long> counts = new java.util.LinkedHashMap<>();
            for (Map<String, Object> row : prov.query("prov_flow", "prov1"))
                counts.put(row.get("nodeId") + "|" + row.get("rel"), ((Number) row.get("rowCount")).longValue());

            assertEquals(3L, counts.get("src|data"), "seed recordsIn");
            assertEquals(2L, counts.get("flt|data"), "amt>=100 keeps id1(150), id3(200)");
            assertEquals(1L, counts.get("flt|dropped"), "id2(50) diverted");
            assertEquals(2L, counts.get("out|data"), "the sink received both surviving rows");
            // the run is discoverable
            assertEquals(1, prov.batches("prov_flow", 10).size());
        }
    }

    @Test
    void reportConservationEmitsAnImbalanceEventForALostRecordCount() {
        // T22 — the run→check→event bridge. A healthy real run conserves by construction (every conserving
        // node records both its kept and its diverted relations — see PipelineExecutor.recordCounts), so a
        // positive imbalance is only reachable from an injected count mismatch, not a clean flow. Drive the
        // bridge directly with crafted counts: a filter that consumed 3 but accounted for only 2 (data 2,
        // dropped 0) is a silent LOSS the runner must promote to a FLOW_CONSERVATION_IMBALANCE event.
        PipelineGraph g = new PipelineGraph("loss_flow", true,
                List.of(PipelineNode.of("src", "parser"),
                        PipelineNode.of("flt", "transform.filter", Map.of("where", "amt >= 100")),
                        PipelineNode.of("out", "sink.persistent", Map.of("store", "o"))),
                List.of(PipelineEdge.data("src", "flt"), PipelineEdge.data("flt", "out")));

        List<Event> captured = new CopyOnWriteArrayList<>();
        Consumer<Event> sub = e -> {
            if (EventType.PIPELINE_CONSERVATION_IMBALANCE.equals(e.type()) && "loss_flow".equals(e.pipeline()))
                captured.add(e);
        };
        EventLog.global().addSubscriber(sub);   // reportConservation emits via EventLog.current() → global() (no space MDC)
        try {
            PipelineJobRunner.reportConservation(g, "loss_flow", "batch-1", List.of(
                    new ProvenanceRow("loss_flow", "batch-1", "src", PipelineRel.DATA, 3, "t"),
                    new ProvenanceRow("loss_flow", "batch-1", "flt", PipelineRel.DATA, 2, "t"),
                    new ProvenanceRow("loss_flow", "batch-1", "flt", PipelineRel.DROPPED, 0, "t"),
                    new ProvenanceRow("loss_flow", "batch-1", "out", PipelineRel.DATA, 2, "t")));

            assertEquals(1, captured.size(), "one imbalance event for the lossy filter node");
            Event e = captured.get(0);
            assertEquals("flt", e.attributes().get("node"));
            assertEquals("3", e.attributes().get("recordsIn"));
            assertEquals("2", e.attributes().get("recordsOut"));
            assertEquals("LOSS", e.attributes().get("kind"));
            assertEquals(EventLevel.ERROR, e.level(), "a LOSS is ERROR-level (an AMPLIFICATION would be WARN)");
            assertEquals("batch-1", e.correlationId(), "correlated to the run's batchId");

            // a balanced accounting (dropped 1 ⇒ 2 + 1 == 3 in) fires nothing — no false positive on a clean run
            captured.clear();
            PipelineJobRunner.reportConservation(g, "loss_flow", "batch-2", List.of(
                    new ProvenanceRow("loss_flow", "batch-2", "src", PipelineRel.DATA, 3, "t"),
                    new ProvenanceRow("loss_flow", "batch-2", "flt", PipelineRel.DATA, 2, "t"),
                    new ProvenanceRow("loss_flow", "batch-2", "flt", PipelineRel.DROPPED, 1, "t"),
                    new ProvenanceRow("loss_flow", "batch-2", "out", PipelineRel.DATA, 2, "t")));
            assertTrue(captured.isEmpty(), "a balanced run emits no imbalance");
        } finally {
            EventLog.global().removeSubscriber(sub);
        }
    }

    // ── store-layout contract (BACKLOG §1, decided 2026-07-18) ──────────────────

    @Test
    void seedReadsAPipelineShapedStoresMappedOutputOnly() throws Exception {
        // a pipeline-shaped store: mapped output under database/, plus a stray sibling parquet tree
        String dataDir = tmp.resolve("data").toString();
        String auditDir = tmp.resolve("audit").toString();
        seedParquetFile(dataDir, "orders/database", "seed", "(1,150),(2,50)");
        seedParquetFile(dataDir, "orders/stray", "extra", "(9,999)");
        PipelineStore store = new PipelineStore(tmp.resolve("flows"));
        store.write("copy_flow", new PipelineGraph("copy_flow", true,
                List.of(PipelineNode.of("src", "acquisition", Map.of("source_store", "orders")),
                        new PipelineNode("out", "sink.persistent", "O", null, Map.of("store", "rollup"), null)),
                List.of(PipelineEdge.data("src", "out"))));

        JobConfig cfg = new JobConfig("copyjob", JobType.PIPELINE, null, null, true, false,
                Map.of("flow", "copy_flow", "data_dir", dataDir));
        JobResult res = new PipelineJobRunner(cfg, new BatchEventBus(), store, dataDir, auditDir).run();

        assertTrue(res.success(), res.message());
        assertEquals(List.of(1, 2), readIds(dataDir, "rollup"),
                "only database/ rows seeded — the stray sibling tree stayed out");
    }

    @Test
    void sinkNestedInsideAnotherStoreFailsClosed() throws Exception {
        // the UAT double-count shape: data_dir points INSIDE a store, so the sink would nest there
        String dataDir = tmp.resolve("data").toString();
        seedParquetFile(dataDir, "orders/database", "seed", "(1,150)");
        PipelineStore store = new PipelineStore(tmp.resolve("flows"));
        store.write("nest_flow", new PipelineGraph("nest_flow", true,
                List.of(PipelineNode.of("src", "acquisition", Map.of("source_store", "database")),
                        new PipelineNode("out", "sink.persistent", "O", null, Map.of("store", "rollup"), null)),
                List.of(PipelineEdge.data("src", "out"))));

        JobConfig cfg = new JobConfig("nestjob", JobType.PIPELINE, null, null, true, false,
                Map.of("flow", "nest_flow", "data_dir", tmp.resolve("data").resolve("orders").toString()));
        PipelineJobRunner runner = new PipelineJobRunner(cfg, new BatchEventBus(), store, dataDir,
                tmp.resolve("audit").toString());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, runner::run);
        assertTrue(ex.getMessage().contains("top-level"), ex.getMessage());
        assertFalse(Files.exists(Path.of(dataDir, "orders", "rollup")), "failed closed — nothing written");
    }

    @Test
    void slashedSinkStoreNameFailsClosed() throws Exception {
        String dataDir = tmp.resolve("data").toString();
        seedParquet(dataDir, "events", "(1,150)");
        PipelineStore store = new PipelineStore(tmp.resolve("flows"));
        store.write("slash_flow", new PipelineGraph("slash_flow", true,
                List.of(PipelineNode.of("src", "acquisition", Map.of("source_store", "events")),
                        new PipelineNode("out", "sink.persistent", "O", null,
                                Map.of("store", "events/rollup"), null)),
                List.of(PipelineEdge.data("src", "out"))));

        JobConfig cfg = new JobConfig("slashjob", JobType.PIPELINE, null, null, true, false,
                Map.of("flow", "slash_flow", "data_dir", dataDir));
        assertThrows(IllegalArgumentException.class,
                () -> new PipelineJobRunner(cfg, new BatchEventBus(), store, dataDir,
                        tmp.resolve("audit").toString()).run());
    }

    @Test
    void externalDataDirStaysAllowed() throws Exception {
        // a root fully outside the space data root is the data_dir escape hatch (external export)
        String dataDir = tmp.resolve("data").toString();
        String external = tmp.resolve("external").toString();
        seedParquet(external, "events", "(1,150)");
        PipelineStore store = new PipelineStore(tmp.resolve("flows"));
        store.write("ext_flow", new PipelineGraph("ext_flow", true,
                List.of(PipelineNode.of("src", "acquisition", Map.of("source_store", "events")),
                        new PipelineNode("out", "sink.persistent", "O", null, Map.of("store", "rollup"), null)),
                List.of(PipelineEdge.data("src", "out"))));

        JobConfig cfg = new JobConfig("extjob", JobType.PIPELINE, null, null, true, false,
                Map.of("flow", "ext_flow", "data_dir", external));
        JobResult res = new PipelineJobRunner(cfg, new BatchEventBus(), store, dataDir,
                tmp.resolve("audit").toString()).run();

        assertTrue(res.success(), res.message());
        assertEquals(List.of(1), readIds(external, "rollup"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** Write {@code (id,ts)} VARCHAR VALUES as a uniquely-named Parquet file under {@code <dataDir>/<store>/}. */
    private static void seedTsFile(String dataDir, String store, String file, String valuesSql) throws Exception {
        Path dir = Path.of(dataDir, store);
        Files.createDirectories(dir);
        File db = DuckDbUtil.tempDbFile("seed_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES " + valuesSql + ") t(id,ts)) TO '"
                    + dir.resolve(file + ".parquet").toString().replace("\\", "/") + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** Execute {@code sql} on a fresh DuckDB and return the {@code id} column, ascending. */
    private static List<Integer> runIds(String sql) throws Exception {
        File db = DuckDbUtil.tempDbFile("dsql_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM (" + sql + ") q ORDER BY 1")) {
            List<Integer> out = new ArrayList<>();
            while (rs.next()) out.add(rs.getInt(1));
            return out;
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    // ── A5-at-rest slice 2: pipeline_config: lifts the flat file's Stage-2 chain at run time ──

    @Test
    void runsAFlatConfigsStageTwoChainOverItsLandedStore() throws Exception {
        String dataDir = tmp.resolve("data").toString();
        String auditDir = tmp.resolve("audit").toString();
        // the REAL file, through the real toon codec — a fromMap test would skip the spelling
        // (steps: needs the element count; a bare list decodes as a map and refuses)
        Path flat = tmp.resolve("shape_pipeline.toon");
        Files.writeString(flat, """
                name: shape_etl
                active: false
                output_store: shaped
                dirs:
                  poll: in
                  database: out
                processing:
                  threads: 1
                steps[1]:
                  - dedup:
                      keys[1]: amt
                      order_by: id
                """);
        // the landed store's name is the lift's canonical-name fallback — read it off the config
        String landed = com.gamma.etl.PipelineConfig.load(flat.toString()).identity().pipelineName();
        seedParquet(dataDir, landed, "(1,150),(2,50),(3,150)");   // amt 150 is duplicated

        JobConfig cfg = new JobConfig("shaper", JobType.PIPELINE, null, null, true, false,
                Map.of("pipeline_config", flat.toString(), "data_dir", dataDir));
        JobResult res = new PipelineJobRunner(cfg, new BatchEventBus(), null, dataDir, auditDir).run();

        assertTrue(res.success(), res.message());
        assertEquals(List.of(1, 2), readIds(dataDir, "shaped"),
                "dedup by amt keeps the first per key — id 3's amt=150 is the duplicate");
    }

    // ── A5-at-rest slice 5: transform.join resolves its Reference Dataset for real ──

    /** A path-form reference needs no pipeline context at all — the file is self-describing. */
    @Test
    void aStageTwoJoinAgainstAPathReferenceEnrichesTheLandedRows() throws Exception {
        String dataDir = tmp.resolve("data").toString();
        String auditDir = tmp.resolve("audit").toString();
        Path dim = tmp.resolve("region_dim.parquet");
        seedDim(dim, "(1,'EMEA'),(2,'APAC')");

        Path flat = tmp.resolve("j1_pipeline.toon");
        Files.writeString(flat, """
                name: j1_etl
                active: false
                output_store: joined
                dirs:
                  poll: in
                  database: out
                processing:
                  threads: 1
                steps[1]:
                  - join:
                      reference: %s
                      on[1]: id
                """.formatted(dim.toString().replace("\\", "/")));
        seedParquet(dataDir, "j1_etl", "(1,150),(2,50),(3,200)");

        JobConfig cfg = new JobConfig("j1", JobType.PIPELINE, null, null, true, false,
                Map.of("pipeline_config", flat.toString(), "data_dir", dataDir));
        JobResult res = new PipelineJobRunner(cfg, new BatchEventBus(), null, dataDir, auditDir).run();

        assertTrue(res.success(), res.message());
        // LEFT JOIN: all three rows survive; id 3 has no dimension row and carries NULL
        assertEquals(List.of(1, 2, 3), readIds(dataDir, "joined"));
        assertEquals(java.util.Arrays.asList("EMEA", "APAC", null),   // by id; List.of rejects the null
                readColumn(dataDir, "joined", "region"));
    }

    /** A by-name reference (reference/<pipeline>) resolves through the loaded-pipeline context. */
    @Test
    void aStageTwoJoinResolvesAByNameReferenceFromThePipelineContext() throws Exception {
        String dataDir = tmp.resolve("data").toString();
        String auditDir = tmp.resolve("audit").toString();
        // the reference producer: a pipeline declaring produces: reference, whose store holds the dimension
        Path refStore = tmp.resolve("refdb");
        seedDim(refStore.resolve("dim.parquet"), "(1,'EMEA'),(2,'APAC')");
        Path refCfgPath = tmp.resolve("regions_pipeline.toon");
        Files.writeString(refCfgPath, """
                name: regions
                active: false
                produces: reference
                dirs:
                  poll: in
                  database: %s
                output:
                  format: PARQUET
                processing:
                  threads: 1
                """.formatted(refStore.toString().replace("\\", "/")));
        var refCfg = com.gamma.etl.PipelineConfig.load(refCfgPath.toString());

        Path flat = tmp.resolve("j2_pipeline.toon");
        Files.writeString(flat, """
                name: j2_etl
                active: false
                output_store: joined2
                dirs:
                  poll: in
                  database: out
                processing:
                  threads: 1
                steps[1]:
                  - join:
                      reference: reference/regions
                      on[1]: id
                """);
        seedParquet(dataDir, "j2_etl", "(1,150),(2,50)");

        JobConfig cfg = new JobConfig("j2", JobType.PIPELINE, null, null, true, false,
                Map.of("pipeline_config", flat.toString(), "data_dir", dataDir));
        JobResult res = new PipelineJobRunner(cfg, new BatchEventBus(), null, dataDir, auditDir,
                null, null, () -> List.of(refCfg)).run();

        assertTrue(res.success(), res.message());
        assertEquals(List.of("EMEA", "APAC"), readColumn(dataDir, "joined2", "region"));   // by id
    }

    /** Without the pipeline context a by-name join must refuse, naming the missing wiring. */
    @Test
    void aByNameJoinWithoutPipelineContextRefuses() throws Exception {
        String dataDir = tmp.resolve("data").toString();
        Path flat = tmp.resolve("j3_pipeline.toon");
        Files.writeString(flat, """
                name: j3_etl
                active: false
                output_store: joined3
                dirs:
                  poll: in
                  database: out
                processing:
                  threads: 1
                steps[1]:
                  - join:
                      reference: reference/nowhere
                      on[1]: id
                """);
        seedParquet(dataDir, "j3_etl", "(1,150)");
        JobConfig cfg = new JobConfig("j3", JobType.PIPELINE, null, null, true, false,
                Map.of("pipeline_config", flat.toString(), "data_dir", dataDir));
        var e = assertThrows(IllegalArgumentException.class, () ->
                new PipelineJobRunner(cfg, new BatchEventBus(), null, dataDir,
                        tmp.resolve("audit").toString()).run());
        assertTrue(e.getMessage().contains("nowhere"), e.getMessage());
    }

    @Test
    void aJobCarryingBothGraphSourcesRefuses() throws Exception {
        String dataDir = tmp.resolve("data").toString();
        JobConfig cfg = new JobConfig("twosrc", JobType.PIPELINE, null, null, true, false,
                Map.of("pipeline_config", "x.toon", "flow", "some_flow", "data_dir", dataDir));
        PipelineJobRunner runner = new PipelineJobRunner(cfg, new BatchEventBus(), null,
                dataDir, tmp.resolve("audit").toString());
        var e = assertThrows(IllegalArgumentException.class, runner::run);
        assertTrue(e.getMessage().contains("pick one graph source"), e.getMessage());
    }

    /** Write {@code (id,amt)} VALUES as a Parquet file under {@code <dataDir>/<store>/} (the at-rest store). */
    private static void seedParquet(String dataDir, String store, String valuesSql) throws Exception {
        seedParquetFile(dataDir, store, "seed", valuesSql);
    }

    /** Like {@link #seedParquet} but with an explicit file stem, so a store can accumulate several files. */
    private static void seedParquetFile(String dataDir, String store, String file, String valuesSql) throws Exception {
        Path dir = Path.of(dataDir, store);
        Files.createDirectories(dir);
        File db = DuckDbUtil.tempDbFile("seed_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES " + valuesSql + ") t(id,amt)) TO '"
                    + dir.resolve(file + ".parquet").toString().replace("\\", "/") + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** Write {@code (id,region)} VALUES as one Parquet dimension file at {@code path}. */
    private static void seedDim(Path path, String valuesSql) throws Exception {
        Files.createDirectories(path.getParent());
        File db = DuckDbUtil.tempDbFile("dim_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES " + valuesSql + ") t(id,region)) TO '"
                    + path.toString().replace("\\", "/") + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** Read one column of every Parquet file under {@code <dataDir>/<store>}, ordered by {@code id}. */
    private static List<String> readColumn(String dataDir, String store, String column) throws Exception {
        String glob = dataDir.replace("\\", "/") + "/" + store + "/**/*.parquet";
        File db = DuckDbUtil.tempDbFile("rc_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT \"" + column + "\" FROM "
                     + SqlViews.reader("PARQUET", glob, true) + " ORDER BY id")) {
            List<String> out = new ArrayList<>();
            while (rs.next()) out.add(rs.getString(1));
            return out;
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** Read the {@code id} column of every Parquet file under {@code <dataDir>/<store>}, ascending. */
    private static List<Integer> readIds(String dataDir, String store) throws Exception {
        String glob = dataDir.replace("\\", "/") + "/" + store + "/**/*.parquet";
        File db = DuckDbUtil.tempDbFile("rd_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM " + SqlViews.reader("PARQUET", glob, true) + " ORDER BY 1")) {
            List<Integer> out = new ArrayList<>();
            while (rs.next()) out.add(rs.getInt(1));
            return out;
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }
}
