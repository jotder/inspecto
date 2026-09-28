package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.event.EventLog;
import com.gamma.exchange.Exchange;
import com.gamma.exchange.ExchangeSignalForwarder;
import com.gamma.exchange.ShareGrant;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.SpaceId;
import com.gamma.service.SpaceManager;
import com.gamma.signal.Ref;
import com.gamma.signal.Severity;
import com.gamma.signal.Signal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The cross-Space consequence that was asked for — D10 (operator 2026-09-28), the acceptance test of slice 4
 * ({@code docs/superpower/cross-space-consequence-design.md}; N9, N13): an OpCo Space's {@code fraud.alert}
 * Signal, offered to the Group hub Space, makes a hub Job open a hub Incident carrying only
 * {@code {caseId, typology, impact}} — never the MSISDN.
 *
 * <p>Everything a person does goes over real HTTP with an armed per-Space-roles authenticator; {@code hub}
 * sorts first, so it is also the Space un-prefixed routes bind to (asserted). The origin Signal is emitted on
 * opco's own ledger, as any in-Space emitter writes it. The Incident is a REAL one ({@code inspecto-ops}).
 */
class CrossSpaceFraudIncidentAcceptanceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private static final Authenticator PER_SPACE_ROLES = ex -> {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if (h == null || !h.startsWith("Bearer ")) return Optional.empty();
        String role = h.substring(7);
        Roles.Def def = Roles.effective(ex).get(role);
        return Optional.of(new Subject("user-" + role, def == null ? Set.of() : def.capabilities()));
    };

    private record Ctx(SpaceManager spaces, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }

    @AfterEach
    void restoreAuthenticator() {
        Authenticators.forTest(null);
    }

    private static final String HUB_JOB = """
            {"name":"group_fraud_incident","type":"incident.open","on_signal":"exchange.opco.fraud.alert",
             "title":"Group fraud case","fields":"caseId,typology,impact,msisdn",
             "bind":{"title":"$signal.typology","dedupe_key":"$signal.caseId"}}""";

    @Test
    void anOpcoFraudAlertOpensAHubIncidentCarryingOnlyTheAllowlistedFields(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            consent(c);
            assertEquals(200, post(c, "/spaces/hub/jobs", HUB_JOB, "builder").statusCode());

            emitFraudAlert(c, "C-17");
            assertTrue(recorded(c, ExchangeSignalForwarder.DELIVERED), "delivery recorded on opco");

            JsonNode incident = awaitIncident(c, "hub");
            JsonNode attrs = incident.path("attributes");
            assertEquals("C-17", attrs.path("caseId").asText());
            assertEquals("sim-box", attrs.path("typology").asText());
            assertEquals("4200", attrs.path("impact").asText());
            assertTrue(attrs.path("msisdn").isMissingNode(),
                    "the hub Job even names msisdn, but it never crossed the boundary: " + attrs);
            assertFalse(incident.toString().contains("+15550100"), "the MSISDN appears nowhere on the hub Incident");
            assertEquals("sim-box", incident.path("title").asText(), "the hub Job's own binding");
            assertEquals(List.of(), incidents(c, "opco"), "N13: the consequence lands in hub, not in opco");
            assertEquals(1, incidents(c, "hub").size());
        }
    }

    @Test
    void aHubJobOnTheBareOriginTypeDoesNotFire(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            consent(c);
            assertEquals(200, post(c, "/spaces/hub/jobs", HUB_JOB
                    .replace("group_fraud_incident", "bare_type").replace("exchange.opco.fraud.alert", "fraud.*"), "builder")
                    .statusCode());
            assertEquals(200, post(c, "/spaces/hub/jobs", HUB_JOB, "builder").statusCode());
            emitFraudAlert(c, "C-18");
            awaitIncident(c, "hub");
            Thread.sleep(500);
            assertEquals(1, incidents(c, "hub").size(), "N9: only the Job on exchange.opco.* fired, not the one on fraud.*");
        }
    }

    @Test
    void withoutTheOwnersApprovalNothingHappensInHub(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            offer(c);
            assertEquals(200, post(c, "/exchange/requests", REQUEST, "analyst").statusCode());
            assertEquals(200, post(c, "/spaces/hub/jobs", HUB_JOB, "builder").statusCode());
            emitFraudAlert(c, "C-19");
            assertTrue(recorded(c, ExchangeSignalForwarder.UNDELIVERABLE), "undeliverable recorded on opco");
            Thread.sleep(1500);
            assertEquals(List.of(), incidents(c, "hub"), "the hub's request alone is not consent");
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static final String REQUEST = "{\"kind\":\"signal\",\"item\":\"fraud.alert\",\"owner\":\"opco\",\"consumer\":\"hub\"}";

    private void offer(Ctx c) throws Exception {
        HttpResponse<String> r = post(c, "/exchange/signal-offers", "{\"owner\":\"opco\",\"item\":\"fraud.alert\","
                + "\"payloadKeys\":[\"caseId\",\"typology\",\"impact\"]}", "opsadmin");
        assertEquals(200, r.statusCode(), r.body());
    }

    private void consent(Ctx c) throws Exception {
        offer(c);
        assertEquals(200, post(c, "/exchange/requests", REQUEST, "analyst").statusCode());
        String id = ShareGrant.idFor(Exchange.SIGNAL, "fraud.alert", "opco", "hub");
        assertEquals(200, post(c, "/exchange/grants/" + id + "/approve", "", "opsadmin").statusCode());
    }

    private static void emitFraudAlert(Ctx c, String caseId) {
        EventLog opco = c.spaces().space(SpaceId.of("opco")).orElseThrow().service().eventLog();
        opco.emit(new Signal(null, "fraud.alert", Instant.now(), Severity.WARN, Ref.of("decision-rule", "fraud_rule"),
                null, null, null, "opco", Ref.of("user", "analyst-7"), "fraud.alert",
                Map.of("caseId", caseId, "typology", "sim-box", "impact", 4200, "msisdn", "+15550100"), 1).toEvent());
    }

    private static boolean recorded(Ctx c, String type) {
        return c.spaces().space(SpaceId.of("opco")).orElseThrow().service().eventLog().store().recent(200).stream()
                .anyMatch(e -> type.equals(e.attributes().get(Signal.ATTR_TYPE)));
    }

    private JsonNode awaitIncident(Ctx c, String space) throws Exception {
        long until = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < until) {
            List<JsonNode> got = incidents(c, space);
            if (!got.isEmpty()) return got.getFirst();
            Thread.sleep(100);
        }
        fail("no Incident opened in " + space);
        return null;
    }

    private List<JsonNode> incidents(Ctx c, String space) throws Exception {
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port
                + "/api/v1/spaces/" + space + "/objects?type=INCIDENT")).header("Authorization", "Bearer builder")
                .GET().build(), BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        List<JsonNode> out = new ArrayList<>();
        JSON.readTree(r.body()).path("data").forEach(out::add);
        return out;
    }

    private Ctx open(Path root) throws Exception {
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        Ctx c = new Ctx(spaces, api, api.port());
        for (String s : List.of("hub", "opco"))
            assertEquals(200, post(c, "/spaces", "{\"id\":\"" + s + "\"}", null).statusCode());
        assertEquals("hub", spaces.current().id().value(), "hub must be the Space un-prefixed routes bind to");
        Roles.write(configOf(c, "hub"), Map.of(
                "analyst", new Roles.Def(Set.of(Roles.CAN_REQUEST_SHARES), null),
                "builder", new Roles.Def(Set.of(Roles.CAN_AUTHOR_WORKBENCH, Roles.CAN_OPERATE_RUNS), null)), List.of());
        Roles.write(configOf(c, "opco"), Map.of(
                "opsadmin", new Roles.Def(Set.of(Roles.CAN_OFFER_SIGNALS, Roles.CAN_APPROVE_SHARES), null),
                "builder", new Roles.Def(Set.of(Roles.CAN_AUTHOR_WORKBENCH), null)), List.of());
        Authenticators.forTest(PER_SPACE_ROLES);
        return c;
    }

    private static Path configOf(Ctx c, String space) {
        return c.spaces().space(SpaceId.of(space)).orElseThrow().root().config();
    }

    private HttpResponse<String> post(Ctx c, String path, String body, String role) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path));
        if (role != null) b.header("Authorization", "Bearer " + role);
        b.header("Content-Type", "application/json").POST(BodyPublishers.ofString(body));
        return client.send(b.build(), BodyHandlers.ofString());
    }
}
