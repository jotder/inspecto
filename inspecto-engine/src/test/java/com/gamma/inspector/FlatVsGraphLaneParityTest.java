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
                    graphCfg.dirs().database(), "feed", "b_0001", Map.of(0, "feed.csv", 1, "feed.csv"), "");
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
     * Slice B — the same diff for a MULTI-DESTINATION pipeline. The lift emits one sink node per
     * {@code sinks[]} destination and the executor writes each independently, so the fan-out needs no
     * new machinery; what needs proving is that both lanes put the same rows under both roots.
     */
    @Test
    void bothLanesFanOutTheSameRowsToEveryDestination(@TempDir Path dir) throws Exception {
        PipelineConfig flatCfg  = fanOutConfig(dir.resolve("flat"), dir.resolve("flat_out"));
        PipelineConfig graphCfg = fanOutConfig(dir.resolve("graph"), dir.resolve("graph_out"));
        assertTrue(BatchIngestStrategy.graphLaneCarries(graphCfg),
                "a fan-out is carried too: one sink node per destination, each fed off map");

        BatchIngestStrategy.Written flat;
        BatchIngestStrategy.Written graph;
        File flatDb = BatchIngestStrategy.openTempDb(flatCfg, "parity_fanflat_");
        File graphDb = BatchIngestStrategy.openTempDb(graphCfg, "parity_fangraph_");
        try (Connection a = DuckDbUtil.openConnection(flatDb); Connection b = DuckDbUtil.openConnection(graphDb)) {
            seedTable(a);
            seedTable(b);
            flat = BatchIngestStrategy.flatWriteAndTrace(a, "transformed", List.of(), flatCfg,
                    flatCfg.dirs().database(), "feed", "b_0002", Map.of(0, "feed.csv", 1, "feed.csv"),
                    new com.gamma.query.DecisionRuleApplier.Result(List.of(), List.of()));
            graph = BatchIngestStrategy.writeAndTrace(b, "transformed", List.of(), graphCfg,
                    graphCfg.dirs().database(), "feed", "b_0002", Map.of(0, "feed.csv", 1, "feed.csv"), "");
        } finally {
            DuckDbUtil.deleteTempDb(flatDb);
            DuckDbUtil.deleteTempDb(graphDb);
        }

        assertEquals(2, flat.outputs().size(), "harness precondition: the flat lane wrote both destinations");
        assertEquals(flat.outputs().size(), graph.outputs().size(), "output file count");
        // Every destination got the same rows in both lanes — and the SAME rows as each other.
        for (String dest : List.of("db_a", "db_b")) {
            assertEquals(dataLines(dir.resolve("flat_out").resolve(dest)),
                    dataLines(dir.resolve("graph_out").resolve(dest)),
                    "the two lanes wrote different rows to " + dest);
            assertEquals(3, dataLines(dir.resolve("graph_out").resolve(dest)).size(), dest + " got every row");
        }
        assertEquals(lineageKey(flat), lineageKey(graph), "lineage");
    }

    /**
     * Slice C1 — the hazard the {@code writeScope} exists for. The chunked and segmented ingest paths
     * call the write seam SEVERAL times for ONE batch, reusing the same sink node ids. The batch's
     * branch ledger is shared, so without a per-write scope the second call reads as "already
     * committed" and its rows vanish silently. Both halves are pinned: distinct scopes both write, and
     * a REPEATED scope is still skipped (that idempotent replay is what protects a crash-resumed batch).
     */
    @Test
    void severalWritesInOneBatchEachLandWhenTheyCarryTheirOwnScope(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = config(dir.resolve("cfg"), dir.resolve("db").toString());
        File db = BatchIngestStrategy.openTempDb(cfg, "parity_scope_");
        int firstFiles;
        int afterSecondScope;
        int afterRepeatedScope;
        try (Connection conn = DuckDbUtil.openConnection(db)) {
            seedTable(conn);
            BatchIngestStrategy.Written a = BatchIngestStrategy.writeAndTrace(conn, "transformed", List.of(),
                    cfg, cfg.dirs().database(), "chunk_a", "b_same", Map.of(0, "feed.csv", 1, "feed.csv"), "chunk_a");
            firstFiles = a.outputs().size();

            // A DIFFERENT scope: the same batch, the same sink node, a second write that must land.
            BatchIngestStrategy.Written b = BatchIngestStrategy.writeAndTrace(conn, "transformed", List.of(),
                    cfg, cfg.dirs().database(), "chunk_b", "b_same", Map.of(0, "feed.csv", 1, "feed.csv"), "chunk_b");
            afterSecondScope = b.outputs().size();

            // The SAME scope again: the coordinator's idempotent replay skips it, writing nothing.
            BatchIngestStrategy.Written c = BatchIngestStrategy.writeAndTrace(conn, "transformed", List.of(),
                    cfg, cfg.dirs().database(), "chunk_b", "b_same", Map.of(0, "feed.csv", 1, "feed.csv"), "chunk_b");
            afterRepeatedScope = c.outputs().size();
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }

        assertEquals(1, firstFiles, "the first write landed");
        assertEquals(1, afterSecondScope, "the SECOND write landed too — this is what the scope buys");
        assertEquals(0, afterRepeatedScope, "a repeated scope is a replay and writes nothing");
        // Six rows on disk: three from each of the two writes that were supposed to happen.
        assertEquals(6, dataLines(dir.resolve("db")).size(), "both writes' rows are on disk, once each");
    }

    /**
     * Slice C2 — a versioned reference store writes the same way in both lanes. Its refusal in the graph
     * lane was always route-specific ("one version history is ill-defined across branches"); without a
     * route there are no branches, so the stamp runs before the walk exactly as it does on the flat path.
     * What must match: the system columns, the row count, and the **batch-unique file stem** that makes
     * the write an append instead of an overwrite of the prior version.
     */
    @Test
    void bothLanesStampAndAppendAVersionedReferenceStore(@TempDir Path dir) throws Exception {
        PipelineConfig flatCfg  = referenceConfig(dir.resolve("flat_ref"));
        PipelineConfig graphCfg = referenceConfig(dir.resolve("graph_ref"));
        assertTrue(BatchIngestStrategy.graphLaneCarries(graphCfg),
                "a non-route versioned reference store is carried — the refusal was about BRANCHES");

        BatchIngestStrategy.Written flat;
        BatchIngestStrategy.Written graph;
        File flatDb = BatchIngestStrategy.openTempDb(flatCfg, "parity_refflat_");
        File graphDb = BatchIngestStrategy.openTempDb(graphCfg, "parity_refgraph_");
        try (Connection a = DuckDbUtil.openConnection(flatDb); Connection b = DuckDbUtil.openConnection(graphDb)) {
            seedTable(a);
            seedTable(b);
            flat = BatchIngestStrategy.flatWriteAndTrace(a, "transformed", List.of(), flatCfg,
                    flatCfg.dirs().database(), "feed", "b_ref1", Map.of(0, "feed.csv", 1, "feed.csv"),
                    new com.gamma.query.DecisionRuleApplier.Result(List.of(), List.of()));
            graph = BatchIngestStrategy.writeAndTrace(b, "transformed", List.of(), graphCfg,
                    graphCfg.dirs().database(), "feed", "b_ref1", Map.of(0, "feed.csv", 1, "feed.csv"), "");
        } finally {
            DuckDbUtil.deleteTempDb(flatDb);
            DuckDbUtil.deleteTempDb(graphDb);
        }

        assertEquals(flat.outputs().size(), graph.outputs().size(), "output file count");
        // The batch-unique stem is what makes the next batch APPEND rather than overwrite.
        assertEquals(flat.outputs().stream().map(o -> Path.of(o.outputFile()).getFileName().toString()).sorted().toList(),
                graph.outputs().stream().map(o -> Path.of(o.outputFile()).getFileName().toString()).sorted().toList(),
                "the versioned file stem must carry the batch id in BOTH lanes");
        assertTrue(graph.outputs().get(0).outputFile().contains("__v_b_ref1"),
                "the graph lane stamped the batch-unique stem: " + graph.outputs().get(0).outputFile());
        assertEquals(dataLines(dir.resolve("flat_ref").resolve("db")).size(),
                dataLines(dir.resolve("graph_ref").resolve("db")).size(), "row count");
        // The reference system columns landed (the stamp ran) — __op is one of them.
        assertTrue(dataLines(dir.resolve("graph_ref").resolve("db")).stream().anyMatch(l -> l.contains("upsert")),
                "the graph lane stamped the reference system columns");
    }

    /** A {@code produces: reference}, {@code load: upsert} pipeline — the versioned-store shape. */
    private static PipelineConfig referenceConfig(Path dir) throws Exception {
        Files.createDirectories(dir);
        return PipelineConfig.fromMap(Map.of(
                "name", "REF_PARITY",
                "produces", "reference",
                "reference", Map.of("load", "upsert", "key", List.of("ID")),
                // ⚠ dirs.temp is what gives the branch-commit ledger a durable, per-pipeline home; without
                // it graphLaneCarries refuses (the ledger would land in the shared JVM temp dir).
                "dirs", Map.of("poll", dir.resolve("in").toString(),
                        "database", dir.resolve("db").toString(),
                        "temp", dir.resolve("temp").toString()),
                "output", Map.of("format", "CSV"),
                "processing", Map.of("threads", 1)));
    }

    /** A two-destination, non-route pipeline rooted at {@code out}. */
    private static PipelineConfig fanOutConfig(Path dir, Path out) throws Exception {
        Files.createDirectories(dir);
        String d = dir.toString().replace("\\", "/");
        String o = out.toString().replace("\\", "/");
        Path schema = dir.resolve("mini_schema.toon");
        Files.writeString(schema, com.gamma.etl.PipelineConfigBatchTest.miniSchema());
        Path toon = dir.resolve("fanout_pipeline.toon");
        Files.writeString(toon, """
            name: FANOUT_PARITY
            active: true
            dirs:
              poll: %1$s/inbox
              database: %2$s/db
              backup: %1$s/backup
              temp: %1$s/temp
              quarantine: %1$s/quarantine
              status_dir: %1$s/status
            output:
              format: CSV
            sinks[2]{database,format}:
              "%2$s/db_a",CSV
              "%2$s/db_b",CSV
            processing:
              threads: 1
              schema_file: "%3$s"
              csv_settings:
                delimiter: ","
                skip_header_lines: 0
                date_formats[1]: "%%Y-%%m-%%d"
                timestamp_formats[1]: "%%Y-%%m-%%d"
            """.formatted(d, o, schema.toString().replace("\\", "/")));
        return PipelineConfig.load(toon.toString());
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
