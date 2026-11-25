package com.gamma.control;

import com.gamma.access.Roles;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * The capability gates of the Runs ▸ Backlog controls (DATED-REFERENCE-RUNS-1 P4: drain, pause, resume, run a day, give
 * up waiting — {@code canOperateRuns}) and of splitting a Job's Pipeline (JOB-GRAPH-1 — {@code canAuthorWorkbench}),
 * over real HTTP with an ARMED Authenticator: no Subject is 401, an authenticated Subject without the capability is 403
 * naming it, and one holding it reaches the handler. Armed with seeded-role Subjects ({@link Roles#SEED}), as
 * {@code ControlApiIncidentFinishGateTest} is, so every refusal is proven against an authenticated Subject. Written when
 * these routes shipped without an armed test and {@code check-authgate-coverage} went red on master (2026-11-25).
 */
class ControlApiJobBacklogAndSplitGateTest {

    private final HttpClient client = HttpClient.newHttpClient();

    /** {@code Bearer <seeded role>} → Subject {@code u-<role>} carrying exactly that role's seeded grants. */
    private static final Authenticator FAKE = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) return Optional.empty();
        Roles.Def def = Roles.SEED.get(auth.substring("Bearer ".length()));
        return def == null ? Optional.empty() : Optional.of(new Subject("u-" + auth.substring(7), def.capabilities()));
    };

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
    }

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    private Ctx open(Path dir) throws Exception {
        Authenticators.forTest(FAKE);
        Path pipe = PipelineConfigBatchTest.writePipeline(Files.createDirectories(dir.resolve("cfg")), "");
        Path root = Files.createDirectories(dir.resolve("root"));
        System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> post(Ctx c, String path, String role) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json").POST(BodyPublishers.ofString("{}"));
        if (role != null) b.header("Authorization", "Bearer " + role);
        return client.send(b.build(), BodyHandlers.ofString());
    }

    /** No Subject → 401; {@code denied} → 403 naming {@code capability}; {@code allowed} → past the gate. */
    private void gated(Ctx c, String path, String capability, String denied, String allowed) throws Exception {
        assertEquals(401, post(c, path, null).statusCode(), path);
        HttpResponse<String> refused = post(c, path, denied);
        assertEquals(403, refused.statusCode(), path + " as " + denied + ": " + refused.body());
        assertTrue(refused.body().contains(capability), refused.body());
        int through = post(c, path, allowed).statusCode();
        assertTrue(through != 401 && through != 403, path + " as " + allowed + " stopped at the gate: " + through);
    }

    @Test
    void theBacklogControlsNeedCanOperateRuns(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            // literal paths, one per route: check-authgate-coverage reads them
            for (String path : List.of("/jobs/daily/backlog/drain", "/jobs/daily/backlog/pause", "/jobs/daily/backlog/resume",
                    "/jobs/daily/backlog/days/2026-11-01/run", "/jobs/daily/backlog/days/2026-11-01/give-up"))
                gated(c, path, Roles.CAN_OPERATE_RUNS, "business", "operations");
        }
    }

    @Test
    void splittingAJobsPipelineNeedsCanAuthorWorkbench(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            gated(c, "/pipelines/orders/split-job-pipeline", Roles.CAN_AUTHOR_WORKBENCH, "operations", "developer");
        }
    }
}
