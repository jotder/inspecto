package com.gamma.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.pipeline.PipelineCodec;
import com.gamma.pipeline.PipelineStore;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code PREVIEW-REFERENCE-PATH-UNJAILED-1} over real HTTP: a preview's reference {@code path:} is jailed to
 * {@code PathJail.allowedRoots()} ({@code -Dassist.safety.roots}). Covers both routes that read one —
 * {@code POST /pipelines/authored/{id}/dry-run} (a {@code transform.join} reference; the same resolver serves
 * {@code …/run?to=}) and {@code POST /enrichment/preview} ({@code references.<n>.path}).
 *
 * <p>Every refused probe is a readable CSV with the SAME content as the accepted one, so the 403 is the jail
 * and not a missing file; the marker value {@code North} proves the rows were not returned.
 */
class ControlApiPreviewReferenceJailTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CSV = "id,region\n1,North\n3,South\n";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, String priorRoots) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
            if (priorRoots != null) System.setProperty("assist.safety.roots", priorRoots);
            else System.clearProperty("assist.safety.roots");
        }
    }

    /** Boot with the safety roots = {@code roots} and the write root = {@code roots/wr}. */
    private Ctx open(Path roots) throws Exception {
        Path toon = TestConfigs.csv(roots, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        String prior = System.getProperty("assist.write.root");
        String priorRoots = System.getProperty("assist.safety.roots");
        System.setProperty("assist.safety.roots", roots.toString());
        System.setProperty("assist.write.root", roots.resolve("wr").toString());
        try {
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), priorRoots);
        } finally {
            if (prior != null) System.setProperty("assist.write.root", prior);
            else System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> post(int port, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json")
                .method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private static String fwd(Path p) { return p.toString().replace("\\", "/"); }

    // ── dry-run ─────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static void seedJoinFlow(Path writeRoot, String reference) throws Exception {
        String flow = """
            {"name":"join_flow","active":false,
             "nodes":[{"id":"acq","type":"acquisition"},
                      {"id":"j","type":"transform.join","config":{"reference":"%s","on":"id"}},
                      {"id":"sink","type":"sink.persistent","config":{"store":"joined"}}],
             "edges":[{"from":"acq","rel":"data","to":"j"},{"from":"j","rel":"data","to":"sink"}]}"""
                .formatted(reference);
        new PipelineStore(writeRoot.resolve("flows")).write("join_flow",
                PipelineCodec.fromMap(JSON.readValue(flow, Map.class)));
    }

    private HttpResponse<String> dryRun(Path roots, String reference) throws Exception {
        seedJoinFlow(roots.resolve("wr"), reference);
        try (Ctx c = open(roots)) {
            return post(c.port, "/pipelines/authored/join_flow/dry-run",
                    "{\"sampleRows\":[{\"id\":\"1\"},{\"id\":\"2\"},{\"id\":\"3\"}]}");
        }
    }

    private static void assertJailed(HttpResponse<String> r) {
        assertEquals(403, r.statusCode(), r.body());
        assertTrue(r.body().contains(ErrorCodes.PATH_JAIL_VIOLATION), r.body());
        assertTrue(r.body().contains("outside the allowed roots"), r.body());
        assertFalse(r.body().contains("North"), "the reference file must not be read: " + r.body());
    }

    @Test
    void dryRunReadsAReferenceInsideTheRoots(@TempDir Path roots) throws Exception {
        Path inside = roots.resolve("dim.csv");
        Files.writeString(inside, CSV);
        HttpResponse<String> r = dryRun(roots, fwd(inside));
        assertEquals(200, r.statusCode(), r.body());
        assertEquals(3, V1Body.of(r.body()).get("sinks").get(0).get("rowCount").asInt(), r.body());
    }

    @Test
    void dryRunRefusesAnAbsoluteReferenceOutsideTheRoots(@TempDir Path roots, @TempDir Path elsewhere) throws Exception {
        Path outside = elsewhere.resolve("dim.csv");
        Files.writeString(outside, CSV);
        assertJailed(dryRun(roots, fwd(outside)));
    }

    @Test
    void dryRunRefusesADotDotTraversalOutOfTheRoots(@TempDir Path base) throws Exception {
        Path roots = Files.createDirectories(base.resolve("roots"));
        Files.writeString(base.resolve("dim.csv"), CSV);
        assertJailed(dryRun(roots, fwd(roots) + "/wr/../../dim.csv"));
    }

    @Test
    void dryRunRefusesASymlinkThatLeavesTheRoots(@TempDir Path roots, @TempDir Path elsewhere) throws Exception {
        Path outside = elsewhere.resolve("dim.csv");
        Files.writeString(outside, CSV);
        Path link = roots.resolve("link.csv");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (Exception unsupported) {
            assumeTrue(false, "symlinks unavailable here: " + unsupported);
        }
        assertJailed(dryRun(roots, fwd(link)));
    }

    // ── enrichment preview ───────────────────────────────────────────────────────

    private HttpResponse<String> enrichmentPreview(Path roots, String refPath) throws Exception {
        String body = """
                {"config":{"name":"PREVIEW",
                   "input":{"database":"unused","format":"PARQUET","partitions":["day"]},
                   "output":{"database":"unused","format":"PARQUET","partitions":["day"]},
                   "references":{"dim":{"path":"%s","format":"CSV"}},
                   "transform":"SELECT i.id, d.region FROM input i LEFT JOIN dim d ON i.id = CAST(d.id AS VARCHAR)"},
                 "sampleRows":[{"id":"1"},{"id":"2"}]}""".formatted(refPath);
        try (Ctx c = open(roots)) {
            return post(c.port, "/enrichment/preview", body);
        }
    }

    @Test
    void enrichmentPreviewReadsAReferenceInsideTheRoots(@TempDir Path roots) throws Exception {
        Path inside = roots.resolve("dim.csv");
        Files.writeString(inside, CSV);
        HttpResponse<String> r = enrichmentPreview(roots, fwd(inside));
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("North"), r.body());
    }

    @Test
    void enrichmentPreviewRefusesAReferenceOutsideTheRoots(@TempDir Path roots, @TempDir Path elsewhere) throws Exception {
        Path outside = elsewhere.resolve("dim.csv");
        Files.writeString(outside, CSV);
        assertJailed(enrichmentPreview(roots, fwd(outside)));
    }

    @Test
    void enrichmentPreviewRefusesADotDotTraversalOutOfTheRoots(@TempDir Path base) throws Exception {
        Path roots = Files.createDirectories(base.resolve("roots"));
        Files.writeString(base.resolve("dim.csv"), CSV);
        assertJailed(enrichmentPreview(roots, fwd(roots) + "/../dim.csv"));
    }
}
