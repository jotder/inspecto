package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ConsignmentAuditWriterTest {

    @Test
    void writesHeadersAndRows(@TempDir Path dir) throws Exception {
        String statusCsv  = dir.resolve("p_status_TS.csv").toString();
        String batchesCsv = dir.resolve("p_batches_TS.csv").toString();
        String lineageCsv = dir.resolve("p_lineage_TS.csv").toString();
        ConsignmentAuditWriter w = new ConsignmentAuditWriter(statusCsv, batchesCsv, lineageCsv);

        var fileRows = List.of(
                new ConsignmentAuditWriter.FileRow("2026-05-27 10:30:00", "2026-05-27 10:30:01",
                        "a.csv", "SUCCESS", 2, 0, List.of("/db/B1_out.csv"), List.of(120L), 1000, "", "B1"),
                new ConsignmentAuditWriter.FileRow("2026-05-27 10:30:00", "2026-05-27 10:30:01",
                        "bad.csv", "QUARANTINED_MISMATCH", 0, 3, List.of(), List.of(), 50, "0 valid rows", "B1"));
        var batchRow = new ConsignmentAuditWriter.ConsignmentRow("B1", "mini_etl", "mini", "",
                "2026-05-27 10:30:00", "2026-05-27 10:30:02", "SUCCESS",
                2, 1, 2, 2, 1, 120L, 2000, "");
        var lineage = List.of(new LineageRow("B1", 0, "a.csv", "/db/B1_out.csv", "year=2020/month=04/day=03", 2));

        w.flush(batchRow, fileRows, lineage);

        String status = Files.readString(Path.of(statusCsv));
        assertTrue(status.startsWith("start_time,end_time,filename,status,parsed_rows,error_rows,output_paths,output_sizes_bytes,duration_ms,error,consignment_id"));
        assertTrue(status.contains("a.csv"));
        assertTrue(status.contains("QUARANTINED_MISMATCH"));

        String batches = Files.readString(Path.of(batchesCsv));
        assertTrue(batches.contains("consignment_id,pipeline,schema_name,output_table"));
        assertTrue(batches.contains("B1"));
        // cast_failures is the last column and this row never measured it — a BLANK cell, not 0,
        // so an unmeasured coercion count can never be read as a clean batch.
        assertTrue(batches.contains("duration_ms,error,cast_failures"), batches);
        assertTrue(batches.lines().filter(l -> l.startsWith("B1")).findFirst().orElseThrow().endsWith(",\"\","),
                "unmeasured writes a trailing blank: " + batches);

        String lin = Files.readString(Path.of(lineageCsv));
        assertTrue(lin.startsWith("consignment_id,src_id,input_file,output_file,partition,row_count"));
        assertTrue(lin.contains("year=2020/month=04/day=03"));
    }

    /**
     * Unpack plan step 4d: the additive {@code logical_name} column round-trips, and the two
     * shorter arities keep writing a blank for it — the compatibility that lets a caller which
     * never resolved an identity (and every pre-unpack caller) go on constructing a row.
     *
     * <p>⚠ It is QUOTED in the codec: a logical name keeps its poll-relative path, and a path may
     * carry a comma — unquoted, one such name would shift every later column by one on read.
     */
    @Test
    void logicalNameIsTheLastColumnAndRoundTrips(@TempDir Path dir) throws Exception {
        String statusCsv = dir.resolve("p_status_TS.csv").toString();
        ConsignmentAuditWriter w = new ConsignmentAuditWriter(statusCsv,
                dir.resolve("p_batches_TS.csv").toString(), dir.resolve("p_lineage_TS.csv").toString());

        var rows = List.of(
                // full arity: an expanded entry carries its ARCHIVE's origin and identity
                new ConsignmentAuditWriter.FileRow("t0", "t1", "00001_a.csv", "SUCCESS", 2, 0,
                        List.of(), List.of(), 10, "", "B1", "data.zip", "east/data"),
                // a comma in the identity must not shift the columns
                new ConsignmentAuditWriter.FileRow("t0", "t1", "b.csv", "SUCCESS", 1, 0,
                        List.of(), List.of(), 10, "", "B1", "", "od,d/name"),
                // origin-only arity → blank identity
                new ConsignmentAuditWriter.FileRow("t0", "t1", "c.csv", "SUCCESS", 1, 0,
                        List.of(), List.of(), 10, "", "B1", "c.csv.gz"),
                // pre-unpack arity → both blank
                new ConsignmentAuditWriter.FileRow("t0", "t1", "d.csv", "SUCCESS", 1, 0,
                        List.of(), List.of(), 10, "", "B1"));
        w.flush(new ConsignmentAuditWriter.ConsignmentRow("B1", "p", "s", "", "t0", "t1", "SUCCESS",
                4, 0, 5, 5, 1, 1L, 20, ""), rows, List.of());

        List<String> lines = Files.readAllLines(Path.of(statusCsv));
        assertTrue(lines.get(0).endsWith(",origin,logical_name"),
                "appended last, after origin: " + lines.get(0));

        // the reader parses by header NAME, so this is the shape every consumer sees
        List<java.util.Map<String, String>> read = new java.util.ArrayList<>();
        com.gamma.util.Csv.readInto(Path.of(statusCsv), read);
        assertEquals(4, read.size());
        assertEquals("east/data", read.get(0).get("logical_name"));
        assertEquals("data.zip", read.get(0).get("origin"));
        // the comma survived quoting AND did not displace the neighbouring column
        assertEquals("od,d/name", read.get(1).get("logical_name"));
        assertEquals("b.csv", read.get(1).get("filename"));
        assertEquals("", read.get(2).get("logical_name"), "origin-only arity writes a blank identity");
        assertEquals("c.csv.gz", read.get(2).get("origin"));
        assertEquals("", read.get(3).get("logical_name"), "pre-unpack arity writes both blank");
        assertEquals("", read.get(3).get("origin"));
    }

    /**
     * The other half of "additive": a ledger written BEFORE the column existed still parses, and
     * simply carries no such key — never a null-shifted row. This is the guarantee that lets the
     * column be appended at all.
     */
    @Test
    void aLedgerWrittenBeforeTheColumnStillParses(@TempDir Path dir) throws Exception {
        Path old = dir.resolve("p_status_OLD.csv");
        Files.writeString(old,
                "start_time,end_time,filename,status,parsed_rows,error_rows,output_paths,"
                        + "output_sizes_bytes,duration_ms,error,consignment_id\n"
                        + "t0,t1,a.csv,SUCCESS,2,0,\"\",\"\",10,\"\",B1\n");

        List<java.util.Map<String, String>> read = new java.util.ArrayList<>();
        com.gamma.util.Csv.readInto(old, read);

        assertEquals(1, read.size());
        assertEquals("a.csv", read.get(0).get("filename"));
        assertEquals("B1", read.get(0).get("consignment_id"), "the pre-existing columns still line up");
        assertNull(read.get(0).get("logical_name"), "an absent column is absent, never a shifted value");
        assertNull(read.get(0).get("origin"));
    }

    /**
     * E4 (delimited-grammar-properties plan Part II): the finalization-concurrency pin. The class
     * documents that {@code flush} is {@code synchronized} so "each batch's rows are written
     * contiguously even when multiple batches finish concurrently" — this converts that structural
     * claim into a pinned one: N batches flush from N threads, and afterwards (1) no batch row is
     * lost, (2) every status-ledger consignment block is CONTIGUOUS, (3) no lineage line is torn.
     * Removing the {@code synchronized} makes this test fail (verified by mutation while writing it
     * conceptually: interleaved writes split a consignment's block and tear lines).
     */
    @Test
    void concurrentFlushesKeepEachBatchBlockContiguous(@TempDir Path dir) throws Exception {
        String statusCsv = dir.resolve("s.csv").toString();
        String batchesCsv = dir.resolve("b.csv").toString();
        String lineageCsv = dir.resolve("l.csv").toString();
        ConsignmentAuditWriter w = new ConsignmentAuditWriter(statusCsv, batchesCsv, lineageCsv);

        final int batches = 24, filesPerBatch = 20;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        for (int b = 0; b < batches; b++) {
            final String id = "BX" + String.format("%03d", b);
            futures.add(pool.submit(() -> {
                java.util.List<ConsignmentAuditWriter.FileRow> rows = new java.util.ArrayList<>();
                java.util.List<LineageRow> lineage = new java.util.ArrayList<>();
                for (int f = 0; f < filesPerBatch; f++) {
                    rows.add(new ConsignmentAuditWriter.FileRow("t0", "t1", id + "_f" + f + ".csv", "SUCCESS",
                            1, 0, List.of("/db/" + id + "_out.csv"), List.of(1L), 1, "", id));
                    lineage.add(new LineageRow(id, f, id + "_f" + f + ".csv",
                            "/db/" + id + "_out.csv", "", 1));
                }
                w.flush(new ConsignmentAuditWriter.ConsignmentRow(id, "p", "s", "", "t0", "t2", "SUCCESS",
                        filesPerBatch, 0, filesPerBatch, filesPerBatch, 1, 1L, 1, ""), rows, lineage);
            }));
        }
        for (var f : futures) f.get();
        pool.shutdown();

        // (1) no batch row lost
        List<String> batchLines = Files.readAllLines(Path.of(batchesCsv));
        assertEquals(batches, batchLines.stream().filter(l -> l.startsWith("BX")).count(),
                "every concurrently finalized batch has its ledger row");

        // (2) each consignment's status rows form ONE contiguous block
        List<String> statusLines = Files.readAllLines(Path.of(statusCsv));
        String current = null;
        java.util.Set<String> closed = new java.util.HashSet<>();
        for (String line : statusLines.subList(1, statusLines.size())) {
            String id = line.substring(line.lastIndexOf(',') + 1);
            if (!id.equals(current)) {
                assertFalse(closed.contains(id), "consignment " + id + " appears in two separated blocks");
                if (current != null) closed.add(current);
                current = id;
            }
        }
        assertEquals(batches * filesPerBatch, statusLines.size() - 1, "no status row lost");

        // (3) no torn lineage line — every line carries exactly the header's column count
        List<String> lineageLines = Files.readAllLines(Path.of(lineageCsv));
        long cols = lineageLines.get(0).chars().filter(c -> c == ',').count();
        for (String line : lineageLines) {
            assertEquals(cols, line.chars().filter(c -> c == ',').count(), "torn lineage line: " + line);
        }
        assertEquals(batches * filesPerBatch, lineageLines.size() - 1, "no lineage row lost");
    }

    /** v3.7.0: a FAILED batch's emitted event carries error detail (error/offendingFile/errorRows). */
    @Test
    void emittedEventCarriesErrorDetailOnFailure(@TempDir Path dir) {
        ConsignmentAuditWriter w = new ConsignmentAuditWriter(
                dir.resolve("s.csv").toString(), dir.resolve("b.csv").toString(),
                dir.resolve("l.csv").toString());
        AtomicReference<ConsignmentEvent> seen = new AtomicReference<>();
        w.setCommitListener(seen::set);

        var fileRows = List.of(
                new ConsignmentAuditWriter.FileRow("t0", "t1", "good.csv", "SUCCESS",
                        2, 0, List.of("/db/out.csv"), List.of(10L), 5, "", "B9"),
                new ConsignmentAuditWriter.FileRow("t0", "t1", "bad.csv", "QUARANTINED_MISMATCH",
                        0, 3, List.of(), List.of(), 5, "schema selector mismatch", "B9"));
        var batchRow = new ConsignmentAuditWriter.ConsignmentRow("B9", "mini_etl", "mini", "",
                "t0", "t2", "FAILED", 2, 1, 2, 0, 0, 0L, 20, "batch failed: schema selector mismatch");

        w.flush(batchRow, fileRows, List.of());

        ConsignmentEvent ev = seen.get();
        assertNotNull(ev, "a terminal batch emits an event");
        assertEquals("FAILED", ev.status());
        assertEquals("batch failed: schema selector mismatch", ev.error());
        assertEquals("bad.csv", ev.offendingFile(), "first member file with an error");
        assertEquals(3L, ev.errorRows(), "sum of member error rows");
    }
}
