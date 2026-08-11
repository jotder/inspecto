package com.gamma.control;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ApiContext#v1} is a pure function of the request URI — never of state stamped on the exchange.
 *
 * <h3>Why the invariant is worth a test of its own</h3>
 * {@code v1()} used to read an {@code inspecto.v1} attribute set by {@code ControlApi.normalizePath}.
 * That is only safe while exchange attributes are private to the exchange, and in the JDK they are not
 * unconditionally so. {@code sun.net.httpserver.ExchangeImpl} chooses the map at class-init:
 *
 * <pre>{@code
 * private static final boolean perExchangeAttributes =
 *     !System.getProperty("jdk.httpserver.attributes", "").equals("context");
 * ...
 * this.attributes = perExchangeAttributes ? new ConcurrentHashMap<>()
 *                                         : getHttpContext().getAttributes();
 * }</pre>
 *
 * Where that resolves to the {@link HttpContext} map — an older runtime, or a current one started with
 * {@code -Djdk.httpserver.attributes=context} — every exchange shares one map, because ControlApi serves
 * everything from a single {@code createContext("/")}. The flag then latched TRUE for the life of the
 * server after the first {@code /api/v1} request, and two defects followed: the API-5 guard stopped
 * retiring the unversioned surface, so a deep link to {@code /pipelines} was answered with API JSON
 * instead of the SPA shell; and {@code /health} came back enveloped, quoting the previous request's
 * {@code links.self}.
 *
 * <h3>Why it is asserted here rather than over real HTTP</h3>
 * The scoping is decided by a {@code static final} read once at class-init, so reproducing the fault
 * through a live server would need a JVM started with that property — a separate surefire execution, and
 * a test that silently proves nothing if the flag is ever dropped. Driving {@code v1()} directly with an
 * exchange that carries its own attribute map removes the JDK from the question entirely: a derived
 * {@code v1()} ignores a stamped attribute on <em>every</em> runtime, a latching one does not. That makes
 * this a regression test for the invariant rather than for one JDK's behaviour.
 */
class ApiContextV1DerivationTest {

    /** The exact latch the shared-map semantics produced: a stale marker from an earlier v1 request. */
    @Test
    void aStampedAttributeCannotMakeAnUnversionedPathLookLikeV1() {
        FakeExchange deepLink = new FakeExchange("/pipelines");
        deepLink.setAttribute("inspecto.v1", Boolean.TRUE);

        assertFalse(ApiContext.v1(deepLink),
                "an unversioned path must never be treated as v1, whatever is stamped on the exchange — "
                        + "this is the SPA deep link that was being answered with API JSON");
    }

    /** …and the converse: a genuine v1 request needs no stamp to be recognised. */
    @Test
    void aVersionedPathIsV1WithNoAttributeSetAtAll() {
        assertTrue(ApiContext.v1(new FakeExchange("/api/v1/pipelines")));
        assertTrue(ApiContext.v1(new FakeExchange("/api/v1")), "the bare prefix is the v1 root");
    }

    /**
     * The prefix is matched as a path segment, not a string prefix. {@code /api/v1x} shares its first
     * seven characters with {@code /api/v1} and is a different namespace; treating it as v1 would hand it
     * the envelope and route it into the retired-surface guard on the wrong side.
     */
    @Test
    void theV1PrefixIsASegmentNotACharacterPrefix() {
        assertFalse(ApiContext.v1(new FakeExchange("/api/v1x/collectors")));
        assertFalse(ApiContext.v1(new FakeExchange("/api/v2/collectors")));
        assertFalse(ApiContext.v1(new FakeExchange("/apiv1/collectors")));
        assertFalse(ApiContext.v1(new FakeExchange("/health")), "infra probes are always unversioned");
        assertFalse(ApiContext.v1(new FakeExchange("/")));
    }

    /**
     * The minimum {@link HttpExchange} carrying a URI and its own attribute map (no mocking framework in
     * this build). The map being private to the instance is the point: it models the JDK's <em>fixed</em>
     * behaviour, so the assertions above hold whatever the real runtime does.
     */
    private static final class FakeExchange extends HttpExchange {
        private final URI uri;
        private final Map<String, Object> attributes = new HashMap<>();
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();

        private FakeExchange(String path) { this.uri = URI.create(path); }

        @Override public URI getRequestURI() { return uri; }
        @Override public Object getAttribute(String name) { return attributes.get(name); }
        @Override public void setAttribute(String name, Object value) { attributes.put(name, value); }
        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public String getRequestMethod() { return "GET"; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() { }
        @Override public InputStream getRequestBody() { return InputStream.nullInputStream(); }
        @Override public OutputStream getResponseBody() { return OutputStream.nullOutputStream(); }
        @Override public void sendResponseHeaders(int rCode, long responseLength) { }
        @Override public InetSocketAddress getRemoteAddress() { return null; }
        @Override public int getResponseCode() { return -1; }
        @Override public InetSocketAddress getLocalAddress() { return null; }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public void setStreams(InputStream i, OutputStream o) { }
        @Override public HttpPrincipal getPrincipal() { return null; }
    }
}
