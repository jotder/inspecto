package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.alert.AlertRule;
import com.gamma.alert.AlertService;
import com.gamma.alert.InvestigationMeasureProbe;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.event.Event;
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
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-LIVE-DETECTION-1 slices LD-4 and LD-5 over real HTTP: the {@code la.detect} Job Type run through
 * {@code POST /jobs/{name}/trigger}, {@code GET /inv/investigation-templates} (owner-only list),
 * {@code DELETE /inv/investigations/{id}/standing-detection/{rule}} (disable) and
 * {@code PUT /inv/investigations/{id}/alert-rules/{rule}} (edit in place, which drops the owner's authority).
 * Every gate, each with a probe that would otherwise succeed.
 *
 * <p>Fixture: {@code ValueMeasuresTest.ROWS}; the structuring ring into HUB makes the rule's count 1.
 */
class ControlApiStandingDetectionLd5Test {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CREATE = "{\"purpose\":\"test\",\"id\":\"case-v\",\"dataset\":\"tx_ds\","
            + "\"sourceCol\":\"src\",\"targetCol\":\"dst\",\"linkKindCol\":\"ch\"}";
    private static final String RULE = "{\"name\":\"smurfs\",\"severity\":\"CRITICAL\",\"valueMeasure\":"
            + "{\"name\":\"structuring\",\"valueCol\":\"amt\",\"timeCol\":\"booked\",\"from\":\"2026-09-01\","
            + "\"to\":\"2026-09-08\"}}";
    /** The same rule, one severity lower — an in-place edit. */
    private static final String RULE_EDITED = "{\"name\":\"smurfs\",\"severity\":\"WARNING\",\"valueMeasure\":"
            + "{\"name\":\"structuring\",\"valueCol\":\"amt\",\"timeCol\":\"booked\",\"from\":\"2026-09-01\","
            + "\"to\":\"2026-09-08\"}}";
    private static final String BIND = "/inv/investigations/case-v/alert-rules";
    private static final String EDIT = BIND + "/smurfs";
    private static final String ENABLE = "/inv/investigations/case-v/standing-detection";
    private static final String DISABLE = ENABLE + "/smurfs";
    private static final String OWNER = "Bearer owner", NO_ALERTS = "Bearer noalerts", STRANGER = "Bearer stranger";
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
            new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("tx_view", "flow-x", List.of(),
                    com.gamma.la.api.ValueMeasuresTest.ROWS, "2026-09-30T00:00:00Z"));
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "tx_ds", new LinkedHashMap<>(Map.of("view", "tx_view")));
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        if (auth != null) b.header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode ok(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpResponse<String> r = send(c, method, path, body, auth);
        assertTrue(r.statusCode() == 200 || r.statusCode() == 202, r.statusCode() + " " + r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private int status(Ctx c, String method, String path, String body, String auth) throws Exception {
        return send(c, method, path, body, auth).statusCode();
    }

    private static String enableBody() {
        return "{\"rule\":\"smurfs\"}";
    }

    private InvestigationMeasureProbe.Reading reading(Ctx c) {
        AlertRule r = AlertRule.fromMap(c.alerts().rules().stream()
                .filter(x -> "smurfs".equals(x.get("name"))).findFirst().orElseThrow());
        return new com.gamma.geolink.WorkingSetMeasures().read(c.root, null, r);
    }

    private static String binding(Ctx c) throws Exception {
        return Files.readString(c.root.resolve("audit/snapshots/investigations/case-v/alert-rules/smurfs.json"));
    }

    private static void authenticators() {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case "Bearer owner" -> subject(ex, "analyst-1", Set.of("canManageIncidents", "canAuthorAlertRules"));
            case "Bearer noalerts" -> subject(ex, "analyst-1", Set.of("canManageIncidents"));
            case "Bearer stranger" -> subject(ex, "analyst-2", Set.of("canManageIncidents", "canAuthorAlertRules"));
            default -> Optional.empty();
        });
    }

    private static Optional<Subject> subject(com.sun.net.httpserver.HttpExchange ex, String id, Set<String> caps) {
        ComponentAccess.heldRoles(ex, Set.of("analyst"));
        return Optional.of(new Subject(id, caps));
    }

    private static List<String> codes(List<Event> seen, String type) {
        return seen.stream().filter(e -> type.equals(e.type())).map(e -> String.valueOf(e.attributes().get("code"))).toList();
    }

    // ── LD-4: the la.detect Job ────────────────────────────────────────────────────────────────────────

    @Test
    void laDetectRunsTheStandingSweepAndAnUnenabledRuleIsNeverRead(@TempDir Path cfg, @TempDir Path root) throws Exception {
        List<Event> seen = new CopyOnWriteArrayList<>();
        Consumer<Event> sub = seen::add;
        EventLog.current().addSubscriber(sub);
        try (Ctx c = open(cfg, root)) {
            ok(c, "POST", "/inv/investigations", CREATE, null);
            ok(c, "POST", BIND, RULE, null);
            ok(c, "POST", "/jobs", "{\"name\":\"detect\",\"type\":\"la.detect\"}", null);

            JsonNode notEnabled = runJob(c, "detect");
            assertEquals("SUCCESS", notEnabled.get("status").asText(), notEnabled.toString());
            assertTrue(notEnabled.toString().contains("no Investigation rule breached"), notEnabled.toString());
            assertEquals(List.of("NOT_ENABLED"), codes(seen, LinkEventTypes.LINK_STANDING_DETECTION_REFUSED),
                    "the job's sweep reached the rule and the authority gate refused it");

            ok(c, "POST", ENABLE, enableBody(), null);
            JsonNode enabled = runJob(c, "detect");
            assertEquals("SUCCESS", enabled.get("status").asText(), enabled.toString());
            assertTrue(enabled.toString().contains("1 breach(es) - smurfs"), enabled.toString());
            assertFalse(enabled.toString().contains("HUB") || enabled.toString().contains("case-v"),
                    "the run discloses a count and a rule name, never an entity or Investigation id: " + enabled);
            assertEquals(1, seen.stream().filter(e -> LinkEventTypes.LINK_STANDING_DETECTION_SWEPT.equals(e.type())).count());
        } finally {
            EventLog.current().removeSubscriber(sub);
        }
    }

    /** Trigger a Job and poll its Run to a terminal status; returns the Run. */
    private JsonNode runJob(Ctx c, String job) throws Exception {
        String runId = ok(c, "POST", "/jobs/" + job + "/trigger", null, null).get("runId").asText();
        long deadline = System.nanoTime() + 15_000_000_000L;
        JsonNode run = null;
        while (System.nanoTime() < deadline) {
            run = JSON.readTree(send(c, "GET", "/jobs/runs/" + runId, null, null).body()).get("data");
            if (!"RUNNING".equals(run.get("status").asText())) break;
            Thread.sleep(50);
        }
        return run;
    }

    // ── LD-5: the template list ────────────────────────────────────────────────────────────────────────

    @Test
    void theTemplateListShowsOnlyTheCallersOwnTemplatesAsSummaries(@TempDir Path cfg, @TempDir Path root) throws Exception {
        authenticators();
        try (Ctx c = open(cfg, root)) {
            ok(c, "POST", "/inv/investigations", CREATE, OWNER);
            ok(c, "POST", "/inv/investigations/case-v/ops", "{\"op\":\"seed\",\"ids\":[\"HUB\"]}", OWNER);
            ok(c, "POST", "/inv/investigations/case-v/template", "{\"id\":\"tpl-a\",\"title\":\"mine\"}", OWNER);
            assertEquals(0, ok(c, "GET", "/inv/investigation-templates", null, STRANGER).get("templates").size(),
                    "another owner's template is not listed");
            // the probe that would otherwise succeed: the template exists and its owner reads it
            assertEquals(200, status(c, "GET", "/inv/investigation-templates/tpl-a", null, OWNER));
            assertEquals(404, status(c, "GET", "/inv/investigation-templates/tpl-a", null, STRANGER));

            JsonNode mine = ok(c, "GET", "/inv/investigation-templates", null, OWNER).get("templates");
            assertEquals(1, mine.size(), mine.toString());
            JsonNode t = mine.get(0);
            assertEquals("tpl-a", t.get("id").asText());
            assertEquals("mine", t.get("title").asText());
            assertEquals("tx_ds", t.get("dataset").asText());
            assertEquals("case-v", t.get("investigation").asText());
            assertTrue(t.get("parameters").isInt() && t.get("ops").isInt(), "counts, not content: " + t);
            assertFalse(mine.toString().contains("\"seed1\""), "no parameter names or ops in the summary");
        } finally {
            Authenticators.forTest(null);
        }
    }

    @Test
    void theTemplateListFailsClosedWithoutAWriteRoot(@TempDir Path cfg) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        try (Ctx c = new Ctx(svc, api, api.port(), null)) {
            assertEquals(503, status(c, "GET", "/inv/investigation-templates", null, null));
        }
    }

    // ── LD-7: the bound-rule read-back ─────────────────────────────────────────────────────────────────

    @Test
    void theBoundRuleListReadsBackStandingStateAcrossEnableDisableAndEdit(@TempDir Path cfg, @TempDir Path root) throws Exception {
        authenticators();
        try (Ctx c = open(cfg, root)) {
            ok(c, "POST", "/inv/investigations", CREATE, OWNER);
            assertEquals(0, ok(c, "GET", BIND, null, OWNER).get("rules").size(), "nothing bound yet");
            ok(c, "POST", BIND, RULE, OWNER);
            JsonNode bound = ok(c, "GET", BIND, null, OWNER).get("rules");
            assertEquals(1, bound.size(), bound.toString());
            assertEquals("smurfs", bound.get(0).get("rule").get("name").asText());
            assertTrue(bound.get(0).get("valueMeasure").asBoolean());
            assertFalse(bound.get(0).get("edited").asBoolean());
            assertFalse(bound.get(0).get("standingDetection").get("enabled").asBoolean(), "bound, not enabled");

            ok(c, "POST", ENABLE, enableBody(), OWNER);
            JsonNode on = ok(c, "GET", BIND, null, OWNER).get("rules").get(0).get("standingDetection");
            assertTrue(on.get("enabled").asBoolean());
            assertEquals("sweep:case-v", on.get("principal").asText());
            assertEquals("tx_ds", on.get("dataset").asText());
            assertFalse(on.toString().contains("capabilities") || on.toString().contains("masking"), "no recorded authority detail: " + on);

            ok(c, "PUT", EDIT, RULE_EDITED, OWNER);
            JsonNode edited = ok(c, "GET", BIND, null, OWNER).get("rules").get(0);
            assertEquals("WARNING", edited.get("rule").get("severity").asText(), "the edit shows");
            assertFalse(edited.get("standingDetection").get("enabled").asBoolean(), "the edit dropped the authority");

            ok(c, "POST", ENABLE, enableBody(), OWNER);
            ok(c, "DELETE", DISABLE, null, OWNER);
            assertFalse(ok(c, "GET", BIND, null, OWNER).get("rules").get(0).get("standingDetection").get("enabled").asBoolean());

            // gates, each with the probe that would otherwise succeed (the owner read above)
            assertEquals(404, status(c, "GET", BIND, null, STRANGER), "a non-member reads as absence");
            assertEquals(404, status(c, "GET", "/inv/investigations/nope/alert-rules", null, OWNER));
            assertEquals(200, status(c, "GET", BIND, null, NO_ALERTS), "reading needs no alert-authoring capability");
        } finally {
            Authenticators.forTest(null);
        }
    }

    @Test
    void theBoundRuleListFailsClosedWithoutAWriteRoot(@TempDir Path cfg) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        try (Ctx c = new Ctx(svc, api, api.port(), null)) {
            assertEquals(503, status(c, "GET", BIND, null, null));
        }
    }

    // ── LD-5: disable ──────────────────────────────────────────────────────────────────────────────────

    @Test
    void disablingStopsTheSweepIsIdempotentAndReEnablingResumes(@TempDir Path cfg, @TempDir Path root) throws Exception {
        authenticators();
        List<Event> seen = new CopyOnWriteArrayList<>();
        Consumer<Event> sub = seen::add;
        EventLog.current().addSubscriber(sub);
        try (Ctx c = open(cfg, root)) {
            ok(c, "POST", "/inv/investigations", CREATE, OWNER);
            ok(c, "POST", BIND, RULE, OWNER);
            ok(c, "POST", ENABLE, enableBody(), OWNER);
            assertEquals(1.0, reading(c).value().getAsDouble(), "enabled: reads");

            assertEquals(403, status(c, "DELETE", DISABLE, null, NO_ALERTS), "canAuthorAlertRules");
            assertEquals(404, status(c, "DELETE", DISABLE, null, STRANGER), "a non-member reads as absence");
            assertTrue(binding(c).contains("\"standing\""), "a refused disable changes nothing");
            assertEquals(1.0, reading(c).value().getAsDouble(), "…and the sweep still reads");
            assertEquals(404, status(c, "DELETE", ENABLE + "/ghost", null, OWNER), "no such bound rule");
            assertEquals(422, status(c, "DELETE", ENABLE + "/..x", null, OWNER), "unsafe name");
            assertEquals(404, status(c, "DELETE", "/inv/investigations/nope/standing-detection/smurfs", null, OWNER));

            JsonNode off = ok(c, "DELETE", DISABLE, null, OWNER);
            assertTrue(off.get("wasEnabled").asBoolean());
            assertFalse(off.get("enabled").asBoolean());
            assertFalse(binding(c).contains("standing"), "the recorded authority is gone");
            assertTrue(binding(c).contains("\"ruleHash\""), "the binding itself stays");
            assertFalse(reading(c).value().isPresent(), "the sweep now refuses");
            assertEquals(List.of("NOT_ENABLED"), codes(seen, LinkEventTypes.LINK_STANDING_DETECTION_REFUSED));
            // the probe that would otherwise succeed: the rule is still armed and the owner still opens the Investigation
            assertTrue(c.alerts().has("smurfs"));
            assertEquals(200, status(c, "GET", "/inv/investigations/case-v/measures", null, OWNER));
            assertEquals(0, c.alerts().evaluateInvestigationRules().size(), "the la.detect sweep reads nothing either");

            assertFalse(ok(c, "DELETE", DISABLE, null, OWNER).get("wasEnabled").asBoolean(), "idempotent");
            assertEquals(2, seen.stream().filter(e -> LinkEventTypes.LINK_STANDING_DETECTION_DISABLED.equals(e.type())).count());

            ok(c, "POST", ENABLE, enableBody(), OWNER);
            assertTrue(reading(c).value().isPresent(), "re-enabling resumes");
        } finally {
            EventLog.current().removeSubscriber(sub);
            Authenticators.forTest(null);
        }
    }

    // ── LD-5: edit in place ────────────────────────────────────────────────────────────────────────────

    @Test
    void editingABoundRuleInPlaceDropsTheAuthorityAndTheOwnerReEnables(@TempDir Path cfg, @TempDir Path root) throws Exception {
        authenticators();
        try (Ctx c = open(cfg, root)) {
            ok(c, "POST", "/inv/investigations", CREATE, OWNER);
            ok(c, "POST", BIND, RULE, OWNER);
            ok(c, "POST", ENABLE, enableBody(), OWNER);
            String hashBefore = JSON.readTree(binding(c)).get("ruleHash").asText();
            assertEquals(1.0, reading(c).value().getAsDouble());

            JsonNode edited = ok(c, "PUT", EDIT, RULE_EDITED, OWNER);
            assertTrue(edited.get("replaced").asBoolean());
            assertEquals("WARNING", edited.at("/rule/severity").asText());
            assertTrue(edited.get("standingDetection").asText().startsWith("disabled"), edited.toString());
            assertEquals("WARNING", c.alerts().rules().stream().filter(r -> "smurfs".equals(r.get("name"))).findFirst()
                    .orElseThrow().get("severity"), "re-armed with the new rule");
            String after = binding(c);
            assertFalse(after.contains("standing"), "the old authority does not carry to the edited rule");
            assertFalse(after.contains(hashBefore), "the binding holds the new rule's hash");
            assertFalse(reading(c).value().isPresent(), "until the owner re-enables, the sweep refuses");

            ok(c, "POST", ENABLE, enableBody(), OWNER);
            assertEquals(1.0, reading(c).value().getAsDouble(), "re-enabled: a fresh snapshot, reads again");
        } finally {
            Authenticators.forTest(null);
        }
    }

    @Test
    void theEditRouteFailsClosedAtEveryGateAndAFailedEditChangesNothing(@TempDir Path cfg, @TempDir Path root) throws Exception {
        authenticators();
        try (Ctx c = open(cfg, root)) {
            ok(c, "POST", "/inv/investigations", CREATE, OWNER);
            ok(c, "POST", BIND, RULE, OWNER);
            ok(c, "POST", ENABLE, enableBody(), OWNER);
            String before = binding(c);

            assertEquals(403, status(c, "PUT", EDIT, RULE_EDITED, NO_ALERTS), "canAuthorAlertRules");
            assertEquals(404, status(c, "PUT", EDIT, RULE_EDITED, STRANGER), "a non-member reads as absence");
            assertEquals(404, status(c, "PUT", BIND + "/ghost", RULE_EDITED.replace("smurfs", "ghost"), OWNER), "no armed rule");
            assertEquals(422, status(c, "PUT", EDIT, RULE_EDITED.replace("\"smurfs\"", "\"other\""), OWNER), "the name cannot change");
            assertEquals(422, status(c, "PUT", EDIT, RULE_EDITED.replace("}}", "},\"owner\":\"x\"}"), OWNER), "a field outside the shape");
            assertEquals(422, status(c, "PUT", EDIT, "{\"severity\":\"WARNING\",\"valueMeasure\":{\"name\":\"nope\"}}", OWNER),
                    "an invalid value Measure");
            assertEquals(422, status(c, "PUT", BIND + "/..x", RULE_EDITED, OWNER), "unsafe name");
            assertEquals(404, status(c, "PUT", "/inv/investigations/nope/alert-rules/smurfs", RULE_EDITED, OWNER));
            assertEquals(before, binding(c), "no refused edit touched the binding");
            assertEquals("CRITICAL", c.alerts().rules().stream().filter(r -> "smurfs".equals(r.get("name"))).findFirst()
                    .orElseThrow().get("severity"), "…or the armed rule");
            // the probe that would otherwise succeed: after all those refusals the enabled sweep still reads
            assertEquals(1.0, reading(c).value().getAsDouble());

            // a rule bound to ANOTHER Investigation cannot be edited through this one
            ok(c, "POST", "/inv/investigations", CREATE.replace("case-v", "case-w"), OWNER);
            assertEquals(404, status(c, "PUT", "/inv/investigations/case-w/alert-rules/smurfs", RULE_EDITED, OWNER));

            // armed rule edited out of band (not through the bind/edit routes): the binding no longer vouches for it
            Map<String, Object> oob = new LinkedHashMap<>(c.alerts().rules().stream()
                    .filter(x -> "smurfs".equals(x.get("name"))).findFirst().orElseThrow());
            oob.put("severity", "INFO");
            c.alerts().upsert(AlertRule.fromMap(oob));
            assertEquals(409, status(c, "PUT", EDIT, RULE_EDITED, OWNER));
        } finally {
            Authenticators.forTest(null);
        }
    }
}
