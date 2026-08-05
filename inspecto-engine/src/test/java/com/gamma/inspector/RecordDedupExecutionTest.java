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
}
