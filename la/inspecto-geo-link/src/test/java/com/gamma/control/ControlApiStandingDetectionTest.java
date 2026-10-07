package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.alert.AlertRule;
import com.gamma.alert.AlertService;
import com.gamma.alert.InvestigationMeasureProbe;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.audit.Event;
import com.gamma.event.EventLog;
import com.gamma.la.core.LinkEventTypes;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import com.gamma.access.ComponentAccess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-LIVE-DETECTION-1 slices LD-1..LD-3 over real HTTP — {@code POST /inv/investigations/{id}/standing-detection}
 * (D-LD1 option A: the sweep is {@code sweep:<id>}, holds no capability, and re-decides the OWNER's authority before
 * every read of the live Dataset). Every gate of the route, and each change after enable that must stop the sweep:
 * an un-share, a role-only grant, an access-policy DENY, a tightened masking mode — each with the probe that would
 * otherwise SUCCEED (the owner can still read the Dataset on a request).
 *
 * <p>Fixture: {@code ValueMeasuresTest.ROWS}; the structuring ring into HUB makes the rule's count 1.
 */
class ControlApiStandingDetectionTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CREATE = "{\"purpose\":\"test\",\"id\":\"case-v\",\"dataset\":\"tx_ds\","
            + "\"sourceCol\":\"src\",\"targetCol\":\"dst\",\"linkKindCol\":\"ch\"}";
    private static final String RULE = "{\"name\":\"smurfs\",\"severity\":\"CRITICAL\",\"valueMeasure\":"
            + "{\"name\":\"structuring\",\"valueCol\":\"amt\",\"timeCol\":\"booked\",\"from\":\"2026-09-01\","
            + "\"to\":\"2026-09-08\"}}";
    private static final String BIND = "/inv/investigations/case-v/alert-rules";
    private static final String ENABLE = "/inv/investigations/case-v/standing-detection";
    private static final String OWNER = "Bearer owner", NO_ALERTS = "Bearer noalerts", STRANGER = "Bearer stranger",
            LEAD2 = "Bearer lead2";
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

    private Ctx open(Path configDir, Path writeRoot, Map<String, Object> datasetKeys) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            if (writeRoot != null) {
                new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("tx_view", "flow-x", List.of(),
                        com.gamma.la.api.ValueMeasuresTest.ROWS, "2026-09-30T00:00:00Z"));
                dataset(writeRoot, datasetKeys);
            }
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private static void dataset(Path root, Map<String, Object> extra) throws Exception {
        Map<String, Object> content = new LinkedHashMap<>(Map.of("view", "tx_view"));
        content.putAll(extra);
        new ComponentStore(root.resolve("registry")).write("dataset", "tx_ds", content);
    }

    private static Map<String, Object> share(String type, String id) {
        return Map.of("subjectType", type, "subjectId", id, "access", "view");
    }

    private static Map<String, Object> restricted(Object... shares) {
        return Map.of("owner", "admin-9", "shares", List.of(shares));
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        if (auth != null) b.header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode ok(Ctx c, String path, String body, String auth) throws Exception {
        HttpResponse<String> r = send(c, "POST", path, body, auth);
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private int status(Ctx c, String path, String body, String auth) throws Exception {
        return send(c, "POST", path, body, auth).statusCode();
    }

    private static String enableBody(String rule) {
        return "{\"rule\":\"" + rule + "\"}";
    }

    private InvestigationMeasureProbe.Reading reading(Ctx c, String rule) {
        AlertRule r = AlertRule.fromMap(c.alerts().rules().stream()
                .filter(x -> rule.equals(x.get("name"))).findFirst().orElseThrow());
        return new com.gamma.geolink.WorkingSetMeasures().read(c.root, null, r);
    }

    private static List<String> codes(List<Event> seen, String type) {
        return seen.stream().filter(e -> type.equals(e.type())).map(e -> String.valueOf(e.attributes().get("code"))).toList();
    }

    private static void authenticators() {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case "Bearer owner" -> subject(ex, "analyst-1", Set.of("canManageIncidents", "canAuthorAlertRules"));
            case "Bearer noalerts" -> subject(ex, "analyst-1", Set.of("canManageIncidents"));
            case "Bearer stranger" -> subject(ex, "analyst-2", Set.of("canManageIncidents", "canAuthorAlertRules"));
            case "Bearer lead2" -> subject(ex, "analyst-3", Set.of("canManageIncidents", "canAuthorAlertRules"));
            default -> Optional.empty();
        });
    }

    /** Every Subject holds the "analyst" role, stamped as the security module does (R3). */
    private static Optional<Subject> subject(com.sun.net.httpserver.HttpExchange ex, String id, Set<String> caps) {
        ComponentAccess.heldRoles(ex, Set.of("analyst"));
        return Optional.of(new Subject(id, caps));
    }

    // ── happy path, audit, no ids ──────────────────────────────────────────────────────────────────────

    @Test
    void aSweepReadsTheLiveDatasetOnlyAfterTheOwnerEnablesItAndEveryOutcomeIsAudited(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        List<Event> seen = new CopyOnWriteArrayList<>();
        Consumer<Event> sub = seen::add;
        EventLog.current().addSubscriber(sub);
        try (Ctx c = open(cfg, root, Map.of())) {
            ok(c, "/inv/investigations", CREATE, null);
            ok(c, BIND, RULE, null);
            assertEquals(0, ok(c, "/alerts/evaluate", "", null).size(), "bound but not enabled: the sweep refuses to read");
            assertFalse(reading(c, "smurfs").value().isPresent());
            assertEquals(List.of("NOT_ENABLED", "NOT_ENABLED"), codes(seen, LinkEventTypes.LINK_STANDING_DETECTION_REFUSED));

            JsonNode on = ok(c, ENABLE, enableBody("smurfs"), null);
            assertEquals("sweep:case-v", on.get("principal").asText());
            assertEquals("tx_ds", on.get("dataset").asText());
            assertEquals("typed", on.at("/masking/mode").asText());
            assertFalse(on.get("replaced").asBoolean());
            String binding = Files.readString(root.resolve("audit/snapshots/investigations/case-v/alert-rules/smurfs.json"));
            assertTrue(binding.contains("\"standing\":{") && binding.contains("\"principal\":\"sweep:case-v\""), binding);
            assertTrue(binding.contains("\"ruleHash\""), "the original binding is kept");

            JsonNode fired = ok(c, "/alerts/evaluate", "", null);
            assertEquals(1, fired.size(), fired.toString());
            assertFalse(fired.toString().contains("HUB"), "the Alert names no entity: " + fired);
            assertTrue(ok(c, ENABLE, enableBody("smurfs"), null).get("replaced").asBoolean(), "re-enable re-snapshots");

            List<Event> swept = seen.stream().filter(e -> LinkEventTypes.LINK_STANDING_DETECTION_SWEPT.equals(e.type())).toList();
            assertEquals(1, swept.size());
            assertEquals("sweep:case-v", swept.get(0).attributes().get("actor"));
            assertEquals("1.0", swept.get(0).attributes().get("value"), "an aggregate count");
            assertEquals(2, seen.stream().filter(e -> LinkEventTypes.LINK_STANDING_DETECTION_ENABLED.equals(e.type())).count());
            assertFalse(seen.stream().anyMatch(e -> String.valueOf(e.attributes()).contains("HUB")), "no entity id in any event");
        } finally {
            EventLog.current().removeSubscriber(sub);
        }
    }

    // ── the route's gates ──────────────────────────────────────────────────────────────────────────────

    @Test
    void theRouteFailsClosedAtEveryGate(@TempDir Path cfg, @TempDir Path root, @TempDir Path cfg2) throws Exception {
        try (Ctx none = open(cfg2, null, Map.of())) {
            assertEquals(503, status(none, ENABLE, enableBody("smurfs"), null), "no write root");
        }
        try (Ctx c = open(cfg, root, Map.of())) {
            ok(c, "/inv/investigations", CREATE, null);
            assertEquals(404, status(c, "/inv/investigations/nope/standing-detection", enableBody("smurfs"), null));
            for (String bad : List.of("{}", "{\"rule\":\"../x\"}", "{\"rule\":\"smurfs\",\"owner\":\"someone\"}",
                    "{\"rule\":\"smurfs\",\"dataset\":\"other\"}"))
                assertEquals(422, status(c, ENABLE, bad, null), bad);
            assertEquals(404, status(c, ENABLE, enableBody("smurfs"), null), "no such armed rule");

            AlertRule forged = AlertRule.fromMap(Map.of("name", "forged", "investigation", "case-v", "comparator", "gte",
                    "threshold", 1, "severity", "CRITICAL", "valueMeasure", Map.of("name", "structuring",
                            "valueCol", "amt", "timeCol", "booked", "from", "2026-09-01", "to", "2026-09-08")));
            new ComponentStore(root.resolve("registry")).write("alert-rule", "forged", forged.toMap());
            assertEquals(404, status(c, ENABLE, enableBody("forged"), null), "a rule written around the bind route has no binding");

            ok(c, BIND, "{\"name\":\"sealed\",\"measure\":\"count\",\"comparator\":\"gte\",\"threshold\":1,\"severity\":\"INFO\"}", null);
            assertEquals(422, status(c, ENABLE, enableBody("sealed"), null), "a sealed-Working-Set rule watches no live Dataset");

            ok(c, BIND, RULE, null);
            Map<String, Object> edited = new LinkedHashMap<>(c.alerts().rules().stream()
                    .filter(x -> "smurfs".equals(x.get("name"))).findFirst().orElseThrow());
            edited.put("severity", "INFO");
            c.alerts().upsert(AlertRule.fromMap(edited));
            assertEquals(409, status(c, ENABLE, enableBody("smurfs"), null), "edited after it was bound");
            assertFalse(Files.readString(root.resolve("audit/snapshots/investigations/case-v/alert-rules/smurfs.json"))
                    .contains("standing"), "a refused enable writes nothing");
        }
    }

    @Test
    void onlyTheOwnerWithTheAlertAuthoringCapabilityEnables(@TempDir Path cfg, @TempDir Path root) throws Exception {
        authenticators();
        try (Ctx c = open(cfg, root, Map.of())) {
            ok(c, "/inv/investigations", CREATE, OWNER);
            ok(c, BIND, RULE, OWNER);
            ok(c, "/inv/investigations/case-v/members", "{\"subject\":\"analyst-3\",\"role\":\"lead\"}", OWNER);
            assertEquals(403, status(c, ENABLE, enableBody("smurfs"), NO_ALERTS), "canAuthorAlertRules");
            assertEquals(404, status(c, ENABLE, enableBody("smurfs"), STRANGER), "a non-member reads as absence");
            // the probe that would otherwise SUCCEED: a LEAD with the capability still may not lend the owner's authority
            assertEquals(403, status(c, ENABLE, enableBody("smurfs"), LEAD2), "a co-lead is not the owner");
            assertFalse(Files.readString(root.resolve("audit/snapshots/investigations/case-v/alert-rules/smurfs.json"))
                    .contains("standing"));
            ok(c, ENABLE, enableBody("smurfs"), OWNER);
            assertEquals(1, c.alerts().evaluateAll().size(), "the owner's enable lets the caller-less sweep read");
        } finally {
            Authenticators.forTest(null);
        }
    }

    // ── D-LD2: a role-only grant cannot be re-resolved ─────────────────────────────────────────────────

    @Test
    void aDatasetSharedToTheOwnerOnlyThroughARoleIsRefusedAtEnableAndAUserShareFixesIt(@TempDir Path cfg,
                                                                                    @TempDir Path root) throws Exception {
        authenticators();
        try (Ctx c = open(cfg, root, restricted(share("role", "analyst")))) {
            ok(c, "/inv/investigations", CREATE, OWNER);   // R3 passes on a request: the owner holds the role
            ok(c, BIND, RULE, OWNER);
            HttpResponse<String> refused = send(c, "POST", ENABLE, enableBody("smurfs"), OWNER);
            assertEquals(422, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains("ROLE_SHARE_ONLY"), refused.body());
            dataset(root, restricted(share("role", "analyst"), share("user", "analyst-1")));
            ok(c, ENABLE, enableBody("smurfs"), OWNER);
        } finally {
            Authenticators.forTest(null);
        }
    }

    // ── LD-3: each change after enable stops the sweep, and undoing it resumes it ──────────────────────

    @Test
    void everyChangeAfterEnableStopsTheSweepOnTheNextRun(@TempDir Path cfg, @TempDir Path root) throws Exception {
        authenticators();
        AtomicBoolean deny = new AtomicBoolean();
        AccessDeciders.forTest((ex, subject, action, route, kind, resource) ->
                deny.get() && "investigation".equals(kind) ? AccessDecider.Decision.DENY : AccessDecider.Decision.ABSTAIN);
        List<Event> seen = new CopyOnWriteArrayList<>();
        Consumer<Event> sub = seen::add;
        EventLog.current().addSubscriber(sub);
        try (Ctx c = open(cfg, root, restricted(share("user", "analyst-1")))) {
            ok(c, "/inv/investigations", CREATE, OWNER);
            ok(c, BIND, RULE, OWNER);
            ok(c, ENABLE, enableBody("smurfs"), OWNER);
            assertEquals(1.0, reading(c, "smurfs").value().getAsDouble(), "enabled and still shared: reads");

            dataset(root, restricted(share("user", "someone-else")));
            assertFalse(reading(c, "smurfs").value().isPresent(), "un-shared");
            // the probe that would otherwise succeed: the owner still reads nothing here only because the sweep re-decides
            dataset(root, restricted(share("role", "analyst")));
            assertFalse(reading(c, "smurfs").value().isPresent(), "role-only: the owner holds the role on a request, the sweep cannot");
            assertEquals(200, send(c, "GET", "/inv/investigations/case-v/measures", null, OWNER).statusCode(),
                    "…and the same owner still opens the Investigation on a request");
            assertTrue(new ComponentStore(root.resolve("registry")).delete("dataset", "tx_ds"));
            assertFalse(reading(c, "smurfs").value().isPresent(), "Dataset gone");

            dataset(root, restricted(share("user", "analyst-1")));
            assertTrue(reading(c, "smurfs").value().isPresent(), "re-shared: resumes without re-enabling");

            deny.set(true);
            assertFalse(reading(c, "smurfs").value().isPresent(), "a PDP DENY");
            deny.set(false);
            assertTrue(reading(c, "smurfs").value().isPresent(), "ABSTAIN grants nothing and hides nothing");

            Files.writeString(root.resolve("link-analysis.toon"), "masking_mode: all\n");
            assertFalse(reading(c, "smurfs").value().isPresent(), "masking tightened typed → all");
            Files.writeString(root.resolve("link-analysis.toon"), "masking_mode: none\n");
            assertTrue(reading(c, "smurfs").value().isPresent(), "loosened: aggregates only, so it keeps running");
            Files.deleteIfExists(root.resolve("link-analysis.toon"));

            assertEquals(List.of("DATASET_NOT_SHARED", "ROLE_SHARE_ONLY", "DATASET_GONE", "POLICY_DENIED", "MASKING_TIGHTENED"),
                    codes(seen, LinkEventTypes.LINK_STANDING_DETECTION_REFUSED));
            assertFalse(seen.stream().anyMatch(e -> String.valueOf(e.attributes()).contains("HUB")), "no entity id in any event");
        } finally {
            EventLog.current().removeSubscriber(sub);
            AccessDeciders.forTest(null);
            Authenticators.forTest(null);
        }
    }
}
