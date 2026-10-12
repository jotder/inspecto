package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** {@code processing.output_per_member} (INGEST-MULTIFILE-BATCH-REPLAN-DUP-1 stage 2): parse, default, refusals. */
class OutputPerMemberConfigTest {

    @Test
    void defaultsOffAndReadsTrueAndFalse(@TempDir Path dir) throws Exception {
        assertFalse(PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString()).outputPerMember());
        Path d2 = Files.createDirectory(dir.resolve("on"));
        assertTrue(PipelineConfig.load(PipelineConfigBatchTest.writePipeline(d2, "  output_per_member: true").toString())
                .outputPerMember());
        Path d3 = Files.createDirectory(dir.resolve("off"));
        assertFalse(PipelineConfig.load(PipelineConfigBatchTest.writePipeline(d3, "  output_per_member: false").toString())
                .outputPerMember());
    }

    @Test
    void aNonBooleanValueIsRefusedAtLoad(@TempDir Path dir) throws Exception {
        Path p = PipelineConfigBatchTest.writePipeline(dir, "  output_per_member: sometimes");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> PipelineConfig.load(p.toString()));
        assertTrue(e.getMessage().contains("processing.output_per_member"), e.getMessage());
    }

    @Test
    void itIsRefusedWithRouteBranches(@TempDir Path dir) throws Exception {
        Path p = PipelineConfigBatchTest.writePipeline(dir, "  output_per_member: true");
        String d = dir.toString().replace('\\', '/');
        Files.writeString(p, Files.readString(p) + """
                sinks[2]{database,format}:
                  "%1$s/db_e",CSV
                  "%1$s/db_a",CSV
                route:
                  mode: case
                  default: emea
                  branches[2]{key,where,database}:
                    emea,"ID LIKE 'E%%'","%1$s/db_e"
                    apac,"ID LIKE 'A%%'","%1$s/db_a"
                """.formatted(d));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> PipelineConfig.load(p.toString()));
        assertTrue(e.getMessage().contains("output_per_member"), e.getMessage());
    }
}
