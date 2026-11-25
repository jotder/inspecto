package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.inspector.CollectorProcessor;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code GET /runs/{name}/quarantine} metadata enrichment and
 * {@code POST /runs/{name}/quarantine/release} over real HTTP ({@code QUARANTINE-RELEASE-1}).
 */
class ControlApiQuarantineReleaseTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private static final Authenticator FAKE = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if ("Bearer author".equals(auth)) return Optional.of(new Subject("jdoe", Set.of("canAuthorWorkbench")));
        if ("Bearer operator".equals(auth)) return Optional.of(new Subject("olly", Set.of("canOperateRuns")));
        if ("Bearer plain".equals(auth)) return Optional.of(new Subject("nobody", Set.of()));
        return Optional.empty();
    };

    @BeforeEach
    void setUp() {
        Authenticators.forTest(FAKE);
    }

    @AfterEach
    void tearDown() {
        Authenticators.forTest(null);
    }

    private record Ctx(CollectorService svc, ControlApi api, int port, PipelineConfig cfg) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(dir, "");
        PipelineConfig cfg = PipelineConfig.load(pipe.toString());
        Files.createDirectories(dir.resolve("inbox/sub"));
        // Empty file (header only) -> quarantined under "empty" with a status ledger row.
        Files.writeString(dir.resolve("inbox/sub/bad.csv"), "ID,AMT,EVENT_DATE\n");
        CollectorProcessor.run(cfg);
        CollectorService svc = new CollectorService(List.of(pipe), List.of(), List.of(), 3600L, 1, null);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port(), cfg);
    }

    private HttpResponse<String> send(int port, String method, String path, String body, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return client.send(b.build(), BodyHandlers.ofString());
    }

    @Test
    void quarantineListingSurfacesConsignmentAndTimeMetadata(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> r = send(c.port(), "GET", "/runs/mini_etl/quarantine", null, "plain");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode arr = JSON.readTree(r.body()).get("data");
            assertEquals(1, arr.size(), r.body());
            JsonNode row = arr.get(0);
            assertEquals("bad.csv", row.get("file").asText());
            assertEquals("empty", row.get("reason").asText());
            assertEquals("sub/empty/bad.csv", row.get("path").asText());
            assertTrue(row.hasNonNull("consignment_id") && !row.get("consignment_id").asText().isBlank(),
                    "expected consignment_id in quarantine row: " + row);
            assertTrue(row.hasNonNull("quarantined_at") && !row.get("quarantined_at").asText().isBlank(),
                    "expected quarantined_at in quarantine row: " + row);
        }
    }

    @Test
    void releaseRequiresCanOperateRunsAndMovesFileBackToInbox(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String body = "{\"path\":\"sub/empty/bad.csv\",\"reprocess\":false}";
            // Capability gate
            assertEquals(403, send(c.port(), "POST", "/runs/mini_etl/quarantine/release", body, "author").statusCode());
            assertEquals(403, send(c.port(), "POST", "/runs/mini_etl/quarantine/release", body, "plain").statusCode());

            // Validation & path-jail gates
            assertEquals(400, send(c.port(), "POST", "/runs/mini_etl/quarantine/release", "{}", "operator").statusCode());
            assertEquals(403, send(c.port(), "POST", "/runs/mini_etl/quarantine/release",
                    "{\"path\":\"../inbox/bad.csv\"}", "operator").statusCode());
            assertEquals(403, send(c.port(), "POST", "/runs/mini_etl/quarantine/release",
                    "{\"path\":\".restricted/secret.csv\"}", "operator").statusCode());
            assertEquals(404, send(c.port(), "POST", "/runs/mini_etl/quarantine/release",
                    "{\"path\":\"sub/empty/missing.csv\"}", "operator").statusCode());
            assertEquals(404, send(c.port(), "POST", "/runs/no_such_pipeline/quarantine/release", body, "operator").statusCode());

            // Happy path
            HttpResponse<String> ok = send(c.port(), "POST", "/runs/mini_etl/quarantine/release", body, "operator");
            assertEquals(200, ok.statusCode(), ok.body());
            JsonNode res = JSON.readTree(ok.body()).get("data");
            assertEquals("bad.csv", res.get("file").asText());
            assertEquals("sub/bad.csv", res.get("destination").asText());
            assertEquals("released", res.get("status").asText());

            assertTrue(Files.isRegularFile(Path.of(c.cfg().dirs().poll()).resolve("sub/bad.csv")));
            assertFalse(Files.exists(Path.of(c.cfg().dirs().quarantine()).resolve("sub/empty/bad.csv")));

            // Quarantine listing is now empty
            JsonNode after = JSON.readTree(send(c.port(), "GET", "/runs/mini_etl/quarantine", null, "plain").body()).get("data");
            assertEquals(0, after.size(), after.toString());
        }
    }
}
