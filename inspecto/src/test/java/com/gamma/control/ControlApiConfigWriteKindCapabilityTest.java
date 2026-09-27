package com.gamma.control;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The legacy {@code /config/write} and {@code /config/patch} doors are never a way around a kind's own route
 * gate, over real HTTP with an ARMED Authenticator (without a Subject every capability check is a no-op): an
 * {@code alert} needs {@code canAuthorAlertRules} exactly as {@code POST /alerts/rules} does — same 403 — and an
 * administer-only maintenance Job cannot be patched in by a non-administrator.
 */
class ControlApiConfigWriteKindCapabilityTest {

    private static final String BUILDER = "Bearer builder", AUTHOR = "Bearer author";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    @BeforeEach
    void arm() {
        com.gamma.etl.EditionFeatures.overrideForTest(Set.of(com.gamma.etl.EditionFeatures.ALERT_DISPATCH));
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case BUILDER -> Optional.of(new Subject("builder-1", Set.of("canAuthorWorkbench")));
            case AUTHOR -> Optional.of(new Subject("author-1", Set.of("canAuthorWorkbench", "canAuthorAlertRules")));
            default -> Optional.empty();
        });
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
        com.gamma.etl.EditionFeatures.overrideForTest(null);
    }

    private Ctx open(Path cfg, Path root) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
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

    private HttpResponse<String> post(int port, String auth, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Authorization", auth).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static final String ALERT_WRITE = """
            {"type":"alert","config":{"alert":{"name":"rule-1","metric":"error_rate","threshold":"0.1",
             "window":"1h"}}}""";
    private static final String ALERT_RULE = """
            {"name":"rule-1","metric":"error_rate","comparator":"gt","threshold":0.1,"window":"1h",
             "severity":"WARNING"}""";

    @Test
    void anAlertWithoutCanAuthorAlertRulesIsRefusedAsOnTheDirectRoute(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            int direct = post(c.port, BUILDER, "/alerts/rules", ALERT_RULE).statusCode();
            HttpResponse<String> legacy = post(c.port, BUILDER, "/config/write", ALERT_WRITE);
            assertEquals(403, direct);
            assertEquals(direct, legacy.statusCode(), legacy.body());
            assertTrue(legacy.body().contains("canAuthorAlertRules"), legacy.body());
            assertFalse(Files.exists(root.resolve("rule-1.toon")), "nothing written");
        }
    }

    /**
     * The holder passes the gate. ⚠ It then meets a PRE-EXISTING content refusal: {@code identityFields("alert")}
     * is the top-level {@code name}, while the alert spec keys the rule under {@code alert.name} and refuses a
     * top-level {@code name} as dead — so no {@code type: alert} draft is writable through this door today. The
     * test pins "not 403" (the gate), not a write.
     */
    @Test
    void anAlertWithCanAuthorAlertRulesPassesTheGate(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(200, post(c.port, AUTHOR, "/alerts/rules", ALERT_RULE).statusCode(), "the direct route admits");
            HttpResponse<String> r = post(c.port, AUTHOR, "/config/write", ALERT_WRITE);
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("identity field"), r.body());
        }
    }

    @Test
    void anAlertPatchWithoutCanAuthorAlertRulesIsRefused(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            Files.writeString(root.resolve("rule-1.toon"),
                    "alert:\n  name: rule-1\n  metric: error_rate\n  threshold: \"0.1\"\n  window: 1h\n");
            String before = Files.readString(root.resolve("rule-1.toon"));
            String patch = "{\"type\":\"alert\",\"name\":\"rule-1\",\"patch\":{\"alert\":{\"threshold\":\"0.2\"}}}";
            HttpResponse<String> r = post(c.port, BUILDER, "/config/patch", patch);
            assertEquals(403, r.statusCode(), r.body());
            assertEquals(before, Files.readString(root.resolve("rule-1.toon")));
            HttpResponse<String> ok = post(c.port, AUTHOR, "/config/patch", patch);
            assertEquals(200, ok.statusCode(), ok.body());
        }
    }

    @Test
    void patchingAJobIntoAnEventPruneNeedsCanAdminister(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            Files.writeString(root.resolve("nightly.toon"),
                    "job:\n  name: nightly\n  type: maintenance\n  task: vacuum\n  schedule: \"0 3 * * *\"\n");
            String before = Files.readString(root.resolve("nightly.toon"));
            HttpResponse<String> r = post(c.port, BUILDER, "/config/patch",
                    "{\"type\":\"job\",\"name\":\"nightly\",\"patch\":{\"job\":{\"task\":\"event_prune\"}}}");
            assertEquals(403, r.statusCode(), r.body());
            assertEquals(before, Files.readString(root.resolve("nightly.toon")));
        }
    }

    /** The type is matched as {@code ConfigSpecs.forType} matches it — case-insensitively — so no casing slips past. */
    @Test
    void aTypeInAnyCasingMeetsTheSameGate(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            Files.writeString(root.resolve("nightly.toon"),
                    "job:\n  name: nightly\n  type: maintenance\n  task: vacuum\n  schedule: \"0 3 * * *\"\n");
            Files.writeString(root.resolve("rule-1.toon"),
                    "alert:\n  name: rule-1\n  metric: error_rate\n  threshold: \"0.1\"\n  window: 1h\n");
            String job = Files.readString(root.resolve("nightly.toon")), alert = Files.readString(root.resolve("rule-1.toon"));
            HttpResponse<String> r = post(c.port, BUILDER, "/config/patch",
                    "{\"type\":\"Job\",\"name\":\"nightly\",\"patch\":{\"job\":{\"task\":\"restore\"}}}");
            assertEquals(403, r.statusCode(), r.body());
            r = post(c.port, BUILDER, "/config/patch",
                    "{\"type\":\"Alert\",\"name\":\"rule-1\",\"patch\":{\"alert\":{\"threshold\":\"0.2\"}}}");
            assertEquals(403, r.statusCode(), r.body());
            assertEquals(job, Files.readString(root.resolve("nightly.toon")));
            assertEquals(alert, Files.readString(root.resolve("rule-1.toon")));
            r = post(c.port, BUILDER, "/config/write",
                    "{\"type\":\"JOB\",\"overwrite\":true,\"config\":{\"job\":{\"name\":\"nightly\",\"type\":\"maintenance\",\"task\":\"event_prune\",\"schedule\":\"0 3 * * *\"}}}");
            assertEquals(403, r.statusCode(), r.body());
            r = post(c.port, BUILDER, "/config/write", ALERT_WRITE.replace("\"alert\",\"config\"", "\"Alert\",\"config\""));
            assertEquals(403, r.statusCode(), r.body());
            assertEquals(job, Files.readString(root.resolve("nightly.toon")));
        }
    }

    /** DELETE /config/{type}/{name} in another casing still meets the active-pipeline 409. */
    @Test
    void deletingAnActivePipelineInAnyCasingIsRefused(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            Files.writeString(root.resolve("p1.toon"), "name: p1\nactive: true\n");
            HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create(
                    "http://localhost:" + c.port + "/api/v1/config/Pipeline/p1")).header("Authorization", AUTHOR)
                    .DELETE().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(409, r.statusCode(), r.body());
            assertTrue(Files.exists(root.resolve("p1.toon")));
        }
    }
}
