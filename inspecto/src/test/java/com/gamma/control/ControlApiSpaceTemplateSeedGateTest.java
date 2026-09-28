package com.gamma.control;

import com.gamma.metrics.MetricRegistry;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * `ASSURE-KPI-DEFINITIONS-RESIDUALS-1` (1) ⚠ over real HTTP: EVERY registry kind a Space Template seeds meets its own
 * route's save validation and the capability {@link ImportCapabilityGuard} maps for it ({@link TemplateSeedGate}); a
 * refusal creates no Space. Every shipped template still applies.
 */
class ControlApiSpaceTemplateSeedGateTest {

    private static final String ADMIN = "Bearer admin", OPS = "Bearer ops";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SpaceManager spaces, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }

    /** admin: every capability a template kind can need; ops: administers only. */
    @BeforeEach
    void arm() {
        com.gamma.etl.EditionFeatures.overrideForTest(Set.of(com.gamma.etl.EditionFeatures.ALERT_DISPATCH));
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case ADMIN -> Optional.of(new Subject("admin-1", Set.of(Roles.CAN_ADMINISTER, Roles.CAN_AUTHOR_WORKBENCH,
                    Roles.CAN_AUTHOR_ALERT_RULES, Roles.CAN_ONBOARD_CONNECTIONS, Roles.CAN_MANAGE_INCIDENTS)));
            case OPS -> Optional.of(new Subject("ops-1", Set.of(Roles.CAN_ADMINISTER)));
            default -> Optional.empty();
        });
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
        com.gamma.etl.EditionFeatures.overrideForTest(null);
    }

    private Ctx open(Path root) throws Exception {
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        Ctx c = new Ctx(spaces, api, api.port());
        // a hosted Space first: the zero-Space recovery create asks no capability at all
        assertEquals(200, send(c, "POST", "/spaces", "{\"id\":\"first\"}", ADMIN).statusCode());
        return c;
    }

    private static void copyTree(Path src, Path dst) throws Exception {
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : walk.sorted().toList()) {
                Path t = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t);
                else Files.copy(p, t);
            }
        }
    }

    @Test
    void everyShippedTemplateApplies(@TempDir Path root) throws Exception {
        Path shipped = Path.of("..", "spaces", "_templates");
        List<String> ids;
        try (Stream<Path> s = Files.list(shipped)) {
            ids = s.filter(p -> Files.exists(p.resolve("template.toon"))).map(p -> p.getFileName().toString()).sorted().toList();
        }
        assertFalse(ids.isEmpty(), "the repo ships at least one Space Template");
        copyTree(shipped, root.resolve("_templates"));
        try (Ctx c = open(root)) {
            for (String id : ids) {
                String space = "t-" + id.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
                HttpResponse<String> r = send(c, "POST", "/spaces",
                        "{\"id\":\"" + space + "\",\"template\":\"" + id + "\"}", ADMIN);
                assertEquals(200, r.statusCode(), "shipped template '" + id + "': " + r.body());
            }
        }
    }

    private static void seedAlertTemplate(Path root, String alertToon) throws Exception {
        Path cfg = root.resolve("_templates").resolve("alerting").resolve("config");
        Files.createDirectories(cfg.resolve("registry").resolve("alert-rules"));
        Files.writeString(cfg.getParent().resolve("template.toon"), "name: Alerting\n");
        Files.writeString(cfg.resolve("registry").resolve("alert-rules").resolve("errors.toon"), alertToon);
    }

    private static final String VALID_ALERT = """
            name: errors
            metric: error_rate
            comparator: gt
            threshold: 0.05
            window: 1h
            severity: WARNING
            onPipeline: ORDERS
            """;

    @Test
    void anInvalidTemplateAlertRuleRefusesTheWholeTemplate(@TempDir Path root) throws Exception {
        seedAlertTemplate(root, "name: errors\nseverity: WARNING\n");
        try (Ctx c = open(root)) {
            HttpResponse<String> r = send(c, "POST", "/spaces", "{\"id\":\"acme\",\"template\":\"alerting\"}", ADMIN);
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("template alert-rule 'errors' is refused") && !r.body().contains("Professional"), r.body());
            assertFalse(Files.exists(root.resolve("acme")), "a refused template leaves no Space directory");
            assertFalse(send(c, "GET", "/spaces", null, ADMIN).body().contains("\"acme\""));
        }
    }

    @Test
    void aTemplateKindTheCallerLacksTheCapabilityForIsRefused(@TempDir Path root) throws Exception {
        seedAlertTemplate(root, VALID_ALERT);
        try (Ctx c = open(root)) {
            HttpResponse<String> denied = send(c, "POST", "/spaces", "{\"id\":\"acme\",\"template\":\"alerting\"}", OPS);
            assertEquals(403, denied.statusCode(), denied.body());
            assertTrue(denied.body().contains(Roles.CAN_AUTHOR_ALERT_RULES), denied.body());
            assertFalse(Files.exists(root.resolve("acme")), "a denied template leaves no Space directory");
            assertFalse(send(c, "GET", "/spaces", null, ADMIN).body().contains("\"acme\""));
            // the same template, by a caller who holds the capability, applies
            HttpResponse<String> ok = send(c, "POST", "/spaces", "{\"id\":\"acme\",\"template\":\"alerting\"}", ADMIN);
            assertEquals(200, ok.statusCode(), ok.body());
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
