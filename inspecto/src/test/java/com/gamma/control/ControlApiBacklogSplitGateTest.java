package com.gamma.control;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
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
 * Real-HTTP gate tests for the Job backlog routes ({@code POST /jobs/{id}/backlog/drain|pause|resume},
 * {@code POST /jobs/{id}/backlog/days/{day}/run|give-up}, {@code canOperateRuns}) and the split route
 * ({@code POST /pipelines/{id}/split-job-pipeline}, {@code canAuthorWorkbench}): no credential is refused 401, a
 * subject without the capability 403 naming it, and a subject with it is let through to the handler's own answer.
 */
class ControlApiBacklogSplitGateTest {

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
    void tearDown() {
        Authenticators.forTest(null);
        System.clearProperty("assist.write.root");
    }

    private HttpResponse<String> send(int port, String path, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json")
                .method("POST", BodyPublishers.ofString("{}"));
        if (headers.length > 0) b.headers(headers);
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private void assertGated(int port, String path, String capability, String holder, String other) throws Exception {
        assertEquals(401, send(port, path).statusCode(), path + " without a credential");
        HttpResponse<String> denied = send(port, path, "Authorization", "Bearer plain");
        assertEquals(403, denied.statusCode(), path + ": " + denied.body());
        assertTrue(denied.body().contains(capability), path + " names the capability: " + denied.body());
        assertEquals(403, send(port, path, "Authorization", "Bearer " + other).statusCode(),
                path + ": the other capability is not enough");
        int through = send(port, path, "Authorization", "Bearer " + holder).statusCode();
        assertNotEquals(401, through, path + " with " + capability);
        assertNotEquals(403, through, path + " with " + capability);
    }

    @Test
    void theBacklogAndSplitRoutesAreGatedByTheirCapability(@TempDir Path cfgDir, @TempDir Path writeRoot) throws Exception {
        Authenticators.forTest(FAKE);
        System.setProperty("assist.write.root", writeRoot.toString());
        Path pipe = PipelineConfigBatchTest.writePipeline(cfgDir, "");
        Files.createDirectories(cfgDir.resolve("inbox"));
        try (CollectorService svc = new CollectorService(List.of(pipe), List.of(), List.of(), 3600L, 1, null);
             ControlApi api = new ControlApi(svc, 0)) {
            api.start();
            int port = api.port();
            for (String path : List.of("/jobs/daily_job/backlog/drain", "/jobs/daily_job/backlog/pause",
                    "/jobs/daily_job/backlog/resume", "/jobs/daily_job/backlog/days/2026-11-01/run",
                    "/jobs/daily_job/backlog/days/2026-11-01/give-up")) {
                assertGated(port, path, "canOperateRuns", "operator", "author");
            }
            assertGated(port, "/pipelines/mini_etl/split-job-pipeline", "canAuthorWorkbench", "author", "operator");
        }
    }
}
