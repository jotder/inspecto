package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-DAILY-INGEST-1 T8 - {@code GET /datasets/{id}/freshness}, real HTTP + real DuckDB: "data as of" is the newest value of
 * the event-date column the Dataset names ({@code eventDate:}), a DATE counted through its end. Gates: 503 write root
 * unset, 404 unknown Dataset; null when no column is named, the column is absent or not a date/time.
 */
class ControlApiDatasetFreshnessTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private void seed(Ctx c, String id, Object eventDate) throws Exception {
        new ViewStore(c.root.resolve("views")).write(new ViewDefinition(id + "_view", "pipeline-x", List.of(),
                "SELECT * FROM (VALUES (DATE '2026-09-01', TIMESTAMP '2026-09-01 10:00:00', 'a'), "
                        + "(DATE '2026-09-03', TIMESTAMP '2026-09-03 23:30:00', 'b')) AS t(EVENT_DATE, START_AT, WHO)",
                "2026-10-06T00:00:00Z"));
        Map<String, Object> ds = new HashMap<>();
        ds.put("view", id + "_view");
        if (eventDate != null) ds.put("eventDate", eventDate);
        new ComponentStore(c.root.resolve("registry")).write("dataset", id, ds);
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path)).GET().build(),
                BodyHandlers.ofString());
    }

    @Test
    void aDateColumnIsCoveredThroughItsEndAndATimestampIsItsInstant(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seed(c, "xdr", "EVENT_DATE");
            seed(c, "xdr_ts", "START_AT");
            HttpResponse<String> r = get(c.port, "/datasets/xdr/freshness");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode d = V1Body.of(r.body());
            assertEquals("xdr", d.get("dataset").asText());
            assertEquals("EVENT_DATE", d.get("eventDateColumn").asText());
            assertEquals("2026-09-04T00:00:00Z", d.get("dataAsOf").asText(), "the newest DATE counts through its end");
            JsonNode ts = V1Body.of(get(c.port, "/datasets/xdr_ts/freshness").body());
            assertEquals("2026-09-03T23:30:00Z", ts.get("dataAsOf").asText());
        }
    }

    @Test
    void noColumnAnAbsentColumnOrANonDateColumnIsNullNeverNow(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seed(c, "plain", null);
            seed(c, "absent", "NO_SUCH");
            seed(c, "text", "WHO");
            for (String id : List.of("plain", "absent", "text")) {
                HttpResponse<String> r = get(c.port, "/datasets/" + id + "/freshness");
                assertEquals(200, r.statusCode(), r.body());
                assertTrue(V1Body.of(r.body()).get("dataAsOf").isNull(), id + ": " + r.body());
            }
        }
    }

    @Test
    void anUnknownDatasetIs404AndNoWriteRootIs503(@TempDir Path cfg, @TempDir Path root, @TempDir Path cfg2) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(404, get(c.port, "/datasets/nope/freshness").statusCode());
        }
        try (Ctx c = open(cfg2, null)) {
            assertEquals(503, get(c.port, "/datasets/xdr/freshness").statusCode());
        }
    }
}
