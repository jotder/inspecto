package com.gamma.control;

import com.gamma.entitystore.LinkAnalysisSettings;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-EMBED-FRAME-HEADERS-1 (slice S5) over real HTTP: {@code -Dcontrol.frameDeny} (default OFF) flips the frame headers of EVERY
 * non-embed response and never the embed ones; the embed viewer page is always strict (no third-party link, CSP, no-referrer) and
 * frameable only by the origins a Space lists.
 */
class ControlApiFrameHeadersTest {

    private static final String SPA = "<!doctype html><html><head><base href=\"/\">"
            + "<link href=\"https://fonts.gstatic.com\" rel=\"preconnect\">"
            + "<link href=\"https://fonts.googleapis.com/css2?family=IBM+Plex+Mono\" rel=\"stylesheet\">"
            + "<link rel=\"stylesheet\" href=\"styles-ABC.css\" media=\"print\" onload=\"this.media='all'\">"
            + "<style>@font-face{font-family:'IBM Plex Mono';src:url(https://fonts.gstatic.com/s/ibmplexmono/v20/x.woff2) format('woff2');}"
            + "@font-face{font-family:'Local';src:url(assets/local.woff2);}body{margin:0}</style>"
            + "</head><body><app-root></app-root></body></html>";

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @AfterEach
    void reset() {
        for (String p : List.of(FrameHeaders.FLAG, "ui.dir", "assist.write.root")) System.clearProperty(p);
        com.gamma.spi.auth.EmbedShellSpaces.register(null);
    }

    /** The viewer token "abc.def" is a live grant of the Space at {@code dir/space}; any other token is not a live grant. */
    private static void tokenBelongsTo(Path root) {
        com.gamma.spi.auth.EmbedShellSpaces.register(t -> "abc.def".equals(t) ? java.util.Optional.of(root) : java.util.Optional.empty());
    }

    private Ctx open(Path dir, List<String> frameAncestors) throws Exception {
        Path ui = dir.resolve("ui");
        Files.createDirectories(ui.resolve("assets"));
        Files.writeString(ui.resolve("index.html"), SPA);
        Files.writeString(ui.resolve("assets").resolve("app.js"), "console.log('x');");
        Path root = dir.resolve("space");
        Files.createDirectories(root);
        if (frameAncestors != null) writeAllowList(root, frameAncestors);
        tokenBelongsTo(root);
        System.setProperty("ui.dir", ui.toString());
        System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("ui.dir");
            System.clearProperty("assist.write.root");
        }
    }

    private static void writeAllowList(Path root, List<String> frameAncestors) throws Exception {
        Files.createDirectories(root);
        new LinkAnalysisSettings(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                new LinkAnalysisSettings.Embed(null, null, null, frameAncestors)).write(root.resolve(LinkAnalysisSettings.FILE));
    }

    private HttpResponse<String> get(Ctx c, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + path)).GET().build(), BodyHandlers.ofString());
    }

    private static Map<String, String> framing(HttpResponse<?> r) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String h : List.of("Content-Security-Policy", "X-Frame-Options"))
            m.put(h, r.headers().firstValue(h).orElse(null));
        return m;
    }

    private static final List<String> NON_EMBED = List.of("/", "/index.html", "/assets/app.js", "/some/deep/link", "/api/v1/health",
            "/api/v1/no-such-route", "/api/v1/public/dashboards/x", "/missing.png");
    private static final List<String> EMBED = List.of("/embed/inv/abc.def", "/api/v1/public/investigations/abc.def");

    @Test
    void theFlagShipsOffAndPutsNoFrameHeaderOnAnyNonEmbedResponse(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, null)) {
            for (String p : NON_EMBED) {
                Map<String, String> f = framing(get(c, p));
                assertNull(f.get("Content-Security-Policy"), p);
                assertNull(f.get("X-Frame-Options"), p);
            }
        }
    }

    @Test
    void flippingTheFlagDeniesFramingOnEveryNonEmbedResponseAndNeverChangesAnEmbedOne(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, List.of("https://portal.example"))) {
            Map<String, Map<String, String>> embedOff = new LinkedHashMap<>();
            for (String p : EMBED) embedOff.put(p, framing(get(c, p)));

            System.setProperty(FrameHeaders.FLAG, "true");
            for (String p : NON_EMBED) {
                HttpResponse<String> r = get(c, p);
                Map<String, String> f = framing(r);
                assertEquals("frame-ancestors 'none'", f.get("Content-Security-Policy"), p + " (" + r.statusCode() + ")");
                assertEquals("DENY", f.get("X-Frame-Options"), p);
            }
            for (String p : EMBED)
                assertEquals(embedOff.get(p), framing(get(c, p)), "the flag never changes an embed response: " + p);

            System.clearProperty(FrameHeaders.FLAG);   // and back: read per request, no restart
            for (String p : NON_EMBED) assertNull(framing(get(c, p)).get("X-Frame-Options"), p);
        }
    }

    @Test
    void theEmbedViewerPageIsStrictHasNoThirdPartyLinkAndIsFrameableOnlyByAListedOrigin(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, null)) {
            HttpResponse<String> r = get(c, "/embed/inv/abc.def");
            assertEquals(200, r.statusCode());
            String csp = r.headers().firstValue("Content-Security-Policy").orElseThrow();
            assertTrue(csp.contains("default-src 'none'") && csp.contains("script-src 'self'") && csp.contains("connect-src 'self'"), csp);
            assertTrue(csp.endsWith("frame-ancestors 'none'"), "no list = nobody: " + csp);
            assertEquals("DENY", r.headers().firstValue("X-Frame-Options").orElse(null));
            assertEquals("no-referrer", r.headers().firstValue("Referrer-Policy").orElse(null));
            assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(null));
            assertFalse(r.body().contains("http"), "no third-party link, not even a preconnect: " + r.body());
            assertFalse(r.body().contains("IBM Plex Mono"), "the inlined external @font-face is dropped: " + r.body());
            assertTrue(r.body().contains("assets/local.woff2") && r.body().contains("body{margin:0}"), "a same-origin font and other CSS stay: " + r.body());
            assertFalse(r.body().contains("onload"), "no inline handler the policy would refuse: " + r.body());
            assertTrue(r.body().contains("styles-ABC.css") && !r.body().contains("media=\"print\""), r.body());
        }
        try (Ctx c = open(dir, List.of("https://portal.example", "https://portal.example:8443"))) {
            HttpResponse<String> r = get(c, "/embed/inv/abc.def");
            assertTrue(r.headers().firstValue("Content-Security-Policy").orElseThrow()
                    .endsWith("frame-ancestors https://portal.example https://portal.example:8443"), r.headers().toString());
            assertNull(r.headers().firstValue("X-Frame-Options").orElse(null), "X-Frame-Options cannot name an allow-list");
            System.setProperty(FrameHeaders.FLAG, "true");   // the host-wide flag still leaves the allow-list alone
            assertTrue(get(c, "/embed/inv/abc.def").headers().firstValue("Content-Security-Policy").orElseThrow().contains("https://portal.example"));
            // the SPA's own index (not an embed path) is denied with the flag on, whatever the allow-list says
            assertEquals("DENY", get(c, "/").headers().firstValue("X-Frame-Options").orElse(null));
        }
    }

    @Test
    void anOriginThatIsNotAnOriginIsDroppedNeverWidened(@TempDir Path dir) throws Exception {
        List<String> bad = new ArrayList<>(List.of("*", "https://*.example.com", "https://a.example/path", "http://portal.example",
                "https://a.example; script-src *", "javascript:alert(1)", "https://user@a.example", "'self'", "https://a.example b"));
        bad.add("https://ok.example");
        try (Ctx c = open(dir, bad)) {
            String csp = get(c, "/embed/inv/abc.def").headers().firstValue("Content-Security-Policy").orElseThrow();
            assertTrue(csp.endsWith("frame-ancestors https://ok.example"), csp);
        }
        assertEquals("http://localhost:4200", LinkAnalysisSettings.Embed.normaliseOrigin("HTTP://localhost:4200"));
        assertNull(LinkAnalysisSettings.Embed.normaliseOrigin("http://evil.example"));
    }

    @Test
    void aTokenIsFramedOnlyByItsOwnSpacesAllowListNeverTheUnionAndAnUnresolvedTokenByNobody(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, List.of("https://a.example"))) {
            Path a = dir.resolve("space"), b = dir.resolve("space-b");
            writeAllowList(b, List.of("https://b.example"));
            com.gamma.spi.auth.EmbedShellSpaces.register(t -> switch (t) {
                case "abc.def" -> java.util.Optional.of(a);
                case "bbb.bbb" -> java.util.Optional.of(b);
                default -> java.util.Optional.empty();
            });
            String ofA = get(c, "/embed/inv/abc.def").headers().firstValue("Content-Security-Policy").orElseThrow();
            String ofB = get(c, "/embed/inv/bbb.bbb").headers().firstValue("Content-Security-Policy").orElseThrow();
            assertTrue(ofA.endsWith("frame-ancestors https://a.example"), ofA);
            assertTrue(ofB.endsWith("frame-ancestors https://b.example"), ofB);
            for (String forged : List.of("zzz.zzz", "abc", "abc.def/x")) {
                HttpResponse<String> r = get(c, "/embed/inv/" + forged);
                assertTrue(r.headers().firstValue("Content-Security-Policy").orElseThrow().endsWith("frame-ancestors 'none'"), forged);
                assertEquals("DENY", r.headers().firstValue("X-Frame-Options").orElse(null), forged);
            }
        }
    }
}
