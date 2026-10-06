package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.event.Event;
import com.gamma.event.EventType;
import com.gamma.workflow.ObjectType;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FINDINGS-EDITOR-PER-CASE-TYPE-1, option (c) (operator, 2026-10-06): every Case Findings form carries a BUILT-IN
 * Impact field whose value IS the Case's typed {@code Impact.confirmed}. A spec save that removes, re-keys or
 * retypes it is a 422 (only its label may change); a Findings save with it writes the Case's Impact through the
 * impact path (same gate, validation, audit), never into the Findings blob; and the analytics read sees it.
 */
class ControlApiFindingsImpactFieldTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private static final Authenticator FAKE = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) return Optional.empty();
        String role = auth.substring("Bearer ".length());
        Roles.Def def = Roles.SEED.get(role);
        return def == null ? Optional.empty() : Optional.of(new Subject("u-" + role, def.capabilities()));
    };

    @AfterEach
    void tearDown() {
        Authenticators.forTest(null);
        System.clearProperty("assist.write.root");
    }

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Authenticators.forTest(FAKE);
        Path writeRoot = java.nio.file.Files.createDirectories(dir.resolve("cfg"));
        System.setProperty("assist.write.root", writeRoot.toString());
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    private static String openCase(Ctx c) {
        return TestOpsEngine.of(c.svc).open(ObjectType.CASE, "leak", "d", "MAJOR", null, Map.of()).id();
    }

    @Test
    void theBuiltInImpactFieldIsServedOnEveryCaseForm(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode impact = field(V1Body.of(send(c.port, "GET", "/findings/case", null, "operations").body()), "impact");
            assertNotNull(impact, "the default Case form carries the built-in Impact field");
            assertEquals("number", impact.get("type").asText());
            JsonNode incident = V1Body.of(send(c.port, "GET", "/findings/incident", null, "operations").body());
            assertNull(field(incident, "impact"), "an Incident form is not a Case form");
        }
    }

    @Test
    void aSpecThatDropsReKeysOrRetypesTheImpactFieldIs422AndARelabelIsAccepted(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String head = "{\"name\":\"case\",\"objectType\":\"case\",\"sections\":[{\"key\":\"summary\",\"type\":\"multiline\"}";
            for (String bad : List.of(
                    head + "]}",                                                                          // dropped
                    head + ",{\"key\":\"loss\",\"label\":\"Impact\",\"type\":\"number\",\"tier\":\"required\"}]}", // re-keyed
                    head + ",{\"key\":\"impact\",\"type\":\"string\",\"tier\":\"required\"}]}",          // retyped
                    head + ",{\"key\":\"impact\",\"type\":\"number\",\"tier\":\"advanced\"}]}",          // re-tiered
                    head + ",{\"key\":\"impact\",\"type\":\"number\",\"tier\":\"required\",\"required\":true}]}")) {
                HttpResponse<String> r = send(c.port, "POST", "/components/findings-spec", bad, "operations");
                assertEquals(422, r.statusCode(), bad + " -> " + r.body());
                assertTrue(r.body().contains("impact"), r.body());
            }
            // a per-Case-type form is held to the same lock
            assertEquals(422, send(c.port, "POST", "/components/findings-spec",
                    "{\"name\":\"case.sim-box\",\"objectType\":\"case\",\"caseType\":\"sim-box\","
                            + "\"sections\":[{\"key\":\"summary\"}]}", "operations").statusCode());

            HttpResponse<String> ok = send(c.port, "POST", "/components/findings-spec", head
                    + ",{\"key\":\"impact\",\"label\":\"Confirmed loss\",\"type\":\"number\",\"tier\":\"required\"}]}",
                    "operations");
            assertEquals(200, ok.statusCode(), ok.body());
            JsonNode served = V1Body.of(send(c.port, "GET", "/findings/case", null, "operations").body());
            assertEquals("Confirmed loss", field(served, "impact").get("label").asText(), "only the label may change");
        }
    }

    @Test
    void aFindingsSaveWritesTheCasesImpactAuditedAndTheAnalyticsReadSeesIt(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String id = openCase(c);
            assertEquals(200, send(c.port, "PUT", "/objects/" + id + "/impact",
                    "{\"impact\":{\"suspected\":\"9000\",\"currency\":\"EUR\",\"basis\":\"CDR gap\"}}", "operations").statusCode());

            List<Event> seen = new CopyOnWriteArrayList<>();
            Consumer<Event> sub = seen::add;
            c.svc.eventLog().addSubscriber(sub);
            HttpResponse<String> r;
            try {
                r = send(c.port, "PUT", "/objects/" + id + "/findings",
                        "{\"findings\":{\"summary\":\"billing gap\",\"impact\":1500.25}}", "operations");
            } finally {
                c.svc.eventLog().removeSubscriber(sub);
            }
            assertEquals(200, r.statusCode(), r.body());

            var o = TestOpsEngine.of(c.svc).get(id).orElseThrow();
            String stored = o.attributes().get("impact");
            assertTrue(stored.contains("\"confirmed\":\"1500.25\""), "the Findings value IS the Case's Impact: " + stored);
            assertTrue(stored.contains("\"suspected\":\"9000\"") && stored.contains("EUR") && stored.contains("CDR gap"),
                    "the rest of the Impact is kept: " + stored);
            assertFalse(o.attributes().get("findings").contains("impact"),
                    "never stored twice — the Findings blob does not hold it: " + o.attributes().get("findings"));
            assertTrue(o.attributes().get("findings").contains("billing gap"));

            List<Event> audits = seen.stream()
                    .filter(e -> EventType.OBJECT_ACTIVITY.equals(e.type()))
                    .filter(e -> "impact".equals(e.attributes().get("action"))).toList();
            assertEquals(1, audits.size(), () -> "audited as an impact write: " + seen);
            assertEquals("u-operations", audits.get(0).attributes().get("actor"));
            assertEquals(stored, audits.get(0).attributes().get("after"));

            HttpResponse<String> a = send(c.port, "GET", "/objects/analytics?type=CASE", null, "operations");
            assertEquals(200, a.statusCode(), a.body());
            JsonNode eur = V1Body.of(a.body()).get("impact").get("byCurrency").get("EUR");
            assertEquals(0, new BigDecimal("1500.25").compareTo(eur.get("confirmed").decimalValue()), a.body());

            // Edited elsewhere: the impact route's value is what the next Findings read sees — one value.
            assertEquals(200, send(c.port, "PUT", "/objects/" + id + "/impact",
                    "{\"impact\":{\"confirmed\":\"7\",\"currency\":\"EUR\"}}", "operations").statusCode());
            var after = TestOpsEngine.of(c.svc).get(id).orElseThrow();
            assertFalse(after.attributes().get("findings").contains("impact"));
            assertTrue(after.attributes().get("impact").contains("\"confirmed\":\"7\""));
        }
    }

    @Test
    void anImpactChangeThroughFindingsKeepsTheImpactRoutesGateAndValidation(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String id = openCase(c);
            HttpResponse<String> noCurrency = send(c.port, "PUT", "/objects/" + id + "/findings",
                    "{\"findings\":{\"impact\":\"10\"}}", "operations");
            assertEquals(422, noCurrency.statusCode(), "an amount needs the Case's currency: " + noCurrency.body());
            assertEquals(200, send(c.port, "PUT", "/objects/" + id + "/impact",
                    "{\"impact\":{\"confirmed\":\"10\",\"currency\":\"EUR\"}}", "operations").statusCode());
            assertEquals(422, send(c.port, "PUT", "/objects/" + id + "/findings",
                    "{\"findings\":{\"impact\":\"-5\"}}", "operations").statusCode(), "negative");

            // Findings stay collaboration: re-sending the unchanged value needs no capability ...
            assertEquals(200, send(c.port, "PUT", "/objects/" + id + "/findings",
                    "{\"findings\":{\"summary\":\"s\",\"impact\":\"10.00\"}}", "business").statusCode());
            // ... but changing the money needs canWorkIncidents, as the impact route does.
            HttpResponse<String> denied = send(c.port, "PUT", "/objects/" + id + "/findings",
                    "{\"findings\":{\"impact\":\"99\"}}", "business");
            assertEquals(403, denied.statusCode(), denied.body());
            assertTrue(TestOpsEngine.of(c.svc).get(id).orElseThrow().attributes().get("impact").contains("\"confirmed\":\"10\""));

            // The PATCH's free bag may not carry it — the Findings blob is never its home.
            HttpResponse<String> patch = send(c.port, "PATCH", "/objects/" + id,
                    "{\"attributes\":{\"findings\":\"{\\\"impact\\\":\\\"3\\\"}\"}}", "super");
            assertEquals(422, patch.statusCode(), patch.body());
        }
    }

    private static JsonNode field(JsonNode spec, String key) {
        for (JsonNode s : spec.get("sections")) if (key.equals(s.get("key").asText())) return s;
        return null;
    }

    private HttpResponse<String> send(int port, String method, String path, String body, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        b.header("Content-Type", "application/json")
                .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        return client.send(b.build(), BodyHandlers.ofString());
    }
}
