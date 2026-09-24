package com.gamma.job;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** P1d Run Artifacts (§10): recording through {@code ctx.artifacts()} persists to JSONL and reads back
 *  with the {@link ResultSetMeta} intact — the round trip the Control API + $upstream depend on. */
class RunArtifactStoreTest {

    @Test
    void recordsAndReadsBackDatasetAndFileArtifacts(@TempDir Path dir) {
        RunArtifactStore store = new RunArtifactStore(dir.toString());
        RunContext ctx = new RunContext("r1", "default", "loader", "manual", "r1", null, 0,
                Map.of(), new RunLogStore(dir.toString()), 100, store);

        ResultSetMeta meta = new ResultSetMeta(List.of(
                new ResultSetMeta.Column("account_id", "BIGINT", ResultSetMeta.Role.DIMENSION),
                new ResultSetMeta.Column("total", "DECIMAL", ResultSetMeta.Role.MEASURE)));
        ctx.artifacts().dataset("output", "txn_rollup", meta, 4200L, Instant.parse("2026-07-07T06:00:04Z"));
        ctx.artifacts().file("export", dir.resolve("out.csv"), 1024L);

        List<RunArtifact> arts = store.read("r1");
        assertEquals(2, arts.size(), "both artifacts persisted");

        RunArtifact d = arts.get(0);
        assertEquals("dataset", d.kind());
        assertEquals("txn_rollup", d.ref());
        assertEquals(4200L, d.rows());
        assertEquals("2026-07-07T06:00:04Z", d.watermark());
        assertEquals(1, d.seq());
        assertNotNull(d.resultSet());
        assertEquals(2, d.resultSet().columns().size());
        assertEquals(ResultSetMeta.Role.MEASURE, d.resultSet().columns().get(1).role(),
                "ResultSetMeta (columns + roles) survives the JSON round trip");

        RunArtifact f = arts.get(1);
        assertEquals("file", f.kind());
        assertEquals(1024L, f.bytes());
        assertNull(f.resultSet(), "a file artifact has no result-set shape");
        assertEquals(2, f.seq(), "seq is monotonic across a run's artifacts");
        assertNull(f.detail(), "dataset/file artifacts carry no detail");
    }

    /** DUCKLE-C4: the parameter receipt is one more artifact on the run, and survives the JSONL round trip. */
    @Test
    void recordsTheParameterReceiptAsAnArtifact(@TempDir Path dir) {
        RunArtifactStore store = new RunArtifactStore(dir.toString());
        RunContext ctx = new RunContext("r2", "default", "loader", "manual", "r2", null, 0,
                Map.of(), new RunLogStore(dir.toString()), 100, store);

        ctx.artifacts().params(Map.of("day", Map.of("source", "args", "overrode", List.of("config"))));

        List<RunArtifact> arts = store.read("r2");
        assertEquals(1, arts.size());
        assertEquals("params", arts.get(0).kind());
        @SuppressWarnings("unchecked")
        Map<String, Object> day = (Map<String, Object>) arts.get(0).detail().get("day");
        assertEquals("args", day.get("source"));
        assertEquals(List.of("config"), day.get("overrode"));
    }

    @Test
    void unknownRunReadsEmpty(@TempDir Path dir) {
        assertTrue(new RunArtifactStore(dir.toString()).read("never-ran").isEmpty());
    }

    /** AUDIT-LOG-UNBOUNDED-READ-1: the streamed read returns a large file whole, in write order, no cap. */
    @Test
    void readsALargeArtifactFileWholeInWriteOrder(@TempDir Path dir) throws Exception {
        Path f = seedFile(dir, "r3");
        String template = Files.readString(f, StandardCharsets.UTF_8).strip();
        StringBuilder sb = new StringBuilder();
        int n = 20_000;
        for (int i = 1; i <= n; i++) sb.append(template.replace("\"seq\":0", "\"seq\":" + i)).append("\n\n");
        Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);

        List<RunArtifact> arts = new RunArtifactStore(dir.toString()).read("r3");
        assertEquals(n, arts.size(), "every artifact comes back, blank lines skipped");
        assertEquals(1, arts.get(0).seq());
        assertEquals(n, arts.get(n - 1).seq());
    }

    /** AUDIT-LOG-UNBOUNDED-READ-1: a torn trailing line still fails the read, exactly as the whole-file read did. */
    @Test
    void aTornTrailingLineStillFailsTheRead(@TempDir Path dir) throws Exception {
        Path f = seedFile(dir, "r4");
        Files.writeString(f, Files.readString(f) + "{\"runId\":\"r4\",\"job\":\"lo", StandardCharsets.UTF_8);
        RunArtifactStore store = new RunArtifactStore(dir.toString());
        assertThrows(java.io.UncheckedIOException.class, () -> store.read("r4"));
    }

    private static Path seedFile(Path dir, String runId) {
        new RunArtifactStore(dir.toString()).append(new RunArtifact(runId, "loader", 0, "output", "file",
                "out.csv", null, 0L, 10L, null, "2026-09-24T00:00:00Z", null));
        return dir.resolve("artifacts").resolve(runId + ".jsonl");
    }
}
