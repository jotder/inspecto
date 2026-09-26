package com.gamma.query;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * R2-05 follow-up: the readable Dataset name fired Alert / Incident text uses — a short
 * {@code description}, else nothing (the caller then says the id).
 */
class DatasetMeasureProbeLabelTest {

    private static DatasetMeasureProbe probe(Path root) {
        return new DatasetMeasureProbe(() -> root, () -> null);
    }

    private static void dataset(Path root, String id, String body) throws Exception {
        Path dir = Files.createDirectories(root.resolve("registry").resolve("datasets"));
        Files.writeString(dir.resolve(id + ".toon"), body);
    }

    @Test
    void aShortDescriptionIsUsedWithoutItsTrailingPeriod(@TempDir Path root) throws Exception {
        dataset(root, "cases", "name: cases\nphysicalRef: x\ndescription: Open cases with owner.\n");
        assertEquals("Open cases with owner", probe(root).label("cases"));

        String sixty = "a".repeat(DatasetMeasureProbe.MAX_LABEL);
        dataset(root, "edge", "name: edge\nphysicalRef: x\ndescription: " + sixty + "\n");
        assertEquals(sixty, probe(root).label("edge"), "exactly the limit still fits");
    }

    @Test
    void aLongDescriptionOrNoDescriptionOrNoDatasetGivesNoLabel(@TempDir Path root) throws Exception {
        dataset(root, "long", "name: long\nphysicalRef: x\ndescription: "
                + "a".repeat(DatasetMeasureProbe.MAX_LABEL + 1) + "\n");
        assertNull(probe(root).label("long"), "a paragraph never becomes a title");
        dataset(root, "bare", "name: bare\nphysicalRef: x\n");
        assertNull(probe(root).label("bare"));
        assertNull(probe(root).label("ghost"));
        assertNull(new DatasetMeasureProbe(() -> null, () -> null).label("cases"), "no registry root");
    }
}
