package com.gamma.oidc;

import com.gamma.access.Roles;
import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Subject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link OidcAuthenticator} with {@code auth.oidc.provider=keycloak} + {@code auth.oidc.discoveryUrl}
 * against a <b>real Keycloak</b>: the Keycloak twin of {@link OidcAgainstRealProviderTest}. SKIPS unless a
 * Keycloak is named (a skip is NOT a pass: discovery, the real JWKS fetch and the real token shape are
 * unverified whenever this skips).
 *
 * <pre>{@code
 * docker compose -f dev-infra/docker-compose.yml up -d keycloak      # imports dev-infra/keycloak/inspecto-realm.json
 * mvn -o test -Pedition-professional -pl providers/inspecto-oidc -am -Dsurefire.failIfNoSpecifiedTests=false \
 *     -Dtest=OidcKeycloakAgainstRealProviderTest -Dkeycloak.issuer=http://localhost:8180/realms/inspecto
 * }</pre>
 *
 * <p>Optional: {@code -Dkeycloak.clientId} (default {@code inspecto_spa}), {@code -Dkeycloak.username}/
 * {@code -Dkeycloak.password} (default the dev realm's {@code kc-operator}, whose role is a CLIENT role, so a
 * pass proves the realm + client role union). Env fallbacks: {@code KEYCLOAK_ISSUER}, ... The token is fetched with the
 * dev realm's direct-access grant (enabled on the dev client only, never in production).
 */
class OidcKeycloakAgainstRealProviderTest {

    private String issuer;
    private String clientId;
    private String username;
    private String password;

    @BeforeEach
    void requireAKeycloak() {
        issuer = prop("keycloak.issuer", "KEYCLOAK_ISSUER");
        clientId = orDefault(prop("keycloak.clientId", "KEYCLOAK_CLIENT_ID"), "inspecto_spa");
        username = orDefault(prop("keycloak.username", "KEYCLOAK_USERNAME"), "kc-operator");
        password = orDefault(prop("keycloak.password", "KEYCLOAK_PASSWORD"), "dev-only-operator"); // secret-allow: dev-infra realm test-user password, a published dev fixture
        assumeTrue(issuer != null, "needs a real Keycloak: pass -Dkeycloak.issuer=http://localhost:8180/realms/inspecto "
                + "(or KEYCLOAK_ISSUER) after `docker compose -f dev-infra/docker-compose.yml up -d keycloak`. "
                + "Skipping is NOT a pass: discovery + the real JWKS fetch + the real token shape are unverified.");
    }

    @Test
    void aRealKeycloakTokenAuthenticatesThroughDiscoveryWithRealmAndClientRoles() throws Exception {
        String token = passwordGrantToken();
        withProperties(() -> {
            Optional<Subject> s = authenticate(new OidcAuthenticator(), "Bearer " + token);
            assertTrue(s.isPresent(), "a live Keycloak token must authenticate (issuer/JWKS from discovery, aud = client id)");
            assertTrue(s.get().capabilities().contains(Roles.CAN_OPERATE_RUNS)
                            || s.get().capabilities().contains(Roles.CAN_TRIAGE_REQUIREMENTS)
                            || s.get().capabilities().contains(Roles.CAN_AUTHOR_WORKBENCH),
                    "the user's realm/client role must become capabilities, got " + s.get().capabilities());
        });
    }

    /** The audience mapper matters: Keycloak's default aud is `account`, so a wrong audience must be refused. */
    @Test
    void aTokenIsRefusedForAnotherAudience() throws Exception {
        String token = passwordGrantToken();
        withProperties(() -> {
            System.setProperty("auth.oidc.audience", "not-this-client");
            assertTrue(authenticate(new OidcAuthenticator(), "Bearer " + token).isEmpty());
        });
    }

    // ── plumbing ──────────────────────────────────────────────────────────────────────────────

    private interface Body { void run() throws Exception; }

    private void withProperties(Body body) throws Exception {
        String[] keys = {"auth.oidc.provider", "auth.oidc.discoveryUrl", "auth.oidc.audience", "auth.oidc.clientId",
                "auth.oidc.issuer", "auth.oidc.jwksUri", "auth.oidc.rolesClaim"};
        String[] saved = new String[keys.length];
        for (int i = 0; i < keys.length; i++) { saved[i] = System.getProperty(keys[i]); System.clearProperty(keys[i]); }
        System.setProperty("auth.oidc.provider", "keycloak");
        System.setProperty("auth.oidc.discoveryUrl", issuer + "/.well-known/openid-configuration");
        System.setProperty("auth.oidc.audience", clientId);
        System.setProperty("auth.oidc.clientId", clientId);
        try {
            body.run();
        } finally {
            for (int i = 0; i < keys.length; i++)
                if (saved[i] == null) System.clearProperty(keys[i]); else System.setProperty(keys[i], saved[i]);
        }
    }

    private String passwordGrantToken() throws Exception {
        String form = "grant_type=password&client_id=" + enc(clientId) + "&username=" + enc(username)
                + "&password=" + enc(password) + "&scope=openid"; // secret-allow: form field name, the value comes from the dev fixture
        HttpResponse<String> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(issuer + "/protocol/openid-connect/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), "token endpoint said: " + r.body());
        String body = r.body();
        int start = body.indexOf('"', body.indexOf(':', body.indexOf("\"access_token\"")) + 1) + 1;
        return body.substring(start, body.indexOf('"', start));
    }

    private static String enc(String v) { return URLEncoder.encode(v, StandardCharsets.UTF_8); }

    private static String prop(String sys, String env) {
        String v = System.getProperty(sys);
        if (v == null || v.isBlank()) v = System.getenv(env);
        return v == null || v.isBlank() ? null : v;
    }

    private static String orDefault(String v, String d) { return v == null ? d : v; }

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
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"))
                    .header("Authorization", header).GET().build(), HttpResponse.BodyHandlers.discarding());
            assertTrue(done.await(5, TimeUnit.SECONDS));
            return out.get();
        } finally {
            server.stop(0);
        }
    }
}
