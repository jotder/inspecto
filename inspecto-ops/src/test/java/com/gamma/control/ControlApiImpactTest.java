package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.event.Event;
import com.gamma.event.EventType;
import com.gamma.objects.ObjectType;
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
 * WS-10 ({@code ASSURE-IMPACT-LEDGER-1}): {@code PUT /objects/{id}/impact} records an Incident's or Case's typed
 * financial impact, with {@code outstanding} derived on read. One test per gate — 401/403 (capability, before
 * existence-hiding), 400 (malformed), 422 (validation, another key, a wrong object type), 409 (terminal state),
 * 404 (unknown id) — then the happy path, its audit, and the per-currency analytics roll-up. The 503 a Personal
 * bundle answers is pinned by core's {@code NoOperationalObjectsShipInThePersonalBuildTest}.
 *
 * <p>Armed with seeded-role Subjects ({@link Roles#SEED}), as {@code ControlApiIncidentFinishGateTest} is.
 */
class ControlApiImpactTest {

    private static final String IMPACT = "{\"impact\":{\"suspected\":\"5000\",\"confirmed\":1200.50,"
            + "\"recovered\":\"200.25\",\"prevented\":\"0\",\"currency\":\"eur\",\"period\":\"2026-09\","
            + "\"basis\":\"rated vs billed CDRs\"}}";
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
    }

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Authenticators.forTest(FAKE);
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    private static String open(Ctx c, ObjectType type) {
        return TestOpsEngine.of(c.svc).open(type, "leak", "d", "MAJOR", null, Map.of()).id();
    }

    @Test
    void theImpactRouteNeedsCanWorkIncidents(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String id = open(c, ObjectType.INCIDENT);
            assertEquals(401, send(c.port, "/objects/" + id + "/impact", IMPACT, null).statusCode());
            HttpResponse<String> denied = send(c.port, "/objects/" + id + "/impact", IMPACT, "business");
            assertEquals(403, denied.statusCode(), denied.body());
            assertTrue(denied.body().contains("canWorkIncidents"), denied.body());
            // refused before existence-hiding (a literal path: the auth-gate coverage guard reads it)
            assertEquals(403, send(c.port, "/objects/nope/impact", IMPACT, "business").statusCode());
            assertNull(TestOpsEngine.of(c.svc).get(id).orElseThrow().attributes().get("impact"));
        }
    }

    @Test
    void anInvalidImpactIsRefusedWith422AndNothingIsWritten(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String id = open(c, ObjectType.INCIDENT);
            for (String bad : List.of(
                    "{\"impact\":{\"confirmed\":\"-1\",\"currency\":\"EUR\"}}",          // negative
                    "{\"impact\":{\"confirmed\":\"12,5\",\"currency\":\"EUR\"}}",        // not a decimal
                    "{\"impact\":{\"confirmed\":\"0.1234567\",\"currency\":\"EUR\"}}",   // > 6 decimal places
                    "{\"impact\":{\"confirmed\":\"100\"}}",                              // amount without currency
                    "{\"impact\":{\"confirmed\":\"100\",\"currency\":\"EURO\"}}",        // not ISO 4217
                    "{\"impact\":{\"confirmed\":\"100\",\"currency\":\"XYZ\"}}",         // shaped right, not a code
                    "{\"impact\":{\"confirmed\":\"100\",\"currency\":\"EUR\",\"outstanding\":\"100\"}}", // derived
                    "{\"impact\":{\"amount\":\"100\",\"currency\":\"EUR\"}}",            // unknown field
                    "{\"impact\":{\"basis\":{\"nested\":true}}}",                        // non-string text
                    "{\"impact\":{\"confirmed\":\"" + "0".repeat(40) + "1\",\"currency\":\"EUR\"}}",     // = 1, but > 40 chars
                    "{\"impact\":{\"confirmed\":\"1\",\"currency\":\"EUR\"},\"priority\":\"LOW\"}")) { // other key
                HttpResponse<String> r = send(c.port, "/objects/" + id + "/impact", bad, "operations");
                assertEquals(422, r.statusCode(), bad + " -> " + r.body());
            }
            HttpResponse<String> derived = send(c.port, "/objects/" + id + "/impact",
                    "{\"impact\":{\"confirmed\":\"1\",\"currency\":\"EUR\",\"outstanding\":\"1\"}}", "operations");
            assertTrue(derived.body().contains("derived"), derived.body());
            assertEquals(400, send(c.port, "/objects/" + id + "/impact", "{\"impact\":\"100 EUR\"}", "operations").statusCode());
            assertEquals(400, send(c.port, "/objects/" + id + "/impact", "{}", "operations").statusCode());

            String alert = open(c, ObjectType.ALERT);
            HttpResponse<String> onAlert = send(c.port, "/objects/" + alert + "/impact", IMPACT, "operations");
            assertEquals(422, onAlert.statusCode(), "an Alert carries no impact: " + onAlert.body());

            assertNull(TestOpsEngine.of(c.svc).get(id).orElseThrow().attributes().get("impact"), "nothing was written");
            assertNotEquals("LOW", TestOpsEngine.of(c.svc).get(id).orElseThrow().priority());
        }
    }

    @Test
    void aTerminalObjectsImpactIsClosed409AndAnUnknownIdIs404(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String id = open(c, ObjectType.INCIDENT);
            TestOpsEngine.of(c.svc).transition(id, "archive", "test");
            HttpResponse<String> r = send(c.port, "/objects/" + id + "/impact", IMPACT, "operations");
            assertEquals(409, r.statusCode(), r.body());
            assertTrue(r.body().contains("reopen"), r.body());

            TestOpsEngine.of(c.svc).transition(id, "reopen", "test");
            assertEquals(200, send(c.port, "/objects/" + id + "/impact", IMPACT, "operations").statusCode(),
                    "reopened, its impact is writable again");

            assertEquals(404, send(c.port, "/objects/nope/impact", IMPACT, "operations").statusCode());
        }
    }

    /** The PATCH's free attribute merge must not become a way round the impact route or the Disposition gate. */
    @Test
    void thePatchRefusesTheImpactAndTheDisposition(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String id = open(c, ObjectType.INCIDENT);
            for (String key : List.of("impact", "disposition")) {
                HttpResponse<String> r = send(c.port, "PATCH", "/objects/" + id,
                        "{\"attributes\":{\"" + key + "\":\"CONFIRMED\"}}", "admin");
                assertEquals(422, r.statusCode(), key + " -> " + r.body());
                assertTrue(r.body().contains(key), r.body());
            }
            var after = TestOpsEngine.of(c.svc).get(id).orElseThrow();
            assertNull(after.attributes().get("impact"));
            assertNull(after.attributes().get("disposition"));
            assertEquals(200, send(c.port, "PATCH", "/objects/" + id, "{\"attributes\":{\"tags\":\"x\"}}", "admin").statusCode(),
                    "other attributes still ride the PATCH");
        }
    }

    /**
     * Late recoveries (operator 2026-09-26): on a RESOLVED Incident or a CLOSED Case only `recovered` and
     * `prevented` may still change; any other field is 409, and an ARCHIVED Incident refuses everything.
     */
    @Test
    void lateRecoveriesAreRecordedOnAClosedCaseAndAResolvedIncidentButNothingElseChanges(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            var engine = TestOpsEngine.of(c.svc);
            String cs = open(c, ObjectType.CASE);
            String base = "{\"impact\":{\"confirmed\":\"100\",\"recovered\":\"10\",\"currency\":\"EUR\"}}";
            assertEquals(200, send(c.port, "/objects/" + cs + "/impact", base, "operations").statusCode());
            engine.transition(cs, "investigate", "t");
            engine.transition(cs, "resolve", "t");
            engine.transition(cs, "close", "t");
            HttpResponse<String> late = send(c.port, "/objects/" + cs + "/impact",
                    "{\"impact\":{\"confirmed\":\"100\",\"recovered\":\"60\",\"prevented\":\"5\",\"currency\":\"EUR\"}}", "operations");
            assertEquals(200, late.statusCode(), late.body());
            assertEquals(new BigDecimal("40"), V1Body.of(late.body()).get("impact").get("outstanding").decimalValue());
            HttpResponse<String> rewrite = send(c.port, "/objects/" + cs + "/impact",
                    "{\"impact\":{\"confirmed\":\"200\",\"recovered\":\"60\",\"prevented\":\"5\",\"currency\":\"EUR\"}}", "operations");
            assertEquals(409, rewrite.statusCode(), rewrite.body());
            assertTrue(rewrite.body().contains("confirmed"), rewrite.body());

            String inc = TestOpsEngine.of(c.svc).open(ObjectType.INCIDENT, "leak", "d", "MAJOR", null, Map.of(
                    "dueAt", Long.toString(System.currentTimeMillis() + 86_400_000L),
                    "postmortem", "{\"timeline\":[{\"time\":\"9\",\"text\":\"x\"}],\"causeAnalysis\":[\"y\"],\"actions\":[{\"text\":\"z\"}]}")).id();
            assertEquals(200, send(c.port, "/objects/" + inc + "/impact", base, "operations").statusCode());
            engine.transition(inc, "resolve", "t", "CONFIRMED");
            assertEquals(200, send(c.port, "/objects/" + inc + "/impact",
                    "{\"impact\":{\"confirmed\":\"100\",\"recovered\":\"100\",\"currency\":\"EUR\"}}", "operations").statusCode(),
                    "a recovery after the Incident is resolved");
            assertEquals(409, send(c.port, "/objects/" + inc + "/impact",
                    "{\"impact\":{\"confirmed\":\"100\",\"recovered\":\"100\",\"currency\":\"USD\"}}", "operations").statusCode(),
                    "the currency is not a late recovery");
            engine.transition(inc, "archive", "t");
            assertEquals(409, send(c.port, "/objects/" + inc + "/impact", base, "operations").statusCode(),
                    "ARCHIVED refuses even a recovery");

            // ARCHIVED -> DIAGNOSING (reopen) is the one way back: the Incident is open again, so every field is
            // editable; resolved again (with a fresh Disposition — the reopen cleared the old one), the
            // late-recovery rule holds once more
            engine.transition(inc, "reopen", "t");
            assertEquals(200, send(c.port, "/objects/" + inc + "/impact",
                    "{\"impact\":{\"confirmed\":\"150\",\"recovered\":\"100\",\"currency\":\"EUR\"}}", "operations").statusCode(),
                    "reopened, the whole impact is editable");
            engine.transition(inc, "resolve", "t", "RECOVERED");
            assertEquals(200, send(c.port, "/objects/" + inc + "/impact",
                    "{\"impact\":{\"confirmed\":\"150\",\"recovered\":\"150\",\"currency\":\"EUR\"}}", "operations").statusCode());
            assertEquals(409, send(c.port, "/objects/" + inc + "/impact",
                    "{\"impact\":{\"confirmed\":\"999\",\"recovered\":\"150\",\"currency\":\"EUR\"}}", "operations").statusCode(),
                    "re-resolved, only late recoveries again");
        }
    }

    @Test
    void anAnalystRecordsImpactOutstandingIsDerivedAndTheChangeIsAudited(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String id = open(c, ObjectType.CASE);
            List<Event> seen = new CopyOnWriteArrayList<>();
            Consumer<Event> sub = seen::add;
            c.svc.eventLog().addSubscriber(sub);
            HttpResponse<String> r;
            try {
                r = send(c.port, "/objects/" + id + "/impact", IMPACT, "operations");
                assertEquals(200, r.statusCode(), r.body());
                assertEquals(200, send(c.port, "/objects/" + id + "/impact", "{\"impact\":{}}", "operations").statusCode(),
                        "an empty impact clears it");
            } finally {
                c.svc.eventLog().removeSubscriber(sub);
            }
            JsonNode impact = V1Body.of(r.body()).get("impact");
            assertEquals(new BigDecimal("1200.5"), impact.get("confirmed").decimalValue());
            assertEquals(new BigDecimal("1000.25"), impact.get("outstanding").decimalValue(), "confirmed - recovered");
            assertEquals("EUR", impact.get("currency").asText(), "the currency code is normalised to upper case");
            assertEquals("rated vs billed CDRs", impact.get("basis").asText());
            String stored = V1Body.of(r.body()).get("attributes").get("impact").asText();
            assertFalse(stored.contains("outstanding"), "outstanding is never stored: " + stored);

            var after = TestOpsEngine.of(c.svc).get(id).orElseThrow();
            assertEquals("", after.attributes().get("impact"));
            assertFalse(after.toMap().containsKey("impact"), "a cleared impact reads as none");

            List<Event> audits = seen.stream()
                    .filter(e -> EventType.OBJECT_ACTIVITY.equals(e.type()))
                    .filter(e -> "impact".equals(e.attributes().get("action")))
                    .toList();
            assertEquals(2, audits.size(), () -> "one audit event per impact write: " + seen);
            assertEquals("u-operations", audits.get(0).attributes().get("actor"));
            assertEquals("", audits.get(0).attributes().get("before"));
            assertEquals(stored, audits.get(0).attributes().get("after"));
            assertEquals(stored, audits.get(1).attributes().get("before"), "the clear records what it cleared");
        }
    }

    @Test
    void analyticsSumsTheTypedImpactPerCurrencyNeverAcrossThem(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String a = open(c, ObjectType.INCIDENT);
            String b = open(c, ObjectType.INCIDENT);
            String d = open(c, ObjectType.INCIDENT);
            assertEquals(200, send(c.port, "/objects/" + a + "/impact",
                    "{\"impact\":{\"confirmed\":\"100\",\"recovered\":\"40\",\"currency\":\"EUR\"}}", "operations").statusCode());
            assertEquals(200, send(c.port, "/objects/" + b + "/impact",
                    "{\"impact\":{\"confirmed\":\"50.5\",\"prevented\":\"10\",\"currency\":\"EUR\"}}", "operations").statusCode());
            assertEquals(200, send(c.port, "/objects/" + d + "/impact",
                    "{\"impact\":{\"suspected\":\"7\",\"currency\":\"USD\"}}", "operations").statusCode());

            HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create(
                    "http://localhost:" + c.port + "/api/v1/objects/analytics?type=INCIDENT"))
                    .header("Authorization", "Bearer operations").GET().build(), BodyHandlers.ofString());
            assertEquals(200, r.statusCode(), r.body());
            JsonNode byCurrency = V1Body.of(r.body()).get("impact").get("byCurrency");
            JsonNode eur = byCurrency.get("EUR");
            assertEquals(2, eur.get("count").asInt());
            assertEquals(0, new BigDecimal("150.5").compareTo(eur.get("confirmed").decimalValue()));
            assertEquals(0, new BigDecimal("40").compareTo(eur.get("recovered").decimalValue()));
            assertEquals(0, new BigDecimal("110.5").compareTo(eur.get("outstanding").decimalValue()));
            assertEquals(0, new BigDecimal("10").compareTo(eur.get("prevented").decimalValue()));
            assertEquals(0, new BigDecimal("7").compareTo(byCurrency.get("USD").get("suspected").decimalValue()));
            assertEquals(0, BigDecimal.ZERO.compareTo(byCurrency.get("USD").get("outstanding").decimalValue()),
                    "nothing confirmed, nothing outstanding");
        }
    }

    private HttpResponse<String> send(int port, String path, String body, String bearer) throws Exception {
        return send(port, "PUT", path, body, bearer);
    }

    private HttpResponse<String> send(int port, String method, String path, String body, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        return client.send(b.build(), BodyHandlers.ofString());
    }
}
