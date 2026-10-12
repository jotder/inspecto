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

    private HttpResponse<String> post(Ctx c, String route, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + route))
                .method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private static void parquet(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        DuckDbUtil.loadDriver();
        java.io.File db = DuckDbUtil.tempDbFile("nodata_");
        try (java.sql.Connection conn = DuckDbUtil.openConnection(db); java.sql.Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES ('alice','bob'),('bob','carol')) t(who,other)) TO '"
                    + file.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    private static void assertClosed(HttpResponse<String> r, String dataset, String store) throws Exception {
        assertEquals(409, r.statusCode(), r.body());
        String message = new ObjectMapper().readTree(r.body()).at("/error/message").asText();
        assertTrue(message.contains("Dataset '" + dataset + "' has no data yet"), message);
        assertTrue(message.contains(store), "names the store: " + message);
        assertTrue(message.contains("Run the Pipeline"), message);
        for (String leak : List.of("IO Error", "read_parquet", "CREATE VIEW", "LINE 1", "projection failed", ":/", ":\\", "**"))
            assertFalse(r.body().contains(leak), "no '" + leak + "' in: " + r.body());
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
}
