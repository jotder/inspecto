package com.gamma.control;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SCHEDULE-EXPORT-DASHBOARD-SCOPE-1 (declined until demand): a legacy report schedule that still carries
 * {@code dashboardId} must FAIL its Run with a clear reason over real HTTP — before this it ran to SUCCESS
 * as a status snapshot and delivered nothing. Real HTTP, an armed Subject.
 */
class ControlApiLegacyDashboardScheduleTest {

    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void arm() {
        Authenticators.forTest(ex -> {
            if (!"Bearer admin".equals(ex.getRequestHeaders().getFirst("Authorization"))) return Optional.empty();
            ComponentAccess.heldRoles(ex, Set.of("admin"));
            return Optional.of(new Subject("admin-1", Set.of("canAuthorWorkbench", "canOperateRuns", "canAdminister")));
        });
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
        System.clearProperty("jobs.audit.dir");
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json").header("Authorization", "Bearer admin")
                .method(method, BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    @Test
    void aLegacyDashboardScheduleRunFailsWithAClearReason(@TempDir Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        Path wr = Files.createDirectories(dir.resolve("wr"));
        System.setProperty("assist.write.root", wr.toString());
        System.setProperty("jobs.audit.dir", dir.resolve("jobs_audit").toString());
        CollectorService svc;
        ControlApi api;
        try {
            svc = new CollectorService(List.of(toon), 3600, 1);
            api = new ControlApi(svc, 0);
            api.start();
        } finally {
            System.clearProperty("assist.write.root");
        }
        try {
            int port = api.port();
            HttpResponse<String> save = send(port, "POST", "/jobs",
                    "{\"name\":\"daily_cdr_export\",\"type\":\"report\",\"reportKind\":\"dashboard\","
                            + "\"dashboardId\":\"cdr_overview\",\"format\":\"csv\"}");
            assertTrue(save.statusCode() < 300, save.body());

            HttpResponse<String> fire = send(port, "POST", "/jobs/daily_cdr_export/trigger", "");
            assertEquals(202, fire.statusCode(), fire.body());
            Matcher m = Pattern.compile("\"runId\"\\s*:\\s*\"([^\"]+)\"").matcher(fire.body());
            assertTrue(m.find(), fire.body());
            long deadline = System.currentTimeMillis() + 20_000;
            String run = "";
            while (System.currentTimeMillis() < deadline) {
                run = send(port, "GET", "/jobs/runs/" + m.group(1), "").body();
                if (run.contains("\"status\"") && !run.contains("RUNNING")) break;
                Thread.sleep(100);
            }
            assertFalse(run.contains("SUCCESS"), "a dashboard schedule must not succeed silently: " + run);
            assertTrue(run.contains("FAILED") || run.contains("ERROR"), run);
            assertTrue(run.contains("Dashboard export is not supported"), "the reason reaches the Run: " + run);
        } finally {
            api.close();
            svc.close();
        }
    }
}
