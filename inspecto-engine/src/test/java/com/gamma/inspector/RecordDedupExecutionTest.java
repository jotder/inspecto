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
 * Record-grain dedup is <b>not</b> executed by Stage-1 — the assertion this class used to make, inverted
 * 2026-08-11 on an operator decision.
 *
 * <p><b>What changed and why.</b> {@code processing.dedup} used to drive a {@code ROW_NUMBER() = 1}
 * QUALIFY between transform materialisation and the partitioned write. It worked, but it was the one
 * genuine cross-record operation inside the M..N multiplexer, and dedup is a <em>transform</em> concern:
 * in ELT terms it belongs in the T, not the EL. Stage-1 is now per-record work plus routing, which is
 * what keeps every batch embarrassingly parallel and crash-isolated.
 *
 * <p>⚠ <b>Removing an executor is only half a change.</b> Deleting the QUALIFY and leaving the config key
 * parsing would give a pipeline that arms, runs, writes — and silently keeps every duplicate it was
 * configured to fold. That is worse than never having shipped the feature, and it is precisely the
 * silent-discard shape the multiplicity work exists to remove. So the executor's removal is paired with
 * a {@code prepare()} refusal, and <b>both halves are asserted here</b>: the rows are not folded, and an
 * {@code active} pipeline carrying the key cannot arm at all.
 *
 * <p>The replacement already exists and is better: {@code RowShaper.dedup} computes the same window but
 * emits the losers as a first-class {@code duplicate} relation rather than counting and discarding them.
 * Routing a pipeline to it is the multiplicity plan's slice A5.
 */
class RecordDedupExecutionTest {

    /**
     * The behavioural half: three rows in, two sharing a key, and <b>all three land</b>. Asserted through
     * the lineage row count — the same signal the old test used to prove the opposite — so the inversion
     * is visible as a changed number rather than a deleted test.
     */
    @Test
    void stage1DoesNotFoldDuplicatesAnyMore(@TempDir Path dir) throws Exception {
        // active: false — the arming refusal is the other half of this change, asserted below
        Path toon = PipelineConfigBatchTestRef.writeInactivePipeline(dir, """
                  dedup:
                    keys[1]: ID
                    order_by: AMT DESC
                """);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        assertNotNull(cfg.dedup(),
                "the section still PARSES and round-trips — it is authoring-only, not rejected outright");

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
                    cfg.dirs().database(), "b1", "batch-1", Map.of(0, "solo.csv"), "");

            long rows = written.lineage().stream().mapToLong(com.gamma.etl.LineageRow::rowCount).sum();
            assertEquals(3, rows, "all 3 rows land — Stage-1 no longer folds the duplicate key");

            // and the intermediate table the old executor materialised is gone entirely
            try (Statement st = conn.createStatement()) {
                assertThrows(java.sql.SQLException.class,
                        () -> st.executeQuery("SELECT * FROM __dedup"),
                        "no __dedup table is materialised on the ingest path any more");
            }
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /**
     * The fail-closed half, and the one that makes the removal safe: the same config with
     * {@code active: true} refuses to load, so nobody gets a running pipeline quietly ignoring its
     * dedup. Mirrors the refusals {@code route}/{@code summarize}/{@code join} have always had — all
     * three cross-record kinds are finally uniform.
     */
    @Test
    void anActivePipelineCarryingDedupRefusesToArm(@TempDir Path dir) throws Exception {
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, """
                  dedup:
                    keys[1]: ID
                """);   // the shared fixture is active: true
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PipelineConfig.load(toon.toString()),
                "arming a pipeline whose dedup nothing executes would silently keep every duplicate");
        assertTrue(e.getMessage().contains("processing.dedup"), e.getMessage());
        assertTrue(e.getMessage().contains("Stage-2"), e.getMessage());
    }
}
