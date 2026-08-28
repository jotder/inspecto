package com.gamma.inspector;

import com.gamma.etl.PipelineConfig;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>ELT Phase 6 precondition — the two-lane output-parity gate.</b> Deleting the legacy write lane
 * needs the graph lane to carry NON-route pipelines with proven parity, and "proven" has to mean a
 * side-by-side diff: {@code RouteIngestEndToEndTest} is single-lane, and its finalisation parity is
 * prose. This runs the SAME materialised table through both lanes and compares what each produced.
 *
 * <p>The comparison is deliberately at the {@code writeAndTrace} seam rather than end to end: that is
 * exactly where the fork lives, everything downstream of the returned {@code Written} is literally the
 * same code for both lanes, and seeding the two lanes from one table removes every source of difference
 * except the write itself — which is the thing under test.
 */
class FlatVsGraphLaneParityTest {

    /** A minimal single-destination, non-route pipeline — the shape the narrow slice admits. */
    private static PipelineConfig config(Path dir, String dbDir) throws Exception {
        Files.createDirectories(dir);
        String d = dir.toString().replace("\\", "/");
        Path schema = dir.resolve("mini_schema.toon");
        if (!Files.exists(schema))
            Files.writeString(schema, com.gamma.etl.PipelineConfigBatchTest.miniSchema());
        Path toon = dir.resolve("flat_pipeline.toon");
        Files.writeString(toon, """
            name: FLAT_PARITY
            active: true
            dirs:
              poll: %1$s/inbox
              database: %2$s
              backup: %1$s/backup
              temp: %1$s/temp
              quarantine: %1$s/quarantine
              status_dir: %1$s/status
            output:
              format: CSV
            processing:
              threads: 1
              schema_file: "%3$s"
              csv_settings:
                delimiter: ","
                skip_header_lines: 0
                date_formats[1]: "%%Y-%%m-%%d"
                timestamp_formats[1]: "%%Y-%%m-%%d"
            """.formatted(d, dbDir.replace("\\", "/"), schema.toString().replace("\\", "/")));
        return PipelineConfig.load(toon.toString());
    }

    /** Materialise the same three rows both lanes will write. */
    private static void seedTable(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE transformed AS SELECT * FROM (VALUES "
                    + "('E1', 1.0, DATE '2020-04-03', 0), "
                    + "('A2', 2.0, DATE '2020-04-03', 0), "
                    + "('X3', 3.0, DATE '2020-04-04', 1)) AS t(ID, AMT, EVENT_DATE, __src_id)");
        }
    }

    @Test
    void bothLanesWriteTheSameRowsFilesAndLineageForANonRoutePipeline(@TempDir Path dir) throws Exception {
        PipelineConfig flatCfg  = config(dir.resolve("flat"), dir.resolve("db_flat").toString());
        PipelineConfig graphCfg = config(dir.resolve("graph"), dir.resolve("db_graph").toString());

        // The admission itself: this shape is exactly what the narrow slice carries.
        assertTrue(BatchIngestStrategy.graphLaneCarries(graphCfg),
                "a single-destination non-route pipeline is what the graph lane now carries");

        BatchIngestStrategy.Written flat;
        BatchIngestStrategy.Written graph;
        File flatDb = BatchIngestStrategy.openTempDb(flatCfg, "parity_flat_");
        File graphDb = BatchIngestStrategy.openTempDb(graphCfg, "parity_graph_");
        try (Connection a = DuckDbUtil.openConnection(flatDb); Connection b = DuckDbUtil.openConnection(graphDb)) {
            seedTable(a);
            seedTable(b);
            // The flat lane, called directly — writeAndTrace would now divert this very config.
            flat = BatchIngestStrategy.flatWriteAndTrace(a, "transformed", List.of(), flatCfg,
                    flatCfg.dirs().database(), "feed", "b_0001", Map.of(0, "feed.csv", 1, "feed.csv"),
                    new com.gamma.query.DecisionRuleApplier.Result(List.of(), List.of()));
            // The graph lane, through the real fork (wholeBatchWrite = the batch's one write).
            graph = BatchIngestStrategy.writeAndTrace(b, "transformed", List.of(), graphCfg,
                    graphCfg.dirs().database(), "feed", "b_0001", Map.of(0, "feed.csv", 1, "feed.csv"), true);
        } finally {
            DuckDbUtil.deleteTempDb(flatDb);
            DuckDbUtil.deleteTempDb(graphDb);
        }

        // 1. the same number of output files, with the same partitions and the same relative names
        assertEquals(flat.outputs().size(), graph.outputs().size(), "output file count");
        assertEquals(flat.outputs().stream().map(o -> o.partition()).sorted().toList(),
                graph.outputs().stream().map(o -> o.partition()).sorted().toList(), "partitions");
        assertEquals(flat.outputs().stream().map(o -> Path.of(o.outputFile()).getFileName().toString()).sorted().toList(),
                graph.outputs().stream().map(o -> Path.of(o.outputFile()).getFileName().toString()).sorted().toList(),
                "output file names");

        // 2. the same ROWS on disk — the assertion that actually matters
        assertEquals(dataLines(dir.resolve("db_flat")), dataLines(dir.resolve("db_graph")),
                "the two lanes wrote different rows");
        assertEquals(3, dataLines(dir.resolve("db_graph")).size(), "every input row landed");

        // 3. the same lineage matrix (Run Detail's input→output counts) — per srcId and row count,
        //    keyed on the output's FILE NAME since the two lanes write under different roots.
        assertEquals(lineageKey(flat), lineageKey(graph), "lineage");

        // 4. event-time bounds are recorded by both (§3.1) for the same set of files
        assertEquals(flat.bounds().size(), graph.bounds().size(), "event-time bounds count");
    }

    /**
     * A pipeline with a SECOND destination stays flat: {@code sinks:>1} is a fan-out the graph lane
     * does not implement, and {@code dataFedSinkCount} counts N plain-data sinks as ONE branch — so
     * engagement could never separate them. Refused by the admission, not discovered at write time.
     */
    @Test
    void aFanOutPipelineIsNotCarriedByTheGraphLane(@TempDir Path dir) throws Exception {
        String d = dir.toString().replace("\\", "/");
        Path schema = dir.resolve("mini_schema.toon");
        Files.writeString(schema, com.gamma.etl.PipelineConfigBatchTest.miniSchema());
        Path toon = dir.resolve("fanout_pipeline.toon");
        Files.writeString(toon, """
            name: FANOUT_PARITY
            active: true
            dirs:
              poll: %1$s/inbox
              database: %1$s/db
              backup: %1$s/backup
              temp: %1$s/temp
              quarantine: %1$s/quarantine
              status_dir: %1$s/status
            output:
              format: CSV
            sinks[2]{database,format}:
              "%1$s/db_a",CSV
              "%1$s/db_b",CSV
            processing:
              threads: 1
              schema_file: "%2$s"
              csv_settings:
                delimiter: ","
                skip_header_lines: 0
                date_formats[1]: "%%Y-%%m-%%d"
                timestamp_formats[1]: "%%Y-%%m-%%d"
            """.formatted(d, schema.toString().replace("\\", "/")));
        assertFalse(BatchIngestStrategy.graphLaneCarries(PipelineConfig.load(toon.toString())),
                "sinks:>1 is a fan-out the graph lane does not implement");
    }

    /** All non-header data lines across every output file under {@code root}, sorted. */
    private static List<String> dataLines(Path root) throws Exception {
        try (Stream<Path> w = Files.walk(root)) {
            return w.filter(Files::isRegularFile)
                    .flatMap(p -> {
                        try { return Files.readAllLines(p).stream().skip(1); }
                        catch (Exception e) { throw new RuntimeException(e); }
                    })
                    .sorted().toList();
        }
    }

    /** Lineage reduced to what must match across roots: output file NAME → (srcId, rowCount). */
    private static List<String> lineageKey(BatchIngestStrategy.Written w) {
        return w.lineage().stream()
                .map(r -> Path.of(r.outputFile()).getFileName() + "|" + r.srcId() + "|" + r.rowCount())
                .sorted().toList();
    }
}
