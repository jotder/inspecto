package com.gamma.inspector;

import com.gamma.etl.Consignment;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.SchemaSelector;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * HA-KILL9-DOUBLE-INGEST-1 reproduction: a node killed after a Consignment's OUTPUT is durable but before its
 * source is acknowledged (backup move + marker), then a survivor re-ingesting the still-present file.
 */
class KillBeforeAckDuplicateTest {

    private Consignment.Member member(PipelineConfig cfg, File f, int id) {
        SchemaSelector.Selection sel = new SchemaSelector.Selection(cfg.schemas().single(), null);
        return new Consignment.Member(f, id, f.length(), sel);
    }

    /** Writes the batch output the way ingest does (parquet under dirs.database), then optionally finalises. */
    private void ingest(PipelineConfig cfg, Consignment batch, List<Consignment.Member> members,
                        String baseName, boolean ack) throws Exception {
        DuckDbUtil.loadDriver();
        File db = DuckDbUtil.tempDbFile("kill_ack_");
        try (Connection conn = DuckDbUtil.openConnection(db)) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE transformed AS SELECT * FROM (VALUES "
                        + "('alice', 250.0, '2026', '07', '01', 1), ('bob', 50.0, '2026', '07', '01', 1)) "
                        + "v(name, cost, year, month, day, __src_id)");
            }
            ConsignmentIngestStrategy.Written w = ConsignmentIngestStrategy.writeAndTrace(
                    conn, "transformed", List.of("year", "month", "day"), cfg,
                    cfg.dirs().database(), baseName, batch.batchId(), Map.of(1, "x.csv"), "");
            if (ack) ConsignmentIngestor.finalizeSource(batch, cfg, members, w.outputs(), w.lineage());
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    private long rows(PipelineConfig cfg) throws Exception {
        try (var walk = Files.walk(Path.of(cfg.dirs().database()))) {
            long total = 0;
            for (Path p : walk.filter(Files::isRegularFile).toList())
                total += Files.readAllLines(p).size() - 1;   // CSV output: one header line per file
            return total;
        }
    }

    @Test
    void singleFileBatchReingestedAfterKillOverwritesItsOwnOutput(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTestRef.writePipeline(dir, "").toString());
        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Path f = inbox.resolve("solo.csv");
        Files.writeString(f, "ID,AMT,EVENT_DATE\nx,9.0,2020-04-03\n");
        List<Consignment.Member> ms = List.of(member(cfg, f.toFile(), 0));
        Consignment batch = new Consignment("b_one", "stg", null, ms);

        ingest(cfg, batch, ms, "solo", false);          // node A: output durable, killed before the ack
        ingest(cfg, batch, ms, "solo", true);           // node B: the file is still in the inbox
        assertEquals(2, rows(cfg), "same stem + partition = overwrite, not a second copy");
    }

    @Test
    void knownHoleMultiFileBatchReplannedAfterKillDuplicates(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTestRef.writePipeline(dir, "").toString());
        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Path a = inbox.resolve("a.csv"), b = inbox.resolve("b.csv");
        Files.writeString(a, "ID,AMT,EVENT_DATE\nx,9.0,2020-04-03\n");
        Files.writeString(b, "ID,AMT,EVENT_DATE\ny,9.0,2020-04-03\n");
        List<Consignment.Member> ms = List.of(member(cfg, a.toFile(), 0), member(cfg, b.toFile(), 1));
        // node A planned {a,b}; the survivor plans {a,b} plus a file that arrived meanwhile -> another id
        ingest(cfg, new Consignment("b_ab", "stg", null, ms), ms, "b_ab", false);
        ingest(cfg, new Consignment("b_abc", "stg", null, ms), ms, "b_abc", true);
        // ⚠ PINS A KNOWN HOLE (HA-KILL9-DOUBLE-INGEST-1), not a wanted behaviour: a multi-file batch is named by
        // its batch id, so a different member set is a different file name. Fix it and flip this to 2.
        assertEquals(4, rows(cfg), "the re-planned batch wrote a second, differently named output");
    }
}
