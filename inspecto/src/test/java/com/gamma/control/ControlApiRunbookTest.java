package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.ComponentAccess;
import com.gamma.access.Roles;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
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
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code runbook} component kind over real HTTP with an ARMED Authenticator (operator 2026-10-10): CRUD and
 * history through the generic {@code /components/runbook} door (canAuthorWorkbench: 401 without a token, 403 without
 * the capability), the structural save gate, and the Alert Rule's {@code runbook:} link — refused at save when it
 * names no Runbook, on both Alert Rule doors.
 */
class ControlApiRunbookTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ALICE = "Bearer alice", BUSINESS = "Bearer business";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    /** alice: pipeline-developer (canAuthorWorkbench + canAuthorAlertRules); business: no capability. */
    @BeforeEach
    void arm() {
        com.gamma.etl.EditionFeatures.overrideForTest(Set.of(com.gamma.etl.EditionFeatures.ALERT_DISPATCH));
        Authenticators.forTest(ex -> {
            String h = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
            if (BUSINESS.equals(h)) return Optional.of(new Subject("biz-1", Set.of()));
            String[] who = switch (h) {
                case ALICE -> new String[] {"alice", "pipeline-developer"};
                default -> null;
            };
            if (who == null) return Optional.empty();
            Roles.Def def = Roles.effective(ex).get(who[1]);
            ComponentAccess.heldRoles(ex, Set.of(who[1]));
            return Optional.of(new Subject(who[0], def.capabilities(), def.dataScopes()));
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
            return new Ctx(svc, api, api.port(), root);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        if (auth != null) b.header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r, int status) throws Exception {
        assertEquals(status, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private static String error(HttpResponse<String> r, int status) throws Exception {
        assertEquals(status, r.statusCode(), r.body());
        return JSON.readTree(r.body()).at("/error/message").asText();
    }

    private static final String RUNBOOK = """
            {"id":"irsf","title":"IRSF runbook","summary":"Premium ranges","ownerRole":"fraud-analyst","tags":["fraud"],
             "steps":[{"text":"Open the evidence","link":{"kind":"dataset","id":"fraud_irsf"}},{"text":"Bar the line"}]}""";

    private static String rule(String name, String runbook) {
        return """
                {"name":"%s","metric":"error_rate","comparator":"gt","threshold":0.05,"window":"1h",
                 "severity":"WARNING"%s}""".formatted(name, runbook == null ? "" : ",\"runbook\":\"" + runbook + "\"");
    }

    @Test
    void aRunbookIsCreatedReadUpdatedVersionedAndDeleted(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            JsonNode saved = data(send(c, "POST", "/components/runbook", RUNBOOK, ALICE), 200);
            assertEquals("IRSF runbook", saved.at("/content/title").asText());
            assertEquals("alice", saved.at("/content/owner").asText(), "R3 author stamp, apart from ownerRole");
            assertEquals("fraud-analyst", saved.at("/content/ownerRole").asText());
            assertTrue(new ComponentStore(root.resolve("registry")).exists("runbook", "irsf"));

            JsonNode read = data(send(c, "GET", "/components/runbook/irsf", null, ALICE), 200);
            assertEquals("fraud_irsf", read.at("/content/steps/0/link/id").asText());
            assertEquals("Bar the line", read.at("/content/steps/1/text").asText(), "steps keep their order");

            String edited = RUNBOOK.replace("IRSF runbook", "IRSF runbook v2");
            data(send(c, "PUT", "/components/runbook/irsf", edited, ALICE), 200);
            JsonNode versions = data(send(c, "GET", "/components/runbook/irsf/versions", null, ALICE), 200);
            assertEquals(1, versions.size(), versions.toString());
            assertEquals("IRSF runbook", versions.get(0).at("/content/title").asText(), "the prior copy is kept");

            assertEquals(200, send(c, "DELETE", "/components/runbook/irsf", null, ALICE).statusCode());
            assertFalse(new ComponentStore(root.resolve("registry")).exists("runbook", "irsf"));
        }
    }

    @Test
    void writesAreGatedOnCanAuthorWorkbench(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(401, send(c, "POST", "/components/runbook", RUNBOOK, null).statusCode());
            assertEquals(403, send(c, "POST", "/components/runbook", RUNBOOK, BUSINESS).statusCode());
            assertFalse(new ComponentStore(root.resolve("registry")).exists("runbook", "irsf"), "nothing written");
            assertEquals(200, send(c, "POST", "/components/runbook", RUNBOOK, ALICE).statusCode());
        }
    }

    @Test
    void aMalformedRunbookIsRefusedAndNothingIsWritten(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertTrue(error(send(c, "POST", "/components/runbook", RUNBOOK.replace("\"title\":\"IRSF runbook\",", ""), ALICE), 422)
                    .contains("title"));
            assertTrue(error(send(c, "POST", "/components/runbook", RUNBOOK.replace("\"dataset\"", "\"pipeline\""), ALICE), 422)
                    .contains("link.kind"));
            assertTrue(error(send(c, "POST", "/components/runbook", RUNBOOK.replace("\"tags\"", "\"automation\""), ALICE), 422)
                    .contains("automation"));
            assertFalse(new ComponentStore(root.resolve("registry")).exists("runbook", "irsf"));
        }
    }

    @Test
    void anAlertRuleNamingNoRunbookIsRefusedAtSaveOnBothDoors(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertTrue(error(send(c, "POST", "/alerts/rules", rule("r1", "irsf"), ALICE), 422).contains("alert.runbook 'irsf'"));
            assertTrue(error(send(c, "POST", "/components/alert-rule", rule("r1", "irsf").replace("\"name\"", "\"id\""), ALICE), 422)
                    .contains("alert.runbook"));
            assertFalse(new ComponentStore(root.resolve("registry")).exists("alert-rule", "r1"), "nothing written");

            // The same body saves once the Runbook exists, and the link round-trips.
            data(send(c, "POST", "/components/runbook", RUNBOOK, ALICE), 200);
            data(send(c, "POST", "/alerts/rules", rule("r1", "irsf"), ALICE), 200);
            assertEquals("irsf", new ComponentStore(root.resolve("registry")).get("alert-rule", "r1").orElseThrow()
                    .content().get("runbook"));
            data(send(c, "POST", "/alerts/rules", rule("r2", null), ALICE), 200);   // a link is optional
        }
    }
}
