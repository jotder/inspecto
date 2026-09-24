package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code POST /runs/{name}/replay-rejects} over real HTTP (EXECUTION-RESIDUALS X4): replay one file's rejected
 * records from its reject sidecar as a new Consignment. Every gate, through the real capability chain — an
 * authenticated Subject WITHOUT {@code canOperateRuns} is refused 403; without a Subject {@code withCapability}
 * would be a no-op and prove nothing — then the happy path and the idempotence refusal.
 */
class ControlApiReplayRejectsTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private static final Authenticator FAKE = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if ("Bearer author".equals(auth)) return Optional.of(new Subject("jdoe", Set.of("canAuthorWorkbench")));
        if ("Bearer operator".equals(auth)) return Optional.of(new Subject("olly", Set.of("canOperateRuns")));
        if ("Bearer plain".equals(auth)) return Optional.of(new Subject("nobody", Set.of()));
        return Optional.empty();
    };

    @BeforeEach
    void auth() { Authenticators.forTest(FAKE); }

    @AfterEach
    void tearDown() { Authenticators.forTest(null); }

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(dir, "");
        Files.createDirectories(dir.resolve("inbox"));
        CollectorService svc = new CollectorService(List.of(pipe), List.of(), List.of(), 3600L, 1, null);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    private HttpResponse<String> replay(int port, String pipeline, String body, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/runs/" + pipeline + "/replay-rejects"))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return client.send(b.build(), BodyHandlers.ofString());
    }

    /** A sidecar as the Java ingester writes it: two records that parse under the (fixed) mini schema. */
    private static void sidecar(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("errors"));
        Files.writeString(dir.resolve("errors/feed_errors.csv"), """
                line_number,reason,raw_line
                3,"Insufficient columns (expected >3, found 3)","r1,1.5,2020-04-03"
                5,"Insufficient columns (expected >3, found 3)","r2,2.5,2020-04-03"
                """);
    }

    private static String fileBody(String file) { return "{\"file\":\"" + file + "\"}"; }

    @Test
    void theCapabilityGateRefusesWithoutCanOperateRuns(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            sidecar(dir);
            assertEquals(401, replay(c.port, "mini_etl", fileBody("feed.csv"), null).statusCode());
            HttpResponse<String> denied = replay(c.port, "mini_etl", fileBody("feed.csv"), "plain");
            assertEquals(403, denied.statusCode(), denied.body());
            assertTrue(denied.body().contains("canOperateRuns"), denied.body());
            assertEquals(403, replay(c.port, "mini_etl", fileBody("feed.csv"), "author").statusCode(),
                    "canAuthorWorkbench is not an operate capability");
            assertFalse(Files.exists(dir.resolve("status/replays")), "a refused call claims nothing");
        }
    }

    @Test
    void unknownPipelineIs404(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(404, replay(c.port, "nope", fileBody("feed.csv"), "operator").statusCode());
        }
    }

    @Test
    void aMissingFileIs400(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(400, replay(c.port, "mini_etl", "{}", "operator").statusCode());
            assertEquals(400, replay(c.port, "mini_etl", fileBody("  "), "operator").statusCode());
        }
    }

    @Test
    void aPathIsNeverAFileName403(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(403, replay(c.port, "mini_etl", fileBody("../feed.csv"), "operator").statusCode());
            assertEquals(403, replay(c.port, "mini_etl", fileBody("sub/feed.csv"), "operator").statusCode());
        }
    }

    @Test
    void noSidecarIs404(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(404, replay(c.port, "mini_etl", fileBody("never.csv"), "operator").statusCode());
        }
    }

    @Test
    void aSidecarWithoutRawLinesIs422(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            Files.createDirectories(dir.resolve("errors"));
            Files.writeString(dir.resolve("errors/feed_errors.csv"), "line_number,reason\n3,\"bad\"\n");
            assertEquals(422, replay(c.port, "mini_etl", fileBody("feed.csv"), "operator").statusCode());
        }
    }

    @Test
    void replaysTheRejectedRecordsOnceAndRefusesTheSecondTime(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            sidecar(dir);

            HttpResponse<String> ok = replay(c.port, "mini_etl", fileBody("feed.csv"), "operator");
            assertEquals(200, ok.statusCode(), ok.body());
            JsonNode b = V1Body.of(ok.body());
            assertEquals("SUCCESS", b.get("status").asText(), b.toString());
            assertEquals(2, b.get("records").asInt(), b.toString());
            assertEquals(2, b.get("outputRows").asLong(), b.toString());
            assertTrue(b.get("replayFile").asText().startsWith("feed__replay_"), b.toString());
            assertFalse(b.get("batchId").asText().isBlank(), b.toString());
            try (Stream<Path> w = Files.walk(dir.resolve("db"))) {
                String landed = String.join("\n", w.filter(p -> p.toString().endsWith(".csv"))
                        .map(p -> { try { return Files.readString(p); } catch (Exception x) { throw new RuntimeException(x); } })
                        .toList());
                assertTrue(landed.contains("r1") && landed.contains("r2"), landed);
            }

            HttpResponse<String> again = replay(c.port, "mini_etl", fileBody("feed.csv"), "operator");
            assertEquals(409, again.statusCode(), again.body());
            assertTrue(again.body().contains("already replayed"), again.body());
        }
    }
}
