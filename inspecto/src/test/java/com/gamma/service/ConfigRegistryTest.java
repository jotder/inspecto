package com.gamma.service;

import com.gamma.etl.PipelineConfigBatchTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the O(1) config index (P3): lookups address pipelines by their <b>in-file
 * identity</b> (not their filename), duplicate identities are reconciled rather than silently
 * shadowing, unloadable paths are skipped, and the rebuild callback fires (the catalog-invalidation
 * hook). {@code PipelineConfigBatchTest.writePipeline} emits {@code mini_pipeline.toon} declaring
 * {@code name: MINI_ETL} → id {@code mini_etl}, so the filename prefix ("mini") deliberately differs
 * from the identity ("mini_etl") to prove the keying.
 */
class ConfigRegistryTest {

    @Test
    void indexesByInFileIdentityNotFilename(@TempDir Path dir) throws Exception {
        Path p = PipelineConfigBatchTest.writePipeline(dir, "");
        ConfigRegistry reg = new ConfigRegistry();
        reg.rebuild(List.of(p));

        assertEquals(1, reg.size());
        assertTrue(reg.get("mini_etl").isPresent(), "lookup by in-file identity");
        assertTrue(reg.get("mini").isEmpty(), "the filename prefix is NOT the key");
        assertEquals(p, reg.getPath("mini_etl").orElseThrow());
        assertEquals("mini_etl", reg.idForPath(p).orElseThrow());
        assertEquals(1, reg.configs().size());
        assertEquals("mini_etl", reg.all().get(0).id());
    }

    @Test
    void duplicateInFileIdentityKeepsLatter(@TempDir Path dir) throws Exception {
        Path da = Files.createDirectories(dir.resolve("a"));
        Path db = Files.createDirectories(dir.resolve("b"));
        Path a = PipelineConfigBatchTest.writePipeline(da, "");
        Path b = PipelineConfigBatchTest.writePipeline(db, "");

        ConfigRegistry reg = new ConfigRegistry();
        reg.rebuild(List.of(a, b)); // both declare name MINI_ETL → same id
        assertEquals(1, reg.size(), "the same identity collapses to one entry");
        assertEquals(b, reg.getPath("mini_etl").orElseThrow(), "the later path wins");
    }

    @Test
    void unloadablePathSkippedButCallbackFires(@TempDir Path dir) throws Exception {
        Path good = PipelineConfigBatchTest.writePipeline(dir, "");
        Path missing = dir.resolve("nope_pipeline.toon"); // never written
        AtomicInteger rebuilds = new AtomicInteger();
        ConfigRegistry reg = new ConfigRegistry(rebuilds::incrementAndGet);

        reg.rebuild(List.of(good, missing));
        assertEquals(1, reg.size(), "the unloadable path is warned and skipped");
        assertEquals(1, rebuilds.get(), "the rebuild callback fires once");

        reg.rebuild(List.of(good));
        assertEquals(2, rebuilds.get(), "callback fires on every rebuild");
    }

    /**
     * PIPELINE-LOAD-FAILURE-INVISIBLE-1: a Pipeline that does not load is KEPT as a load failure (file, line,
     * the loader's own message) instead of vanishing behind a WARN — and it never enters the index, so
     * nothing that runs, schedules or counts Pipelines can see it.
     */
    @Test
    void anUnloadablePipelineIsKeptAsALoadFailureOutsideTheIndex(@TempDir Path dir) throws Exception {
        Path good = PipelineConfigBatchTest.writePipeline(Files.createDirectories(dir.resolve("good")), "");
        Path badDir = Files.createDirectories(dir.resolve("bad"));
        Path bad = Files.move(PipelineConfigBatchTest.writePipeline(badDir, ""), badDir.resolve("orders_pipeline.toon"));
        Files.writeString(badDir.resolve("mini_schema.toon"), PipelineConfigBatchTest.miniSchema()
                .replace("AMT,\"1\",DOUBLE", "AMT,\"1\",DECIMAL(18,2)"));
        ConfigRegistry reg = new ConfigRegistry();

        reg.rebuild(List.of(good, bad));
        assertEquals(1, reg.size(), "the broken Pipeline is not indexed");
        assertTrue(reg.configForPath(bad).isEmpty());
        assertEquals(1, reg.failures().size(), "but it is not forgotten either");
        ConfigRegistry.LoadFailure f = reg.failures().get(0);
        assertEquals(bad, f.path());
        assertEquals("orders", f.name(), "named after its file, since its declared name never parsed");
        assertTrue(f.file().endsWith("mini_schema.toon"), "the file holding the bad row, not the pipeline: " + f.file());
        assertEquals(7, f.line());
        assertTrue(f.message().contains("\"DECIMAL(18,2)\""), f.message());

        Files.writeString(badDir.resolve("mini_schema.toon"), PipelineConfigBatchTest.miniSchema());
        reg.rebuild(List.of(good, bad));
        assertTrue(reg.failures().isEmpty(), "a fixed file drops out of the failures");
        assertEquals(1, reg.size(), "and loads (same MINI_ETL identity as the good one, so still one id)");
        assertTrue(reg.configForPath(bad).isPresent());
    }

    @Test
    void aFailureWithNoLineFallsBackToThePipelineFile(@TempDir Path dir) throws Exception {
        Path missing = dir.resolve("nope_pipeline.toon"); // never written
        ConfigRegistry reg = new ConfigRegistry();
        reg.rebuild(List.of(missing));
        ConfigRegistry.LoadFailure f = reg.failures().get(0);
        assertEquals(missing.toString(), f.file());
        assertNull(f.line());
        assertEquals("nope", f.name());
    }

    @Test
    void unchangedFilesReuseTheParsedConfig(@TempDir Path dir) throws Exception {
        Path p = PipelineConfigBatchTest.writePipeline(dir, "");
        ConfigRegistry reg = new ConfigRegistry();
        reg.rebuild(List.of(p));
        var first = reg.get("mini_etl").orElseThrow();
        reg.rebuild(List.of(p));   // nothing changed on disk
        assertSame(first, reg.get("mini_etl").orElseThrow(),
                "an unchanged config (and its schemas) is not re-parsed across rebuilds");
    }

    @Test
    void editingThePipelineFileReloads(@TempDir Path dir) throws Exception {
        Path p = PipelineConfigBatchTest.writePipeline(dir, "");
        ConfigRegistry reg = new ConfigRegistry();
        reg.rebuild(List.of(p));
        var first = reg.get("mini_etl").orElseThrow();
        bumpMtime(p);
        reg.rebuild(List.of(p));
        assertNotSame(first, reg.get("mini_etl").orElseThrow(), "a changed pipeline file is re-parsed");
    }

    @Test
    void editingAReferencedSchemaReloads(@TempDir Path dir) throws Exception {
        Path p = PipelineConfigBatchTest.writePipeline(dir, "");
        ConfigRegistry reg = new ConfigRegistry();
        reg.rebuild(List.of(p));
        var first = reg.get("mini_etl").orElseThrow();
        bumpMtime(dir.resolve("mini_schema.toon"));   // a referenced schema, not the pipeline file
        reg.rebuild(List.of(p));
        assertNotSame(first, reg.get("mini_etl").orElseThrow(),
                "editing a referenced schema file re-parses the pipeline");
    }

    @Test
    void configForPathResolvesBothCollidingNames(@TempDir Path dir) throws Exception {
        Path a = PipelineConfigBatchTest.writePipeline(Files.createDirectories(dir.resolve("a")), "");
        Path b = PipelineConfigBatchTest.writePipeline(Files.createDirectories(dir.resolve("b")), "");
        ConfigRegistry reg = new ConfigRegistry();
        reg.rebuild(List.of(a, b));   // both declare name MINI_ETL
        assertTrue(reg.configForPath(a).isPresent(), "the shadowed path is still resolvable by path");
        assertTrue(reg.configForPath(b).isPresent());
        assertTrue(reg.configForPath(dir.resolve("nope_pipeline.toon")).isEmpty());
    }

    /** Push a file's mtime forward so the change is detected regardless of filesystem timestamp granularity. */
    private static void bumpMtime(Path p) throws Exception {
        long t = Files.getLastModifiedTime(p).toMillis() + 5_000L;
        Files.setLastModifiedTime(p, java.nio.file.attribute.FileTime.fromMillis(t));
    }

    @Test
    void emptyRegistryReadsAreSafe() {
        ConfigRegistry reg = new ConfigRegistry();
        assertEquals(0, reg.size());
        assertTrue(reg.get("x").isEmpty());
        assertTrue(reg.getPath("x").isEmpty());
        assertTrue(reg.idForPath(Path.of("whatever")).isEmpty());
        assertTrue(reg.configs().isEmpty());
        assertTrue(reg.all().isEmpty());
        assertTrue(reg.failures().isEmpty());
    }
}
