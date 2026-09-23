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
 * Two {@code sinks[]} destinations registering into ONE DuckLake table
 * ({@code SINK-DUCKLAKE-SHARED-LAKE-DUPLICATES-1}, operator decision 2026-09-23: refuse the shape) are
 * reported on the SAVE path over real HTTP — {@code /validate} (draft and {@code configPath}),
 * {@code /config/write} and {@code PUT /pipelines/{name}/graph} — at ERROR even on an inactive draft,
 * because {@code PipelineConfig.prepare()} refuses the shape unconditionally.
 */
class ControlApiSinkDuckLakeSharedTableTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                        .method(method, BodyPublishers.ofString(body)).build(),
                BodyHandlers.ofString());
    }

    /** Two sinks; the first inherits {@code output.ducklake}, the second declares {@code secondLake}. */
    private static String draft(String name, String secondLake) {
        return """
                {"type":"pipeline","config":{
                   "name":"%s",
                   "active":false,
                   "dirs":{"poll":"in","database":"out"},
                   "processing":{"threads":1,"schema_file":"cdr.toon"},
                   "output":{"format":"PARQUET","ducklake":{"enabled":true,"catalog_url":"lake.ducklake",
                             "data_path":"lake-data","table":"orders"}},
                   "sinks":[{"database":"out/hot"},
                            {"database":"out/cold","ducklake":%s}]}}"""
                .formatted(name, secondLake);
    }

    private static final String SAME_LAKE_SAME_TABLE =
            "{\"enabled\":true,\"catalog_url\":\"lake.ducklake\",\"data_path\":\"lake-data\",\"table\":\"orders\"}";
    private static final String SAME_LAKE_OTHER_TABLE =
            "{\"enabled\":true,\"catalog_url\":\"lake.ducklake\",\"data_path\":\"lake-data\",\"table\":\"orders_cold\"}";
    private static final String OTHER_LAKE =
            "{\"enabled\":true,\"catalog_url\":\"other.ducklake\",\"data_path\":\"lake-data\",\"table\":\"orders\"}";

    @Test
    @DisplayName("/config/write refuses two sinks sharing one lake table — 422, nothing written, even inactive")
    void writeRefusesTheSharedTableEvenWhenInactive(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = send(c.port, "POST", "/config/write", draft("shared_lake", SAME_LAKE_SAME_TABLE));
            assertEquals(422, r.statusCode(), r.body());
            JsonNode out = V1Body.envelope(r.body()).get("error").get("details");
            assertFalse(out.get("written").asBoolean());
            String findings = out.get("findings").toString();
            assertTrue(findings.contains("\"code\":\"ERR_SINK_DUCKLAKE_SHARED_TABLE\""), findings);
            assertTrue(findings.contains("out/hot") && findings.contains("out/cold"), findings);
            assertTrue(findings.contains("its own ducklake table"), findings);
            assertFalse(Files.exists(root.resolve("shared_lake_pipeline.toon")));
        }
    }

    @Test
    @DisplayName("/config/write allows the same lake with different tables, and different lakes")
    void writeAllowsDifferentTablesAndDifferentLakes(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            for (String[] t : new String[][]{{"other_table", SAME_LAKE_OTHER_TABLE}, {"other_lake", OTHER_LAKE}}) {
                HttpResponse<String> r = send(c.port, "POST", "/config/write", draft(t[0], t[1]));
                assertEquals(200, r.statusCode(), t[0] + ": " + r.body());
                assertFalse(r.body().contains("ERR_SINK_DUCKLAKE_SHARED_TABLE"), r.body());
            }
        }
    }

    @Test
    @DisplayName("/validate reports the refusal on a draft, and on a configPath answers 422 naming it")
    void validateReportsItOnADraftAndOnAConfigPath(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            HttpResponse<String> r = send(c.port, "POST", "/validate", draft("shared_lake", SAME_LAKE_SAME_TABLE));
            assertEquals(200, r.statusCode(), r.body());
            JsonNode out = V1Body.of(r.body());
            assertFalse(out.get("clean").asBoolean(), out.toString());
            assertTrue(out.get("findings").toString().contains("ERR_SINK_DUCKLAKE_SHARED_TABLE"), out.toString());

            HttpResponse<String> ok = send(c.port, "POST", "/validate", draft("other_lake", OTHER_LAKE));
            assertFalse(ok.body().contains("ERR_SINK_DUCKLAKE_SHARED_TABLE"), ok.body());

            // An on-disk config with the shape: load() -> prepare() refuses, surfaced as a 422, not a 500.
            Path disk = Files.createDirectories(cfg.resolve("disk"));
            Path onDisk = PipelineConfigBatchTest.writePipeline(disk, "");
            String text = Files.readString(onDisk);
            String outputBlock = "output:\n  format: CSV\n";
            assertTrue(text.contains(outputBlock), "fixture drifted: " + text);
            Files.writeString(onDisk, text.replace(outputBlock, """
                    output:
                      format: PARQUET
                      ducklake:
                        enabled: true
                        catalog_url: lake.ducklake
                        data_path: lake-data
                        table: orders
                    sinks[2]{database}:
                      out/hot
                      out/cold
                    """));
            HttpResponse<String> p = send(c.port, "POST", "/validate",
                    "{\"configPath\":\"" + onDisk.toString().replace('\\', '/') + "\"}");
            assertEquals(422, p.statusCode(), p.body());
            assertTrue(p.body().contains("out/hot") && p.body().contains("out/cold"), p.body());
        }
    }

    @Test
    @DisplayName("PUT /pipelines/{name}/graph refuses two sink nodes sharing one lake table")
    void graphSaveRefusesTheSharedTable(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        try (Ctx c = open(dir, wr)) {
            Path schema = dir.resolve("lake_schema.toon");
            Files.writeString(schema, "raw:\n  fields[1]{name,selector,type}:\n    ID, \"0\", VARCHAR\n");
            String b = dir.toString().replace('\\', '/');
            String lake = "{\"enabled\":true,\"catalog_url\":\"lake.ducklake\",\"data_path\":\"lake-data\",\"table\":\"orders\"}";
            String graph = """
                {"active":false,
                 "nodes":[{"id":"acq","type":"acquisition","config":{"poll":"%s/in"}},
                          {"id":"p","type":"parser","config":{"schema_file":"%s"}},
                          {"id":"s1","type":"sink.persistent","config":{"database":"%s/db_hot","ducklake":%s}},
                          {"id":"s2","type":"sink.persistent","config":{"database":"%s/db_cold","ducklake":%s}}],
                 "edges":[{"from":"acq","rel":"data","to":"p"},{"from":"p","rel":"data","to":"s1"},
                          {"from":"p","rel":"data","to":"s2"}]}"""
                    .formatted(b, schema.toString().replace('\\', '/'), b, lake, b, lake);
            HttpResponse<String> r = send(c.port, "PUT", "/pipelines/lake_graph/graph", graph);
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("ERR_SINK_DUCKLAKE_SHARED_TABLE"), r.body());
            assertFalse(Files.exists(wr.resolve("lake_graph_pipeline.toon")));
        }
    }
}
