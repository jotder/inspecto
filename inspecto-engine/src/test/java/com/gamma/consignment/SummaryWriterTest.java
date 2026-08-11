package com.gamma.consignment;

import com.gamma.util.JdbcDrivers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §7.3 — the durable summary tier: one Parquet file per (Consignment × record-day), partitioned by
 * {@code record_day}, with a sidecar carrying §7.2 composability so a reader cannot sum an average.
 */
class SummaryWriterTest {

    private static SummaryRow row(String target, String day, Measure... measures) {
        Map<String, String> keys = new LinkedHashMap<>();
        if (day != null) keys.put(SummaryWriter.RECORD_DAY, day);
        return new SummaryRow(target, keys, List.of(measures));
    }

    private static List<ConsignmentOutput> write(Path root, String consignmentId, List<SummaryRow> rows)
            throws Exception {
        return write(root, consignmentId, rows, null);
    }

    private static List<ConsignmentOutput> write(Path root, String consignmentId, List<SummaryRow> rows,
                                                 String producer) throws Exception {
        try (Connection c = JdbcDrivers.connect("jdbc:duckdb:")) {
            return SummaryWriter.write(c, root.toString(), consignmentId, rows, producer);
        }
    }

    /** Read a written Parquet file back through DuckDB — proving the bytes are queryable, not just present. */
    private static List<Map<String, Object>> read(String path) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = JdbcDrivers.connect("jdbc:duckdb:"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM read_parquet('"
                     + path.replace('\\', '/').replace("'", "''") + "')")) {
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++)
                    m.put(rs.getMetaData().getColumnName(i), rs.getObject(i));
                out.add(m);
            }
        }
        return out;
    }

    // ── the §7.3 layout ──────────────────────────────────────────────────────────

    @Test
    void writesOneFilePerRecordDayPartitionedByDay(@TempDir Path root) throws Exception {
        List<ConsignmentOutput> written = write(root, "c1", List.of(
                row("cdr", "2026-07-01", Measure.additive("count", 7)),
                row("cdr", "2026-07-02", Measure.additive("count", 9))));

        assertEquals(2, written.size(), "one file per (Consignment × record-day)");
        assertEquals(List.of("record_day=2026-07-01", "record_day=2026-07-02"),
                written.stream().map(ConsignmentOutput::partitionKey).sorted().toList());
        assertTrue(Files.exists(Path.of(written.get(0).path())));
        // The file is named for its Consignment; the `_summaries` root segment is the ADAPTER's concern
        // (ConsignmentProcessJobType.summariesRoot), proved in its own test — this writer takes a root as given.
        assertTrue(written.get(0).path().endsWith("c1_summary_out.parquet"), written.get(0).path());
        assertEquals(List.of("2026-07-01", "2026-07-02"),
                written.stream().map(ConsignmentOutput::recordDay).sorted().toList());
    }

    /** The file must be readable and hold the measures at the right grain. */
    @Test
    void writesMeasuresAsQueryableColumns(@TempDir Path root) throws Exception {
        List<ConsignmentOutput> written = write(root, "c1", List.of(
                row("cdr", "2026-07-01",
                        Measure.additive("count", 7),
                        Measure.additive("duration_sum", 42.5))));

        List<Map<String, Object>> back = read(written.get(0).path());
        assertEquals(1, back.size());
        assertEquals(7.0d, ((Number) back.get(0).get("count")).doubleValue());
        assertEquals(42.5d, ((Number) back.get(0).get("duration_sum")).doubleValue());
    }

    /** §5.1: a second Consignment adds a file to the same day, it never rewrites the first. */
    @Test
    void twoConsignmentsShareADayWithoutOverwritingEachOther(@TempDir Path root) throws Exception {
        write(root, "c1", List.of(row("cdr", "2026-07-01", Measure.additive("count", 7))));
        write(root, "c2", List.of(row("cdr", "2026-07-01", Measure.additive("count", 9))));

        Path dayDir = root.resolve("cdr").resolve("record_day=2026-07-01");
        try (Stream<Path> files = Files.list(dayDir)) {
            List<String> names = files.map(p -> p.getFileName().toString()).sorted().toList();
            assertEquals(List.of("c1_summary_out.parquet", "c2_summary_out.parquet"), names,
                    "append-only: one file per Consignment, no read-modify-write");
        }
    }

    /** Per-partition row_count is what makes the registry rows reconcilable (§7.2). */
    @Test
    void registersARealRowCountPerPartition(@TempDir Path root) throws Exception {
        List<ConsignmentOutput> written = write(root, "c1", List.of(
                row("cdr", "2026-07-01", Measure.additive("count", 1)),
                row("cdr", "2026-07-01", Measure.additive("count", 2)),
                row("cdr", "2026-07-02", Measure.additive("count", 3))));

        Map<String, Long> byPartition = new LinkedHashMap<>();
        for (ConsignmentOutput o : written) byPartition.put(o.partitionKey(), o.rows());
        assertEquals(2L, byPartition.get("record_day=2026-07-01"), "two summary rows landed in that day");
        assertEquals(1L, byPartition.get("record_day=2026-07-02"));
    }

    /**
     * The suffix is load-bearing: {@code reconcile()} sums detail row counts BY TABLE NAME, so a summary
     * registered under the target's own name would inflate the detail total and break §7.2's check.
     */
    @Test
    void registersUnderASuffixedTableNameSoDetailCountsStayClean(@TempDir Path root) throws Exception {
        List<ConsignmentOutput> written = write(root, "c1",
                List.of(row("cdr", "2026-07-01", Measure.additive("count", 7))));

        assertEquals("cdr__summary", written.get(0).tableName());

        // Prove the consequence, not just the string: reconcile() must still see only the detail rows.
        GuardedSummaryEmitter emitter = new GuardedSummaryEmitter();
        emitter.emit(row("cdr", "2026-07-01", Measure.additive("count", 7)));
        List<ConsignmentOutput> registry = new ArrayList<>(written);
        registry.add(new ConsignmentOutput("c1", null, "cdr", "record_day=2026-07-01", "2026-07-01",
                "/w/detail.parquet", 7L, 100L, "2026-08-04T10:00:00Z", 0, ConsignmentOutput.State.LIVE));
        assertTrue(emitter.reconcile(registry).isEmpty(),
                "7 summarised vs 7 detail must reconcile — the summary file must not be counted as detail");
    }

    // ── the §7.2 sidecar ─────────────────────────────────────────────────────────

    @Test
    void writesASidecarNamingEachMeasuresComposability(@TempDir Path root) throws Exception {
        write(root, "c1", List.of(row("cdr", "2026-07-01",
                Measure.additive("count", 7),
                new Measure("duration_avg", Measure.Composability.COMPUTED_FROM_DETAIL, 3.5))));

        String sidecar = Files.readString(root.resolve("cdr").resolve(SummaryWriter.MEASURES_SIDECAR));
        assertTrue(sidecar.startsWith("measure,composability"), sidecar);
        assertTrue(sidecar.contains("count,ADDITIVE"), sidecar);
        assertTrue(sidecar.contains("duration_avg,COMPUTED_FROM_DETAIL"),
                "a reader must be able to tell it may not sum this: " + sidecar);
    }

    /** The sidecar describes the TARGET, so a later Consignment with different measures must not erase earlier ones. */
    @Test
    void mergesTheSidecarAcrossConsignmentsRatherThanOverwritingIt(@TempDir Path root) throws Exception {
        write(root, "c1", List.of(row("cdr", "2026-07-01", Measure.additive("count", 1))));
        write(root, "c2", List.of(row("cdr", "2026-07-02",
                Measure.additive("count", 1), Measure.additive("bytes_sum", 10))));

        String sidecar = Files.readString(root.resolve("cdr").resolve(SummaryWriter.MEASURES_SIDECAR));
        assertTrue(sidecar.contains("count,ADDITIVE"), sidecar);
        assertTrue(sidecar.contains("bytes_sum,ADDITIVE"), sidecar);
        assertEquals(1, sidecar.lines().filter(l -> l.startsWith("count,")).count(),
                "merged, not duplicated: " + sidecar);
    }

    // ── the unpartitioned fallback (operator call, against advice) ────────────────

    /**
     * Rows with no {@code record_day} are written flat rather than refused — a deliberate operator call. §7.3
     * supersedes the flat layout, so this test also documents the cost: no day partition to prune on.
     */
    @Test
    void writesUnpartitionedWhenNoRowCarriesARecordDay(@TempDir Path root) throws Exception {
        List<ConsignmentOutput> written = write(root, "c1",
                List.of(row("totals", null, Measure.additive("count", 5))));

        assertEquals(1, written.size());
        assertEquals("", written.get(0).partitionKey(), "flat: there is no partition to prune on");
        assertNull(written.get(0).recordDay(), "and therefore no record day either");
        assertEquals(Path.of(root.toString(), "totals", "c1_summary_out.parquet").toString(),
                written.get(0).path());
        assertEquals(5.0d, ((Number) read(written.get(0).path()).get(0).get("count")).doubleValue());
    }

    /** A target must not be half-partitioned — neither layout's reader would find all of it. */
    @Test
    void writesTheWholeTargetFlatWhenOnlySomeRowsCarryADay(@TempDir Path root) throws Exception {
        List<ConsignmentOutput> written = write(root, "c1", List.of(
                row("mixed", "2026-07-01", Measure.additive("count", 1)),
                row("mixed", null, Measure.additive("count", 2))));

        assertEquals(1, written.size(), "one flat file, not one partitioned plus one flat");
        assertEquals("", written.get(0).partitionKey());
        assertEquals(2, read(written.get(0).path()).size(), "both rows are still written");
    }

    // ── unioned schemas and refusals ─────────────────────────────────────────────

    /** Two grains for one target union their columns; a missing measure is a NULL, not a refusal. */
    @Test
    void unionsMeasureColumnsAcrossRowsFillingGapsWithNull(@TempDir Path root) throws Exception {
        List<ConsignmentOutput> written = write(root, "c1", List.of(
                row("cdr", "2026-07-01", Measure.additive("count", 1)),
                row("cdr", "2026-07-01", Measure.additive("count", 2), Measure.additive("bytes_sum", 8))));

        List<Map<String, Object>> back = read(written.get(0).path());
        assertEquals(2, back.size());
        assertTrue(back.get(0).containsKey("bytes_sum"), "the union schema carries it");
        assertNull(back.get(0).get("bytes_sum"), "and the row that omitted it is NULL, not refused");
    }

    @Test
    void separatesTargetsIntoTheirOwnDirectories(@TempDir Path root) throws Exception {
        write(root, "c1", List.of(
                row("cdr", "2026-07-01", Measure.additive("count", 1)),
                row("sms", "2026-07-01", Measure.additive("count", 2))));

        assertTrue(Files.isDirectory(root.resolve("cdr")));
        assertTrue(Files.isDirectory(root.resolve("sms")));
    }

    /** A target becomes a DIRECTORY name, so an unsafe one is refused rather than escaped (path-jail). */
    @Test
    void refusesATargetThatWouldEscapeTheSummaryRoot(@TempDir Path root) {
        for (String bad : List.of("../escape", "a/b", "a\\b", "", "1_leading_digit")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> write(root, "c1", List.of(row(bad, "2026-07-01", Measure.additive("count", 1)))),
                    "expected '" + bad + "' to be refused");
            assertTrue(e.getMessage().contains("summary target"), e.getMessage());
        }
    }

    @Test
    void refusesAMeasureNameThatIsNotASafeIdentifier(@TempDir Path root) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> write(root, "c1", List.of(row("cdr", "2026-07-01",
                        new Measure("count\"; DROP TABLE x; --", Measure.Composability.ADDITIVE, 1)))));
        assertTrue(e.getMessage().contains("measure name"), e.getMessage());
    }

    /** One name → one concept: a string cannot be both the grain and a measure. */
    @Test
    void refusesANameUsedAsBothGrainKeyAndMeasure(@TempDir Path root) {
        SummaryRow clash = new SummaryRow("cdr",
                Map.of(SummaryWriter.RECORD_DAY, "2026-07-01", "region", "east"),
                List.of(Measure.additive("count", 1), Measure.additive("region", 2)));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> write(root, "c1", List.of(clash)));
        assertTrue(e.getMessage().contains("one name must mean one thing"), e.getMessage());
    }

    @Test
    void writingNothingProducesNothing(@TempDir Path root) throws Exception {
        assertEquals(List.of(), write(root, "c1", List.of()));
        assertEquals(List.of(), write(root, "c1", null));
        assertFalse(Files.exists(root.resolve("cdr")));
    }

    /**
     * Every registered summary row carries its {@code producer}, so `producerHighWater` can attribute it.
     * These rows used to come through with producer null — an unattributed group, which is indistinguishable
     * from "nobody wrote this" for a per-producer watermark.
     */
    @Test
    void everyRegisteredRowCarriesItsProducer(@TempDir Path root) throws Exception {
        List<ConsignmentOutput> rows = write(root, "c1",
                List.of(row("cdr", "2026-07-01", Measure.additive("count", 3)),
                        row("cdr", "2026-07-02", Measure.additive("count", 4))),
                "daily_rollup");

        assertEquals(2, rows.size());
        for (ConsignmentOutput o : rows) assertEquals("daily_rollup", o.producer(), o.path());
    }

    // ── event-time bounds: stated by the processor, never derived from the grain ─────────────────

    /** {@link #row} plus the declared event-time range of the detail behind it. */
    private static SummaryRow bounded(String target, String day, String min, String max, Measure... measures) {
        Map<String, String> keys = new LinkedHashMap<>();
        if (day != null) keys.put(SummaryWriter.RECORD_DAY, day);
        return new SummaryRow(target, keys, List.of(measures), EventTimeBounds.of(min, max));
    }

    /**
     * ⚠ The replacement for {@code boundsStayNullBecauseASummaryRowHasNoEventTime}. Absence is still the
     * default — what changed is that it is now the answer for a row that <em>declares</em> nothing, rather than
     * the only answer available. A reader treats a null bound as "cannot prune", which is the honest degradation.
     */
    @Test
    void aRowThatDeclaresNoEventTimeStillRecordsNoBounds(@TempDir Path root) throws Exception {
        List<ConsignmentOutput> rows = write(root, "c1",
                List.of(row("cdr", "2026-07-01", Measure.additive("count", 3))), "daily_rollup");

        assertEquals(1, rows.size());
        assertNull(rows.get(0).bounds(), "nothing was declared, so nothing is claimed");
    }

    /** A declared range reaches the registry row for the file it was written into. */
    @Test
    void aDeclaredRangeIsRecordedOnTheFile(@TempDir Path root) throws Exception {
        List<ConsignmentOutput> rows = write(root, "c1",
                List.of(bounded("cdr", "2026-07-01", "2026-07-01T00:12:30", "2026-07-01T23:47:05",
                        Measure.additive("count", 3))), "daily_rollup");

        assertEquals(1, rows.size());
        EventTimeBounds b = rows.get(0).bounds();
        assertNotNull(b, "the processor declared a range");
        assertEquals("2026-07-01T00:12:30", b.min());
        assertEquals("2026-07-01T23:47:05", b.max());
    }

    /**
     * Several rows land in one file (one partition), so the file's bounds are the <b>union</b> of theirs — and
     * {@code spreadMs} is re-derived from the folded endpoints, not summed from the per-row spreads, which
     * would double-count the overlap.
     */
    @Test
    void boundsFoldAcrossEveryRowInTheSameFile(@TempDir Path root) throws Exception {
        List<ConsignmentOutput> rows = write(root, "c1", List.of(
                bounded("cdr", "2026-07-01", "2026-07-01T08:00:00", "2026-07-01T09:00:00",
                        Measure.additive("count", 3)),
                bounded("cdr", "2026-07-01", "2026-07-01T06:30:00", "2026-07-01T07:00:00",
                        Measure.additive("count", 4))), "daily_rollup");

        assertEquals(1, rows.size(), "same record_day — one file");
        EventTimeBounds b = rows.get(0).bounds();
        assertEquals("2026-07-01T06:30:00", b.min(), "earliest across the rows");
        assertEquals("2026-07-01T09:00:00", b.max(), "latest across the rows");
        assertEquals(150 * 60 * 1000L, b.spreadMs(), "06:30 → 09:00, derived from the folded endpoints");
    }

    /** Bounds belong to the file, so two partitions get their own — never one range smeared over both. */
    @Test
    void eachPartitionKeepsItsOwnBounds(@TempDir Path root) throws Exception {
        List<ConsignmentOutput> rows = write(root, "c1", List.of(
                bounded("cdr", "2026-07-01", "2026-07-01T08:00:00", "2026-07-01T09:00:00",
                        Measure.additive("count", 3)),
                bounded("cdr", "2026-07-02", "2026-07-02T10:00:00", "2026-07-02T11:00:00",
                        Measure.additive("count", 4))), "daily_rollup");

        Map<String, EventTimeBounds> byPartition = new LinkedHashMap<>();
        for (ConsignmentOutput o : rows) byPartition.put(o.partitionKey(), o.bounds());
        assertEquals("2026-07-01T08:00:00", byPartition.get("record_day=2026-07-01").min());
        assertEquals("2026-07-02T11:00:00", byPartition.get("record_day=2026-07-02").max());
    }

    /**
     * A file is bounded only when <b>every</b> row in it declared a range. One undeclared row means the file
     * contains events the range does not cover, and a bound that under-covers its file is exactly the false
     * negative the Selector must never be given.
     */
    @Test
    void oneUndeclaredRowLeavesTheWholeFileUnbounded(@TempDir Path root) throws Exception {
        List<ConsignmentOutput> rows = write(root, "c1", List.of(
                bounded("cdr", "2026-07-01", "2026-07-01T08:00:00", "2026-07-01T09:00:00",
                        Measure.additive("count", 3)),
                row("cdr", "2026-07-01", Measure.additive("count", 4))), "daily_rollup");

        assertEquals(1, rows.size());
        assertNull(rows.get(0).bounds(),
                "a partial range would claim the file holds nothing outside 08:00–09:00, which is not known");
    }

    /** The unpartitioned fallback carries bounds too — it is the layout that most needs them to be prunable. */
    @Test
    void theFlatFallbackIsBoundedAsWell(@TempDir Path root) throws Exception {
        List<ConsignmentOutput> rows = write(root, "c1",
                List.of(bounded("cdr", null, "2026-07-01T08:00:00", "2026-07-03T09:00:00",
                        Measure.additive("count", 3))), "daily_rollup");

        assertEquals(1, rows.size());
        assertEquals("", rows.get(0).partitionKey());
        assertEquals("2026-07-03T09:00:00", rows.get(0).bounds().max());
    }
}
