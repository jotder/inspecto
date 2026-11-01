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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A machine (client-credentials) token becomes a machine Subject named by its client (OIDC-CC-MACHINE-ACTOR-1, review
 * G7): id {@code client:<client id>}, {@link Subject#machine()} true, the token's {@code sub} kept as
 * {@link Subject#machineSub()}. A person's token is unchanged.
 */
class OidcMachineActorTest {

    private static final String ISSUER = "https://issuer.example/realms/inspecto";
    private static final String AUDIENCE = "inspecto-api";
    private static RSAKey rsa;

    @BeforeAll
    static void key() throws Exception {
        rsa = new RSAKeyGenerator(2048).keyID("k1").generate();
    }

    private static JWTClaimsSet.Builder token(String sub) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder().issuer(ISSUER).audience(AUDIENCE).subject(sub)
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(60)));
    }

    @Test
    void aClientCredentialsGrantNamesTheClient() throws Exception {
        Subject s = authenticate(null, token("svc-uuid").claim("azp", "la-integration")
                .claim("gty", "client_credentials").build());
        assertEquals("client:la-integration", s.id());
        assertTrue(s.machine());
        assertEquals("svc-uuid", s.machineSub());
    }

    @Test
    void theAuth0SpellingAndTheClientIdClaimCountToo() throws Exception {
        Subject s = authenticate(null, token("abc@clients").claim("client_id", "la-integration")
                .claim("gty", "client-credentials").build());
        assertEquals("client:la-integration", s.id());
    }

    @Test
    void aClientListedInAuthOidcClientsIsAMachineWithoutGty() throws Exception {
        MachineTrust pin = new MachineTrust(Set.of("la-integration"), "inspecto-spa", null, false);
        Subject s = authenticate(pin, token("svc-uuid").claim("azp", "la-integration").build());
        assertEquals("client:la-integration", s.id());
        assertEquals("svc-uuid", s.machineSub());
    }

    @Test
    void aPersonsTokenIsUnchanged() throws Exception {
        MachineTrust pin = new MachineTrust(Set.of("la-integration"), "inspecto-spa", null, false);
        Subject s = authenticate(pin, token("f3a9-uuid").claim("azp", "inspecto-spa").build());
        assertEquals("f3a9-uuid", s.id());
        assertFalse(s.machine());
        assertNull(s.machineSub());
        assertEquals("f3a9-uuid", authenticate(null, token("f3a9-uuid").claim("azp", "other-app").build()).id(),
                "without gty or the allowlist an unknown client is not guessed to be a machine");
    }

    @Test
    void aGrantNamingNoClientKeepsTheSub() throws Exception {
        Subject s = authenticate(null, token("svc-uuid").claim("gty", "client_credentials").build());
        assertEquals("svc-uuid", s.id(), "no client to name");
        assertFalse(s.machine());
    }

    private static Subject authenticate(MachineTrust trust, JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims);
        jwt.sign(new RSASSASigner(rsa));
        Authenticator auth = new OidcAuthenticator(new ImmutableJWKSet<>(new JWKSet(rsa.toPublicJWK())), ISSUER,
                AUDIENCE, "roles", null, null, null, "X-JWT-Assertion", null, trust);
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicReference<Subject> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        server.createContext("/", ex -> {
            result.set(auth.authenticate(ex).orElse(null));
            ex.sendResponseHeaders(204, -1);
            ex.close();
            done.countDown();
        });
        server.start();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + server.getAddress().getPort() + "/"))
                    .header("Authorization", "Bearer " + jwt.serialize()).GET().build();
            HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.discarding());
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertTrue(result.get() != null, "the token authenticates");
            return result.get();
        } finally {
            server.stop(0);
        }
    }
}
