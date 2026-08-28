package com.gamma.inspector;

import com.gamma.etl.PipelineConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Arming plan S2, end to end: an authored {@code route:} pipeline executes on the REAL ingest path
 * ({@code CollectorProcessor.run}) through the branch-aware runner, and the whole flat-path output
 * surface still happens — because everything after the write IS the flat path's code.
 *
 * <p>What this pins, in one run over one inbox file (rows E1 / A2 / X3):
 * <ul>
 *   <li><b>Branch routing:</b> the {@code emea} branch's destination receives exactly the E row; the
 *       {@code apac} destination receives the A row AND the X row (via {@code default: apac}) — row
 *       conservation: in == Σ branches, nothing silently dropped.</li>
 *   <li><b>Lineage:</b> the input→output matrix names each destination's file (Run Detail's food).</li>
 *   <li><b>Finalisation parity smoke:</b> backup + status/batches ledgers exist exactly as a flat run
 *       leaves them — proof commit/writeAudit ran unchanged after the graph write.</li>
 * </ul>
 *
 * <p>The pipeline is {@code active: true} — S3 lifted the blanket refusal, so this is the ARMED
 * path exactly as production runs it; {@code prepare()}'s remaining route validations (default:
 * required, branch↔sink database pairing, case-mode only, single-schema) all hold on this fixture.
 */
class RouteIngestEndToEndTest {

    @Test
    void aRoutePipelineWritesEachBranchToItsPairedDestination(@TempDir Path dir) throws Exception {
        String d = dir.toString().replace("\\", "/");
        Path schema = dir.resolve("mini_schema.toon");
        Files.writeString(schema, com.gamma.etl.PipelineConfigBatchTest.miniSchema());
        Path toon = dir.resolve("route_pipeline.toon");
        Files.writeString(toon, """
            name: ROUTE_E2E
            active: true
            dirs:
              poll: %1$s/inbox
              database: %1$s/db
              backup: %1$s/backup
              temp: %1$s/temp
              quarantine: %1$s/quarantine
              markers: %1$s/markers
              status_dir: %1$s/status
            output:
              format: CSV
            sinks[2]{database,format}:
              "%1$s/db_emea",CSV
              "%1$s/db_apac",CSV
            route:
              mode: case
              default: apac
              branches[2]{key,where,database}:
                emea,"ID LIKE 'E%%'","%1$s/db_emea"
                apac,"ID LIKE 'A%%'","%1$s/db_apac"
            processing:
              threads: 1
              schema_file: "%2$s"
              csv_settings:
                delimiter: ","
                skip_header_lines: 0
                date_formats[1]: "%%Y-%%m-%%d"
                timestamp_formats[1]: "%%Y-%%m-%%d"
            """.formatted(d, schema.toString().replace("\\", "/")));

        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Path inbox = Files.createDirectories(Path.of(cfg.dirs().poll()));
        Files.writeString(inbox.resolve("feed.csv"),
                "ID,AMT,EVENT_DATE\nE1,1.0,2020-04-03\nA2,2.0,2020-04-03\nX3,3.0,2020-04-03\n");

        CollectorProcessor.run(cfg);

        // branch routing + row conservation
        List<String> emea = dataLines(dir.resolve("db_emea"));
        List<String> apac = dataLines(dir.resolve("db_apac"));
        assertEquals(1, emea.size(), "emea branch got exactly the E row: " + emea);
        assertTrue(emea.get(0).startsWith("E1,"), emea.toString());
        assertEquals(2, apac.size(), "apac got its own row AND the unmatched row via default: " + apac);
        assertTrue(apac.stream().anyMatch(l -> l.startsWith("A2,")), apac.toString());
        assertTrue(apac.stream().anyMatch(l -> l.startsWith("X3,")),
                "the X row must land via default:, never vanish: " + apac);

        // finalisation parity smoke: the flat commit tail ran — backup took the input, ledgers exist
        assertFalse(Files.exists(inbox.resolve("feed.csv")), "the original left the inbox");
        try (Stream<Path> w = Files.walk(dir.resolve("backup"))) {
            assertTrue(w.anyMatch(p -> p.getFileName().toString().equals("feed.csv")),
                    "commit's backup step ran (same code as the flat path)");
        }
        Path statusCsv;
        try (Stream<Path> w = Files.walk(Path.of(cfg.dirs().statusFilePath()).getParent())) {
            statusCsv = w.filter(p -> p.getFileName().toString().contains("_status_"))
                    .findFirst().orElseThrow();
        }
        String status = Files.readString(statusCsv);
        assertTrue(status.contains("feed.csv") && status.contains("SUCCESS"),
                "writeAudit ran unchanged: " + status);

        // lineage: the input→output matrix names BOTH destinations' files
        Path lineageCsv;
        try (Stream<Path> w = Files.walk(Path.of(cfg.dirs().statusFilePath()).getParent())) {
            lineageCsv = w.filter(p -> p.getFileName().toString().contains("_lineage_"))
                    .findFirst().orElseThrow();
        }
        String lineage = Files.readString(lineageCsv);
        assertTrue(lineage.contains("db_emea") && lineage.contains("db_apac"),
                "per-branch lineage reached the ledger: " + lineage);

        // S4-pre housekeeping: a fully committed batch's branch commit log is deleted by commit()'s
        // tail (a committed batch never replays). Retention on failure is structural — a failed batch
        // never reaches commit(), so its log survives as the partial-commit record.
        try (Stream<Path> w = Files.walk(dir.resolve("temp"))) {
            assertTrue(w.noneMatch(p -> p.getFileName().toString().startsWith("branch_commit_")),
                    "no branch_commit_*.log left in temp after a successful commit");
        }
    }

    /**
     * Phase 4 S4b end to end — the D-13 acceptance row: "a fixture Consignment parks at a disabled
     * Step, is inspectable". Disabling the apac branch's sink ({@code sink__d1}) arms (S4b relaxed
     * exactly this shape) and at rest the batch PARKS instead of committing:
     * the live branch's rows land, the disabled branch's rows survive as a durable park Parquet, the
     * manifest records {@code parkedAt}, the original moves to the park home (so the next poll does
     * not re-ingest), nothing is marked, and the branch commit log is KEPT (the drain's resume record).
     */
    @Test
    void aDisabledBranchSinkParksTheConsignment(@TempDir Path dir) throws Exception {
        String d = dir.toString().replace("\\", "/");
        Path schema = dir.resolve("mini_schema.toon");
        Files.writeString(schema, com.gamma.etl.PipelineConfigBatchTest.miniSchema());
        Path toon = dir.resolve("park_pipeline.toon");
        Files.writeString(toon, """
            name: PARK_E2E
            active: true
            dirs:
              poll: %1$s/inbox
              database: %1$s/db
              backup: %1$s/backup
              temp: %1$s/temp
              quarantine: %1$s/quarantine
              markers: %1$s/markers
              status_dir: %1$s/status
            output:
              format: CSV
            sinks[2]{database,format}:
              "%1$s/db_emea",CSV
              "%1$s/db_apac",CSV
            route:
              mode: case
              default: apac
              branches[2]{key,where,database}:
                emea,"ID LIKE 'E%%'","%1$s/db_emea"
                apac,"ID LIKE 'A%%'","%1$s/db_apac"
            processing:
              threads: 1
              schema_file: "%2$s"
              disabled_steps[1]: sink__d1
              csv_settings:
                delimiter: ","
                skip_header_lines: 0
                date_formats[1]: "%%Y-%%m-%%d"
                timestamp_formats[1]: "%%Y-%%m-%%d"
            """.formatted(d, schema.toString().replace("\\", "/")));

        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Path inbox = Files.createDirectories(Path.of(cfg.dirs().poll()));
        Files.writeString(inbox.resolve("feed.csv"),
                "ID,AMT,EVENT_DATE\nE1,1.0,2020-04-03\nA2,2.0,2020-04-03\nX3,3.0,2020-04-03\n");

        CollectorProcessor.run(cfg);

        // the live branch committed its row; the disabled branch wrote NOTHING to its destination
        assertEquals(1, dataLines(dir.resolve("db_emea")).size(), "emea (live) committed");
        assertFalse(Files.exists(dir.resolve("db_apac")) && dataLines(dir.resolve("db_apac")).size() > 0,
                "the disabled branch's destination received no rows");

        // the parked rows survive durably as the park table (A2 + X3-via-default = 2 rows)
        Path parkHome = dir.resolve("backup").resolve("parked");
        Path parkTable;
        try (Stream<Path> w = Files.walk(parkHome)) {
            parkTable = w.filter(p -> p.getFileName().toString().endsWith("__sink__d1.parquet"))
                    .findFirst().orElseThrow(() -> new AssertionError("park table missing"));
        }
        assertTrue(Files.size(parkTable) > 0, "the park table holds the branch's rows");

        // the original moved to the park home — the next poll cannot re-ingest it
        assertFalse(Files.exists(inbox.resolve("feed.csv")), "original left the inbox");
        assertTrue(Files.exists(parkHome.resolve("feed.csv")), "original lives in the park home");
        try (Stream<Path> w = Files.walk(dir.resolve("backup"))) {
            assertTrue(w.filter(p -> p.getFileName().toString().equals("feed.csv"))
                            .allMatch(p -> p.startsWith(parkHome)),
                    "NOT backed up as committed — parked is not committed");
        }

        // the manifest is the inspectable record: parkedAt + PARKED members + the park table path
        Path manifest;
        try (Stream<Path> w = Files.walk(dir.resolve("status"))) {
            manifest = w.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .findFirst().orElseThrow(() -> new AssertionError("parked manifest missing"));
        }
        String mf = Files.readString(manifest);
        assertTrue(mf.contains("parkedAt") && mf.contains("sink__d1"), mf);
        assertTrue(mf.contains("\"PARKED\""), mf);
        assertTrue(mf.contains("parkedTables"), mf);

        // uncommitted by choice: the BATCHES ledger says PARKED (the _status_ file is per-member,
        // and the members themselves ingested SUCCESS), and the commit log is KEPT
        Path batchesCsv;
        try (Stream<Path> w = Files.walk(Path.of(cfg.dirs().statusFilePath()).getParent())) {
            batchesCsv = w.filter(p -> p.getFileName().toString().contains("_batches_"))
                    .findFirst().orElseThrow();
        }
        assertTrue(Files.readString(batchesCsv).contains("PARKED"), "the batches ledger records the park");
        try (Stream<Path> w = Files.walk(dir.resolve("temp"))) {
            assertTrue(w.anyMatch(p -> p.getFileName().toString().startsWith("branch_commit_")),
                    "the branch commit log survives a park — it is the drain's resume record");
        }

        // a second poll cycle ingests nothing new — the park home is outside the poll tree
        CollectorProcessor.run(cfg);
        assertEquals(1, dataLines(dir.resolve("db_emea")).size(), "no re-ingest on the next cycle");
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
}
