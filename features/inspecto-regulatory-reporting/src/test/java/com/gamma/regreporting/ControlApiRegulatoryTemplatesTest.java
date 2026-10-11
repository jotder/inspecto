package com.gamma.regreporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.ComponentAccess;
import com.gamma.access.Roles;
import com.gamma.control.ApproverRoster;
import com.gamma.control.ControlApi;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.gamma.workflow.ObjectType;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Report Template authoring over real HTTP ({@code REGULATORY-REPORTING-1}): {@code PUT / DELETE
 * /regulatory-reports/templates/{id}}, every gate in order, and the round trip - an authored template drafts a report.
 * (The module-absent 503 is pinned by {@code RegulatoryReportRoutesManifestParityTest}: the routes are in module.toon.)
 */
class ControlApiRegulatoryTemplatesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ADMIN = "Bearer admin", OPS = "Bearer ops";
    private static final String T = "/regulatory-reports/templates/";
    private final HttpClient client = HttpClient.newHttpClient();

    @TempDir
    Path tmp;

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @BeforeEach
    void arm() {
        System.setProperty("assist.safety.roots", tmp.toString());
        Authenticators.forTest(ex -> {
            String auth = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
            String role = ADMIN.equals(auth) ? "admin" : OPS.equals(auth) ? "operations" : null;
            if (role == null) return Optional.empty();
            Roles.Def def = Roles.effective(ex).get(role);
            ComponentAccess.heldRoles(ex, Set.of(role));
            return Optional.of(new Subject(role + "-1", def.capabilities(), def.dataScopes()));
        });
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
        System.clearProperty("assist.safety.roots");
    }

    private Ctx open(boolean writable) throws Exception {
        Path cfg = Files.createDirectories(tmp.resolve("pipes"));
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        Path root = writable ? Files.createDirectories(tmp.resolve("config")) : null;
        if (root != null) System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            if (root != null) Files.writeString(root.resolve(ApproverRoster.FILE), dev.toonformat.jtoon.JToon.encode(
                    Map.of("users", List.of("admin-1", "operations-1"))));
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

    private static void status(HttpResponse<String> r, int status, String contains) {
        assertEquals(status, r.statusCode(), r.body());
        if (contains != null) assertTrue(r.body().contains(contains), r.body());
    }

    private static String template(String title, String dir) {
        return "{\"title\":\"" + title + "\",\"format\":\"json\",\"delivery\":{\"kind\":\"file-drop\",\"dir\":\"" + dir + "\"},"
                + "\"fields\":[{\"name\":\"reportId\",\"source\":\"report.id\",\"required\":true,\"maxLength\":64},"
                + "{\"name\":\"subjectTitle\",\"source\":\"subject.title\",\"required\":true,\"maxLength\":400},"
                + "{\"name\":\"narrative\",\"source\":\"input.narrative\",\"required\":true,\"maxLength\":2000}]}";
    }

    @Test
    void createUpdateListAndDelete() throws Exception {
        try (Ctx c = open(true)) {
            JsonNode created = data(send(c, "PUT", T + "my-tpl", template("Mine", "out/my"), ADMIN), 200);
            assertTrue(created.get("created").asBoolean());
            assertEquals("space", created.get("origin").asText());
            assertTrue(Files.isRegularFile(c.root.resolve("regulatory-report-templates/my-tpl.toon")));

            JsonNode updated = data(send(c, "PUT", T + "my-tpl", template("Mine v2", "out/my"), ADMIN), 200);
            assertFalse(updated.get("created").asBoolean());
            assertEquals("Mine v2", updated.get("title").asText());

            JsonNode list = data(send(c, "GET", "/regulatory-reports/templates", null, ADMIN), 200);
            assertEquals(0, list.get("problems").size());
            assertEquals(2, list.get("items").size(), "built-in sample-sar + the authored one");

            assertTrue(data(send(c, "DELETE", T + "my-tpl", null, ADMIN), 200).get("deleted").asBoolean());
            assertFalse(Files.exists(c.root.resolve("regulatory-report-templates/my-tpl.toon")));
            status(send(c, "DELETE", T + "my-tpl", null, ADMIN), 404, "no Space report template");
        }
    }

    @Test
    void gatesFailClosedInOrder() throws Exception {
        try (Ctx ro = open(false)) {
            status(send(ro, "PUT", T + "my-tpl", template("Mine", "out/my"), ADMIN), 503, null);
            status(send(ro, "DELETE", T + "my-tpl", null, ADMIN), 503, null);
        }
        try (Ctx c = open(true)) {
            // capability (before every other gate)
            status(send(c, "PUT", T + "my-tpl", template("Mine", "out/my"), OPS), 403, "canApproveChanges");
            status(send(c, "DELETE", T + "my-tpl", null, OPS), 403, "canApproveChanges");
            // 422: unsafe id, id mismatch, invalid template (every problem named, shown verbatim)
            status(send(c, "PUT", T + "BAD_ID", template("Mine", "out/my"), ADMIN), 422, "template id must match");
            status(send(c, "DELETE", T + "BAD_ID", null, ADMIN), 422, "template id must match");
            status(send(c, "PUT", T + "my-tpl", template("Mine", "out/my").replace("{", "{\"id\":\"other-one\","), ADMIN),
                    422, "but the path names 'my-tpl'");
            status(send(c, "PUT", T + "my-tpl", "{\"title\":\"x\",\"format\":\"pdf\",\"bogus\":1}", ADMIN), 422, "unknown key 'bogus'");
            status(send(c, "PUT", T + "my-tpl", "{\"title\":\"x\",\"format\":\"pdf\"}", ADMIN), 422, "'format' must be one of");
            // 403: the drop directory escapes the jail
            status(send(c, "PUT", T + "my-tpl", template("Mine", "../../../outside"), ADMIN), 403, "delivery.dir is refused");
            assertFalse(Files.exists(c.root.resolve("regulatory-report-templates/my-tpl.toon")), "no refusal wrote anything");
            // 409: a built-in with no Space override
            status(send(c, "DELETE", T + "sample-sar", null, ADMIN), 409, "built in");
        }
    }

    /**
     * Armed-Authenticator coverage for the authgate guard ({@code tools/check-authgate-coverage.mjs} reads quoted
     * path LITERALS, so this method spells them out rather than using {@link #T}). A Subject lacking
     * {@code canApproveChanges} gets 403 and nothing is written / removed; one holding it succeeds.
     */
    @Test
    void templateWritesRequireCanApproveChanges() throws Exception {
        try (Ctx c = open(true)) {
            Path file = c.root.resolve("regulatory-report-templates/armed-test.toon");
            status(send(c, "PUT", "/regulatory-reports/templates/armed-test", template("Armed", "out/armed"), OPS),
                    403, "canApproveChanges");
            assertFalse(Files.exists(file), "a denied PUT writes nothing");

            assertTrue(data(send(c, "PUT", "/regulatory-reports/templates/armed-test", template("Armed", "out/armed"), ADMIN), 200)
                    .get("created").asBoolean());
            assertTrue(Files.isRegularFile(file));

            status(send(c, "DELETE", "/regulatory-reports/templates/armed-test", null, OPS), 403, "canApproveChanges");
            assertTrue(Files.isRegularFile(file), "a denied DELETE removes nothing");

            assertTrue(data(send(c, "DELETE", "/regulatory-reports/templates/armed-test", null, ADMIN), 200)
                    .get("deleted").asBoolean());
            assertFalse(Files.exists(file));
        }
    }

    @Test
    void anAuthoredTemplateDraftsAReportAndIsThenInUse() throws Exception {
        try (Ctx c = open(true)) {
            data(send(c, "PUT", T + "my-tpl", template("Mine", "out/my"), ADMIN), 200);
            String kase = c.svc.objects().orElseThrow().open(ObjectType.CASE, "Odd wires", "d", "error", "t", Map.of());
            JsonNode rec = data(send(c, "POST", "/regulatory-reports",
                    "{\"template\":\"my-tpl\",\"caseId\":\"" + kase + "\",\"inputs\":{\"narrative\":\"wired twice\"}}", ADMIN), 200);
            assertEquals("draft", rec.get("status").asText());
            JsonNode body = JSON.readTree(rec.get("content").asText());
            assertEquals("Odd wires", body.get("subjectTitle").asText());
            assertEquals("wired twice", body.get("narrative").asText());

            status(send(c, "DELETE", T + "my-tpl", null, ADMIN), 409, "used by 1 regulatory report");
            assertTrue(Files.exists(c.root.resolve("regulatory-report-templates/my-tpl.toon")));
        }
    }
}
