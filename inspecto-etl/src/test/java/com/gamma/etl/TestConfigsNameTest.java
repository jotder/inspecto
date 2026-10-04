package com.gamma.etl;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** TESTCONFIGS-PREFIX-SUFFIX-TRAP-1: the fixture must emit the {@code *_pipeline.toon} name scanners match. */
class TestConfigsNameTest {

    @TempDir Path dir;

    @Test
    void writtenPipelineFileHasTheScannedSuffix() throws Exception {
        Path p = TestConfigs.csv(dir, "name: s\n").write();
        assertTrue(p.getFileName().toString().endsWith("_pipeline.toon"), p.toString());
    }
}
