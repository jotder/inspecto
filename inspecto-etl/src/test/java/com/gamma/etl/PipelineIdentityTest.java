package com.gamma.etl;

import com.gamma.util.ToonHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Splitting a pipeline's <b>identity</b> from its <b>display name</b>.
 *
 * <p>Identity is the value ~140 call sites key on ({@code identity().pipelineName()}) and it is further
 * embedded in the config file name, the {@code <pipelineName>_commits.log} audit trail, the acquisition
 * ledger's default {@code sourceId} (dedup + watermark state) and the Catalog Stream name. Historically it
 * was <em>derived</em> from {@code name}, which is precisely why renaming was a multi-write migration rather
 * than an edit. An explicit {@code id} decouples them.
 *
 * <p>The load-bearing test here is {@link #aConfigWithNoIdBehavesExactlyAsBefore()} — every config in the
 * wild omits {@code id}, so the derived path must stay byte-identical or this becomes a silent data
 * migration for every existing deployment.
 */
class PipelineIdentityTest {

    /**
     * Parse the sample pipeline with an optional top-level {@code id} and/or a replaced {@code name}.
     * ⚠ Not {@code writePipeline(dir, extra)} — that argument is interpolated *inside* the
     * {@code processing:} block, so passing {@code "id: x"} there would nest it under `processing` and the
     * test would pass for the wrong reason.
     */
    private static PipelineConfig parse(Path dir, String id, String name) throws Exception {
        String toon = java.nio.file.Files.readString(PipelineConfigBatchTest.writePipeline(dir, ""));
        if (name != null) toon = toon.replace("name: MINI_ETL", "name: " + name);
        if (id != null) toon = "id: " + id + "\n" + toon;
        Path p = dir.resolve("identity_pipeline.toon");
        java.nio.file.Files.writeString(p, toon);
        return PipelineConfig.fromMap(ToonHelper.load(p.toString()));
    }

    @Test
    void aConfigWithNoIdBehavesExactlyAsBefore(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = parse(dir, null, null);

        // The sample's `name` is "mini_etl"; derived identity = lowercased, spaces underscored.
        assertEquals("mini_etl", cfg.identity().pipelineName());
    }

    @Test
    void anExplicitIdWinsOverTheDerivedName(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = parse(dir, "stable_orders_id", null);

        assertEquals("stable_orders_id", cfg.identity().pipelineName());
    }

    @Test
    void withAnIdSetTheDisplayNameNoLongerDrivesIdentity(@TempDir Path dir) throws Exception {
        // This is the whole point: `name` changes, identity does not — so no file, dir, commit log,
        // ledger sourceId or Catalog Stream has to move.
        PipelineConfig before = parse(dir, "stable_orders_id", null);
        PipelineConfig after = parse(dir, "stable_orders_id", "Totally Different Label");

        assertEquals(before.identity().pipelineName(), after.identity().pipelineName());
        assertEquals("stable_orders_id", after.identity().pipelineName());
        // ...and the identity is genuinely NOT the renamed label's derived form.
        assertNotEquals("totally_different_label", after.identity().pipelineName());
    }

    @Test
    void aBlankIdFallsBackToDerivationRatherThanEmptyIdentity(@TempDir Path dir) throws Exception {
        // A half-authored `id:` must not yield an empty identity — that would key every downstream
        // artifact on "".
        PipelineConfig cfg = parse(dir, "\"   \"", null);

        assertEquals("mini_etl", cfg.identity().pipelineName());
        assertTrue(!cfg.identity().pipelineName().isBlank());
    }

    @Test
    void theNameDerivedPathStillLowercasesAndUnderscores(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = parse(dir, null, "Mixed Case Feed");

        assertEquals("mixed_case_feed", cfg.identity().pipelineName());
    }
}
