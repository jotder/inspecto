package com.gamma.inspector;

import com.gamma.acquire.CollectorConnector;
import com.gamma.acquire.CollectorConnectorFactory;
import com.gamma.acquire.CollectorConnectors;
import com.gamma.acquire.DiscoveryContext;
import com.gamma.acquire.PostAction;
import com.gamma.acquire.RemoteFile;
import com.gamma.etl.PipelineConfig;
import com.gamma.pipeline.ComponentStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code connector: dataset} source (ELT Phase 3 S3c-2): dataset id → snapshot dir resolved
 * fresh through the registry (never baked into config), discovery over the producer's snapshots,
 * fetchTo copying into the consumer's own tree, and — load-bearing — a consumer can never
 * delete/move the producer's files, whatever post-action its config declares.
 */
class DatasetCollectorConnectorFactoryTest {

    private String prevWriteRoot;
    private String prevDataDir;

    private void ambient(Path writeRoot, Path dataDir) {
        prevWriteRoot = System.setProperty("assist.write.root", writeRoot.toString());
        prevDataDir = System.setProperty("data.dir", dataDir.toString());
    }

    @AfterEach
    void restore() {
        if (prevWriteRoot == null) System.clearProperty("assist.write.root");
        else System.setProperty("assist.write.root", prevWriteRoot);
        if (prevDataDir == null) System.clearProperty("data.dir");
        else System.setProperty("data.dir", prevDataDir);
    }

    /** Registry entry + a snapshot on disk + a consuming config — the whole seam end to end. */
    @Test
    void resolvesDiscoversAndCopiesButNeverTouchesTheProducersFiles(@TempDir Path dir) throws Exception {
        Path writeRoot = Files.createDirectories(dir.resolve("write"));
        Path dataRoot = Files.createDirectories(dir.resolve("data"));
        ambient(writeRoot, dataRoot);

        new ComponentStore(writeRoot.resolve("registry"))
                .write("dataset", "orders_rollup", Map.of("physicalRef", "orders_rollup"));
        Path snapshots = Files.createDirectories(dataRoot.resolve("orders_rollup"));
        Path snap = snapshots.resolve("matrix-1.parquet");
        Files.writeString(snap, "not-real-parquet", StandardCharsets.UTF_8);

        PipelineConfig cfg = consumer(dir, "trigger:\n  type: event\n  on: dataset\n  from: datasets/orders_rollup\n");
        assertTrue(cfg.collector().hasDataset());
        assertEquals("orders_rollup", cfg.collector().dataset());
        assertTrue(CollectorConnectors.isRemote(cfg), "dataset is a fetching scheme, not the in-place local one");

        try (CollectorConnector c = CollectorConnectors.forConfig(cfg)) {   // via ServiceLoader
            assertEquals("dataset", c.scheme());
            List<RemoteFile> found = c.discover(new DiscoveryContext(
                    List.of("glob:**/*.parquet"), List.of(), DiscoveryContext.UNBOUNDED));
            assertEquals(1, found.size(), "the producer's snapshot is discoverable");

            Path dest = dir.resolve("inbox/matrix-1.parquet");
            assertEquals(dest, c.fetchTo(found.get(0), dest));
            assertArrayEquals(Files.readAllBytes(snap), Files.readAllBytes(dest), "byte-faithful copy");

            c.post(found.get(0), new PostAction(PostAction.Kind.DELETE, null, Map.of()));
            assertTrue(Files.exists(snap),
                    "a consumer must NEVER delete the producer's snapshot — post is forced to RETAIN");
        }
    }

    @Test
    void aViewBackedOrUnknownDatasetFailsClosed(@TempDir Path dir) throws Exception {
        Path writeRoot = Files.createDirectories(dir.resolve("write"));
        ambient(writeRoot, Files.createDirectories(dir.resolve("data")));
        new ComponentStore(writeRoot.resolve("registry"))
                .write("dataset", "view_only", Map.of("view", "some_view"));

        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> DatasetCollectorConnectorFactory.resolveDatasetDir("no_such"));
        assertTrue(unknown.getMessage().contains("no_such"), unknown.getMessage());
        IllegalArgumentException view = assertThrows(IllegalArgumentException.class,
                () -> DatasetCollectorConnectorFactory.resolveDatasetDir("view_only"));
        assertTrue(view.getMessage().contains("physicalRef"), view.getMessage());
    }

    @Test
    void theFactoryIsServiceLoaderDiscoverable() {
        boolean found = false;
        for (CollectorConnectorFactory f : ServiceLoader.load(CollectorConnectorFactory.class))
            if ("dataset".equals(f.scheme())) found = true;
        assertTrue(found, "META-INF/services must register the dataset scheme");
    }

    /** A consuming pipeline whose source is the dataset — its own dirs, its own inbox. */
    private static PipelineConfig consumer(Path dir, String triggerBlock) throws Exception {
        String d = dir.toString().replace('\\', '/');
        Path schema = dir.resolve("schema.toon");
        Files.writeString(schema, """
                partitionKey: EVENT_DATE
                raw:
                  name: ev
                  format: CSV
                  fields[1]{name,selector,type}:
                    ACCOUNT_NUMBER,"account",VARCHAR
                mapping:
                  canonicalName: ev
                  rawName: ev
                  rules[1]{targetColumn,sourceExpression,transformType}:
                    ACCOUNT_NUMBER,ACCOUNT_NUMBER,DIRECT
                """, StandardCharsets.UTF_8);
        Path p = dir.resolve("ds_down_pipeline.toon");
        Files.writeString(p,
                "name: DS_DOWN\n" +
                "version: 1\n" +
                triggerBlock +
                "collector:\n" +
                "  connector: dataset\n" +
                "  dataset: datasets/orders_rollup\n" +
                "dirs:\n" +
                "  poll: " + d + "/inbox\n" +
                "  database: " + d + "/db\n" +
                "  backup: " + d + "/backup\n" +
                "  temp: " + d + "/temp\n" +
                "  errors: " + d + "/errors\n" +
                "  quarantine: " + d + "/quarantine\n" +
                "  status_dir: " + d + "/status\n" +
                "output:\n" +
                "  format: PARQUET\n" +
                "processing:\n" +
                "  threads: 1\n" +
                "  file_pattern: \"glob:**/*.parquet\"\n" +
                "  schema_file: " + schema.toString().replace('\\', '/') + "\n" +
                "parsing:\n" +
                "  frontend: parquet\n", StandardCharsets.UTF_8);
        return PipelineConfig.load(p.toString());
    }
}
