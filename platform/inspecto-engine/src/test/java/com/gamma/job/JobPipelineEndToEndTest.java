package com.gamma.job;

import com.gamma.config.io.ConfigLoader;
import com.gamma.consignment.ConsignmentOutput;
import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.consignment.ConsignmentSource;
import com.gamma.consignment.DbConsignmentOutputStore;
import com.gamma.etl.ConsignmentEventBus;
import com.gamma.etl.PipelineConfig;
import com.gamma.pipeline.PipelineCodec;
import com.gamma.pipeline.PipelineEditable;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineLift;
import com.gamma.pipeline.PipelineRel;
import com.gamma.pipeline.PipelineStores;
import com.gamma.signal.SignalEmitter;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.RunLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>JG0 — Job Pipeline end-to-end spike.</b> Confirms that a {@code *_pipeline.toon} with
 * {@code collector.connector: dataset} (no parser/schema):
 * <ol>
 *   <li>lifts cleanly via {@link PipelineLift#lift} and {@link PipelineLift#stageTwo} (no parse/map nodes);</li>
 *   <li>round-trips through {@link PipelineEditable#toMap} → {@link PipelineEditable#lower} (strict mode);</li>
 *   <li>runs end-to-end via {@link PipelineJobRunner} on Zain-shaped synthetic CDR + two Reference Datasets;</li>
 *   <li>records which Consignments it read from the input Dataset via {@link JobContext#readConsignments}.</li>
 * </ol>
 */
class JobPipelineEndToEndTest {

    @TempDir
    Path tmp;

    @Test
    void jobPipelineRunsZainShapedJoinChainAndRecordsConsignments() throws Exception {
        Path dataDir = Files.createDirectories(tmp.resolve("data"));
        Path auditDir = Files.createDirectories(tmp.resolve("audit"));
        Path configDir = Files.createDirectories(tmp.resolve("config"));

        // 1. Seed synthetic Zain-shaped CDR store + two Reference Datasets (dim_dial_digit, dim_service_range)
        Path cdrFile = Files.createDirectories(dataDir.resolve("cdr")).resolve("part-0.parquet");
        seedParquet(cdrFile, """
                SELECT '24991' AS dial_prefix, 'SVC_VOICE' AS service_code, 42 AS duration_sec
                UNION ALL
                SELECT '24992' AS dial_prefix, 'SVC_DATA' AS service_code, 108 AS duration_sec
                """);

        Path dialDir = Files.createDirectories(dataDir.resolve("dim_dial_digit"));
        seedParquet(dialDir.resolve("dim.parquet"), """
                SELECT '24991' AS dial_prefix, 'Zain Sudan' AS operator_name
                UNION ALL
                SELECT '24992' AS dial_prefix, 'MTN Sudan' AS operator_name
                """);
        Path svcDir = Files.createDirectories(dataDir.resolve("dim_service_range"));
        seedParquet(svcDir.resolve("dim.parquet"), """
                SELECT 'SVC_VOICE' AS service_code, 'Voice' AS service_family
                UNION ALL
                SELECT 'SVC_DATA' AS service_code, 'Data' AS service_family
                """);

        PipelineConfig dialRefCfg = writeRefPipeline(configDir, "dim_dial_digit", dialDir);
        PipelineConfig svcRefCfg = writeRefPipeline(configDir, "dim_service_range", svcDir);

        // 2. Author the Job Pipeline file: dataset input -> join -> join -> cdr_enriched
        Path jobPipePath = configDir.resolve("cdr_enrich_pipeline.toon");
        Files.writeString(jobPipePath, """
                name: cdr_enrich
                active: false
                collector:
                  connector: dataset
                  dataset: cdr
                dirs:
                  database: %s/cdr_enriched
                output:
                  format: PARQUET
                output_store: cdr_enriched
                steps[2]:
                  - join:
                      reference: reference/dim_dial_digit
                      on[1]: dial_prefix
                  - join:
                      reference: reference/dim_service_range
                      on[1]: service_code
                """.formatted(dataDir.toString().replace("\\", "/")));

        // 3. Verify lift + editable round-trip (strict=true)
        PipelineConfig loaded = PipelineConfig.load(jobPipePath.toString());
        assertTrue(loaded.isJobPipeline(), "dataset connector without parser/schema is a Job Pipeline");
        PipelineGraph lifted = PipelineLift.lift(loaded);
        assertNull(lifted.byId().get("parse"), "Job Pipeline has no parser node");
        assertNull(lifted.byId().get("map"), "Job Pipeline has no map node");
        assertEquals(Set.of("cdr"), PipelineStores.consumed(lifted));
        assertEquals(Set.of("cdr_enriched"), PipelineStores.produced(lifted));
        assertTrue(hasEdge(lifted, "acq", PipelineRel.DATA, "join"));
        assertTrue(hasEdge(lifted, "join", PipelineRel.DATA, "join__s1"));
        assertTrue(hasEdge(lifted, "join__s1", PipelineRel.DATA, "sink"));

        Map<String, Object> raw = ConfigLoader.filesystem().decode(jobPipePath.toString());
        PipelineGraph editable = PipelineCodec.fromMap(PipelineEditable.toMap(loaded, raw));
        Map<String, Object> lowered = PipelineEditable.lower(editable, raw, true);
        PipelineConfig reloaded = PipelineConfig.fromMap(lowered, configDir);
        assertTrue(reloaded.isJobPipeline());
        assertEquals("cdr", reloaded.collector().dataset());
        assertEquals("cdr_enriched", reloaded.outputStore());
        assertEquals(2, reloaded.steps().size());

        // 4. Register cdr Consignment in DbConsignmentOutputStore and run PipelineJobRunner
        try (DbConsignmentOutputStore outputStore = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            outputStore.record(List.of(new ConsignmentOutput(
                    "cdr-batch-1", "run-0", "cdr", "", null, cdrFile.toString(),
                    2, 200, "2026-11-25T00:00:00Z", ConsignmentOutput.State.LIVE)));
            ConsignmentOutputStores.use(outputStore);

            List<ConsignmentSource> recordedSources = new ArrayList<>();
            JobContext ctx = new JobContext() {
                @Override public String runId() { return "run-1"; }
                @Override public String spaceId() { return "default"; }
                @Override public TriggerInfo trigger() { return null; }
                @Override public Map<String, String> config() { return Map.of(); }
                @Override public Map<String, String> params() { return Map.of(); }
                @Override public RunLog log() { return null; }
                @Override public SignalEmitter signals() { return null; }
                @Override public ArtifactRecorder artifacts() { return null; }
                @Override public void readConsignments(List<ConsignmentSource> sources) {
                    if (sources != null) recordedSources.addAll(sources);
                }
            };

            JobConfig jobCfg = new JobConfig("cdr_enrich_job", JobType.PIPELINE, null, null, true, false,
                    Map.of("pipeline_config", jobPipePath.toString(), "data_dir", dataDir.toString()));
            JobResult res = new PipelineJobRunner(jobCfg, new ConsignmentEventBus(), null,
                    dataDir.toString(), auditDir.toString(), null, null,
                    () -> List.of(dialRefCfg, svcRefCfg)).run(ctx);

            assertTrue(res.success(), res.message());
            assertFalse(recordedSources.isEmpty(), "Job Pipeline run must record read Consignments");
            assertEquals("cdr-batch-1", recordedSources.get(0).consignmentId());

            // Verify joined output in cdr_enriched
            List<String> operators = new ArrayList<>();
            List<String> families = new ArrayList<>();
            try (Connection c = java.sql.DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT operator_name, service_family FROM read_parquet('"
                         + dataDir.resolve("cdr_enriched").toString().replace("\\", "/")
                         + "/**/*.parquet') ORDER BY dial_prefix")) {
                while (rs.next()) {
                    operators.add(rs.getString(1));
                    families.add(rs.getString(2));
                }
            }
            assertEquals(List.of("Zain Sudan", "MTN Sudan"), operators);
            assertEquals(List.of("Voice", "Data"), families);
        } finally {
            ConsignmentOutputStores.use(null);
        }
    }

    private static PipelineConfig writeRefPipeline(Path dir, String name, Path dbDir) throws Exception {
        Path p = dir.resolve(name + "_pipeline.toon");
        Files.writeString(p, """
                name: %s
                active: false
                produces: reference
                dirs:
                  poll: in
                  database: %s
                output:
                  format: PARQUET
                processing:
                  threads: 1
                """.formatted(name, dbDir.toString().replace("\\", "/")));
        return PipelineConfig.load(p.toString());
    }

    private static void seedParquet(Path target, String selectSql) throws Exception {
        try (Connection c = java.sql.DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (" + selectSql + ") TO '" + target.toString().replace("\\", "/") + "' (FORMAT PARQUET)");
        }
    }

    private static boolean hasEdge(PipelineGraph g, String from, String rel, String to) {
        return g.edges().stream().anyMatch(e -> e.from().equals(from) && e.rel().equals(rel) && e.to().equals(to));
    }
}
