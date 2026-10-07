package com.gamma.inspector;

import com.gamma.etl.PipelineConfig;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Branch-aware segment lift S6, end to end on the REAL ingest path ({@code CollectorProcessor.run}): a copy
 * of the demo {@code stock_movements} Pipeline (plugin {@code XmlRecordIngester}, segments receipt /
 * dispatch / transfer) with ONE shared {@code route:} on {@code QTY} — a column every segment maps
 * (operator Q1). Fixture: {@code src/test/resources/branch-aware-segment-lift/}, never {@code spaces/demo}.
 *
 * <p>The sample holds 12 records: 5 receipts (QTY 120/80/200/60/20), 4 dispatches (30/50/40/30),
 * 2 transfers (40/20) and 1 undeclared adjustment (skipped as junk). With {@code bulk: QTY >= 40} each
 * (branch × segment) store gets a known count — and lands at {@code <branch database>/<segKey>/…}, one store
 * per branch × segment (operator Q2).
 */
class SegmentRouteEndToEndTest {

    /** (branch database dir, segment) → rows, from the sample above. */
    private static final Map<String, Long> EXPECTED = new LinkedHashMap<>();
    static {
        EXPECTED.put("db_bulk/receipt", 4L);
        EXPECTED.put("db_normal/receipt", 1L);
        EXPECTED.put("db_bulk/dispatch", 2L);
        EXPECTED.put("db_normal/dispatch", 2L);
        EXPECTED.put("db_bulk/transfer", 1L);
        EXPECTED.put("db_normal/transfer", 1L);
    }

    @AfterEach
    void clearRetryPolicy() {
        System.clearProperty("ingest.retry.max");
    }

    @Test
    void eachSegmentRoutesItsOwnRowsToOneStorePerBranchAndSegment(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir);
        CollectorProcessor.run(cfg);

        for (Map.Entry<String, Long> e : EXPECTED.entrySet())
            assertEquals(e.getValue(), rows(dir.resolve(e.getKey())), e.getKey());
        assertFalse(Files.exists(dir.resolve("database")), "no write under the un-routed database root");

        // Lineage: every row of the ledger names the sample as its input and an output under ONE segment's
        // home, and the per-segment sums are that segment's row counts — each file attributed to its own segment.
        Map<String, Long> bySegment = lineageRowsBySegment(dir);
        assertEquals(Map.of("receipt", 5L, "dispatch", 4L, "transfer", 2L), bySegment);
        assertTrue(Files.exists(dir.resolve("backup").resolve("MOVEMENTS_WH01_20260815.xml")),
                "the commit tail ran once for the whole batch");
    }

    /**
     * Operator Q5 (2026-09-24): partial-batch semantics. The dispatch segment's first branch cannot be written
     * (a FILE blocks its store directory), so the batch FAILS after receipt's two branches are durable. The
     * file stays in the inbox; after the fault is cleared the next cycle re-ingests it under the SAME
     * content-derived Consignment id, the branch-commit ledger skips receipt's branches, and every store ends
     * with exactly the clean run's rows — no duplicates.
     */
    @Test
    void aBatchFailingAfterTheFirstSegmentCompletesOnRestartWithoutDuplicates(@TempDir Path dir) throws Exception {
        System.setProperty("ingest.retry.max", "0");   // unbounded retry, no backoff: the next cycle retries
        PipelineConfig cfg = load(dir);
        Files.createDirectories(dir.resolve("db_bulk"));
        Path blocker = Files.writeString(dir.resolve("db_bulk").resolve("dispatch"), "not a directory");

        CollectorProcessor.run(cfg);

        assertEquals(4L, rows(dir.resolve("db_bulk/receipt")), "segment 1 committed before the failure");
        assertEquals(1L, rows(dir.resolve("db_normal/receipt")));
        assertEquals(0L, rows(dir.resolve("db_normal/dispatch")), "nothing after the failing write");
        assertTrue(Files.exists(dir.resolve("inbox").resolve("MOVEMENTS_WH01_20260815.xml")),
                "a FAILED batch leaves its file in the inbox");
        try (Stream<Path> w = Files.walk(dir.resolve("temp"))) {
            assertTrue(w.anyMatch(p -> p.getFileName().toString().startsWith("branch_commit_")),
                    "the branch-commit ledger survives the failure — it is the retry's resume record");
        }

        Files.delete(blocker);
        CollectorProcessor.run(load(dir));

        for (Map.Entry<String, Long> e : EXPECTED.entrySet())
            assertEquals(e.getValue(), rows(dir.resolve(e.getKey())), "after restart: " + e.getKey());
        assertFalse(Files.exists(dir.resolve("inbox").resolve("MOVEMENTS_WH01_20260815.xml")), "committed");
        // The lineage LEDGER is whole across the two runs: the failed run's audit recorded receipt's rows.
        assertEquals(Map.of("receipt", 5L, "dispatch", 4L, "transfer", 2L), lineageRowsBySegment(dir));

        // ⚠ KNOWN GAP, pinned so it cannot be forgotten (reported 2026-09-24, NOT introduced here — a
        // single-schema route batch whose second branch fails resumes the same way): the retry SKIPS the
        // branches the ledger holds, so its commit tail never sees their outputs, and the committed manifest
        // (and so the DuckLake register / §11.3 output registry fed from it) lists dispatch + transfer only.
        // receipt's rows are durable and correct on disk, but invisible to the output registry. The park path
        // solved the same problem with the ParkedCommit sidecar; the failure path has none. Flip this
        // assertion when the resume carries the skipped branches' outputs.
        String manifest;
        try (Stream<Path> w = Files.walk(dir.resolve("status"))) {
            manifest = Files.readString(w.filter(f -> f.toString().endsWith(".json")).findFirst().orElseThrow())
                    .replace("\\\\", "/");
        }
        assertTrue(manifest.contains("/db_bulk/dispatch/") && manifest.contains("/db_normal/transfer/"), manifest);
        assertFalse(manifest.contains("/receipt/"),
                "KNOWN GAP: the resumed commit's manifest omits the skipped segment's outputs — fixed? flip this");
        try (Stream<Path> w = Files.walk(dir.resolve("temp"))) {
            assertTrue(w.noneMatch(p -> p.getFileName().toString().startsWith("branch_commit_")),
                    "the ledger is consumed once the batch commits");
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Copies the fixture into {@code dir} (pipeline + three segment schemas + the sample in the inbox). */
    private static PipelineConfig load(Path dir) throws Exception {
        Path res = Path.of(SegmentRouteEndToEndTest.class.getResource("/branch-aware-segment-lift").toURI());
        Path inbox = Files.createDirectories(dir.resolve("inbox"));
        for (String f : List.of("stock_receipt_schema.toon", "stock_dispatch_schema.toon", "stock_transfer_schema.toon"))
            Files.copy(res.resolve(f), dir.resolve(f), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Path sample = inbox.resolve("MOVEMENTS_WH01_20260815.xml");
        if (!Files.exists(sample) && !Files.exists(dir.resolve("backup").resolve(sample.getFileName())))
            Files.copy(res.resolve(sample.getFileName()), sample);
        Path toon = dir.resolve("stock_movements_routed_pipeline.toon");
        Files.writeString(toon, Files.readString(res.resolve("stock_movements_routed_pipeline.toon"))
                .replace("@DIR@", dir.toString().replace("\\", "/")));
        return PipelineConfig.load(toon.toString());
    }

    /** Rows across every Parquet file under {@code root} (0 when there is none). */
    private static long rows(Path root) throws Exception {
        if (!Files.isDirectory(root)) return 0;
        try (Stream<Path> w = Files.walk(root)) {
            if (w.noneMatch(p -> p.toString().endsWith(".parquet") && !p.toString().contains(".staging"))) return 0;
        }
        try (Connection c = DuckDbUtil.openConnection(Files.createTempDirectory("rows").resolve("r.duckdb").toFile());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM read_parquet('"
                     + root.toString().replace('\\', '/') + "/*/**/*.parquet')")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** The lineage ledger's row counts summed by the segment each output file lives under. */
    private static Map<String, Long> lineageRowsBySegment(Path dir) throws Exception {
        Map<String, Long> out = new LinkedHashMap<>();
        try (Stream<Path> w = Files.walk(dir.resolve("status"))) {
            for (Path p : w.filter(f -> f.getFileName().toString().contains("_lineage_")).toList())
                for (String line : Files.readAllLines(p).stream().skip(1).toList()) {
                    String norm = line.replace('\\', '/');
                    assertTrue(norm.contains("MOVEMENTS_WH01_20260815.xml"), "input is the sample: " + line);
                    String seg = null;
                    for (String s : List.of("receipt", "dispatch", "transfer"))
                        if (norm.contains("/db_bulk/" + s + "/") || norm.contains("/db_normal/" + s + "/")) {
                            assertNull(seg, "one segment per output file: " + line);
                            seg = s;
                        }
                    assertNotNull(seg, "every output lives under a segment's own home: " + line);
                    out.merge(seg, Long.parseLong(line.substring(line.lastIndexOf(',') + 1).trim()), Long::sum);
                }
        }
        return out;
    }
}
