package com.gamma.consignment;

import com.gamma.etl.LineageRow;
import com.gamma.etl.PartitionOutput;
import com.gamma.util.JdbcDrivers;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ConsignmentOutputs} — the mapping half of the §11.3 registry (slice 2). These cover the one field
 * {@code PartitionOutput} cannot supply: {@code row_count}, which has to be derived per output file either by
 * summing {@code LineageCollector}'s matrix (ingest) or by a {@code GROUP BY} over the written relation
 * (enrichment / Pipeline sinks).
 */
class ConsignmentOutputsTest {

    private static PartitionOutput out(String partition, String path, long bytes) {
        return new PartitionOutput(partition, path, bytes);
    }

    private static LineageRow lineage(String outputFile, String partition, long rows) {
        return new LineageRow("c1", 1, "in.csv", outputFile, partition, rows);
    }

    // ── row_count from the lineage matrix (the ingest path) ──────────────────────

    /**
     * The whole reason a registry row cannot be a field copy: {@code LineageCollector} counts per
     * {@code (srcId, partition)}, so several rows share one output file and must be summed. §7.2's
     * reconciliation is exactly this sum matching the detail count.
     */
    @Test
    void sumsEveryLineageRowSharingAnOutputFile() {
        List<PartitionOutput> outputs = List.of(
                out("year=2026/month=07/day=01", "/w/d1/f_out.parquet", 100),
                out("year=2026/month=07/day=02", "/w/d2/f_out.parquet", 200));
        List<LineageRow> lineage = List.of(
                lineage("/w/d1/f_out.parquet", "year=2026/month=07/day=01", 3),
                lineage("/w/d1/f_out.parquet", "year=2026/month=07/day=01", 4),   // second member, same file
                lineage("/w/d2/f_out.parquet", "year=2026/month=07/day=02", 5));

        List<ConsignmentOutput> rows = ConsignmentOutputs.fromLineage("c1", null, "cdr", outputs, lineage);

        assertEquals(2, rows.size(), "one registry row per output file, not per lineage cell");
        assertEquals(7L, rows.get(0).rows(), "3 + 4 summed across the two members writing this file");
        assertEquals(5L, rows.get(1).rows());
        assertEquals(12L, rows.stream().mapToLong(ConsignmentOutput::rows).sum());
        assertEquals(100L, rows.get(0).bytes(), "bytes still come straight from PartitionOutput");
    }

    /** A lineage cell whose partition had no revealed file belongs to no registry row. */
    @Test
    void ignoresLineageRowsWithNoOutputFile() {
        List<PartitionOutput> outputs = List.of(out("year=2026/month=07/day=01", "/w/d1/f_out.parquet", 10));
        List<ConsignmentOutput> rows = ConsignmentOutputs.fromLineage("c1", null, "cdr", outputs, List.of(
                lineage("/w/d1/f_out.parquet", "year=2026/month=07/day=01", 6),
                lineage("", "year=2026/month=07/day=09", 99)));

        assertEquals(1, rows.size());
        assertEquals(6L, rows.get(0).rows(), "the fileless cell must not inflate any file's count");
    }

    /** An output file no lineage row names counts 0 rather than throwing — a count is never invented. */
    @Test
    void unmatchedOutputFileCountsZero() {
        List<ConsignmentOutput> rows = ConsignmentOutputs.fromLineage("c1", null, "cdr",
                List.of(out("year=2026/month=07/day=01", "/w/d1/f_out.parquet", 10)), List.of());
        assertEquals(1, rows.size());
        assertEquals(0L, rows.get(0).rows());
    }

    @Test
    void stampsEveryRowLiveAtGenerationZero() {
        List<ConsignmentOutput> rows = ConsignmentOutputs.fromLineage("c1", "r1", "cdr",
                List.of(out("year=2026/month=07/day=01", "/w/d1/f_out.parquet", 10)),
                List.of(lineage("/w/d1/f_out.parquet", "year=2026/month=07/day=01", 1)));

        ConsignmentOutput o = rows.get(0);
        assertEquals(ConsignmentOutput.State.LIVE, o.state());
        assertEquals(0, o.generation(), "a fresh write is generation 0; compaction bumps it");
        assertEquals("c1", o.consignmentId());
        assertEquals("r1", o.runId());
        assertEquals("cdr", o.tableName());
        assertNotNull(o.writtenAt());
    }

    @Test
    void noOutputsYieldsNoRows() {
        assertTrue(ConsignmentOutputs.fromLineage("c1", null, "cdr", List.of(), List.of()).isEmpty());
        assertTrue(ConsignmentOutputs.fromLineage("c1", null, "cdr", null, null).isEmpty());
    }

    // ── row_count from a GROUP BY (enrichment / Pipeline sinks) ──────────────────

    /** The partition keys this builds must join straight onto {@link PartitionOutput#partition()}. */
    @Test
    void countsPerPartitionKeyedLikePartitionWriterNamesItsDirectories() throws Exception {
        try (Connection conn = JdbcDrivers.connect("jdbc:duckdb:")) {
            try (Statement st = conn.createStatement()) {
                st.execute("""
                        CREATE TABLE t AS SELECT * FROM (VALUES
                          ('2026','07','01'), ('2026','07','01'), ('2026','07','02')
                        ) v(year, month, day)""");
            }
            Map<String, Long> counts =
                    ConsignmentOutputs.countByPartition(conn, "t", List.of("year", "month", "day"));

            assertEquals(2, counts.size());
            assertEquals(2L, counts.get("year=2026/month=07/day=01").longValue());
            assertEquals(1L, counts.get("year=2026/month=07/day=02").longValue());
        }
    }

    /** An unpartitioned sink reports partition {@code ""}, so the whole-table count lands under that key. */
    @Test
    void countsWholeTableUnderTheEmptyKeyWhenUnpartitioned() throws Exception {
        try (Connection conn = JdbcDrivers.connect("jdbc:duckdb:")) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE t AS SELECT * FROM (VALUES (1),(2),(3)) v(x)");
            }
            Map<String, Long> counts = ConsignmentOutputs.countByPartition(conn, "t", List.of());
            assertEquals(Map.of("", 3L), counts);

            List<ConsignmentOutput> rows = ConsignmentOutputs.fromPartitionCounts(
                    "c1", null, "store", List.of(out("", "/w/store/f_out.parquet", 40)), counts);
            assertEquals(3L, rows.get(0).rows());
        }
    }

    /**
     * A multi-destination fan-out writes the same partition once per destination. Each file really does hold
     * those rows, so each registry row repeats the count — the sum is per-file, not per-Consignment.
     */
    @Test
    void repeatsThePartitionCountForEachDestinationsFile() {
        Map<String, Long> counts = Map.of("year=2026/month=07/day=01", 4L);
        List<ConsignmentOutput> rows = ConsignmentOutputs.fromPartitionCounts("c1", null, "cdr", List.of(
                out("year=2026/month=07/day=01", "/dest_a/f_out.parquet", 10),
                out("year=2026/month=07/day=01", "/dest_b/f_out.parquet", 10)), counts);

        assertEquals(2, rows.size());
        assertEquals(4L, rows.get(0).rows());
        assertEquals(4L, rows.get(1).rows());
    }

    @Test
    void unmatchedPartitionCountsZero() {
        List<ConsignmentOutput> rows = ConsignmentOutputs.fromPartitionCounts("c1", null, "cdr",
                List.of(out("year=2026/month=07/day=01", "/w/f_out.parquet", 10)), Map.of());
        assertEquals(0L, rows.get(0).rows());
    }

    // ── record_day derivation (write-time approximation; §10.1 supersedes it) ────

    @Test
    void derivesRecordDayFromYearMonthDaySegments() {
        assertEquals("2026-07-01", ConsignmentOutputs.recordDay("year=2026/month=07/day=01"));
        assertEquals("2026-07-01", ConsignmentOutputs.recordDay("year=2026/month=7/day=1"),
                "unpadded partition values still yield an ISO date");
        assertEquals("2026-07-01", ConsignmentOutputs.recordDay("event_type=CALL/year=2026/month=07/day=01"),
                "a leading non-date partition column must not defeat the lookup");
    }

    /** No triple ⇒ null, never a guess: a wrong record_day silently mis-buckets a file for §10.1. */
    @Test
    void yieldsNullWhenThePartitionSchemeCarriesNoDayTriple() {
        assertNull(ConsignmentOutputs.recordDay("dt=2026-08-04"));
        assertNull(ConsignmentOutputs.recordDay("year=2026/month=07"));
        assertNull(ConsignmentOutputs.recordDay("region=EU"));
        assertNull(ConsignmentOutputs.recordDay(""));
        assertNull(ConsignmentOutputs.recordDay(null));
    }

    /** Partition values are free text — a non-numeric one degrades to null instead of throwing. */
    @Test
    void yieldsNullOnNonNumericPartitionValues() {
        assertNull(ConsignmentOutputs.recordDay("year=2026/month=JUL/day=01"));
        assertNull(ConsignmentOutputs.recordDay("year=/month=07/day=01"));
    }
}
