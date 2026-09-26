package com.gamma.control;

import com.gamma.pipeline.exec.WebhookSink;
import com.gamma.pipeline.exec.WebhookSinkTransport;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ActionDispatcher}'s attempt loop against a real in-process {@link HttpServer}: retries under one
 * idempotency key, the terminal statuses, redirect refusal and the per-attempt integrity check.
 *
 * <p>The wire here is {@link LoopbackWire}, a test double of the Professional transport
 * ({@code HttpWebhookSinkTransport}, which lives downstream in inspecto-notify-channels and cannot be on this
 * module's classpath) with the same {@code Redirect.NEVER} client; that transport's own redirect behaviour is
 * pinned by {@code HttpWebhookSinkTransportTest#exchangeNeverFollowsARedirectToAnotherHost}.
 */
class ActionDispatcherTest {

    /** JDK HttpClient, redirects NEVER — the shape of the Professional transport, over plain http for the stub. */
    static class LoopbackWire implements WebhookSinkTransport {
        private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

        @Override public void post(URI url, String t, Duration d, String b, Map<String, String> h) {
            throw new UnsupportedOperationException();
        }

        @Override public Response exchange(String method, URI url, String token, Duration timeout, String json,
                                           Map<String, String> headers, int cap) throws Exception {
            HttpRequest.Builder b = HttpRequest.newBuilder(url).timeout(timeout)
                    .header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
            headers.forEach(b::header);
            HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
            String body = r.body() == null ? "" : r.body();
            return new Response(r.statusCode(), body.length() > cap ? body.substring(0, cap) : body);
        }
    }

    private HttpServer server, other;
    private final List<String> keys = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> otherHits = new CopyOnWriteArrayList<>();
    private final AtomicInteger accepted = new AtomicInteger();
    private volatile int failFirst;
    private volatile int failWith = 500;

    @BeforeEach
    void start() throws Exception {
        System.setProperty(ActionDispatcher.PROP_BACKOFF_MS, "0");
        other = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        other.createContext("/", ex -> {
            otherHits.add(ex.getRequestMethod());
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        other.start();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", ex -> {
            keys.add(String.valueOf(ex.getRequestHeaders().getFirst("Idempotency-Key")));
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int status = keys.size() <= failFirst ? failWith : 200;
            if (status / 100 == 3)
                ex.getResponseHeaders().add("Location", "http://localhost:" + other.getAddress().getPort() + "/steal");
            if (status == 200) accepted.incrementAndGet();
            byte[] out = ("answer-" + status).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        System.clearProperty(ActionDispatcher.PROP_BACKOFF_MS);
        server.stop(0);
        other.stop(0);
    }

    private WebhookSink.Endpoint endpoint() {
        return new WebhookSink.Endpoint(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api"),
                null, Duration.ofSeconds(5));
    }

    private static String approved(Path root) throws Exception {
        Map<String, Object> r = ActionRequests.draft("hook", "https://hooks.example.test/api", "POST",
                Map.of("incident", "inc-1"), null, "inc-1", null, "manual", "author-1", "user", null, 168);
        ActionRequests.transition(r, ActionRequests.PENDING, "author-1");
        ActionRequests.transition(r, ActionRequests.APPROVED, "checker-1");
        ActionRequests.save(root, r);
        return (String) r.get("id");
    }

    private static Map<String, Object> read(Path root, String id) throws Exception {
        return ActionRequests.read(root, id);
    }

    @Test
    void retriesUnderOneKeyAndIsAcceptedExactlyOnce(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        String id = approved(root);
        failFirst = 2;
        ActionDispatcher.run(root, id, endpoint(), new LoopbackWire());
        Map<String, Object> rec = read(root, id);
        assertEquals("succeeded", rec.get("status"));
        assertEquals(3, rec.get("attempts"));
        assertEquals(List.of(id, id, id), keys, "the SAME idempotency key on every attempt");
        assertEquals(1, accepted.get(), "exactly one delivery accepted");
        assertEquals("{\"incident\":\"inc-1\"}", bodies.get(0));
        assertEquals(200, ((Map<?, ?>) rec.get("lastResponse")).get("status"));
    }

    @Test
    void aTargetThatKeepsFailingEndsFailedAfterMaxAttempts(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        String id = approved(root);
        failFirst = 99;
        ActionDispatcher.run(root, id, endpoint(), new LoopbackWire());
        Map<String, Object> rec = read(root, id);
        assertEquals("failed", rec.get("status"));
        assertEquals(ActionDispatcher.maxAttempts(), rec.get("attempts"));
        assertEquals(ActionDispatcher.maxAttempts(), keys.size());
        Map<?, ?> last = (Map<?, ?>) rec.get("lastResponse");
        assertEquals(500, last.get("status"));
        assertEquals("answer-500", last.get("bodyExcerpt"));
    }

    @Test
    void aClientErrorIsNotRetried(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        String id = approved(root);
        failFirst = 99;
        failWith = 400;
        ActionDispatcher.run(root, id, endpoint(), new LoopbackWire());
        assertEquals("failed", read(root, id).get("status"));
        assertEquals(1, keys.size());
    }

    /** A redirect to another host fails the request at once; the other host never hears of it. */
    @Test
    void aRedirectToAnotherHostIsRefusedAndNotRetried(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        String id = approved(root);
        failFirst = 99;
        failWith = 302;
        ActionDispatcher.run(root, id, endpoint(), new LoopbackWire());
        Map<String, Object> rec = read(root, id);
        assertEquals("failed", rec.get("status"));
        assertEquals(1, rec.get("attempts"), "a redirect is not retried");
        assertTrue(otherHits.isEmpty(), "the redirect's host must never be dialled: " + otherHits);
        assertTrue(String.valueOf(((Map<?, ?>) rec.get("lastResponse")).get("error")).contains("redirect"));
    }

    @Test
    void aTamperedApprovedRecordIsNeverSent(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        String id = approved(root);
        Path f = root.resolve(ActionRequests.DIR).resolve(id + ".json");
        Files.writeString(f, Files.readString(f).replace("inc-1", "inc-9"));
        ActionDispatcher.run(root, id, endpoint(), new LoopbackWire());
        assertTrue(keys.isEmpty(), "nothing sent");
        assertTrue(ActionRequests.invalid(read(root, id)), "and the forgery was not re-signed");
    }
}
