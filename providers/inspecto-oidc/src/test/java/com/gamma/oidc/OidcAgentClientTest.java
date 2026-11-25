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
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The delegated-person agent-client allowlist ({@code -Dauth.oidc.agentClients}, AGENT-KIT-1 AK-2a, plan §4.2): a person's
 * token minted for a listed agent client is that person with {@link Subject#agentClient()} set and the never-capabilities
 * (approve, reveal) dropped; the SPA client's token is unmarked; a client-credentials token stays a machine's; the client
 * pin for machines does not refuse a listed agent client; boot refuses a client listed as both kinds.
 */
class OidcAgentClientTest {

    private static final String ISSUER = "https://issuer.example/realms/inspecto";
    private static final String AUDIENCE = "inspecto-api";
    private static RSAKey rsa;

    @BeforeAll
    static void key() throws Exception {
        rsa = new RSAKeyGenerator(2048).keyID("k1").generate();
    }

    @AfterEach
    void clear() {
        for (String k : List.of("auth.oidc.agentClients", "auth.oidc.clients", "auth.oidc.audience", "auth.oidc.clientId"))
            System.clearProperty(k);
    }

    private static JWTClaimsSet.Builder token(String sub, String client) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder().issuer(ISSUER).audience(AUDIENCE).subject(sub).claim("azp", client)
                .claim("roles", List.of("admin")).issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(60)));
    }

    @Test
    void anAgentClientsPersonTokenIsThePersonMarkedWithTheClient() throws Exception {
        Subject spa = authenticate(null, Set.of("claude-code"), token("alice", "inspecto-spa").build());
        Subject agent = authenticate(null, Set.of("claude-code"), token("alice", "claude-code").build());
        assertEquals("alice", agent.id(), "the actor stays the person");
        assertFalse(agent.machine());
        assertEquals("claude-code", agent.agentClient());
        assertTrue(agent.agent());
        assertNull(spa.agentClient(), "the SPA client's token is not an agent's");
        assertTrue(spa.capabilities().containsAll(Roles.AGENT_NEVER_CAPABILITIES), "precondition: admin holds approve + reveal");
        for (String never : Roles.AGENT_NEVER_CAPABILITIES)
            assertFalse(agent.capabilities().contains(never), "an agent never holds " + never);
        Set<String> rest = new java.util.HashSet<>(spa.capabilities());
        rest.removeAll(Roles.AGENT_NEVER_CAPABILITIES);
        assertEquals(rest, agent.capabilities(), "otherwise exactly the person's grants");
    }

    @Test
    void aClientCredentialsTokenFromAnAgentClientStaysAMachine() throws Exception {
        Subject s = authenticate(null, Set.of("claude-code"),
                token("svc-uuid", "claude-code").claim("gty", "client_credentials").build());
        assertTrue(s.machine());
        assertNull(s.agentClient(), "a person's agent client never marks a client-credentials token");
    }

    @Test
    void aMachineClientListedAsAnAgentMachineIsBothMachineAndAgent() throws Exception {
        MachineTrust pin = new MachineTrust(Set.of("la-integration", "ops-bot"), "inspecto-spa", null, false, Set.of("ops-bot"));
        Subject bot = authenticate(pin, Set.of(), token("svc-ops", "ops-bot").claim("gty", "client_credentials").build());
        assertEquals("client:ops-bot", bot.id());
        assertTrue(bot.machine());
        assertEquals("ops-bot", bot.agentClient(), "AK-2c: a listed machine client is an agent");
        for (String never : Roles.AGENT_NEVER_CAPABILITIES)
            assertFalse(bot.capabilities().contains(never), "a machine agent never holds " + never);
        Subject plain = authenticate(pin, Set.of(), token("svc-la", "la-integration").claim("gty", "client_credentials").build());
        assertTrue(plain.machine());
        assertNull(plain.agentClient(), "a machine client not listed stays an ordinary machine");
    }

    @Test
    void agentMachineClientsMustBeListedMachineClients() {
        assertThrows(IllegalStateException.class,
                () -> new MachineTrust(Set.of("la-integration"), "inspecto-spa", null, false, Set.of("ops-bot")));
        assertThrows(IllegalStateException.class,
                () -> new MachineTrust(Set.of("inspecto-spa"), "inspecto-spa", null, false, Set.of("inspecto-spa")));
        System.setProperty("auth.oidc.agentMachineClients", "ops-bot");
        try {
            IllegalStateException e = assertThrows(IllegalStateException.class, MachineTrust::fromSystemProperties,
                    "no -Dauth.oidc.clients");
            assertTrue(e.getMessage().contains("agentMachineClients"), e.getMessage());
        } finally {
            System.clearProperty("auth.oidc.agentMachineClients");
        }
    }

    @Test
    void theMachineClientPinDoesNotRefuseAListedAgentClient() throws Exception {
        MachineTrust pin = new MachineTrust(Set.of("la-integration"), "inspecto-spa", null, false);
        Subject s = authenticate(pin, Set.of("claude-code"), token("alice", "claude-code").build());
        assertEquals("claude-code", s.agentClient());
        assertNull(authenticateOrNull(pin, Set.of(), token("alice", "claude-code").build()),
                "without the allowlist the pin refuses the unknown client, as before");
    }

    @Test
    void theIssuerIsNamedForProtectedResourceMetadata() {
        Authenticator auth = new OidcAuthenticator(new ImmutableJWKSet<>(new JWKSet(rsa.toPublicJWK())), ISSUER, AUDIENCE, "roles");
        assertEquals(Optional.of(ISSUER), auth.issuer());
    }

    @Test
    void bootRefusesAClientListedAsBothMachineAndAgent() {
        System.setProperty("auth.oidc.audience", AUDIENCE);
        System.setProperty("auth.oidc.clients", "la-integration,claude-code");
        System.setProperty("auth.oidc.agentClients", "claude-code");
        IllegalStateException e = assertThrows(IllegalStateException.class, MachineTrust::agentClientsFromSystemProperties);
        assertTrue(e.getMessage().contains("claude-code"), e.getMessage());
    }

    @Test
    void bootRefusesTheSpaClientAsAnAgentAndAnAllowlistWithoutAnAudience() {
        System.setProperty("auth.oidc.agentClients", "claude-code");
        assertThrows(IllegalStateException.class, MachineTrust::agentClientsFromSystemProperties, "no audience");
        System.setProperty("auth.oidc.audience", AUDIENCE);
        assertEquals(Set.of("claude-code"), MachineTrust.agentClientsFromSystemProperties());
        System.setProperty("auth.oidc.agentClients", "claude-code, inspecto-spa");
        assertThrows(IllegalStateException.class, MachineTrust::agentClientsFromSystemProperties, "the SPA client");
        System.clearProperty("auth.oidc.agentClients");
        assertEquals(Set.of(), MachineTrust.agentClientsFromSystemProperties(), "unset: no agent clients");
    }

    private static Subject authenticate(MachineTrust trust, Set<String> agents, JWTClaimsSet claims) throws Exception {
        Subject s = authenticateOrNull(trust, agents, claims);
        assertNotNull(s, "the token authenticates");
        return s;
    }

    private static Subject authenticateOrNull(MachineTrust trust, Set<String> agents, JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims);
        jwt.sign(new RSASSASigner(rsa));
        Authenticator auth = new OidcAuthenticator(new ImmutableJWKSet<>(new JWKSet(rsa.toPublicJWK())), ISSUER,
                AUDIENCE, "roles", null, null, null, "X-JWT-Assertion", null, trust, agents);
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
            return result.get();
        } finally {
            server.stop(0);
        }
    }
}
