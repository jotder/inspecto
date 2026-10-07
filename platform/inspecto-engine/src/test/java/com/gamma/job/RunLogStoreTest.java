package com.gamma.job;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code AUDIT-LOG-UNBOUNDED-READ-1}: {@link RunLogStore#read} streams the run's JSONL instead of loading it
 * whole. Same answers as before — every entry in write order, blank lines skipped, and a torn trailing line
 * still fails the read loudly (it is not silently dropped).
 */
class RunLogStoreTest {

    private static final int ENTRIES = 20_000;

    /** Write {@code n} entries for {@code runId} straight to its JSONL file, blank lines interleaved. */
    private static Path writeLog(Path dir, String runId, int n) throws Exception {
        RunLogStore store = new RunLogStore(dir.toString());
        store.append(new RunLogEntry(runId, 0, "2026-09-24T00:00:00Z", "INFO", "seed", Map.of()));
        Path f = dir.resolve("runlog").resolve(runId + ".jsonl");
        String template = Files.readString(f, StandardCharsets.UTF_8).strip();
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= n; i++) {
            sb.append(template.replace("\"seq\":0", "\"seq\":" + i)).append('\n');
            if (i % 1000 == 0) sb.append('\n');
        }
        Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
        return f;
    }

    @Test
    void readsALargeLogWholeInWriteOrder(@TempDir Path dir) throws Exception {
        writeLog(dir, "r1", ENTRIES);
        List<RunLogEntry> entries = new RunLogStore(dir.toString()).read("r1");
        assertEquals(ENTRIES, entries.size(), "no cap: every entry comes back, blank lines skipped");
        assertEquals(1, entries.get(0).seq());
        assertEquals(ENTRIES, entries.get(ENTRIES - 1).seq(), "write order preserved to the last line");
    }

    @Test
    void aTornTrailingLineStillFailsTheRead(@TempDir Path dir) throws Exception {
        Path f = writeLog(dir, "r2", 3);
        Files.writeString(f, Files.readString(f) + "{\"runId\":\"r2\",\"seq\":4,\"at\":\"2026", StandardCharsets.UTF_8);
        RunLogStore store = new RunLogStore(dir.toString());
        UncheckedIOException e = assertThrows(UncheckedIOException.class, () -> store.read("r2"));
        assertTrue(e.getMessage().contains("r2"));
    }

    @Test
    void anUnknownRunReadsEmpty(@TempDir Path dir) {
        assertEquals(List.of(), new RunLogStore(dir.toString()).read("nope"));
    }
}
