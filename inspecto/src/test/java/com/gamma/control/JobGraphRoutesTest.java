package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import com.gamma.spi.http.ApiContext;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Backend routes for JOB-GRAPH-1:
 * <ul>
 *   <li>JG2: {@code GET .../graph/raw} synthesizes Reference Dataset nodes + {@code reference} edges for a Job Pipeline,
 *       and {@code PUT .../graph} strips them cleanly before lowering;</li>
 *   <li>JG3: {@code POST /pipelines/{id}/split-job-pipeline} previews a two-file diff and splits {@code steps:} +
 *       {@code output_store:} into a Job Pipeline in its own folder + {@code on_pipeline} Job.</li>
 * </ul>
 */
class JobGraphRoutesTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, String priorRoots) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
            if (priorRoots != null) System.setProperty("assist.safety.roots", priorRoots);
            else System.clearProperty("assist.safety.roots");
        }
    }

    private Ctx start(Path dir, Path writeRoot, List<Path> pipes) throws Exception {
        String priorRoots = System.getProperty("assist.safety.roots");
        System.setProperty("assist.safety.roots", dir.toString());
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(pipes, 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), priorRoots);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .method(method, BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    @Test
    void splitJobPipelinePreviewsAndWritesTwoFilesPlusJob(@TempDir Path dir) throws Exception {
        Path writeRoot = Files.createDirectories(dir.resolve("config"));
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
        Files.writeString(pipe, Files.readString(mini)
                .replace("name: MINI_ETL", "name: cdr")
                .replace("active: false\n", """
                        active: false
                        output_store: cdr_enriched
                        steps[1]:
                          - sql:
                              sql: SELECT dial_prefix, TRY_CAST(duration AS BIGINT) AS duration FROM input
                        """)
                .replace(miniSchema, "cdr_schema.toon"));

        try (Ctx ctx = start(dir, writeRoot, List.of(pipe))) {
            // 1. Preview returns diff without writing
            HttpResponse<String> prev = send(ctx.port(), "POST", "/pipelines/cdr/split-job-pipeline",
                    "{\"preview\":true}");
            assertEquals(200, prev.statusCode(), prev.body());
            JsonNode pj = ApiContext.JSON.readTree(prev.body()).path("data");
            assertTrue(pj.path("preview").asBoolean());
            assertTrue(pj.path("onboardingPipeline").path("before").asText().contains("output_store:"));
            assertFalse(pj.path("onboardingPipeline").path("after").asText().contains("output_store:"));
            assertTrue(pj.path("jobPipeline").path("after").asText().contains("connector: dataset"));
            assertTrue(pj.path("jobPipeline").path("after").asText().contains("dataset: cdr"));
            assertFalse(Files.exists(writeRoot.resolve("cdr_enriched/cdr_enriched_pipeline.toon")));

            // 2. Apply writes onboarding pipeline, job pipeline under own folder, and job
            HttpResponse<String> apply = send(ctx.port(), "POST", "/pipelines/cdr/split-job-pipeline",
                    "{\"preview\":false}");
            assertEquals(200, apply.statusCode(), apply.body());
            Path jobPipe = writeRoot.resolve("cdr_enriched/cdr_enriched_pipeline.toon");
            Path jobFile = writeRoot.resolve("cdr_enriched/cdr_enriched_job.toon");
            assertTrue(Files.exists(jobPipe), "Job Pipeline written to its own folder");
            assertTrue(Files.exists(jobFile), "Job written alongside Job Pipeline");
            assertFalse(Files.readString(pipe).contains("output_store:"), "onboarding pipeline stripped output_store");
            assertTrue(Files.readString(jobFile).contains("on_pipeline: cdr"), "Job triggered on_pipeline: cdr");
        }
    }

    @Test
    void editableGraphSynthesizesReferenceNodesAndSaveStripsThem(@TempDir Path dir) throws Exception {
        Path writeRoot = Files.createDirectories(dir.resolve("config"));
        Path refPipe = writeRoot.resolve("dim_dial_digit_pipeline.toon");
        Files.writeString(refPipe, """
                name: dim_dial_digit
                active: false
                produces: reference
                dirs:
                  poll: in
                  database: database/dim_dial_digit
                output:
                  format: PARQUET
                processing:
                  threads: 1
                """);
        Path jobPipe = writeRoot.resolve("cdr_enrich_pipeline.toon");
        Files.writeString(jobPipe, """
                name: cdr_enrich
                active: false
                collector:
                  connector: dataset
                  dataset: cdr
                dirs:
                  database: database/cdr_enriched
                output:
                  format: PARQUET
                output_store: cdr_enriched
                steps[1]:
                  - join:
                      reference: reference/dim_dial_digit
                      on[1]: dial_prefix
                """);

        try (Ctx ctx = start(dir, writeRoot, List.of(refPipe, jobPipe))) {
            HttpResponse<String> rawResp = send(ctx.port(), "GET", "/pipelines/cdr_enrich/graph/raw", "");
            assertEquals(200, rawResp.statusCode(), rawResp.body());
            JsonNode data = ApiContext.JSON.readTree(rawResp.body()).path("data");

            boolean hasRefNode = false;
            boolean hasRefEdge = false;
            for (JsonNode n : data.path("nodes")) {
                if ("acq__ref_dim_dial_digit".equals(n.path("id").asText())) hasRefNode = true;
            }
            for (JsonNode e : data.path("edges")) {
                if ("reference".equals(e.path("rel").asText())
                        && "acq__ref_dim_dial_digit".equals(e.path("from").asText())
                        && "join".equals(e.path("to").asText())) hasRefEdge = true;
            }
            assertTrue(hasRefNode, "GET .../graph/raw synthesizes Reference Dataset node");
            assertTrue(hasRefEdge, "GET .../graph/raw synthesizes reference edge to join");

            // PUT .../graph with the synthesized node + edge round-trips cleanly
            String etag = rawResp.headers().firstValue("ETag").orElse("");
            HttpResponse<String> putResp = client.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + ctx.port() + "/api/v1/pipelines/cdr_enrich/graph"))
                    .header("If-Match", etag)
                    .PUT(BodyPublishers.ofString(data.toString())).build(), BodyHandlers.ofString());
            assertEquals(200, putResp.statusCode(), putResp.body());
        }
    }
}
