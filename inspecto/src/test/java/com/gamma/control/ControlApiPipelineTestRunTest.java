package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code POST /pipelines/authored/{id}/run?to=} — run-to-here (Build→Test→Run Step 5c) over real HTTP.
 *
 * <p>The security-critical case here is {@link #refusesAFileThatEscapesTheSourceRoot}: the {@code files}
 * body is caller-supplied, so without a jail this route is an arbitrary-file-read. The jail root is
 * derived from the pipeline's own config and never from the request.
 */
class ControlApiPipelineTestRunTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    /**
     * A registered CSV pipeline whose inbox is {@code <dir>/inbox}. ⚠ Its id is <b>{@code test_etl}</b>,
     * not the {@code name: TEST_ETL} in the toon — {@code PipelineConfigParser:80} lower-cases an
     * undeclared id.
     */
    private Ctx open(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    /** Two good rows in the pipeline's inbox; returns the inbox dir. */
    private Path seedInbox(Path dir) throws Exception {
        Path inbox = Files.createDirectories(dir.resolve("inbox"));
        Files.writeString(inbox.resolve("a.csv"),
                "ID,AMT,EVENT_DATE\na1,1.0,2020-04-03\na2,2.0,2020-04-03\n");
        return inbox;
    }

    @Test
    void runsOverARealInboxFileAndReportsWhatItProduced(@TempDir Path dir) throws Exception {
        seedInbox(dir);
        try (Ctx c = open(dir)) {
            HttpResponse<String> r = send(c.port, "POST",
                    "/pipelines/authored/test_etl/run", "{\"files\":[\"a.csv\"]}");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode b = json(r);

            assertEquals("a.csv", b.get("files").get(0).asText(), "the picked files are echoed back");
            assertFalse(b.get("relations").isEmpty(), "the graph preview should report relations");
            assertFalse(b.get("output").isNull(), "the scratch write should be reported");
            assertEquals(2, b.get("output").get("rowCount").asInt(),
                    "the output row count is the FULL parse, not the sample");
            assertTrue(b.get("output").get("path").asText().length() > 0);
        }
    }

    /**
     * The reason this route has a jail at all. {@code files} is caller-supplied, so a {@code ../} escape
     * must be refused rather than read — otherwise this is an arbitrary-file-read over HTTP.
     */
    @Test
    void refusesAFileThatEscapesTheSourceRoot(@TempDir Path dir) throws Exception {
        seedInbox(dir);
        // A real, readable file OUTSIDE the pipeline's inbox — the thing an attacker would target.
        Path secret = dir.resolve("secret.csv");
        Files.writeString(secret, "ID,AMT,EVENT_DATE\nx,1.0,2020-04-03\n");

        try (Ctx c = open(dir)) {
            for (String escape : List.of("../secret.csv", "../../secret.csv",
                    secret.toAbsolutePath().toString().replace('\\', '/'))) {
                HttpResponse<String> r = send(c.port, "POST",
                        "/pipelines/authored/test_etl/run", "{\"files\":[\"" + escape + "\"]}");
                assertEquals(403, r.statusCode(),
                        "'" + escape + "' must be refused, not read — body was: " + r.body());
            }
            assertTrue(Files.exists(secret), "the out-of-jail file is untouched");
        }
    }

    @Test
    void tellsTheOperatorThatStoppingAtAStepIsNotSupportedYet(@TempDir Path dir) throws Exception {
        seedInbox(dir);
        try (Ctx c = open(dir)) {
            HttpResponse<String> r = send(c.port, "POST",
                    "/pipelines/authored/test_etl/run?to=some_node", "{\"files\":[\"a.csv\"]}");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode b = json(r);
            assertEquals("some_node", b.get("toNode").asText());
            // Echoing toNode while having run the whole graph would paint the canvas green on nodes that
            // were never bounded, so the response has to say so.
            assertTrue(warnings(b).stream().anyMatch(w -> w.contains("whole graph")),
                    "a to= run must warn that the cutoff is not implemented: " + warnings(b));
        }
    }

    @Test
    void leavesTheInboxAndTheProductionOutputUntouched(@TempDir Path dir) throws Exception {
        Path inbox = seedInbox(dir);
        try (Ctx c = open(dir)) {
            assertEquals(200, send(c.port, "POST",
                    "/pipelines/authored/test_etl/run", "{\"files\":[\"a.csv\"]}").statusCode());

            assertTrue(Files.exists(inbox.resolve("a.csv")), "a TEST run must not consume the inbox");
            Path db = dir.resolve("db");
            if (Files.exists(db)) {
                try (Stream<Path> s = Files.walk(db)) {
                    assertFalse(s.anyMatch(Files::isRegularFile),
                            "nothing may land in the production database dir");
                }
            }
        }
    }

    @Test
    void rejectsAnEmptyOrMissingFilesListAndAnUnknownPipeline(@TempDir Path dir) throws Exception {
        seedInbox(dir);
        try (Ctx c = open(dir)) {
            assertEquals(400, send(c.port, "POST", "/pipelines/authored/test_etl/run", "{}").statusCode());
            assertEquals(400, send(c.port, "POST", "/pipelines/authored/test_etl/run",
                    "{\"files\":[]}").statusCode());
            assertEquals(404, send(c.port, "POST", "/pipelines/authored/ghost/run",
                    "{\"files\":[\"a.csv\"]}").statusCode());
            assertEquals(404, send(c.port, "POST", "/pipelines/authored/test_etl/run",
                    "{\"files\":[\"nope.csv\"]}").statusCode(), "a file inside the jail but absent is 404");
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static List<String> warnings(JsonNode b) {
        return JSON.convertValue(b.get("warnings"), JSON.getTypeFactory()
                .constructCollectionType(List.class, String.class));
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder rq = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        return client.send(rq.build(), BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception {
        JsonNode n = JSON.readTree(r.body());
        return n.has("data") ? n.get("data") : n;
    }
}
