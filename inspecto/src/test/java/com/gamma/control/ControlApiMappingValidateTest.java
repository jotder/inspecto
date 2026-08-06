package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code POST /components/mapping/validate} over real HTTP (ELT amendment UI plan §2.5, S6b): the
 * mapping import loop's validate-without-write gate. Asserts the response shape the grid editor
 * consumes, that nothing is ever written, and that the route works with writes disabled entirely.
 */
class ControlApiMappingValidateTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir, Path writeRoot) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        String prior = System.getProperty("assist.write.root");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        else System.clearProperty("assist.write.root");
        try {
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            if (prior != null) System.setProperty("assist.write.root", prior);
            else System.clearProperty("assist.write.root");
        }
    }

    @Test
    void cleanRulesValidateClean(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            HttpResponse<String> r = post(c.port, """
                    {"rules":[{"targetColumn":"MSISDN","sourceExpression":"msisdn","transformType":"DIRECT"}]}""");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode body = json(r);
            assertEquals("mapping", body.get("type").asText());
            assertTrue(body.get("clean").asBoolean());
            assertEquals(0, body.get("findings").size());
        }
    }

    @Test
    void findingsCarryTheCellAnchorTheGridNeeds(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            HttpResponse<String> r = post(c.port, """
                    {"rules":[{"targetColumn":"MSISDN","sourceExpression":"a","transformType":"EXPER"}]}""");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode body = json(r);
            assertFalse(body.get("clean").asBoolean());
            JsonNode f = body.get("findings").get(0);
            // the three keys the UI's Finding type reads, verbatim
            assertEquals("ERROR", f.get("severity").asText());
            assertEquals("rules[0].transformType", f.get("fieldPath").asText());
            assertTrue(f.get("message").asText().contains("EXPER"), f.get("message").asText());
        }
    }

    @Test
    void validatingNeverWritesToTheRegistry(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        try (Ctx c = open(dir, wr)) {
            assertEquals(200, post(c.port, """
                    {"rules":[{"targetColumn":"A","sourceExpression":"a"}]}""").statusCode());
            assertFalse(Files.exists(wr.resolve("registry")), "validate must not create the registry");
        }
    }

    @Test
    void worksWithWritesDisabled(@TempDir Path dir) throws Exception {
        // No write root at all: the gate must still answer, since it reads and writes nothing.
        try (Ctx c = open(dir, null)) {
            assertEquals(200, post(c.port, """
                    {"rules":[{"targetColumn":"A","sourceExpression":"a"}]}""").statusCode());
        }
    }

    @Test
    void anEmptyRuleSetIsNotClean(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            JsonNode body = json(post(c.port, "{\"rules\":[]}"));
            assertFalse(body.get("clean").asBoolean());
            assertEquals("", body.get("findings").get(0).get("fieldPath").asText());
        }
    }

    @Test
    void aMalformedBodyIs400(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            assertEquals(400, post(c.port, "{}").statusCode(), "no rules key");
            assertEquals(400, post(c.port, "{\"rules\":\"nope\"}").statusCode(), "rules not a list");
            assertEquals(400, post(c.port, "{\"rules\":[\"nope\"]}").statusCode(), "rule not an object");
        }
    }

    private HttpResponse<String> post(int port, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/components/mapping/validate"))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(body))
                .build();
        return client.send(req, BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception {
        return V1Body.of(r.body());
    }
}
