package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4 S4c — the parked Consignment's pending commit state survives the JVM that produced it.
 * This is the whole point of the sidecar: the branches that committed BEFORE the park hold their
 * outputs / lineage / bounds only in memory, and a drain in a later process must finalise the WHOLE
 * batch. Also pins the {@code Bounds} mirror of {@code com.gamma.consignment.EventTimeBounds} —
 * extend both together.
 */
class ParkedCommitTest {

    @Test
    void roundTripsEveryFieldADrainNeeds(@TempDir Path dir) throws Exception {
        ParkedCommit written = new ParkedCommit("b_0001",
                List.of(new PartitionOutput("year=2020/month=04", "/db/out_0.csv", 512L)),
                List.of(new LineageRow("b_0001", 3, "feed.csv", "/db/out_0.csv", "year=2020/month=04", 42L)),
                Map.of("/db/out_0.csv", new ParkedCommit.Bounds("2020-04-03", "2020-04-04", 86_400_000L)));
        ParkedCommit.write(dir, written);

        ParkedCommit read = ParkedCommit.read(dir, "b_0001");
        assertEquals(written, read, "a record round-trip is field-by-field or it is nothing");
        assertEquals(42L, read.lineage().get(0).rowCount());
        assertEquals(86_400_000L, read.bounds().get("/db/out_0.csv").spreadMs());
    }

    @Test
    void readRefusesWhenTheSidecarIsMissingAndDeleteIsIdempotent(@TempDir Path dir) throws Exception {
        // Loud, not empty: an absent sidecar means a drain would register half a batch.
        assertThrows(IOException.class, () -> ParkedCommit.read(dir, "absent"));

        ParkedCommit.write(dir, new ParkedCommit("b_0002", List.of(), List.of(), Map.of()));
        assertTrue(Files.exists(ParkedCommit.pathFor(dir, "b_0002")));
        ParkedCommit.delete(dir, "b_0002");
        assertFalse(Files.exists(ParkedCommit.pathFor(dir, "b_0002")));
        assertDoesNotThrow(() -> ParkedCommit.delete(dir, "b_0002"), "drain may run twice");
    }
}
