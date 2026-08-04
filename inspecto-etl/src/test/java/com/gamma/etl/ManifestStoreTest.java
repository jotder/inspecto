package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ManifestStoreTest {

    @Test
    void writesReadsAndSupersedes(@TempDir Path dir) throws Exception {
        String manifestsDir = dir.resolve("manifests").toString();

        BatchManifest m = new BatchManifest();
        m.batchId = "B1";
        m.pipeline = "mini_etl";
        m.schemaName = "mini";
        m.outputTable = null;
        m.createdAt = "2026-05-27 10:30:00";
        m.members = List.of(new BatchManifest.MemberEntry("a.csv", 0, "20200403/a.csv",
                dir.resolve("backup/20200403/a.csv").toString(), "SUCCESS"));
        m.outputs = List.of(new BatchManifest.OutputEntry("year=2020/month=04/day=03",
                dir.resolve("db/B1_out.csv").toString()));
        m.markers = List.of(dir.resolve("markers/20200403/a.csv.processed").toString());

        ManifestStore.write(manifestsDir, m);

        BatchManifest back = ManifestStore.read(manifestsDir, "B1");
        assertEquals("B1", back.batchId);
        assertEquals(1, back.members.size());
        assertEquals("a.csv", back.members.get(0).filename());
        assertEquals(1, back.outputs.size());

        ManifestStore.supersede(manifestsDir, "B1");
        assertFalse(Files.exists(Path.of(manifestsDir, "B1.json")));
        assertTrue(Files.exists(Path.of(manifestsDir, "B1.json.superseded")));
    }

    // ── the batch_id → consignment_id rename (plan §11.3, decision 2) ────────────

    /**
     * A manifest written before the rename must still be readable, or {@code ReprocessCommand} breaks.
     *
     * <p>This is the failure the rename could most easily have shipped silently: Gson had no
     * {@code @SerializedName} here, so the on-disk key was the field name. A field rename alone yields
     * {@code null} for every existing manifest and throws <b>nothing</b> — the reprocess would simply act on a
     * null id. Hand-written JSON, because only a real pre-rename file proves it.
     */
    @Test
    void readsAManifestWrittenWithTheLegacyBatchIdKey(@TempDir Path dir) throws Exception {
        Path manifests = dir.resolve("manifests");
        Files.createDirectories(manifests);
        Files.writeString(manifests.resolve("B1.json"), """
            {"batchId":"B1","pipeline":"mini_etl","createdAt":"2026-05-27 10:30:00",
             "members":[],"outputs":[{"partition":"year=2020","outputFile":"/db/B1_out.csv"}],"markers":[]}
            """);

        BatchManifest back = ManifestStore.read(manifests.toString(), "B1");
        assertEquals("B1", back.batchId, "the legacy camelCase key must still bind");
        assertEquals(1, back.outputs.size(), "and the rest of the manifest with it");
    }

    /** Write-new-only: fresh manifests carry the canonical key, and are still readable by us. */
    @Test
    void writesTheCanonicalConsignmentIdKey(@TempDir Path dir) throws Exception {
        String manifests = dir.resolve("manifests").toString();
        BatchManifest m = new BatchManifest();
        m.batchId = "C9";
        m.pipeline = "mini_etl";
        m.members = List.of();
        m.outputs = List.of();
        m.markers = List.of();

        ManifestStore.write(manifests, m);

        String json = Files.readString(Path.of(manifests, "C9.json"));
        assertTrue(json.contains("\"consignmentId\""), "must write the new spelling: " + json);
        assertFalse(json.contains("\"batchId\""), "and only the new one: " + json);
        assertEquals("C9", ManifestStore.read(manifests, "C9").batchId, "round-trips under the new key");
    }
}
