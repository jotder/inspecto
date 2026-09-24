package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.alert.AlertRule;
import com.gamma.alert.AlertService;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-23 — <b>Measure over the Working Set → Alert Rule → Alert</b> over real HTTP: the declared Measures and one
 * asked-for Measure in the BI shorthand; binding an Alert Rule to one (owner-only / PDP via
 * {@code InvestigationRoutes.open}, {@code canAuthorAlertRules}); the bound rule firing through the EXISTING
 * {@code AlertService} path ({@code POST /alerts/evaluate} → the fired Alert, scoped to the Investigation); and the
 * sweep-time gate — a rule the owner did not bind, or one edited since, never evaluates.
 *
 * <p>Fixture: the LA-10 call graph {@code alice–bob (sms ×2), alice–carol (call), bob–dave, bob–erin, carol–frank};
 * {@code case-a} is seed alice + one expand ⇒ entities {alice, bob, carol}, links alice–bob and alice–carol.
 */
class ControlApiInvestigationAlertRuleTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ROWS = "('alice','bob','sms'),('alice','bob','sms'),('alice','carol','call'),"
            + "('bob','dave','call'),('bob','erin','sms'),('carol','frank','call')";
    private static final String CREATE =
            "{\"purpose\":\"test\",\"id\":\"case-a\",\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\"}";
    private static final String BIG_RING = "{\"name\":\"big-ring\",\"measure\":\"count\",\"comparator\":\"gte\","
            + "\"threshold\":3,\"severity\":\"CRITICAL\"}";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }

        AlertService alerts() {
            return svc.alertService().orElseThrow();
        }
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                    "SELECT * FROM (VALUES " + ROWS + ") AS t(caller,callee,channel)", "2026-09-23T00:00:00Z"));
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view"));
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        if (auth != null) b.header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r) throws Exception {
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private JsonNode call(Ctx c, String method, String path, String body) throws Exception {
        return data(send(c.port, method, path, body, null));
    }

    private int status(Ctx c, String method, String path, String body) throws Exception {
        return send(c.port, method, path, body, null).statusCode();
    }

    private void caseA(Ctx c, String auth) throws Exception {
        assertEquals(200, send(c.port, "POST", "/inv/investigations", CREATE, auth).statusCode());
        for (String op : List.of("{\"op\":\"seed\",\"ids\":[\"alice\"]}", "{\"op\":\"expand\"}"))
            assertEquals(200, send(c.port, "POST", "/inv/investigations/case-a/ops", op, auth).statusCode());
    }

    private static double measure(JsonNode m, String name) {
        for (JsonNode d : m.get("measures")) if (name.equals(d.get("name").asText())) return d.get("value").asDouble();
        throw new AssertionError("no measure " + name + " in " + m);
    }

    // ── Measure over the Working Set ───────────────────────────────────────────────────────────────────

    @Test
    void theWorkingSetAnswersItsDeclaredMeasuresAndOneAskedForInTheMeasureShorthand(@TempDir Path cfg,
                                                                                     @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            caseA(c, null);
            JsonNode m = call(c, "GET", "/inv/investigations/case-a/measures", null);
            assertEquals(3, measure(m, "entities"));
            assertEquals(2, measure(m, "links"));
            assertEquals(3, measure(m, "events"), "sum(count): alice–bob twice + alice–carol once");
            assertEquals(0, measure(m, "excluded"));
            assertEquals(1, measure(m, "maxHop"));
            assertEquals("[{\"kind\":\"call\",\"links\":1,\"events\":1},{\"kind\":\"sms\",\"links\":1,\"events\":2}]",
                    m.get("byKind").toString(), "links counted by kind");
            assertEquals(2, m.at("/head/step").asInt());

            JsonNode one = call(c, "GET", "/inv/investigations/case-a/measures?relation=links&measure=countDistinct(kind)", null);
            assertEquals(2, one.at("/measure/value").asDouble());
            assertTrue(one.get("cached").asBoolean(), "the same cached relation the Working Set route serves");
            for (String bad : List.of("?measure=median(hop)", "?relation=links&measure=sum(nope)",
                    "?relation=links&measure=sum(kind)", "?relation=edges&measure=count", "?relation=links"))
                assertEquals(422, status(c, "GET", "/inv/investigations/case-a/measures" + bad, null), bad);
            assertEquals(404, status(c, "GET", "/inv/investigations/nope/measures", null));
        }
    }

    // ── Alert Rule → Alert, through the existing AlertService path ─────────────────────────────────────

    @Test
    void aBoundRuleFiresThroughTheAlertServiceScopedToTheInvestigation(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            caseA(c, null);
            JsonNode bound = call(c, "POST", "/inv/investigations/case-a/alert-rules", BIG_RING);
            assertEquals("case-a", bound.at("/rule/investigation").asText(), "the Investigation is the path's");
            assertEquals("entities", bound.at("/rule/relation").asText());
            assertEquals(3, bound.get("current").asDouble());
            assertTrue(bound.get("wouldFire").asBoolean());
            assertTrue(bound.get("disclosure").asText().contains("Alerts and Incidents"));

            AlertRule onDisk = AlertRule.fromMap(new ComponentStore(root.resolve("registry"))
                    .get("alert-rule", "big-ring").orElseThrow().content());
            assertEquals("case-a", onDisk.investigation(), "an ordinary alert-rule component the boot loader re-arms");
            assertTrue(Files.isRegularFile(root.resolve("audit/snapshots/investigations/case-a/alert-rules/big-ring.json")),
                    "the owner's binding is recorded beside the Investigation");
            assertTrue(call(c, "GET", "/alerts/rules", null).toString().contains("\"investigation\":\"case-a\""),
                    "armed in the running engine");

            JsonNode fired = call(c, "POST", "/alerts/evaluate", "");
            assertEquals(1, fired.size(), fired.toString());
            assertEquals("big-ring", fired.at("/0/rule").asText());
            assertEquals("case-a", fired.at("/0/pipeline").asText(), "scoped to the Investigation");
            assertEquals("entities count", fired.at("/0/metric").asText());
            assertEquals(3, fired.at("/0/value").asDouble());
            assertEquals("big-ring", call(c, "GET", "/alerts", null).at("/0/rule").asText(), "in the Alert feed");

            assertEquals(409, status(c, "POST", "/inv/investigations/case-a/alert-rules", BIG_RING));
        }
    }

    /**
     * A sweep has no caller, so the owner gate travels as the binding. A rule written around the binding route
     * (straight into the registry and armed) never evaluates; nor does a bound rule edited since it was bound.
     */
    @Test
    void aRuleTheOwnerDidNotBindOrThatChangedSinceNeverEvaluates(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            caseA(c, null);
            AlertRule forged = AlertRule.fromMap(Map.of("name", "forged", "investigation", "case-a",
                    "measure", "count", "comparator", "gte", "threshold", 1, "severity", "CRITICAL"));
            new ComponentStore(root.resolve("registry")).write("alert-rule", "forged", forged.toMap());
            c.alerts().upsert(forged);
            assertEquals(0, call(c, "POST", "/alerts/evaluate", "").size(), "no binding → not evaluated");

            call(c, "POST", "/inv/investigations/case-a/alert-rules", BIG_RING);
            c.alerts().upsert(AlertRule.fromMap(Map.of("name", "big-ring", "investigation", "case-a",
                    "relation", "links", "measure", "count", "comparator", "gte", "threshold", 1,
                    "severity", "CRITICAL")));
            assertEquals(0, call(c, "POST", "/alerts/evaluate", "").size(),
                    "the bound rule was changed after binding — its hash no longer matches, so it is not evaluated");
        }
    }

    // ── gates ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void bindingFailsClosed(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            caseA(c, null);
            String path = "/inv/investigations/case-a/alert-rules";
            assertEquals(404, status(c, "POST", "/inv/investigations/nope/alert-rules", BIG_RING));
            for (String bad : List.of(
                    "{\"name\":\"r\",\"dataset\":\"calls_ds\",\"measure\":\"count\",\"comparator\":\"gt\",\"threshold\":1,\"severity\":\"INFO\"}",
                    "{\"name\":\"r\",\"investigation\":\"someone-else\",\"measure\":\"count\",\"comparator\":\"gt\",\"threshold\":1,\"severity\":\"INFO\"}",
                    "{\"name\":\"r\",\"relation\":\"edges\",\"measure\":\"count\",\"comparator\":\"gt\",\"threshold\":1,\"severity\":\"INFO\"}",
                    "{\"name\":\"r\",\"relation\":\"links\",\"measure\":\"sum(kind)\",\"comparator\":\"gt\",\"threshold\":1,\"severity\":\"INFO\"}",
                    "{\"name\":\"r\",\"measure\":\"count\",\"comparator\":\"between\",\"threshold\":1,\"severity\":\"INFO\"}",
                    "{\"name\":\"../r\",\"measure\":\"count\",\"comparator\":\"gt\",\"threshold\":1,\"severity\":\"INFO\"}"))
                assertEquals(422, status(c, "POST", path, bad), bad);
            assertTrue(c.alerts().rules().isEmpty(), "nothing armed by a refused binding");
        }
    }

    /**
     * Owner-only (a non-owner who MAY author Alert Rules is still refused — 404, as absence) and
     * {@code canAuthorAlertRules} (tested WITH a Subject, since with none {@code withCapability} is a no-op).
     */
    @Test
    void onlyTheOwnerBindsAndBindingNeedsTheAlertAuthoringCapability(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case "Bearer owner" -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents", "canAuthorAlertRules")));
            case "Bearer owner-noalerts" -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents")));
            case "Bearer other" -> Optional.of(new Subject("analyst-2", Set.of("canManageIncidents", "canAuthorAlertRules")));
            default -> Optional.empty();
        });
        try (Ctx c = open(cfg, root)) {
            caseA(c, "Bearer owner");
            String path = "/inv/investigations/case-a/alert-rules";
            assertEquals(403, send(c.port, "POST", path, BIG_RING, "Bearer owner-noalerts").statusCode());
            HttpResponse<String> other = send(c.port, "POST", path, BIG_RING, "Bearer other");
            assertEquals(404, other.statusCode(), "a rule authored by a non-owner is refused: " + other.body());
            assertEquals(404, send(c.port, "GET", "/inv/investigations/case-a/measures", null, "Bearer other").statusCode());
            assertFalse(new ComponentStore(root.resolve("registry")).get("alert-rule", "big-ring").isPresent());

            assertEquals(200, send(c.port, "POST", path, BIG_RING, "Bearer owner").statusCode());
            assertEquals(1, c.alerts().evaluateAll().size(), "the owner's binding evaluates on a caller-less sweep");
        } finally {
            Authenticators.forTest(null);
        }
    }

    /** Enterprise: a PDP DENY on the Investigation refuses the owner too — measuring it, binding to it, templating it. */
    @Test
    void anAccessDeciderDenyRefusesTheOwnersMeasuresBindingAndTemplate(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        Authenticators.forTest(ex -> "Bearer owner".equals(ex.getRequestHeaders().getFirst("Authorization"))
                ? Optional.of(new Subject("analyst-1", Set.of("canManageIncidents", "canAuthorAlertRules")))
                : Optional.empty());
        java.util.concurrent.atomic.AtomicBoolean deny = new java.util.concurrent.atomic.AtomicBoolean();
        AccessDeciders.forTest((ex, subject, action, route, kind, resource) ->
                deny.get() && "investigation".equals(kind) ? AccessDecider.Decision.DENY : AccessDecider.Decision.ABSTAIN);
        try (Ctx c = open(cfg, root)) {
            caseA(c, "Bearer owner");
            deny.set(true);
            assertEquals(404, send(c.port, "GET", "/inv/investigations/case-a/measures", null, "Bearer owner").statusCode());
            assertEquals(404, send(c.port, "POST", "/inv/investigations/case-a/alert-rules", BIG_RING, "Bearer owner")
                    .statusCode());
            assertEquals(404, send(c.port, "POST", "/inv/investigations/case-a/template", "{}", "Bearer owner")
                    .statusCode());
            assertTrue(c.alerts().rules().isEmpty());
        } finally {
            AccessDeciders.forTest(null);
            Authenticators.forTest(null);
        }
    }
}
