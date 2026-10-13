package com.gamma.oidc;

import com.gamma.access.Roles;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The provider preset ({@code auth.oidc.provider}) and discovery ({@code auth.oidc.discoveryUrl}):
 * Keycloak role union, the explicit-claim override, the generic default staying put, the override
 * precedence, and fail-closed discovery. Offline: a loopback {@link HttpServer} stands in for the IdP.
 */
class OidcProviderPresetTest {

    private static final String ISSUER = "http://localhost:8180/realms/inspecto";
    private static final String CLIENT = "inspecto_spa";
    private static final String[] KEYS = {"auth.oidc.provider", "auth.oidc.discoveryUrl", "auth.oidc.issuer",
            "auth.oidc.jwksUri", "auth.oidc.tokenEndpoint", "auth.oidc.authorizeUrl", "auth.oidc.endSessionUrl",
            "auth.oidc.rolesClaim", "auth.oidc.clientId", "auth.oidc.audience"};
    private static RSAKey rsa;
    private final Map<String, String> saved = new java.util.HashMap<>();

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

    // ── role union ────────────────────────────────────────────────────────────────────────────

    private static JWTClaimsSet claims() {
        return new JWTClaimsSet.Builder().subject("u1").claim("azp", CLIENT)
                .claim("realm_access", Map.of("roles", List.of("business")))
                .claim("resource_access", Map.of(CLIENT, Map.of("roles", List.of("operations")),
                        "other-client", Map.of("roles", List.of("super"))))
                .build();
    }

    @Test
    void keycloakUnionsRealmRolesAndTheConfiguredClientsRolesOnly() {
        List<String> roles = RoleMapper.roles(claims(), "roles", new RoleMapper.Keycloak(CLIENT));
        assertEquals(Set.of("business", "operations"), Set.copyOf(roles), "another client's roles must not leak in");
    }

    @Test
    void keycloakFallsBackToAzpWhenNoClientIdIsConfigured() {
        assertEquals(Set.of("business", "operations"),
                Set.copyOf(RoleMapper.roles(claims(), "roles", new RoleMapper.Keycloak(null))));
        JWTClaimsSet noAzp = new JWTClaimsSet.Builder().subject("u1")
                .claim("realm_access", Map.of("roles", List.of("business")))
                .claim("resource_access", Map.of(CLIENT, Map.of("roles", List.of("operations")))).build();
        assertEquals(List.of("business"), RoleMapper.roles(noAzp, "roles", new RoleMapper.Keycloak(null)),
                "no clientId and no azp: realm roles only, never a guess");
    }

    @Test
    void keycloakCapabilitiesAreTheUnionOfBothRoleSources() {
        Set<String> caps = RoleMapper.capabilitiesFor(claims(), "roles", Roles.SEED, new RoleMapper.Keycloak(CLIENT));
        assertTrue(caps.contains(Roles.CAN_TRIAGE_REQUIREMENTS), "realm role business");
        assertTrue(caps.contains(Roles.CAN_OPERATE_RUNS), "client role operations");
        assertTrue(!caps.contains(Roles.CAN_AUTHOR_WORKBENCH));
    }

    @Test
    void genericIsUnchanged() {
        assertEquals(List.of("business"), RoleMapper.roles(claims(), "roles"), "today: realm_access fallback only");
        assertEquals(List.of("a"), RoleMapper.roles(new JWTClaimsSet.Builder().claim("groups", List.of("a")).build(), "groups"));
    }

    @Test
    void anExplicitRolesClaimTurnsTheKeycloakUnionOff() {
        System.setProperty("auth.oidc.provider", "keycloak");
        System.setProperty("auth.oidc.issuer", ISSUER);
        System.setProperty("auth.oidc.rolesClaim", "groups");
        Authenticator a = new OidcAuthenticator();   // RemoteJWKSet fetches lazily; nothing is called here
        assertTrue(a != null);
        // the wiring is private; assert through the same decision the constructor makes
        assertNull(roleSourceFor("groups"));
        assertEquals(new RoleMapper.Keycloak(null), roleSourceFor(null));
    }

    private static RoleMapper.Keycloak roleSourceFor(String explicit) {
        if (explicit != null) System.setProperty("auth.oidc.rolesClaim", explicit); else System.clearProperty("auth.oidc.rolesClaim");
        try {
            var m = OidcAuthenticator.class.getDeclaredMethod("keycloakRoles");
            m.setAccessible(true);
            return (RoleMapper.Keycloak) m.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void anAuthenticatorWithTheKeycloakSourceGrantsFromBothClaims() throws Exception {
        Authenticator a = new OidcAuthenticator(new ImmutableJWKSet<>(new JWKSet(rsa.toPublicJWK())), ISSUER, CLIENT,
                "roles", null, null, null, "X-JWT-Assertion", new RoleMapper.Keycloak(CLIENT));
        JWTClaimsSet c = new JWTClaimsSet.Builder().issuer(ISSUER).subject("u1").audience(CLIENT)
                .expirationTime(Date.from(Instant.now().plusSeconds(60))).claim("azp", CLIENT)
                .claim("realm_access", Map.of("roles", List.of("pipeline-developer")))
                .claim("resource_access", Map.of(CLIENT, Map.of("roles", List.of("operations")))).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), c);
        jwt.sign(new RSASSASigner(rsa));
        Optional<Subject> s = authenticate(a, "Bearer " + jwt.serialize());
        assertTrue(s.isPresent());
        assertTrue(s.get().capabilities().containsAll(Set.of(Roles.CAN_AUTHOR_WORKBENCH, Roles.CAN_OPERATE_RUNS)),
                "got " + s.get().capabilities());
    }

    private static Optional<Subject> authenticate(Authenticator auth, String header) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<Optional<Subject>> out = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        server.createContext("/", ex -> {
            out.set(auth.authenticate(ex));
            ex.sendResponseHeaders(204, -1);
            ex.close();
            done.countDown();
        });
        server.start();
        try {
            HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/")).header("Authorization", header)
                    .GET().build(), HttpResponse.BodyHandlers.discarding());
            assertTrue(done.await(5, TimeUnit.SECONDS));
            return out.get();
        } finally {
            server.stop(0);
        }
    }

    // ── presets, discovery and precedence ─────────────────────────────────────────────────────

    @Test
    void genericWithNothingConfiguredResolvesNothing() {
        assertNull(OidcConfig.get("issuer"));
        assertNull(OidcConfig.get("jwksUri"));
        System.setProperty("auth.oidc.jwksUri", "https://x/jwks");
        assertEquals("https://x/jwks", OidcConfig.get("jwksUri"));
    }

    @Test
    void anUnknownProviderRefusesToBoot() {
        System.setProperty("auth.oidc.provider", "okta");
        assertThrows(IllegalStateException.class, OidcConfig::provider);
    }

    @Test
    void theKeycloakPresetDerivesItsFixedEndpointsFromTheIssuer() {
        System.setProperty("auth.oidc.provider", "keycloak");
        System.setProperty("auth.oidc.issuer", ISSUER + "/");
        assertEquals(ISSUER + "/protocol/openid-connect/certs", OidcConfig.get("jwksUri"));
        assertEquals(ISSUER + "/protocol/openid-connect/token", OidcConfig.get("tokenEndpoint"));
        assertEquals(ISSUER + "/protocol/openid-connect/auth", OidcConfig.get("authorizeUrl"));
        System.setProperty("auth.oidc.jwksUri", "https://override/certs");
        assertEquals("https://override/certs", OidcConfig.get("jwksUri"), "explicit always wins");
    }

    private static HttpServer discoveryServer(String body, int status) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/.well-known/openid-configuration", ex -> {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
            if (b.length > 0) ex.getResponseBody().write(b);
            ex.close();
        });
        s.start();
        return s;
    }

    private static String doc(String issuer) {
        return "{\"issuer\":\"" + issuer + "\",\"jwks_uri\":\"https://idp/certs\",\"token_endpoint\":\"https://idp/token\","
                + "\"authorization_endpoint\":\"https://idp/auth\"}";
    }

    private static String urlOf(HttpServer s) {
        return "http://127.0.0.1:" + s.getAddress().getPort() + "/.well-known/openid-configuration";
    }

    @Test
    void discoveryDerivesIssuerAndEndpointsAndExplicitPropertiesOverrideIt() throws Exception {
        HttpServer s = discoveryServer(doc("https://idp/realms/x"), 200);
        try {
            System.setProperty("auth.oidc.discoveryUrl", urlOf(s));
            assertEquals("https://idp/realms/x", OidcConfig.get("issuer"));
            assertEquals("https://idp/certs", OidcConfig.get("jwksUri"));
            assertEquals("https://idp/token", OidcConfig.get("tokenEndpoint"));
            assertEquals("https://idp/auth", OidcConfig.get("authorizeUrl"));
            System.setProperty("auth.oidc.tokenEndpoint", "https://mine/token");
            assertEquals("https://mine/token", OidcConfig.get("tokenEndpoint"));
            System.setProperty("auth.oidc.issuer", "https://idp/realms/x/");   // equal modulo trailing slash
            assertEquals("https://idp/realms/x/", OidcConfig.get("issuer"));
        } finally {
            s.stop(0);
        }
    }

    @Test
    void aDiscoveryIssuerThatDiffersFromTheConfiguredOneRefusesToBoot() throws Exception {
        HttpServer s = discoveryServer(doc("https://idp/realms/x"), 200);
        try {
            System.setProperty("auth.oidc.discoveryUrl", urlOf(s));
            System.setProperty("auth.oidc.issuer", "https://evil/realms/x");
            IllegalStateException e = assertThrows(IllegalStateException.class, OidcAuthenticator::new);
            assertTrue(!e.getMessage().contains("evil") && !e.getMessage().contains("idp"), "value-free: " + e.getMessage());
        } finally {
            s.stop(0);
        }
    }

    @Test
    void discoveryFailureFailsClosedWithAValueFreeMessage() throws Exception {
        for (HttpServer s : new HttpServer[] {discoveryServer("", 500), discoveryServer("not json", 200),
                discoveryServer("{\"jwks_uri\":\"https://idp/certs\"}", 200)}) {
            try {
                System.setProperty("auth.oidc.discoveryUrl", urlOf(s));
                IllegalStateException e = assertThrows(IllegalStateException.class, OidcAuthenticator::new);
                assertTrue(!e.getMessage().contains("127.0.0.1"), "value-free: " + e.getMessage());
            } finally {
                s.stop(0);
            }
        }
        System.setProperty("auth.oidc.discoveryUrl", "http://127.0.0.1:1/.well-known/openid-configuration");
        assertThrows(IllegalStateException.class, OidcAuthenticator::new);
    }

    @Test
    void discoveryRequiresHttpsExceptOnLoopback() {
        System.setProperty("auth.oidc.discoveryUrl", "http://idp.example.com/.well-known/openid-configuration");
        assertThrows(IllegalStateException.class, () -> OidcConfig.get("issuer"));
    }

    @Test
    void theTokenRelayAndBootstrapReadTheDiscoveredEndpoints() throws Exception {
        HttpServer s = discoveryServer(doc("https://idp/realms/x"), 200);
        try {
            System.setProperty("auth.oidc.discoveryUrl", urlOf(s));
            Map<String, Object> auth = new OidcTokenRelay().bootstrapAuth();
            assertEquals("https://idp/auth", auth.get("authorizeUrl"));
            assertEquals("inspecto-spa", auth.get("clientId"));
        } finally {
            s.stop(0);
        }
    }
}
