package com.gamma.control;

import com.gamma.audit.Event;
import com.gamma.audit.EventLog;
import com.gamma.audit.EventType;
import com.gamma.exchange.Exchange;
import com.gamma.exchange.ExchangeSignalForwarder;
import com.gamma.exchange.ShareGrant;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.SpaceId;
import com.gamma.service.SpaceManager;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-Space consequence slice 5 ({@code docs/archived-documents/plans-archive/cross-space-consequence-design.md} §6; operator
 * 2026-09-28: the emitter is a Decision Rule {@code emit-signal} payload, not a named Collector). An opco
 * Decision Rule's {@code emit-signal} names the Space it offers to ({@code offerTo}), the Signal type, and maps
 * the applied record's fields onto the payload. It passes the manual offer's {@code canOfferSignals} gate in
 * the origin Space, needs an ACTIVE Exchange grant, and is delivered (allowlist, depth, audit) by the same
 * forwarder. Every request carries an armed per-Space-roles Subject, so a refusal is the gate's, not a no-op's.
 */
class ControlApiDecisionRuleSignalOfferTest {

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

    private static final String DELIVERED = "exchange.opco.fraud.alert";
    private static final String RECORD = "{\"record\":{\"case_id\":\"C-17\",\"typ\":\"sim-box\",\"amt\":4200,"
            + "\"msisdn\":\"+15550100\"}}";

    private static String offerRule(String name, String to) {
        return "{\"name\":\"" + name + "\",\"targetType\":\"pipeline\",\"target\":\"cdr\",\"consequences\":["
                + "{\"action\":\"emit-signal\",\"params\":{\"type\":\"fraud.alert\",\"offerTo\":\"" + to + "\","
                + "\"payload\":{\"caseId\":\"case_id\",\"typology\":\"typ\",\"impact\":\"amt\",\"msisdn\":\"msisdn\"}}}]}";
    }

    @Test
    void anAppliedRuleOffersItsMappedSignalToTheNamedSpaceOnly(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            consent(c, "hub");
            consent(c, "risk");
            assertEquals(200, post(c, "/spaces/opco/decision-rules", offerRule("to_hub", "hub"), "opsadmin").statusCode());
            HttpResponse<String> r = post(c, "/spaces/opco/decision-rules/to_hub/apply", RECORD, "opsadmin");
            assertEquals(200, r.statusCode(), r.body());

            Signal origin = Signal.fromEvent(signals(c, "opco", "fraud.alert").getFirst());
            assertEquals("C-17", origin.payload().get("caseId"), "the record field is mapped onto the payload");
            assertEquals("to_hub", origin.payload().get("rule"));

            List<Event> got = signals(c, "hub", DELIVERED);
            assertEquals(1, got.size(), "delivered to the named Space");
            Signal s = Signal.fromEvent(got.getFirst());
            assertEquals(Set.of("caseId", "typology", "impact", "chainDepth"), s.payload().keySet(),
                    "the offer's allowlist still applies — no MSISDN crosses");
            assertEquals(1, ((Number) s.payload().get("chainDepth")).intValue(), "same depth accounting as any offer");
            assertEquals("opco", got.getFirst().attributes().get(ExchangeSignalForwarder.ATTR_ORIGIN_SPACE));
            assertTrue(signals(c, "risk", DELIVERED).isEmpty(), "a Space the rule did not name receives nothing");
            assertEquals(1, signals(c, "opco", ExchangeSignalForwarder.DELIVERED).size(), "delivery audited on the origin");
        }
    }

    /** The negative: the SAME rule and record that the test above delivers, applied by a caller who may run the
     *  rule but lacks {@code canOfferSignals} in opco. Mutation-checked: remove the gate and this goes red. */
    @Test
    void aCallerWithoutCanOfferSignalsIsRefusedAndNothingIsEmitted(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            consent(c, "hub");
            assertEquals(200, post(c, "/spaces/opco/decision-rules", offerRule("to_hub", "hub"), "opsadmin").statusCode());
            HttpResponse<String> r = post(c, "/spaces/opco/decision-rules/to_hub/apply", RECORD, "operator");
            assertEquals(403, r.statusCode(), r.body());
            assertTrue(r.body().contains("canOfferSignals"), r.body());
            assertTrue(signals(c, "opco", "fraud.alert").isEmpty(), "nothing emitted on the origin");
            assertTrue(signals(c, "hub", DELIVERED).isEmpty(), "hub's ledger unchanged");
        }
    }

    @Test
    void noGrantAndNoSuchSpaceAnswerAlike(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            offer(c);
            for (String to : List.of("risk", "ghost")) {
                assertEquals(200, post(c, "/spaces/opco/decision-rules", offerRule("to_" + to, to), "opsadmin").statusCode());
                HttpResponse<String> r = post(c, "/spaces/opco/decision-rules/to_" + to + "/apply", RECORD, "opsadmin");
                assertEquals(422, r.statusCode(), to + ": " + r.body());
                assertTrue(r.body().contains("no ACTIVE Exchange signal grant"), r.body());
            }
            assertTrue(signals(c, "opco", "fraud.alert").isEmpty(), "fail closed: nothing emitted");
        }
    }

    @Test
    void aMappedFieldTheRecordLacksIs422BeforeAnythingRuns(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            consent(c, "hub");
            assertEquals(200, post(c, "/spaces/opco/decision-rules", offerRule("to_hub", "hub"), "opsadmin").statusCode());
            HttpResponse<String> r = post(c, "/spaces/opco/decision-rules/to_hub/apply",
                    "{\"record\":{\"case_id\":\"C-1\"}}", "opsadmin");
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(signals(c, "opco", "fraud.alert").isEmpty());
        }
    }

    @Test
    void malformedEmitSignalConfigIsRefusedOnSave(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            Map<String, String> bad = Map.of(
                    "reserved_depth", "{\"type\":\"fraud.alert\",\"payload\":{\"chainDepth\":\"d\"}}",
                    "reserved_rule", "{\"type\":\"fraud.alert\",\"payload\":{\"rule\":\"r\"}}",
                    "bad_space", "{\"type\":\"fraud.alert\",\"offerTo\":\"Not A Space!\"}",
                    "no_type", "{\"offerTo\":\"hub\"}",
                    "unknown_param", "{\"type\":\"fraud.alert\",\"offerTo\":\"hub\",\"severity\":\"high\"}",
                    "non_string_field", "{\"type\":\"fraud.alert\",\"payload\":{\"caseId\":7}}",
                    "empty_payload", "{\"type\":\"fraud.alert\",\"payload\":{}}");
            for (Map.Entry<String, String> b : bad.entrySet()) {
                HttpResponse<String> r = post(c, "/spaces/opco/decision-rules", "{\"name\":\"" + b.getKey()
                        + "\",\"consequences\":[{\"action\":\"emit-signal\",\"params\":" + b.getValue() + "}]}", "opsadmin");
                assertEquals(422, r.statusCode(), b.getKey() + ": " + r.body());
                assertTrue(r.body().contains("emit-signal"), r.body());
            }
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private void consent(Ctx c, String consumer) throws Exception {
        offer(c);
        HttpResponse<String> req = post(c, "/exchange/requests", "{\"kind\":\"signal\",\"item\":\"fraud.alert\","
                + "\"owner\":\"opco\",\"consumer\":\"" + consumer + "\"}", "analyst");
        assertEquals(200, req.statusCode(), req.body());
        HttpResponse<String> ok = post(c, "/exchange/grants/"
                + ShareGrant.idFor(Exchange.SIGNAL, "fraud.alert", "opco", consumer) + "/approve", "", "opsadmin");
        assertEquals(200, ok.statusCode(), ok.body());
    }

    private void offer(Ctx c) throws Exception {
        HttpResponse<String> r = post(c, "/exchange/signal-offers", "{\"owner\":\"opco\",\"item\":\"fraud.alert\","
                + "\"payloadKeys\":[\"caseId\",\"typology\",\"impact\"]}", "opsadmin");
        assertEquals(200, r.statusCode(), r.body());
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
        for (String s : List.of("hub", "risk"))
            Roles.write(configOf(c, s), Map.of("analyst", new Roles.Def(Set.of(Roles.CAN_REQUEST_SHARES), null)), List.of());
        Roles.write(configOf(c, "opco"), Map.of(
                "opsadmin", new Roles.Def(Set.of(Roles.CAN_OFFER_SIGNALS, Roles.CAN_APPROVE_SHARES,
                        Roles.CAN_AUTHOR_WORKBENCH, Roles.CAN_OPERATE_RUNS), null),
                "operator", new Roles.Def(Set.of(Roles.CAN_AUTHOR_WORKBENCH, Roles.CAN_OPERATE_RUNS), null)), List.of());
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
