package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.ops.OperationalObject;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.gamma.workflow.ObjectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Read-time masking of the {@code key.<column>} attributes on {@code GET /objects} and {@code GET /objects/{id}}
 * (TELCO-FRAUD-MASKED-DASHBOARD-1 (c), D-P8): a caller without {@code canRevealLinkEntities} sees the {@code masked:<16 hex>}
 * token where the Space classifies the column MSISDN; a revealer sees the raw number. Real HTTP under an armed
 * authenticator, so every capability check is real. The title is masked too (origin's ObjectReadMasking, 72e0ef611).
 * An Alert is not an object (it lives in the AlertStore, read by {@code GET /alerts}, which carries no attributes).
 */
class ControlApiObjectReadMaskingTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RAW = "99970001401";
    private final HttpClient client = HttpClient.newHttpClient();

    private static final Authenticator FAKE = ex -> {
        String a = ex.getRequestHeaders().getFirst("Authorization");
        if (a == null || !a.startsWith("Bearer ")) return Optional.empty();
        String user = a.substring(7);
        return Optional.of(new Subject(user, "revealer".equals(user) ? Set.of("canRevealLinkEntities") : Set.of()));
    };

    @AfterEach
    void tearDown() {
        Authenticators.forTest(null);
    }

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir, Path root, boolean classify) throws Exception {
        Authenticators.forTest(FAKE);
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            new ComponentStore(root.resolve("registry")).write("dataset", "fraud_ds", Map.of("columns", List.of(
                    Map.of("name", "msisdn", "classification", classify ? "MSISDN" : "INTERNAL"), Map.of("name", "region"))));
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private static OperationalObject open(Ctx c, ObjectType type) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("rule", "tf_rule");
        attrs.put("dataset", "fraud_ds");
        attrs.put("key", "msisdn=" + RAW);
        attrs.put("key.msisdn", RAW);
        attrs.put("key.region", "EU");
        attrs.put("alertKey", "tf_rule|msisdn=" + RAW);
        return TestOpsEngine.of(c.svc).open(type, "tf_rule breached for msisdn=" + RAW, "d", "CRITICAL", null, null, null, null, attrs);
    }

    private HttpResponse<String> get(Ctx c, String user, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Authorization", "Bearer " + user).GET().build(), BodyHandlers.ofString());
    }

    private void assertMasked(JsonNode obj) {
        JsonNode a = obj.get("attributes");
        assertTrue(a.get("key.msisdn").asText().matches("masked:[0-9a-f]{16}"), a.toString());
        assertEquals("msisdn=" + a.get("key.msisdn").asText(), a.get("key").asText());
        assertEquals("tf_rule|msisdn=" + a.get("key.msisdn").asText(), a.get("alertKey").asText());
        assertEquals("EU", a.get("key.region").asText(), "an unclassified column keeps its value");
        assertFalse(a.toString().contains(RAW), "no raw number in any attribute: " + a);
        assertFalse(obj.get("title").asText().contains(RAW), "the title carries the token too: " + obj.get("title"));
    }

    @Test
    void aViewerSeesTheTokenOnListAndDetail(@TempDir Path dir, @TempDir Path root) throws Exception {
        try (Ctx c = open(dir, root, true)) {
            OperationalObject incident = open(c, ObjectType.INCIDENT), second = open(c, ObjectType.INCIDENT);
            for (String id : List.of(incident.id(), second.id())) {
                JsonNode one = JSON.readTree(get(c, "viewer", "/objects/" + id).body()).get("data");
                assertMasked(one);
            }
            JsonNode list = JSON.readTree(get(c, "viewer", "/objects?limit=50").body()).get("data");
            assertEquals(2, list.size());
            list.forEach(this::assertMasked);
            String token = list.get(0).get("attributes").get("key.msisdn").asText();
            JsonNode forRevealer = JSON.readTree(get(c, "revealer", "/objects/" + incident.id()).body()).get("data");
            assertNotEquals(token, forRevealer.get("attributes").get("key.msisdn").asText());
        }
    }

    @Test
    void aRevealerSeesTheRawKey(@TempDir Path dir, @TempDir Path root) throws Exception {
        try (Ctx c = open(dir, root, true)) {
            OperationalObject incident = open(c, ObjectType.INCIDENT);
            JsonNode a = JSON.readTree(get(c, "revealer", "/objects/" + incident.id()).body()).get("data").get("attributes");
            assertEquals(RAW, a.get("key.msisdn").asText());
            assertEquals("msisdn=" + RAW, a.get("key").asText());
            JsonNode list = JSON.readTree(get(c, "revealer", "/objects").body()).get("data");
            assertEquals(RAW, list.get(0).get("attributes").get("key.msisdn").asText());
        }
    }

    @Test
    void aColumnNotClassifiedMsisdnIsNotMasked(@TempDir Path dir, @TempDir Path root) throws Exception {
        try (Ctx c = open(dir, root, false)) {
            OperationalObject incident = open(c, ObjectType.INCIDENT);
            JsonNode a = JSON.readTree(get(c, "viewer", "/objects/" + incident.id()).body()).get("data").get("attributes");
            assertEquals(RAW, a.get("key.msisdn").asText());
        }
    }
}
