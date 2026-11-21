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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * COMPONENT-VALIDATE-ALL-1 over real HTTP: {@code POST /validate} judges a KPI, Reconciliation, Dataset, Widget or Dashboard
 * draft by the checks its save door runs, as findings, writing nothing; a virtual Dataset's SQL meets the safety check at save
 * (it used to meet it only on read); the unknown-key finding is an error for an agent Subject and a warning for a person.
 */
class ControlApiComponentValidateTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String AGENT = "Bearer agent", AUTHOR = "Bearer author";
    private final HttpClient client = HttpClient.newHttpClient();

    @TempDir
    static Path auditDir;

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @BeforeEach
    void arm() {
        System.setProperty("jobs.audit.dir", auditDir.resolve("jobs_audit").toString());
        Authenticators.forTest(ex -> {
            String h = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
            if (!AGENT.equals(h) && !AUTHOR.equals(h)) return Optional.empty();
            Roles.Def def = Roles.effective(ex).get("pipeline-developer");
            ComponentAccess.heldRoles(ex, Set.of("pipeline-developer"));
            String agent = AGENT.equals(h) ? "claude-code" : null;
            return Optional.of(new Subject("author-1", def.capabilities(), def.dataScopes(), Map.of(), null, null, null, agent));
        });
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
    }

    private Ctx open(Path dir) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(Files.createDirectories(dir.resolve("cfg")), "");
        Path root = Files.createDirectories(dir.resolve("root"));
        System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            Files.writeString(root.resolve(ApproverRoster.FILE), dev.toonformat.jtoon.JToon.encode(
                    Map.of("users", List.of("author-1", "checker-1"))));
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), root);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json").header("Authorization", auth);
        return client.send(b.POST(BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    /** {@code POST /validate} of {@code {type, id, config}}; the {@code data} node. */
    private JsonNode validate(Ctx c, String type, String config, String auth) throws Exception {
        HttpResponse<String> r = send(c, "/validate", "{\"type\":\"" + type + "\",\"id\":\"draft_x\",\"config\":" + config + "}", auth);
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private static String messages(JsonNode data) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode f : data.get("findings")) sb.append(f.get("severity").asText()).append(' ').append(f.get("message").asText()).append(" | ");
        return sb.toString();
    }

    @Test
    void aDatasetDraftIsJudgedByItsSaveDoorAndNothingIsWritten(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode clean = validate(c, "dataset", "{\"physicalRef\":\"orders\",\"description\":\"d\"}", AUTHOR);
            assertTrue(clean.get("clean").asBoolean(), clean.toString());

            JsonNode typo = validate(c, "dataset", "{\"physicalRef\":\"orders\",\"phyiscalRef\":\"x\"}", AUTHOR);
            assertFalse(typo.get("clean").asBoolean());
            assertEquals("WARNING", typo.at("/findings/0/severity").asText(), "a person is warned: " + typo);
            assertEquals("phyiscalRef", typo.at("/findings/0/fieldPath").asText());

            JsonNode agent = validate(c, "dataset", "{\"physicalRef\":\"orders\",\"phyiscalRef\":\"x\"}", AGENT);
            assertEquals("ERROR", agent.at("/findings/0/severity").asText(), "an agent's save would be refused: " + agent);

            assertFalse(new ComponentStore(c.root.resolve("registry")).exists("dataset", "draft_x"), "validate writes nothing");
        }
    }

    /** A virtual Dataset's SQL: refused at validate AND at save (it used to be refused only when first read). */
    @Test
    void aVirtualDatasetsUnsafeSqlIsRefusedAtValidateAndAtSave(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String bad = "{\"kind\":\"virtual\",\"sourceName\":\"orders\",\"sql\":\"DROP TABLE orders\"}";
            JsonNode v = validate(c, "dataset", bad, AUTHOR);
            assertFalse(v.get("clean").asBoolean(), v.toString());
            assertTrue(messages(v).contains("SQL safety check"), messages(v));

            HttpResponse<String> save = send(c, "/components/dataset", "{\"id\":\"v_bad\",\"kind\":\"virtual\",\"sourceName\":\"orders\",\"sql\":\"DROP TABLE orders\"}", AUTHOR);
            assertEquals(422, save.statusCode(), save.body());
            assertTrue(save.body().contains("SQL safety check"), save.body());

            HttpResponse<String> noSource = send(c, "/components/dataset", "{\"id\":\"v_nosrc\",\"kind\":\"virtual\",\"sql\":\"SELECT 1\"}", AUTHOR);
            assertEquals(422, noSource.statusCode(), noSource.body());
            assertTrue(noSource.body().contains("sourceName"), noSource.body());

            String good = "{\"id\":\"v_good\",\"kind\":\"virtual\",\"sourceName\":\"orders\",\"sql\":\"SELECT * FROM orders\"}";
            assertTrue(send(c, "/components/dataset", good, AUTHOR).statusCode() < 300, "a safe virtual Dataset still saves");
            assertTrue(validate(c, "dataset", "{\"kind\":\"virtual\",\"sourceName\":\"orders\",\"sql\":\"SELECT * FROM orders\"}", AUTHOR)
                    .get("clean").asBoolean());
        }
    }

    @Test
    void aKpiDraftWithoutItsShapeOrItsDatasetGetsFindings(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode empty = validate(c, "kpi", "{}", AUTHOR);
            assertFalse(empty.get("clean").asBoolean(), empty.toString());
            assertEquals("ERROR", empty.at("/findings/0/severity").asText());
            assertFalse(empty.at("/findings/0/message").asText().isBlank(), "the finding says what is missing: " + empty);
        }
    }

    @Test
    void aWidgetAndADashboardDraftWithAKeyNothingReadsAreFound(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            for (String kind : List.of("widget", "dashboard")) {
                JsonNode v = validate(c, kind, "{\"bogusKey\":1}", AUTHOR);
                assertFalse(v.get("clean").asBoolean(), kind + ": " + v);
                assertTrue(v.toString().contains("bogusKey"), kind + ": " + v);
            }
        }
    }

    /** /validate is open to every signed-in user, so a Mapping's base Dataset the caller cannot see reads exactly as a missing one. */
    @Test
    void aHiddenBaseDatasetAnswersAsAMissingOne(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            new ComponentStore(c.root.resolve("registry")).write("dataset", "secret_ds",
                    Map.of("physicalRef", "secret_store_xyz", "owner", "someone-else", "shares", List.of()));   // shared with no one
            String hidden = messages(validate(c, "dataset", "{\"origin\":\"mapping\",\"basedOn\":\"secret_ds\",\"physicalRef\":\"x\"}", AUTHOR));
            String missing = messages(validate(c, "dataset", "{\"origin\":\"mapping\",\"basedOn\":\"nope_ds\",\"physicalRef\":\"x\"}", AUTHOR));
            assertEquals(missing.replace("nope_ds", "X"), hidden.replace("secret_ds", "X"), "same answer, hidden or missing");
            assertFalse(hidden.contains("secret_store_xyz"), "the hidden Dataset's store is not quoted: " + hidden);
        }
    }

    /** A key the stored entry already carries is not judged, on validate as on save: an agent re-saving it is not told otherwise. */
    @Test
    void aKeyTheStoredEntryCarriesIsNotFlaggedForAnAgent(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            new ComponentStore(c.root.resolve("registry")).write("dataset", "ds_prior", Map.of("physicalRef", "orders", "legacyKey", "v"));
            HttpResponse<String> r = send(c, "/validate", "{\"type\":\"dataset\",\"id\":\"ds_prior\",\"config\":{\"physicalRef\":\"orders\",\"legacyKey\":\"v\"}}", AGENT);
            assertTrue(JSON.readTree(r.body()).at("/data/clean").asBoolean(), r.body());
            HttpResponse<String> added = send(c, "/validate", "{\"type\":\"dataset\",\"id\":\"ds_prior\",\"config\":{\"physicalRef\":\"orders\",\"legacyKey\":\"v\",\"newTypo\":1}}", AGENT);
            assertFalse(JSON.readTree(added.body()).at("/data/clean").asBoolean(), added.body());
        }
    }

    @Test
    void anUnknownTypeIsStillNotFound(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(404, send(c, "/validate", "{\"type\":\"nonsense\",\"config\":{}}", AUTHOR).statusCode());
        }
    }
}
