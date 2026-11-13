package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@code GET /config/pipeline/{name}/impact} and the dependents gate it backs on
 * {@code DELETE /config/{type}/{name}} (Catalog lifecycle: "no dependent check on origin delete"), over
 * real HTTP.
 *
 * <p>Covers the read's gate order (write-root 503 → unsafe name 422 → subdir jail 403 → missing file
 * 404 → 200), every dependent kind including the two transitive Studio hops, and the delete gate's
 * three outcomes: no dependents ⇒ 200, dependents ⇒ 409 with the file kept, {@code ?force=true} ⇒ 200.
 */
class ControlApiConfigImpactTest {

    private final HttpClient client = HttpClient.newHttpClient();

    /** Inactive, so the active-409 never fires first and mask the gate under test. */
    private static final String ORDERS_TOON = """
            name: Orders Feed
            active: false
            dirs:
              poll: in
              database: out
            processing:
              threads: 1
            """;

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        else System.clearProperty("assist.write.root");
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .GET().build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .DELETE().build(), BodyHandlers.ofString());
    }

    /** The origin under test: file name {@code orders_feed_pipeline.toon}, registered id {@code orders_feed}. */
    private static void writeOrigin(Path root) throws Exception {
        Files.writeString(root.resolve("orders_feed_pipeline.toon"), ORDERS_TOON);
    }

    private static void writeComponent(Path root, String dir, String id, String body) throws Exception {
        Path d = root.resolve("registry").resolve(dir);
        Files.createDirectories(d);
        Files.writeString(d.resolve(id + ".toon"), body);
    }

    // ---- the read's gates -------------------------------------------------------------------

    @Test
    void disabledWhenNoWriteRootConfigured(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            assertEquals(503, get(c.port, "/config/pipeline/anything/impact").statusCode());
        }
    }

    @Test
    void unsafeNameIs422(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(422, get(c.port, "/config/pipeline/a..b/impact").statusCode());
        }
    }

    @Test
    void escapingSubdirIs403(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(403, get(c.port, "/config/pipeline/x/impact?subdir=../escape").statusCode());
        }
    }

    @Test
    void missingFileIs404(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(404, get(c.port, "/config/pipeline/ghost/impact").statusCode());
        }
    }

    @Test
    void badLimitIs400(@TempDir Path cfg, @TempDir Path root) throws Exception {
        writeOrigin(root);
        try (Ctx c = open(cfg, root)) {
            assertEquals(400, get(c.port, "/config/pipeline/orders_feed/impact?limit=zero").statusCode());
            assertEquals(400, get(c.port, "/config/pipeline/orders_feed/impact?limit=0").statusCode());
        }
    }

    // ---- what it finds ----------------------------------------------------------------------

    @Test
    void anUnreferencedOriginReportsNothing(@TempDir Path cfg, @TempDir Path root) throws Exception {
        writeOrigin(root);
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = get(c.port, "/config/pipeline/orders_feed/impact");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode out = V1Body.of(r.body());
            assertEquals("orders_feed", out.get("pipeline").asText(),
                    "the id is the DERIVED one ('Orders Feed' → orders_feed), not the file name");
            assertEquals(0, out.get("total").asInt());
            assertFalse(out.get("truncated").asBoolean());
        }
    }

    @Test
    void findsEveryDirectBindingKind(@TempDir Path cfg, @TempDir Path root) throws Exception {
        writeOrigin(root);
        Files.writeString(root.resolve("daily_enrich.toon"), """
                name: daily
                transform: SELECT 1
                triggers:
                  on_pipeline: orders_feed
                """);
        Files.createDirectories(root.resolve("jobs"));
        Files.writeString(root.resolve("jobs").resolve("nightly_job.toon"), """
                job:
                  name: nightly
                  on_pipeline: orders_feed
                """);
        writeComponent(root, "expectations", "rowcount", "name: rowcount\ntarget: orders_feed\n");
        writeComponent(root, "decision-rules", "routing", "name: routing\ntarget: orders_feed\n");
        writeComponent(root, "datasets", "orders_ds", "name: orders_ds\nphysicalRef: orders_feed/database\n");

        try (Ctx c = open(cfg, root)) {
            JsonNode out = V1Body.of(get(c.port, "/config/pipeline/orders_feed/impact").body());
            assertEquals(5, out.get("total").asInt(), out.toString());
            JsonNode d = out.get("dependents");
            assertEquals("triggers.on_pipeline", d.get("enrichment").get(0).get("via").asText());
            assertEquals("nightly_job", d.get("job").get(0).get("name").asText());
            assertEquals("rowcount", d.get("expectation").get(0).get("name").asText());
            assertEquals("routing", d.get("decision-rule").get(0).get("name").asText());
            assertEquals("physicalRef", d.get("dataset").get(0).get("via").asText());
        }
    }

    /** CENSUS-EXPECTATION-TARGET-PATH-1: an Expectation target is a data path; its first segment names the store. */
    @Test
    void anExpectationTargetingTheStoreByPathCounts(@TempDir Path cfg, @TempDir Path root) throws Exception {
        writeOrigin(root);
        writeComponent(root, "expectations", "band", "name: band\ntarget: orders_feed/database\n");
        writeComponent(root, "expectations", "other", "name: other\ntarget: orders_feedx/database\n");
        try (Ctx c = open(cfg, root)) {
            JsonNode out = V1Body.of(get(c.port, "/config/pipeline/orders_feed/impact").body());
            assertEquals(1, out.get("total").asInt(), out.toString());
            assertEquals("band", out.get("dependents").get("expectation").get(0).get("name").asText());
        }
    }

    /**
     * The loader reads the whole config tree, so the scan must too: an Enrichment and a Job in a sub-folder
     * are dependents (the demo keeps {@code orders/orders_daily_enrich.toon}). A copy under {@code .history/}
     * or a reserved state dir is not a live config and is not counted.
     */
    @Test
    void findsDependentsInSubfoldersAndSkipsReservedCopies(@TempDir Path cfg, @TempDir Path root) throws Exception {
        writeOrigin(root);
        String enrich = """
                name: daily
                transform: SELECT 1
                triggers:
                  on_pipeline: orders_feed
                """;
        String job = """
                job:
                  name: nightly
                  type: pipeline
                  on_pipeline: orders_feed
                """;
        Files.createDirectories(root.resolve("orders"));
        Files.writeString(root.resolve("orders").resolve("daily_enrich.toon"), enrich);
        Files.createDirectories(root.resolve("ops").resolve("nightly"));
        Files.writeString(root.resolve("ops").resolve("nightly").resolve("nightly_job.toon"), job);
        Files.createDirectories(root.resolve("orders").resolve(".history"));
        Files.writeString(root.resolve("orders").resolve(".history").resolve("old_enrich.toon"), enrich);
        Files.createDirectories(root.resolve("pending-changes"));
        Files.writeString(root.resolve("pending-changes").resolve("held_job.toon"), job);
        // A document with no job: section is still read at the top level.
        Files.writeString(root.resolve("ops").resolve("flat_job.toon"), "name: flat\non_pipeline: orders_feed\n");

        try (Ctx c = open(cfg, root)) {
            JsonNode out = V1Body.of(get(c.port, "/config/pipeline/orders_feed/impact").body());
            assertEquals(3, out.get("total").asInt(), out.toString());
            JsonNode d = out.get("dependents");
            assertEquals("daily_enrich", d.get("enrichment").get(0).get("name").asText());
            assertEquals("flat_job", d.get("job").get(0).get("name").asText());
            assertEquals("nightly_job", d.get("job").get(1).get("name").asText());
        }
    }

    @Test
    void followsTheDatasetWidgetDashboardChain(@TempDir Path cfg, @TempDir Path root) throws Exception {
        writeOrigin(root);
        writeComponent(root, "datasets", "orders_ds", "name: orders_ds\nphysicalRef: orders_feed/database\n");
        writeComponent(root, "widgets", "orders_chart", "name: orders_chart\ndatasetId: orders_ds\n");
        writeComponent(root, "dashboards", "ops", """
                name: ops
                tiles[1]{widgetId}:
                  orders_chart
                """);

        try (Ctx c = open(cfg, root)) {
            JsonNode out = V1Body.of(get(c.port, "/config/pipeline/orders_feed/impact").body());
            JsonNode d = out.get("dependents");
            assertEquals(3, out.get("total").asInt(), out.toString());
            assertEquals("orders_chart", d.get("widget").get(0).get("name").asText(),
                    "a widget on the affected dataset is the first transitive hop");
            assertEquals("ops", d.get("dashboard").get(0).get("name").asText(),
                    "a dashboard tiling that widget is the second");
        }
    }

    @Test
    void aByNameEnrichmentReferenceCounts(@TempDir Path cfg, @TempDir Path root) throws Exception {
        writeOrigin(root);
        // No trigger — only a by-name reference, which resolves at RUN time and so would otherwise
        // surface as a job failure long after the delete.
        Files.writeString(root.resolve("lookup_enrich.toon"), """
                name: lookup
                transform: SELECT 1
                references:
                  orders:
                    ref: orders_feed
                """);
        try (Ctx c = open(cfg, root)) {
            JsonNode out = V1Body.of(get(c.port, "/config/pipeline/orders_feed/impact").body());
            assertEquals(1, out.get("total").asInt(), out.toString());
            assertEquals("references.orders.ref",
                    out.get("dependents").get("enrichment").get(0).get("via").asText());
        }
    }

    @Test
    void aJobNamingThePipelineInACommaListCounts(@TempDir Path cfg, @TempDir Path root) throws Exception {
        writeOrigin(root);
        // JobConfig.onPipelines splits `on_pipeline` on commas (PIPELINE-LINK-GAPS-1 b); one Job is one dependent
        // even when the list names the pipeline twice, and a member that only starts with the id is not it.
        Files.createDirectories(root.resolve("jobs"));
        Files.writeString(root.resolve("jobs").resolve("both_job.toon"), """
                job:
                  name: both
                  type: pipeline
                  on_pipeline: "fx_feed, orders_feed ,orders_feed"
                """);
        Files.writeString(root.resolve("jobs").resolve("other_job.toon"), """
                job:
                  name: other
                  type: pipeline
                  on_pipeline: "fx_feed, orders_feed_v2"
                """);
        try (Ctx c = open(cfg, root)) {
            JsonNode out = V1Body.of(get(c.port, "/config/pipeline/orders_feed/impact").body());
            assertEquals(1, out.get("total").asInt(), out.toString());
            assertEquals("both_job", out.get("dependents").get("job").get(0).get("name").asText());
        }
    }

    @Test
    void limitTruncatesButReportsTheTrueTotal(@TempDir Path cfg, @TempDir Path root) throws Exception {
        writeOrigin(root);
        for (int i = 0; i < 4; i++) {
            writeComponent(root, "expectations", "exp" + i, "name: exp" + i + "\ntarget: orders_feed\n");
        }
        try (Ctx c = open(cfg, root)) {
            JsonNode out = V1Body.of(get(c.port, "/config/pipeline/orders_feed/impact?limit=2").body());
            assertEquals(4, out.get("total").asInt(), "the TRUE total, not the truncated size");
            assertTrue(out.get("truncated").asBoolean());
            assertEquals(2, out.get("dependents").get("expectation").size());
        }
    }

    @Test
    void anotherPipelinesDependentsAreNotCounted(@TempDir Path cfg, @TempDir Path root) throws Exception {
        writeOrigin(root);
        writeComponent(root, "expectations", "other", "name: other\ntarget: some_other_feed\n");
        // targetType: dataset means this rule does not target a pipeline at all.
        writeComponent(root, "decision-rules", "onds",
                "name: onds\ntargetType: dataset\ntarget: orders_feed\n");
        try (Ctx c = open(cfg, root)) {
            JsonNode out = V1Body.of(get(c.port, "/config/pipeline/orders_feed/impact").body());
            assertEquals(0, out.get("total").asInt(), out.toString());
        }
    }

    // ---- the delete gate --------------------------------------------------------------------

    @Test
    void deleteWithNoDependentsStillSucceeds(@TempDir Path cfg, @TempDir Path root) throws Exception {
        writeOrigin(root);
        try (Ctx c = open(cfg, root)) {
            assertEquals(200, delete(c.port, "/config/pipeline/orders_feed").statusCode());
            assertFalse(Files.exists(root.resolve("orders_feed_pipeline.toon")));
        }
    }

    @Test
    void deleteWithDependentsIs409AndKeepsTheFile(@TempDir Path cfg, @TempDir Path root) throws Exception {
        writeOrigin(root);
        writeComponent(root, "datasets", "orders_ds", "name: orders_ds\nphysicalRef: orders_feed/database\n");
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = delete(c.port, "/config/pipeline/orders_feed");
            assertEquals(409, r.statusCode(), r.body());
            assertTrue(r.body().contains("dataset/orders_ds"), "the 409 names what would break: " + r.body());
            assertTrue(Files.exists(root.resolve("orders_feed_pipeline.toon")), "the origin is kept");
        }
    }

    @Test
    void forceDeletesOverDependents(@TempDir Path cfg, @TempDir Path root) throws Exception {
        writeOrigin(root);
        writeComponent(root, "datasets", "orders_ds", "name: orders_ds\nphysicalRef: orders_feed/database\n");
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = delete(c.port, "/config/pipeline/orders_feed?force=true");
            assertEquals(200, r.statusCode(), r.body());
            assertFalse(Files.exists(root.resolve("orders_feed_pipeline.toon")));
        }
    }

    @Test
    void theActiveGateStillFiresBeforeTheDependentsGate(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Files.writeString(root.resolve("orders_feed_pipeline.toon"), ORDERS_TOON.replace("active: false", "active: true"));
        writeComponent(root, "datasets", "orders_ds", "name: orders_ds\nphysicalRef: orders_feed/database\n");
        try (Ctx c = open(cfg, root)) {
            // force bypasses the DEPENDENTS gate only — an active pipeline is still never deleted.
            HttpResponse<String> r = delete(c.port, "/config/pipeline/orders_feed?force=true");
            assertEquals(409, r.statusCode(), r.body());
            assertTrue(r.body().contains("is active"), r.body());
            assertTrue(Files.exists(root.resolve("orders_feed_pipeline.toon")));
        }
    }

    @Test
    void nonPipelineTypesAreUnaffected(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Files.writeString(root.resolve("notes.toon"), "name: notes\n");
        try (Ctx c = open(cfg, root)) {
            assertEquals(200, delete(c.port, "/config/meta/notes").statusCode());
        }
    }
}
