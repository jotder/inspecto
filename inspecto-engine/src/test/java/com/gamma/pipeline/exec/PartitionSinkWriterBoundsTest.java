package com.gamma.pipeline.exec;

import com.gamma.consignment.ConsignmentOutput;
import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.consignment.DbConsignmentOutputStore;
import com.gamma.consignment.EventTimeBounds;
import com.gamma.pipeline.PipelineNode;
import com.gamma.util.JdbcDrivers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Event-time bounds and {@code producer} on the <b>Pipeline-sink</b> write path (consignment addressing §3.1,
 * extended there 2026-08-10). Until this, only the ingest path recorded either, which left every
 * pipeline-written table with no bounds for a Selector and an unattributed producer that suppressed its
 * watermark outright.
 *
 * <p>The declaration is a {@code partitions[]} entry's {@code source} — the raw column the partition was
 * derived from, the same word {@code PartitionDef.source} carries on the ingest schema. The tests that matter
 * most here are the ones asserting bounds stay <b>absent</b>: nothing is inferred, because bounds that are
 * confidently wrong are worse than bounds that are missing.
 */
class PartitionSinkWriterBoundsTest {

    @AfterEach
    void clearRegistry() {
        ConsignmentOutputStores.use(null);
    }

    /** A relation with a raw timestamp column {@code call_ts} plus the {@code day} partition derived from it. */
    private static void seed(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE src AS SELECT * FROM (VALUES "
                    + "('2026-08-10 04:00:00', '2026-08-10', 'north'), "
                    + "('2026-08-10 18:30:00', '2026-08-10', 'north'), "
                    + "('2026-08-11 09:15:00', '2026-08-11', 'north')) AS t(call_ts, day, region)");
        }
    }

    private static PipelineNode sink(Object partitions) {
        Map<String, Object> cfg = partitions == null
                ? Map.of("store", "cdr_out", "format", "parquet")
                : Map.of("store", "cdr_out", "format", "parquet", "partitions", partitions);
        return new PipelineNode("s1", "sink.persistent", "s1", null, cfg, null);
    }

    private static List<ConsignmentOutput> write(Connection conn, Path dir, PipelineNode s) throws Exception {
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            ConsignmentOutputStores.use(db);
            new PartitionSinkWriter(conn, dir.toString(), "run1", "c1", "cdr_pipeline").write(s, "src");
            return db.outputs("c1");
        }
    }

    /** The declared source is aggregated at full resolution — not at the partition's day granularity, which is
     *  what {@code record_day} already gives and would make the bounds worthless for a watermark. */
    @Test
    void aDeclaredPartitionSourceYieldsBoundsAtTheSourcesOwnResolution(@TempDir Path dir) throws Exception {
        try (Connection conn = JdbcDrivers.connect("jdbc:duckdb:")) {
            seed(conn);
            List<ConsignmentOutput> rows = write(conn, dir,
                    sink(List.of(Map.of("column", "day", "source", "call_ts"))));

            assertEquals(2, rows.size(), "one file per partition");
            ConsignmentOutput first = rows.stream().filter(r -> "day=2026-08-10".equals(r.partitionKey()))
                    .findFirst().orElseThrow();
            EventTimeBounds b = first.bounds();
            assertNotNull(b, "a declared source must produce bounds on this path");
            assertEquals("2026-08-10T04:00:00", b.min());
            assertEquals("2026-08-10T18:30:00", b.max(), "the full range inside the day, not the day itself");
            assertEquals(14L * 3600 * 1000 + 30L * 60 * 1000, b.spreadMs(), "04:00 → 18:30 is 14h30m");
        }
    }

    @Test
    void everyRowCarriesTheProducerSoTheStreamHasAWatermarkAtAll(@TempDir Path dir) throws Exception {
        try (Connection conn = JdbcDrivers.connect("jdbc:duckdb:")) {
            seed(conn);
            List<ConsignmentOutput> rows = write(conn, dir,
                    sink(List.of(Map.of("column", "day", "source", "call_ts"))));

            assertTrue(rows.stream().allMatch(r -> "cdr_pipeline".equals(r.producer())),
                    "an unattributed producer suppresses the whole stream's watermark");
        }
    }

    // ── the absent cases: nothing is inferred ────────────────────────────────────

    /** Bare-string partitions declare no source, so there is nothing to aggregate. */
    @Test
    void bareStringPartitionsRecordNoBounds(@TempDir Path dir) throws Exception {
        try (Connection conn = JdbcDrivers.connect("jdbc:duckdb:")) {
            seed(conn);
            assertTrue(write(conn, dir, sink(List.of("day"))).stream().allMatch(r -> r.bounds() == null));
        }
    }

    /**
     * A relation whose only timestamp-shaped column is right there is still not evidence of event time. This is
     * the inference this design refuses to make.
     */
    @Test
    void anUndeclaredTimestampColumnIsNotTreatedAsEventTime(@TempDir Path dir) throws Exception {
        try (Connection conn = JdbcDrivers.connect("jdbc:duckdb:")) {
            seed(conn);
            assertTrue(write(conn, dir, sink(List.of(Map.of("column", "day")))).stream()
                            .allMatch(r -> r.bounds() == null),
                    "a partition entry with no 'source' must not fall back to guessing a column");
            assertTrue(write(conn, dir, sink(null)).stream().allMatch(r -> r.bounds() == null),
                    "and an unpartitioned sink declares nothing at all");
        }
    }

    /** Two entries naming different sources identify no single event time — the rule
     *  {@code PartitionDef.eventTimeDef} already applies on the ingest side. */
    @Test
    void disagreeingSourcesRecordNoBounds(@TempDir Path dir) throws Exception {
        try (Connection conn = JdbcDrivers.connect("jdbc:duckdb:")) {
            seed(conn);
            PipelineNode twoSources = sink(List.of(
                    Map.of("column", "day", "source", "call_ts"),
                    Map.of("column", "region", "source", "other_ts")));

            assertTrue(write(conn, dir, twoSources).stream().allMatch(r -> r.bounds() == null),
                    "ambiguity must read as unknown, not as a coin flip");
        }
    }

    /** The source is embedded in min()/max() SQL, so a non-identifier fails closed rather than being quoted in. */
    @Test
    void anUnsafeSourceNameFailsClosed(@TempDir Path dir) throws Exception {
        try (Connection conn = JdbcDrivers.connect("jdbc:duckdb:")) {
            seed(conn);
            PipelineNode injected = sink(List.of(
                    Map.of("column", "day", "source", "call_ts\" ; DROP TABLE src --")));

            assertTrue(write(conn, dir, injected).stream().allMatch(r -> r.bounds() == null));
            try (Statement st = conn.createStatement()) {
                st.executeQuery("SELECT 1 FROM src LIMIT 1");   // throws if the table were gone
            }
        }
    }

    /** A source that does not parse contributes no bound — TRY_CAST, so the write still succeeds. */
    @Test
    void anUnparseableSourceDegradesRatherThanFailingTheWrite(@TempDir Path dir) throws Exception {
        try (Connection conn = JdbcDrivers.connect("jdbc:duckdb:")) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE src AS SELECT * FROM (VALUES ('not-a-time', 'x')) AS t(call_ts, day)");
            }
            List<ConsignmentOutput> rows = write(conn, dir,
                    sink(List.of(Map.of("column", "day", "source", "call_ts"))));

            assertFalse(rows.isEmpty(), "the bytes still land");
            assertTrue(rows.stream().allMatch(r -> r.bounds() == null), "and record no fabricated range");
        }
    }
}
