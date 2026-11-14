package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.alert.ScoreOutputDirs;
import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.consignment.DbConsignmentOutputStore;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.query.QueryExecutorTestSeam;
import com.gamma.service.SpaceManager;
import com.gamma.util.DuckDbUtil;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SNAPSHOT-SWAP-UNLOCKED-READERS-1, the store browser: {@code GET /db/table} pins the store's files when it renders the
 * relation and {@code QueryExecutor} scans them afterwards; a snapshot writer's swap in between renamed the pinned file
 * away and the browse failed with "No files found". The relation is now repinnable, so the executor renders it again.
 */
class ControlApiDbBrowserSwapTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @AfterEach
    void reset() {
        QueryExecutorTestSeam.beforeScan(null);
    }

    /** What every snapshot writer does: write the new file invisibly, hide the old, reveal the new. */
    private static void publish(Path store, int gen) throws Exception {
        Files.createDirectories(store);
        Path tmp = store.resolve("snap-" + gen + ".parquet.tmp");
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT " + gen + " AS gen) TO '" + tmp.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        }
        ScoreOutputDirs.swapIn(store, tmp, "snap-" + gen + ".parquet", "*.parquet");
    }

    @Test
    void aBrowseWhosePinnedFileASwapRemovedReadsThePublishedSnapshot(@TempDir Path root) throws Exception {
        DuckDbUtil.loadDriver();
        Path config = root.resolve("s1").resolve("config");
        Files.createDirectories(config.resolve("inbox"));
        Files.createDirectories(root.resolve("s1").resolve("duckdb"));
        Files.move(TestConfigs.csv(config, PipelineConfigBatchTest.miniSchema()).write(), config.resolve("etl_pipeline.toon"));
        Path links = root.resolve("s1").resolve("data").resolve("links");
        publish(links, 1);

        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        DbConsignmentOutputStore registry = DbConsignmentOutputStore.open("jdbc:duckdb:");
        try {
            spaces.startAll();
            api.start();
            ConsignmentOutputStores.register("s1", registry);   // as in production: the browse pins an explicit file list
            AtomicInteger attempts = new AtomicInteger();
            QueryExecutorTestSeam.beforeScan(() -> {
                try {
                    if (attempts.incrementAndGet() == 1) publish(links, 2);   // between the browse's pin and its scan
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });

            HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + api.port() + "/api/v1/spaces/s1/db/table?name=links")).GET().build(),
                    BodyHandlers.ofString());

            assertEquals(200, r.statusCode(), r.body());
            List<Integer> gens = new ArrayList<>();
            for (JsonNode row : JSON.readTree(r.body()).get("data").get("rows")) gens.add(row.get("gen").asInt());
            assertEquals(List.of(2), gens, "the re-pinned browse shows the published snapshot");
            assertEquals(2, attempts.get());
        } finally {
            ConsignmentOutputStores.unregister("s1");
            api.close();
            spaces.close();
        }
    }
}
