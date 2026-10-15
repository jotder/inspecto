package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.Roles;
import com.gamma.entitystore.LaLimit;
import com.gamma.entitystore.LaLimits;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET|PUT /settings/link-analysis/limits} and {@code PUT /system/link-analysis/limits} ({@code LA-CONFIG-LIMITS-1}
 * P1, plan §6.2) over real HTTP with real Subjects: the wire shape, the {@code canAuthorWorkbench} /
 * {@code canAdminister} gates, every 422 (unknown key, wrong layer, outside the range, not a number, a cross-item rule)
 * with nothing written, the write-disabled 503, and the two writers of {@code link-analysis.toon} keeping each other's keys.
 */
class ControlApiLinkAnalysisLimitsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SPACE = "/settings/link-analysis/limits";
    private static final String SYSTEM = "/system/link-analysis/limits";
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
                case AUTHOR -> new String[] {"dev-1", "developer"};   // canAuthorWorkbench, not canAdminister
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
        LaLimits.installDocPath(null);
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

    private static JsonNode item(JsonNode data, String key) {
        for (JsonNode i : data.get("items")) if (i.get("key").asText().equals(key)) return i;
        throw new AssertionError("no item " + key);
    }

    private static String message(HttpResponse<String> r) throws Exception {
        return JSON.readTree(r.body()).path("error").path("message").asText();
    }

    @Test
    void theGetListsEveryCatalogueItemInTheContractShape(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, Files.createDirectories(tmp.resolve("config")))) {
            HttpResponse<String> r = send(c, "GET", SPACE, null, AUTHOR);
            JsonNode v = data(r, 200);
            assertTrue(JSON.readTree(r.body()).path("metadata").path("podScoped").asBoolean(false), "install values are this Pod's");
            assertEquals(LaLimit.values().length, v.get("items").size());
            JsonNode p = item(v, "projection_node_cap");
            List<String> fields = new ArrayList<>();
            p.fieldNames().forEachRemaining(fields::add);
            assertEquals(List.of("key", "layer", "browser", "group", "unit", "default", "min", "max", "value", "inForce", "editable",
                    "description"), fields);
            assertEquals("space", p.get("layer").asText());
            assertTrue(p.get("browser").asBoolean());
            assertEquals("canvas", p.get("group").asText());
            assertEquals("nodes", p.get("unit").asText());
            assertEquals(500, p.get("default").asInt());
            assertEquals(1, p.get("min").asInt());
            assertEquals(100_000, p.get("max").asInt());
            assertTrue(p.get("value").isNull(), "nothing stated = inherit");
            assertEquals(500, p.get("inForce").asInt());
            assertTrue(p.get("editable").asBoolean(), "an author may change a Space item");
            assertTrue(item(v, "four_eyes_budget_above").get("default").isNull(), "no threshold");
            assertEquals(0.5, item(v, "rate.link_analysis_per_s").get("inForce").asDouble());
            assertFalse(item(v, "graph_run.ceiling_nodes").get("editable").asBoolean(), "an author may not change an install item");
            assertEquals("install", item(v, "graph_run.ceiling_nodes").get("layer").asText());
            // ordered by group: every group's items are contiguous
            Set<String> seen = new LinkedHashSet<>();
            String last = null;
            for (JsonNode i : v.get("items")) {
                String g = i.get("group").asText();
                if (!g.equals(last)) assertTrue(seen.add(g), "group '" + g + "' is split");
                last = g;
            }
            JsonNode asAdmin = data(send(c, "GET", SPACE, null, ADMIN), 200);
            assertTrue(item(asAdmin, "graph_run.ceiling_nodes").get("editable").asBoolean());
            assertFalse(item(asAdmin, "projection_node_cap").get("editable").asBoolean());
        }
    }

    @Test
    void theSpacePutNeedsCanAuthorWorkbenchAndSetsAndClears(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, Files.createDirectories(tmp.resolve("config")))) {
            Path doc = c.root.resolve(LinkAnalysisSettings.FILE);
            assertEquals(403, send(c, "PUT", SPACE, "{\"traversal.timeout_s\":7}", ADMIN).statusCode(), "no canAuthorWorkbench");
            assertFalse(Files.exists(doc));
            JsonNode v = data(send(c, "PUT", SPACE, "{\"traversal.timeout_s\":7,\"projection_node_cap\":900,"
                    + "\"browser_algorithms.pageRank\":1500}", AUTHOR), 200);
            assertEquals(7, item(v, "traversal.timeout_s").get("value").asInt());
            assertEquals(7, item(v, "traversal.timeout_s").get("inForce").asInt());
            assertEquals(1500, item(v, "browser_algorithms.pageRank").get("inForce").asInt());
            assertEquals(7, LaLimits.forSpace(c.root).get(LaLimit.TRAVERSAL_TIMEOUT_S), "persisted in the Space's link-analysis.toon");
            v = data(send(c, "PUT", SPACE, "{\"traversal.timeout_s\":null}", AUTHOR), 200);
            assertTrue(item(v, "traversal.timeout_s").get("value").isNull(), "null clears = inherit");
            assertEquals(10, item(v, "traversal.timeout_s").get("inForce").asInt());
            assertEquals(900, item(v, "projection_node_cap").get("value").asInt(), "a key the body does not name is kept");
        }
    }

    @Test
    void everyBadSpaceBodyIs422NamingTheKeyAndNothingIsWritten(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, Files.createDirectories(tmp.resolve("config")))) {
            Path doc = c.root.resolve(LinkAnalysisSettings.FILE);
            HttpResponse<String> r = send(c, "PUT", SPACE, "{\"traversal.timeout_s\":301}", AUTHOR);
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(message(r).contains("traversal.timeout_s") && message(r).contains("1..300"), message(r));
            r = send(c, "PUT", SPACE, "{\"no.such_limit\":1}", AUTHOR);
            assertEquals(422, r.statusCode());
            assertTrue(message(r).contains("no.such_limit"), message(r));
            r = send(c, "PUT", SPACE, "{\"graph_run.ceiling_nodes\":600000}", AUTHOR);
            assertEquals(422, r.statusCode(), "an install key on the Space route");
            assertTrue(message(r).contains("graph_run.ceiling_nodes") && message(r).contains(SYSTEM), message(r));
            for (String bad : List.of("{\"traversal.timeout_s\":\"7\"}", "{\"traversal.timeout_s\":2.5}", "{\"traversal.timeout_s\":0}",
                    "{\"drafts.expire_after_days\":1,\"drafts.hibernate_after_minutes\":2880}",
                    "{\"embed.default_ttl_hours\":800}"))
                assertEquals(422, send(c, "PUT", SPACE, bad, AUTHOR).statusCode(), bad);
            // all or nothing: one good key beside one bad one writes neither
            assertEquals(422, send(c, "PUT", SPACE, "{\"projection_node_cap\":900,\"traversal.timeout_s\":999}", AUTHOR).statusCode());
            assertFalse(Files.exists(doc), "a refused write writes nothing");
        }
    }

    @Test
    void theSystemPutNeedsCanAdministerAndWritesTheServerWideDocument(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, Files.createDirectories(tmp.resolve("config")))) {
            Path doc = LaLimits.installDocPath();
            assertTrue(doc != null && doc.getFileName().toString().equals(LaLimits.INSTALL_FILE), "installed at boot: " + doc);
            assertEquals(403, send(c, "PUT", SYSTEM, "{\"graph_run.ceiling_nodes\":600000}", AUTHOR).statusCode(), "no canAdminister");
            assertFalse(Files.exists(doc));
            HttpResponse<String> r = send(c, "PUT", SYSTEM, "{\"projection_node_cap\":900}", ADMIN);
            assertEquals(422, r.statusCode(), "a Space key on the install route");
            assertEquals(422, send(c, "PUT", SYSTEM, "{\"rate.link_analysis_per_s\":0}", ADMIN).statusCode(), "outside 0.01..1000");
            assertFalse(Files.exists(doc));
            r = send(c, "PUT", SYSTEM, "{\"graph_run.ceiling_nodes\":600000,\"rate.link_analysis_per_s\":0.25}", ADMIN);
            JsonNode v = data(r, 200);
            assertTrue(JSON.readTree(r.body()).path("metadata").path("podScoped").asBoolean(false), "Pod-scoped like /system/scheduler");
            assertEquals(600_000, item(v, "graph_run.ceiling_nodes").get("inForce").asInt());
            assertEquals(0.25, item(v, "rate.link_analysis_per_s").get("value").asDouble());
            assertTrue(Files.exists(doc));
            assertEquals(500, item(v, "projection_node_cap").get("inForce").asInt(), "the Space document is untouched");
        }
    }

    @Test
    void writesAre503WhenNoWritableSpaceIsBound(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            assertEquals(503, send(c, "PUT", SPACE, "{\"traversal.timeout_s\":7}", AUTHOR).statusCode());
            assertEquals(503, send(c, "PUT", SYSTEM, "{\"graph_run.ceiling_nodes\":600000}", ADMIN).statusCode());
            JsonNode v = data(send(c, "GET", SPACE, null, AUTHOR), 200);
            assertFalse(item(v, "projection_node_cap").get("editable").asBoolean(), "nobody may change what cannot be written");
            assertFalse(item(data(send(c, "GET", SPACE, null, ADMIN), 200), "graph_run.ceiling_nodes").get("editable").asBoolean());
        }
    }

    @Test
    void theOldSettingsPutAndTheLimitsPutKeepEachOthersKeys(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, Files.createDirectories(tmp.resolve("config")))) {
            // the new route first, then the old whole-document PUT: traversal.timeout_s must survive it
            data(send(c, "PUT", SPACE, "{\"traversal.timeout_s\":7,\"index.frontier_cap\":40}", AUTHOR), 200);
            data(send(c, "PUT", "/settings/link-analysis", "{\"projectionNodeCap\":900,\"maskingMode\":\"all\","
                    + "\"index\":{\"keepVersions\":3}}", AUTHOR), 200);
            JsonNode v = data(send(c, "GET", SPACE, null, AUTHOR), 200);
            assertEquals(7, item(v, "traversal.timeout_s").get("value").asInt(), "kept by the old PUT");
            assertEquals(40, item(v, "index.frontier_cap").get("value").asInt(), "kept inside a block the old PUT models");
            assertEquals(900, item(v, "projection_node_cap").get("value").asInt());
            assertEquals(3, item(v, "index.keep_versions").get("value").asInt());
            // and vice versa: the new route leaves everything the old PUT wrote
            data(send(c, "PUT", SPACE, "{\"projection_node_cap\":1200}", AUTHOR), 200);
            JsonNode old = data(send(c, "GET", "/settings/link-analysis", null, AUTHOR), 200);
            assertEquals(1200, old.get("projectionNodeCap").asInt());
            assertEquals("all", old.get("maskingMode").asText());
            assertEquals(3, old.get("indexInForce").get("keepVersions").asInt());
        }
    }
}
