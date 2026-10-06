package com.gamma.control;

import com.gamma.util.egress.EgressPolicy;
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
        final List<String> addresses = new CopyOnWriteArrayList<>();

        @Override public void post(URI url, String t, Duration d, String b, Map<String, String> h) {
            throw new UnsupportedOperationException();
        }

        /**
         * The test NETWORK: every checked address is routed to the loopback stub on the URL's port — loopback itself
         * is never allowlistable, so the target is given a private address by {@link #NET} and this wire records the
         * address it was handed (the real wire's pinning is pinned in inspecto-notify-channels).
         */
        @Override public Response exchange(String method, URI url, java.net.InetAddress to, String token,
                                           Duration timeout, String json, Map<String, String> headers, int cap)
                throws Exception {
            addresses.add(to.getHostAddress());
            URI pinned = new URI(url.getScheme(), null, "127.0.0.1", url.getPort(), url.getPath(), null, null);
            HttpRequest.Builder b = HttpRequest.newBuilder(pinned).timeout(timeout)
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

    /** The simulated DNS: the target's name answers a private address; anything else resolves for real. */
    static final EgressPolicy.Resolver NET = h -> switch (h) {
        case "tickets.test" -> new java.net.InetAddress[] {java.net.InetAddress.ofLiteral("10.9.0.5")};
        case "moved.test" -> new java.net.InetAddress[] {java.net.InetAddress.ofLiteral("10.9.0.6")};
        case "rebound.test" -> new java.net.InetAddress[] {java.net.InetAddress.ofLiteral("127.0.0.1")};
        default -> EgressPolicy.SYSTEM.resolve(h);
    };
    private EgressPolicy.Resolver priorResolver;

    @BeforeEach
    void network() {
        priorResolver = ActionDispatcher.resolver;
        ActionDispatcher.resolver = NET;
    }

    @AfterEach
    void stop() {
        ActionDispatcher.resolver = priorResolver;
        System.clearProperty(ActionDispatcher.PROP_BACKOFF_MS);
        server.stop(0);
        other.stop(0);
    }

    private WebhookSink.Endpoint endpoint() {
        return new WebhookSink.Endpoint(URI.create("http://tickets.test:" + server.getAddress().getPort() + "/api"),
                null, Duration.ofSeconds(5));
    }

    /** The target's private address is denied by default; the Space allowlists the name. */
    private static void allowLoopback(Path root) throws Exception {
        Files.writeString(root.resolve(EgressRoutes.FILE), dev.toonformat.jtoon.JToon.encode(Map.of("allow", List.of("tickets.test"))));
    }

    private static String approved(Path root) throws Exception {
        allowLoopback(root);
        return approvedWithoutAllowlist(root);
    }

    private static String approvedWithoutAllowlist(Path root) throws Exception {
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

    /** An allowlisted NAME that re-resolves to loopback (DNS rebinding) is still refused. */
    @Test
    void anAllowlistedNameReboundToLoopbackIsRefused(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        Files.writeString(root.resolve(EgressRoutes.FILE), dev.toonformat.jtoon.JToon.encode(Map.of("allow", List.of("rebound.test"))));
        String id = approvedWithoutAllowlist(root);
        ActionDispatcher.run(root, id, new WebhookSink.Endpoint(URI.create("http://rebound.test:"
                + server.getAddress().getPort() + "/api"), null, Duration.ofSeconds(5)), new LoopbackWire());
        assertTrue(keys.isEmpty(), "nothing sent");
        assertTrue(String.valueOf(((Map<?, ?>) read(root, id).get("lastResponse")).get("error")).contains("loopback"));
    }

    /** Deny by default: a private target with no allowlist entry is never dialled, and fails without a retry. */
    @Test
    void aPrivateTargetIsRefusedByTheEgressPolicyUnlessAllowlisted(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        String id = approvedWithoutAllowlist(root);
        ActionDispatcher.run(root, id, endpoint(), new LoopbackWire());
        Map<String, Object> rec = read(root, id);
        assertEquals("failed", rec.get("status"));
        assertTrue(keys.isEmpty(), "nothing sent");
        assertTrue(String.valueOf(((Map<?, ?>) rec.get("lastResponse")).get("error")).contains("private"));
        assertEquals(1, rec.get("attempts"), "an egress refusal is not retried");
    }

    @Test
    void eachAttemptRecordsTheCheckedAddressItConnectedTo(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        String id = approved(root);
        failFirst = 1;
        LoopbackWire wire = new LoopbackWire();
        ActionDispatcher.run(root, id, endpoint(), wire);
        Map<String, Object> rec = read(root, id);
        assertEquals(List.of("10.9.0.5", "10.9.0.5"), wire.addresses);
        List<?> log = (List<?>) rec.get("attemptLog");
        assertEquals(2, log.size());
        assertEquals("10.9.0.5", ((Map<?, ?>) log.get(1)).get("address"));
        assertEquals(500, ((Map<?, ?>) log.get(0)).get("status"));
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
