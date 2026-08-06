package com.gamma.control;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
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
 * {@code GET /pipelines/{name}/document} over real HTTP (ELT amendment §5.1) — the Pipeline Document
 * a business reviewer signs off on. The gates: 404 for an unknown pipeline, a Markdown (not JSON)
 * body, the {@code X-Config-Fingerprint} header matching what the body claims, and — the property the
 * whole sign-off story rests on — the fingerprint <b>changing when the config changes</b> and
 * <b>staying stable when it does not</b>.
 */
class ControlApiPipelineDocumentTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, String priorRoots, Path toon)
            implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
            if (priorRoots != null) System.setProperty("assist.safety.roots", priorRoots);
            else System.clearProperty("assist.safety.roots");
        }
    }

    private Ctx open(Path dir, Path writeRoot) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        String prior = System.getProperty("assist.write.root");
        String priorRoots = System.getProperty("assist.safety.roots");
        System.setProperty("assist.safety.roots", dir.toString());
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        else System.clearProperty("assist.write.root");
        try {
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), priorRoots, toon);
        } finally {
            if (prior != null) System.setProperty("assist.write.root", prior);
            else System.clearProperty("assist.write.root");
        }
    }

    private String firstPipeline(int port) throws Exception {
        HttpResponse<String> r = send(port, "GET", "/pipelines");
        return V1Body.of(r.body()).get(0).get("name").asText();
    }

    @Test
    void unknownPipelineIs404(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir)) {
            HttpResponse<String> r = send(c.port, "GET", "/pipelines/ghost/document");
            assertEquals(404, r.statusCode());
        }
    }

    @Test
    void servesMarkdownNotJsonAndNamesThePipeline(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir)) {
            String name = firstPipeline(c.port);
            HttpResponse<String> r = send(c.port, "GET", "/pipelines/" + name + "/document");

            assertEquals(200, r.statusCode());
            assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("text/markdown"),
                    "the document is Markdown, not a JSON envelope");
            assertTrue(r.body().startsWith("# Pipeline: " + name), "titled with the pipeline name");
            assertTrue(r.body().contains("## Steps"), "renders the Step chain");
            assertTrue(r.body().contains("## Guarantees"), "renders the Guarantees section");
        }
    }

    @Test
    void theFingerprintHeaderMatchesTheOneInTheDocumentBody(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir)) {
            String name = firstPipeline(c.port);
            HttpResponse<String> r = send(c.port, "GET", "/pipelines/" + name + "/document");

            String header = r.headers().firstValue("X-Config-Fingerprint").orElse("");
            assertFalse(header.isBlank(), "the fingerprint rides a header so a blob download can read it");
            assertTrue(r.body().contains("| Config fingerprint | `" + header + "` |"),
                    "header and body must agree — a reviewer signs off on the body, the UI reports the header");
        }
    }

    @Test
    void theSameConfigAlwaysProducesTheSameFingerprint(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir)) {
            String name = firstPipeline(c.port);
            HttpResponse<String> a = send(c.port, "GET", "/pipelines/" + name + "/document");
            HttpResponse<String> b = send(c.port, "GET", "/pipelines/" + name + "/document");

            assertEquals(a.body(), b.body(), "regenerating must be deterministic — no timestamp, no ordering drift");
            assertEquals(a.headers().firstValue("X-Config-Fingerprint"),
                    b.headers().firstValue("X-Config-Fingerprint"));
        }
    }

    /**
     * The sign-off property: change the config the pipeline is registered from, and the fingerprint
     * must move. Without this the document could claim to describe a config it no longer matches.
     * (The route re-decodes the registered file on every request, so no service reload is involved —
     * that is itself the "regenerated on demand, never stored" contract.)
     */
    @Test
    void editingTheConfigChangesTheFingerprint(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir)) {
            String name = firstPipeline(c.port);
            String before = send(c.port, "GET", "/pipelines/" + name + "/document")
                    .headers().firstValue("X-Config-Fingerprint").orElseThrow();

            Files.writeString(c.toon, Files.readString(c.toon).replace("active: true", "active: false"));

            HttpResponse<String> r = send(c.port, "GET", "/pipelines/" + name + "/document");
            assertNotEquals(before, r.headers().firstValue("X-Config-Fingerprint").orElseThrow(),
                    "a config change must invalidate the document's fingerprint");
            assertTrue(r.body().contains("| Status | Inactive |"), "and the body reflects the new config");
        }
    }

    @Test
    void secretsInConfigNeverReachTheDocument(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir)) {
            String name = firstPipeline(c.port);
            // a secret-shaped key on the collector section, which projects onto the collect Step
            Files.writeString(c.toon, Files.readString(c.toon) + "collector:\n  password: hunter2\n");

            String body = send(c.port, "GET", "/pipelines/" + name + "/document").body();
            assertFalse(body.contains("hunter2"), "a secret-shaped key must never render its value");
            assertTrue(body.contains("••••"), "it is masked, not silently dropped");
        }
    }

    private HttpResponse<String> send(int port, String method, String path) throws Exception {
        return send(port, method, path, null);
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }
}
