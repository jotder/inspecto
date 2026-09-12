package com.gamma.security;

import com.gamma.control.Authenticator;
import com.gamma.control.Subject;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link OidcAuthenticator} against a <b>real OpenID provider over real HTTPS</b> — the one path this
 * module has never exercised.
 *
 * <p>🔴 {@code OidcAuthenticatorTest} injects a {@code JWKSource} through the package-private
 * constructor and says so: <em>"offline (no network) … a self-signed RSA key pair stands in for the
 * IAM's JWKS via ImmutableJWKSet … without a running Keycloak/WSO2."</em> Thorough on logic, but it means
 * the <b>no-arg {@code ServiceLoader} constructor that production actually uses</b>, Nimbus's
 * {@code RemoteJWKSet}, the HTTPS fetch, and the real token shape were all unproven. This closes that.
 *
 * <p><b>Enable it</b> by naming a provider — with neither property nor env var, every test SKIPS, and the
 * skip message says exactly how to turn it back on rather than passing quietly
 * ({@code okf/backend/build-run/guard-coverage.md}):
 *
 * <pre>{@code
 * -Dinspecto.test.oidc.issuer=https://localhost:9443/oauth2/token   (or INSPECTO_TEST_OIDC_ISSUER)
 * -Dinspecto.test.oidc.clientId=...  -Dinspecto.test.oidc.clientSecret=...
 * }</pre>
 *
 * <p>🔴 <b>The JVM must trust the provider's certificate.</b> {@code OidcAuthenticator} fetches JWKS with
 * stock Nimbus over the default truststore and there is <b>no TLS-skip option in the module</b> — by
 * design. For a self-signed dev IdP, import its cert and point the TEST JVM at the result:
 *
 * <pre>{@code
 * keytool -importcert -noprompt -alias wso2is -file wso2is.crt -keystore ts.jks -storepass changeit
 * MAVEN_OPTS="-Djavax.net.ssl.trustStore=<abs>/ts.jks -Djavax.net.ssl.trustStorePassword=changeit" \
 * mvn -o -B test -Pedition-enterprise -pl inspecto-security -am -DforkCount=0 \
 *     -Dtest=OidcAgainstRealProviderTest -Dsurefire.failIfNoSpecifiedTests=false \
 *     -Dinspecto.test.oidc.issuer=... -Dinspecto.test.oidc.clientId=... -Dinspecto.test.oidc.clientSecret=...
 * }</pre>
 *
 * <p>⚠ {@code -DforkCount=0} is not decoration. The parent POM's surefire config is
 * {@code <argLine>@{argLine} …</argLine>}, and that late-bound {@code @{argLine}} resolves the PROJECT
 * property — a command-line {@code -DargLine} does not override it, so TLS flags never reach a forked
 * JVM. Running in the Maven JVM is what lets {@code MAVEN_OPTS} apply.
 *
 * <p><b>Proven against WSO2 Identity Server 7.3.0, 2026-09-13.</b> Findings worth keeping, because each
 * contradicts something the Keycloak-shaped blueprint in {@code docs/api/deployment/README.md} implies:
 * <ul>
 *   <li>WSO2 issues an <b>opaque</b> access token by default — the application must be switched to
 *       {@code ext_token_type: JWT} or every request 401s against a JWT validator.</li>
 *   <li>Its {@code aud} is the <b>client id</b>, not a separate API audience like {@code inspecto-api}.</li>
 *   <li>A client-credentials token carries <b>no roles claim at all</b> — neither {@code roles} nor
 *       Keycloak's {@code realm_access.roles} — so {@link RoleMapper} grants nothing and the subject
 *       authenticates with zero capabilities. Fail-closed, and correct, but it means an authenticated
 *       caller is still refused until roles are actually issued.</li>
 *   <li>Client ids must match {@code [a-zA-Z0-9_]{15,30}} — the documented default {@code inspecto-spa}
 *       is not even issuable by this vendor.</li>
 * </ul>
 */
class OidcAgainstRealProviderTest {

    private static final String ISSUER_PROP = "inspecto.test.oidc.issuer";

    private String issuer;
    private String clientId;
    private String clientSecret;

    @BeforeEach
    void requireAConfiguredProvider() {
        issuer = prop(ISSUER_PROP, "INSPECTO_TEST_OIDC_ISSUER");
        clientId = prop("inspecto.test.oidc.clientId", "INSPECTO_TEST_OIDC_CLIENT_ID");
        clientSecret = prop("inspecto.test.oidc.clientSecret", "INSPECTO_TEST_OIDC_CLIENT_SECRET");
        assumeTrue(issuer != null && clientId != null && clientSecret != null,
                "needs a real OpenID provider: pass -D" + ISSUER_PROP + "=… plus .clientId/.clientSecret "
                        + "(or INSPECTO_TEST_OIDC_*), and point the JVM truststore at its certificate — "
                        + "see this class's javadoc for the exact command. Skipping is NOT a pass: the "
                        + "RemoteJWKSet/HTTPS path is unverified whenever these tests skip.");
    }

    private static String prop(String sys, String env) {
        String v = System.getProperty(sys);
        if (v == null || v.isBlank()) v = System.getenv(env);
        return v == null || v.isBlank() ? null : v;
    }

    /** Everything the module needs, read from the provider's own discovery document — never derived. */
    private String discover(String field) throws Exception {
        String doc = get(issuer + "/.well-known/openid-configuration");
        int i = doc.indexOf('"' + field + '"');
        assertTrue(i >= 0, "the provider's discovery document has no '" + field + "': " + doc);
        int start = doc.indexOf('"', doc.indexOf(':', i) + 1) + 1;
        return doc.substring(start, doc.indexOf('"', start));
    }

    /**
     * 🔴 The whole point: a token this test just obtained from a live provider, validated through the
     * <b>no-arg constructor</b> — real {@code RemoteJWKSet}, real HTTPS, real key rotation semantics.
     */
    @Test
    void aRealTokenFromARealProviderValidatesThroughTheNoArgConstructor() throws Exception {
        String jwks = discover("jwks_uri");
        String token = clientCredentialsToken(discover("token_endpoint"));
        assertEquals(2, token.chars().filter(c -> c == '.').count(),
                "the provider must be configured to issue a JWT access token, not an opaque one — "
                        + "WSO2 defaults to opaque and needs ext_token_type: JWT");

        withProperties(jwks, issuer, audienceOf(token), () -> {
            Authenticator auth = new OidcAuthenticator();          // the constructor production uses
            Optional<Subject> subject = auth.authenticate(bearer(token));
            assertTrue(subject.isPresent(), "a live, correctly-signed token must authenticate");
            assertEquals(clientId, subject.get().id(),
                    "the 'sub' claim must reach the Subject — WSO2 sets it to the client id for a "
                            + "client-credentials grant");
            assertTrue(subject.get().capabilities().isEmpty(),
                    "⚠ and it authenticates with ZERO capabilities: this token carries no roles claim at "
                            + "all, so RoleMapper grants nothing. Fail-closed and correct — but it means "
                            + "'authenticated' is not 'authorized' until the IdP actually issues roles.");
        });
    }

    /** ⚠ A negative that would otherwise SUCCEED: the same live JWKS, a deliberately wrong issuer. */
    @Test
    void aRealTokenIsRefusedWhenTheIssuerDoesNotMatch() throws Exception {
        String jwks = discover("jwks_uri");
        String token = clientCredentialsToken(discover("token_endpoint"));

        withProperties(jwks, issuer + "/not-this-issuer", audienceOf(token), () -> {
            Authenticator auth = new OidcAuthenticator();
            assertTrue(auth.authenticate(bearer(token)).isEmpty(),
                    "issuer is checked against the live key set, so this proves the signature passed and "
                            + "the ISSUER claim is what refused it — not a fetch failure masquerading as one");
        });
    }

    // ── plumbing ──────────────────────────────────────────────────────────────────────────────

    private interface Body { void run() throws Exception; }

    /** Sets the {@code -Dauth.oidc.*} the no-arg constructor reads, and restores them afterwards. */
    private void withProperties(String jwks, String iss, String aud, Body body) throws Exception {
        String pj = System.getProperty("auth.oidc.jwksUri");
        String pi = System.getProperty("auth.oidc.issuer");
        String pa = System.getProperty("auth.oidc.audience");
        System.setProperty("auth.oidc.jwksUri", jwks);
        System.setProperty("auth.oidc.issuer", iss);
        System.setProperty("auth.oidc.audience", aud);
        try {
            body.run();
        } finally {
            restore("auth.oidc.jwksUri", pj);
            restore("auth.oidc.issuer", pi);
            restore("auth.oidc.audience", pa);
        }
    }

    private static void restore(String key, String saved) {
        if (saved == null) System.clearProperty(key); else System.setProperty(key, saved);
    }

    /** ⚠ Read the audience OUT of the token rather than assuming it — WSO2 uses the client id. */
    private static String audienceOf(String jwt) {
        String claims = new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
        int i = claims.indexOf("\"aud\"");
        int start = claims.indexOf('"', claims.indexOf(':', i) + 1) + 1;
        return claims.substring(start, claims.indexOf('"', start));
    }

    private String clientCredentialsToken(String tokenEndpoint) throws Exception {
        String basic = Base64.getEncoder().encodeToString(
                (clientId + ':' + clientSecret).getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(tokenEndpoint))
                        .header("Authorization", "Basic " + basic)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), "token endpoint said: " + r.body());
        String body = r.body();
        int start = body.indexOf('"', body.indexOf(':', body.indexOf("\"access_token\"")) + 1) + 1;
        return body.substring(start, body.indexOf('"', start));
    }

    private static String get(String url) throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), url + " said " + r.statusCode());
        return r.body();
    }

    /** The smallest {@link HttpExchange} carrying one Authorization header. */
    private static HttpExchange bearer(String token) {
        Headers in = new Headers();
        in.add("Authorization", "Bearer " + token);
        return new StubExchange(in);
    }

    /** Only the accessors {@link OidcAuthenticator} touches are implemented; the rest throw if used. */
    private static final class StubExchange extends HttpExchange {
        private final Headers request;

        private StubExchange(Headers request) { this.request = request; }

        @Override public Headers getRequestHeaders() { return request; }
        @Override public Headers getResponseHeaders() { return new Headers(); }
        @Override public URI getRequestURI() { return URI.create("/api/v1/spaces"); }
        @Override public String getRequestMethod() { return "GET"; }
        @Override public com.sun.net.httpserver.HttpContext getHttpContext() { throw new UnsupportedOperationException(); }
        @Override public void close() { }
        @Override public java.io.InputStream getRequestBody() { return java.io.InputStream.nullInputStream(); }
        @Override public OutputStream getResponseBody() { return new ByteArrayOutputStream(); }
        @Override public void sendResponseHeaders(int rCode, long responseLength) { }
        @Override public java.net.InetSocketAddress getRemoteAddress() { throw new UnsupportedOperationException(); }
        @Override public int getResponseCode() { return -1; }
        @Override public java.net.InetSocketAddress getLocalAddress() { throw new UnsupportedOperationException(); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) { }
        @Override public void setStreams(java.io.InputStream i, OutputStream o) { }
        @Override public com.sun.net.httpserver.HttpPrincipal getPrincipal() { return null; }
    }
}
