package com.gamma.job;

import com.gamma.consignment.ConsignmentOutput;
import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.consignment.DbConsignmentOutputStore;
import com.gamma.enrich.DatedReferences;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.enrich.ReferenceReader;
import com.gamma.etl.ConsignmentEvent;
import com.gamma.etl.PartitionOutput;
import com.gamma.etl.PartitionWriter;
import com.gamma.etl.PipelineConfig;
import com.gamma.pipeline.PipelineEdge;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.PipelineRel;
import com.gamma.pipeline.PipelineStore;
import com.gamma.pipeline.exec.PipelineExecutor;
import com.gamma.pipeline.exec.RowShaper;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end unit and integration tests for {@code DATED-REFERENCE-RUNS-1} (P1–P4):
 * daily Reference versions, {@code version_by} + {@code if_version_missing} on keyed join,
 * work ledger over {@code consignment_outputs}, {@link DayReconciler}, deadlines,
 * corrections, and P4 Backlog controls.
 */
class DatedReferenceRunsTest {

    @AfterEach
    void clearStore() {
        ConsignmentOutputStores.use(null);
    }

    private static PipelineConfig dailyRefConfig(Path dir, String dayFrom, int keepDays) throws Exception {
        Files.createDirectories(dir);
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("load", "replace");
        ref.put("versions", "daily");
        ref.put("day_from", dayFrom);
        if (keepDays > 0) ref.put("keep_days", keepDays);
        return PipelineConfig.fromMap(Map.of(
                "name", "dim_dial_digit",
                "produces", "reference",
                "reference", ref,
                "dirs", Map.of("poll", dir.resolve("in").toString(),
                        "database", dir.resolve("db").toString(),
                        "temp", dir.resolve("temp").toString()),
                "output", Map.of("format", "PARQUET"),
                "processing", Map.of("threads", 1)));
    }

    private static void landDailyRef(PipelineConfig cfg, String fileName, String batchId, String[]... rows)
            throws Exception {
        String day = DatedReferences.extractDayFromFileName(fileName).orElseThrow();
        StringBuilder v = new StringBuilder();
        for (String[] r : rows) {
            v.append(v.isEmpty() ? "" : ", ")
                    .append("('").append(r[0]).append("', '").append(r[1]).append("', '").append(day).append("')");
        }
        File tmp = DuckDbUtil.tempDbFile("dated_ref_");
        try (Connection c = DuckDbUtil.openConnection(tmp); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE transformed AS SELECT * FROM (VALUES " + v
                    + ") AS t(dial_digit_key, provider, ref_day)");
            String stem = fileName.replaceFirst("\\.[^.]+$", "") + "_" + batchId;
            List<PartitionOutput> outs = PartitionWriter.write(c, "transformed", cfg.dirs().database(),
                    "PARQUET", "uncompressed", stem, List.of("ref_day"));
            DatedReferences.replaceDayAndPrune(Path.of(cfg.dirs().database()),
                    outs.stream().map(o -> Path.of(o.outputFile())).toList(), cfg.reference().keepDays());
        } finally {
            DuckDbUtil.deleteTempDb(tmp);
        }
    }

    @Test
    void p1DailyReferenceVersioningReplacesOnlyItsOwnDayAndPrunesKeepDays(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = dailyRefConfig(dir, "file_name", 2);
        assertTrue(cfg.reference().isDaily());
        assertEquals(2, cfg.reference().keepDays());

        landDailyRef(cfg, "DIM_DIAL_DIGIT_20261119.csv", "b19", new String[]{"44", "OLD_UK"});
        landDailyRef(cfg, "DIM_DIAL_DIGIT_20261120.csv", "b20", new String[]{"44", "MID_UK"});
        landDailyRef(cfg, "DIM_DIAL_DIGIT_20261122.csv", "b22", new String[]{"44", "NEW_UK"});

        // keep_days=2 relative to newest (2026-11-22) keeps 2026-11-20 and 2026-11-22; 2026-11-19 is pruned
        List<String> avail = DatedReferences.availableDays(Path.of(cfg.dirs().database()), "parquet");
        assertEquals(List.of("2026-11-20", "2026-11-22"), avail);

        // Corrected file for 2026-11-20 replaces 2026-11-20 only
        landDailyRef(cfg, "DIM_DIAL_DIGIT_20261120_corr.csv", "b20_corr", new String[]{"44", "FIXED_UK"});
        EnrichmentConfig.Reference refBinding = new EnrichmentConfig.Reference("dim", null, null, "dim_dial_digit");
        File tmp = DuckDbUtil.tempDbFile("read_day_");
        try (Connection c = DuckDbUtil.openConnection(tmp); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT provider FROM "
                    + ReferenceReader.sqlForDay(refBinding, List.of(cfg), "2026-11-20", "wait"))) {
                assertTrue(rs.next());
                assertEquals("FIXED_UK", rs.getString(1));
                assertFalse(rs.next());
            }
            try (ResultSet rs = st.executeQuery("SELECT provider FROM "
                    + ReferenceReader.sqlForDay(refBinding, List.of(cfg), "2026-11-22", "wait"))) {
                assertTrue(rs.next());
                assertEquals("NEW_UK", rs.getString(1));
                assertFalse(rs.next());
            }
        } finally {
            DuckDbUtil.deleteTempDb(tmp);
        }
    }

    @Test
    void p1KeyedJoinVersionByJoinsEachRowToItsOwnDayAndSupportsMissingPolicies(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = dailyRefConfig(dir, "file_name", 0);
        landDailyRef(cfg, "DIM_DIAL_DIGIT_20261119.csv", "b19", new String[]{"44", "UK_19"});
        landDailyRef(cfg, "DIM_DIAL_DIGIT_20261122.csv", "b22", new String[]{"44", "UK_22"});

        PipelineNode joinNode = new PipelineNode("j1", "transform.join", Map.of(
                "reference", "reference/dim_dial_digit",
                "on", "prefix",
                "key", "dial_digit_key",
                "match", "exact",
                "version_by", "CALL_DATE",
                "if_version_missing", "wait",
                "columns", List.of("provider")
        ), null);

        RowShaper.ReferenceResolver resolver = new RowShaper.ReferenceResolver() {
            @Override
            public String resolve(Connection conn, String reference) throws java.sql.SQLException {
                return resolveForDay(conn, reference, null, null);
            }

            @Override
            public String resolveForDay(Connection conn, String reference, String day, String ifVersionMissing)
                    throws java.sql.SQLException {
                EnrichmentConfig.Reference r = ReferenceReader.parse(reference);
                String sql = ReferenceReader.sqlForDay(r, List.of(cfg), day, ifVersionMissing);
                String v = "ref_" + (day == null ? "latest" : day.replace("-", "_"));
                try (Statement st = conn.createStatement()) {
                    st.execute("CREATE OR REPLACE VIEW " + v + " AS SELECT * FROM " + sql);
                }
                return v;
            }
        };

        File tmp = DuckDbUtil.tempDbFile("dated_join_");
        try (Connection c = DuckDbUtil.openConnection(tmp); Statement st = c.createStatement()) {
            // Multi-day input: 2026-11-19 and 2026-11-22
            st.execute("CREATE TABLE cdr_in AS SELECT * FROM (VALUES "
                    + "('c1', DATE '2026-11-19', '44'), "
                    + "('c2', DATE '2026-11-22', '44')) AS t(id, CALL_DATE, prefix)");
            List<RowShaper.Relation> rels = RowShaper.shape(c, joinNode, "cdr_in", "j1", resolver);
            String outTable = rels.get(0).table();
            Map<String, String> byId = new LinkedHashMap<>();
            try (ResultSet rs = st.executeQuery("SELECT id, prefix_provider FROM " + outTable + " ORDER BY id")) {
                while (rs.next()) byId.put(rs.getString(1), rs.getString(2));
            }
            assertEquals(Map.of("c1", "UK_19", "c2", "UK_22"), byId);

            // Missing day 2026-11-21 with policy=wait throws MissingVersionException
            st.execute("CREATE TABLE cdr_missing AS SELECT 'c3' AS id, DATE '2026-11-21' AS CALL_DATE, '44' AS prefix");
            assertThrows(DatedReferences.MissingVersionException.class,
                    () -> RowShaper.shape(c, joinNode, "cdr_missing", "j2", resolver));

            // Missing day 2026-11-21 with policy=previous falls back to 2026-11-19
            PipelineNode prevNode = new PipelineNode("j3", "transform.join", Map.of(
                    "reference", "reference/dim_dial_digit",
                    "on", "prefix",
                    "key", "dial_digit_key",
                    "match", "exact",
                    "version_by", "CALL_DATE",
                    "if_version_missing", "previous",
                    "columns", List.of("provider")
            ), null);
            List<RowShaper.Relation> prevRels = RowShaper.shape(c, prevNode, "cdr_missing", "j3", resolver);
            try (ResultSet rs = st.executeQuery("SELECT prefix_provider FROM " + prevRels.get(0).table())) {
                assertTrue(rs.next());
                assertEquals("UK_19", rs.getString(1));
            }
        } finally {
            DuckDbUtil.deleteTempDb(tmp);
        }
    }

    @Test
    void p2p3p4ReconcilerHoldsMissingRefDrainsMaxParallelHandlesDeadlinesAndBacklogControls(@TempDir Path dir)
            throws Exception {
        DbConsignmentOutputStore store = DbConsignmentOutputStore.open(
                "jdbc:duckdb:" + dir.resolve("outputs.duckdb"));
        ConsignmentOutputStores.use(store);

        PipelineConfig refCfg = dailyRefConfig(dir.resolve("ref"), "file_name", 0);
        // Only 2026-11-19 and 2026-11-20 exist; 2026-11-22 is missing
        landDailyRef(refCfg, "DIM_DIAL_DIGIT_20261119.csv", "r19", new String[]{"44", "UK_19"});
        landDailyRef(refCfg, "DIM_DIAL_DIGIT_20261120.csv", "r20", new String[]{"44", "UK_20"});

        // Register 3 days of CDR input files arrived 10 minutes ago (past quiet=5m)
        String tenMinsAgo = Instant.now().minusSeconds(600).toString();
        store.record(List.of(
                new ConsignmentOutput("c1", "run1", "cdr", "dt=2026-11-19", "2026-11-19",
                        dir.resolve("cdr/19a.parquet").toString(), 100, 1000, tenMinsAgo,
                        ConsignmentOutput.State.LIVE, null, null, "cdr"),
                new ConsignmentOutput("c2", "run2", "cdr", "dt=2026-11-19", "2026-11-19",
                        dir.resolve("cdr/19b.parquet").toString(), 50, 500, tenMinsAgo,
                        ConsignmentOutput.State.LIVE, null, null, "cdr"),
                new ConsignmentOutput("c3", "run3", "cdr", "dt=2026-11-20", "2026-11-20",
                        dir.resolve("cdr/20.parquet").toString(), 80, 800, tenMinsAgo,
                        ConsignmentOutput.State.LIVE, null, null, "cdr"),
                new ConsignmentOutput("c4", "run4", "cdr", "dt=2026-11-22", "2026-11-22",
                        dir.resolve("cdr/22.parquet").toString(), 40, 400, tenMinsAgo,
                        ConsignmentOutput.State.LIVE, null, null, "cdr")
        ));

        JobConfig jobCfg = JobConfig.fromMap(Map.of("job", Map.of(
                "name", "cdr_enrich",
                "type", "pipeline",
                "input_stream", "cdr",
                "pipeline", "cdr_enrich_flow",
                "schedule", Map.of(
                        "mode", "by_day",
                        "quiet", "5m",
                        "max_hold", "30m",
                        "order", "oldest_first",
                        "max_parallel", "1",
                        "rerun_on_reference_change", Map.of("within", "7d")
                )
        )));

        PipelineStore pStore = new PipelineStore(dir.resolve("flows"));
        pStore.write("cdr_enrich_flow", new PipelineGraph("cdr_enrich_flow", true, List.of(
                new PipelineNode("src", "source_store", Map.of("store", "cdr"), null),
                new PipelineNode("j1", "transform.join", Map.of(
                        "reference", "reference/dim_dial_digit",
                        "on", "prefix", "key", "dial_digit_key", "version_by", "CALL_DATE"), null)
        ), List.of(new PipelineEdge("src", PipelineRel.DATA, "j1"))));

        List<String> submittedDays = new ArrayList<>();
        java.util.concurrent.atomic.AtomicReference<DayReconciler> refHolder = new java.util.concurrent.atomic.AtomicReference<>();
        DayReconciler reconciler = new DayReconciler(
                () -> List.of(jobCfg),
                () -> List.of(refCfg),
                pStore,
                ZoneId.of("UTC"),
                null,
                new DayReconciler.RunSubmitter() {
                    @Override
                    public boolean submitDay(JobConfig cfg, String day, List<String> inputPaths,
                                             String ifVersionMissingOverride, boolean stale, String staleReason) {
                        submittedDays.add(day + ":" + inputPaths.size());
                        store.recordJobDayProgress(cfg.name(), day, inputPaths, "c_out_" + day,
                                "{dim_dial_digit=" + day + "}", "ENRICHED", stale, staleReason, "r_" + day);
                        if (refHolder.get() != null) refHolder.get().markDayFinished(cfg.name(), day, 10);
                        return true;
                    }

                    @Override
                    public int activeCount(String jobName) { return 0; }
                });
        refHolder.set(reconciler);

        // First reconcile: max_parallel=1 picks oldest ready day (2026-11-19 with 2 files coalesced!)
        reconciler.reconcileAll(Instant.now(), false);
        assertEquals(List.of("2026-11-19:2"), submittedDays);

        // Backlog snapshot shows 2026-11-20 READY and 2026-11-22 WAITING on dim_dial_digit
        List<DayReconciler.JobBacklog> snap = reconciler.backlogSnapshot();
        assertEquals(1, snap.size());
        DayReconciler.JobBacklog jb = snap.get(0);
        assertEquals(1, jb.readyCount());
        assertEquals(1, jb.waitingCount());

        // P4 Pause prevents automatic drain; Resume + Drain runs 2026-11-20
        assertTrue(reconciler.pauseJob("cdr_enrich"));
        assertEquals(0, reconciler.drainJob("cdr_enrich", true));
        assertTrue(reconciler.resumeJob("cdr_enrich"));
        assertEquals(1, reconciler.drainJob("cdr_enrich", true));
        assertEquals(List.of("2026-11-19:2", "2026-11-20:1"), submittedDays);

        // P3: Reference correction for 2026-11-20 marks 2026-11-20 stale and re-runs it
        reconciler.onConsignmentCommitted(new ConsignmentEvent(
                "dim_dial_digit", "r20_corr", "SUCCESS", List.of("ref_day=2026-11-20"), 10, 50, 0));
        assertEquals(List.of("2026-11-19:2", "2026-11-20:1", "2026-11-20:1"), submittedDays);

        // P4: Give up waiting on 2026-11-22
        assertTrue(reconciler.giveUpWaiting("cdr_enrich", "2026-11-22"));
        assertTrue(store.pendingDaysForJob("cdr_enrich", "cdr").isEmpty());
    }
}
