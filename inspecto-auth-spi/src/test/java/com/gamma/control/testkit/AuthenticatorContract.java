package com.gamma.control.testkit;

import com.gamma.control.Authenticator;
import com.gamma.control.Subject;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The platform's TCK for an {@link Authenticator} (MODULE-REORG-1 P5b): the fail-closed rules every identity provider
 * must keep. It builds a bare {@link HttpExchange} with only an {@code Authorization} header, so it needs no host.
 * An authenticator that cannot be built without host objects is listed in the architecture plan, not mocked.
 *
 * <p>Defects each test catches:
 * <ul>
 *   <li>{@link #noCredentialIsNoSubject} - a missing header that yields a Subject is an open door.</li>
 *   <li>{@link #garbageCredentialsAreNoSubject} - a blank, malformed, non-Bearer, NUL-laden or 100 kB token that yields a
 *       Subject (fail-open) or throws (a 500 that a probe can tell apart from a 401, or an unhandled error that drops
 *       the request).</li>
 *   <li>{@link #aRefusalIsOfConstantShape} - the same garbage always gives the same answer, so the response cannot
 *       be used to learn which part of a credential was almost right.</li>
 *   <li>{@link #concurrentGarbageStaysFailClosed} - shared mutable state (a parser, a cache) that lets one request's
 *       result leak into another's, found by 8 threads x 200 calls.</li>
 * </ul>
 * A positive check (a valid credential yields a Subject) is deliberately NOT here: minting a valid one is
 * implementation-specific and stays in each module's own tests.
 */
public abstract class AuthenticatorContract {

    /** The authenticator under test. */
    protected abstract Authenticator authenticator();

    /** Credentials a correct implementation must refuse. */
    protected List<String> garbageAuthorizationHeaders() {
        return List.of("", " ", "Bearer", "Bearer ", "Bearer    ", "Bearer not-a-token", "Bearer a.b.c",
                "Bearer eyJhbGciOiJub25lIn0.eyJzdWIiOiJhZG1pbiJ9.", "bearer \u0000\u0000",
                "Basic dXNlcjpwYXNz", "Negotiate abc", "Bearer " + "x".repeat(100_000));
    }

    @Test
    void noCredentialIsNoSubject() {
        assertTrue(authenticator().authenticate(new StubExchange(null)).isEmpty(),
                "a request with no Authorization header must not authenticate");
    }

    @Test
    void garbageCredentialsAreNoSubject() {
        Authenticator a = authenticator();
        for (String header : garbageAuthorizationHeaders()) {
            Optional<Subject> s;
            try {
                s = a.authenticate(new StubExchange(header));
            } catch (Throwable t) {
                fail("authenticate() threw " + t + " for header '" + abbreviate(header) + "' - it must answer empty", t);
                return;
            }
            assertTrue(s.isEmpty(), "header '" + abbreviate(header) + "' must not authenticate");
        }
    }

    @Test
    void aRefusalIsOfConstantShape() {
        Authenticator a = authenticator();
        for (String header : garbageAuthorizationHeaders()) {
            assertEquals(a.authenticate(new StubExchange(header)), a.authenticate(new StubExchange(header)),
                    "the answer for header '" + abbreviate(header) + "' must not vary between calls");
        }
    }

    @Test
    void concurrentGarbageStaysFailClosed() throws Exception {
        Authenticator a = authenticator();
        List<String> headers = garbageAuthorizationHeaders();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> results = new java.util.ArrayList<>();
            for (int t = 0; t < 8; t++)
                results.add(pool.submit(() -> {
                    for (int i = 0; i < 200; i++)
                        if (a.authenticate(new StubExchange(headers.get(i % headers.size()))).isPresent()) return false;
                    return true;
                }));
            for (Future<Boolean> f : results)
                assertTrue(f.get(30, TimeUnit.SECONDS), "a garbage credential authenticated under concurrency");
        } finally {
            pool.shutdownNow();
        }
    }

    private static String abbreviate(String s) {
        return s.length() > 40 ? s.substring(0, 40) + "...(" + s.length() + " chars)" : s;
    }

    /** The smallest exchange an Authenticator can be handed: one Authorization header, no network. */
    public static final class StubExchange extends HttpExchange {
        private final Headers request = new Headers();
        private final Map<String, Object> attributes = new LinkedHashMap<>();

        public StubExchange(String authorization) {
            if (authorization != null) request.add("Authorization", authorization);
        }

        @Override public Headers getRequestHeaders() { return request; }
        @Override public Headers getResponseHeaders() { return new Headers(); }
        @Override public URI getRequestURI() { return URI.create("/api/v1/spaces"); }
        @Override public String getRequestMethod() { return "GET"; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() { }
        @Override public InputStream getRequestBody() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream getResponseBody() { return new ByteArrayOutputStream(); }
        @Override public void sendResponseHeaders(int rCode, long responseLength) { }
        @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 0); }
        @Override public int getResponseCode() { return -1; }
        @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 0); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return attributes.get(name); }
        @Override public void setAttribute(String name, Object value) { attributes.put(name, value); }
        @Override public void setStreams(InputStream i, OutputStream o) { }
        @Override public HttpPrincipal getPrincipal() { return null; }
    }
}
