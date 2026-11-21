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
 * UNKNOWN-KEYS-SILENT-1 over real HTTP (operator 2026-11-21): a Dataset or a Reconciliation saved by an agent Subject (the
 * AK-2a agent marker) with a key nothing reads is refused with 422 naming the key, before anything is held; the same body
 * from the same person's own token is saved (step 3 adds its warning). {@code x-} keys and clean bodies still go through.
 */
class ControlApiUnknownKeysTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** AGENT = author-1 (pipeline-developer) through agent client claude-code; AUTHOR = the same person, own token. */
    private static final String AGENT = "Bearer agent", AUTHOR = "Bearer author", ADMIN = "Bearer admin";
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
            if (!AGENT.equals(h) && !AUTHOR.equals(h) && !ADMIN.equals(h)) return Optional.empty();
            String role = ADMIN.equals(h) ? "admin" : "pipeline-developer";
            Roles.Def def = Roles.effective(ex).get(role);
            ComponentAccess.heldRoles(ex, Set.of(role));
            String agent = AGENT.equals(h) ? "claude-code" : null;
            return Optional.of(new Subject(ADMIN.equals(h) ? "admin-1" : "author-1", def.capabilities(), def.dataScopes(), Map.of(), null, null, null, agent));
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

    private HttpResponse<String> post(Ctx c, String type, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/components/" + type))
                .header("Content-Type", "application/json").header("Authorization", auth);
        return client.send(b.POST(BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private static final String DATASET = "{\"id\":\"%s\",\"physicalRef\":\"orders\",\"description\":\"d\"%s}";
    private static final String RECON = "{\"id\":\"%s\",\"datasets\":[\"a\",\"b\"],\"keyColumns\":[\"k\"],"
            + "\"compareColumns\":[{\"column\":\"amount\",\"agg\":\"sum\",%s\"tolerance\":0}]}";

    private boolean exists(Ctx c, String type, String id) {
        return new ComponentStore(c.root.resolve("registry")).exists(type, id);
    }

    @Test
    void anAgentsDatasetWithAKeyNothingReadsIsRefusedNamingTheKey(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> typo = post(c, "dataset", DATASET.formatted("ds_typo", ",\"phyiscalRef\":\"x\""), AGENT);
            assertEquals(422, typo.statusCode(), typo.body());
            assertTrue(typo.body().contains("phyiscalRef"), typo.body());
            assertFalse(exists(c, "dataset", "ds_typo"));

            // a clean body, and an x- extension key, are held for approval as before
            assertEquals(202, post(c, "dataset", DATASET.formatted("ds_clean", ""), AGENT).statusCode());
            assertEquals(202, post(c, "dataset", DATASET.formatted("ds_ext", ",\"x-note\":\"mine\""), AGENT).statusCode());
        }
    }

    @Test
    void anAgentsReconciliationWithAMisspelledToleranceIsRefusedNamingThePath(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> typo = post(c, "reconciliation", RECON.formatted("rc_typo", "\"toleranceTyp\":\"absolute\","), AGENT);
            assertEquals(422, typo.statusCode(), typo.body());
            assertTrue(typo.body().contains("compareColumns[0].toleranceTyp"), typo.body());

            HttpResponse<String> top = post(c, "reconciliation",
                    RECON.formatted("rc_top", "\"toleranceType\":\"absolute\",").replace("{\"id\"", "{\"tolerence\":1,\"id\""), AGENT);
            assertEquals(422, top.statusCode(), top.body());
            assertTrue(top.body().contains("tolerence"), top.body());

            assertEquals(202, post(c, "reconciliation", RECON.formatted("rc_ok", "\"toleranceType\":\"absolute\","), AGENT).statusCode());
        }
    }

    private HttpResponse<String> put(Ctx c, String type, String id, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/components/" + type + "/" + id))
                .header("Content-Type", "application/json").header("Authorization", auth);
        return client.send(b.PUT(BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    /** What the product itself writes on a Dataset or a Reconciliation is never "unknown" to the agent that saves it again. */
    @Test
    void keysTheProductWritesAreAcceptedAndAKeyTheStoredEntryCarriesRoundTrips(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            // the console's and the importers' own keys, on a new Dataset and a new Reconciliation
            assertEquals(202, post(c, "dataset", DATASET.formatted("ds_title", ",\"title\":\"T\",\"importFilter\":{},\"watchInbox\":{}"), AGENT).statusCode());
            assertEquals(202, post(c, "reconciliation", RECON.formatted("rc_desc", "\"toleranceType\":\"absolute\",")
                    .replace("{\"id\"", "{\"description\":\"d\",\"id\""), AGENT).statusCode());

            // a stored entry with a key the census does not list: the agent re-saving it (a PUT, or a restore) keeps that key ...
            new ComponentStore(c.root.resolve("registry")).write("dataset", "ds_prior", Map.of("physicalRef", "orders", "legacyKey", "v"));
            HttpResponse<String> resave = put(c, "dataset", "ds_prior", DATASET.formatted("ds_prior", ",\"legacyKey\":\"v\""), AGENT);
            assertEquals(202, resave.statusCode(), resave.body());
            // ... but a key it adds on top is judged
            HttpResponse<String> added = put(c, "dataset", "ds_prior", DATASET.formatted("ds_prior", ",\"legacyKey\":\"v\",\"newTypo\":1"), AGENT);
            assertEquals(422, added.statusCode(), added.body());
            assertTrue(added.body().contains("newTypo") && !added.body().contains("legacyKey"), added.body());
        }
    }

    private HttpResponse<String> get(Ctx c, String path, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path)).header("Authorization", auth);
        return client.send(b.GET().build(), BodyHandlers.ofString());
    }

    /** Step 3: a person saves, the response names the unknown keys in `warnings`, and the audit row records them. */
    @Test
    void aPersonsSaveWithAnUnknownKeySucceedsWithWarningsAndAnAuditedRow(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> own = post(c, "dataset", DATASET.formatted("ds_person", ",\"phyiscalRef\":\"x\""), AUTHOR);
            assertTrue(own.statusCode() < 300, own.body());
            JsonNode warnings = JSON.readTree(own.body()).at("/data/warnings");
            assertEquals(1, warnings.size(), own.body());
            assertEquals("UNKNOWN_KEY", warnings.get(0).get("code").asText());
            assertEquals("phyiscalRef", warnings.get(0).get("key").asText());
            assertTrue(exists(c, "dataset", "ds_person"), "the save still happened");

            HttpResponse<String> rc = post(c, "reconciliation", RECON.formatted("rc_person", "\"toleranceTyp\":\"absolute\","), AUTHOR);
            assertTrue(rc.statusCode() < 300, rc.body());
            assertEquals("compareColumns[0].toleranceTyp", JSON.readTree(rc.body()).at("/data/warnings/0/key").asText(), rc.body());

            // a clean save carries no warnings
            JsonNode clean = JSON.readTree(post(c, "dataset", DATASET.formatted("ds_person_ok", ""), AUTHOR).body());
            assertTrue(clean.at("/data/warnings").isMissingNode(), clean.toString());

            // the audit row of the unclean save names the keys
            boolean audited = false;
            for (JsonNode row : JSON.readTree(get(c, "/audit/search?type=AUDIT&limit=500", ADMIN).body()).get("data"))
                if (row.toString().contains("unknownKeys") && row.toString().contains("phyiscalRef")) audited = true;
            assertTrue(audited, "an audit row carries unknownKeys=phyiscalRef");
        }
    }
}
