package com.gamma.consignment;

import com.gamma.etl.ConsignmentManifest;
import com.gamma.etl.ManifestStore;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The read-only {@code consignment-status} Platform Service (S1-6): a seeded manifest answers
 * status/files/outputs, the search spans every loaded pipeline, and the two default-off registries
 * (output files, file stages) read as empty rather than failing.
 */
class ConsignmentStatusAccessTest {

    private static PipelineConfig pipelineIn(Path dir) throws Exception {
        Files.createDirectories(dir);
        return PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
    }

    private static void seedManifest(PipelineConfig cfg, String consignmentId, String createdAt) throws Exception {
        ConsignmentManifest m = new ConsignmentManifest();
        m.batchId = consignmentId;
        m.pipeline = cfg.identity().pipelineName();
        m.schemaName = "cdr";
        m.outputTable = "cdr";
        m.createdAt = createdAt;
        m.schemaFingerprint = "abc123";
        m.members = List.of(new ConsignmentManifest.MemberEntry("a.csv", 0, "in/a.csv", "bk/a.csv", "SUCCESS"),
                new ConsignmentManifest.MemberEntry("b.csv", 1, "in/b.csv", "bk/b.csv", "QUARANTINED_PARSE"));
        m.outputs = List.of(new ConsignmentManifest.OutputEntry("day=2026-08-09", "/w/cdr/day=2026-08-09/x.parquet"));
        m.markers = List.of();
        ManifestStore.write(cfg.dirs().manifestsDir(), m);
    }

    @Test
    void seededManifestAnswersStatusFilesAndOutputs(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = pipelineIn(dir.resolve("p1"));
        seedManifest(cfg, "c-1", "2026-08-09 10:00:00");
        ConsignmentStatusAccess status = ConsignmentStatusAccess.over(() -> List.of(cfg));

        ConsignmentManifest m = status.consignment("c-1").orElseThrow();
        assertEquals("mini_etl", m.pipeline, "the manifest carries the NORMALISED pipeline name");
        assertEquals("cdr", m.schemaName);
        assertEquals("abc123", m.schemaFingerprint, "the schema identity a Consignment was written with");
        assertEquals(2, m.members.size(), "member files with their per-file status");
        assertEquals("QUARANTINED_PARSE", m.members.get(1).status());
        assertEquals(1, m.outputs.size());
        assertEquals("day=2026-08-09", m.outputs.get(0).partition());

        assertTrue(status.consignment("nope").isEmpty(), "an unknown Consignment reads as absent");
        assertTrue(status.consignment(null).isEmpty());
        assertTrue(status.consignment(" ").isEmpty());
    }

    @Test
    void searchSpansEveryLoadedPipelineAndLatestForPicksTheNewest(@TempDir Path dir) throws Exception {
        PipelineConfig one = pipelineIn(dir.resolve("p1"));
        PipelineConfig two = pipelineIn(dir.resolve("p2"));
        seedManifest(one, "c-old", "2026-08-09 09:00:00");
        seedManifest(one, "c-new", "2026-08-09 12:00:00");
        seedManifest(two, "c-elsewhere", "2026-08-09 11:00:00");
        ConsignmentStatusAccess status = ConsignmentStatusAccess.over(() -> List.of(one, two));

        assertTrue(status.consignment("c-old").isPresent());
        assertTrue(status.consignment("c-elsewhere").isPresent(), "found in the second pipeline's manifests");

        // Both configs carry the same pipeline name, so latestFor sees all three manifests and takes
        // the newest createdAt. Either spelling resolves — the configured name as authored
        // (MINI_ETL) or the normalised one the manifest itself records (mini_etl).
        assertEquals("c-new", status.latestFor("MINI_ETL").orElseThrow().batchId);
        assertEquals("c-new", status.latestFor("mini_etl").orElseThrow().batchId);
        assertTrue(status.latestFor("no_such_pipeline").isEmpty());
        assertTrue(status.latestFor(null).isEmpty());
    }

    @Test
    void defaultOffRegistriesReadAsEmptyNotAsAFailure(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = pipelineIn(dir.resolve("p1"));
        ConsignmentStatusAccess status = ConsignmentStatusAccess.over(() -> List.of(cfg));

        // Neither -Dconsignment.outputs.backend nor -Dfile.stages.backend is set in tests: absent
        // registries answer empty — the manifest stays authoritative for what exists.
        assertEquals(List.of(), status.outputs("c-1"));
        assertEquals(List.of(), status.fileStages("src-1", "in/a.csv"));
    }

    @Test
    void aPipelineWithStatusDisabledContributesNoManifestsDir(@TempDir Path dir) throws Exception {
        // No status_dir ⇒ manifestsDir is null; the service must skip it, not NPE.
        Path config = dir.resolve("nostatus");
        Files.createDirectories(config);
        Path schema = config.resolve("mini_schema.toon");
        Files.writeString(schema, PipelineConfigBatchTest.miniSchema());
        Path toon = config.resolve("bare_pipeline.toon");
        Files.writeString(toon, """
                name: BARE_ETL
                version: 1
                dirs:
                  poll: %s/inbox
                  database: %s/db
                  backup: %s/backup
                  temp: %s/temp
                  errors: %s/errors
                  quarantine: %s/quarantine
                output:
                  format: CSV
                processing:
                  threads: 1
                  file_pattern: "glob:**/*.csv"
                  schema_file: "%s"
                """.formatted(config, config, config, config, config, config,
                schema.toString().replace("\\", "/")));
        PipelineConfig bare = PipelineConfig.load(toon.toString());
        assertNull(bare.dirs().manifestsDir(), "status disabled ⇒ no manifests dir");

        ConsignmentStatusAccess status = ConsignmentStatusAccess.over(() -> List.of(bare));
        assertTrue(status.consignment("c-1").isEmpty());
        assertTrue(status.latestFor("BARE_ETL").isEmpty());
    }
}
