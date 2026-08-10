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
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The job write contract over real HTTP ({@code POST /jobs}, {@code PUT /jobs/{name}}) — the shape an
 * authoring client must send, which had no coverage at all before this class.
 *
 * <p>The body <b>is</b> the {@code job:} TOON section in JSON: keys are <b>snake_case</b>
 * ({@code on_pipeline} / {@code on_signal} / {@code catch_up}) and type-specific parameters are
 * <b>flat</b> alongside them, because {@link com.gamma.job.JobConfig#fromMap} sweeps every unrecognised
 * top-level key into {@code params}. That sweep is also why the contract is worth pinning: an unknown
 * key is <b>not</b> a 422 — a camelCase {@code onPipeline} is silently absorbed as an inert parameter
 * and the job ends up with no trigger at all. The last test nails that behaviour down so a client
 * regressing to camelCase fails here rather than in a deployment.
 */
class ControlApiJobCrudTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SpaceManager spaces, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }

    private Ctx open(Path root) throws Exception {
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        return new Ctx(spaces, api, api.port());
    }

    @Test
    void snakeCaseTriggersAndFlatParamsRoundTrip(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            String base = "/spaces/acme";

            // an event trigger + a flat type-specific param
            assertEquals(200, send(c.port, "POST", base + "/jobs", """
                    {"name":"after_ingest","type":"maintenance","task":"cleanup",
                     "on_pipeline":"cdr_ingest","retention_days":"30"}""").statusCode());

            JsonNode detail = json(send(c.port, "GET", base + "/jobs/after_ingest", null));
            assertEquals("cdr_ingest", detail.get("on_pipeline").asText(), "the trigger is wired, not swallowed");
            assertEquals("30", detail.get("retention_days").asText(), "params are flat in the detail view");
            assertEquals("cleanup", detail.get("task").asText());

            // the list projection reports the same trigger, camelCased (JobView is a Java record)
            assertEquals("cdr_ingest", find(json(send(c.port, "GET", base + "/jobs", null)), "after_ingest")
                    .get("onPipeline").asText());

            // and it survives to the persisted TOON
            Path toon = root.resolve("acme").resolve("config").resolve("jobs").resolve("after_ingest_job.toon");
            assertTrue(Files.readString(toon).contains("on_pipeline: cdr_ingest"), Files.readString(toon));
        }
    }

    @Test
    void aSignalTriggeredJobIsAuthorableEndToEnd(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            String base = "/spaces/acme";

            assertEquals(200, send(c.port, "POST", base + "/jobs", """
                    {"name":"on_dataset_write","type":"maintenance","task":"cleanup",
                     "on_signal":"dataset.write","when":"$signal.dataset == 'premium_cdr_view'",
                     "catch_up":"true"}""").statusCode());

            JsonNode detail = json(send(c.port, "GET", base + "/jobs/on_dataset_write", null));
            assertEquals("dataset.write", detail.get("on_signal").asText());
            assertEquals("$signal.dataset == 'premium_cdr_view'", detail.get("when").asText());
            assertTrue(detail.get("catch_up").asBoolean(), "catch_up is snake_case on the wire too");

            // the list distinguishes it from a manual job — the reason JobView carries onSignal
            JsonNode row = find(json(send(c.port, "GET", base + "/jobs", null)), "on_dataset_write");
            assertEquals("dataset.write", row.get("onSignal").asText());
            assertTrue(row.get("cron").asText().isEmpty() || row.get("cron").isNull());

            Path toon = root.resolve("acme").resolve("config").resolve("jobs").resolve("on_dataset_write_job.toon");
            String written = Files.readString(toon);
            assertTrue(written.contains("on_signal: dataset.write"), written);
            assertTrue(written.contains("when:"), written);

            // an edit preserves the signal trigger (PUT replaces the whole config)
            assertEquals(200, send(c.port, "PUT", base + "/jobs/on_dataset_write", """
                    {"name":"on_dataset_write","type":"maintenance","task":"cleanup",
                     "on_signal":"dataset.*"}""").statusCode());
            JsonNode edited = json(send(c.port, "GET", base + "/jobs/on_dataset_write", null));
            assertEquals("dataset.*", edited.get("on_signal").asText(), "a prefix match is accepted");
            assertFalse(edited.has("when"), "the guard was dropped by the replacing edit");
        }
    }

    @Test
    void aCamelCaseTriggerKeyIsAbsorbedAsAParamAndLeavesTheJobUntriggered(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            String base = "/spaces/acme";

            // NOT a 422 — fromMap's default branch sweeps unknown keys into params
            assertEquals(200, send(c.port, "POST", base + "/jobs", """
                    {"name":"camel","type":"maintenance","task":"cleanup","onPipeline":"cdr_ingest"}""")
                    .statusCode());

            JsonNode detail = json(send(c.port, "GET", base + "/jobs/camel", null));
            assertFalse(detail.has("on_pipeline"), "the misspelled key never became a trigger");
            assertEquals("cdr_ingest", detail.get("onPipeline").asText(), "it is an inert parameter instead");

            JsonNode row = find(json(send(c.port, "GET", base + "/jobs", null)), "camel");
            assertTrue(row.get("onPipeline").isNull() || row.get("onPipeline").asText().isEmpty(),
                    "so the job reads as manual-only");
        }
    }

    /** The row for {@code name} in a {@code GET /jobs} listing. */
    private static JsonNode find(JsonNode list, String name) {
        for (JsonNode n : list) if (name.equals(n.path("name").asText())) return n;
        return fail("no job named '" + name + "' in " + list);
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception { return V1Body.of(r.body()); }
}
