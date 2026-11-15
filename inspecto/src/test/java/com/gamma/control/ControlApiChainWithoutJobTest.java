package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import com.gamma.util.ToonHelper;
import org.junit.jupiter.api.DisplayName;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PIPELINE-CHAIN-NO-JOB-1, over real HTTP: a Stage-2 chain WITH a top-level {@code output_store:} that no enabled
 * {@code type: pipeline} Job runs saves with a WARNING naming the missing Job. The chain runs only at rest, from a
 * Job whose {@code pipeline_config:} names the Pipeline's file; the ingest lane runs none of it. Never a refusal:
 * the Job is a separate config, so refusing would make the order of authoring matter.
 *
 * <p>The fixture's file is {@code orders_pipeline.toon} for a Pipeline called {@code shop_orders}, as the shipped
 * samples do ({@code orders_pipeline.toon} for {@code STEP_DEDUP}): a Job is matched to the Pipeline by the
 * identity of the file it names, never by the file name.
 */
class ControlApiChainWithoutJobTest {

    private static final String CODE = "WARN_CHAIN_WITHOUT_PIPELINE_JOB";

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path pipe, String priorRoots)
            implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
            if (priorRoots != null) System.setProperty("assist.safety.roots", priorRoots);
            else System.clearProperty("assist.safety.roots");
        }
    }

    /** An inactive {@code shop_orders} Pipeline in {@code orders_pipeline.toon} (plus {@code extraTopLevel} keys). */
    private Ctx open(Path dir, String extraTopLevel) throws Exception {
        Path writeRoot = Files.createDirectories(dir.resolve("wr"));
        Files.writeString(writeRoot.resolve("shop_orders_schema.toon"), """
                raw:
                  name: shop_orders
                  format: CSV
                  fields[2]{name,selector,type}:
                    order_id,"0",VARCHAR
                    amount,"1",VARCHAR
                """);
        Path mini = PipelineConfigBatchTest.writePipeline(Files.createDirectories(dir.resolve("seed")), "", false);
        String miniSchema = mini.resolveSibling("mini_schema.toon").toString().replace('\\', '/');
        Path pipe = writeRoot.resolve("orders_pipeline.toon");
        Files.writeString(pipe, Files.readString(mini).replace("name: MINI_ETL", "name: shop_orders")
                .replace("active: false\n", "active: false\n" + extraTopLevel)
                .replace(miniSchema, "shop_orders_schema.toon"));
        String priorRoots = System.getProperty("assist.safety.roots");
        System.setProperty("assist.safety.roots", dir.toString());
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), pipe, priorRoots);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json")
                .method(method, BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    /** parse → the projection slot ({@code map}) → [a chain {@code transform.sql}] → sink. */
    private static String graph(Path dir, boolean active, boolean chain) {
        String d = dir.toString().replace('\\', '/') + "/data";
        String mid = chain
                ? """
                  {"id":"sql","type":"transform.sql","config":{"sql":"SELECT order_id, TRY_CAST(amount AS DOUBLE) AS amount FROM input"}},"""
                : "";
        String edges = chain
                ? """
                  {"from":"map","rel":"data","to":"sql"},{"from":"sql","rel":"data","to":"sink"}"""
                : """
                  {"from":"map","rel":"data","to":"sink"}""";
        return """
            {"active":%2$s,
             "nodes":[
              {"id":"acq","type":"acquisition","config":{"poll":"%1$s/inbox/shop_orders"}},
              {"id":"parse","type":"parser","config":{"schema_file":"shop_orders_schema.toon"}},
              {"id":"map","type":"transform.sql","config":{}},
              %3$s
              {"id":"sink","type":"sink.persistent","config":{"database":"%1$s/shop_orders/database"}}],
             "edges":[{"from":"acq","rel":"data","to":"parse"},{"from":"parse","rel":"data","to":"map"},%4$s]}"""
                .formatted(d, active, mid, edges);
    }

    /** A {@code type: pipeline} Job running {@code pipelineFile} (absolute, so its base is not in question). */
    private static String job(String name, Path pipelineFile, boolean enabled) {
        return job(name, pipelineFile.toAbsolutePath().toString().replace('\\', '/'), enabled);
    }

    /** A {@code type: pipeline} Job whose {@code pipeline_config:} is {@code pipelineConfig} as written. */
    private static String job(String name, String pipelineConfig, boolean enabled) {
        return """
                {"name":"%s","type":"pipeline","enabled":%s,"pipeline_config":"%s","on_pipeline":"shop_orders"}"""
                .formatted(name, enabled, pipelineConfig);
    }

    private static String findings(HttpResponse<String> r) throws Exception {
        return V1Body.of(r.body()).get("findings").toString();
    }

    @Test
    @DisplayName("an ACTIVE chain with an output_store: and no type: pipeline Job saves, warning that it never runs")
    void chainWithStoreAndNoJobWarns(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, "output_store: shop_orders_shaped\n")) {
            HttpResponse<String> r = send(c.port, "PUT", "/pipelines/shop_orders/graph", graph(dir, true, true));
            assertEquals(200, r.statusCode(), r.body());
            JsonNode out = V1Body.of(r.body());
            assertTrue(out.get("written").asBoolean(), "a warning never refuses: " + r.body());
            String f = findings(r);
            assertTrue(f.contains("\"code\":\"" + CODE + "\""), f);
            assertTrue(f.contains("\"fieldPath\":\"output_store\""), f);
            assertTrue(f.contains("no enabled type: pipeline Job runs it"), f);
            assertTrue(f.contains("add a type: pipeline Job that runs 'shop_orders'"), "the fix is named: " + f);
            assertFalse(f.contains("\"severity\":\"ERROR\""), f);
        }
    }

    @Test
    @DisplayName("no warning once an enabled type: pipeline Job names the Pipeline's file; a disabled one does not count")
    void noWarningOnceTheJobExists(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, "output_store: shop_orders_shaped\n")) {
            HttpResponse<String> off = send(c.port, "POST", "/jobs", job("shop_orders_off", c.pipe, false));
            assertEquals(200, off.statusCode(), off.body());
            HttpResponse<String> still = send(c.port, "PUT", "/pipelines/shop_orders/graph", graph(dir, true, true));
            assertEquals(200, still.statusCode(), still.body());
            assertTrue(findings(still).contains(CODE), "a disabled Job runs nothing: " + still.body());

            HttpResponse<String> on = send(c.port, "POST", "/jobs", job("shop_orders_rollup", c.pipe, true));
            assertEquals(200, on.statusCode(), on.body());
            HttpResponse<String> r = send(c.port, "PUT", "/pipelines/shop_orders/graph", graph(dir, true, true));
            assertEquals(200, r.statusCode(), r.body());
            assertFalse(r.body().contains(CODE), "the Job runs the chain (file orders_pipeline.toon, Pipeline "
                    + "shop_orders — matched by identity, not file name): " + r.body());
        }
    }

    @Test
    @DisplayName("an enabled type: pipeline Job running ANOTHER Pipeline's file leaves the warning")
    void aJobForAnotherPipelineDoesNotCount(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, "output_store: shop_orders_shaped\n")) {
            Path other = c.pipe.resolveSibling("returns_pipeline.toon");
            String content = Files.readString(c.pipe);
            assertTrue(content.contains("name: shop_orders"), content);
            Files.writeString(other, content.replace("name: shop_orders", "name: returns"));
            HttpResponse<String> on = send(c.port, "POST", "/jobs", job("returns_rollup", other, true));
            assertEquals(200, on.statusCode(), on.body());
            HttpResponse<String> r = send(c.port, "PUT", "/pipelines/shop_orders/graph", graph(dir, true, true));
            assertEquals(200, r.statusCode(), r.body());
            assertTrue(findings(r).contains(CODE), "the only Job runs 'returns', not 'shop_orders': " + r.body());
        }
    }

    @Test
    @DisplayName("a RELATIVE pipeline_config resolves from the runner's base (the config read root) and silences it")
    void aRelativePipelineConfigResolvesFromTheRunnersBase(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, "output_store: shop_orders_shaped\n")) {
            Path launch = Path.of("").toAbsolutePath();
            assertEquals(launch, com.gamma.pipeline.SpaceConfigRoot.currentConfigReadRoot(),
                    "the premise: the single-tenant read root is the launch dir, not the write root");
            String rel = launch.relativize(c.pipe.toAbsolutePath()).toString().replace('\\', '/');
            assertFalse(Path.of(rel).isAbsolute(), rel);
            assertFalse(Files.exists(c.pipe.getParent().resolve(rel)), "the value means nothing from the write root");
            HttpResponse<String> on = send(c.port, "POST", "/jobs", job("shop_orders_rollup", rel, true));
            assertEquals(200, on.statusCode(), on.body());
            HttpResponse<String> r = send(c.port, "PUT", "/pipelines/shop_orders/graph", graph(dir, true, true));
            assertEquals(200, r.statusCode(), r.body());
            assertFalse(r.body().contains(CODE), "the relative path names this Pipeline's file: " + r.body());
        }
    }

    @Test
    @DisplayName("a Job whose pipeline_config the runner would refuse (outside the jail) is no Job: the warning stays")
    void aJobOutsideTheJailDoesNotCount(@TempDir Path dir, @TempDir Path outside) throws Exception {
        try (Ctx c = open(dir, "output_store: shop_orders_shaped\n")) {
            Path copy = outside.resolve("orders_pipeline.toon");
            Files.copy(c.pipe, copy);   // the same Pipeline's identity, outside assist.safety.roots
            // A hand-edited Job file the save doors would refuse: registered directly, as a loaded one would be.
            c.svc.jobServiceOrCreate().upsertJob(new com.gamma.job.JobConfig("outside_rollup", "pipeline", null,
                    "shop_orders", true, false,
                    Map.of("pipeline_config", copy.toAbsolutePath().toString().replace('\\', '/')), null, null));
            HttpResponse<String> r = send(c.port, "PUT", "/pipelines/shop_orders/graph", graph(dir, true, true));
            assertEquals(200, r.statusCode(), r.body());
            assertTrue(findings(r).contains(CODE), "the runner refuses that path, so nothing runs the chain: " + r.body());
        }
    }

    @Test
    @DisplayName("no warning for a chain-less Pipeline with an output_store:, and none for a chain without a store")
    void chainlessPipelineIsNotJudged(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, "output_store: shop_orders_shaped\n")) {
            HttpResponse<String> r = send(c.port, "PUT", "/pipelines/shop_orders/graph", graph(dir, true, false));
            assertEquals(200, r.statusCode(), r.body());
            assertFalse(r.body().contains(CODE), r.body());
        }
        Path other = Files.createDirectories(dir.resolve("other"));
        try (Ctx c = open(other, "")) {
            HttpResponse<String> r = send(c.port, "PUT", "/pipelines/shop_orders/graph", graph(other, false, true));
            assertEquals(200, r.statusCode(), r.body());
            assertTrue(r.body().contains("WARN_CHAIN_WITHOUT_OUTPUT_STORE"), r.body());
            assertFalse(r.body().contains(CODE), "the store-less chain is the other finding's: " + r.body());
        }
    }

    @Test
    @DisplayName("/validate and an INACTIVE draft carry the same warning (a legacy summarize block counts as a chain)")
    void validateAndDraftWarnToo(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, "")) {
            String draft = """
                    {"type":"pipeline","config":{
                       "name":"rollup","active":false,"output_store":"rollup_out",
                       "dirs":{"poll":"in","database":"out"},
                       "processing":{"threads":1,"schema_file":"shop_orders_schema.toon",
                                     "summarize":{"group_by":["order_id"],"measures":["count"]}}}}""";
            HttpResponse<String> r = send(c.port, "POST", "/validate", draft);
            assertEquals(200, r.statusCode(), r.body());
            String f = findings(r);
            assertTrue(f.contains("\"code\":\"" + CODE + "\""), f);
            assertTrue(f.contains("runs 'rollup'"), f);
        }
    }

    /**
     * No shipped sample gets the warning where its Job exists: every {@code pipeline_config:} Job under
     * {@code spaces/} and {@code inspecto/examples/} names a file whose identity is the Pipeline the save judges.
     * The examples name {@code orders_pipeline.toon} for {@code STEP_DEDUP}, so a file-name rule would warn falsely.
     */
    @Test
    @DisplayName("every shipped type: pipeline Job resolves to its Pipeline by identity")
    void shippedJobsResolveToTheirPipelines() throws Exception {
        Path repo = Path.of("").toAbsolutePath().getParent();
        List<Path> jobs = new ArrayList<>();
        for (Path root : List.of(repo.resolve("spaces"), repo.resolve("inspecto/examples")))
            try (Stream<Path> s = Files.walk(root)) {
                s.filter(p -> p.getFileName().toString().endsWith("_job.toon")).forEach(jobs::add);
            }
        int checked = 0;
        boolean nameDiffersFromFile = false;
        String priorRoots = System.getProperty("assist.safety.roots");
        System.setProperty("assist.safety.roots", repo.toString());   // the runner's jail: the shipped tree
        try {
            for (Path jobFile : jobs) {
                Map<String, Object> raw = ToonHelper.decode(Files.readString(jobFile));
                if (!(raw.get("job") instanceof Map<?, ?> job) || !"pipeline".equals(job.get("type"))
                        || !(job.get("pipeline_config") instanceof String pc)) continue;
                // A Space's Job resolves against the Space config root; a sample's against its own directory.
                Path base = jobFile.getParent().getFileName().toString().equals("jobs")
                        ? jobFile.getParent().getParent() : jobFile.getParent();
                Path pipelineFile = base.resolve(pc);
                String expected = ConfigReadRoutes.pipelineIdOf(ToonHelper.decode(Files.readString(pipelineFile)), null);
                assertNotNull(expected, pipelineFile.toString());
                assertEquals(expected, com.gamma.job.PipelineConfigIds.registeredIdOf(pc, base), jobFile.toString());
                if (!pipelineFile.getFileName().toString().equalsIgnoreCase(expected + "_pipeline.toon"))
                    nameDiffersFromFile = true;
                checked++;
            }
        } finally {
            if (priorRoots != null) System.setProperty("assist.safety.roots", priorRoots);
            else System.clearProperty("assist.safety.roots");
        }
        assertTrue(checked >= 25, "found " + checked + " shipped pipeline_config Jobs");
        assertTrue(nameDiffersFromFile, "the premise: some shipped Pipeline is not named after its file");
    }
}
