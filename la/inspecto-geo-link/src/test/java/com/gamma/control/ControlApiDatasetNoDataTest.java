package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.CollectorService;
import com.gamma.util.DuckDbUtil;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A Link Analysis read over a file-backed Dataset whose store holds nothing yet answers a CLOSED 409 naming the
 * Dataset and its store - never DuckDB's "IO Error: No files found" text, never a server path
 * ({@code DatasetReadFault}). A Dataset with files still reads.
 */
class ControlApiDatasetNoDataTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> get(Ctx c, String route) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + route)).GET().build(),
                BodyHandlers.ofString());
    }

    private HttpResponse<String> post(Ctx c, String route, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + route))
                .method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private static void parquet(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        DuckDbUtil.loadDriver();
        java.io.File db = DuckDbUtil.tempDbFile("nodata_");
        try (java.sql.Connection conn = DuckDbUtil.openConnection(db); java.sql.Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES ('alice','bob',TIMESTAMP '2026-09-01 10:00:00',51.5,-0.1,52.5,13.4,10.0),"
                    + "('bob','carol',TIMESTAMP '2026-09-01 10:01:00',48.8,2.3,40.7,-74.0,20.0)) t(who,other,ts,lat,lon,lat2,lon2,amt)) TO '"
                    + file.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    private static void assertClosed(HttpResponse<String> r, String dataset, String store) throws Exception {
        assertClosed(r, dataset, store, null);
    }

    private static void assertClosed(HttpResponse<String> r, String dataset, String store, String route) throws Exception {
        assertEquals(409, r.statusCode(), r.body());
        String message = new ObjectMapper().readTree(r.body()).at("/error/message").asText();
        assertTrue(message.contains("Dataset '" + dataset + "' has no data yet"), message);
        if (route == null) assertTrue(message.contains(store), "names the store: " + message);
        assertTrue(message.contains("Run the Pipeline"), message);
        for (String leak : List.of("IO Error", "read_parquet", "CREATE VIEW", "LINE 1", "projection failed", ":/", ":\\", "**", System.getProperty("java.io.tmpdir").replace('\\', '/'), "/tmp"))
            assertFalse(r.body().replace('\\', '/').contains(leak), "no '" + leak + "' in: " + r.body());
    }

    @Test
    void anEmptyStoreAnswersAClosed409OnProjectionNeighboursAndMulti(@TempDir Path cfg, @TempDir Path root) throws Exception {
        String store = "nodata_" + System.nanoTime();
        try (Ctx c = open(cfg, root)) {
            new ComponentStore(root.resolve("registry")).write("dataset", "empty_store_ds", Map.of("physicalRef", store));
            String cols = "\"sourceCol\":\"who\",\"targetCol\":\"other\"";
            assertClosed(post(c, "/inv/projection", "{\"dataset\":\"empty_store_ds\"," + cols + "}"), "empty_store_ds", store);
            assertClosed(post(c, "/inv/projection/neighbors", "{\"dataset\":\"empty_store_ds\"," + cols + ",\"value\":\"alice\"}"),
                    "empty_store_ds", store);
            assertClosed(post(c, "/inv/projection/multi", "{\"edges\":[{\"dataset\":\"empty_store_ds\",\"sourceColumn\":\"who\",\"targetColumn\":\"other\"}]}"),
                    "empty_store_ds", store);
        }
    }

    @Test
    void aStoreWithFilesStillReads(@TempDir Path cfg, @TempDir Path root) throws Exception {
        // a legacy Space's data root is the relative directory "database": a uniquely named store, removed at the end
        String store = "hasdata_" + System.nanoTime();
        Path dir = Path.of("database").resolve(store);
        boolean hadDatabase = Files.isDirectory(Path.of("database"));
        try (Ctx c = open(cfg, root)) {
            new ComponentStore(root.resolve("registry")).write("dataset", "files_ds", Map.of("physicalRef", store));
            parquet(dir.resolve("p1.parquet"));
            HttpResponse<String> r = post(c, "/inv/projection", "{\"dataset\":\"files_ds\",\"sourceCol\":\"who\",\"targetCol\":\"other\"}");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode rows = new ObjectMapper().readTree(r.body()).at("/data/rows");
            assertEquals(2, rows.size(), r.body());
        } finally {
            try (Stream<Path> w = Files.walk(dir)) { w.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); } catch (Exception ignore) { }
            if (!hadDatabase) Path.of("database").toFile().delete();
        }
    }

    // ── every other Dataset-reading route goes through the same translation ───────────────────────────────

    /** Every route that reads a Dataset's rows, called over {@code ds}; the key is the route, the value its answer. */
    private Map<String, HttpResponse<String>> callEveryRoute(Ctx c, String ds) throws Exception {
        Map<String, HttpResponse<String>> out = new java.util.LinkedHashMap<>();
        String cols = "\"dataset\":\"" + ds + "\",\"sourceCol\":\"who\",\"targetCol\":\"other\"";
        out.put("/inv/traversal/recursive-paths", post(c, "/inv/traversal/recursive-paths", "{" + cols + ",\"startNode\":\"alice\"}"));
        out.put("/inv/pattern/temporal", post(c, "/inv/pattern/temporal", "{" + cols + ",\"timeCol\":\"ts\",\"mode\":\"burst\",\"minEvents\":2}"));
        out.put("/inv/pattern/branching", post(c, "/inv/pattern/branching",
                "{" + cols + ",\"stages\":[{\"shape\":\"fan-out\",\"minBranches\":1}]}"));
        out.put("/inv/value-measures", get(c, "/inv/value-measures?dataset=" + ds
                + "&sourceCol=who&targetCol=other&name=structuring&valueCol=amt&timeCol=ts&from=2026-09-01&to=2026-09-08"));
        out.put("/geo/projection", post(c, "/geo/projection", "{\"dataset\":\"" + ds + "\",\"latCol\":\"lat\",\"lonCol\":\"lon\"}"));
        out.put("/geo/routes", post(c, "/geo/routes", "{\"dataset\":\"" + ds + "\",\"fromLatCol\":\"lat\",\"fromLonCol\":\"lon\","
                + "\"toLatCol\":\"lat2\",\"toLonCol\":\"lon2\"}"));
        out.put("/inv/reference-rows", post(c, "/inv/reference-rows", "{\"dataset\":\"" + ds + "\",\"keyCol\":\"who\",\"keys\":[\"alice\"]}"));
        out.put("/inv/entity-identities/import", post(c, "/inv/entity-identities/import",
                "{\"dataset\":\"" + ds + "\",\"aCol\":\"who\",\"bCol\":\"other\",\"reason\":\"test\"}"));
        out.put("/inv/investigations", post(c, "/inv/investigations", "{\"purpose\":\"test\",\"id\":\"inv_" + ds + "\",\"dataset\":\"" + ds
                + "\",\"sourceCol\":\"who\",\"targetCol\":\"other\"}"));
        return out;
    }

    @Test
    void everyOtherLaDatasetReadAnswersTheClosed409OnAnEmptyStore(@TempDir Path cfg, @TempDir Path root) throws Exception {
        String store = "nodata_" + System.nanoTime();
        try (Ctx c = open(cfg, root)) {
            new ComponentStore(root.resolve("registry")).write("dataset", "empty_store_ds", Map.of("physicalRef", store));
            callEveryRoute(c, "empty_store_ds").forEach((route, r) -> {
                try {
                    assertClosed(r, "empty_store_ds", store, route);
                } catch (AssertionError | Exception e) {
                    throw new AssertionError(route + ": " + e.getMessage(), e);
                }
            });
        }
    }

    @Test
    void everyOtherLaDatasetReadStillAnswersOverAStoreWithFiles(@TempDir Path cfg, @TempDir Path root) throws Exception {
        String store = "hasdata_" + System.nanoTime();
        Path dir = Path.of("database").resolve(store);
        boolean hadDatabase = Files.isDirectory(Path.of("database"));
        try (Ctx c = open(cfg, root)) {
            new ComponentStore(root.resolve("registry")).write("dataset", "files_ds", Map.of("physicalRef", store));
            parquet(dir.resolve("p1.parquet"));
            callEveryRoute(c, "files_ds").forEach((route, r) -> {
                if (route.endsWith("/import"))   // the columns read; this fixture's columns carry no classification, so its own validation answers
                    assertTrue(r.statusCode() == 422 && r.body().contains("state 'aType'"), route + ": " + r.body());
                else assertEquals(200, r.statusCode(), route + ": " + r.body());
            });
        } finally {
            try (Stream<Path> w = Files.walk(dir)) { w.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); } catch (Exception ignore) { }
            if (!hadDatabase) Path.of("database").toFile().delete();
        }
    }

    @Test
    void aDriverFailureOverAStoreWithFilesIsA422WithThePathScrubbed(@TempDir Path cfg, @TempDir Path root) throws Exception {
        String store = "badfile_" + System.nanoTime();
        Path dir = Path.of("database").resolve(store);
        boolean hadDatabase = Files.isDirectory(Path.of("database"));
        try (Ctx c = open(cfg, root)) {
            new ComponentStore(root.resolve("registry")).write("dataset", "bad_ds", Map.of("physicalRef", store));
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("p1.parquet"), "this is not a parquet file");
            HttpResponse<String> r = post(c, "/inv/projection", "{\"dataset\":\"bad_ds\",\"sourceCol\":\"who\",\"targetCol\":\"other\"}");
            assertEquals(422, r.statusCode(), r.body());
            String body = r.body().replace('\\', '/');
            assertTrue(body.contains("<path>"), "the path is scrubbed to <path>: " + r.body());
            assertFalse(body.contains(store), "no store path: " + r.body());
            for (String leak : List.of(":/", "CREATE VIEW", "LINE 1", "read_parquet"))
                assertFalse(body.contains(leak), "no '" + leak + "' in: " + r.body());
        } finally {
            try (Stream<Path> w = Files.walk(dir)) { w.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); } catch (Exception ignore) { }
            if (!hadDatabase) Path.of("database").toFile().delete();
        }
    }

    @Test
    void anInvestigationMeasureRuleOverAStoreThatEmptiedAnswersTheClosed409(@TempDir Path cfg, @TempDir Path root) throws Exception {
        String store = "emptied_" + System.nanoTime();
        Path dir = Path.of("database").resolve(store);
        boolean hadDatabase = Files.isDirectory(Path.of("database"));
        try (Ctx c = open(cfg, root)) {
            new ComponentStore(root.resolve("registry")).write("dataset", "emptied_ds", Map.of("physicalRef", store));
            parquet(dir.resolve("p1.parquet"));
            HttpResponse<String> created = post(c, "/inv/investigations", "{\"purpose\":\"test\",\"id\":\"case-e\",\"dataset\":\"emptied_ds\","
                    + "\"sourceCol\":\"who\",\"targetCol\":\"other\"}");
            assertEquals(200, created.statusCode(), created.body());
            String rule = "{\"name\":\"%s\",\"severity\":\"INFO\",\"valueMeasure\":{\"name\":\"structuring\",\"valueCol\":\"amt\","
                    + "\"timeCol\":\"ts\",\"from\":\"2026-09-01\",\"to\":\"2026-09-08\"}}";
            HttpResponse<String> bound = post(c, "/inv/investigations/case-e/alert-rules", rule.formatted("r1"));
            assertEquals(200, bound.statusCode(), "over a store with files: " + bound.body());
            Files.delete(dir.resolve("p1.parquet"));   // the store holds nothing now
            assertClosed(post(c, "/inv/investigations/case-e/alert-rules", rule.formatted("r2")), "emptied_ds", store, "alert-rules");
        } finally {
            try (Stream<Path> w = Files.walk(dir)) { w.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); } catch (Exception ignore) { }
            if (!hadDatabase) Path.of("database").toFile().delete();
        }
    }
}
