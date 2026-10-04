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

import static org.junit.jupiter.api.Assertions.*;

/**
 * ASSURE-AUDIT-CHAIN-1: an {@code event_prune} job decides which audit rows may lawfully vanish (its chained prune
 * record is what {@code /audit/verify} accepts for anchored rows being gone), so authoring or editing one needs
 * {@code canAdminister} — through {@code /jobs} and {@code /config/write} alike. Real HTTP, armed Subjects.
 */
class ControlApiEventPruneJobGateTest {

    // CollectorService over SpaceRoot.legacy() resolves its job audit dir CWD-relative ("jobs_audit") unless
    // -Djobs.audit.dir is set — read at construction AND lazily by jobServiceOrCreate(), so it spans the class.
    @TempDir static Path auditDir;
    private static String priorAuditDir;

    @org.junit.jupiter.api.BeforeAll
    static void pinJobsAuditDir() {
        priorAuditDir = System.getProperty("jobs.audit.dir");
        System.setProperty("jobs.audit.dir", auditDir.resolve("jobs_audit").toString());
    }

    @org.junit.jupiter.api.AfterAll
    static void restoreJobsAuditDir() {
        if (priorAuditDir != null) System.setProperty("jobs.audit.dir", priorAuditDir);
        else System.clearProperty("jobs.audit.dir");
    }

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    @BeforeEach
    void arm() {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case "Bearer author" -> Optional.of(new Subject("author-1", Set.of("canAuthorWorkbench")));
            case "Bearer admin" -> Optional.of(new Subject("admin-1", Set.of("canAuthorWorkbench", "canAdminister")));
            default -> Optional.empty();
        });
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
    }

    private Ctx open(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        System.setProperty("assist.write.root", Files.createDirectories(dir.resolve("wr")).toString());
        try {
            CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json").header("Authorization", auth);
        return client.send(b.method(method, BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private static final String PRUNE =
            "{\"name\":\"retention\",\"type\":\"maintenance\",\"task\":\"event_prune\",\"retention_days\":\"1\"}";

    @Test
    void anAuthorCannotCreateAnEventPruneJobButAnAdministratorCan(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> author = send(c, "POST", "/jobs", PRUNE, "Bearer author");
            assertEquals(403, author.statusCode(), author.body());
            assertTrue(author.body().contains("canAdminister"), author.body());
            // an ordinary maintenance job stays an author's
            assertTrue(send(c, "POST", "/jobs", "{\"name\":\"tidy\",\"type\":\"maintenance\",\"task\":\"cleanup\"}",
                    "Bearer author").statusCode() < 300);
            HttpResponse<String> admin = send(c, "POST", "/jobs", PRUNE, "Bearer admin");
            assertTrue(admin.statusCode() < 300, admin.body());
        }
    }

    /** ASSURE-AUDIT-CHAIN-RESIDUALS-1 (1): the anchor export is an administrator's job, and its out_dir is jailed. */
    @Test
    void theAuditAnchorExportJobIsAnAdministratorsAndItsOutDirIsJailed(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String job = "{\"name\":\"anchors\",\"type\":\"maintenance\",\"task\":\"audit_anchor_export\",\"out_dir\":\"%s\"}";
            HttpResponse<String> author = send(c, "POST", "/jobs", job.formatted("exports"), "Bearer author");
            assertEquals(403, author.statusCode(), author.body());
            assertTrue(author.body().contains("canAdminister"), author.body());
            HttpResponse<String> escape = send(c, "POST", "/jobs",
                    job.formatted(Path.of(System.getProperty("user.home")).resolve("zz-anchor-escape-probe").toString().replace("\\", "/")), "Bearer admin");
            assertEquals(422, escape.statusCode(), escape.body());
            HttpResponse<String> admin = send(c, "POST", "/jobs", job.formatted("exports"), "Bearer admin");
            assertTrue(admin.statusCode() < 300, admin.body());
        }
    }

    @Test
    void anAuthorCannotTurnAJobIntoAnEventPruneThroughConfigWrite(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String body = "{\"type\":\"job\",\"config\":{\"job\":" + PRUNE + "}}";
            HttpResponse<String> author = send(c, "POST", "/config/write", body, "Bearer author");
            assertEquals(403, author.statusCode(), author.body());
        }
    }
}
