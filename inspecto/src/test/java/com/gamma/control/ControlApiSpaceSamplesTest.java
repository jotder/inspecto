package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.access.Roles;
import com.gamma.spi.auth.Authenticators;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.SpaceManager;
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
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code LA-ONBOARD-1} OB-12 over real HTTP: {@code POST /spaces {template, loadSamples: true}} copies the template's
 * {@code data/samples/<pipeline>/} files into that Pipeline's inbox and runs it once, answering one result per
 * Pipeline; a template that ships no samples (or no template at all) is refused before anything is created. Run
 * against the SHIPPED {@code la-sample} and {@code orders-starter} templates, unarmed (Basic: no sign-in) and armed
 * (Standard: {@code canAdminister} gates the create).
 */
class ControlApiSpaceSamplesTest {

    private static final String ADMIN = "Bearer admin", NOBODY = "Bearer nobody";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SpaceManager spaces, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
    }

    private Ctx open(Path root) throws Exception {
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        return new Ctx(spaces, api, api.port());
    }

    /** The shipped templates named, copied under {@code root/_templates}. */
    private static void ship(Path root, String... ids) throws Exception {
        for (String id : ids) {
            Path src = Path.of("..", "spaces", "_templates", id);
            Path dst = root.resolve("_templates").resolve(id);
            try (Stream<Path> walk = Files.walk(src)) {
                for (Path p : walk.sorted().toList()) {
                    Path t = dst.resolve(src.relativize(p).toString());
                    if (Files.isDirectory(p)) Files.createDirectories(t);
                    else Files.copy(p, t);
                }
            }
        }
    }

    /** A template with a Pipeline-less config and no data at all. */
    private static void seedEmptyTemplate(Path root) throws Exception {
        Path tpl = root.resolve("_templates").resolve("bare");
        Files.createDirectories(tpl.resolve("config"));
        Files.writeString(tpl.resolve("template.toon"), "name: Bare\ntagline: \"Nothing in it\"\n");
    }

    @Test
    void theGalleryTellsWhichTemplatesShipSamples(@TempDir Path root) throws Exception {
        ship(root, "la-sample", "orders-starter");
        seedEmptyTemplate(root);
        try (Ctx c = open(root)) {
            JsonNode list = json(send(c, "GET", "/spaces/templates", null, null));
            for (JsonNode t : list) {
                boolean expected = !"bare".equals(t.get("id").asText());
                assertEquals(expected, t.get("samples").asBoolean(), t.toString());
            }
            JsonNode sample = find(list, "la-sample");
            assertTrue(sample.get("creatable").asBoolean(), "the LA sample is creatable on this edition: " + sample);
        }
    }

    /** Basic: no sign-in, one call creates the Space, loads the three sample days and runs the Pipeline. */
    @Test
    void theLaSampleLoadsAndRunsFromOneCall(@TempDir Path root) throws Exception {
        ship(root, "la-sample");
        try (Ctx c = open(root)) {
            HttpResponse<String> r = send(c, "POST", "/spaces",
                    "{\"id\":\"sample\",\"display_name\":\"Sample\",\"template\":\"la-sample\",\"loadSamples\":true}", null);
            assertEquals(200, r.statusCode(), r.body());
            JsonNode body = json(r);
            assertEquals("sample", body.get("id").asText());
            JsonNode pipelines = body.get("samples").get("pipelines");
            assertEquals(1, pipelines.size(), body.toString());
            JsonNode p = pipelines.get(0);
            assertEquals("mule_transfers", p.get("pipeline").asText());
            assertEquals(3, p.get("sampleFiles").asInt());
            assertEquals("loaded", p.get("status").asText(), p.toString());
            assertEquals(3, p.get("filesLoaded").asInt(), p.toString());
            assertEquals(0, p.get("filesQuarantined").asInt());
            assertFalse(p.has("error"), p.toString());
            // LA-ONBOARD-1 OB-13: the Space created is counted where it happens, in the installation's counters, which
            // live in the server-wide document home (here the spaces container root)
            JsonNode m = json(send(c, "GET", "/onboarding/metrics", null, null));
            assertEquals(1, m.get("spacesCreated").asInt(), m.toString());
            assertTrue(m.get("firstSpaceSeconds").isNumber() && m.get("firstSpaceSeconds").asLong() >= 0, m.toString());
            assertTrue(Files.readString(root.resolve(OnboardingRoutes.FILE)).contains("\tspace_created\n"));
            // the run landed the rows: the Dataset's store is queryable in the new Space
            JsonNode table = json(send(c, "GET", "/spaces/sample/db/table?name=mule_transfers&limit=1000", null, null));
            assertTrue(table.toString().contains("MULE-HUB-01"), "the planted hub is in the loaded rows");
            // the template's samples stay pristine for a re-run
            try (Stream<Path> s = Files.list(root.resolve("sample/data/samples/mule_transfers"))) {
                assertEquals(3, s.count());
            }
        }
    }

    /** A sample folder that names no Pipeline is reported as failed, never silently dropped; the rest still loads. */
    @Test
    void aSampleFolderNamingNoPipelineIsReportedAsFailed(@TempDir Path root) throws Exception {
        ship(root, "la-sample");
        Path orphan = root.resolve("_templates/la-sample/data/samples/nobody");
        Files.createDirectories(orphan);
        Files.writeString(orphan.resolve("x.csv"), "a,b\n1,2\n");
        try (Ctx c = open(root)) {
            HttpResponse<String> r = send(c, "POST", "/spaces", "{\"id\":\"s2\",\"template\":\"la-sample\",\"loadSamples\":true}", null);
            assertEquals(200, r.statusCode(), r.body());
            JsonNode pipelines = json(r).get("samples").get("pipelines");
            assertEquals(2, pipelines.size(), r.body());
            JsonNode bad = find2(pipelines, "nobody"), good = find2(pipelines, "mule_transfers");
            assertEquals("failed", bad.get("status").asText());
            assertTrue(bad.get("error").asText().contains("names no Pipeline"), bad.toString());
            assertEquals("loaded", good.get("status").asText(), good.toString());
        }
    }

    private static JsonNode find2(JsonNode pipelines, String name) {
        for (JsonNode p : pipelines) if (name.equals(p.get("pipeline").asText())) return p;
        throw new AssertionError("no " + name + " in " + pipelines);
    }

    @Test
    void ordersStarterLoadsItsSamplesToo(@TempDir Path root) throws Exception {
        ship(root, "orders-starter");
        try (Ctx c = open(root)) {
            HttpResponse<String> r = send(c, "POST", "/spaces",
                    "{\"id\":\"shop\",\"template\":\"orders-starter\",\"loadSamples\":true}", null);
            assertEquals(200, r.statusCode(), r.body());
            JsonNode p = json(r).get("samples").get("pipelines").get(0);
            assertEquals("orders", p.get("pipeline").asText());
            assertEquals("loaded", p.get("status").asText(), p.toString());
            assertEquals(3, p.get("filesLoaded").asInt(), p.toString());
        }
    }

    @Test
    void withoutLoadSamplesNothingIsRunAndTheAnswerIsTheManifest(@TempDir Path root) throws Exception {
        ship(root, "la-sample");
        try (Ctx c = open(root)) {
            HttpResponse<String> r = send(c, "POST", "/spaces", "{\"id\":\"plain\",\"template\":\"la-sample\"}", null);
            assertEquals(200, r.statusCode(), r.body());
            assertFalse(json(r).has("samples"), r.body());
        }
    }

    @Test
    void aTemplateWithoutSamplesIsRefusedAndNothingIsCreated(@TempDir Path root) throws Exception {
        seedEmptyTemplate(root);
        try (Ctx c = open(root)) {
            HttpResponse<String> r = send(c, "POST", "/spaces", "{\"id\":\"bare1\",\"template\":\"bare\",\"loadSamples\":true}", null);
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("ships no sample data"), r.body());
            assertFalse(Files.exists(root.resolve("bare1")), "no Space directory");
            assertFalse(send(c, "GET", "/spaces", null, null).body().contains("bare1"));

            HttpResponse<String> noTemplate = send(c, "POST", "/spaces", "{\"id\":\"bare2\",\"loadSamples\":true}", null);
            assertEquals(422, noTemplate.statusCode(), noTemplate.body());
            assertTrue(noTemplate.body().contains("loadSamples needs a 'template'"), noTemplate.body());
            assertFalse(Files.exists(root.resolve("bare2")));

            HttpResponse<String> unknown = send(c, "POST", "/spaces", "{\"id\":\"bare3\",\"template\":\"nope\",\"loadSamples\":true}", null);
            assertEquals(400, unknown.statusCode(), unknown.body());
            HttpResponse<String> escape = send(c, "POST", "/spaces", "{\"id\":\"bare4\",\"template\":\"../bare1\",\"loadSamples\":true}", null);
            assertEquals(400, escape.statusCode(), escape.body());
        }
    }

    /** Standard: the create stays gated on canAdminister; the samples load only for a subject holding it. */
    @Test
    void signedInTheCreateNeedsCanAdminister(@TempDir Path root) throws Exception {
        ship(root, "la-sample");
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case ADMIN -> Optional.of(new Subject("admin-1", Set.of(Roles.CAN_ADMINISTER, Roles.CAN_AUTHOR_WORKBENCH)));
            case NOBODY -> Optional.of(new Subject("nobody-1", Set.of()));
            default -> Optional.empty();
        });
        String body = "{\"id\":\"sample\",\"template\":\"la-sample\",\"loadSamples\":true}";
        try (Ctx c = open(root)) {
            assertEquals(401, send(c, "POST", "/spaces", body, null).statusCode());
            HttpResponse<String> denied = send(c, "POST", "/spaces", body, NOBODY);
            assertEquals(403, denied.statusCode(), denied.body());
            assertFalse(Files.exists(root.resolve("sample")), "a refused create leaves nothing");
            HttpResponse<String> ok = send(c, "POST", "/spaces", body, ADMIN);
            assertEquals(200, ok.statusCode(), ok.body());
            JsonNode p = json(ok).get("samples").get("pipelines").get(0);
            assertEquals("loaded", p.get("status").asText(), ok.body());
            assertEquals(3, p.get("filesLoaded").asInt(), ok.body());
        }
    }

    private static JsonNode find(JsonNode list, String id) {
        for (JsonNode t : list) if (id.equals(t.get("id").asText())) return t;
        throw new AssertionError("no template " + id + " in " + list);
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path));
        if (auth != null) b.header("Authorization", auth);
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception { return V1Body.of(r.body()); }
}
