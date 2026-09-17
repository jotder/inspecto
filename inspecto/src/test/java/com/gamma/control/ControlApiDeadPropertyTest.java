package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.config.io.ConfigLoader;
import com.gamma.config.spec.FindingCodes;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `DUCKLE-C3-DEAD-PROPERTY-1` at the STRICT seam, over real HTTP: a config block no component reads
 * must be REFUSED at {@code POST /config/write} and {@code POST /config/patch}, with the stable code
 * and — when there is one — the near name. The value of the row is turning a silent loss (the save
 * answers {@code written:true} and the engine ignores the key forever) into a refusal the author is
 * still present to act on.
 */
class ControlApiDeadPropertyTest {

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

    private HttpResponse<String> post(int port, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    /** A findings entry carrying {@code code}, or null. A 422 carries them under the error envelope's
     *  {@code details}, not at the top level — the shape every ERROR→422 finding already rides. */
    private static JsonNode findingWithCode(String body, String code) throws Exception {
        JsonNode findings = V1Body.envelope(body).get("error").get("details").get("findings");
        if (findings == null) return null;
        for (JsonNode f : findings)
            if (f.hasNonNull("code") && code.equals(f.get("code").asText())) return f;
        return null;
    }

    @Test
    void aDeadTopLevelBlockIsRefusedWithTheStableCodeAndANearName(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            String body = """
                    {"type":"pipeline","config":{
                       "name":"orders",
                       "dirs":{"poll":"in","database":"out"},
                       "procesing":{"threads":1}}}""";
            HttpResponse<String> r = post(c.port, "/config/write", body);
            assertEquals(422, r.statusCode(), r.body());

            JsonNode f = findingWithCode(r.body(), FindingCodes.ERR_UNKNOWN_CONFIG_KEY);
            assertTrue(f != null, "no ERR_UNKNOWN_CONFIG_KEY finding in: " + r.body());
            assertEquals("procesing", f.get("fieldPath").asText());
            assertTrue(f.get("guidance").asText().contains("did you mean 'processing'"),
                    "the near name must be offered: " + f.get("guidance").asText());

            assertFalse(Files.exists(root.resolve("orders_pipeline.toon")),
                    "a refused write must persist nothing");
        }
    }

    @Test
    void aDeadBlockWithNoNearNameIsRefusedWithoutASuggestion(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            String body = """
                    {"type":"pipeline","config":{
                       "name":"orders",
                       "dirs":{"poll":"in","database":"out"},
                       "processing":{"threads":1},
                       "banana":"yellow"}}""";
            HttpResponse<String> r = post(c.port, "/config/write", body);
            assertEquals(422, r.statusCode(), r.body());

            JsonNode f = findingWithCode(r.body(), FindingCodes.ERR_UNKNOWN_CONFIG_KEY);
            assertTrue(f != null, "no ERR_UNKNOWN_CONFIG_KEY finding in: " + r.body());
            assertEquals("banana", f.get("fieldPath").asText());
            assertFalse(f.get("guidance").asText().contains("did you mean"),
                    "nothing is close to 'banana', so no name may be guessed: " + f.get("guidance").asText());
        }
    }

    /** The escape hatch: an `x-` block is the author's own annotation and must persist untouched. */
    @Test
    void anXPrefixedBlockIsWrittenAndRoundTripsOffDisk(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String body = """
                    {"type":"pipeline","config":{
                       "name":"orders",
                       "dirs":{"poll":"in","database":"out"},
                       "processing":{"threads":1},
                       "x-owner":"platform-team"}}""";
            HttpResponse<String> r = post(c.port, "/config/write", body);
            assertEquals(200, r.statusCode(), r.body());

            Map<String, Object> decoded = ConfigLoader.filesystem()
                    .decode(root.resolve("orders_pipeline.toon").toString());
            assertEquals("platform-team", decoded.get("x-owner"),
                    "an x- block must round-trip untouched through the write");
        }
    }

    /** A patch can introduce a dead block as easily as a write can. */
    @Test
    void aPatchThatAddsADeadBlockIsRefused(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String create = """
                    {"type":"pipeline","config":{
                       "name":"orders",
                       "dirs":{"poll":"in","database":"out"},
                       "processing":{"threads":1}}}""";
            assertEquals(200, post(c.port, "/config/write", create).statusCode());

            HttpResponse<String> r = post(c.port, "/config/patch", """
                    {"type":"pipeline","name":"orders","patch":{"banana":"yellow"}}""");
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(findingWithCode(r.body(), FindingCodes.ERR_UNKNOWN_CONFIG_KEY) != null,
                    "no ERR_UNKNOWN_CONFIG_KEY finding in: " + r.body());
        }
    }

    /** A config type with no census is unaffected — the checker is fail-open there, deliberately. */
    @Test
    void aTypeWithNoCensusIsUnaffected(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = post(c.port, "/config/write", """
                    {"type":"schema","config":{
                       "raw":{"name":"syn_rt","format":"CSV",
                          "fields":[{"name":"ID","selector":"0","type":"VARCHAR"}]}}}""");
            assertEquals(200, r.statusCode(), r.body());
        }
    }

    /**
     * The other three censused types (`alert`, `meta`, `enrichment`) run through the SAME generic
     * {@code AcceptedConfigKeys.unknownKeyFindings} call in {@code ConfigWriteRoutes} that the pipeline
     * tests above prove live — this closes the row's own demand to ground that claim live rather than
     * trust the prose that they behave the same way.
     */
    @Test
    void aDeadAlertKeyIsRefused(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = post(c.port, "/config/write", """
                    {"type":"alert","config":{"alert":{
                       "name":"rule-1","metric":"error_rate","threshold":"0.1","window":"1h",
                       "banana":"yellow"}}}""");
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(findingWithCode(r.body(), FindingCodes.ERR_UNKNOWN_CONFIG_KEY) != null,
                    "no ERR_UNKNOWN_CONFIG_KEY finding in: " + r.body());
        }
    }

    @Test
    void aDeadMetaKeyIsRefused(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = post(c.port, "/config/write", """
                    {"type":"meta","config":{"name":"orders_semantics","banana":"yellow"}}""");
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(findingWithCode(r.body(), FindingCodes.ERR_UNKNOWN_CONFIG_KEY) != null,
                    "no ERR_UNKNOWN_CONFIG_KEY finding in: " + r.body());
        }
    }

    @Test
    void aDeadEnrichmentKeyIsRefused(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = post(c.port, "/config/write", """
                    {"type":"enrichment","config":{
                       "name":"orders_enrich",
                       "input":{"database":"in","partitions":[]},
                       "output":{"database":"out","partitions":[]},
                       "banana":"yellow"}}""");
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(findingWithCode(r.body(), FindingCodes.ERR_UNKNOWN_CONFIG_KEY) != null,
                    "no ERR_UNKNOWN_CONFIG_KEY finding in: " + r.body());
        }
    }
}
