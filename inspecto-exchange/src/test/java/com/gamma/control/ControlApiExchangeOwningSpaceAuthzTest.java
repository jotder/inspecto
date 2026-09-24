package com.gamma.control;

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

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Exchange's capability gates are decided by the role table of the Space that OWNS the thing acted on
 * — not by the Space the un-prefixed {@code /exchange/*} request happens to bind to
 * ({@code EXCHANGE-OWNING-SPACE-AUTHZ-1}, reproduced 2026-09-24).
 *
 * <p>The Exchange routes carry no {@code /spaces/{id}} prefix, so {@code ControlApi.authenticate} hands the
 * {@link Authenticator} the DEFAULT (first-hosted) Space's config root and the {@link Subject} carries THAT
 * Space's grants. Until the fix, {@code POST /exchange/grants/{id}/approve} on a grant owned by Space B was
 * decided by Space A's {@code roles.toon}: a steward of A approved B's grants, and B's own approver was
 * refused. Every case here is a pair — a caller whose capability lives only in the wrong Space (403, and the
 * grant is untouched) and its positive twin whose capability lives in the owning Space (200).
 *
 * <p>⚠ A real {@link Subject} is attached on every request: with none, {@code requireCapability} is a
 * no-op and every one of these would pass against an ungated route. The authenticator mirrors
 * {@code OidcAuthenticator}: the bearer token names a role, and its grants come from
 * {@link Roles#effective(com.sun.net.httpserver.HttpExchange)} — the per-request, per-Space table.
 */
class ControlApiExchangeOwningSpaceAuthzTest {

    private final HttpClient client = HttpClient.newHttpClient();

    /** Role (the bearer token) → grants from the role table of whichever Space's config root was stamped. */
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

    /**
     * {@code hub} is hosted first, so it is the Space every un-prefixed request binds to. {@code finance} owns
     * the Datasets, {@code audit} consumes them. {@code steward} holds every Exchange capability in hub
     * ONLY; {@code financier} holds the owner capabilities in finance ONLY; {@code auditor} holds
     * canRequestShares in audit ONLY. Returns the id of an open (requested) grant finance→audit.
     */
    private String arrange(Ctx c) throws Exception {
        for (String s : List.of("hub", "finance", "audit"))
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"" + s + "\"}", null).statusCode());
        for (String ds : List.of("tax_receipts", "payroll", "ledger"))
            assertEquals(200, send(c.port, "POST", "/spaces/finance/components/dataset",
                    "{\"id\":\"" + ds + "\",\"physicalRef\":\"" + ds + "\"}", null).statusCode());
        for (String ds : List.of("tax_receipts", "payroll"))
            assertEquals(200, send(c.port, "POST", "/exchange/offers",
                    "{\"kind\":\"dataset\",\"item\":\"" + ds + "\",\"owner\":\"finance\"}", null).statusCode());
        HttpResponse<String> req = send(c.port, "POST", "/exchange/requests",
                "{\"kind\":\"dataset\",\"item\":\"tax_receipts\",\"owner\":\"finance\",\"consumer\":\"audit\"}", null);
        assertEquals(200, req.statusCode(), req.body());

        Roles.write(configOf(c, "hub"), Map.of("steward", new Roles.Def(Set.of(Roles.CAN_APPROVE_SHARES,
                Roles.CAN_OFFER_DATASETS, Roles.CAN_REQUEST_SHARES), null)), List.of());
        Roles.write(configOf(c, "finance"), Map.of("financier", new Roles.Def(Set.of(Roles.CAN_APPROVE_SHARES,
                Roles.CAN_OFFER_DATASETS), null)), List.of());
        Roles.write(configOf(c, "audit"), Map.of("auditor",
                new Roles.Def(Set.of(Roles.CAN_REQUEST_SHARES), null)), List.of());
        Authenticators.forTest(PER_SPACE_ROLES);
        return ShareGrant.idFor("dataset", "tax_receipts", "finance", "audit");
    }

    @Test
    void approveAndRevokeAreDecidedByTheOwningSpacesRoles(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            String id = arrange(c);

            HttpResponse<String> foreign = send(c.port, "POST", "/exchange/grants/" + id + "/approve", "", "steward");
            assertEquals(403, foreign.statusCode(), "a hub steward must not approve finance's grant: " + foreign.body());
            assertTrue(foreign.body().contains("PERMISSION_DENIED"), foreign.body());
            assertEquals(ShareGrant.REQUESTED, status(c, id), "a refused approve must not have activated the grant");

            HttpResponse<String> owner = send(c.port, "POST", "/exchange/grants/" + id + "/approve", "", "financier");
            assertEquals(200, owner.statusCode(), "finance's own approver must approve: " + owner.body());
            assertEquals(ShareGrant.ACTIVE, status(c, id));

            assertEquals(403, send(c.port, "POST", "/exchange/grants/" + id + "/expiry",
                    "{\"expiresAt\":4102444800000}", "steward").statusCode());
            assertNull(grant(c, id).expiresAt(), "a refused expiry must not have been written");
            assertEquals(200, send(c.port, "POST", "/exchange/grants/" + id + "/expiry",
                    "{\"expiresAt\":4102444800000}", "financier").statusCode());

            assertEquals(403, send(c.port, "POST", "/exchange/grants/" + id + "/revoke", "", "steward").statusCode());
            assertEquals(ShareGrant.ACTIVE, status(c, id), "a refused revoke must leave the grant active");
            assertEquals(200, send(c.port, "POST", "/exchange/grants/" + id + "/revoke", "", "financier").statusCode());
            assertEquals(ShareGrant.REVOKED, status(c, id));
        }
    }

    @Test
    void denyIsDecidedByTheOwningSpacesRoles(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            String id = arrange(c);
            assertEquals(403, send(c.port, "POST", "/exchange/grants/" + id + "/deny", "", "steward").statusCode());
            assertEquals(ShareGrant.REQUESTED, status(c, id));
            assertEquals(200, send(c.port, "POST", "/exchange/grants/" + id + "/deny", "", "financier").statusCode());
            assertEquals(ShareGrant.DENIED, status(c, id));
        }
    }

    @Test
    void requestAndPinAreDecidedByTheConsumerSpacesRoles(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            arrange(c);
            String body = "{\"kind\":\"dataset\",\"item\":\"payroll\",\"owner\":\"finance\",\"consumer\":\"audit\"}";
            String id = ShareGrant.idFor("dataset", "payroll", "finance", "audit");

            assertEquals(403, send(c.port, "POST", "/exchange/requests", body, "steward").statusCode());
            assertTrue(grantOpt(c, id).isEmpty(), "a refused request must not have opened a grant");
            HttpResponse<String> ok = send(c.port, "POST", "/exchange/requests", body, "auditor");
            assertEquals(200, ok.statusCode(), ok.body());

            assertEquals(403, send(c.port, "POST", "/exchange/grants/" + id + "/pin",
                    "{\"version\":\"v1\"}", "steward").statusCode());
            assertNull(grant(c, id).pin(), "a refused pin must not have been written");
            assertEquals(200, send(c.port, "POST", "/exchange/grants/" + id + "/pin",
                    "{\"version\":\"v1\"}", "auditor").statusCode());
        }
    }

    @Test
    void offerIsDecidedByTheOwningSpacesRoles(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            arrange(c);
            String body = "{\"kind\":\"dataset\",\"item\":\"ledger\",\"owner\":\"finance\"}";
            Exchange ex = Exchange.under(c.spaces().containerRoot());

            assertEquals(403, send(c.port, "POST", "/exchange/offers", body, "steward").statusCode());
            assertTrue(ex.offer("finance", "dataset", "ledger").isEmpty(), "a refused offer must not be listed");
            assertEquals(200, send(c.port, "POST", "/exchange/offers", body, "financier").statusCode());
            assertTrue(ex.offer("finance", "dataset", "ledger").isPresent());

            // refresh republishes finance's data into the Exchange — the same owner decision. Its positive
            // twin needs real Parquet, so here it only has to get PAST the gate (any status but 403).
            String refresh = "{\"item\":\"tax_receipts\",\"owner\":\"finance\"}";
            assertEquals(403, send(c.port, "POST", "/exchange/refresh", refresh, "steward").statusCode());
            assertNotEquals(403, send(c.port, "POST", "/exchange/refresh", refresh, "financier").statusCode());
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private Ctx open(Path root) throws Exception {
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        return new Ctx(spaces, api, api.port());
    }

    private static Path configOf(Ctx c, String space) {
        return c.spaces().space(SpaceId.of(space)).orElseThrow().root().config();
    }

    private static Optional<ShareGrant> grantOpt(Ctx c, String id) {
        return Exchange.under(c.spaces().containerRoot()).grant(id);
    }

    private static ShareGrant grant(Ctx c, String id) {
        return grantOpt(c, id).orElseThrow();
    }

    private static String status(Ctx c, String id) {
        return grant(c, id).status();
    }

    private HttpResponse<String> send(int port, String method, String path, String body, String role) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (role != null) b.header("Authorization", "Bearer " + role);
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }
}
