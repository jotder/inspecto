package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code GET /runs/{name}/errors?file=} over real HTTP — the rejected ROWS behind an
 * {@code error_rows} count (audit hole 2).
 *
 * <p>The audit ledgers carry counts, filenames and an error string, never row content. The content
 * existed all along in the companion {@code <base>_errors.csv} but only on disk, so an operator
 * without filesystem access could see THAT 37 rows failed and never WHICH. One test per gate plus
 * the two resolution paths that make one key work from both the Files and Quarantine tabs.
 */
class ControlApiRejectedRowsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        CollectorService svc = new CollectorService(List.of(pipeline(dir)), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    /** A minimal pipeline with errors/ and quarantine/ configured — the two places detail can live. */
    private Path pipeline(Path dir) throws Exception {
        PipelineConfigBatchTest.writePipeline(dir, "");          // creates dir/mini_schema.toon
        Path schema = dir.resolve("mini_schema.toon");
        String toon = """
            name: REJECTS_ETL
            dirs:
              poll: %1$s/inbox
              database: %1$s/db
              temp: %1$s/temp
              errors: %1$s/errors
              quarantine: %1$s/quarantine
              status_dir: %1$s/status
              log_dir: %1$s/logs
            output:
              format: CSV
            processing:
              threads: 1
              file_pattern: "glob:**/*.csv"
              schema_file: "%2$s"
              csv_settings:
                delimiter: ","
                skip_header_lines: 0
            """.formatted(dir, schema.toString().replace("\\", "/"));
        Path p = dir.resolve("rejects_pipeline.toon");
        Files.writeString(p, toon);
        return p;
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1" + path)).GET().build(),
                BodyHandlers.ofString());
    }

    private HttpResponse<String> errorsFor(int port, String file) throws Exception {
        return get(port, "/runs/rejects_etl/errors?file="
                + URLEncoder.encode(file, StandardCharsets.UTF_8));
    }

    /** A 2xx {@code /api/v1} body is the {@code {data, metadata, …}} envelope — unwrap it. */
    private JsonNode data(HttpResponse<String> r) throws Exception {
        JsonNode data = JSON.readTree(r.body()).get("data");
        assertNotNull(data, "a 2xx v1 response carries its payload under 'data': " + r.body());
        return data;
    }

    /** The real writer's shape (`DuckDbCsvIngester.writeRejects`), verbatim. */
    private static String errorsCsv() {
        return """
            line_number,column,reason,raw_line
            7,AMT,"CAST","a,notanumber,2020-04-03"
            9,,"TOO MANY COLUMNS","a,1.5,2020-04-03,extra"
            """;
    }

    // ── gates ──────────────────────────────────────────────────────────────────

    @Test
    void unknownPipelineIs404(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(404, get(c.port, "/runs/nope/errors?file=a.csv").statusCode());
        }
    }

    @Test
    void aMissingFileParamIs400(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(400, get(c.port, "/runs/rejects_etl/errors").statusCode());
            assertEquals(400, errorsFor(c.port, "   ").statusCode());
        }
    }

    /** A name is never a path: traversal is refused before anything is resolved. */
    @Test
    void aPathInTheFileParamIs403(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(403, errorsFor(c.port, "../../etc/passwd").statusCode());
            assertEquals(403, errorsFor(c.port, "sub/dir/a.csv").statusCode());
            assertEquals(403, errorsFor(c.port, "..\\a.csv").statusCode());
        }
    }

    /** No detail recorded is 404 — honestly distinct from "an empty list of rejects". */
    @Test
    void noDetailRecordedIs404(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(404, errorsFor(c.port, "never_seen.csv").statusCode());
        }
    }

    // ── the two resolution paths ───────────────────────────────────────────────

    /** Files tab: the input was ACCEPTED with rejects, so its detail stays in {@code dirs.errors()}. */
    @Test
    void servesRowsForAnAcceptedFileFromTheErrorsDir(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            Files.createDirectories(dir.resolve("errors"));
            Files.writeString(dir.resolve("errors/feed_errors.csv"), errorsCsv());

            HttpResponse<String> r = errorsFor(c.port, "feed.csv.gz");   // extensions stripped
            assertEquals(200, r.statusCode(), r.body());
            JsonNode b = data(r);
            assertEquals("feed.csv.gz", b.get("file").asText());
            assertEquals("feed_errors.csv", b.get("errorsFile").asText());
            assertEquals(2, b.get("rowCount").asInt());
            assertFalse(b.get("truncated").asBoolean());
            // the ROW CONTENT — the whole point of the route
            JsonNode first = b.get("rows").get(0);
            assertEquals("7", first.get("line_number").asText());
            assertEquals("AMT", first.get("column").asText());
            assertEquals("CAST", first.get("reason").asText());
            assertEquals("a,notanumber,2020-04-03", first.get("raw_line").asText());
        }
    }

    /** Quarantine tab: the input was rejected outright, so its detail moved beside it in the tree. */
    @Test
    void servesRowsForAQuarantinedFileFromTheQuarantineTree(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            Path reasonDir = dir.resolve("quarantine/providerA/field_mismatch");
            Files.createDirectories(reasonDir);
            Files.writeString(reasonDir.resolve("bad_errors.csv"), errorsCsv());

            HttpResponse<String> r = errorsFor(c.port, "bad.csv");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode b = data(r);
            assertEquals(2, b.get("rowCount").asInt());
            assertEquals("TOO MANY COLUMNS", b.get("rows").get(1).get("reason").asText());
        }
    }

    /** A diagnostic sample, not an export: a huge reject file is capped and says so. */
    @Test
    void aHugeRejectFileIsCappedAndFlagged(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            StringBuilder csv = new StringBuilder("line_number,column,reason,raw_line\n");
            for (int i = 1; i <= 600; i++) csv.append(i).append(",AMT,\"CAST\",\"x\"\n");
            Files.createDirectories(dir.resolve("errors"));
            Files.writeString(dir.resolve("errors/big_errors.csv"), csv.toString());

            JsonNode b = data(errorsFor(c.port, "big.csv"));
            assertEquals(600, b.get("rowCount").asInt(), "the true total is still reported");
            assertTrue(b.get("truncated").asBoolean());
            assertEquals(500, b.get("rows").size(), "the response body is bounded");
        }
    }
}
