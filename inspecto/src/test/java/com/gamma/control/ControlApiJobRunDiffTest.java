package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** DUCKLE-C2-RUN-DIFF-1: {@code GET /jobs/{name}/runs/{a}/diff/{b}} over real HTTP — two real runs, recorded facts only. */
class ControlApiJobRunDiffTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SpaceManager spaces, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); spaces.close(); MetricRegistry.global().reset(); }
    }

    private Ctx open(Path root) throws Exception {
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        return new Ctx(spaces, api, api.port());
    }

    @Test
    void diffsTwoRunsByKindAndStatesWhatIsNotCompared(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            String base = "/spaces/acme";
            String outDir = root.resolve("reports").toString().replace("\\", "/");
            assertEquals(200, send(c.port, "POST", base + "/jobs",
                    "{\"name\":\"daily_report\",\"type\":\"report\",\"scope\":\"status\","
                    + "\"out_dir\":\"" + outDir + "\",\"cron\":\"0 6 * * *\"}").statusCode());

            send(c.port, "POST", base + "/jobs/daily_report/trigger", null);
            String first = pollForSuccessfulRuns(c.port, base, "daily_report", 1).get(0);
            send(c.port, "POST", base + "/jobs/daily_report/trigger", null);
            List<String> both = pollForSuccessfulRuns(c.port, base, "daily_report", 2);
            String second = both.stream().filter(r -> !r.equals(first)).findFirst().orElseThrow();

            JsonNode d = json(send(c.port, "GET", base + "/jobs/daily_report/runs/" + first + "/diff/" + second, null));
            assertEquals(first, d.get("a").asText());
            assertEquals(second, d.get("b").asText());
            JsonNode kinds = d.get("kinds");
            for (String k : List.of("code", "runtime", "inputs")) {
                assertFalse(kinds.get(k).get("compared").asBoolean(), k + " has no recorded fact and must say so");
                assertTrue(kinds.get(k).get("reason").asText().startsWith("no recorded fact"));
            }
            assertTrue(kinds.get("execution").get("compared").asBoolean());
            assertTrue(kinds.get("output").get("compared").asBoolean());
            assertTrue(kinds.get("invocation").get("compared").asBoolean());
            // both runs delivered the same 'report' file artifact — the output kind compares it by name
            JsonNode outDiffs = kinds.get("output").get("differences");
            for (JsonNode x : outDiffs) assertTrue(x.get("field").asText().startsWith("output.report"), x.toString());
            // every explanation line traces to a difference: same cardinality, always
            for (String k : List.of("execution", "output", "invocation"))
                assertEquals(kinds.get(k).get("differences").size(), kinds.get(k).get("explanation").size(), k);

            assertEquals(404, send(c.port, "GET", base + "/jobs/daily_report/runs/" + first + "/diff/nope", null).statusCode());
        }
    }

    private List<String> pollForSuccessfulRuns(int port, String base, String job, int wanted) throws Exception {
        for (int i = 0; i < 150; i++) {
            JsonNode runs = json(send(port, "GET", base + "/jobs/" + job + "/runs", null));
            List<String> ok = new ArrayList<>();
            for (JsonNode run : runs) {
                String status = run.get("status").asText();
                if ("SUCCESS".equals(status)) ok.add(run.get("runId").asText());
                if ("FAILED".equals(status) || "ERROR".equals(status)) fail("report run failed: " + run);
            }
            if (ok.size() >= wanted) return ok;
            Thread.sleep(100);
        }
        fail("expected " + wanted + " successful run(s) of '" + job + "'");
        return List.of();
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception { return V1Body.of(r.body()); }
}
