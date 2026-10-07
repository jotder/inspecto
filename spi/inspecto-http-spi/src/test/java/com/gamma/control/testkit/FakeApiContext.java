package com.gamma.control.testkit;

import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.Handler;
import com.gamma.spi.auth.Subject;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The platform test kit's {@link ApiContext} (MODULE-REORG-1 P5a): a lightweight in-memory double with NO control plane,
 * NO engine and NO inspecto-processor. It records every route a {@link com.gamma.spi.http.RouteModule} registers
 * (method, pattern, whether the handler was wrapped by {@link ApiContext#withCapability}) and lets a test dispatch a
 * request to a handler in-memory, optionally as a fake {@link Subject}.
 *
 * <p>It provides only what the SPI itself defines. Anything a module needs from the HOST (an engine, the Space registry,
 * a store) is not here and is not faked: a module that reaches for it during {@code register()} cannot be driven by this
 * kit, and that is a finding about the module, not a gap to paper over with a mock.
 */
public final class FakeApiContext implements ApiContext {

    /** One registered route. {@code capability} is non-null when the handler was wrapped by {@code withCapability}. */
    public record Route(String method, String pattern, Handler handler, String capability, boolean stub) {
        public boolean gated() { return capability != null; }
        public String key() { return method + " " + pattern; }
    }

    private final List<Route> routes = new ArrayList<>();
    private final Path root;
    private Set<String> registeredFeatures = Set.of();
    private Set<String> disabledFeatures = Set.of();

    public FakeApiContext() { this(null); }

    /** {@code root} answers both {@link #writeRoot()} and {@link #dataRoot()} (null = writes disabled, as in a real host). */
    public FakeApiContext(Path root) { this.root = root; }

    public FakeApiContext registeredFeatures(Set<String> f) { this.registeredFeatures = Set.copyOf(f); return this; }
    public FakeApiContext disabledFeatures(Set<String> f) { this.disabledFeatures = Set.copyOf(f); return this; }

    /** Every route registered so far, in registration order (stubs included; see {@link Route#stub()}). */
    public List<Route> routes() { return List.copyOf(routes); }

    private void add(String method, String pattern, Handler h, boolean stub) {
        routes.add(new Route(method, pattern, h, h instanceof ApiContext.Gated g ? g.capability() : null, stub));
    }

    @Override public void get(String pattern, Handler h) { add("GET", pattern, h, false); }
    @Override public void post(String pattern, Handler h) { add("POST", pattern, h, false); }
    @Override public void put(String pattern, Handler h) { add("PUT", pattern, h, false); }
    @Override public void patch(String pattern, Handler h) { add("PATCH", pattern, h, false); }
    @Override public void delete(String pattern, Handler h) { add("DELETE", pattern, h, false); }
    @Override public void stub(String method, String pattern, Handler h) { add(method, pattern, h, true); }

    @Override public boolean hasRoute(String method, String pattern) {
        return routes.stream().anyMatch(r -> !r.stub() && r.method().equals(method) && r.pattern().equals(pattern));
    }

    @Override public Set<String> registeredFeatures() { return registeredFeatures; }
    @Override public Set<String> disabledFeatures() { return disabledFeatures; }
    @Override public Path writeRoot() { return root; }
    @Override public Path dataRoot() { return root; }

    @Override public Map<String, Object> body(HttpExchange ex) { return ((StubExchange) ex).body; }
    @Override public byte[] rawBody(HttpExchange ex) { return rawBody(ex, Integer.MAX_VALUE); }
    @Override public byte[] rawBody(HttpExchange ex, int maxBytes) {
        return ((StubExchange) ex).body.toString().getBytes(StandardCharsets.UTF_8);
    }
    @Override public Replayed replay(HttpExchange outer, String method, String path, byte[] body,
                                     Map<String, String> headers, Map<String, Object> attrs) {
        throw new UnsupportedOperationException("FakeApiContext cannot replay: that needs the real dispatcher");
    }

    /** Dispatch with no Subject (the Personal-edition shape: no capability check runs). */
    public Object dispatch(String method, String path, Map<String, Object> body) throws Exception {
        return dispatch(method, path, body, null);
    }

    /**
     * Run the first non-stub route matching {@code METHOD path} (the concrete path, no {@code /api/v1} prefix), as
     * {@code subject} when non-null: {@code withCapability} then enforces the capability exactly as the host does.
     */
    public Object dispatch(String method, String path, Map<String, Object> body, Subject subject) throws Exception {
        StubExchange ex = new StubExchange(method, path, body);
        try {
            if (subject != null) ApiContext.attr(ex, ATTR_SUBJECT, subject);
            for (Route r : routes) {
                if (r.stub() || !r.method().equals(method)) continue;
                Matcher m = Pattern.compile(r.pattern()).matcher(path);
                if (m.matches()) return r.handler().handle(ex, m);
            }
            throw new AssertionError("no route registered for " + method + " " + path);
        } finally {
            ApiContext.dropAttrScope(ex);
        }
    }

    /** A request that has no network behind it. */
    public static class StubExchange extends HttpExchange {
        public final String method;
        public final URI uri;
        public final Map<String, Object> body;
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final Map<String, Object> attributes = new LinkedHashMap<>();

        public StubExchange(String method, String path, Map<String, Object> body) {
            this.method = method;
            this.uri = URI.create("/api/v1" + path);
            this.body = body == null ? Map.of() : body;
        }

        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public URI getRequestURI() { return uri; }
        @Override public String getRequestMethod() { return method; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() { }
        @Override public InputStream getRequestBody() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream getResponseBody() { return new ByteArrayOutputStream(); }
        @Override public void sendResponseHeaders(int code, long length) throws IOException { }
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
