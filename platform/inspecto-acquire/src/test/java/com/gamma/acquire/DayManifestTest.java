package com.gamma.acquire;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LA-DAILY-INGEST-1 T8 (operator 2026-10-06): the per-day delivery manifest and what a recorded part reveals. */
class DayManifestTest {

    private static DayManifest.Part part(String day, long seq, String name, long rows) {
        return new DayManifest.Part(day, seq, name, rows, rows * 10, 1L);
    }

    @Test
    void aConformingFeedRevealsNothingAndALateWholeDayIsNotAnAnomaly(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("m.tsv");
        assertTrue(DayManifest.record(f, List.of(part("20260901", 1, "XDR_20260901_part0001.csv", 5),
                part("20260901", 2, "XDR_20260901_part0002.csv", 6))).isEmpty());
        assertTrue(DayManifest.record(f, List.of(part("20260903", 1, "XDR_20260903_part0001.csv", 4))).isEmpty());
        assertTrue(DayManifest.record(f, List.of(part("20260902", 1, "XDR_20260902_part0001.csv", 3))).isEmpty(),
                "a whole day arriving after a later day is a late file, which the contract allows");
        DayManifest m = DayManifest.read(f);
        assertEquals(List.of("20260901", "20260902", "20260903"), List.copyOf(m.days().keySet()));
        assertEquals(6, m.days().get("20260901").get(1).rows(), "row counts per part survive the round trip");
    }

    @Test
    void aSameNameRedeliveryARenamedCorrectionAndAChangedPartCountAreEachRevealed(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("m.tsv");
        DayManifest.record(f, List.of(part("20260901", 1, "XDR_20260901_part0001.csv", 5),
                part("20260902", 1, "XDR_20260902_part0001.csv", 7)));

        List<DayManifest.Anomaly> same = DayManifest.record(f, List.of(part("20260901", 1, "XDR_20260901_part0001.csv", 8)));
        assertEquals(DayManifest.Kind.REDELIVERED, same.get(0).kind());
        assertEquals("5", same.get(0).previous());
        assertEquals(1, DayManifest.read(f).days().get("20260901").size(), "a same-name redelivery replaces its entry");

        List<DayManifest.Anomaly> renamed = DayManifest.record(f, List.of(part("20260902", 1, "XDR_20260902_part0001_fix.csv", 7)));
        assertEquals(DayManifest.Kind.RENAMED_CORRECTION, renamed.get(0).kind());
        assertEquals("XDR_20260902_part0001.csv", renamed.get(0).previous());
        assertEquals(2, DayManifest.read(f).days().get("20260902").size(), "both landed, both stay recorded");

        List<DayManifest.Anomaly> grown = DayManifest.record(f, List.of(part("20260901", 2, "XDR_20260901_part0002.csv", 2)));
        assertEquals(DayManifest.Kind.PART_COUNT_CHANGED, grown.get(0).kind());
        assertEquals("1", grown.get(0).previous());
    }

    @Test
    void aMalformedFileOrAnUnstorableNameIsRefusedNotGuessed(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("m.tsv");
        Files.writeString(f, "day\tseq\tname\trows\tbytes\tat\n20260901\tx\tA\t1\t1\t1\n");
        assertThrows(java.io.IOException.class, () -> DayManifest.read(f));
        assertThrows(IllegalArgumentException.class,
                () -> DayManifest.record(dir.resolve("n.tsv"), List.of(part("20260901", 1, "bad\tname", 1))));
        assertTrue(Files.notExists(dir.resolve("n.tsv")), "nothing written on a refusal");
    }
}
