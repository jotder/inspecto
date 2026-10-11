package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ONB-LA-ENDPOINT-TYPE-1 over real HTTP: an Investigation may declare its binding's endpoint Entity Type at create
 * ({@code entityType}, or {@code sourceType}/{@code targetType}); it is sealed in the header only when given, copied into each
 * expand's {@code read.query}, and the entities an expand admits carry it - replay-pure. Without it the header, the read
 * and the Working Set are exactly as before (untyped admitted entities).
 */
class ControlApiInvestigationEndpointTypeTest {
    @BeforeAll static void raiseLinkAnalysisBudget() {
        System.setProperty("control.rateLimit.linkAnalysis.capacity", "10000");
    }

    @AfterAll static void restoreLinkAnalysisBudget() {
        System.clearProperty("control.rateLimit.linkAnalysis.capacity");
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            Files.writeString(writeRoot.resolve("link-analysis.toon"), "masking_mode: none\n");
            new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                    "SELECT caller, callee FROM (VALUES ('256700000001','256700000002'),('256700000003','256700000001')) AS t(caller,callee)",
                    "2026-10-11T00:00:00Z"));
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "calls", Map.of("view", "calls_view"));
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port() + "/api/v1" + path))
                .header("Content-Type", "application/json");
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r, int expected) throws Exception {
        assertEquals(expected, r.statusCode(), r.body());
        return expected < 300 ? JSON.readTree(r.body()).get("data") : JSON.readTree(r.body());
    }

    private HttpResponse<String> create(Ctx c, String id, String extra) throws Exception {
        return send(c, "POST", "/inv/investigations", "{\"purpose\":\"test\",\"id\":\"" + id + "\",\"dataset\":\"calls\","
                + "\"sourceCol\":\"caller\",\"targetCol\":\"callee\"" + extra + "}");
    }

    /** Seed 256700000001 (typed msisdn as the seed op allows), expand one hop both ways; the expand step. */
    private JsonNode seedAndExpand(Ctx c, String id) throws Exception {
        data(send(c, "POST", "/inv/investigations/" + id + "/ops", "{\"op\":\"seed\",\"ids\":[\"256700000001\"],\"entityType\":\"msisdn\"}"), 200);
        return data(send(c, "POST", "/inv/investigations/" + id + "/ops", "{\"op\":\"expand\"}"), 200);
    }

    /** entity id -> type (JSON null when untyped), from a replay, after asserting it reproduces every recorded hash. */
    private Map<String, String> typesByReplay(Ctx c, String id) throws Exception {
        JsonNode rep = data(send(c, "POST", "/inv/investigations/" + id + "/replay", "{}"), 200);
        assertTrue(rep.get("equivalent").asBoolean(), rep.toString());
        Map<String, String> out = new TreeMap<>();
        for (JsonNode e : rep.at("/workingSet/entities")) out.put(e.get("id").asText(), e.get("type").isNull() ? null : e.get("type").asText());
        return out;
    }

    @Test
    void aDeclaredEntityTypeTypesEveryAdmittedEndpointAndReplays(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            JsonNode header = data(create(c, "typed", ",\"entityType\":\"MSISDN\""), 200);
            assertEquals("msisdn", header.get("sourceType").asText(), "stored as the Entity Type's id");
            assertEquals("msisdn", header.get("targetType").asText());
            JsonNode step = seedAndExpand(c, "typed");
            assertEquals("msisdn", step.at("/read/rung/sourceType").asText(), step.toString());
            assertEquals("msisdn", step.at("/read/rung/targetType").asText());
            Map<String, String> types = typesByReplay(c, "typed");
            assertEquals(3, types.size(), types.toString());
            types.forEach((id, t) -> assertEquals("msisdn", t, id));
        }
    }

    @Test
    void oneSideOnlyTypesThatSide(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            JsonNode header = data(create(c, "half", ",\"targetType\":\"msisdn\""), 200);
            assertFalse(header.has("sourceType"), header.toString());
            seedAndExpand(c, "half");
            Map<String, String> types = typesByReplay(c, "half");
            assertEquals("msisdn", types.get("256700000002"), "reached as a callee: the target side's type");
            assertEquals(null, types.get("256700000003"), "reached as a caller: the source side declares none");
        }
    }

    @Test
    void withoutADeclarationTheHeaderTheReadAndTheEntitiesAreUnchanged(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            JsonNode header = data(create(c, "plain", ""), 200);
            assertFalse(header.has("sourceType") || header.has("targetType"), header.toString());
            JsonNode step = seedAndExpand(c, "plain");
            assertTrue(step.at("/read/rung/sourceType").isMissingNode() && step.at("/read/rung/targetType").isMissingNode(), step.toString());
            Map<String, String> types = typesByReplay(c, "plain");
            assertEquals("msisdn", types.get("256700000001"), "the seed keeps its own type");
            assertEquals(null, types.get("256700000002"));
            assertEquals(null, types.get("256700000003"));
        }
    }

    @Test
    void underTypedMaskingAMaskedEndpointTypeMasksTheEndpointsAnExpandAdmits(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            Files.writeString(root.resolve("link-analysis.toon"), "masking_mode: typed\n");
            data(create(c, "masked", ",\"entityType\":\"msisdn\""), 200);
            seedAndExpand(c, "masked");
            String log = data(send(c, "GET", "/inv/investigations/masked/log", null), 200).toString();
            for (String raw : List.of("256700000001", "256700000002", "256700000003"))
                assertFalse(log.contains(raw), raw + " leaked: " + log);
            Map<String, String> types = typesByReplay(c, "masked");
            assertEquals(3, types.size(), types.toString());
            types.forEach((id, t) -> assertTrue(id.startsWith("masked:"), id));
        }
    }

    @Test
    void anUnknownTypeOrBothFormsAre422AndNothingIsWritten(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertTrue(data(create(c, "bad", ",\"entityType\":\"nosuch\""), 422).toString().contains("unknown Entity Type"));
            assertTrue(data(create(c, "bad", ",\"entityType\":\"msisdn\",\"sourceType\":\"msisdn\""), 422).toString().contains("not both"));
            data(send(c, "GET", "/inv/investigations/bad", null), 404);
        }
    }
}
