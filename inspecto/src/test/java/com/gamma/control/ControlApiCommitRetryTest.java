package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.inspector.CollectorProcessor;
import com.gamma.inspector.CommitRetry;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * The X1 retry affordance over real HTTP ({@code GET /runs/{name}/retries},
 * {@code POST /runs/{name}/retries/retry-now|cancel}; decisions 2026-09-25). The mutating routes are proven
 * through the real capability chain — an authenticated Subject WITHOUT {@code canOperateRuns} is refused 403
 * (without a Subject {@code withCapability} is a no-op and would prove nothing) — then the happy paths, the
 * "no retry state" vs "none pending" distinction, the path jail, and the already-quarantined refusal.
 */
class ControlApiCommitRetryTest {

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
        System.setProperty("ingest.retry.max", "5");
        System.setProperty("ingest.retry.backoff.initialMs", "3600000");   // an hour: never due by itself
    }

    @AfterEach
    void tearDown() {
        Authenticators.forTest(null);
        for (String k : List.of("ingest.retry.max", "ingest.retry.backoff.initialMs")) System.clearProperty(k);
    }

    private record Ctx(CollectorService svc, ControlApi api, int port, PipelineConfig cfg) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
        Path feed() { return Path.of(cfg.dirs().poll()).resolve("feed.csv"); }
    }

    /** A pipeline whose feed.csv has failed ONE commit (dirs.backup is a FILE — the deterministic fault). */
    private Ctx open(Path dir, boolean statusDir) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(dir, "");
        if (!statusDir)
            Files.writeString(pipe, String.join("\n",
                    Files.readString(pipe).lines().filter(l -> !l.contains("status_dir")).toList()) + "\n");
        PipelineConfig cfg = PipelineConfig.load(pipe.toString());
        Files.createDirectories(dir.resolve("inbox"));
        Files.writeString(dir.resolve("inbox/feed.csv"), "ID,AMT,EVENT_DATE\nr1,1.0,2020-04-03\n");
        Files.writeString(dir.resolve("backup"), "not a directory");
        if (statusDir) CollectorProcessor.run(cfg);
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

    private static String fileBody(String file) { return "{\"file\":\"" + file + "\"}"; }

    @Test
    void bothMutatingRoutesAreGatedOnCanOperateRuns(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            for (String act : List.of("retry-now", "cancel")) {
                String path = "/runs/mini_etl/retries/" + act;
                assertEquals(401, send(c.port, "POST", path, fileBody("feed.csv"), null).statusCode(), act);
                HttpResponse<String> denied = send(c.port, "POST", path, fileBody("feed.csv"), "plain");
                assertEquals(403, denied.statusCode(), act + " " + denied.body());
                assertTrue(denied.body().contains("canOperateRuns"), denied.body());
                assertEquals(403, send(c.port, "POST", path, fileBody("feed.csv"), "author").statusCode(),
                        "canAuthorWorkbench is not an operate capability");
            }
            assertTrue(Files.exists(c.feed()), "a refused cancel moves nothing");
            assertEquals(1, CommitRetry.recordFor(c.feed().toFile(), c.cfg).attempts);
            assertNotNull(CommitRetry.recordFor(c.feed().toFile(), c.cfg).nextRetryAt, "a refused retry-now changes nothing");
        }
    }

    @Test
    void listShowsThePendingFileByPollRelativePath(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            HttpResponse<String> r = send(c.port, "GET", "/runs/mini_etl/retries", null, "plain");
            assertEquals(200, r.statusCode(), "a read is open by policy: " + r.body());
            JsonNode b = V1Body.of(r.body());
            assertTrue(b.get("keepsRetryState").asBoolean());
            assertEquals(1, b.get("total").asInt());
            assertFalse(b.get("truncated").asBoolean());
            assertEquals(5, b.get("policy").get("maxAttempts").asInt());
            JsonNode p = b.get("retries").get(0);
            assertEquals("feed.csv", p.get("file").asText());
            assertEquals(1, p.get("attempts").asInt());
            assertFalse(p.get("due").asBoolean());
            assertTrue(p.get("lastError").asText().contains("commit failed"), p.toString());

            JsonNode none = V1Body.of(send(c.port, "GET", "/runs/mini_etl/retries?limit=0", null, "plain").body());
            assertEquals(1, none.get("total").asInt(), "the true total survives the bound");
            assertTrue(none.get("truncated").asBoolean());
            assertEquals(400, send(c.port, "GET", "/runs/mini_etl/retries?limit=x", null, "plain").statusCode());
            assertEquals(404, send(c.port, "GET", "/runs/nope/retries", null, "plain").statusCode());
        }
    }

    @Test
    void noStatusDirIsNotTheSameAnswerAsNothingPending(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, false)) {
            JsonNode b = V1Body.of(send(c.port, "GET", "/runs/mini_etl/retries", null, "plain").body());
            assertFalse(b.get("keepsRetryState").asBoolean());
            assertEquals(0, b.get("total").asInt());
            assertTrue(b.get("note").asText().contains("without bound"), b.toString());

            HttpResponse<String> act = send(c.port, "POST", "/runs/mini_etl/retries/cancel", fileBody("feed.csv"), "operator");
            assertEquals(409, act.statusCode(), act.body());
            assertTrue(act.body().contains("keeps no retry state"), act.body());
            assertTrue(Files.exists(c.feed()), "nothing was quarantined");
        }
    }

    @Test
    void retryNowClearsTheBackoffAndSaysTheAttemptCountIsKept(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            HttpResponse<String> r = send(c.port, "POST", "/runs/mini_etl/retries/retry-now", fileBody("feed.csv"), "operator");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode b = V1Body.of(r.body());
            assertEquals("rescheduled", b.get("outcome").asText());
            assertEquals(1, b.get("attempts").asInt());
            assertTrue(b.get("attemptsKept").asBoolean());
            assertTrue(b.get("note").asText().contains("attempt count is kept"), b.toString());
            CommitRetry.Record rec = CommitRetry.recordFor(c.feed().toFile(), c.cfg);
            assertEquals(1, rec.attempts, "Q2: attempts are not reset");
            assertNull(rec.nextRetryAt, "the backoff is cleared");
            assertTrue(V1Body.of(send(c.port, "GET", "/runs/mini_etl/retries", null, "plain").body())
                    .get("retries").get(0).get("due").asBoolean());
        }
    }

    @Test
    void cancelQuarantinesUnderRetryCancelledThenSaysItIsAlreadyQuarantined(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            HttpResponse<String> r = send(c.port, "POST", "/runs/mini_etl/retries/cancel", fileBody("feed.csv"), "operator");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode b = V1Body.of(r.body());
            assertEquals("cancelled", b.get("outcome").asText());
            assertEquals("retry_cancelled", b.get("quarantineReason").asText());
            assertFalse(Files.exists(c.feed()));
            assertTrue(Files.exists(dir.resolve("quarantine/retry_cancelled/feed.csv")));
            assertEquals(0, V1Body.of(send(c.port, "GET", "/runs/mini_etl/retries", null, "plain").body()).get("total").asInt());

            for (String act : List.of("cancel", "retry-now")) {
                HttpResponse<String> again = send(c.port, "POST", "/runs/mini_etl/retries/" + act, fileBody("feed.csv"), "operator");
                assertEquals(409, again.statusCode(), again.body());
                assertTrue(again.body().contains("already quarantined under 'retry_cancelled'"), again.body());
            }
        }
    }

    @Test
    void inputGates(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            String cancel = "/runs/mini_etl/retries/cancel";
            assertEquals(404, send(c.port, "POST", "/runs/nope/retries/cancel", fileBody("feed.csv"), "operator").statusCode());
            assertEquals(400, send(c.port, "POST", cancel, "{}", "operator").statusCode());
            assertEquals(403, send(c.port, "POST", cancel, fileBody("../feed.csv"), "operator").statusCode());
            assertEquals(403, send(c.port, "POST", cancel, fileBody("sub/../../feed.csv"), "operator").statusCode());
            assertEquals(403, send(c.port, "POST", cancel,
                    fileBody(dir.resolve("inbox/feed.csv").toString().replace("\\", "/")), "operator").statusCode());
            HttpResponse<String> never = send(c.port, "POST", cancel, fileBody("never.csv"), "operator");
            assertEquals(404, never.statusCode(), never.body());
            assertTrue(Files.exists(c.feed()), "no gate moved the pending file");
        }
    }
}
