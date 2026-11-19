package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code BASIC-ELT-PROFILE-1}, operator ruling 2026-11-19: a time of day is Basic's {@code TIME} column type, end to end
 * over real HTTP — Add data proposes {@code TIME} for {@code 09:30:00}, the import writes a Parquet {@code TIME} column the
 * Dataset reads back, and a row filter keeps a range of times. Synthetic fixtures only.
 */
class ControlApiOnboardTimeColumnTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private final List<String> ids = new ArrayList<>();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @AfterEach
    void cleanUp() {
        Path data = Path.of("database").toAbsolutePath();
        for (String id : ids) {
            OnboardRoutes.deleteTree(data.resolve(id));
            OnboardRoutes.deleteTree(data.resolve("inbox").resolve(id));
        }
        OnboardRoutes.deleteTree(data.resolve(OnboardRoutes.DRAFTS));
        for (Path p : List.of(data.resolve("inbox"), data))
            try {
                Files.deleteIfExists(p);
            } catch (java.io.IOException notEmpty) {
                // another fixture's data lives there
            }
    }

    private Ctx open(Path cfg, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path));
        b = body == null ? (method.equals("GET") ? b.GET() : b.method(method, BodyPublishers.noBody()))
                : b.method(method, BodyPublishers.ofByteArray(body.getBytes(StandardCharsets.UTF_8)));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r, int expected) throws Exception {
        assertEquals(expected, r.statusCode(), r.body());
        return V1Body.of(r.body());
    }

    private String uniqueId(String base) {
        String id = base + "_" + UUID.randomUUID().toString().substring(0, 8);
        ids.add(id);
        return id;
    }

    /** 240 shifts (enough for a sure proposal, OnboardDraft.MIN_SAMPLE), shift i starting at {@code (6 + i % 12):(i * 7 % 60):00}. */
    private static String shifts() {
        StringBuilder sb = new StringBuilder("shift_id,starts_at\n");
        for (int i = 1; i <= 240; i++) sb.append(String.format("S%04d,%02d:%02d:00\n", i, 6 + i % 12, i * 7 % 60));
        return sb.toString();
    }

    private static JsonNode column(JsonNode draft, String name) {
        for (JsonNode c : draft.get("columns")) if (name.equals(c.get("name").asText())) return c;
        throw new AssertionError("no " + name + " in " + draft.get("columns"));
    }

    /** The DuckDB type of {@code column} in the Parquet files the import wrote for {@code id}. */
    private static String parquetType(String id, String column) throws Exception {
        Path dir = Path.of("database").toAbsolutePath().resolve(id);
        List<Path> files;
        try (Stream<Path> s = Files.walk(dir)) {
            files = s.filter(p -> p.toString().endsWith(".parquet")).toList();
        }
        assertFalse(files.isEmpty(), "the import wrote Parquet under " + dir);
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT column_type FROM (DESCRIBE SELECT * FROM read_parquet('"
                     + files.get(0).toString().replace('\\', '/') + "')) WHERE column_name = '" + column + "'")) {
            assertTrue(rs.next(), column + " is a column of " + files.get(0));
            return rs.getString(1);
        }
    }

    @Test
    void aTimeOfDayIsProposedImportedAsTimeAndReadBack(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = uniqueId("shifts");
            JsonNode d = data(send(c, "POST", "/onboard/files?name=shifts.csv&kind=stream", shifts()), 200);
            JsonNode type = column(d, "starts_at").get("type");
            assertEquals("TIME", type.get("value").asText(), type.toString());
            assertEquals("high", type.get("confidence").asText(), type.toString());
            assertTrue(type.get("evidence").asText().contains("times of day"), type.toString());

            JsonNode s = data(send(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit",
                    "{\"name\":\"" + id + "\"}"), 200);
            assertEquals(240, s.get("rowsAfter").asLong(), s.toString());
            assertEquals("TIME", parquetType(id, "starts_at"));
            JsonNode rows = data(send(c, "GET", "/datasets/" + id + "/rows?limit=100", null), 200);
            assertTrue(rows.toString().contains("\"starts_at\":\"07:07\""), rows.toString());   // shift 1, read back as a time
        }
    }

    @Test
    void aRowFilterKeepsARangeOfTimes(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = uniqueId("morning");
            JsonNode d = data(send(c, "POST", "/onboard/files?name=shifts.csv&kind=stream", shifts()), 200);
            JsonNode s = data(send(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit",
                    "{\"name\":\"" + id + "\",\"filter\":[{\"column\":\"starts_at\",\"op\":\"range\",\"min\":\"09:00\",\"max\":\"11:59:59\"}]}"),
                    200);
            // starts 06..17 by i % 12: hours 9, 10 and 11 are i % 12 in {3, 4, 5} - three in every twelve shifts
            assertEquals(60, s.get("rowsAfter").asLong(), s.toString());

            JsonNode bad = data(send(c, "POST", "/onboard/files?name=shifts.csv&kind=stream", shifts()), 200);
            HttpResponse<String> refused = send(c, "POST", "/onboard/files/" + bad.get("draftId").asText() + "/commit",
                    "{\"name\":\"" + uniqueId("bad") + "\",\"filter\":[{\"column\":\"starts_at\",\"op\":\"eq\",\"value\":\"nine\"}]}");
            assertEquals(422, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains("a time of day"), refused.body());
        }
    }
}
