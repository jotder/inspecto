package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.Roles;
import com.gamma.entitystore.LinkAnalysisSettings;
import com.gamma.etl.PipelineConfigBatchTest;
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
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET|PUT /settings/link-analysis/domain-profile} (LA-EDITIONS-1 phase 4) over real HTTP with real Subjects: the
 * terminology the active Industry pack publishes, the {@code canAuthorWorkbench} gate, the 503 / 422 / 409 refusals with
 * nothing written, the round trip, and the settings routes reading the pack's relabelled Entity Types.
 */
class ControlApiDomainProfileTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ROUTE = "/settings/link-analysis/domain-profile";
    private static final String AUTHOR = "Bearer author", ADMIN = "Bearer admin";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @BeforeEach
    void arm() {
        Authenticators.forTest(ex -> {
            String h = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
            String[] who = switch (h) {
                case AUTHOR -> new String[] {"dev-1", "developer"};   // canAuthorWorkbench
                case ADMIN -> new String[] {"admin-1", "admin"};      // canAdminister, not canAuthorWorkbench
                default -> null;
            };
            if (who == null) return Optional.empty();
            Roles.Def def = Roles.effective(ex).get(who[1]);
            return Optional.of(new Subject(who[0], def.capabilities(), def.dataScopes()));
        });
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
    }

    private static Ctx open(Path cfg, Path root) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        if (root != null) System.setProperty("assist.write.root", root.toString());
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
                .header("Content-Type", "application/json").header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, HttpRequest.BodyPublishers.ofString(body));
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r, int status) throws Exception {
        assertEquals(status, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private static JsonNode term(JsonNode profile, String key) {
        for (JsonNode t : profile.get("terms")) if (t.get("key").asText().equals(key)) return t;
        throw new AssertionError("no term " + key);
    }

    private static String entityTypeLabel(JsonNode types, String id) {
        for (JsonNode t : types) if (t.get("id").asText().equals(id)) return t.get("label").asText();
        throw new AssertionError("no entity type " + id);
    }

    @Test
    void theGetListsTheInstalledPacksAndANewSpaceReadsNeutral(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, Files.createDirectories(tmp.resolve("config")))) {
            JsonNode v = data(send(c, "GET", ROUTE, null, AUTHOR), 200);
            assertEquals("generic", v.get("active").asText());
            assertTrue(v.get("stated").isNull());
            assertTrue(v.get("profile").isNull(), "no pack = the neutral vocabulary");
            assertEquals(2, v.get("installed").size());
            assertEquals("telecom", v.get("installed").get(0).get("id").asText());
            assertEquals("mobile-money", v.get("installed").get(1).get("id").asText());
            assertEquals(0, v.get("refused").size());
        }
    }

    @Test
    void thePutRoundTripsAndTheGetPublishesTheActivePacksTerminology(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, Files.createDirectories(tmp.resolve("config")))) {
            Path file = c.root().resolve(LinkAnalysisSettings.FILE);
            Files.writeString(file, "masking_mode: none\nsome_newer_key: 7\n");
            JsonNode put = data(send(c, "PUT", ROUTE, "{\"domainProfile\":\"telecom\"}", AUTHOR), 200);
            assertEquals("telecom", put.get("active").asText());

            JsonNode v = data(send(c, "GET", ROUTE, null, AUTHOR), 200);
            assertEquals("telecom", v.get("stated").asText());
            JsonNode p = v.get("profile");
            assertEquals("MSISDN", term(p, "entity_type.msisdn").get("label").asText());
            assertEquals("CDR / XDR", term(p, "concept.row").get("abbreviation").asText());
            assertFalse(term(p, "term.wangiri").get("definition").asText().isBlank(), "hover definitions travel with the labels");
            assertEquals("msisdn", p.get("profile").get("investigate").get("entityType").asText());
            assertEquals("MSISDN", entityTypeLabel(p.get("entityTypes"), "msisdn"));

            // every other key of link-analysis.toon is kept, and the settings route reads the pack's labels
            String doc = Files.readString(file);
            assertTrue(doc.contains("some_newer_key") && doc.contains("masking_mode: none"), doc);
            JsonNode settings = data(send(c, "GET", "/settings/link-analysis", null, AUTHOR), 200);
            assertEquals("MSISDN", entityTypeLabel(settings.get("entityTypesInForce"), "msisdn"));

            // the settings PUT does not own domain_profile: it survives
            data(send(c, "PUT", "/settings/link-analysis", "{\"maskingMode\":\"typed\"}", AUTHOR), 200);
            assertEquals("telecom", LinkAnalysisSettings.read(file).domainProfile());

            // switching packs switches the labels; generic clears the key
            JsonNode mm = data(send(c, "PUT", ROUTE, "{\"domainProfile\":\"mobile-money\"}", AUTHOR), 200);
            assertEquals("Wallet", term(mm.get("profile"), "entity_type.wallet").get("label").asText());
            JsonNode neutral = data(send(c, "PUT", ROUTE, "{\"domainProfile\":\"generic\"}", AUTHOR), 200);
            assertEquals("generic", neutral.get("active").asText());
            assertNull(LinkAnalysisSettings.read(file).domainProfile());
            assertEquals("Phone number", entityTypeLabel(
                    data(send(c, "GET", "/settings/link-analysis", null, AUTHOR), 200).get("entityTypesInForce"), "msisdn"));
        }
    }

    @Test
    void anUninstalledStatedPackReadsNeutralWithADiagnostic(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, Files.createDirectories(tmp.resolve("config")))) {
            Files.writeString(c.root().resolve(LinkAnalysisSettings.FILE), "domain_profile: atlantis\n");
            JsonNode v = data(send(c, "GET", ROUTE, null, AUTHOR), 200);
            assertEquals("generic", v.get("active").asText());
            assertEquals("atlantis", v.get("stated").asText());
            assertTrue(v.get("diagnostic").asText().contains("not installed"));
        }
    }

    @Test
    void thePutNeedsCanAuthorWorkbench(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, Files.createDirectories(tmp.resolve("config")))) {
            assertEquals(401, send(c, "PUT", ROUTE, "{\"domainProfile\":\"telecom\"}", "Bearer nobody").statusCode());
            assertEquals(403, send(c, "PUT", ROUTE, "{\"domainProfile\":\"telecom\"}", ADMIN).statusCode());
            assertFalse(Files.exists(c.root().resolve(LinkAnalysisSettings.FILE)), "a refused write writes nothing");
        }
    }

    @Test
    void anUnknownPackOrABadBodyIs422AndWritesNothing(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, Files.createDirectories(tmp.resolve("config")))) {
            HttpResponse<String> r = send(c, "PUT", ROUTE, "{\"domainProfile\":\"atlantis\"}", AUTHOR);
            assertEquals(422, r.statusCode());
            assertTrue(r.body().contains("telecom"), "the refusal names the installed packs: " + r.body());
            assertEquals(422, send(c, "PUT", ROUTE, "{\"domainProfile\":7}", AUTHOR).statusCode());
            assertEquals(422, send(c, "PUT", ROUTE, "{}", AUTHOR).statusCode());
            assertFalse(Files.exists(c.root().resolve(LinkAnalysisSettings.FILE)));
        }
    }

    @Test
    void anUnparseableSettingsDocumentIs409AndLeftAsItIs(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, Files.createDirectories(tmp.resolve("config")))) {
            Path file = c.root().resolve(LinkAnalysisSettings.FILE);
            Files.writeString(file, "[2]: a,b\n");
            assertEquals(409, send(c, "PUT", ROUTE, "{\"domainProfile\":\"telecom\"}", AUTHOR).statusCode());
            assertEquals("[2]: a,b\n", Files.readString(file));
        }
    }

    @Test
    void writesAre503WhenNoWritableSpaceIsBound(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            assertEquals(503, send(c, "PUT", ROUTE, "{\"domainProfile\":\"telecom\"}", AUTHOR).statusCode());
            assertEquals(200, send(c, "GET", ROUTE, null, AUTHOR).statusCode(), "the read still answers: neutral");
        }
    }
}
