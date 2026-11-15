package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TRANSFORMER-TWO-NODES-1 (C), over real HTTP: a Stage-2 chain with no top-level {@code output_store:} is
 * refused at the save, not only at registration.
 *
 * <p>Found rebuilding the football pilot (2026-11-13): a Record Transformer added from the palette lowers to a
 * chain {@code steps: - sql:}. A chain runs only at rest ({@code PipelineLift.stageTwo}, from a
 * {@code type: pipeline} Job), writing the {@code output_store:} the file names; with none, nothing runs it.
 * {@code PipelineConfig.requireRunnable} refuses to ARM such a file, and an ACTIVE save was already refused by
 * {@code ConfigSpecs}' {@code stage-two-blocks-require-output-store} rule — but with no code and without naming
 * the Step, and an INACTIVE draft saved with no word at all. The save now names each Step, with a stable code
 * and the arming split: ACTIVE ⇒ 422 and nothing written, an INACTIVE draft saves with a warning. The
 * projection slot (node {@code map}) is not a chain Step and is never judged here.
 */
class ControlApiChainOutputStoreArmingTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, String priorRoots) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
            if (priorRoots != null) System.setProperty("assist.safety.roots", priorRoots);
            else System.clearProperty("assist.safety.roots");
        }
    }

    /** An inactive {@code shop_orders} draft in the write root (plus {@code extraTopLevel} keys), and its schema. */
    private Ctx open(Path dir, Path writeRoot, String extraTopLevel) throws Exception {
        Files.createDirectories(writeRoot);
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
        Path pipe = writeRoot.resolve("shop_orders_pipeline.toon");
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
            return new Ctx(svc, api, api.port(), priorRoots);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .method(method, BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    /**
     * The pilot's canvas: parse → the projection slot ({@code map}) → a palette Record Transformer (a chain
     * {@code transform.sql}, id {@code sql}) → sink. {@code chain=false} drops the palette Step.
     */
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

    @Test
    @DisplayName("an ACTIVE chain Step with no output_store: is refused at the GRAPH save — 422, the Step named, nothing written")
    void activeChainWithoutOutputStoreIsRefused(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        try (Ctx c = open(dir, wr, "")) {
            String before = Files.readString(wr.resolve("shop_orders_pipeline.toon"));
            HttpResponse<String> r = send(c.port, "PUT", "/pipelines/shop_orders/graph", graph(dir, true, true));
            assertEquals(422, r.statusCode(), r.body());
            JsonNode out = V1Body.envelope(r.body()).get("error").get("details");
            assertFalse(out.get("written").asBoolean(), r.body());
            String findings = out.get("findings").toString();
            assertTrue(findings.contains("\"code\":\"ERR_CHAIN_WITHOUT_OUTPUT_STORE\""), findings);
            assertTrue(findings.contains("\"fieldPath\":\"steps[0].sql\""), findings);
            assertTrue(findings.contains("Step 'sql' (steps[0]) never runs"), findings);
            assertTrue(findings.contains("output_store:"), findings);
            assertEquals(before, Files.readString(wr.resolve("shop_orders_pipeline.toon")), "a refused save writes nothing");
        }
    }

    @Test
    @DisplayName("an INACTIVE draft with the same chain saves, warning when it will refuse")
    void inactiveChainWithoutOutputStoreWarnsButSaves(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        try (Ctx c = open(dir, wr, "")) {
            HttpResponse<String> r = send(c.port, "PUT", "/pipelines/shop_orders/graph", graph(dir, false, true));
            assertEquals(200, r.statusCode(), r.body());
            JsonNode out = V1Body.of(r.body());
            assertTrue(out.get("written").asBoolean(), r.body());
            String findings = out.get("findings").toString();
            assertTrue(findings.contains("\"code\":\"WARN_CHAIN_WITHOUT_OUTPUT_STORE\""), findings);
            assertFalse(findings.contains("\"severity\":\"ERROR\""), findings);
            assertTrue(findings.contains("only once it is activated"), findings);
            assertTrue(Files.readString(wr.resolve("shop_orders_pipeline.toon")).contains("steps"),
                    "the palette Step lowers to a steps: chain");

            // Activating the saved draft is the door the pilot used: it trips the same refusal.
            HttpResponse<String> p = send(c.port, "POST", "/config/patch",
                    "{\"type\":\"pipeline\",\"name\":\"shop_orders\",\"patch\":{\"active\":true}}");
            assertEquals(422, p.statusCode(), p.body());
            assertTrue(p.body().contains("ERR_CHAIN_WITHOUT_OUTPUT_STORE"), p.body());
        }
    }

    @Test
    @DisplayName("the same chain WITH an output_store: saves active — the at-rest Job has somewhere to write")
    void chainWithOutputStoreSavesActive(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        try (Ctx c = open(dir, wr, "output_store: shop_orders_shaped\n")) {
            HttpResponse<String> r = send(c.port, "PUT", "/pipelines/shop_orders/graph", graph(dir, true, true));
            assertEquals(200, r.statusCode(), r.body());
            assertFalse(r.body().contains("CHAIN_WITHOUT_OUTPUT_STORE"), r.body());
            assertTrue(Files.readString(wr.resolve("shop_orders_pipeline.toon")).contains("output_store"),
                    "the graph save keeps the file's output_store:");
        }
    }

    @Test
    @DisplayName("the projection slot alone is never judged: no chain, no output_store:, active, 200")
    void projectionSlotAloneSavesActive(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        try (Ctx c = open(dir, wr, "")) {
            HttpResponse<String> r = send(c.port, "PUT", "/pipelines/shop_orders/graph", graph(dir, true, false));
            assertEquals(200, r.statusCode(), r.body());
            assertFalse(r.body().contains("CHAIN_WITHOUT_OUTPUT_STORE"), r.body());
        }
    }

    @Test
    @DisplayName("a legacy Stage-2 block (processing.summarize) without output_store: is refused the same way at /validate")
    void legacyStageTwoBlockIsJudgedToo(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        try (Ctx c = open(dir, wr, "")) {
            String draft = """
                    {"type":"pipeline","config":{
                       "name":"rollup","active":true,
                       "dirs":{"poll":"in","database":"out"},
                       "processing":{"threads":1,"schema_file":"shop_orders_schema.toon",
                                     "summarize":{"group_by":["order_id"],"measures":["count"]}}}}""";
            HttpResponse<String> r = send(c.port, "POST", "/validate", draft);
            assertEquals(200, r.statusCode(), r.body());
            String findings = V1Body.of(r.body()).get("findings").toString();
            assertTrue(findings.contains("\"code\":\"ERR_CHAIN_WITHOUT_OUTPUT_STORE\""), findings);
            assertTrue(findings.contains("\"fieldPath\":\"processing.summarize\""), findings);

            HttpResponse<String> ok = send(c.port, "POST", "/validate",
                    draft.replace("\"active\":true,", "\"active\":true,\"output_store\":\"rollup_out\","));
            assertFalse(ok.body().contains("CHAIN_WITHOUT_OUTPUT_STORE"), ok.body());
        }
    }
}
