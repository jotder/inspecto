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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Sprint A contracts — four routes that used to answer the wrong thing, each proved over real HTTP.
 *
 * <p>These are one theme, not four fixes: <b>a surface that disagrees with another surface, or that
 * dresses a client error as a server error, teaches callers to distrust it.</b> Each test below pins the
 * answer a caller now gets, and each names the work item and board row it discharges so the next reader
 * can find the evidence rather than re-deriving it.
 *
 * <p>⚠ The fifth Sprint A item, {@code WB-04} (the {@code csv_settings} rules are delimited-only), is a
 * pure {@code inspecto-etl} concern with no HTTP surface — it is pinned by {@code ConfigValidatorTest}
 * in that module, not here.
 */
class ControlApiSprintAContractsTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, String priorRoots)
            implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
            if (priorRoots != null) System.setProperty("assist.safety.roots", priorRoots);
            else System.clearProperty("assist.safety.roots");
        }
    }

    private Ctx open(Path dir) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(dir, "");
        String priorRoots = System.getProperty("assist.safety.roots");
        System.setProperty("assist.safety.roots", dir.toString());
        System.setProperty("assist.write.root", dir.toString());
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
        HttpRequest.Builder b =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (body != null) {
            b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        } else {
            b.method(method, BodyPublishers.noBody());
        }
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private String pipelineName(int port) throws Exception {
        return V1Body.of(send(port, "GET", "/pipelines", null).body()).get(0).get("name").asText();
    }

    // ── WB-06 · DRYRUN-MALFORMED-BODY-500-1 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("WB-06: a bare JSON array is a 400 with the expected shape — never a 500")
    void aBareArrayBodyIsAClientErrorNotAServerError(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String name = pipelineName(c.port);
            HttpResponse<String> r =
                    send(c.port, "POST", "/pipelines/authored/" + name + "/dry-run", "[{\"ID\":\"1\"}]");

            assertEquals(400, r.statusCode(),
                    "`[{…}]` is the natural first guess for \"sample rows\"; answering 500 blames the "
                            + "server for the caller's shape. Body: " + r.body());
            assertTrue(r.body().contains("JSON object"),
                    "the refusal must say what shape IS expected, not just that parsing failed: " + r.body());
            assertFalse(r.body().contains("LinkedHashMap"),
                    "a Jackson type name is an implementation leak, not a contract: " + r.body());
        }
    }

    @Test
    @DisplayName("WB-06: a well-formed object body still reaches the route's own contract check")
    void anObjectBodyStillGetsTheRoutesOwnRefusal(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String name = pipelineName(c.port);
            HttpResponse<String> r = send(c.port, "POST", "/pipelines/authored/" + name + "/dry-run", "{}");

            // The seam must not swallow the route's own, more specific 400 — that is the half a blanket
            // catch would have broken.
            assertEquals(400, r.statusCode(), r.body());
            assertTrue(r.body().contains("sample row"),
                    "an object body with no sampleRows must still get the ROUTE's message: " + r.body());
        }
    }

    // ── WB-07 · TESTRUN-DATASET-COLLECTOR-SILENT-1 ───────────────────────────────────────────────

    @Test
    @DisplayName("WB-07: a Dataset-fed Pipeline refuses a file test run 501 — not 200 \"no rows\"")
    void aDatasetFedPipelineRefusesAFileTestRunByName(@TempDir Path dir) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(dir, "");
        // Give the staged pipeline a Dataset feed, which is what `collector: dataset` authors.
        // ⚠ The mini fixture carries NO collector block at all (it defaults to the local poll dir), so
        // this APPENDS one rather than rewriting — a replace silently did nothing and the test then
        // proved the opposite of its name.
        String toon = Files.readString(pipe);
        assertFalse(toon.contains("connector:"),
                "the fixture is expected to carry no connector; if it gained one, this append would "
                        + "produce a duplicate block instead of a Dataset feed:\n" + toon);
        Files.writeString(pipe,
                toon + "\ncollector:\n  connector: dataset\n  dataset: datasets/orders_by_region\n");

        String priorRoots = System.getProperty("assist.safety.roots");
        System.setProperty("assist.safety.roots", dir.toString());
        System.setProperty("assist.write.root", dir.toString());
        CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        try {
            String name = pipelineName(api.port());
            HttpResponse<String> r = send(api.port(), "POST",
                    "/pipelines/authored/" + name + "/run", "{\"files\":[\"anything.csv\"]}");

            assertEquals(501, r.statusCode(),
                    "a file test run over a Dataset feed can never succeed; 200 \"no rows were parsed\" "
                            + "reads as \"your file is bad\". Body: " + r.body());
            assertTrue(r.body().contains("Dataset"),
                    "the refusal must name WHY this instrument does not apply: " + r.body());
        } finally {
            api.close();
            svc.close();
            System.clearProperty("assist.write.root");
            if (priorRoots != null) System.setProperty("assist.safety.roots", priorRoots);
            else System.clearProperty("assist.safety.roots");
        }
    }

    // ── WB-03 · SAVE-GATE-VS-VALIDATE-DISAGREE-1 ─────────────────────────────────────────────────

    @Test
    @DisplayName("WB-03: /validate and the write gate give the SAME answer about a missing schema")
    void validateAndTheWriteGateAgreeOnArming(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            // An ACTIVE draft with no schema source at all: the gate has always refused this.
            Map<String, Object> draft = Map.of(
                    "active", true,
                    "dirs", Map.of("poll", dir.toString().replace('\\', '/') + "/in"),
                    "parsing", Map.of("frontend", "delimited"));
            JsonNode findings = validateFindings(c.port, draft);

            assertTrue(namesCode(findings, "ERR_ARMED_WITHOUT_SCHEMA"),
                    "POST /validate ran NEITHER branch of the arming check before WB-03, so a config the "
                            + "save route refuses validated clean — one config, two answers. Findings: " + findings);
        }
    }

    @Test
    @DisplayName("WB-03 · D7: a parsing.<frontend>.segments{} map IS a schema source")
    void aSegmentMapCountsAsASchemaForTheArmingGate(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            // The shape every segment-routed frontend authors — ASN.1 BER here, the plugin ingester over
            // XML identically. It is the ONLY schema such a frontend has.
            Map<String, Object> draft = Map.of(
                    "active", true,
                    "dirs", Map.of("poll", dir.toString().replace('\\', '/') + "/in"),
                    "parsing", Map.of(
                            "frontend", "asn1",
                            "asn1", Map.of(
                                    "root_type", "CallEventRecord",
                                    "segments", Map.of("moCallRecord", "seg_schema.toon"))));
            JsonNode findings = validateFindings(c.port, draft);

            assertFalse(namesCode(findings, "ERR_ARMED_WITHOUT_SCHEMA"),
                    "D7 (2026-09-22): segments{} IS a schema source. Refusing it is what made a shipped, "
                            + "active, working ASN.1 Pipeline openable but unsaveable. Findings: " + findings);
        }
    }

    @Test
    @DisplayName("WB-03: an EMPTY segments{} map is not a schema — the predicate checks content, not shape")
    void anEmptySegmentMapIsStillNoSchema(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            Map<String, Object> draft = Map.of(
                    "active", true,
                    "dirs", Map.of("poll", dir.toString().replace('\\', '/') + "/in"),
                    "parsing", Map.of("frontend", "asn1", "asn1", Map.of("segments", Map.of())));
            JsonNode findings = validateFindings(c.port, draft);

            assertTrue(namesCode(findings, "ERR_ARMED_WITHOUT_SCHEMA"),
                    "an empty map names no schema; accepting it would make the predicate a shape check "
                            + "that arms a Pipeline with nothing to parse into. Findings: " + findings);
        }
    }

    /** {@code POST /validate} over a draft, returning its {@code findings} array. */
    private JsonNode validateFindings(int port, Map<String, Object> draft) throws Exception {
        String body = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(Map.of("type", "pipeline", "config", draft));
        HttpResponse<String> r = send(port, "POST", "/validate", body);
        assertEquals(200, r.statusCode(), "validate should answer, not refuse: " + r.body());
        return V1Body.of(r.body()).path("findings");
    }

    private static boolean namesCode(JsonNode findings, String code) {
        for (JsonNode f : findings) if (code.equals(f.path("code").asText())) return true;
        return false;
    }
}
