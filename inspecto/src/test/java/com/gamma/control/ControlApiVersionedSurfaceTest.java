package com.gamma.control;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * API-5 (2026-07-25): {@code /api/v1} is the only API surface. This pins the contract that replaced the
 * unversioned legacy aliases and their sunset machinery (the {@code Deprecation}/{@code Sunset} headers,
 * {@code -Dapi.legacy.routes=off} and {@code inspecto_legacy_api_requests_total} are all gone):
 *
 * <ul>
 *   <li>the route table answers under {@code /api/v1} — and nowhere else;</li>
 *   <li>{@code /health}, {@code /ready}, {@code /metrics}, {@code /metrics/acquisition} stay unversioned,
 *       because health checks and metric scrapers have no v1 semantics;</li>
 *   <li>a bare business path is no longer an API route (route patterns are registered version-free, so
 *       this is the guard in {@code routeDispatch}, not an accident of pattern matching);</li>
 *   <li>an {@code /api/…} path that is not {@code /api/v1} is an unmigrated client and gets a JSON 404 —
 *       never the 200 {@code text/html} SPA shell that the static fallback would otherwise hand it.</li>
 * </ul>
 */
class ControlApiVersionedSurfaceTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static HttpResponse<String> send(ControlApi api, String method, String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://localhost:" + api.port() + path))
                .method(method, HttpRequest.BodyPublishers.noBody()).build(), BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(ControlApi api, String path) throws Exception {
        return send(api, "GET", path);
    }

    private interface ApiCase { void run(ControlApi api) throws Exception; }

    /** Runs {@code body} against a started control plane over a throwaway single-pipeline service. */
    private static void withApi(Path cfg, ApiCase body) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
        try {
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            try {
                body.run(api);
            } finally {
                api.close();
            }
        } finally {
            svc.close();
        }
    }

    @Test
    void v1IsTheOnlyApiSurface(@TempDir Path cfg) throws Exception {
        withApi(cfg, api -> {
            HttpResponse<String> v1 = get(api, "/api/v1/collectors");
            assertEquals(200, v1.statusCode(), v1.body());
            assertTrue(v1.body().contains("\"data\""), "v1 responses are envelope-shaped: " + v1.body());

            // The retired unversioned alias. The route pattern "^/collectors$" still matches this path —
            // only the dispatch guard stops it — so this assertion is the actual retirement.
            HttpResponse<String> bare = get(api, "/collectors");
            assertEquals(404, bare.statusCode(), "the unversioned alias must be retired");
            assertTrue(bare.body().contains("/api/v1"), bare.body());
        });
    }

    @Test
    void noSunsetSignallingRemains(@TempDir Path cfg) throws Exception {
        withApi(cfg, api -> {
            for (String path : List.of("/api/v1/collectors", "/collectors", "/health")) {
                HttpResponse<String> r = get(api, path);
                assertTrue(r.headers().firstValue("Deprecation").isEmpty(),
                        "no surface is 'deprecated' any more: " + path);
                assertTrue(r.headers().firstValue("Sunset").isEmpty(), "Sunset signalling is gone: " + path);
            }
            HttpResponse<String> metrics = get(api, "/metrics");
            assertEquals(200, metrics.statusCode());
            assertFalse(metrics.body().contains("inspecto_legacy_api_requests_total"),
                    "the sunset metric is retired along with the surface it measured");
        });
    }

    @Test
    void infraProbesStayUnversioned(@TempDir Path cfg) throws Exception {
        withApi(cfg, api -> {
            for (String probe : List.of("/health", "/ready", "/metrics", "/metrics/acquisition")) {
                assertEquals(200, get(api, probe).statusCode(), probe + " must answer unversioned");
            }
            // Raw text/plain, not an envelope — the whole reason these are exempt.
            assertFalse(get(api, "/metrics").body().contains("\"data\""), "/metrics is not envelope-shaped");
        });
    }

    @Test
    void unmigratedApiPrefixGetsJsonNotTheSpaShell(@TempDir Path cfg) throws Exception {
        withApi(cfg, api -> {
            for (String path : List.of("/api", "/api/collectors", "/api/v2/collectors")) {
                HttpResponse<String> r = get(api, path);
                assertEquals(404, r.statusCode(), path);
                assertTrue(r.body().contains("/api/v1"), path + " → " + r.body());
                assertFalse(r.body().toLowerCase().contains("<!doctype"), path + " must not get the SPA shell");
            }
        });
    }

    @Test
    void bareWritesAreRejected(@TempDir Path cfg) throws Exception {
        withApi(cfg, api -> {
            // A GET on a bare path may legitimately be an Angular deep link (served by the static
            // fallback when a UI is configured); a write never is.
            for (String method : List.of("POST", "PUT", "DELETE")) {
                assertEquals(404, send(api, method, "/collectors").statusCode(),
                        method + " on an unversioned path must not reach the route table");
            }
        });
    }
}
