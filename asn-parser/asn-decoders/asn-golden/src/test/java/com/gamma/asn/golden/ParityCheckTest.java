package com.gamma.asn.golden;

import org.junit.jupiter.api.Test;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins the parity level of the new stack against the golden corpus so it can only move
 * up. One fast case runs per build; the full sweep is the ParityCheck main
 * (see corpus/PARITY.md). Floor set from the measured 2026-07-29 value (0.99).
 */
class ParityCheckTest {

    private static final Path BASE = Path.of("..", "..").toAbsolutePath().normalize();

    @Test
    void aftelImsStructuralParityAndContentFloor() throws Exception {
        assumeTrue(Files.isDirectory(BASE.resolve("corpus").resolve("aftel_ims")),
                "golden corpus not present: " + BASE);
        List<Map<String, Object>> results = new ParityCheck(BASE)
                .run("aftel_ims", new PrintStream(java.io.OutputStream.nullOutputStream()));
        assertEquals(1, results.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>) results.getFirst().get("files");
        assertEquals(3, files.size());
        for (Map<String, Object> fr : files) {
            assertTrue(Boolean.TRUE.equals(fr.get("structuralParity")),
                    fr.get("file") + ": " + fr);
            @SuppressWarnings("unchecked")
            Map<String, Object> content = (Map<String, Object>) fr.get("content");
            double ratio = ((Number) content.get("matchRatio")).doubleValue();
            assertTrue(ratio >= 0.98, fr.get("file") + " leaf match regressed: " + ratio);

            // Phase 3 rows parity: identical row counts, row-leaf ratio floor 0.99
            @SuppressWarnings("unchecked")
            Map<String, Object> rows = (Map<String, Object>) fr.get("rows");
            assertEquals(rows.get("legacyRows"), rows.get("newRows"),
                    fr.get("file") + " row count diverged: " + rows);
            @SuppressWarnings("unchecked")
            Map<String, Object> rowContent = (Map<String, Object>) rows.get("content");
            double rowRatio = ((Number) rowContent.get("matchRatio")).doubleValue();
            assertTrue(rowRatio >= 0.99, fr.get("file") + " row leaf match regressed: " + rowRatio);
        }
    }
}
