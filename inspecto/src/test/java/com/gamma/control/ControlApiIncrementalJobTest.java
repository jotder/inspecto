package com.gamma.control;

import com.gamma.metrics.MetricRegistry;
import com.gamma.service.SpaceManager;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * INCREMENTAL-1 (operator, 2026-10-10) over real HTTP: a malformed {@code incremental:} block and a sink that
 * cannot be day-partitioned are both 422 at save with nothing written; a valid block persists nested.
 */
class ControlApiIncrementalJobTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private static String body(String name, String lookback) {
        return """
                {"name":"%s","type":"sql.template","sink_dataset":"daily_totals","sources":"events",
                 "sql":"SELECT event_date, count(*) AS n FROM events GROUP BY ALL",
                 "incremental":{"by":"day","column":"event_date","lookback":"%s"}}""".formatted(name, lookback);
    }

    @Test
    void validationAndSinkLayoutRefuseAtSave(@TempDir Path root) throws Exception {
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        try {
            int port = api.port();
            assertEquals(200, send(port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            String base = "/spaces/acme";
            Path toon = root.resolve("acme").resolve("config").resolve("jobs").resolve("daily_job.toon");

            for (String bad : new String[] {"0", "367", "x"}) {
                HttpResponse<String> r = send(port, "POST", base + "/jobs", body("daily", bad));
                assertEquals(422, r.statusCode(), r.body());
                assertTrue(r.body().contains("lookback"), r.body());
                assertFalse(Files.exists(toon), "nothing was written");
            }

            Path sink = root.resolve("acme").resolve("data").resolve("daily_totals");
            Files.createDirectories(sink);
            Files.writeString(sink.resolve("sql-1.parquet"), "a flat snapshot");
            HttpResponse<String> flat = send(port, "POST", base + "/jobs", body("daily", "3"));
            assertEquals(422, flat.statusCode(), flat.body());
            assertTrue(flat.body().contains("not laid out by day"), flat.body());
            assertFalse(Files.exists(toon), "nothing was written");

            Files.delete(sink.resolve("sql-1.parquet"));
            HttpResponse<String> ok = send(port, "POST", base + "/jobs", body("daily", "3"));
            assertEquals(200, ok.statusCode(), ok.body());
            String written = Files.readString(toon);
            assertTrue(written.contains("incremental:") && !written.contains("incremental."), written);
        } finally {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }
}
