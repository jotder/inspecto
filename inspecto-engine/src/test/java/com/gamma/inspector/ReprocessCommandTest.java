package com.gamma.inspector;

import com.gamma.consignment.ConsignmentOutput;
import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.consignment.DbConsignmentOutputStore;
import com.gamma.etl.ConsignmentManifest;
import com.gamma.etl.ManifestStore;
import com.gamma.etl.PipelineConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ReprocessCommandTest {

    @Test
    void deletesOutputsRestoresFilesAndReprocesses(@TempDir Path dir) throws Exception {
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, """
              batch:
                max_files: 100
            """);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("a.csv"), "ID,AMT,EVENT_DATE\na,1.0,2020-04-03\n");
        Files.writeString(inbox.resolve("b.csv"), "ID,AMT,EVENT_DATE\nb,2.0,2020-04-03\n");

        CollectorProcessor.run(cfg);

        // Find the batch id from the single manifest written.
        String batchId;
        try (Stream<Path> w = Files.walk(Path.of(cfg.dirs().manifestsDir()))) {
            Path mf = w.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow();
            batchId = mf.getFileName().toString().replace(".json", "");
        }

        // Sanity: outputs + markers + backup present before reprocess.
        assertTrue(Files.exists(Path.of(cfg.dirs().backup(), "a.csv")));
        assertTrue(Files.exists(Path.of(cfg.dirs().markers(), "a.csv.processed")));

        // Reprocess: must restore files, delete old outputs/markers, supersede manifest, re-run.
        ReprocessCommand.run(toon.toString(), batchId);

        // Old manifest superseded.
        assertTrue(Files.exists(Path.of(cfg.dirs().manifestsDir(), batchId + ".json.superseded")));
        // Markers exist again (re-run re-created them) and outputs exist.
        assertTrue(Files.exists(Path.of(cfg.dirs().markers(), "a.csv.processed")));
        try (Stream<Path> w = Files.walk(Path.of(cfg.dirs().database()))) {
            assertTrue(w.anyMatch(p -> p.getFileName().toString().endsWith("_out.csv")));
        }
    }

    /**
     * §11.3(a) — the silent data-duplication bug, now refused. With the output files merged away by compaction,
     * the old path deleted nothing (its {@code deleteIfExists} no-ops), restored the members and re-ingested
     * rows that still exist inside the merged file. This asserts the refusal, and that it names the reason.
     */
    @Test
    void refusesWhenTheOutputsWereCompactedAway(@TempDir Path dir) throws Exception {
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, """
              batch:
                max_files: 100
            """);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("a.csv"), "ID,AMT,EVENT_DATE\na,1.0,2020-04-03\n");

        CollectorProcessor.run(cfg);

        String batchId;
        try (Stream<Path> w = Files.walk(Path.of(cfg.dirs().manifestsDir()))) {
            batchId = w.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow()
                    .getFileName().toString().replace(".json", "");
        }
        ConsignmentManifest m = ManifestStore.read(cfg.dirs().manifestsDir(), batchId);
        assertFalse(m.outputs.isEmpty(), "precondition: the run produced outputs to compact away");

        try (DbConsignmentOutputStore store = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            store.record(m.outputs.stream()
                    .map(o -> new ConsignmentOutput(batchId, null, "t", "dt=2020-04-03", "2020-04-03",
                            o.outputFile(), 1L, 100L, "2026-08-04T10:00:00Z", 0,
                            ConsignmentOutput.State.LIVE))
                    .toList());
            // What PartitionCompactor now does when it merges those files away.
            assertEquals(m.outputs.size(),
                    store.markCompactedAway(m.outputs.stream().map(o -> o.outputFile()).toList()));
            ConsignmentOutputStores.use(store);

            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> ReprocessCommand.run(toon.toString(), batchId));
            assertTrue(e.getMessage().contains("duplicate"), e.getMessage());
            assertTrue(e.getMessage().contains(batchId), e.getMessage());

            // And it refused before mutating anything — the manifest is still live.
            assertTrue(Files.exists(Path.of(cfg.dirs().manifestsDir(), batchId + ".json")));
            assertFalse(Files.exists(Path.of(cfg.dirs().manifestsDir(), batchId + ".json.superseded")));
        } finally {
            ConsignmentOutputStores.use(null);
        }
    }

    /** The same run must still succeed while the outputs are LIVE — the guard blocks only the unsafe case. */
    @Test
    void stillReprocessesWhenTheRegistryShowsLiveOutputs(@TempDir Path dir) throws Exception {
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, """
              batch:
                max_files: 100
            """);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("a.csv"), "ID,AMT,EVENT_DATE\na,1.0,2020-04-03\n");

        CollectorProcessor.run(cfg);
        String batchId;
        try (Stream<Path> w = Files.walk(Path.of(cfg.dirs().manifestsDir()))) {
            batchId = w.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow()
                    .getFileName().toString().replace(".json", "");
        }
        ConsignmentManifest m = ManifestStore.read(cfg.dirs().manifestsDir(), batchId);

        try (DbConsignmentOutputStore store = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            store.record(m.outputs.stream()
                    .map(o -> new ConsignmentOutput(batchId, null, "t", "dt=2020-04-03", "2020-04-03",
                            o.outputFile(), 1L, 100L, "2026-08-04T10:00:00Z", 0,
                            ConsignmentOutput.State.LIVE))
                    .toList());
            ConsignmentOutputStores.use(store);

            List<String> originalPaths = m.outputs.stream().map(o -> o.outputFile()).toList();

            ReprocessCommand.run(toon.toString(), batchId);

            assertTrue(Files.exists(Path.of(cfg.dirs().manifestsDir(), batchId + ".json.superseded")));

            // Count SUPERSEDED rows rather than asserting every row for this id (or even for these paths) has
            // moved. Two things collide here, both by design: batch ids are yyyyMMdd_HHmmss, so a reprocess
            // finishing inside the same second re-ingests under the *same* id (ReprocessCommand's "fresh batch
            // id" holds only across a second boundary), and output file names are deterministic
            // (<baseName>_out.<ext>), so the re-run lands on the same path. The result is one SUPERSEDED row
            // and one fresh LIVE row sharing a path — which is exactly the history the registry should keep.
            List<ConsignmentOutput> rows = store.outputs(batchId);
            assertEquals(originalPaths.size(),
                    rows.stream().filter(o -> o.state() == ConsignmentOutput.State.SUPERSEDED).count(),
                    "every pre-existing row is superseded alongside the manifest, got "
                            + rows.stream().map(ConsignmentOutput::state).toList());
            assertTrue(rows.stream().anyMatch(o -> originalPaths.contains(o.path())),
                    "the original output paths are still listed — superseded, not deleted");
        } finally {
            ConsignmentOutputStores.use(null);
        }
    }
}
