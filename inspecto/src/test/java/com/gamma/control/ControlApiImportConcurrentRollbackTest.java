package com.gamma.control;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.BundleExporter;
import com.gamma.service.DataSourceBundle;
import com.gamma.service.ImportJournal;
import com.gamma.service.SpaceManager;
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
 * `IMPORT-RESIDUALS-1` (3) over real HTTP: a refused {@code POST /spaces/{id}/import} rolls back every file it
 * wrote EXCEPT one another request saved in the meantime — that newer write survives, and the refusal names it
 * under {@code notRolledBack}. The concurrent save is made deterministically through
 * {@link ImportJournal#beforeRollback}, between the import's write and its rollback.
 */
class ControlApiImportConcurrentRollbackTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SpaceManager spaces, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); spaces.close(); MetricRegistry.global().reset(); }
    }

    private static Ctx open(Path root) throws Exception {
        Files.createDirectories(root.resolve("beta").resolve("config"));   // empty target space
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        spaces.startAll();
        api.start();
        return new Ctx(spaces, api, api.port());
    }

    @Test
    void aConcurrentSaveSurvivesTheRollbackAndTheRefusalNamesIt(@TempDir Path root) throws Exception {
        Path landed = root.resolve("beta").resolve("config").resolve("etl_pipeline.toon");
        String theirs = "# saved by another request while the import was in flight\n";
        ImportJournal.beforeRollback = written -> {
            assertTrue(written.contains(landed.toAbsolutePath().normalize()), "the import wrote it: " + written);
            try {
                Files.writeString(landed, theirs);
            } catch (java.io.IOException io) {
                throw new java.io.UncheckedIOException(io);
            }
        };
        try (Ctx c = open(root)) {
            HttpResponse<String> imp = post(c.port, "/spaces/beta/import", bundleWithMissingSchema(root));
            assertEquals(422, imp.statusCode(), imp.body());
            assertTrue(imp.body().contains("notRolledBack"), imp.body());
            assertTrue(imp.body().contains("etl_pipeline.toon"), imp.body());
            assertEquals(theirs, Files.readString(landed), "the newer write is not clobbered by pre-import bytes");
        } finally {
            ImportJournal.beforeRollback = null;
        }
    }

    @Test
    void anOrdinaryRefusedImportStillRollsBackFully(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            Path config = root.resolve("beta").resolve("config");
            List<Path> before;
            try (var s = Files.list(config)) {
                before = s.sorted().toList();
            }
            HttpResponse<String> imp = post(c.port, "/spaces/beta/import", bundleWithMissingSchema(root));
            assertEquals(422, imp.statusCode(), imp.body());
            assertFalse(imp.body().contains("notRolledBack"), imp.body());
            try (var s = Files.list(config)) {
                assertEquals(before, s.sorted().toList(), "the tree is back as it was");
            }
            assertFalse(Files.exists(config.resolve("etl_pipeline.toon")), "the imported file is gone");
        }
    }

    /**
     * A rollback that itself fails does not replace the refusal: the 422 stays the answer and says the rollback was
     * incomplete. The file is turned into a non-empty directory between the write and the rollback.
     */
    @Test
    void aRollbackThatFailsKeepsTheRefusalAndSaysSo(@TempDir Path root) throws Exception {
        Path landed = root.resolve("beta").resolve("config").resolve("etl_pipeline.toon");
        ImportJournal.beforeRollback = written -> {
            try {
                Files.delete(landed);
                Files.createDirectories(landed.resolve("inner"));
            } catch (java.io.IOException io) {
                throw new java.io.UncheckedIOException(io);
            }
        };
        try (Ctx c = open(root)) {
            HttpResponse<String> imp = post(c.port, "/spaces/beta/import", bundleWithMissingSchema(root));
            assertEquals(422, imp.statusCode(), "the refusal, not a 500 from the rollback: " + imp.body());
            assertTrue(imp.body().contains("rollbackIncomplete"), imp.body());
        } finally {
            ImportJournal.beforeRollback = null;
        }
    }

    /** A one-pipeline bundle whose schema reference the target Space does not have — the import's 422. */
    private static byte[] bundleWithMissingSchema(Path root) throws Exception {
        Path config = root.resolve("scratch").resolve("config");
        Files.createDirectories(config);
        Path pipeline = config.resolve("etl_pipeline.toon");
        if (!Files.exists(pipeline)) {
            Files.move(TestConfigs.csv(config, PipelineConfigBatchTest.miniSchema()).write(), pipeline);
            Files.writeString(pipeline, Files.readString(pipeline)
                    .replaceAll("(?m)^(\\s*schema_file:).*$", "$1 no_such_schema.toon"));
        }
        return BundleExporter.exportDataSource(
                new DataSourceBundle("test_etl", pipeline, null, List.of(), List.of(), List.of(), List.of(),
                        List.of()), config, "alpha");
    }

    private HttpResponse<String> post(int port, String path, byte[] body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/zip")
                .POST(BodyPublishers.ofByteArray(body)).build(), BodyHandlers.ofString());
    }
}
