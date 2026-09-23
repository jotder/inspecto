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

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void post(String token) throws Exception {
        new HttpWebhookSinkTransport().post(URI.create(base() + "/ingest"), token, Duration.ofSeconds(5),
                "{\"batch\":1,\"rows\":[{\"id\":1}]}", Map.of("Idempotency-Key", "c-1:webhook:1"));
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

    @Test
    void thisModuleRegistersTheTransportTheEngineDiscovers() {
        assertInstanceOf(HttpWebhookSinkTransport.class,
                ServiceLoader.load(WebhookSinkTransport.class).findFirst().orElse(null));
    }
}
