package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.metrics.MetricRegistry;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.SpaceManager;
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
 * `ASSURE-KPI-DEFINITIONS-RESIDUALS-1` (1) over real HTTP, with an armed Authenticator: a Space Template's KPI pack
 * ({@code config/registry/kpis/}) meets the {@code /components/kpi} save gate on {@code POST /spaces} with a
 * {@code template} — against the template's own Datasets — and a refusal (invalid KPI, missing capability) creates
 * no Space at all.
 */
class ControlApiSpaceTemplateKpiTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ADMIN = "Bearer admin", OPS = "Bearer ops";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SpaceManager spaces, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }

    /** admin: administers and authors; ops: administers only (may create a Space, may not author a KPI). */
    @BeforeEach
    void arm() {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case ADMIN -> Optional.of(new Subject("admin-1", Set.of(Roles.CAN_ADMINISTER, Roles.CAN_AUTHOR_WORKBENCH)));
            case OPS -> Optional.of(new Subject("ops-1", Set.of(Roles.CAN_ADMINISTER)));
            default -> Optional.empty();
        });
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

    /** A template with a view-backed {@code orders} Dataset, one {@code kpi} over it, and a KPI tile bound by id. */
    private static void seedTemplate(Path root, String kpiToon) throws Exception {
        Path cfg = root.resolve("_templates").resolve("kpi-pack").resolve("config");
        Files.createDirectories(cfg.resolve("registry").resolve("datasets"));
        Files.createDirectories(cfg.resolve("registry").resolve("kpis"));
        Files.createDirectories(cfg.resolve("registry").resolve("widgets"));
        Files.writeString(cfg.getParent().resolve("template.toon"), "name: KPI pack\ncontents[1]: \"KPI\"\n");
        new ViewStore(cfg.resolve("views")).write(new ViewDefinition("orders_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES (DATE '2026-07-02', 10.0), (DATE '2026-08-01', 20.0),"
                        + " (DATE '2026-08-10', 30.0)) AS t(order_date, amount)", "2026-08-01T00:00:00Z"));
        Files.writeString(cfg.resolve("registry").resolve("datasets").resolve("orders.toon"), "view: orders_view\n");
        Files.writeString(cfg.resolve("registry").resolve("kpis").resolve("revenue.toon"), kpiToon);
        Files.writeString(cfg.resolve("registry").resolve("widgets").resolve("revenue_tile.toon"), """
                vizType: kpi
                options:
                  title: Revenue
                  kpi:
                    kpiId: revenue
                """);
    }

    private static final String VALID_KPI = """
            name: revenue
            title: Revenue
            dataset: orders
            measure: sum(amount)
            timeField: order_date
            grain: month
            comparison: previous
            direction: up
            target: 60
            """;

    @Test
    void aTemplateKpiPackIsSeededThroughTheSaveGateAndEvaluates(@TempDir Path root) throws Exception {
        seedTemplate(root, VALID_KPI);
        try (Ctx c = open(root)) {
            assertEquals(200, send(c, "POST", "/spaces", "{\"id\":\"acme\",\"template\":\"kpi-pack\"}", ADMIN).statusCode());
            HttpResponse<String> v = send(c, "GET", "/spaces/acme/kpis/revenue/value?asOf=2026-08-13", null, ADMIN);
            assertEquals(200, v.statusCode(), v.body());
            JsonNode d = JSON.readTree(v.body()).get("data");
            assertEquals(50.0, d.get("value").asDouble(), 1e-9);
            assertEquals(10.0, d.get("comparisonValue").asDouble(), 1e-9);
            assertEquals("orders", d.get("dataset").asText());
            HttpResponse<String> tile = send(c, "GET", "/spaces/acme/components/widget/revenue_tile", null, ADMIN);
            assertEquals(200, tile.statusCode(), tile.body());
            assertTrue(tile.body().contains("\"kpiId\":\"revenue\""), "the tile binds the seeded KPI by id: " + tile.body());
        }
    }

    @Test
    void anInvalidTemplateKpiRefusesTheWholeTemplate(@TempDir Path root) throws Exception {
        seedTemplate(root, VALID_KPI.replace("timeField: order_date", "timeField: shipped_on"));
        try (Ctx c = open(root)) {
            HttpResponse<String> r = send(c, "POST", "/spaces", "{\"id\":\"acme\",\"template\":\"kpi-pack\"}", ADMIN);
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("template kpi 'revenue' is refused") && r.body().contains("shipped_on"), r.body());
            assertFalse(Files.exists(root.resolve("acme")), "a refused template leaves no Space directory");
            assertFalse(send(c, "GET", "/spaces", null, ADMIN).body().contains("\"acme\""));
        }
        seedTemplate(root, VALID_KPI.replace("dataset: orders", "dataset: nowhere"));
        try (Ctx c = open(root)) {
            HttpResponse<String> r = send(c, "POST", "/spaces", "{\"id\":\"acme\",\"template\":\"kpi-pack\"}", ADMIN);
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("'nowhere' does not exist"), r.body());
            assertFalse(Files.exists(root.resolve("acme")));
        }
    }

    @Test
    void aSharedAwayTemplateDatasetIsRefusedAsAnAccessRefusalNotAsAbsence(@TempDir Path root) throws Exception {
        seedTemplate(root, VALID_KPI);
        Files.writeString(root.resolve("_templates/kpi-pack/config/registry/datasets/orders.toon"), """
                view: orders_view
                owner: carol
                shares[1]{subjectType,subjectId,access}:
                  user,dave,view
                """);
        try (Ctx c = open(root)) {
            HttpResponse<String> r = send(c, "POST", "/spaces", "{\"id\":\"acme\",\"template\":\"kpi-pack\"}", ADMIN);
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("'orders' is shared away from you"), r.body());
            assertFalse(Files.exists(root.resolve("acme")));
        }
    }

    @Test
    void aTemplateKpiPackNeedsTheComponentsKpiCapability(@TempDir Path root) throws Exception {
        seedTemplate(root, VALID_KPI);
        try (Ctx c = open(root)) {
            // a hosted Space first: the zero-Space recovery create asks no capability at all
            assertEquals(200, send(c, "POST", "/spaces", "{\"id\":\"first\"}", OPS).statusCode());
            HttpResponse<String> denied = send(c, "POST", "/spaces", "{\"id\":\"acme\",\"template\":\"kpi-pack\"}", OPS);
            assertEquals(403, denied.statusCode(), denied.body());
            assertTrue(denied.body().contains("canAuthorWorkbench"), denied.body());
            assertFalse(Files.exists(root.resolve("acme")), "a denied template leaves no Space directory");
            assertEquals(200, send(c, "POST", "/spaces", "{\"id\":\"acme\",\"template\":\"kpi-pack\"}", ADMIN).statusCode());
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        if (auth != null) b.header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }
}
