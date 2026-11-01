package com.gamma.oidc;

import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Subject;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Machine-token trust (OIDC-CC-CLIENT-PIN-1, OIDC-CC-LIFETIME-REPLAY-1): the {@code auth.oidc.clients} allowlist,
 * the boot refusals that come with it, the lifetime ceiling and the {@code jti} replay refusal — and that the SPA
 * client's (human) tokens stay exactly as before. Review G1, G2, G4, G6 and the 5b note.
 */
class OidcMachineTrustTest {

    private static final String ISSUER = "https://issuer.example/realms/inspecto";
    private static final String AUDIENCE = "inspecto-api";
    private static final String SPA = "inspecto-spa";
    private static final String[] KEYS = {"auth.oidc.provider", "auth.oidc.discoveryUrl", "auth.oidc.issuer",
            "auth.oidc.jwksUri", "auth.oidc.audience", "auth.oidc.clientId", "auth.oidc.rolesClaim",
            "auth.oidc.gateway.issuer", "auth.oidc.clients", "auth.oidc.maxTokenLifetime", "auth.oidc.refuseReplay"};
    private static RSAKey rsa;
    private final Map<String, String> saved = new HashMap<>();

    @BeforeAll
    static void key() throws Exception {
        rsa = new RSAKeyGenerator(2048).keyID("k1").generate();
    }

    @BeforeEach
    void clearProps() {
        for (String k : KEYS) { saved.put(k, System.getProperty(k)); System.clearProperty(k); }
    }

    @AfterEach
    void restoreProps() {
        saved.forEach((k, v) -> { if (v == null) System.clearProperty(k); else System.setProperty(k, v); });
    }

    // ── boot: opt-in, fail closed ─────────────────────────────────────────────────────────────

    private static void bootProps(String jwksUri, String audience) {
        System.setProperty("auth.oidc.issuer", ISSUER);
        System.setProperty("auth.oidc.jwksUri", jwksUri);
        if (audience != null) System.setProperty("auth.oidc.audience", audience);
    }

    private static String bootFailure() {
        return assertThrows(IllegalStateException.class, OidcAuthenticator::new).getMessage();
    }

    @Test
    void withoutAClientAllowlistNothingChanges() {
        bootProps("http://idp.example/certs", null);   // plain http and no audience: boots, as before
        assertNull(MachineTrust.fromSystemProperties());
        new OidcAuthenticator();
    }

    @Test
    void aClientAllowlistWithoutAnAudienceRefusesBoot() {
        bootProps("https://idp.example/certs", null);
        System.setProperty("auth.oidc.clients", "la-integration");
        assertTrue(bootFailure().contains("auth.oidc.audience"));
    }

    @Test
    void aClientAllowlistWithAPlainHttpJwksRefusesBoot() {
        bootProps("http://idp.example/certs", AUDIENCE);
        System.setProperty("auth.oidc.clients", "la-integration");
        assertTrue(bootFailure().contains("auth.oidc.jwksUri"));
    }

    @Test
    void aLoopbackHttpJwksIsAllowedLikeTheDiscoveryUrl() {
        bootProps("http://localhost:8180/realms/inspecto/protocol/openid-connect/certs", AUDIENCE);
        System.setProperty("auth.oidc.clients", "la-integration");
        new OidcAuthenticator();
    }

    @Test
    void lifetimeOrReplaySettingsWithoutAClientAllowlistRefuseBoot() {
        bootProps("https://idp.example/certs", AUDIENCE);
        System.setProperty("auth.oidc.maxTokenLifetime", "PT5M");
        assertTrue(bootFailure().contains("auth.oidc.clients"));
        System.clearProperty("auth.oidc.maxTokenLifetime");
        System.setProperty("auth.oidc.refuseReplay", "true");
        assertTrue(bootFailure().contains("auth.oidc.clients"));
    }

    @Test
    void replayRefusalWithoutALifetimeCeilingRefusesBoot() {
        bootProps("https://idp.example/certs", AUDIENCE);
        System.setProperty("auth.oidc.clients", "la-integration");
        System.setProperty("auth.oidc.refuseReplay", "true");
        assertTrue(bootFailure().contains("auth.oidc.maxTokenLifetime"));
    }

    @Test
    void malformedValuesRefuseBoot() {
        bootProps("https://idp.example/certs", AUDIENCE);
        System.setProperty("auth.oidc.clients", "la-integration");
        for (String bad : List.of("5m", "PT0S", "-PT5M")) {
            System.setProperty("auth.oidc.maxTokenLifetime", bad);
            assertTrue(bootFailure().contains("auth.oidc.maxTokenLifetime"), bad);
        }
        System.setProperty("auth.oidc.maxTokenLifetime", "PT5M");
        System.setProperty("auth.oidc.refuseReplay", "yes");
        assertTrue(bootFailure().contains("auth.oidc.refuseReplay"));
    }

    @Test
    void theSystemPropertiesBuildTheConfiguredPolicy() {
        bootProps("https://idp.example/certs", AUDIENCE);
        System.setProperty("auth.oidc.clients", " la-integration , other-system ");
        System.setProperty("auth.oidc.maxTokenLifetime", "PT5M");
        System.setProperty("auth.oidc.refuseReplay", "true");
        MachineTrust t = MachineTrust.fromSystemProperties();
        Instant now = Instant.now();
        assertTrue(t.admits(claims("other-system", null, now, now.plusSeconds(300), "j1"), now), "trimmed list entry");
        assertFalse(t.admits(claims("la-integration", null, now, now.plusSeconds(301), "j2"), now), "PT5M ceiling");
        assertFalse(t.admits(claims("other-system", null, now, now.plusSeconds(300), "j1"), now), "replay on");
        assertTrue(t.admits(claims(SPA, null, now, now.plusSeconds(3600), "h"), now), "default SPA client id");
    }

    // ── admission ─────────────────────────────────────────────────────────────────────────────

    private static JWTClaimsSet claims(String azp, String clientId, Instant iat, Instant exp, String jti) {
        JWTClaimsSet.Builder b = new JWTClaimsSet.Builder().issuer(ISSUER).audience(AUDIENCE).subject("svc-uuid")
                .expirationTime(Date.from(exp));
        if (azp != null) b.claim("azp", azp);
        if (clientId != null) b.claim("client_id", clientId);
        if (iat != null) b.issueTime(Date.from(iat));
        if (jti != null) b.jwtID(jti);
        return b.build();
    }

    private static MachineTrust pinOnly() {
        return new MachineTrust(Set.of("la-integration"), SPA, null, false);
    }

    private static MachineTrust full() {
        return new MachineTrust(Set.of("la-integration"), SPA, Duration.ofMinutes(5), true);
    }

    @Test
    void onlyAnAllowedClientIsAdmitted() {
        Instant now = Instant.now();
        assertTrue(pinOnly().admits(claims("la-integration", null, now, now.plusSeconds(60), null), now));
        assertFalse(pinOnly().admits(claims("some-other-client", null, now, now.plusSeconds(60), null), now));
    }

    @Test
    void azpDecidesAndClientIdIsTheFallback() {
        Instant now = Instant.now();
        assertTrue(pinOnly().admits(claims(null, "la-integration", now, now.plusSeconds(60), null), now));
        assertFalse(pinOnly().admits(claims("some-other-client", "la-integration", now, now.plusSeconds(60), null), now),
                "azp names the client; client_id is read only when azp is absent");
    }

    @Test
    void aTokenNamingNoClientIsRefused() {
        Instant now = Instant.now();
        assertFalse(pinOnly().admits(claims(null, null, now, now.plusSeconds(60), null), now));
    }

    @Test
    void aMachineTokenAboveTheLifetimeCeilingIsRefused() {
        Instant now = Instant.now();
        assertFalse(full().admits(claims("la-integration", null, now, now.plus(Duration.ofDays(30)), "a"), now));
        assertTrue(full().admits(claims("la-integration", null, now, now.plusSeconds(300), "b"), now), "exactly the ceiling");
    }

    @Test
    void aMachineTokenWithoutIatIsRefusedUnderACeiling() {
        Instant now = Instant.now();
        assertFalse(full().admits(claims("la-integration", null, null, now.plusSeconds(60), "a"), now));
    }

    @Test
    void aReplayedJtiIsRefused() {
        Instant now = Instant.now();
        MachineTrust t = full();
        JWTClaimsSet c = claims("la-integration", null, now, now.plusSeconds(60), "jti-1");
        assertTrue(t.admits(c, now));
        assertFalse(t.admits(c, now.plusSeconds(1)), "the same token, sent again");
        assertTrue(t.admits(claims("la-integration", null, now, now.plusSeconds(60), "jti-2"), now));
    }

    @Test
    void aMachineTokenWithoutJtiIsRefusedUnderReplayRefusal() {
        Instant now = Instant.now();
        assertFalse(full().admits(claims("la-integration", null, now, now.plusSeconds(60), null), now));
    }

    @Test
    void aSeenJtiIsForgottenOnlyAfterExpiryPlusSkew() {
        Instant now = Instant.now();
        MachineTrust t = full();
        JWTClaimsSet c = claims("la-integration", null, now, now.plusSeconds(60), "jti-1");
        assertTrue(t.admits(c, now));
        assertFalse(t.admits(c, now.plusSeconds(60 + 59)), "still inside exp + 60 s skew");
        assertTrue(t.admits(c, now.plusSeconds(60 + 61)), "purged once past it (Nimbus refuses the token itself)");
    }

    @Test
    void theSpaClientsHumanTokensAreUnchanged() {
        Instant now = Instant.now();
        MachineTrust t = full();
        JWTClaimsSet human = claims(SPA, null, null, now.plus(Duration.ofHours(10)), "same");
        assertTrue(t.admits(human, now), "no iat, long lifetime: the SPA client is not a machine client");
        assertTrue(t.admits(human, now), "a person's token is sent on every call: never a replay");
    }

    // ── wiring: the Bearer path applies it ────────────────────────────────────────────────────

    private static String sign(JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims);
        jwt.sign(new RSASSASigner(rsa));
        return jwt.serialize();
    }

    private static Authenticator authenticator(MachineTrust trust) {
        return new OidcAuthenticator(new ImmutableJWKSet<>(new JWKSet(rsa.toPublicJWK())), ISSUER, AUDIENCE, "roles",
                null, null, null, "X-JWT-Assertion", null, trust);
    }

    @Test
    void theBearerPathRefusesWhatThePolicyRefuses() throws Exception {
        Instant now = Instant.now();
        String other = sign(claims("some-other-client", null, now, now.plusSeconds(60), "x"));
        String allowed = sign(claims("la-integration", null, now, now.plusSeconds(60), "y"));
        assertTrue(authenticate(authenticator(null), other).isPresent(), "no allowlist: today's behaviour");
        Authenticator pinned = authenticator(full());
        assertTrue(authenticate(pinned, other).isEmpty(), "client not on the allowlist");
        assertEquals("svc-uuid", authenticate(pinned, allowed).orElseThrow().id());
        assertTrue(authenticate(pinned, allowed).isEmpty(), "the same machine token, replayed");
    }

    /** The pin is the Bearer path's: a gateway assertion is minted by the gateway for its own callers. */
    @Test
    void theGatewayAssertionIsNotPinned() throws Exception {
        Instant now = Instant.now();
        RSAKey gw = new RSAKeyGenerator(2048).keyID("gw1").generate();
        JWTClaimsSet c = new JWTClaimsSet.Builder().issuer("gateway").audience("backend").subject("end-user")
                .expirationTime(Date.from(now.plusSeconds(60))).build();   // names no client at all
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("gw1").build(), c);
        jwt.sign(new RSASSASigner(gw));
        Authenticator a = new OidcAuthenticator(new ImmutableJWKSet<>(new JWKSet(rsa.toPublicJWK())), ISSUER, AUDIENCE,
                "roles", new ImmutableJWKSet<>(new JWKSet(gw.toPublicJWK())), "gateway", "backend", "X-JWT-Assertion",
                null, full());
        assertEquals("end-user", send(a, Map.of("X-JWT-Assertion", jwt.serialize())).orElseThrow().id());
    }

    private static Optional<Subject> authenticate(Authenticator auth, String token) throws Exception {
        return send(auth, Map.of("Authorization", "Bearer " + token));
    }

    private static Optional<Subject> send(Authenticator auth, Map<String, String> headers) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicReference<Optional<Subject>> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        server.createContext("/", ex -> {
            result.set(auth.authenticate(ex));
            ex.sendResponseHeaders(204, -1);
            ex.close();
            done.countDown();
        });
        server.start();
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + server.getAddress().getPort() + "/"));
            headers.forEach(b::header);
            HttpClient.newHttpClient().send(b.GET().build(), HttpResponse.BodyHandlers.discarding());
            assertTrue(done.await(5, TimeUnit.SECONDS));
            return result.get();
        } finally {
            server.stop(0);
        }
    }
}
