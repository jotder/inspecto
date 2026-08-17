package com.gamma.control;

import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the v4.1.0 UI-hosting additions to {@link ControlApi} (both pure-JDK, no new deps):
 * CORS ({@code -Dcontrol.cors}) and static SPA serving ({@code -Dui.dir}). These prove the operator
 * UI can be served from the same process while the JSON API — including its scoped auth and JSON
 * 404s — is unchanged.
 */
class ControlApiStaticAndCorsTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    /**
     * Start a ControlApi over an empty service with the given system properties in effect during
     * construction (where the constructor reads them). Properties are cleared right after so they
     * never leak to other tests.
     */
    private Ctx open(String uiDir, String cors) throws Exception {
        if (uiDir != null) System.setProperty("ui.dir", uiDir);  else System.clearProperty("ui.dir");
        if (cors  != null) System.setProperty("control.cors", cors); else System.clearProperty("control.cors");
        try {
            CollectorService svc = new CollectorService(List.of(), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("ui.dir");
            System.clearProperty("control.cors");
        }
    }

    /** Lay down a minimal built-SPA dir: index.html + a JS asset. */
    private static Path spaDir(Path dir) throws Exception {
        Path ui = dir.resolve("ui");
        Files.createDirectories(ui.resolve("assets"));
        Files.writeString(ui.resolve("index.html"), "<!doctype html><html><body>Inspecto UI</body></html>");
        Files.writeString(ui.resolve("assets").resolve("app.js"), "console.log('inspecto');");
        return ui;
    }

    private HttpResponse<String> send(int port, String method, String path) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> sendIfNoneMatch(int port, String path, String etag) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("If-None-Match", etag).GET().build();
        return client.send(r, BodyHandlers.ofString());
    }

    private static String acao(HttpResponse<?> r) {
        return r.headers().firstValue("Access-Control-Allow-Origin").orElse(null);
    }

    private static String ctype(HttpResponse<?> r) {
        return r.headers().firstValue("Content-Type").orElse("");
    }

    @Test
    void corsPreflightAnsweredWhenEnabled(@TempDir Path dir) throws Exception {
        try (Ctx c = open(null, "http://localhost:4200")) {
            HttpResponse<String> pre = send(c.port, "OPTIONS", "/runs");
            assertEquals(204, pre.statusCode(), "preflight short-circuits with 204");
            assertEquals("http://localhost:4200", acao(pre), "echoes the configured origin");
            // a real GET also carries the CORS header
            assertEquals("http://localhost:4200", acao(send(c.port, "GET", "/health")));
        }
    }

    @Test
    void noCorsHeadersWhenDisabled(@TempDir Path dir) throws Exception {
        try (Ctx c = open(null, null)) {
            HttpResponse<String> health = send(c.port, "GET", "/health");
            assertEquals(200, health.statusCode());
            assertNull(acao(health), "no CORS header when -Dcontrol.cors is unset");
            // OPTIONS is not short-circuited; it is just an unsupported method on a known path
            assertNotEquals(204, send(c.port, "OPTIONS", "/health").statusCode());
        }
    }

    @Test
    void knownRouteStillReturnsJson(@TempDir Path dir) throws Exception {
        try (Ctx c = open(spaDir(dir).toString(), null)) {
            HttpResponse<String> health = send(c.port, "GET", "/health");
            assertEquals(200, health.statusCode());
            assertTrue(ctype(health).startsWith("application/json"), "API route stays JSON even with a UI dir");
            assertTrue(health.body().contains("\"status\""));
        }
    }

    @Test
    void rootServesIndexHtml(@TempDir Path dir) throws Exception {
        try (Ctx c = open(spaDir(dir).toString(), null)) {
            HttpResponse<String> root = send(c.port, "GET", "/");
            assertEquals(200, root.statusCode());
            assertTrue(ctype(root).startsWith("text/html"));
            assertTrue(root.body().contains("Inspecto UI"), "index.html served at /");
        }
    }

    @Test
    void assetServedWithMimeType(@TempDir Path dir) throws Exception {
        try (Ctx c = open(spaDir(dir).toString(), null)) {
            HttpResponse<String> js = send(c.port, "GET", "/assets/app.js");
            assertEquals(200, js.statusCode());
            assertTrue(ctype(js).startsWith("text/javascript"));
            assertTrue(js.body().contains("inspecto"));
        }
    }

    @Test
    void extensionlessDeepLinkFallsBackToIndex(@TempDir Path dir) throws Exception {
        try (Ctx c = open(spaDir(dir).toString(), null)) {
            HttpResponse<String> deep = send(c.port, "GET", "/dashboard/runs");
            assertEquals(200, deep.statusCode(), "SPA deep link resolves to index.html");
            assertTrue(ctype(deep).startsWith("text/html"));
            assertTrue(deep.body().contains("Inspecto UI"));
        }
    }

    @Test
    void unknownApiPathStaysJson404NotIndex(@TempDir Path dir) throws Exception {
        try (Ctx c = open(spaDir(dir).toString(), null)) {
            // matches the /runs/{n}/commits route → handler 404 (no such pipeline), as JSON
            HttpResponse<String> r = send(c.port, "GET", "/runs/nope/commits");
            assertEquals(404, r.statusCode());
            assertTrue(ctype(r).startsWith("application/json"), "API 404 is JSON, not the SPA shell");
            assertFalse(r.body().contains("<html"), "must not serve index.html for an API path");
        }
    }

    @Test
    void v1ApiPathResolvesToTheJsonRouteNotTheSpaShell(@TempDir Path dir) throws Exception {
        // API-5: the SPA addresses routes as "/api/v1/…". Served same-origin (no proxy) the backend must
        // strip the version prefix and hit the real route, returning JSON — never the index.html shell,
        // which is the regression to guard against when a UI dir is configured.
        try (Ctx c = open(spaDir(dir).toString(), null)) {
            HttpResponse<String> r = send(c.port, "GET", "/runs");
            assertEquals(200, r.statusCode());
            assertTrue(ctype(r).startsWith("application/json"), "/api/v1/* resolves to the JSON route, not index.html");
            assertFalse(r.body().contains("<html"), "must not serve the SPA shell for an API path");
        }
    }

    @Test
    void staticIsPublicAndApiIsReachable(@TempDir Path dir) throws Exception {
        try (Ctx c = open(spaDir(dir).toString(), null)) {
            assertEquals(200, send(c.port, "GET", "/").statusCode(), "SPA shell loads");
            assertEquals(200, send(c.port, "GET", "/runs").statusCode(),
                    "CONTROL route is reachable");
        }
    }

    /**
     * The stale-chunk guard. A UI file used to be served with NO cache directive and NO validator,
     * which is not "do not cache" — a browser then falls back to heuristic freshness and may reuse a
     * chunk without asking. Serving an upgraded UI from the same host:port that way mixes the new
     * index.html with stale chunks, and since Angular reuses short chunk names across builds the
     * stale file can hold a different module than the new graph imports from it, so bootstrap dies on
     * an undefined helper. Pinned because the symptom (a "corrupt bundle") points nowhere near
     * the cause.
     */
    @Test
    void uiFilesCarryANoCacheDirectiveAndAValidator(@TempDir Path dir) throws Exception {
        try (Ctx c = open(spaDir(dir).toString(), null)) {
            for (String path : List.of("/", "/assets/app.js")) {
                HttpResponse<String> r = send(c.port, "GET", path);
                assertEquals("no-cache", r.headers().firstValue("Cache-Control").orElse(null),
                        path + " must tell the browser to revalidate before reuse");
                assertTrue(r.headers().firstValue("ETag").isPresent(),
                        path + " needs a validator, or no-cache costs a full refetch every load");
            }
        }
    }

    @Test
    void unchangedUiFileRevalidatesToABodilessNotModified(@TempDir Path dir) throws Exception {
        try (Ctx c = open(spaDir(dir).toString(), null)) {
            String etag = send(c.port, "GET", "/assets/app.js").headers().firstValue("ETag").orElseThrow();
            HttpResponse<String> again = sendIfNoneMatch(c.port, "/assets/app.js", etag);
            assertEquals(304, again.statusCode(), "an unchanged file revalidates cheaply");
            assertEquals("", again.body(), "a 304 carries no body");
        }
    }

    @Test
    void redeployedUiFileGetsANewEtagAndTheNewBytes(@TempDir Path dir) throws Exception {
        Path ui = spaDir(dir);
        try (Ctx c = open(ui.toString(), null)) {
            String stale = send(c.port, "GET", "/assets/app.js").headers().firstValue("ETag").orElseThrow();
            Files.writeString(ui.resolve("assets").resolve("app.js"), "console.log('inspecto v2 rebuilt');");
            HttpResponse<String> fresh = sendIfNoneMatch(c.port, "/assets/app.js", stale);
            assertEquals(200, fresh.statusCode(), "a replaced file must NOT revalidate as unchanged");
            assertTrue(fresh.body().contains("v2 rebuilt"), "the new bytes are served");
            assertNotEquals(stale, fresh.headers().firstValue("ETag").orElse(null));
        }
    }

    @Test
    void noStaticServingWhenUiDirUnset(@TempDir Path dir) throws Exception {
        try (Ctx c = open(null, null)) {
            // no -Dui.dir → an unmatched GET is a plain JSON 404 (legacy behaviour preserved)
            HttpResponse<String> r = send(c.port, "GET", "/");
            assertEquals(404, r.statusCode());
            assertTrue(ctype(r).startsWith("application/json"));
        }
    }
}
