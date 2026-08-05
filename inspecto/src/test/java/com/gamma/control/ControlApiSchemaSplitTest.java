package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Schema split-write + the BACKWARD compatibility save-gate over real HTTP (ELT amendment Phase 1
 * slice 2, §3.4.2 / D-10): {@code POST /config/write type=schema} persists {@code mapping.rules} as
 * the sibling {@code <name>_mapping.csv} and the structure TOON without them; {@code GET} serves the
 * conflated view back (round-trip); a breaking overwrite (remove/narrow/selector-move) is refused
 * 422 with cell-level findings unless {@code compatibility: "none"} overrides; {@code DELETE}
 * discards the sibling too.
 */
class ControlApiSchemaSplitTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
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
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        return client.send(b.method(method,
                body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body)).build(),
                BodyHandlers.ofString());
    }

    /** A schema draft (identity {@code raw.name=ev}) with the given QTY type and one mapping rule set. */
    private static String schemaDraft(String qtyType, boolean overwrite, String extra) {
        return """
                {"type":"schema",%s"config":{
                   "raw":{"name":"ev","format":"CSV","fields":[
                      {"name":"ID","selector":"0","type":"VARCHAR"},
                      {"name":"QTY","selector":"1","type":"%s"}]},
                   "mapping":{"canonicalName":"ev","rules":[
                      {"targetColumn":"ID","sourceExpression":"ID","transformType":"DIRECT"},
                      {"targetColumn":"GROSS","sourceExpression":"TRY_CAST(QTY AS DOUBLE) * 2, 0 + 1","transformType":"EXPR"}]}}%s}
                """.formatted(overwrite ? "\"overwrite\":true," : "", qtyType, extra);
    }

    @Test
    void schemaWriteSplitsRulesIntoASiblingCsvAndReadsBackConflated(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> w = send(c.port, "POST", "/config/write", schemaDraft("INTEGER", false, ""));
            assertEquals(200, w.statusCode(), w.body());
            JsonNode out = V1Body.of(w.body());
            assertEquals("ev_mapping.csv", out.get("mappingPath").asText());

            // On disk: TOON without rules + sibling CSV with them.
            String toon = Files.readString(root.resolve("ev.toon"), StandardCharsets.UTF_8);
            assertFalse(toon.contains("GROSS"), "rules are NOT in the TOON:\n" + toon);
            assertTrue(toon.contains("canonicalName"), "the rest of the mapping block survives");
            String csv = Files.readString(root.resolve("ev_mapping.csv"), StandardCharsets.UTF_8);
            assertTrue(csv.contains("GROSS"), "rules live in the sibling CSV");

            // Read-back is the conflated view the author sent (round-trip).
            JsonNode read = V1Body.of(send(c.port, "GET", "/config/schema/ev", null).body());
            JsonNode rules = read.get("config").get("mapping").get("rules");
            assertEquals(2, rules.size(), "sibling CSV merged back on read");
            assertEquals("TRY_CAST(QTY AS DOUBLE) * 2, 0 + 1",
                    rules.get(1).get("sourceExpression").asText(), "commas survive the CSV hop");
        }
    }

    @Test
    void breakingOverwriteIsRefusedWithCellLevelFindings(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(200, send(c.port, "POST", "/config/write", schemaDraft("INTEGER", false, "")).statusCode());
            // Dropping ID is a field removal — the canonical breaking edit under BACKWARD.
            String breaking = """
                    {"type":"schema","overwrite":true,"config":{
                       "raw":{"name":"ev","format":"CSV","fields":[
                          {"name":"QTY","selector":"1","type":"INTEGER"}]}}}""";
            HttpResponse<String> r = send(c.port, "POST", "/config/write", breaking);
            assertEquals(422, r.statusCode(), r.body());
            JsonNode details = V1Body.envelope(r.body()).get("error").get("details");
            assertFalse(details.get("written").asBoolean());
            assertTrue(details.get("findings").toString().contains("raw.fields[ID]"),
                    "cell-level finding names the removed field: " + details.get("findings"));
        }
    }

    @Test
    void wideningOverwritePassesAndOverrideBypassesTheGate(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(200, send(c.port, "POST", "/config/write", schemaDraft("INTEGER", false, "")).statusCode());
            // INTEGER → DOUBLE is a widening: allowed in place.
            assertEquals(200, send(c.port, "POST", "/config/write", schemaDraft("DOUBLE", true, "")).statusCode());
            // DOUBLE → INTEGER is narrowing: refused…
            assertEquals(422, send(c.port, "POST", "/config/write", schemaDraft("INTEGER", true, "")).statusCode());
            // …unless explicitly overridden (D-10 escape hatch).
            String overridden = schemaDraft("INTEGER", true, "").replaceFirst("\\{\"type\":\"schema\",",
                    "{\"type\":\"schema\",\"compatibility\":\"none\",");
            assertEquals(200, send(c.port, "POST", "/config/write", overridden).statusCode());
        }
    }

    @Test
    void deleteDiscardsTheSiblingCsvToo(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(200, send(c.port, "POST", "/config/write", schemaDraft("INTEGER", false, "")).statusCode());
            assertTrue(Files.exists(root.resolve("ev_mapping.csv")));
            assertEquals(200, send(c.port, "DELETE", "/config/schema/ev", null).statusCode());
            assertFalse(Files.exists(root.resolve("ev.toon")));
            assertFalse(Files.exists(root.resolve("ev_mapping.csv")), "sibling is part of the component");
        }
    }
}
