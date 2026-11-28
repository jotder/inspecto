package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.consignment.ConsignmentOutput;
import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.consignment.ConsignmentSource;
import com.gamma.consignment.DbConsignmentOutputStore;
import com.gamma.etl.ConsignmentEventBus;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.job.ArtifactRecorder;
import com.gamma.job.JobConfig;
import com.gamma.job.JobContext;
import com.gamma.job.JobResult;
import com.gamma.job.JobType;
import com.gamma.job.PipelineJobRunner;
import com.gamma.job.TriggerInfo;
import com.gamma.service.CollectorService;
import com.gamma.signal.SignalEmitter;
import com.gamma.spi.http.ApiContext;
import com.gamma.util.RunLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
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
 * SPLIT-JOB-PIPELINE-DROPS-PROCESSING-1: {@code POST /pipelines/{id}/split-job-pipeline} carries a Pipeline's Stage-2
 * chain into the Job Pipeline as its onboarding Pipeline's at-rest run executes it — the legacy
 * {@code processing.join / dedup / summarize / profile} blocks become {@code steps:} entries, a join's reference
 * stays as authored, the Stage-1 {@code csv_settings.where} stays on the onboarding Pipeline — and refuses a chain
 * that cannot move without changing what it does. The preview shows exactly what the split writes.
 */
class JobPipelineSplitChainTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, String priorRoots) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
            if (priorRoots != null) System.setProperty("assist.safety.roots", priorRoots);
            else System.clearProperty("assist.safety.roots");
        }
    }

    private Ctx start(Path dir, Path writeRoot, Path pipe) throws Exception {
        String priorRoots = System.getProperty("assist.safety.roots");
        System.setProperty("assist.safety.roots", dir.toString());
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), priorRoots);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> split(int port, boolean preview) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/api/v1/pipelines/cdr/split-job-pipeline"))
                .POST(BodyPublishers.ofString("{\"preview\":" + preview + "}")).build(), BodyHandlers.ofString());
    }

    @Test
    void legacyStageTwoBlocksMoveIntoTheJobPipelineAndTheStageOneFilterStays(@TempDir Path dir) throws Exception {
        Path writeRoot = Files.createDirectories(dir.resolve("config"));
        Path pipe = seedCdr(dir, writeRoot, """
                  join:
                    reference: refs/dim_dial_digit
                    on[1]: dial_prefix
                  dedup:
                    keys[1]: dial_prefix
                    order_by: duration DESC
                  summarize:
                    group_by[1]: dial_prefix
                    measures[1]: count
                  profile:
                    columns[1]: duration
                """, "    where: \"duration <> ''\"\n");
        Path jobPipe = writeRoot.resolve("cdr_enriched/cdr_enriched_pipeline.toon");

        try (Ctx ctx = start(dir, writeRoot, pipe)) {
            HttpResponse<String> prev = split(ctx.port(), true);
            assertEquals(200, prev.statusCode(), prev.body());
            JsonNode pj = ApiContext.JSON.readTree(prev.body()).path("data");
            HttpResponse<String> apply = split(ctx.port(), false);
            assertEquals(200, apply.statusCode(), apply.body());
            JsonNode aj = ApiContext.JSON.readTree(apply.body()).path("data");

            // the preview shows exactly what the split writes
            assertEquals(pj.path("onboardingPipeline").path("after").asText(), Files.readString(pipe));
            assertEquals(pj.path("jobPipeline").path("after").asText(), Files.readString(jobPipe));
            assertEquals(pj.path("onboardingPipeline").path("after").asText(),
                    aj.path("onboardingPipeline").path("after").asText());

            PipelineConfig onboarding = PipelineConfig.fromMap(
                    com.gamma.config.io.ConfigLoader.filesystem().decode(pipe.toString()), pipe.getParent());
            assertNull(onboarding.join(), "processing.join moved off the onboarding Pipeline");
            assertNull(onboarding.dedup(), "processing.dedup moved off the onboarding Pipeline");
            assertNull(onboarding.summarize(), "processing.summarize moved off the onboarding Pipeline");
            assertNull(onboarding.profile(), "processing.profile moved off the onboarding Pipeline");
            assertEquals("duration <> ''", onboarding.csv().where(), "the Stage-1 filter stays at ingest");

            PipelineConfig job = PipelineConfig.fromMap(
                    com.gamma.config.io.ConfigLoader.filesystem().decode(jobPipe.toString()), jobPipe.getParent());
            assertTrue(job.isJobPipeline());
            assertEquals("PARQUET", job.output().format(), "the output Dataset keeps the at-rest run's Parquet, not "
                    + "the onboarding Pipeline's landed format (CSV here)");
            assertEquals(List.of("join", "dedup", "summarize", "profile"),
                    job.steps().stream().map(PipelineConfig.Step::kind).toList(),
                    "the four Stage-2 blocks, in the order the at-rest run executes them, and no Stage-1 filter");
            assertEquals(List.of("dial_prefix"), job.steps().get(1).config().get("keys"));
            assertEquals("duration DESC", job.steps().get(1).config().get("order_by"));
            assertEquals(List.of("count"), job.steps().get(2).config().get("measures"));
            assertEquals(List.of("duration"), job.steps().get(3).config().get("columns"));
            assertTrue(Files.readString(jobPipe).contains("reference: refs/dim_dial_digit"),
                    "the join's reference is written as authored, not as this machine's absolute path:\n"
                            + Files.readString(jobPipe));
        }
    }

    @Test
    void theTwoPipelinesTogetherProduceTheRowsTheOriginalDid(@TempDir Path dir) throws Exception {
        Path writeRoot = Files.createDirectories(dir.resolve("config"));
        Path pipe = seedCdr(dir, writeRoot, """
                  dedup:
                    keys[1]: dial_prefix
                    order_by: duration DESC
                """, "");
        String landedRows = """
                SELECT '24991' AS dial_prefix, '10' AS duration UNION ALL
                SELECT '24991' AS dial_prefix, '30' AS duration UNION ALL
                SELECT '24992' AS dial_prefix, '20' AS duration UNION ALL
                SELECT '24992' AS dial_prefix, '20' AS duration UNION ALL
                SELECT '24993' AS dial_prefix, '5'  AS duration
                """;

        // The Space's data root, as a Job runs by default (SpaceRoot.dataDir() = database): the original's at-rest
        // run writes <data_dir>/cdr_enriched, the Job Pipeline its dirs.database database/cdr_enriched — one folder.
        Path dataDir = dir.resolve("database");

        // Before: the onboarding Pipeline's own at-rest run (PipelineLift.stageTwo over the landed store).
        List<String> before = runAtRest(dataDir, pipe, landedRows);
        assertEquals(List.of("24991|30", "24992|20", "24993|5"), before, "the original dedups");
        try (var gone = Files.walk(dataDir.resolve("cdr_enriched"))) {
            gone.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }

        try (Ctx ctx = start(dir, writeRoot, pipe)) {
            HttpResponse<String> apply = split(ctx.port(), false);
            assertEquals(200, apply.statusCode(), apply.body());
        }
        // After: the onboarding Pipeline lands the same records (its ingest is unchanged) and the Job Pipeline
        // runs over them.
        List<String> after = runAtRest(dataDir,
                writeRoot.resolve("cdr_enriched/cdr_enriched_pipeline.toon"), landedRows);
        assertEquals(before, after, "the split Pipelines produce the original's rows, dedup still applied");
    }

    @Test
    void aChainThatCannotMoveRefusesTheSplitAndWritesNothing(@TempDir Path dir) throws Exception {
        Path writeRoot = Files.createDirectories(dir.resolve("config"));
        Path pipe = seedCdr(dir, writeRoot, """
                  dedup:
                    keys[1]: dial_prefix
                  disabled_steps[1]: dedup
                """, "");
        String before = Files.readString(pipe);
        try (Ctx ctx = start(dir, writeRoot, pipe)) {
            for (boolean preview : new boolean[]{true, false}) {
                HttpResponse<String> r = split(ctx.port(), preview);
                assertEquals(422, r.statusCode(), r.body());
                JsonNode f = ApiContext.JSON.readTree(r.body()).path("error").path("details").path("findings").path(0);
                assertEquals(PipelineGraphRoutes.ERR_SPLIT_CHAIN_NOT_MOVABLE, f.path("code").asText(), r.body());
                assertEquals("processing.disabled_steps", f.path("fieldPath").asText(), r.body());
            }
            assertEquals(before, Files.readString(pipe), "the onboarding Pipeline is untouched");
            assertFalse(Files.exists(writeRoot.resolve("cdr_enriched")), "no Job Pipeline or Job written");
        }

        Path writeRoot2 = Files.createDirectories(dir.resolve("other/config"));
        Path routed = seedCdr(dir.resolve("other"), writeRoot2, "", "", """
                route:
                  mode: case
                  default: one
                  branches[1]{key,where}:
                    one,"dial_prefix = '1'"
                """);
        try (Ctx ctx = start(dir.resolve("other"), writeRoot2, routed)) {
            HttpResponse<String> r = split(ctx.port(), true);
            assertEquals(422, r.statusCode(), r.body());
            JsonNode f = ApiContext.JSON.readTree(r.body()).path("error").path("details").path("findings").path(0);
            assertEquals(PipelineGraphRoutes.ERR_SPLIT_CHAIN_NOT_MOVABLE, f.path("code").asText(), r.body());
            assertEquals("route", f.path("fieldPath").asText(), r.body());
        }

        // An authored filter with a pre-parse key: the at-rest run refuses it, so the split must too.
        Path writeRoot3 = Files.createDirectories(dir.resolve("third/config"));
        Path preParse = seedCdr(dir.resolve("third"), writeRoot3, "", "", """
                steps[1]:
                  - filter:
                      where: "duration > 0"
                      include_prefixes[1]: "2499"
                """);
        try (Ctx ctx = start(dir.resolve("third"), writeRoot3, preParse)) {
            for (boolean preview : new boolean[]{true, false}) {
                HttpResponse<String> r = split(ctx.port(), preview);
                assertEquals(422, r.statusCode(), r.body());
                JsonNode f = ApiContext.JSON.readTree(r.body()).path("error").path("details").path("findings").path(0);
                assertEquals(PipelineGraphRoutes.ERR_SPLIT_CHAIN_NOT_MOVABLE, f.path("code").asText(), r.body());
                assertEquals("steps", f.path("fieldPath").asText(), r.body());
            }
            assertFalse(Files.exists(writeRoot3.resolve("cdr_enriched")), "no Job Pipeline or Job written");
        }
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────────

    private Path seedCdr(Path dir, Path writeRoot, String processingBlocks, String csvWhere) throws Exception {
        return seedCdr(dir, writeRoot, processingBlocks, csvWhere, "");
    }

    /** A registered legacy onboarding Pipeline {@code cdr} (no {@code steps:}) with {@code output_store: cdr_enriched}. */
    private Path seedCdr(Path dir, Path writeRoot, String processingBlocks, String csvWhere, String topLevel)
            throws Exception {
        Path cdrDir = Files.createDirectories(writeRoot.resolve("cdr"));
        Files.writeString(cdrDir.resolve("cdr_schema.toon"), """
                raw:
                  name: cdr
                  format: CSV
                  fields[2]{name,selector,type}:
                    dial_prefix,"0",VARCHAR
                    duration,"1",VARCHAR
                """);
        Path mini = PipelineConfigBatchTest.writePipeline(Files.createDirectories(dir.resolve("seed")), "", false);
        String miniSchema = mini.resolveSibling("mini_schema.toon").toString().replace('\\', '/');
        Path pipe = cdrDir.resolve("cdr_pipeline.toon");
        String text = Files.readString(mini)
                .replace("name: MINI_ETL", "name: cdr")
                .replace("active: false\n", "active: false\noutput_store: cdr_enriched\n" + topLevel)
                .replace(miniSchema, "cdr_schema.toon")
                .replace("  csv_settings:\n", processingBlocks + "  csv_settings:\n" + csvWhere);
        Files.writeString(pipe, text);
        return pipe;
    }

    /** Runs {@code pipelineFile}'s at-rest chain over a landed {@code cdr} store holding {@code rows}; the sorted output. */
    private static List<String> runAtRest(Path dataDir, Path pipelineFile, String rows) throws Exception {
        Path cdrFile = Files.createDirectories(dataDir.resolve("cdr")).resolve("part-0.parquet");
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (" + rows + ") TO '" + cdrFile.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        }
        try (DbConsignmentOutputStore store = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            store.record(List.of(new ConsignmentOutput("cdr-batch-1", "run-0", "cdr", "", null, cdrFile.toString(),
                    5, 200, "2026-11-25T00:00:00Z", ConsignmentOutput.State.LIVE)));
            ConsignmentOutputStores.use(store);
            JobContext ctx = new JobContext() {
                @Override public String runId() { return "run-1"; }
                @Override public String spaceId() { return "default"; }
                @Override public TriggerInfo trigger() { return null; }
                @Override public Map<String, String> config() { return Map.of(); }
                @Override public Map<String, String> params() { return Map.of(); }
                @Override public RunLog log() { return null; }
                @Override public SignalEmitter signals() { return null; }
                @Override public ArtifactRecorder artifacts() { return null; }
                @Override public void readConsignments(List<ConsignmentSource> sources) { }
            };
            JobConfig jobCfg = new JobConfig("cdr_at_rest", JobType.PIPELINE, null, null, true, false,
                    Map.of("pipeline_config", pipelineFile.toString(), "data_dir", dataDir.toString()));
            JobResult res = new PipelineJobRunner(jobCfg, new ConsignmentEventBus(), null, dataDir.toString(),
                    Files.createDirectories(dataDir.resolve("audit")).toString(), null, null, List::of).run(ctx);
            assertTrue(res.success(), res.message());
        } finally {
            ConsignmentOutputStores.use(null);
        }
        List<String> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT dial_prefix, duration FROM read_parquet('"
                     + dataDir.resolve("cdr_enriched").toString().replace('\\', '/')
                     + "/**/*.parquet') ORDER BY dial_prefix")) {
            while (rs.next()) out.add(rs.getString(1) + "|" + rs.getString(2));
        }
        return out;
    }
}
