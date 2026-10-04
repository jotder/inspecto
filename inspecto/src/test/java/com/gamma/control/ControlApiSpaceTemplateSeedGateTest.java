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

    private static final String ADMIN = "Bearer admin", OPS = "Bearer ops", NOBODY = "Bearer nobody",
            SEEDED_ADMIN = "Bearer seeded-admin", NO_WORK = "Bearer no-work";
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
                    Roles.CAN_AUTHOR_ALERT_RULES, Roles.CAN_ONBOARD_CONNECTIONS, Roles.CAN_MANAGE_INCIDENTS,
                    "canWorkIncidents")));
            case OPS -> Optional.of(new Subject("ops-1", Set.of(Roles.CAN_ADMINISTER)));
            case NO_WORK -> Optional.of(new Subject("no-work-1", Set.of(Roles.CAN_ADMINISTER, Roles.CAN_AUTHOR_WORKBENCH,
                    Roles.CAN_AUTHOR_ALERT_RULES, Roles.CAN_ONBOARD_CONNECTIONS, Roles.CAN_MANAGE_INCIDENTS)));
            case NOBODY ->Optional.of(new Subject("nobody-1", Set.of()));
            // the fresh-install bootstrap: an IdP subject in the `admin` role, granted through Roles.effective —
            // which serves the SEED table while no Space is hosted (ControlApi binds no roles root then)
            case SEEDED_ADMIN -> Optional.of(new Subject("bootstrap-1", Roles.effective(ex).get("admin").capabilities()));
            default -> Optional.empty();
        });
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
        com.gamma.acquire.ConnectionRegistry.clear();
        com.gamma.etl.EditionFeatures.overrideForTest(null);
    }

    private Ctx open(Path root) throws Exception {
        return open(root, true);
    }

    private Ctx open(Path root, boolean hostFirst) throws Exception {
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        Ctx c = new Ctx(spaces, api, api.port());
        // a hosted Space first: the zero-Space recovery create skips the kind-table capability
        if (hostFirst) assertEquals(200, send(c, "POST", "/spaces", "{\"id\":\"first\"}", ADMIN).statusCode());
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
                // a template's runbooks live in config/runbooks/ (createFromTemplate copies only config/ + data/)
                if (RUNBOOK_PACKS.containsKey(id))
                    assertTrue(Files.isRegularFile(root.resolve(space).resolve("config/runbooks").resolve(RUNBOOK_PACKS.get(id))),
                            "template '" + id + "' must deliver its runbook file to the created Space");
            }
        }
    }

    @Test
    void aTemplatePipelineWithAnUnreadBlockRefusesTheWholeTemplate(@TempDir Path root) throws Exception {
        copyTree(Path.of("..", "spaces", "_templates", "orders-starter"), root.resolve("_templates").resolve("broken"));
        Path pipeline = root.resolve("_templates").resolve("broken").resolve("config/orders/orders_pipeline.toon");
        try (Ctx c = open(root)) {
            // the probe: the untouched copy applies, so the refusal below is the content gate and nothing else
            assertEquals(200, send(c, "POST", "/spaces", "{\"id\":\"ok\",\"template\":\"broken\"}", ADMIN).statusCode());
            Files.writeString(pipeline, Files.readString(pipeline) + "\nno_such_block:\n  x: 1\n");
            HttpResponse<String> r = send(c, "POST", "/spaces", "{\"id\":\"acme\",\"template\":\"broken\"}", ADMIN);
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("template pipeline 'orders_pipeline.toon' is refused"), r.body());
            assertFalse(Files.exists(root.resolve("acme")), "a refused template leaves no Space directory");
        }
    }

    private static final java.util.Map<String, String> RUNBOOK_PACKS = java.util.Map.of(
            "telco-ra", "telco-ra-runbooks.md",
            "business-assurance", "business-assurance-runbooks.md",
            "telco-fraud", "telco-fraud-runbooks.md",
            "payment-fraud", "payment-fraud-runbooks.md");

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

    @Test
    void aTemplateCsvMappingIsValidatedLikeEveryOtherKind(@TempDir Path root) throws Exception {
        Path cfg = root.resolve("_templates").resolve("mapped").resolve("config");
        Files.createDirectories(cfg.resolve("registry").resolve("mappings"));
        Files.writeString(cfg.getParent().resolve("template.toon"), "name: Mapped\n");
        Files.writeString(cfg.resolve("registry").resolve("mappings").resolve("std.csv"),
                "targetColumn,sourceExpression,transformType\nA,A,NOT_A_TRANSFORM\n");
        try (Ctx c = open(root)) {
            HttpResponse<String> r = send(c, "POST", "/spaces", "{\"id\":\"acme\",\"template\":\"mapped\"}", ADMIN);
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("template mapping"), r.body());
            assertFalse(Files.exists(root.resolve("acme")));
        }
    }

    private static void seedInvokeApiTemplate(Path root) throws Exception {
        Path cfg = root.resolve("_templates").resolve("actions").resolve("config");
        Files.createDirectories(cfg.resolve("connections"));
        Files.createDirectories(cfg.resolve("registry").resolve("decision-rules"));
        Files.writeString(cfg.getParent().resolve("template.toon"), "name: Actions\n");
        Files.writeString(cfg.resolve("connections").resolve("hook_connection.toon"),
                "connection:\n  id: hook\n  connector: https\n  host: tickets.test\n  port: 443\n  base_path: api\n");
        Files.writeString(cfg.resolve("registry").resolve("decision-rules").resolve("leak.toon"),
                com.gamma.config.io.ConfigCodec.toToon(java.util.Map.of("name", "leak", "enabled", true,
                        "createdBy", "author-9", "updatedBy", "author-9", "restoredMakers", List.of("author-0"),
                        "consequences", List.of(java.util.Map.of("action", "invoke-api",
                                "params", java.util.Map.of("connection", "hook"))))));
    }

    @Test
    void anInvokeApiRuleOverTheTemplatesOwnConnectionAppliesStampedByTheApplier(@TempDir Path root) throws Exception {
        seedInvokeApiTemplate(root);
        try (Ctx c = open(root)) {
            HttpResponse<String> r = send(c, "POST", "/spaces", "{\"id\":\"acme\",\"template\":\"actions\"}", ADMIN);
            assertEquals(200, r.statusCode(), r.body());
            String stored = Files.readString(root.resolve("acme/config/registry/decision-rules/leak.toon"));
            assertTrue(stored.contains("createdBy: admin-1") && stored.contains("updatedBy: admin-1"), stored);
            assertFalse(stored.contains("author-9") || stored.contains("author-0") || stored.contains("restoredMakers"),
                    "the template file's makers are not trusted: " + stored);
        }
    }

    @Test
    void theRecoveryCreateStillAsksTheInvokeApiCapability(@TempDir Path root) throws Exception {
        seedInvokeApiTemplate(root);
        try (Ctx c = open(root, false)) {
            HttpResponse<String> r = send(c, "POST", "/spaces", "{\"id\":\"acme\",\"template\":\"actions\"}", NO_WORK);
            assertEquals(403, r.statusCode(), r.body());
            assertTrue(r.body().contains("canWorkIncidents"), r.body());
            assertFalse(Files.exists(root.resolve("acme")));
        }
    }

    /**
     * {@code TEMPLATE-RECOVERY-IMPORT-GATE-1} (operator 2026-10-03): the zero-Space recovery create is Space
     * governance — it needs {@code canAdminister}, and a template it applies meets the same import gate as any
     * other create. Where no admin exists yet, the IdP's {@code admin} role resolves through the seeded roles.
     */
    @Test
    void theZeroSpaceRecoveryCreateNeedsCanAdministerAndRunsTheImportGate(@TempDir Path root) throws Exception {
        seedAlertTemplate(root, VALID_ALERT);
        try (Ctx c = open(root, false)) {
            assertEquals(0, c.spaces.size(), "precondition: zero Spaces hosted");
            HttpResponse<String> denied = send(c, "POST", "/spaces", "{\"id\":\"acme\",\"template\":\"alerting\"}", NOBODY);
            assertEquals(403, denied.statusCode(), denied.body());
            assertTrue(denied.body().contains(Roles.CAN_ADMINISTER), denied.body());
            assertEquals(403, send(c, "POST", "/spaces", "{\"id\":\"acme\"}", NOBODY).statusCode(),
                    "a plain recovery create is administration too");
            assertEquals(401, send(c, "POST", "/spaces", "{\"id\":\"acme\"}", null).statusCode());
            // canAdminister alone no longer slips a template's other kinds past the import gate
            HttpResponse<String> gated = send(c, "POST", "/spaces", "{\"id\":\"acme\",\"template\":\"alerting\"}", OPS);
            assertEquals(403, gated.statusCode(), gated.body());
            assertTrue(gated.body().contains(Roles.CAN_AUTHOR_ALERT_RULES), gated.body());
            assertFalse(Files.exists(root.resolve("acme")), "a refused recovery create leaves no Space directory");
            assertEquals(0, c.spaces.size());
            // the seeded `admin` role (fresh install, no authored roles anywhere) recovers the server
            HttpResponse<String> boot = send(c, "POST", "/spaces", "{\"id\":\"boot\"}", SEEDED_ADMIN);
            assertEquals(200, boot.statusCode(), boot.body());
            assertEquals(200, send(c, "DELETE", "/spaces/boot", null, ADMIN).statusCode());
            assertEquals(0, c.spaces.size());
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
