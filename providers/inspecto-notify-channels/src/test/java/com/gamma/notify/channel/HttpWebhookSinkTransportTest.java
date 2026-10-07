package com.gamma.notify.channel;

import com.gamma.pipeline.exec.WebhookSinkTransport;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code sink.webhook} wire against a real in-process {@link HttpServer}: what reaches the receiver
 * (body, content type, bearer token, idempotency key), non-2xx and redirects as failures, and that this
 * module — and only this module — registers the transport the engine discovers.
 */
class HttpWebhookSinkTransportTest {

    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<Map<String, String>> headers = new CopyOnWriteArrayList<>();
    private final List<String> redirectHits = new CopyOnWriteArrayList<>();
    private volatile int respondWith = 202;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ingest", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            headers.add(Map.of(
                    "content-type", String.valueOf(ex.getRequestHeaders().getFirst("Content-Type")),
                    "authorization", String.valueOf(ex.getRequestHeaders().getFirst("Authorization")),
                    "idempotency-key", String.valueOf(ex.getRequestHeaders().getFirst("Idempotency-Key"))));
            if (respondWith / 100 == 3) ex.getResponseHeaders().add("Location", base() + "/elsewhere");
            ex.sendResponseHeaders(respondWith, -1);
            ex.close();
        });
        server.createContext("/elsewhere", ex -> {
            redirectHits.add(ex.getRequestMethod());
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
    }

    /** {@code hook.test} is allowlisted; see {@link EgressNet}. */
    private EgressNet net = new EgressNet("hook.test");

    @AfterEach
    void stop() {
        net.close();
        server.stop(0);
    }

    private String base() {
        return base("hook.test");
    }

    private String base(String host) {
        return "http://" + host + ":" + server.getAddress().getPort();
    }

    private void post(String token) throws Exception {
        postTo("hook.test", token);
    }

    private void postTo(String host, String token) throws Exception {
        new HttpWebhookSinkTransport().post(URI.create(base(host) + "/ingest"), token, Duration.ofSeconds(5),
                "{\"batch\":1,\"rows\":[{\"id\":1}]}", Map.of("Idempotency-Key", "c-1:webhook:1"));
    }

    // ── the egress policy on post (WEBHOOK-EGRESS-POLICY-1) ─────────────────────────────────────────────

    /** Deny by default: a private address with no allowlist entry is never dialled. */
    @Test
    void aPrivateAddressWithNoAllowlistEntryIsRefusedAndNeverDialled() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> postTo("private.test", "t"));
        assertTrue(e.getMessage().contains("egress refused") && e.getMessage().contains("10.77.0.2")
                && e.getMessage().contains("private"), e.getMessage());
        assertTrue(net.dialled.isEmpty(), "nothing may be dialled: " + net.dialled);
        assertTrue(bodies.isEmpty());
    }

    /** The metadata service is never liftable — not even by a host entry naming it. */
    @Test
    void theMetadataAddressIsRefusedEvenWhenItsNameIsAllowlisted() {
        net.close();
        net = new EgressNet("hook.test", "meta.test");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> postTo("meta.test", "t"));
        assertTrue(e.getMessage().contains("169.254.169.254") && e.getMessage().contains("link-local"), e.getMessage());
        assertTrue(net.dialled.isEmpty());
    }

    /** A loopback literal is refused — the old wire dialled it. */
    @Test
    void aLoopbackLiteralIsRefused() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> postTo("127.0.0.1", "t"));
        assertTrue(e.getMessage().contains("loopback"), e.getMessage());
        assertTrue(bodies.isEmpty(), "the in-process server on 127.0.0.1 must not be reached");
    }

    /** A name that does not resolve fails closed, naming the host. */
    @Test
    void aNameThatDoesNotResolveIsRefused() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> postTo("nowhere.test", "t"));
        assertTrue(e.getMessage().contains("'nowhere.test' does not resolve"), e.getMessage());
    }

    /** The host-syntax check still applies: a non-canonical numeric host never reaches the resolver. */
    @Test
    void aNonCanonicalNumericHostIsRefused() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> postTo("0x7f000001", "t"));
        assertTrue(e.getMessage().contains("non-canonical"), e.getMessage());
    }

    /** Allowed via the allowlist, and pinned: the ONE checked address is dialled; the name travels as Host. */
    @Test
    void anAllowlistedHostIsDialledAtTheCheckedAddressWithTheNameAsHost() throws Exception {
        List<String> hosts = new CopyOnWriteArrayList<>();
        server.createContext("/hosted", ex -> {
            hosts.add(ex.getRequestHeaders().getFirst("Host"));
            ex.getRequestBody().readAllBytes();
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        new HttpWebhookSinkTransport().post(URI.create(base() + "/hosted"), null, Duration.ofSeconds(5), "{}", Map.of());
        assertEquals(List.of(EgressNet.HOOK), net.dialled);
        assertEquals(List.of("hook.test:" + server.getAddress().getPort()), hosts);
    }

    @Test
    void postsTheJsonBodyWithTokenAndIdempotencyKey() throws Exception {
        post("s3cret");
        assertEquals(List.of("{\"batch\":1,\"rows\":[{\"id\":1}]}"), bodies);
        assertEquals("application/json", headers.get(0).get("content-type"));
        assertEquals("Bearer s3cret", headers.get(0).get("authorization"));
        assertEquals("c-1:webhook:1", headers.get(0).get("idempotency-key"));
    }

    @Test
    void sendsNoAuthorizationWithoutAToken() throws Exception {
        post(null);
        assertEquals("null", headers.get(0).get("authorization"));
    }

    @Test
    void aNon2xxAnswerIsAFailure() {
        respondWith = 503;
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> post("t"));
        assertTrue(e.getMessage().contains("503"), e.getMessage());
    }

    /** The Connection names the only host rows may reach — a receiver cannot bounce them elsewhere. */
    @Test
    void aRedirectIsAFailureAndIsNeverFollowed() {
        respondWith = 307;
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> post("t"));
        assertTrue(e.getMessage().contains("307"), e.getMessage());
        assertTrue(redirectHits.isEmpty(), "the redirect target must never receive the rows");
    }

    // ── exchange: the Action Request wire (ASSURE-ACTION-REQUESTS-1) ────────────────────────────────

    @Test
    void exchangeSendsTheMethodAndKeyAndReturnsTheStatusWithACappedExcerpt() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        server.createContext("/api", ex -> {
            seen.add(ex.getRequestMethod() + " " + ex.getRequestHeaders().getFirst("Idempotency-Key") + " "
                    + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "x".repeat(5000).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(500, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        WebhookSinkTransport.Response r = new HttpWebhookSinkTransport().exchange("PUT", URI.create(base() + "/api"),
                LOOPBACK, null, Duration.ofSeconds(5), "{\"a\":1}", Map.of("Idempotency-Key", "ar-1"), 100);
        assertEquals(500, r.status(), "a non-2xx comes back, it does not throw");
        assertEquals(100, r.bodyExcerpt().length());
        assertEquals(List.of("PUT ar-1 {\"a\":1}"), seen);
    }

    /** A redirect to ANOTHER host comes back as the 3xx itself; the other host is never dialled. */
    @Test
    void exchangeNeverFollowsARedirectToAnotherHost() throws Exception {
        HttpServer other = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> otherHits = new CopyOnWriteArrayList<>();
        other.createContext("/", ex -> {
            otherHits.add(ex.getRequestMethod());
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        other.start();
        try {
            server.createContext("/bounce", ex -> {
                ex.getRequestBody().readAllBytes();
                ex.getResponseHeaders().add("Location", "http://localhost:" + other.getAddress().getPort() + "/steal");
                ex.sendResponseHeaders(302, -1);
                ex.close();
            });
            WebhookSinkTransport.Response r = new HttpWebhookSinkTransport().exchange("POST",
                    URI.create(base() + "/bounce"), LOOPBACK, "s3cret", Duration.ofSeconds(5), "{}", Map.of(), 100);
            assertEquals(302, r.status());
            assertTrue(otherHits.isEmpty(), "the redirect's host must never receive the request: " + otherHits);
        } finally {
            other.stop(0);
        }
    }

    private static final java.net.InetAddress LOOPBACK = java.net.InetAddress.ofLiteral("127.0.0.1");

    /**
     * Pinned: the URL names a host that resolves NOWHERE, and the request still reaches the stub — so the wire
     * connected to the given address and never resolved the name — while the Host header carries the name.
     */
    @Test
    void exchangeConnectsToThePinnedAddressAndSendsTheNameAsHost() throws Exception {
        List<String> hosts = new CopyOnWriteArrayList<>();
        server.createContext("/pinned", ex -> {
            hosts.add(ex.getRequestHeaders().getFirst("Host"));
            ex.getRequestBody().readAllBytes();
            byte[] out = "ok".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        int port = server.getAddress().getPort();
        WebhookSinkTransport.Response r = new HttpWebhookSinkTransport().exchange("POST",
                URI.create("http://tickets.invalid:" + port + "/pinned"), LOOPBACK, null, Duration.ofSeconds(5), "{}",
                Map.of(), 100);
        assertEquals(200, r.status());
        assertEquals("ok", r.bodyExcerpt());
        assertEquals(List.of("tickets.invalid:" + port), hosts);
    }

    @Test
    void aHeaderValueCarryingCrLfIsRefused() {
        assertThrows(java.io.IOException.class, () -> new HttpWebhookSinkTransport().exchange("POST",
                URI.create(base() + "/api"), LOOPBACK, "t\r\nX-Evil: 1", Duration.ofSeconds(5), "{}", Map.of(), 10));
    }

    @Test
    void thisModuleRegistersTheTransportTheEngineDiscovers() {
        assertInstanceOf(HttpWebhookSinkTransport.class,
                ServiceLoader.load(WebhookSinkTransport.class).findFirst().orElse(null));
    }
}
