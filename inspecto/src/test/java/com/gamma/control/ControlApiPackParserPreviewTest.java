package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.parse.ParseResult;
import com.gamma.parse.ParserPlugin;
import com.gamma.parse.Parsers;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-HTTP proof of operator decision D4 (parser-plugins-trust-design.md slice P4, 2026-09-25):
 * {@code POST /parsers/{id}/preview} over a parser a Job Pack contributed requires {@code canAuthorWorkbench}
 * — the capability that authors Pipelines — because it runs third-party code over caller-chosen bytes. A
 * built-in's preview stays open. An {@link Authenticator} is forced via {@link Authenticators#forTest} to
 * stand in for Professional/Enterprise; without a Subject the gate is a no-op (Personal).
 *
 * <p>Every refusal is paired with the probe that would otherwise succeed: the SAME request by an author
 * passes, and the same viewer's request against a built-in passes. The pack parser counts its preview
 * calls, so a 403 is also shown to happen BEFORE any pack code runs.
 */
class ControlApiPackParserPreviewTest {

    private static final String BODY = "{\"sample_text\":\"CALL,C001,2026-09-25\\n\"}";

    /** {@code Bearer author} → canAuthorWorkbench; {@code Bearer viewer} → authenticated, no capabilities. */
    private static final Authenticator FAKE = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if ("Bearer author".equals(auth)) return Optional.of(new Subject("author", Set.of("canAuthorWorkbench")));
        if ("Bearer viewer".equals(auth)) return Optional.of(new Subject("viewer", Set.of()));
        return Optional.empty();
    };

    private final HttpClient client = HttpClient.newHttpClient();
    private final AtomicInteger packPreviews = new AtomicInteger();

    @BeforeEach
    void registerAPackParser() {
        Parsers.register(new ParserPlugin() {
            @Override public String id() { return "acme_cdr"; }
            @Override public String label() { return "Acme CDR"; }
            @Override public boolean hierarchical() { return false; }
            @Override public List<com.gamma.config.spec.FieldSpec> grammarSchema() { return List.of(); }
            @Override public ParseResult preview(byte[] sample, Map<String, Object> grammar) {
                packPreviews.incrementAndGet();
                return new ParseResult.Table(List.of("line"), List.of(Map.of("line", new String(sample))), 1, 0);
            }
        }, "acme-1.jar");
    }

    @AfterEach
    void tearDown() {
        Authenticators.forTest(null);
        Parsers.deregister("acme-1.jar");
    }

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path cfg) throws Exception {
        Path toon = TestConfigs.csv(cfg, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    @Test
    void aViewerIsRefusedAPackParsersPreviewBeforeAnyPackCodeRuns(@TempDir Path cfg) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(cfg)) {
            HttpResponse<String> denied = send(c.port, "/parsers/acme_cdr/preview", "Bearer viewer");
            assertEquals(403, denied.statusCode(), denied.body());
            JsonNode err = V1Body.of(denied.body()).get("error");
            assertEquals("PERMISSION_DENIED", err.get("errorCode").asText());
            assertTrue(err.toString().contains("canAuthorWorkbench"), err.toString());
            assertEquals(0, packPreviews.get(), "the gate runs before the pack's preview");
        }
    }

    @Test
    void anAuthorMayPreviewTheSamePackParser(@TempDir Path cfg) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(cfg)) {
            HttpResponse<String> ok = send(c.port, "/parsers/acme_cdr/preview", "Bearer author");
            assertEquals(200, ok.statusCode(), ok.body());
            assertEquals("table", V1Body.of(ok.body()).get("kind").asText());
            assertEquals(1, packPreviews.get());
        }
    }

    @Test
    void aBuiltinsPreviewStaysOpenToTheSameViewer(@TempDir Path cfg) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(cfg)) {
            HttpResponse<String> ok = send(c.port, "/parsers/delimited/preview", "Bearer viewer");
            assertEquals(200, ok.statusCode(), ok.body());
            assertEquals("table", V1Body.of(ok.body()).get("kind").asText());
        }
    }

    @Test
    void anUnknownParserIsStill404ForAViewer(@TempDir Path cfg) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(cfg)) {
            assertEquals(404, send(c.port, "/parsers/no_such_parser/preview", "Bearer viewer").statusCode());
        }
    }

    @Test
    void withNoAuthenticatorThePackPreviewAnswersAsEveryGateDoesOnPersonal(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg)) {
            HttpResponse<String> ok = send(c.port, "/parsers/acme_cdr/preview", null);
            assertEquals(200, ok.statusCode(), ok.body());
        }
    }

    private HttpResponse<String> send(int port, String path, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json").POST(BodyPublishers.ofString(BODY));
        if (bearer != null) b.header("Authorization", bearer);
        return client.send(b.build(), BodyHandlers.ofString());
    }
}
