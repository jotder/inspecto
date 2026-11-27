package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code GET /signals/types} (SIGNAL-BOARD-1 SB-1a) over real HTTP with an armed Authenticator: 401 without a
 * credential, 403 for a Subject without {@code canAuthorWorkbench} (the only proof of the gate), 200 with it, and the
 * body lists core types with their fields and source.
 */
class ControlApiSignalTypesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void arm() {
        Authenticators.forTest(ex -> {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if ("Bearer author".equals(auth)) return Optional.of(new Subject("author-1", Set.of("canAuthorWorkbench")));
            if ("Bearer reader".equals(auth)) return Optional.of(new Subject("reader-1", Set.of("canOperateRuns")));
            return Optional.empty();
        });
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
    }

    private HttpResponse<String> get(int port, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/signals/types")).GET();
        if (token != null) b.header("Authorization", "Bearer " + token);
        return client.send(b.build(), BodyHandlers.ofString());
    }

    @Test
    void theCatalogIsGatedOnAuthoringAndListsCoreTypesWithTheirFields(@TempDir Path dir) throws Exception {
        CollectorService svc = new CollectorService(List.of(TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write()), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        try {
            int port = api.port();
            assertEquals(401, get(port, null).statusCode(), "no credential");
            assertEquals(403, get(port, "reader").statusCode(), "a Subject without canAuthorWorkbench");

            HttpResponse<String> ok = get(port, "author");
            assertEquals(200, ok.statusCode(), ok.body());
            JsonNode data = JSON.readTree(ok.body()).get("data");
            JsonNode types = data.get("types");
            assertEquals(types.size(), data.get("count").asInt());

            JsonNode started = find(types, "job.run.started");
            assertEquals("core", started.get("source").asText());
            assertEquals("engine", started.get("poster").asText());
            assertTrue(started.get("fieldsDeclared").asBoolean());
            assertEquals("4.0.0", started.get("since").asText());
            JsonNode job = started.get("fields").get(0);
            assertEquals("job", job.get("name").asText());
            assertEquals("string", job.get("kind").asText());
            assertTrue(job.get("always").asBoolean());

            JsonNode failed = find(types, "pipeline.batch.failed");
            boolean failedStepOptional = false;
            for (JsonNode f : failed.get("fields"))
                if ("failedStep".equals(f.get("name").asText())) failedStepOptional = !f.get("always").asBoolean();
            assertTrue(failedStepOptional, "failedStep is written only when a Step owns the failure");
        } finally {
            api.close();
            svc.close();
        }
    }

    private static JsonNode find(JsonNode types, String type) {
        for (JsonNode t : types) if (type.equals(t.get("type").asText())) return t;
        return fail("no Signal type '" + type + "' in " + types);
    }
}
