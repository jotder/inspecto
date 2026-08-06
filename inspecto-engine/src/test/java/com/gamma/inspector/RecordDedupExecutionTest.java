package com.gamma.inspector;

import com.gamma.etl.PipelineConfig;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Record-grain dedup execution (ELT amendment §2.4 — the dedup STEP): {@code processing.dedup} drives a
 * {@code ROW_NUMBER() = 1} QUALIFY between transform materialisation and the partitioned write, so one
 * winner per business key lands and the section is never dead config (the W1 lesson: a lowered key the
 * engine ignores is a trap, not a feature).
 */
class RecordDedupExecutionTest {

    @Test
    void oneWinnerPerKeyLandsAndTheWinnerFollowsOrderBy(@TempDir Path dir) throws Exception {
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, """
                  dedup:
                    keys[1]: ID
                    order_by: AMT DESC
                """);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        assertNotNull(cfg.dedup(), "harness precondition: the dedup section parsed");

        DuckDbUtil.loadDriver();
        File db = DuckDbUtil.tempDbFile("dedup_exec_");
        try (Connection conn = DuckDbUtil.openConnection(db)) {
            try (Statement st = conn.createStatement()) {
                st.execute("""
                        CREATE TABLE transformed AS SELECT * FROM (VALUES
                          ('a', 10.0, '2026', '07', '01', 0),
                          ('a', 99.0, '2026', '07', '01', 0),
                          ('b',  5.0, '2026', '07', '01', 0)
                        ) v(ID, AMT, year, month, day, __src_id)""");
            }
            BatchIngestStrategy.Written written = BatchIngestStrategy.writeAndTrace(
                    conn, "transformed", List.of("year", "month", "day"), cfg,
                    cfg.dirs().database(), "b1", "batch-1", Map.of(0, "solo.csv"));

            long rows = written.lineage().stream().mapToLong(com.gamma.etl.LineageRow::rowCount).sum();
            assertEquals(2, rows, "3 rows in, one duplicate key folded, 2 rows written");

            assertFalse(written.outputs().isEmpty(), "the partitioned write ran over the deduped table");
            // the winner is order_by's pick — the materialised __dedup table is what was written
            try (Statement st = conn.createStatement();
                 var rs = st.executeQuery("SELECT AMT FROM __dedup WHERE ID = 'a'")) {
                assertTrue(rs.next());
                assertEquals(99.0, rs.getDouble(1), 0.001, "order_by AMT DESC keeps the highest-AMT row");
                assertFalse(rs.next(), "exactly one winner per key");
            }
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /**
     * Phase 4 §2.4/§11.3 — the legacy lane's edge-grain counter: {@code applyRecordDedup} has no
     * per-node provenance graph to record against, so the dropped-duplicate count is a durable,
     * queryable {@link com.gamma.event.EventType#DEDUP_RECORDS_DROPPED} event instead of a log line only.
     */
    @Test
    void dedupDropCountIsRecordedAsADurableEvent(@TempDir Path dir) throws Exception {
        com.gamma.event.InMemoryEventStore events = new com.gamma.event.InMemoryEventStore(1000);
        com.gamma.event.EventLog.global().installStore(events);

        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, """
                  dedup:
                    keys[1]: ID
                    order_by: AMT DESC
                """);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());

        DuckDbUtil.loadDriver();
        File db = DuckDbUtil.tempDbFile("dedup_evt_");
        try (Connection conn = DuckDbUtil.openConnection(db)) {
            try (Statement st = conn.createStatement()) {
                st.execute("""
                        CREATE TABLE transformed AS SELECT * FROM (VALUES
                          ('a', 10.0, '2026', '07', '01', 0),
                          ('a', 99.0, '2026', '07', '01', 0),
                          ('b',  5.0, '2026', '07', '01', 0)
                        ) v(ID, AMT, year, month, day, __src_id)""");
            }
            BatchIngestStrategy.writeAndTrace(conn, "transformed", List.of("year", "month", "day"), cfg,
                    cfg.dirs().database(), "b1", "batch-evt-1", Map.of(0, "solo.csv"));

            // installStore() drains the global log's pre-existing buffered events into the new store
            // (so nothing emitted before the swap is lost) — filter by this test's own batch id, not
            // just type, since another test in this class emits the same event type for its own batch.
            List<com.gamma.event.Event> dropped = events.recent(1000).stream()
                    .filter(e -> com.gamma.event.EventType.DEDUP_RECORDS_DROPPED.equals(e.type())
                            && "batch-evt-1".equals(e.correlationId()))
                    .toList();
            assertEquals(1, dropped.size(), "exactly one dedup-drop event for this batch");
            com.gamma.event.Event e = dropped.get(0);
            assertEquals("batch-evt-1", e.correlationId());
            assertEquals(cfg.identity().pipelineName(), e.pipeline());
            assertEquals("1", String.valueOf(e.attributes().get("dropped")), "one duplicate folded");
            assertEquals("ID", String.valueOf(e.attributes().get("keys")));
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }
}
