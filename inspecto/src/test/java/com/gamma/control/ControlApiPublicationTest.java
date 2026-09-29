package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.job.PublicationDestinations;
import com.gamma.service.CollectorService;
import com.gamma.service.ReservedConfigPaths;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ASSURE-BI-PUBLICATION-1 (operator 2026-09-29): the per-Space publication destination allowlist
 * ({@code /settings/publication-destinations}, canAdminister, empty by default, reserved from imports) and the
 * MANDATORY four-eyes hold on a {@code publish.postgres} Job — on create, and on a change to its connection,
 * datasets or include_sensitive — whatever the Space's approval policy says. Real HTTP, armed Subjects.
 */
class ControlApiPublicationTest {

    @TempDir static Path auditDir;
    private static String priorAuditDir;
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void pinJobsAuditDir() {
        priorAuditDir = System.getProperty("jobs.audit.dir");
        System.setProperty("jobs.audit.dir", auditDir.resolve("jobs_audit").toString());
    }

    @AfterAll
    static void restoreJobsAuditDir() {
        if (priorAuditDir != null) System.setProperty("jobs.audit.dir", priorAuditDir);
        else System.clearProperty("jobs.audit.dir");
    }

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
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
    void disarm() { Authenticators.forTest(null); }

    private Ctx open(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        Path root = Files.createDirectories(dir.resolve("wr"));
        System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), root);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json").header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static final String PUBLISH = "{\"name\":\"to-bi\",\"type\":\"publish.postgres\",\"connection\":\"BI\","
            + "\"datasets\":\"subs\",\"schema\":\"bi\"}";

    @Test
    void theDestinationAllowlistIsEmptyByDefaultAdministratorOnlyAndValidated(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> empty = send(c, "GET", "/settings/publication-destinations", "", "Bearer author");
            assertEquals(200, empty.statusCode(), empty.body());
            assertEquals(0, JSON.readTree(empty.body()).get("data").get("hosts").size(), "empty by default");
            assertEquals(403, send(c, "PUT", "/settings/publication-destinations", "{\"hosts\":[\"bi.example.com\"]}",
                    "Bearer author").statusCode(), "canAdminister only");
            assertEquals(422, send(c, "PUT", "/settings/publication-destinations", "{\"hosts\":[\"a@b.example.com\"]}",
                    "Bearer admin").statusCode());
            assertEquals(422, send(c, "PUT", "/settings/publication-destinations", "{\"allow\":[]}", "Bearer admin").statusCode());
            HttpResponse<String> ok = send(c, "PUT", "/settings/publication-destinations", "{\"hosts\":[\"BI.Example.com\"]}", "Bearer admin");
            assertEquals(200, ok.statusCode(), ok.body());
            assertEquals(List.of("bi.example.com"), PublicationDestinations.hosts(c.root()));
            assertTrue(ReservedConfigPaths.reserved(PublicationDestinations.FILE), "no import may write it");
        }
    }

    @Test
    void creatingAPublishJobIsAlwaysHeldForFourEyesEvenWithNoApprovalPolicy(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertFalse(Files.exists(c.root().resolve("approval.toon")), "no approval policy in this Space");
            HttpResponse<String> held = send(c, "POST", "/jobs", PUBLISH, "Bearer admin");
            assertEquals(202, held.statusCode(), held.body());
            JsonNode pc = JSON.readTree(held.body()).get("data").get("pendingChange");
            assertEquals("job", pc.get("kind").asText(), held.body());
            assertTrue(pc.get("fourEyes").asBoolean());
            assertTrue(c.svc().jobService().flatMap(s -> s.jobConfig("to-bi")).isEmpty(), "nothing was written");

            HttpResponse<String> plain = send(c, "POST", "/jobs",
                    "{\"name\":\"tidy\",\"type\":\"maintenance\",\"task\":\"cleanup\"}", "Bearer admin");
            assertTrue(plain.statusCode() / 100 == 2 && plain.statusCode() != 202, "other Jobs are not held: " + plain.body());
        }
    }

    @Test
    void theHoldCoversAChangeToConnectionDatasetsOrIncludeSensitiveOnly() {
        Map<String, Object> saved = Map.of("name", "to-bi", "type", "publish.postgres", "connection", "BI",
                "datasets", "subs", "schema", "bi");
        assertNotNull(PendingChanges.mandatoryRule("job", saved, null), "create");
        assertNull(PendingChanges.mandatoryRule("job", Map.of("job", withKey(saved, "cron", "0 0 * * * ?")), Map.of("job", saved)),
                "a schedule change is not held by this rule");
        for (String k : List.of("connection", "datasets", "include_sensitive"))
            assertNotNull(PendingChanges.mandatoryRule("job", withKey(saved, k, "other"), saved), k);
        assertNull(PendingChanges.mandatoryRule("job", null, saved), "a delete is not held");
        assertNull(PendingChanges.mandatoryRule("job", Map.of("name", "x", "type", "maintenance"), null));
        assertNotNull(PendingChanges.mandatoryRule("job", saved, Map.of("name", "to-bi", "type", "maintenance")),
                "turning another Job into a publication is a create");
    }

    @Test
    void noImportMayCarryAPublishJob() {
        byte[] job = ("job:\n  name: to-bi\n  type: publish.postgres\n  connection: BI\n  datasets: subs\n  schema: bi\n")
                .getBytes(StandardCharsets.UTF_8);
        ApiException refused = assertThrows(ApiException.class,
                () -> ImportCapabilityGuard.checkFiles(null, Map.of("jobs/to-bi_job.toon", job), true));
        assertEquals(409, refused.status);
        assertThrows(ApiException.class, () -> ImportCapabilityGuard.checkItems(null,
                List.of(Map.of("kind", "job", "id", "to-bi", "content", Map.of("name", "to-bi", "type", "publish.postgres")))));
    }

    private static Map<String, Object> withKey(Map<String, Object> m, String k, Object v) {
        Map<String, Object> out = new java.util.LinkedHashMap<>(m);
        out.put(k, v);
        return out;
    }
}
