package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentIntegrity;
import com.gamma.pipeline.LinkGraph;
import com.gamma.service.CollectorService;
import com.gamma.service.ReservedConfigPaths;
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
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code GET /config/schema/{name}/columns/{column}/impact} (`SPACE-LINK-CENSUS-1` LC-5) over real HTTP: the read's
 * gate order (write-root 503 → unsafe name 422 → subdir jail 403 → reserved 403 → missing file 404 → blank column
 * 422 → 200), and a planted column rename in a temp copy of the demo Space: the impact read lists exactly the
 * consumers that, once the mapped column is renamed, dangle.
 */
class ControlApiColumnImpactTest {

    private final HttpClient client = HttpClient.newHttpClient();

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

    /** A copy of {@code spaces/demo/config} (no {@code .history}), the Space under test. */
    private static Path demoCopy(Path root) throws Exception {
        Path src = ReactorModules.root().resolve("spaces/demo/config");
        try (Stream<Path> s = Files.walk(src)) {
            for (Path p : s.toList()) {
                Path rel = src.relativize(p);
                if (rel.toString().replace('\\', '/').contains(".history")) continue;
                Path to = root.resolve(rel.toString());
                if (Files.isDirectory(p)) Files.createDirectories(to);
                else Files.copy(p, to);
            }
        }
        return root;
    }

    // ---- the read's gates -------------------------------------------------------------------

    @Test
    void disabledWhenNoWriteRootConfigured(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            assertEquals(503, get(c.port, "/config/schema/orders_schema/columns/GROSS/impact").statusCode());
        }
    }

    @Test
    void unsafeNameIs422(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(422, get(c.port, "/config/schema/a..b/columns/GROSS/impact").statusCode());
        }
    }

    @Test
    void escapingSubdirIs403(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(403, get(c.port, "/config/schema/x/columns/GROSS/impact?subdir=../escape").statusCode());
        }
    }

    @Test
    void aReservedFileIs403(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Files.writeString(root.resolve("roles.toon"), "roles[0]:\n");
        try (Ctx c = open(cfg, root)) {
            assertEquals(403, get(c.port, "/config/schema/roles/columns/GROSS/impact").statusCode());
        }
    }

    @Test
    void missingSchemaIs404(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(404, get(c.port, "/config/schema/ghost_schema/columns/GROSS/impact").statusCode());
        }
    }

    @Test
    void blankColumnIs422(@TempDir Path cfg, @TempDir Path root) throws Exception {
        demoCopy(root);
        try (Ctx c = open(cfg, root)) {
            assertEquals(422, get(c.port, "/config/schema/orders_schema/columns/%20/impact").statusCode());
        }
    }

    // ---- what it finds ----------------------------------------------------------------------

    private static List<String> holders(JsonNode consumers) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : consumers) out.add(n.get("kind").asText() + " " + n.get("name").asText() + " " + n.get("key").asText());
        return out;
    }

    /** What reads the demo's mapped GROSS: every kind of column link but a Query (its text is not analysed). */
    private static final List<String> GROSS_CONSUMERS = List.of(
            "enrichment orders_daily_enrich transform",
            "expectation orders_gross_non_null column",
            "decision-rule orders_high_value_review when.items[0].field",
            "dataset orders_enriched_dataset calculated[0].expr",
            "widget orders_daily_trend controls.y[0].field",
            "widget orders_revenue_by_region controls.y[0].field",
            "reconciliation orders_regional_recon compareColumns[0].column");

    @Test
    void aPlantedColumnRenameBreaksExactlyTheListedConsumers(@TempDir Path cfg, @TempDir Path root) throws Exception {
        demoCopy(root);
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = get(c.port, "/config/schema/orders_schema/columns/gross/impact");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode out = V1Body.of(r.body());
            assertTrue(out.get("mapped").asBoolean(), "GROSS is a mapped column of orders_schema (matched case-insensitively)");
            assertEquals("orders/orders_schema.toon", out.get("path").asText());
            assertEquals(GROSS_CONSUMERS.size(), out.get("total").asInt());
            assertEquals(new java.util.TreeSet<>(GROSS_CONSUMERS), new java.util.TreeSet<>(holders(out.get("consumers"))));
            assertEquals(List.of("enrichment orders_daily_enrich transform", "query orders_by_region text"),
                    holders(out.get("notAnalysed")), "SQL the census could not fully read is named, not hidden");

            HttpResponse<String> none = get(c.port, "/config/schema/orders_schema/columns/NOT_A_COLUMN/impact");
            assertEquals(200, none.statusCode());
            assertFalse(V1Body.of(none.body()).get("mapped").asBoolean());
            assertEquals(0, V1Body.of(none.body()).get("total").asInt());
        }

        // Plant the rename: the mapping now publishes GROSS_AMOUNT. Exactly the listed consumers dangle.
        Path schema = root.resolve("orders/orders_schema.toon");
        String text = Files.readString(schema);
        assertTrue(text.contains("    - name: GROSS\n"), "the demo schema changed shape — re-aim the plant");
        Files.writeString(schema, text.replace("    - name: GROSS\n", "    - name: GROSS_AMOUNT\n"));
        LinkGraph after = LinkGraph.of(root, ReservedConfigPaths::reserved);
        List<String> broken = new ArrayList<>();
        for (LinkGraph.Edge e : after.dangling())
            if (e.toKind().equals("column")) {
                assertEquals("GROSS", e.value().trim().toUpperCase(java.util.Locale.ROOT), "only GROSS broke: " + e);
                broken.add(e.fromKind() + " " + e.fromName() + " " + e.key());
            }
        assertEquals(new java.util.TreeSet<>(GROSS_CONSUMERS), new java.util.TreeSet<>(broken));
        assertEquals(GROSS_CONSUMERS.size(), ComponentIntegrity.brokenColumns(after).size(),
                "metadata_validate reports each one");
    }

    @Test
    void theShippedDemoHasNoDanglingColumnLink(@TempDir Path root) throws Exception {
        demoCopy(root);
        assertEquals(List.of(), ComponentIntegrity.brokenColumns(LinkGraph.of(root, ReservedConfigPaths::reserved)));
    }
}
