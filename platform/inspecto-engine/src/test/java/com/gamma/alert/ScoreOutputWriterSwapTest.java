package com.gamma.alert;

import com.gamma.analytics.score.ScoreColumns;
import com.gamma.analytics.score.ScoreKind;
import com.gamma.analytics.score.ScoreRunFiles;
import com.gamma.job.SnapshotSwapLock;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SNAPSHOT-SWAP-UNLOCKED-READERS-1, the score output writer: its retention prune deletes history runs a reader may have
 * pinned, so it waits for the readers holding the history; and a run sweeps the {@code .tmp}/{@code .stale} files a
 * crashed run left behind, as the snapshot Jobs do.
 */
class ScoreOutputWriterSwapTest {

    private static final ScoreKind KIND = new ScoreKind("test-score", "test_scores_", ".test-score-output");

    private static List<String> names(Path dir) throws Exception {
        List<String> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            ds.forEach(p -> out.add(p.getFileName().toString()));
        }
        out.sort(null);
        return out;
    }

    @Test
    void theRetentionPruneWaitsForAReaderOfTheHistory(@TempDir Path history) throws Exception {
        String older = ScoreRunFiles.name(Instant.parse("2026-11-01T00:00:00Z"), "r1");
        String newer = ScoreRunFiles.name(Instant.parse("2026-11-02T00:00:00Z"), "r2");
        Files.writeString(history.resolve(older), "run 1");
        Files.writeString(history.resolve(newer), "run 2");
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread pruner = new Thread(() -> {
            try {
                ScoreOutputWriter.prune(history, new ScoreRunFiles.Retention(null, 1), Instant.now(), newer);
            } catch (Throwable t) {
                failed.set(t);
            }
        });

        try (SnapshotSwapLock.Held reading = SnapshotSwapLock.read(List.of(history))) {
            pruner.start();
            long deadline = System.nanoTime() + 30_000_000_000L;
            while (pruner.isAlive() && !SnapshotSwapLock.hasQueuedThreads(history)) {
                if (System.nanoTime() > deadline) fail("the prune neither finished nor waited for the reader");
                Thread.sleep(5);
            }
            assertTrue(pruner.isAlive(), "the prune waits for the reader (failure: " + failed.get() + ")");
            assertEquals(List.of(older, newer), names(history), "the run the reader may hold is still there");
        }
        pruner.join(30_000);

        assertNull(failed.get());
        assertEquals(List.of(newer), names(history));
    }

    @Test
    void aRunSweepsTheLeftoversOfACrashedRun(@TempDir Path data) throws Exception {
        DuckDbUtil.loadDriver();
        List<ScoreColumns.Column> cols = List.of(ScoreColumns.Column.measure("score", "DOUBLE"));
        Instant first = Instant.parse("2026-11-01T00:00:00Z");
        Path history = ScoreOutputWriter.write(data, KIND, "m1", "msisdn", cols,
                List.of(new ScoreOutputWriter.Row("e1", List.of(0.5))), "v1", "r1", first, ScoreRunFiles.Retention.OFF);
        Path latest = data.resolve(KIND.latest("m1"));
        // What a run that died mid-write or mid-swap leaves: unrevealed new files and a hidden old one.
        Files.writeString(history.resolve(ScoreRunFiles.name(first.plusSeconds(60), "dead") + ".tmp"), "x");
        Files.writeString(latest.resolve(ScoreRunFiles.name(first.plusSeconds(60), "dead") + ".tmp"), "x");
        Files.writeString(latest.resolve(ScoreRunFiles.name(first, "r1") + ".stale"), "x");

        Instant second = first.plusSeconds(120);
        ScoreOutputWriter.write(data, KIND, "m1", "msisdn", cols,
                List.of(new ScoreOutputWriter.Row("e1", List.of(0.7))), "v1", "r2", second, ScoreRunFiles.Retention.OFF);

        assertEquals(List.of(KIND.ownerMarker(), ScoreRunFiles.name(first, "r1"), ScoreRunFiles.name(second, "r2")),
                names(history), "no .tmp is left in the history");
        assertEquals(List.of(KIND.ownerMarker(), ScoreRunFiles.name(second, "r2")), names(latest),
                "no .tmp or .stale is left in _latest");
    }
}
