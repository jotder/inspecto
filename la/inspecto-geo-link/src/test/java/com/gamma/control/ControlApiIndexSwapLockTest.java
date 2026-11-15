package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.alert.ScoreOutputDirs;
import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.consignment.DbConsignmentOutputStore;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.job.SnapshotSwapLock;
import com.gamma.la.api.IndexRoutes;
import com.gamma.la.storage.IndexBuilder;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
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
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SNAPSHOT-SWAP-READER-RESIDUALS-1: an index build's relation is rendered - and its store's files pinned - when the build
 * is SUBMITTED, on the request thread, and the build reads it later on a worker. A snapshot swap of the store in between
 * renamed the pinned file away and the build FAILED with "No files found". The worker now renders the relation again and
 * holds the store until the build has read it, so the swap waits for the build.
 */
class ControlApiIndexSwapLockTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OWNER = "Bearer owner";
    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void reset() {
        Authenticators.forTest(null);
        IndexRoutes.forTest(null);
    }

    private static void write(Path file, String select) throws Exception {
        Files.createDirectories(file.getParent());
        DuckDbUtil.loadDriver();
        java.io.File db = DuckDbUtil.tempDbFile("idx_swap_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement()) {
            st.execute("COPY (" + select + ") TO '" + file.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json").header("Authorization", OWNER);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode data(HttpResponse<String> r, int expected) throws Exception {
        assertEquals(expected, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    @Test
    void aBuildReadsItsDatasetHeldWhenASwapLandsAfterItWasSubmitted(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(ex -> OWNER.equals(ex.getRequestHeaders().getFirst("Authorization"))
                ? Optional.of(new Subject("analyst-1", Set.of("canBuildLinkIndex"))) : Optional.empty());
        DbConsignmentOutputStore registry = DbConsignmentOutputStore.open("jdbc:duckdb:");
        ConsignmentOutputStores.use(registry);   // as in production: the relation pins an explicit file list
        String storeName = "idx_swap_" + System.nanoTime();
        Path store = Path.of("database").resolve(storeName);   // under the control plane's default data root
        boolean hadDatabase = Files.isDirectory(Path.of("database"));
        write(store.resolve("snap-1.parquet"), "SELECT * FROM (VALUES ('A','B'), ('B','C')) v(s, t)");
        AtomicReference<Throwable> writerFailed = new AtomicReference<>();
        Thread writer = new Thread(() -> {   // publishes a one-link snapshot: write invisibly, hide the old, reveal the new
            try {
                Path tmp = store.resolve("snap-2.parquet.tmp");
                write(tmp, "SELECT * FROM (VALUES ('X','Y')) v(s, t)");
                ScoreOutputDirs.swapIn(store, tmp, "snap-2.parquet", "*.parquet");
            } catch (Throwable t) {
                writerFailed.set(t);
            }
        });
        IndexRoutes.forTest(req -> {   // the worker's build, after the submit pinned the file and before the build reads it
            writer.start();
            long deadline = System.nanoTime() + 30_000_000_000L;
            while (writer.getState() != Thread.State.TERMINATED && !SnapshotSwapLock.hasQueuedThreads(store)) {
                if (System.nanoTime() > deadline) throw new IllegalStateException("the writer neither finished nor waited");
                try { Thread.sleep(5); } catch (InterruptedException e) { throw new IllegalStateException(e); }
            }
            return IndexBuilder.build(req);
        });
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", root.toString());
        CollectorService svc = null;
        ControlApi api = null;
        try {
            svc = new CollectorService(List.of(pipe), 3600, 1);
            api = new ControlApi(svc, 0);
            api.start();
            Files.writeString(root.resolve("link-analysis.toon"), "index:\n  enabled: true\n");
            new ComponentStore(root.resolve("registry")).write("dataset", "f_ds",
                    Map.of("physicalRef", storeName, "owner", "analyst-1", "shares", List.of()));

            JsonNode started = data(send(api.port(), "POST", "/inv/index/builds",
                    "{\"dataset\":\"f_ds\",\"sourceCol\":\"s\",\"targetCol\":\"t\"}"), 202);
            String id = started.get("buildId").asText();
            JsonNode done = null;
            long end = System.nanoTime() + 60_000_000_000L;
            while (System.nanoTime() < end) {
                done = data(send(api.port(), "GET", "/inv/index/builds/" + id, null), 200);
                String st = done.get("status").asText();
                if (!st.equals("QUEUED") && !st.equals("RUNNING")) break;
                Thread.sleep(10);
            }
            writer.join(30_000);

            assertEquals("COMPLETED", done.get("status").asText(), done.toString());
            assertEquals(2, done.at("/result/edges").asInt(), "the build read the snapshot it pinned: " + done);
            assertNull(writerFailed.get(), "the swap landed once the build had read: " + writerFailed.get());
            assertTrue(Files.exists(store.resolve("snap-2.parquet")) && !Files.exists(store.resolve("snap-1.parquet")));
        } finally {
            if (api != null) api.close();
            if (svc != null) svc.close();
            System.clearProperty("assist.write.root");
            ConsignmentOutputStores.use(null);
            registry.close();
            com.gamma.util.TarUtil.deleteTree(store);
            if (!hadDatabase) Path.of("database").toFile().delete();
        }
    }
}
