package com.gamma.control;

import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
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
import org.slf4j.MDC;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-Space Signal delivery — slice 3 ({@code docs/archived-documents/plans-archive/cross-space-consequence-design.md} §5.5, tests
 * N3–N6, N10, N11, T4, T7). The consent handshake runs over real HTTP with an armed per-Space-roles
 * authenticator: {@code opco} offers {@code fraud.alert} allowlisting {@code {caseId, typology, impact}},
 * {@code hub} requests, {@code opco} approves. The origin Signal is then emitted on opco's own ledger, the way
 * any in-Space emitter writes it. {@code hub} sorts first, so it is the Space un-prefixed routes bind to.
 */
class ControlApiExchangeSignalDeliveryTest {

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

    private static final String DELIVERED_TYPE = "exchange.opco.fraud.alert";

    @Test
    void anApprovedGrantDeliversANamespacedSignalCarryingOnlyTheAllowlist(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            String grant = consent(c, "hub");
            Signal origin = emit(c, "opco", "fraud.alert", Map.of("caseId", "C-17", "typology", "sim-box",
                    "impact", 4200, "msisdn", "+15550100"), "corr-1");

            List<Event> got = signals(c, "hub", DELIVERED_TYPE);
            assertEquals(1, got.size(), "delivered exactly once");
            Signal s = Signal.fromEvent(got.getFirst());
            assertEquals("hub", s.space());
            assertEquals(Ref.of("exchange-grant", grant), s.actor(), "D6: the grant is the delivered Signal's actor");
            Map<String, Object> expected = new LinkedHashMap<>();
            expected.put("caseId", "C-17");
            expected.put("typology", "sim-box");
            expected.put("impact", 4200);
            expected.put("chainDepth", 1);
            assertEquals(expected.keySet(), s.payload().keySet(), "D5: only allowlisted keys cross — no MSISDN");
            assertEquals("C-17", s.payload().get("caseId"));
            assertEquals(1, ((Number) s.payload().get("chainDepth")).intValue());
            assertEquals(origin.signalId(), s.causationId());
            assertEquals("corr-1", s.correlationId());
            Map<String, String> attrs = got.getFirst().attributes();
            assertEquals("opco", attrs.get(ExchangeSignalForwarder.ATTR_ORIGIN_SPACE));
            assertEquals(origin.signalId(), attrs.get(ExchangeSignalForwarder.ATTR_ORIGIN_SIGNAL));
            assertEquals("user:analyst-7", attrs.get(ExchangeSignalForwarder.ATTR_ORIGIN_ACTOR), "provenance, not authority");
            assertFalse(got.getFirst().toString().contains("+15550100"), "the MSISDN appears nowhere on hub's event");

            assertEquals(1, signals(c, "opco", ExchangeSignalForwarder.DELIVERED).size(), "delivery recorded on the origin");
            assertTrue(signals(c, "risk", DELIVERED_TYPE).isEmpty(), "a Space with no grant receives nothing");
            assertTrue(signals(c, "hub", "fraud.alert").isEmpty(), "never delivered under the bare origin type (D4)");
        }
    }

    @Test
    void noGrantMeansNothingIsDeliveredOrRecorded(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            offer(c);
            emit(c, "opco", "fraud.alert", Map.of("caseId", "C-1"), null);
            assertTrue(signals(c, "hub", DELIVERED_TYPE).isEmpty());
            assertTrue(signals(c, "opco", ExchangeSignalForwarder.DELIVERED).isEmpty());
            assertTrue(signals(c, "opco", ExchangeSignalForwarder.UNDELIVERABLE).isEmpty());
        }
    }

    @Test
    void aRequestedButUnapprovedGrantDeliversNothing(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            offer(c);
            assertEquals(200, post(c, "/exchange/requests", request("hub"), "analyst").statusCode());
            emit(c, "opco", "fraud.alert", Map.of("caseId", "C-1"), null);
            assertTrue(signals(c, "hub", DELIVERED_TYPE).isEmpty(), "the owner has not consented");
            assertEquals("grant requested", reasonOf(c));
        }
    }

    @Test
    void aRevokedGrantIsUndeliverable(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            String grant = consent(c, "hub");
            assertEquals(200, post(c, "/exchange/grants/" + grant + "/revoke", "", "opsadmin").statusCode());
            emit(c, "opco", "fraud.alert", Map.of("caseId", "C-1"), null);
            assertTrue(signals(c, "hub", DELIVERED_TYPE).isEmpty(), "T9: status read at delivery time");
            assertEquals("grant revoked", reasonOf(c));
        }
    }

    @Test
    void anotherTypeFromTheSameOwnerIsNotDelivered(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            consent(c, "hub");
            emit(c, "opco", "fraud.other", Map.of("caseId", "C-1"), null);
            emit(c, "opco", "fraud.alert.extra", Map.of("caseId", "C-1"), null);
            assertTrue(signals(c, "hub", "exchange.opco.fraud.other").isEmpty());
            assertTrue(signals(c, "hub", "exchange.opco.fraud.alert.extra").isEmpty());
            assertTrue(signals(c, "opco", ExchangeSignalForwarder.DELIVERED).isEmpty());
        }
    }

    @Test
    void aConsumerNoLongerHostedIsUndeliverableAndTheEmitterIsUnaffected(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            consent(c, "risk");
            assertTrue(c.spaces().delete(SpaceId.of("risk"), false));
            assertDoesNotThrow(() -> emit(c, "opco", "fraud.alert", Map.of("caseId", "C-1"), null));
            assertEquals("consumer not hosted here", reasonOf(c), "D8: undeliverable, never queued");
            assertEquals(1, signals(c, "opco", "fraud.alert").size(), "the origin Signal itself is recorded");
        }
    }

    @Test
    void aSignalClaimingOriginInItsPayloadIsNotADelivery(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            consent(c, "hub");
            emit(c, "hub", "exchange.opco.fraud.alert", Map.of("originSpace", "opco", "caseId", "fake"), null);
            Event forged = signals(c, "hub", DELIVERED_TYPE).getFirst();
            assertNull(forged.attributes().get(ExchangeSignalForwarder.ATTR_ORIGIN_SPACE),
                    "T5: origin attributes are set only by the forwarder");
            assertNull(forged.attributes().get(ExchangeSignalForwarder.ATTR_GRANT));
            assertTrue(signals(c, "opco", ExchangeSignalForwarder.DELIVERED).isEmpty());
        }
    }

    @Test
    void manySignalsSharingOneCorrelationIdAreAllDelivered(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            consent(c, "hub");
            for (int i = 0; i < 20; i++)
                emit(c, "opco", "fraud.alert", Map.of("caseId", "C-" + i), "one-run");
            assertEquals(20, signals(c, "hub", DELIVERED_TYPE).size(), "one Run's 20 Signals are 20 deliveries");
            assertTrue(signals(c, "opco", ExchangeSignalForwarder.UNDELIVERABLE).isEmpty());
        }
    }

    @Test
    void oneSignalFansOutToEveryConsentedGrant(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            offer(c);
            List<String> consumers = new java.util.ArrayList<>();
            for (int i = 0; i < 10; i++) {
                String space = "x" + i;
                Authenticators.forTest(null);   // Space creation is not what is under test
                assertEquals(200, post(c, "/spaces", "{\"id\":\"" + space + "\"}", null).statusCode());
                Authenticators.forTest(PER_SPACE_ROLES);
                Roles.write(configOf(c, space), Map.of("analyst", new Roles.Def(Set.of(Roles.CAN_REQUEST_SHARES), null)), List.of());
                assertEquals(200, post(c, "/exchange/requests", request(space), "analyst").statusCode());
                assertEquals(200, post(c, "/exchange/grants/" + ShareGrant.idFor(Exchange.SIGNAL, "fraud.alert", "opco", space)
                        + "/approve", "", "opsadmin").statusCode());
                consumers.add(space);
            }
            emit(c, "opco", "fraud.alert", Map.of("caseId", "C-1"), "fan-1");
            for (String space : consumers) assertEquals(1, signals(c, space, DELIVERED_TYPE).size(), space);
            assertEquals(10, signals(c, "opco", ExchangeSignalForwarder.DELIVERED).size());
        }
    }

    @Test
    void aChainAtTheMaxDepthIsUndeliverable(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            consent(c, "hub");
            int max = Integer.getInteger("jobs.signal.maxChainDepth", 8);
            emit(c, "opco", "fraud.alert", Map.of("caseId", "C-1", "chainDepth", max - 1), "deep");
            assertEquals(max, ((Number) Signal.fromEvent(signals(c, "hub", DELIVERED_TYPE).getFirst())
                    .payload().get("chainDepth")).intValue(), "one cross-Space hop deeper");
            emit(c, "opco", "fraud.alert", Map.of("caseId", "C-2", "chainDepth", max), "deeper");
            assertEquals(1, signals(c, "hub", DELIVERED_TYPE).size(), "the hop past the cap is not delivered");
            assertEquals("chain depth " + max + " reached", reasonOf(c));
        }
    }

    @Test
    void deliveryBindsTheConsumersSpaceAndRestoresTheCallers(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            consent(c, "hub");
            List<String> seenMdc = new java.util.concurrent.CopyOnWriteArrayList<>();
            EventLog hub = log(c, "hub");
            java.util.function.Consumer<Event> probe = e -> {
                if (EventType.SIGNAL.equals(e.type())) seenMdc.add(String.valueOf(MDC.get(EventLog.SPACE_MDC_KEY)));
            };
            hub.addSubscriber(probe);
            try {
                MDC.put(EventLog.SPACE_MDC_KEY, "opco");
                emit(c, "opco", "fraud.alert", Map.of("caseId", "C-1"), null);
                assertEquals("opco", MDC.get(EventLog.SPACE_MDC_KEY), "the emitter's MDC is restored");
            } finally {
                MDC.remove(EventLog.SPACE_MDC_KEY);
                hub.removeSubscriber(probe);
            }
            assertEquals(List.of("hub"), seenMdc, "T7: hub's subscribers run under hub's Space");
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private String consent(Ctx c, String consumer) throws Exception {
        offer(c);
        HttpResponse<String> req = post(c, "/exchange/requests", request(consumer), "analyst");
        assertEquals(200, req.statusCode(), req.body());
        String id = ShareGrant.idFor(Exchange.SIGNAL, "fraud.alert", "opco", consumer);
        HttpResponse<String> ok = post(c, "/exchange/grants/" + id + "/approve", "", "opsadmin");
        assertEquals(200, ok.statusCode(), ok.body());
        return id;
    }

    private void offer(Ctx c) throws Exception {
        HttpResponse<String> r = post(c, "/exchange/signal-offers", "{\"owner\":\"opco\",\"item\":\"fraud.alert\","
                + "\"payloadKeys\":[\"caseId\",\"typology\",\"impact\"]}", "opsadmin");
        assertEquals(200, r.statusCode(), r.body());
    }

    private static String request(String consumer) {
        return "{\"kind\":\"signal\",\"item\":\"fraud.alert\",\"owner\":\"opco\",\"consumer\":\"" + consumer + "\"}";
    }

    private static Signal emit(Ctx c, String space, String type, Map<String, Object> payload, String cid) {
        Signal s = new Signal(null, type, Instant.now(), Severity.WARN, Ref.of("decision-rule", "fraud_rule"), null,
                cid, null, space, Ref.of("user", "analyst-7"), type, payload, 1);
        log(c, space).emit(s.toEvent());
        return s;
    }

    private static String reasonOf(Ctx c) {
        List<Event> u = signals(c, "opco", ExchangeSignalForwarder.UNDELIVERABLE);
        assertFalse(u.isEmpty(), "an undeliverable record on the origin");
        return String.valueOf(Signal.fromEvent(u.getFirst()).payload().get("reason"));
    }

    private static EventLog log(Ctx c, String space) {
        return c.spaces().space(SpaceId.of(space)).orElseThrow().service().eventLog();
    }

    private static List<Event> signals(Ctx c, String space, String type) {
        return log(c, space).store().recent(1000).stream()
                .filter(e -> EventType.SIGNAL.equals(e.type()) && type.equals(e.attributes().get(Signal.ATTR_TYPE)))
                .toList();
    }

    private Ctx open(Path root) throws Exception {
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        Ctx c = new Ctx(spaces, api, api.port());
        for (String s : List.of("hub", "opco", "risk"))
            assertEquals(200, post(c, "/spaces", "{\"id\":\"" + s + "\"}", null).statusCode());
        assertEquals("hub", spaces.current().id().value(), "hub must be the Space un-prefixed routes bind to");
        Roles.write(configOf(c, "hub"), Map.of("analyst", new Roles.Def(Set.of(Roles.CAN_REQUEST_SHARES), null)), List.of());
        Roles.write(configOf(c, "risk"), Map.of("analyst", new Roles.Def(Set.of(Roles.CAN_REQUEST_SHARES), null)), List.of());
        Roles.write(configOf(c, "opco"), Map.of("opsadmin",
                new Roles.Def(Set.of(Roles.CAN_OFFER_SIGNALS, Roles.CAN_APPROVE_SHARES), null)), List.of());
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
