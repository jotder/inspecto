package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.alert.AlertRule;
import com.gamma.alert.AlertService;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
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
import java.nio.file.Path;
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
 * LA-18 over real HTTP — {@code GET /inv/value-measures} (a named value Measure over the WHOLE Dataset) and a
 * value-measure Alert Rule bound through the LA-23 route {@code POST /inv/investigations/{id}/alert-rules}: it
 * watches the COUNT of entities breaching the Measure's thresholds, fires when that count is above 0, and the
 * binding answers the entities.
 *
 * <p>Fixture: {@code ValueMeasuresTest.ROWS} — the structuring ring into HUB is made of 950 legs only, so a view
 * narrowed to {@code amt ≥ 5 000} would hold none of it (the §2.6 trap); the Measure reads the Dataset.
 */
class ControlApiValueMeasureTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CREATE = "{\"purpose\":\"test\",\"id\":\"case-v\",\"dataset\":\"tx_ds\","
            + "\"sourceCol\":\"src\",\"targetCol\":\"dst\",\"linkKindCol\":\"ch\"}";
    private static final String Q = "/inv/value-measures?dataset=tx_ds&sourceCol=src&targetCol=dst&linkKindCol=ch"
            + "&valueCol=amt&timeCol=booked&from=2026-09-01&to=2026-09-08";
    private static final String STRUCTURING_RULE = "{\"name\":\"smurfs\",\"severity\":\"CRITICAL\",\"valueMeasure\":"
            + "{\"name\":\"structuring\",\"valueCol\":\"amt\",\"timeCol\":\"booked\",\"from\":\"2026-09-01\","
            + "\"to\":\"2026-09-08\"}}";
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
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            if (writeRoot != null) {
                new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("tx_view", "flow-x", List.of(),
                        com.gamma.geolink.ValueMeasuresTest.ROWS, "2026-09-30T00:00:00Z"));
                new ComponentStore(writeRoot.resolve("registry")).write("dataset", "tx_ds", Map.of("view", "tx_view"));
            }
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

    private JsonNode ok(Ctx c, String method, String path, String body) throws Exception {
        HttpResponse<String> r = send(c.port, method, path, body, null);
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private int status(Ctx c, String method, String path, String body) throws Exception {
        return send(c.port, method, path, body, null).statusCode();
    }

    // ── GET /inv/value-measures ────────────────────────────────────────────────────────────────────────

    @Test
    void theStructuringMeasureFindsTheSubThresholdRingWithItsThresholdsVisible(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            List<Event> seen = new CopyOnWriteArrayList<>();
            Consumer<Event> sub = seen::add;
            EventLog.current().addSubscriber(sub);
            JsonNode m;
            try {
                m = ok(c, "GET", Q + "&name=structuring", null);
            } finally {
                EventLog.current().removeSubscriber(sub);
            }
            assertEquals("HUB", m.at("/entities/0/entity").asText(), m.toString());
            assertEquals(1, m.get("count").asInt());
            assertEquals(900, m.at("/measure/min").asDouble(), "every default spelled out");
            assertEquals(10, m.at("/measure/minLegs").asDouble());
            assertEquals("≥ 10 legs 900 ≤ amt < 1000 from ≥ 5 payers", m.get("threshold").asText());
            assertEquals(1, m.get("unvalued").asInt());
            assertFalse(m.get("truncated").asBoolean());
            assertTrue(seen.stream().anyMatch(e -> EventType.LINK_VALUE_MEASURED.equals(e.type())
                    && "structuring".equals(e.attributes().get("measure"))), "audited");

            JsonNode pt = ok(c, "GET", Q + "&name=passThrough&minRatio=0.96", null);
            assertEquals("HUB", pt.at("/entities/0/entity").asText(), "thresholds are editable on the query");
            assertTrue(pt.at("/entities/0/retention").isNumber(), "retention shown as a derived label");
            assertEquals("TILL6", ok(c, "GET", Q + "&name=cashOutConcentration&cashOutKinds=cash_out", null)
                    .at("/entities/0/entity").asText());
        }
    }

    @Test
    void theReadFailsClosed(@TempDir Path cfg, @TempDir Path root, @TempDir Path cfg2) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(422, status(c, "GET", Q + "&name=structuring&filter=amt%3E%3D5000", null),
                    "no view filter is accepted — the Measure reads the Dataset (the ≥ 5 000 trap)");
            assertEquals(422, status(c, "GET", Q + "&name=median", null));
            assertEquals(422, status(c, "GET", Q, null), "no name");
            assertEquals(422, status(c, "GET", Q.replace("valueCol=amt", "valueCol=nope") + "&name=structuring", null));
            assertEquals(422, status(c, "GET", Q.replace("sourceCol=src", "sourceCol=a;b") + "&name=structuring", null));
            assertEquals(422, status(c, "GET", Q + "&name=timeToCashOut", null), "no cashOutKinds");
            assertEquals(404, status(c, "GET", Q.replace("tx_ds", "ghost_ds") + "&name=structuring", null));
        }
        try (Ctx c = open(cfg2, null)) {
            assertEquals(503, status(c, "GET", Q + "&name=structuring", null));
        }
    }

    // ── Alert Rule: the count of breaching entities, fires when > 0 ─────────────────────────────────────

    @Test
    void aBoundValueMeasureRuleFiresOnTheCountOfBreachingEntities(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            ok(c, "POST", "/inv/investigations", CREATE);
            JsonNode bound = ok(c, "POST", "/inv/investigations/case-v/alert-rules", STRUCTURING_RULE);
            assertEquals("gte", bound.at("/rule/comparator").asText(), "fixed: at least one entity");
            assertEquals(1, bound.at("/rule/threshold").asDouble());
            assertEquals(10, bound.at("/rule/valueMeasure/minLegs").asDouble(), "thresholds stored visibly");
            assertEquals(1, bound.get("current").asDouble(), "exactly one entity breaches");
            assertTrue(bound.get("wouldFire").asBoolean());
            assertEquals("HUB", bound.at("/entities/0/entity").asText(), "the answer lists the entities");

            AlertRule onDisk = AlertRule.fromMap(new ComponentStore(root.resolve("registry"))
                    .get("alert-rule", "smurfs").orElseThrow().content());
            assertTrue(onDisk.isValueMeasureRule());

            JsonNode fired = ok(c, "POST", "/alerts/evaluate", "");
            assertEquals(1, fired.size(), "one Alert for the rule, never one per entity: " + fired);
            assertEquals("smurfs", fired.at("/0/rule").asText());
            assertEquals("case-v", fired.at("/0/pipeline").asText());
            assertEquals("entities breaching structuring", fired.at("/0/metric").asText());
            assertTrue(fired.at("/0/message").asText().contains("over the whole Dataset, window: 2026-09-01T00:00 to 2026-09-08T00:00"),
                    fired.at("/0/message").asText());
            assertEquals(1, fired.at("/0/value").asDouble());
        }
    }

    @Test
    void aRuleNoEntityBreachesNeverFires(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            ok(c, "POST", "/inv/investigations", CREATE);
            JsonNode bound = ok(c, "POST", "/inv/investigations/case-v/alert-rules",
                    STRUCTURING_RULE.replace("\"name\":\"smurfs\"", "\"name\":\"quiet\"")
                            .replace("\"to\":\"2026-09-08\"}", "\"to\":\"2026-09-08\",\"minLegs\":13}"));
            assertEquals(0, bound.get("current").asDouble());
            assertFalse(bound.get("wouldFire").asBoolean());
            assertEquals(0, ok(c, "POST", "/alerts/evaluate", "").size());
        }
    }

    @Test
    void bindingAValueMeasureRuleFailsClosed(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            ok(c, "POST", "/inv/investigations", CREATE);
            String path = "/inv/investigations/case-v/alert-rules";
            for (String bad : List.of(
                    STRUCTURING_RULE.replace("\"severity\"", "\"threshold\":5,\"severity\""),
                    STRUCTURING_RULE.replace("\"severity\"", "\"measure\":\"count\",\"severity\""),
                    STRUCTURING_RULE.replace("structuring", "valueWeightedLinks"),
                    STRUCTURING_RULE.replace("\"valueCol\":\"amt\"", "\"valueCol\":\"nope\""),
                    STRUCTURING_RULE.replace("2026-09-08", "2026-12-08"),
                    "{\"name\":\"r\",\"severity\":\"INFO\",\"valueMeasure\":\"structuring\"}"))
                assertEquals(422, status(c, "POST", path, bad), bad);
            assertTrue(c.alerts().rules().isEmpty(), "nothing armed by a refused binding");
        }
    }

    // ── agentList: cashOutConcentration restricted to an `agent` Entity List ─────────────────────────────

    private void entityList(Ctx c, String id, String type, String... members) throws Exception {
        assertEquals(201, send(c.port, "POST", "/entity-lists", "{\"id\":\"" + id + "\",\"title\":\"" + id
                + "\",\"purpose\":\"watch\",\"entityType\":\"" + type + "\",\"reason\":\"r\"}", null).statusCode());
        if (members.length > 0)
            ok(c, "POST", "/entity-lists/" + id + "/members", "{\"add\":[\"" + String.join("\",\"", members)
                    + "\"],\"reason\":\"r\"}");
    }

    @Test
    void anAgentListRestrictsCashOutConcentrationAndFailsClosed(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            java.nio.file.Files.writeString(root.resolve("link-analysis.toon"), "masking_mode: none\n");
            entityList(c, "tills", "agent", "till6");         // upper-trim: stated in another form
            entityList(c, "other-tills", "agent", "TILL1");
            entityList(c, "phones", "msisdn", "+447700900123");
            String co = Q + "&name=cashOutConcentration&cashOutKinds=cash_out";
            JsonNode m = ok(c, "GET", co + "&agentList=tills", null);
            assertEquals("TILL6", m.at("/entities/0/entity").asText(), m.toString());
            assertEquals("tills", m.at("/measure/agentList").asText());
            assertEquals(0, ok(c, "GET", co + "&agentList=other-tills", null).get("count").asInt(),
                    "TILL6 is not on that list; TILL1 is under the share");
            assertEquals(404, status(c, "GET", co + "&agentList=ghost", null), "unknown list");
            assertEquals(422, status(c, "GET", co + "&agentList=phones", null), "not of Entity Type agent");
            assertEquals(422, status(c, "GET", Q + "&name=structuring&agentList=tills", null), "cash-out concentration only");
        }
    }

    /**
     * A3 (operator 2026-09-30): the rule reads its agent list LIVE, and each firing records the list VERSION in force
     * when that sweep evaluated - the identity fact log's head seq/hash - on the Alert and its ALERT object.
     */
    @Test
    void eachFiringRecordsTheAgentListVersionTheSweepRead(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            java.nio.file.Files.writeString(root.resolve("link-analysis.toon"), "masking_mode: none\n");
            entityList(c, "tills", "agent", "TILL6");
            ok(c, "POST", "/inv/investigations", CREATE);
            ok(c, "POST", "/inv/investigations/case-v/alert-rules", "{\"name\":\"tills-rule\",\"severity\":\"WARNING\","
                    + "\"valueMeasure\":{\"name\":\"cashOutConcentration\",\"valueCol\":\"amt\",\"timeCol\":\"booked\","
                    + "\"from\":\"2026-09-01\",\"to\":\"2026-09-08\",\"cashOutKinds\":[\"cash_out\"],\"agentList\":\"tills\"}}");
            JsonNode head = ok(c, "GET", "/entity-lists", null);
            JsonNode fired = ok(c, "POST", "/alerts/evaluate", "");
            assertEquals(1, fired.size(), fired.toString());
            assertEquals("tills", fired.at("/0/evidence/agentList").asText(), fired.toString());
            assertEquals(head.get("headSeq").asText(), fired.at("/0/evidence/agentListSeq").asText(), fired.toString());
            assertEquals(head.get("headHash").asText(), fired.at("/0/evidence/agentListHash").asText(), fired.toString());

            // live, not pinned: after the list changes, the next reading carries the NEW version
            ok(c, "POST", "/entity-lists/tills/members", "{\"add\":[\"TILL1\"],\"reason\":\"r\"}");
            JsonNode after = ok(c, "GET", "/entity-lists", null);
            AlertRule rule = AlertRule.fromMap(c.alerts().rules().stream()   // the armed rule, as the sweep holds it
                    .filter(r -> "tills-rule".equals(r.get("name"))).findFirst().orElseThrow());
            var reading = new com.gamma.geolink.WorkingSetMeasures().read(root, null, rule);
            assertTrue(reading.value().isPresent(), "still evaluated");
            assertEquals(after.get("headSeq").asText(), reading.evidence().get("agentListSeq"));
            assertFalse(head.get("headSeq").asText().equals(reading.evidence().get("agentListSeq")), "the version moved");
        }
    }

    /** The twin: a value-measure rule with NO agent list records no list version. */
    @Test
    void aRuleWithoutAnAgentListRecordsNoListVersion(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            ok(c, "POST", "/inv/investigations", CREATE);
            ok(c, "POST", "/inv/investigations/case-v/alert-rules", STRUCTURING_RULE);
            JsonNode fired = ok(c, "POST", "/alerts/evaluate", "");
            assertEquals(1, fired.size(), fired.toString());
            assertTrue(fired.at("/0/evidence/agentListSeq").isMissingNode(), fired.toString());
        }
    }

    @Test
    void aMaskedAgentListNeverLeaksItsMembers(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            java.nio.file.Files.writeString(root.resolve("link-analysis.toon"), "masking_mode: all\n");
            entityList(c, "tills", "agent", "TILL6");
            String token = JSON.readTree(send(c.port, "GET", "/entity-lists/tills", null, null).body())
                    .at("/data/members/0").asText();
            assertTrue(token.startsWith("masked:"), token);
            HttpResponse<String> r = send(c.port, "GET", Q + "&name=cashOutConcentration&cashOutKinds=cash_out"
                    + "&agentList=tills", null, null);
            assertEquals(200, r.statusCode(), r.body());
            assertEquals(token, JSON.readTree(r.body()).at("/data/entities/0/entity").asText(), "the list's own token");
            assertFalse(r.body().contains("TILL6"), r.body());
        }
    }

    // ── rolling windows (`last`) ───────────────────────────────────────────────────────────────────────

    @Test
    void aRollingRuleIsStoredRelativeAndTheSweepReadsTheWindowNow(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            // the structuring ring, 2 hours ago in UTC — a fixed window would have to be re-authored to see it
            String twoHoursAgo = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(2)
                    .withNano(0).toString().replace('T', ' ');
            new ViewStore(root.resolve("views")).write(new ViewDefinition("tx_view", "flow-x", List.of(),
                    com.gamma.geolink.ValueMeasuresTest.ROWS.replace("2026-09-01 10:00:00", twoHoursAgo),
                    "2026-09-30T00:00:00Z"));
            JsonNode m = ok(c, "GET", Q.replace("&from=2026-09-01&to=2026-09-08", "&last=24h") + "&name=structuring", null);
            assertEquals("HUB", m.at("/entities/0/entity").asText(), m.toString());
            assertEquals("UTC", m.at("/window/timezone").asText());
            assertEquals("24h", m.at("/measure/last").asText());
            assertEquals(0, ok(c, "GET", Q.replace("&from=2026-09-01&to=2026-09-08", "&last=1h") + "&name=structuring",
                    null).get("count").asInt(), "the ring is 2 h old: outside the last hour");
            assertEquals(422, status(c, "GET", Q + "&last=24h&name=structuring", null), "both windows");
            assertEquals(422, status(c, "GET", Q.replace("&from=2026-09-01&to=2026-09-08", "&last=32d")
                    + "&name=structuring", null), "the 31-day cap");

            ok(c, "POST", "/inv/investigations", CREATE);
            String rule = "{\"name\":\"rolling\",\"severity\":\"CRITICAL\",\"valueMeasure\":{\"name\":\"structuring\","
                    + "\"valueCol\":\"amt\",\"timeCol\":\"booked\",\"last\":\"24h\"}}";
            JsonNode bound = ok(c, "POST", "/inv/investigations/case-v/alert-rules", rule);
            assertEquals("24h", bound.at("/rule/valueMeasure/last").asText());
            assertTrue(bound.at("/rule/valueMeasure/from").isMissingNode(), "never a baked window: " + bound);
            assertEquals(1, bound.get("current").asDouble());
            JsonNode fired = ok(c, "POST", "/alerts/evaluate", "");
            assertEquals(1, fired.size(), fired.toString());
            String text = fired.at("/0/message").asText();   // plan §5.10: one scope, one window - never a contradiction
            assertTrue(text.contains("over the whole Dataset, window: last 24h"), text);
            assertFalse(text.contains("the whole Dataset, the last"), text);
            // plan §5.10: a from/to with Z is normalised to UTC, not refused
            assertEquals(ok(c, "GET", Q + "&name=structuring", null).get("count").asInt(),
                    ok(c, "GET", Q.replace("from=2026-09-01&to=2026-09-08",
                            "from=2026-09-01T00:00:00Z&to=2026-09-08T00:00:00Z") + "&name=structuring", null).get("count").asInt());
            assertEquals(422, status(c, "POST", "/inv/investigations/case-v/alert-rules",
                    rule.replace("\"last\":\"24h\"", "\"last\":\"24h\",\"from\":\"2026-09-01\"")), "exactly one window");
        }
    }

    /** {@code canAuthorAlertRules} — tested WITH a Subject, since with none {@code withCapability} is a no-op. */
    @Test
    void bindingNeedsTheAlertAuthoringCapability(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case "Bearer owner" -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents", "canAuthorAlertRules")));
            case "Bearer owner-noalerts" -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents")));
            default -> Optional.empty();
        });
        try (Ctx c = open(cfg, root)) {
            assertEquals(200, send(c.port, "POST", "/inv/investigations", CREATE, "Bearer owner").statusCode());
            String path = "/inv/investigations/case-v/alert-rules";
            assertEquals(403, send(c.port, "POST", path, STRUCTURING_RULE, "Bearer owner-noalerts").statusCode());
            assertTrue(c.alerts().rules().isEmpty());
            assertEquals(200, send(c.port, "POST", path, STRUCTURING_RULE, "Bearer owner").statusCode());
            assertEquals(1, c.alerts().evaluateAll().size());
        } finally {
            Authenticators.forTest(null);
        }
    }
}
