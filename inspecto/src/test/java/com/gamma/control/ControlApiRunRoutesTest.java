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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-HTTP tests for {@code RunRoutes} (RUN-ROUTES-TEST-1): the write paths — register
 * ({@code POST /runs}, {@code canAuthorWorkbench}), trigger/pause/resume ({@code POST
 * /runs/{name}/trigger|pause|resume}, {@code canOperateRuns}) — each proven through the real gate
 * chain (same technique as {@link ControlApiRequirementTest}): an authenticated subject WITHOUT the
 * capability is refused 403, one WITH it is let through to the handler's own result.
 */
class ControlApiRunRoutesTest {

    private final HttpClient client = HttpClient.newHttpClient();

    /** {@code Bearer author} → canAuthorWorkbench; {@code Bearer operator} → canOperateRuns; {@code Bearer plain} → neither. */
    private static final Authenticator FAKE = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if ("Bearer author".equals(auth)) return Optional.of(new Subject("jdoe", Set.of("canAuthorWorkbench")));
        if ("Bearer operator".equals(auth)) return Optional.of(new Subject("olly", Set.of("canOperateRuns")));
        if ("Bearer plain".equals(auth)) return Optional.of(new Subject("nobody", Set.of()));
        return Optional.empty();
    };

    @AfterEach
    void tearDown() { Authenticators.forTest(null); }

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path cfgDir) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfgDir, "");
        Files.createDirectories(cfgDir.resolve("inbox"));
        CollectorService svc = new CollectorService(List.of(pipe), List.of(), List.of(), 3600L, 1, null);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    /** A second, differently-named pipeline config on disk under a write root, for the register route. */
    private Path secondPipelineConfig(Path dir) throws Exception {
        Path p = PipelineConfigBatchTest.writePipeline(dir, "");
        Files.createDirectories(dir.resolve("inbox"));
        String toon = Files.readString(p).replace("MINI_ETL", "SECOND_ETL");
        Files.writeString(p, toon);
        return p;
    }

    private HttpResponse<String> send(int port, String method, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (headers.length > 0) b.headers(headers);
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception {
        return V1Body.of(r.body());
    }

    // ── POST /runs — register, canAuthorWorkbench ──────────────────────────────────

    @Test
    void registerRequiresCanAuthorWorkbench(@TempDir Path cfgDir, @TempDir Path writeRoot) throws Exception {
        Authenticators.forTest(FAKE);
        System.setProperty("assist.write.root", writeRoot.toString());
        try (Ctx c = open(cfgDir)) {
            Path configPath = secondPipelineConfig(writeRoot);
            String body = "{\"configPath\":\"" + writeRoot.relativize(configPath).toString().replace("\\", "/") + "\"}";

            // no credential → 401
            assertEquals(401, send(c.port, "POST", "/runs", body).statusCode());

            // authenticated, no capability → 403, naming the capability
            HttpResponse<String> denied = send(c.port, "POST", "/runs", body,
                    "Authorization", "Bearer plain");
            assertEquals(403, denied.statusCode(), denied.body());
            assertTrue(denied.body().contains("canAuthorWorkbench"), "the refusal names the capability: " + denied.body());

            // canOperateRuns alone (the OTHER run capability) is not sufficient — still 403
            assertEquals(403, send(c.port, "POST", "/runs", body,
                    "Authorization", "Bearer operator").statusCode());

            // canAuthorWorkbench → through the gate to the handler; registration succeeds
            HttpResponse<String> ok = send(c.port, "POST", "/runs", body,
                    "Authorization", "Bearer author");
            assertEquals(200, ok.statusCode(), ok.body());
            JsonNode registered = json(ok);
            assertTrue(registered.get("registered").asBoolean());
            assertEquals("second_etl", registered.get("id").asText());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    // ── POST /runs/{name}/trigger|pause|resume — canOperateRuns ─────────────────────

    @Test
    void triggerRequiresCanOperateRuns(@TempDir Path cfgDir) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(cfgDir)) {
            assertEquals(401, send(c.port, "POST", "/runs/mini_etl/trigger", null).statusCode());

            HttpResponse<String> denied = send(c.port, "POST", "/runs/mini_etl/trigger", null,
                    "Authorization", "Bearer plain");
            assertEquals(403, denied.statusCode(), denied.body());
            assertTrue(denied.body().contains("canOperateRuns"), denied.body());

            // canAuthorWorkbench alone (the OTHER run capability) is not sufficient — still 403
            assertEquals(403, send(c.port, "POST", "/runs/mini_etl/trigger", null,
                    "Authorization", "Bearer author").statusCode());

            HttpResponse<String> ok = send(c.port, "POST", "/runs/mini_etl/trigger", null,
                    "Authorization", "Bearer operator");
            assertNotEquals(403, ok.statusCode(), ok.body());
            assertNotEquals(401, ok.statusCode(), ok.body());
        }
    }

    @Test
    void pauseAndResumeRequireCanOperateRuns(@TempDir Path cfgDir) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(cfgDir)) {
            // pause
            assertEquals(401, send(c.port, "POST", "/runs/mini_etl/pause", null).statusCode());
            HttpResponse<String> pauseDenied = send(c.port, "POST", "/runs/mini_etl/pause", null,
                    "Authorization", "Bearer plain");
            assertEquals(403, pauseDenied.statusCode(), pauseDenied.body());
            assertTrue(pauseDenied.body().contains("canOperateRuns"), pauseDenied.body());

            HttpResponse<String> pauseOk = send(c.port, "POST", "/runs/mini_etl/pause", null,
                    "Authorization", "Bearer operator");
            assertEquals(200, pauseOk.statusCode(), pauseOk.body());
            assertTrue(json(pauseOk).get("paused").asBoolean());

            // resume
            assertEquals(401, send(c.port, "POST", "/runs/mini_etl/resume", null).statusCode());
            HttpResponse<String> resumeDenied = send(c.port, "POST", "/runs/mini_etl/resume", null,
                    "Authorization", "Bearer plain");
            assertEquals(403, resumeDenied.statusCode(), resumeDenied.body());
            assertTrue(resumeDenied.body().contains("canOperateRuns"), resumeDenied.body());

            HttpResponse<String> resumeOk = send(c.port, "POST", "/runs/mini_etl/resume", null,
                    "Authorization", "Bearer operator");
            assertEquals(200, resumeOk.statusCode(), resumeOk.body());
            assertFalse(json(resumeOk).get("paused").asBoolean());
        }
    }

    // ── GET /status, GET /report — authenticated reads, but no capability gate ─────

    @Test
    void statusAndReportRequireNoCapability(@TempDir Path cfgDir) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(cfgDir)) {
            // no credential at all → 401, same as any other route once an Authenticator is armed
            assertEquals(401, send(c.port, "GET", "/status", null).statusCode());
            assertEquals(401, send(c.port, "GET", "/report", null).statusCode());

            // authenticated with NO capabilities at all — these routes carry no withCapability gate
            assertEquals(200, send(c.port, "GET", "/status", null,
                    "Authorization", "Bearer plain").statusCode());
            assertEquals(200, send(c.port, "GET", "/report", null,
                    "Authorization", "Bearer plain").statusCode());
        }
    }
}
