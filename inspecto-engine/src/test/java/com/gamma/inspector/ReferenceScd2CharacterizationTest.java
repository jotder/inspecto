package com.gamma.inspector;

import com.gamma.enrich.EnrichmentConfig;
import com.gamma.enrich.ReferenceReader;
import com.gamma.etl.PipelineConfig;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterization (LA-DAILY-INGEST-1 D-ING4 recheck, 2026-10-06): pins what {@code reference: {load: scd2}}
 * does TODAY so the docs and the plan's M08 stay honest. Facts pinned: (1) the write path appends versions
 * and never closes the old one - there is no {@code valid_to} column; (2) {@code __valid_from} is the ingest
 * wall-clock ({@code now()}), not a feed-supplied effective date; (3) the as-of read is ONE literal instant
 * per binding applied as {@code __valid_from <= instant}, i.e. a snapshot of the whole dimension, not a
 * per-fact-row interval join.
 */
class ReferenceScd2CharacterizationTest {

    private static PipelineConfig.Reference scd2() {
        return new PipelineConfig.Reference(List.of("customer_id"), PipelineConfig.Load.SCD2, 0, null, null);
    }

    private static List<String> columns(Statement st, String table) throws Exception {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery("SELECT * FROM " + table + " LIMIT 0")) {
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) out.add(rs.getMetaData().getColumnName(i));
        }
        return out;
    }

    @Test
    void changedRowAppendsANewVersionWithValidFromAndNoValidToColumn() throws Exception {
        File db = DuckDbUtil.tempDbFile("scd2char_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE b1 AS SELECT * FROM (VALUES ('C1','NA')) t(customer_id, region)");
            ConsignmentIngestStrategy.stampReferenceVersions(c, "b1", "store", scd2(), "b1", null);
            st.execute("CREATE TABLE b2 AS SELECT * FROM (VALUES ('C1','APAC')) t(customer_id, region)");
            ConsignmentIngestStrategy.stampReferenceVersions(c, "b2", "appended", scd2(), "b2",
                    "(SELECT * FROM store) AS _store");

            // The changed row is emitted as a NEW version row; nothing rewrites or closes the old one.
            try (ResultSet rs = st.executeQuery("SELECT region, __op, __batch_id FROM appended")) {
                assertTrue(rs.next());
                assertEquals("APAC", rs.getString(1));
                assertEquals("upsert", rs.getString(2));
                assertEquals("b2", rs.getString(3));
                assertFalse(rs.next());
            }
            List<String> cols = columns(st, "appended");
            assertTrue(cols.contains("__valid_from"));
            assertTrue(cols.stream().noneMatch(n -> n.toLowerCase().contains("valid_to")
                            || n.toLowerCase().contains("valid_until") || n.toLowerCase().contains("is_current")),
                    "no closing column exists - an interval's end is implicit (next version's __valid_from): " + cols);
            // __valid_from is the ingest instant (now()), not anything the feed carried.
            try (ResultSet rs = st.executeQuery(
                    "SELECT abs(epoch(now()::TIMESTAMP) - epoch(__valid_from)) < 60 FROM appended")) {
                assertTrue(rs.next());
                assertTrue(rs.getBoolean(1), "__valid_from is processing time");
            }
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    @Test
    void asOfIsOneLiteralInstantCutPerBindingNotAPerRowJoin(@TempDir Path dir) throws Exception {
        PipelineConfig producer = PipelineConfig.fromMap(Map.of(
                "name", "CUSTOMER_DIM",
                "produces", "reference",
                "reference", Map.of("load", "scd2", "key", List.of("customer_id")),
                "dirs", Map.of("poll", dir.resolve("in").toString(), "database", dir.resolve("db").toString()),
                "output", Map.of("format", "PARQUET"),
                "processing", Map.of("threads", 1)));
        String asOf = ReferenceReader.sqlFor(
                new EnrichmentConfig.Reference("d", null, null, producer.identity().pipelineName(), "2026-07-24 10:30:00"),
                List.of(producer));
        assertTrue(asOf.contains("__valid_from <= TIMESTAMP '2026-07-24 10:30:00'"), asOf);
        assertTrue(asOf.contains("QUALIFY row_number() OVER (PARTITION BY __key_hash ORDER BY __valid_from DESC) = 1"), asOf);
        assertFalse(asOf.toLowerCase().contains("valid_to"), asOf);
        assertFalse(asOf.toLowerCase().contains("asof join"), "no ASOF JOIN / per-row interval join: " + asOf);
        // Without as_of it is the current view: same shape minus the instant cut.
        String current = ReferenceReader.sqlFor(
                new EnrichmentConfig.Reference("d", null, null, producer.identity().pipelineName()), List.of(producer));
        assertFalse(current.contains("TIMESTAMP '"), current);
    }
}
