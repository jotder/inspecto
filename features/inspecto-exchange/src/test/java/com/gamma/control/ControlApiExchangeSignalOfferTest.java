package com.gamma.control;

import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.exchange.Exchange;
import com.gamma.exchange.ShareGrant;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.SpaceId;
import com.gamma.service.SpaceManager;
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
import com.gamma.access.Roles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Exchange's {@code signal} kind — cross-Space consequence slice 2
 * ({@code docs/archived-documents/plans-archive/cross-space-consequence-design.md} §6): an owner Space offers a Signal type with a
 * payload allowlist ({@code POST /exchange/signal-offers}, {@code canOfferSignals} in the OWNER, D7), a
 * consumer requests it ({@code canRequestShares} in the CONSUMER), the owner approves ({@code canApproveShares}
 * in the OWNER) — two-party consent (D2). No delivery at this slice.
 *
 * <p>Spaces: {@code hub} (sorts first, so un-prefixed routes bind to it — asserted) is the consumer,
 * {@code opco} the owner, {@code risk} a third Space. {@code steward} holds every Exchange capability in hub
 * ONLY; {@code opsadmin} holds the owner verbs in opco ONLY; {@code curator} holds only canOfferDatasets in
 * opco; {@code analyst} holds canRequestShares in hub ONLY. Each request carries a real Subject.
 */
class ControlApiExchangeSignalOfferTest {

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

    private static final String OFFER = "{\"owner\":\"opco\",\"item\":\"fraud.alert\",\"description\":\"confirmed fraud\","
            + "\"payloadKeys\":[\"caseId\",\"typology\",\"impact\"]}";
    private static final String REQUEST = "{\"kind\":\"signal\",\"item\":\"fraud.alert\",\"owner\":\"opco\",\"consumer\":\"hub\"}";
    private static final String GRANT = ShareGrant.idFor(Exchange.SIGNAL, "fraud.alert", "opco", "hub");

    @Test
    void offeringASignalIsDecidedByTheOwnersCanOfferSignals(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            HttpResponse<String> foreign = send(c, "/exchange/signal-offers", OFFER, "steward");
            assertEquals(403, foreign.statusCode(), "a hub steward must not offer opco's Signals: " + foreign.body());
            assertEquals(403, send(c, "/exchange/signal-offers", OFFER, "curator").statusCode(),
                    "canOfferDatasets is not the Signal offer verb (D7)");
            assertTrue(Exchange.under(c.spaces().containerRoot()).offer("opco", Exchange.SIGNAL, "fraud.alert").isEmpty());

            HttpResponse<String> ok = send(c, "/exchange/signal-offers", OFFER, "opsadmin");
            assertEquals(200, ok.statusCode(), ok.body());
            JsonNode offer = JSON.readTree(ok.body()).path("data");
            assertEquals("signal", offer.path("kind").asText());
            assertEquals(List.of("caseId", "typology", "impact"), strings(offer.path("payloadKeys")));

            JsonNode listed = JSON.readTree(get(c, "/exchange/offers?owner=opco").body()).path("data");
            assertTrue(listed.toString().contains("\"fraud.alert\""), listed.toString());
        }
    }

    @Test
    void theAllowlistIsEmptyByDefault(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            HttpResponse<String> ok = send(c, "/exchange/signal-offers",
                    "{\"owner\":\"opco\",\"item\":\"fraud.alert\"}", "opsadmin");
            assertEquals(200, ok.statusCode(), ok.body());
            assertEquals(List.of(), strings(JSON.readTree(ok.body()).path("data").path("payloadKeys")));
            assertEquals(List.of(), Exchange.under(c.spaces().containerRoot())
                    .offer("opco", Exchange.SIGNAL, "fraud.alert").orElseThrow().payloadKeys());
        }
    }

    @Test
    void malformedSignalOffersAreRefused(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(400, send(c, "/exchange/signal-offers", "{\"owner\":\"opco\",\"item\":\"Fraud Alert\"}", "opsadmin").statusCode());
            assertEquals(400, send(c, "/exchange/signal-offers",
                    "{\"owner\":\"opco\",\"item\":\"fraud.alert\",\"payloadKeys\":\"caseId\"}", "opsadmin").statusCode());
            assertEquals(400, send(c, "/exchange/signal-offers",
                    "{\"owner\":\"opco\",\"item\":\"fraud.alert\",\"payloadKeys\":[\"a b\"]}", "opsadmin").statusCode());
            assertEquals(422, send(c, "/exchange/signal-offers",
                    "{\"owner\":\"opco\",\"item\":\"exchange.risk.fraud.alert\"}", "opsadmin").statusCode(),
                    "a delivered Signal is never re-offered");
            assertEquals(400, send(c, "/exchange/offers",
                    "{\"kind\":\"signal\",\"owner\":\"opco\",\"item\":\"fraud.alert\"}", "curator").statusCode(),
                    "the Dataset offer route does not take the signal kind");
        }
    }

    @Test
    void aSignalGrantNeedsTheConsumerToRequestAndTheOwnerToApprove(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c, "/exchange/signal-offers", OFFER, "opsadmin").statusCode());

            assertEquals(403, send(c, "/exchange/requests", REQUEST, "opsadmin").statusCode(),
                    "opco's admin holds nothing in hub, the consumer");
            HttpResponse<String> req = send(c, "/exchange/requests", REQUEST, "analyst");
            assertEquals(200, req.statusCode(), req.body());
            JsonNode g = JSON.readTree(req.body()).path("data");
            assertEquals(ShareGrant.REQUESTED, g.path("status").asText());
            assertEquals(ShareGrant.LIVE, g.path("mode").asText(), "a Signal grant is live-mode only");

            assertEquals(403, send(c, "/exchange/grants/" + GRANT + "/approve", "", "steward").statusCode(),
                    "the consumer's own steward cannot approve its own request — the owner consents");
            assertEquals(ShareGrant.REQUESTED, status(c));
            assertEquals(200, send(c, "/exchange/grants/" + GRANT + "/approve", "", "opsadmin").statusCode());
            assertEquals(ShareGrant.ACTIVE, status(c));
            assertEquals(200, send(c, "/exchange/grants/" + GRANT + "/revoke", "", "opsadmin").statusCode());
            assertEquals(ShareGrant.REVOKED, status(c));
        }
    }

    @Test
    void aSnapshotModeSignalRequestIs422(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c, "/exchange/signal-offers", OFFER, "opsadmin").statusCode());
            assertEquals(422, send(c, "/exchange/requests",
                    REQUEST.replace("}", ",\"mode\":\"snapshot\"}"), "analyst").statusCode());
        }
    }

    /** D12: on the signal kind, "no such Space" and "not permitted" are indistinguishable. */
    @Test
    void aMissingSpaceAndAForbiddenSpaceAnswerAlike(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c, "/exchange/signal-offers", OFFER, "opsadmin").statusCode());

            HttpResponse<String> forbidden = send(c, "/exchange/requests", REQUEST.replace("\"hub\"", "\"risk\""), "analyst");
            HttpResponse<String> missing = send(c, "/exchange/requests", REQUEST.replace("\"hub\"", "\"nowhere\""), "analyst");
            assertSameRefusal(forbidden, missing);

            HttpResponse<String> forbiddenOwner = send(c, "/exchange/signal-offers", OFFER.replace("opco", "risk"), "opsadmin");
            HttpResponse<String> missingOwner = send(c, "/exchange/signal-offers", OFFER.replace("opco", "nowhere"), "opsadmin");
            assertSameRefusal(forbiddenOwner, missingOwner);

            HttpResponse<String> missingRequestOwner = send(c, "/exchange/requests",
                    REQUEST.replace("\"opco\"", "\"nowhere\""), "analyst");
            assertEquals(403, missingRequestOwner.statusCode(), "an unknown owner is not a 404 oracle either");
            assertEquals(error(forbidden), error(missingRequestOwner));
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static void assertSameRefusal(HttpResponse<String> forbidden, HttpResponse<String> missing) throws Exception {
        assertEquals(403, forbidden.statusCode(), forbidden.body());
        assertEquals(forbidden.statusCode(), missing.statusCode(), missing.body());
        assertEquals(error(forbidden), error(missing), "same code and message");
    }

    private static String error(HttpResponse<String> r) throws Exception {
        JsonNode err = JSON.readTree(r.body()).path("error");
        return err.path("errorCode").asText() + "|" + err.path("message").asText();
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new java.util.ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private Ctx open(Path root) throws Exception {
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        Ctx c = new Ctx(spaces, api, api.port());
        for (String s : List.of("hub", "opco", "risk"))
            assertEquals(200, send(c, "/spaces", "{\"id\":\"" + s + "\"}", null).statusCode());
        assertEquals("hub", spaces.current().id().value(), "hub must be the Space un-prefixed routes bind to");
        Roles.write(configOf(c, "hub"), Map.of(
                "steward", new Roles.Def(Set.of(Roles.CAN_OFFER_SIGNALS, Roles.CAN_OFFER_DATASETS,
                        Roles.CAN_APPROVE_SHARES, Roles.CAN_REQUEST_SHARES), null),
                "analyst", new Roles.Def(Set.of(Roles.CAN_REQUEST_SHARES), null)), List.of());
        Roles.write(configOf(c, "opco"), Map.of(
                "opsadmin", new Roles.Def(Set.of(Roles.CAN_OFFER_SIGNALS, Roles.CAN_APPROVE_SHARES), null),
                "curator", new Roles.Def(Set.of(Roles.CAN_OFFER_DATASETS), null)), List.of());
        Authenticators.forTest(PER_SPACE_ROLES);
        return c;
    }

    private static Path configOf(Ctx c, String space) {
        return c.spaces().space(SpaceId.of(space)).orElseThrow().root().config();
    }

    private static String status(Ctx c) {
        return Exchange.under(c.spaces().containerRoot()).grant(GRANT).orElseThrow().status();
    }

    private HttpResponse<String> send(Ctx c, String path, String body, String role) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path));
        if (role != null) b.header("Authorization", "Bearer " + role);
        b.header("Content-Type", "application/json").POST(BodyPublishers.ofString(body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> get(Ctx c, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Authorization", "Bearer steward").GET().build(), BodyHandlers.ofString());
    }
}
