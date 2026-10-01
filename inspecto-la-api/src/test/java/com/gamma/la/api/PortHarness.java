package com.gamma.la.api;

import com.gamma.control.ApiContext;
import com.gamma.control.Handler;
import com.gamma.la.core.DatasetProvider;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A test harness that needs NO control plane and NO engine: a proxy {@link ApiContext} that records the routes a module
 * registers, a stub {@link HttpExchange}, and a fake {@link DatasetProvider}. Link Analysis routes run through it against
 * the ports alone (LA separation D-1 step 5b/6).
 */
final class PortHarness {

    private final Map<String, Handler> routes = new LinkedHashMap<>();
    private final Map<String, Pattern> patterns = new LinkedHashMap<>();
    final Path root;
    final ApiContext api;

    PortHarness(Path root) {
        this.root = root;
        this.api = (ApiContext) Proxy.newProxyInstance(ApiContext.class.getClassLoader(), new Class<?>[]{ApiContext.class},
                (proxy, m, args) -> {
                    switch (m.getName()) {
                        case "get", "post", "put", "patch", "delete" -> {
                            String key = m.getName().toUpperCase() + " " + args[0];
                            routes.put(key, (Handler) args[1]);
                            patterns.put(key, Pattern.compile((String) args[0]));
                            return null;
                        }
                        case "writeRoot", "dataRoot" -> {
                            return PortHarness.this.root;
                        }
                        case "body" -> {
                            return ((StubExchange) args[0]).body;
                        }
                        case "hasRoute" -> {
                            return false;
                        }
                        default -> {
                            Class<?> r = m.getReturnType();
                            return r == boolean.class ? (Object) false : r == int.class ? (Object) 0 : r == long.class ? (Object) 0L : null;
                        }
                    }
                });
    }

    /** Call a registered route: {@code METHOD /concrete/path}, with a JSON-ish body map. */
    Object call(String method, String path, Map<String, Object> body) throws Exception {
        for (Map.Entry<String, Pattern> e : patterns.entrySet()) {
            if (!e.getKey().startsWith(method + " ")) continue;
            Matcher m = e.getValue().matcher(path);
            if (m.matches()) return routes.get(e.getKey()).handle(new StubExchange(method, path, body), m);
        }
        throw new AssertionError("no route registered for " + method + " " + path);
    }

    /** A fake Dataset registry: id -> columns. {@code run} answers the columns of the Dataset the request names. */
    static final class FakeDatasets implements DatasetProvider {
        final Map<String, List<String>> columnsByDataset;
        final List<String> ran = new ArrayList<>();

        FakeDatasets(Map<String, List<String>> columnsByDataset) {
            this.columnsByDataset = columnsByDataset;
        }

        @Override public Optional<Map<String, Object>> dataset(Path writeRoot, String id) {
            return columnsByDataset.containsKey(id) ? Optional.of(Map.of("id", id)) : Optional.empty();
        }

        @Override public List<Entry> datasets(Path writeRoot) {
            return columnsByDataset.keySet().stream().map(n -> new Entry(n, Map.of())).toList();
        }

        @Override public String relationSql(Map<String, Object> dataset, Path dataRoot, Path writeRoot) {
            return "SELECT * FROM fake_" + dataset.get("id");
        }

        @Override public Result run(Request req) {
            ran.add(req.datasetName());
            List<Column> cols = columnsByDataset.getOrDefault(req.datasetName(), List.of()).stream()
                    .map(c -> new Column(c, "string", "dimension", null)).toList();
            return new Result(cols, List.of(), 0, false, 0);
        }

        @Override public Result run(Request req, com.gamma.sql.SqlSandboxPolicy policy) { return run(req); }

        @Override public Result run(Request req, com.gamma.sql.SqlSandboxPolicy policy, java.time.ZoneId tz) { return run(req); }

        @Override public Result runPlanned(String name, String relationSql, com.gamma.sql.SqlSandboxPolicy policy, Planner planner) {
            throw new UnsupportedOperationException("not needed by these tests");
        }

        @Override public String predicate(Object filter) { return "TRUE"; }
    }

    /** A request that has no network behind it. */
    static final class StubExchange extends HttpExchange {
        final String method;
        final URI uri;
        final Map<String, Object> body;
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final Map<String, Object> attributes = new LinkedHashMap<>();

        StubExchange(String method, String path, Map<String, Object> body) {
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
