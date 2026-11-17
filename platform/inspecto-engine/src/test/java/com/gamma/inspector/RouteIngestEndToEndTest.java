package com.gamma.inspector;

import com.gamma.etl.PipelineConfig;
import com.gamma.pipeline.exec.StepReceipt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
     * SQL-BRANCH-1 (2026-09-06): a {@code sql} step INSIDE a route branch arms and executes on the ingest
     * lane — {@code RowShaper.sql} reads only its input relation, so the walk's NONE reference/execution
     * context is enough. The emea branch multiplies AMT by ten; apac is untouched.
     */
    @Test
    void aSqlStepInsideARouteBranchExecutesOnTheIngestLane(@TempDir Path dir) throws Exception {
        String d = dir.toString().replace("\\", "/");
        Path schema = dir.resolve("mini_schema.toon");
        Files.writeString(schema, com.gamma.etl.PipelineConfigBatchTest.miniSchema());
        Map<String, Object> cfgMap = new java.util.LinkedHashMap<>();
        cfgMap.put("name", "ROUTE_SQL_E2E");
        cfgMap.put("active", true);
        cfgMap.put("dirs", new java.util.LinkedHashMap<>(Map.of(
                "poll", d + "/inbox", "database", d + "/db", "backup", d + "/backup", "temp", d + "/temp",
                "quarantine", d + "/quarantine", "markers", d + "/markers", "status_dir", d + "/status")));
        cfgMap.put("output", Map.of("format", "CSV"));
        cfgMap.put("sinks", List.of(Map.of("database", d + "/db_emea", "format", "CSV"),
                Map.of("database", d + "/db_apac", "format", "CSV")));
        Map<String, Object> emea = new java.util.LinkedHashMap<>(Map.of("key", "emea", "where", "ID LIKE 'E%'",
                "database", d + "/db_emea"));
        // ⚠ `SELECT *, … AS new_col`, never an explicit column list and never `SELECT * REPLACE`: the branch
        // sink partitions by the derived year/month/day columns the projection slot added, so a projection
        // that names columns drops them and the sink refuses the batch ("partition_by expected to find
        // year"); and SqlGuard's lexical allow-list bans the word `replace` (it guards DDL), so the DuckDB
        // REPLACE clause is refused too. Both hold on the trunk as well — schema-mapping-authoring.md §6b.
        emea.put("steps", List.of(Map.of("sql", Map.of("sql", "SELECT *, AMT * 10 AS AMT10 FROM input"))));
        cfgMap.put("route", new java.util.LinkedHashMap<>(Map.of("mode", "case", "default", "apac",
                "branches", List.of(emea, Map.of("key", "apac", "where", "ID LIKE 'A%'", "database", d + "/db_apac")))));
        cfgMap.put("processing", new java.util.LinkedHashMap<>(Map.of("threads", 1,
                "schema_file", schema.toString().replace("\\", "/"),
                "csv_settings", new java.util.LinkedHashMap<>(Map.of("delimiter", ",", "skip_header_lines", 0,
                        "date_formats", List.of("%Y-%m-%d"), "timestamp_formats", List.of("%Y-%m-%d"))))));
        Path toon = dir.resolve("route_sql_pipeline.toon");
        Files.writeString(toon, com.gamma.config.io.ConfigCodec.toToon(cfgMap));

        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Path inbox = Files.createDirectories(Path.of(cfg.dirs().poll()));
        Files.writeString(inbox.resolve("feed.csv"),
                "ID,AMT,EVENT_DATE\nE1,1.0,2020-04-03\nA2,2.0,2020-04-03\n");

        CollectorProcessor.run(cfg);

        List<String> emeaRows = dataLines(dir.resolve("db_emea"));
        List<String> apacRows = dataLines(dir.resolve("db_apac"));
        assertEquals(1, emeaRows.size(), emeaRows.toString());
        assertTrue(emeaRows.get(0).startsWith("E1,") && emeaRows.get(0).contains("10"),
                "the branch's sql step ran over the branch's rows and added AMT10: " + emeaRows);
        assertEquals(1, apacRows.size(), apacRows.toString());
        assertTrue(apacRows.get(0).startsWith("A2,2"), "the other branch is untouched: " + apacRows);
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
        assertTrue(ledgerContains(cfg, "PARKED"), "the batches ledger records the park");
        try (Stream<Path> w = Files.walk(dir.resolve("temp"))) {
            assertTrue(w.anyMatch(p -> p.getFileName().toString().startsWith("branch_commit_")),
                    "the branch commit log survives a park — it is the drain's resume record");
        }

        // a second poll cycle ingests nothing new — the park home is outside the poll tree
        CollectorProcessor.run(cfg);
        assertEquals(1, dataLines(dir.resolve("db_emea")).size(), "no re-ingest on the next cycle");
    }

    /**
     * The park home is {@code <dirs.backup>/parked}, and {@code dirs.backup} is OPTIONAL — so a pipeline
     * that disables a step without one used to ARM, run, and die inside {@code ConsignmentIngestor.parkSource}
     * on a raw NPE, once per batch, presenting to the operator as {@code park failed: null}. It refuses at
     * {@code prepare()} now, which is the same posture every other refusal in that gate takes: an
     * unrunnable config is an authoring-time answer, not a per-cycle failure. Pinned end to end (not just
     * on {@code StepDisableArming}) because the ONLY thing that could regress is the wiring — the gate
     * reading the real {@code dirs.backup}.
     */
    @Test
    void disablingAStepWithNoBackupDirIsRefusedAtArming(@TempDir Path dir) throws Exception {
        Path toon = dir.resolve("park_pipeline.toon");
        String noBackup = parkFixture(dir, true).lines()
                .filter(l -> !l.trim().startsWith("backup:"))
                .collect(java.util.stream.Collectors.joining("\n", "", "\n"));
        Files.writeString(toon, noBackup);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PipelineConfig.load(toon.toString()));
        assertTrue(e.getMessage().contains("dirs.backup"), e.getMessage());
        assertTrue(e.getMessage().contains("nowhere to park"), e.getMessage());

        // and the same config WITH a backup dir still arms — the refusal is the missing park home,
        // not the disable itself
        Files.writeString(toon, parkFixture(dir, true));
        assertEquals(List.of("sink__d1"), PipelineConfig.load(toon.toString()).disabledSteps());
    }

    /**
     * Phase 4 S4c — park → re-enable → drain, end to end on the real ingest path. What this pins:
     * <ul>
     *   <li><b>Output parity:</b> after the drain the two destinations together hold exactly the rows a
     *       run with nothing disabled would have written — the park is a pause, not a loss.</li>
     *   <li><b>One commit tail for the whole batch:</b> the manifest lists BOTH branches' outputs (the
     *       already-committed one came back from the park sidecar), the original is in BACKUP (not the
     *       park home), and the batches ledger gains its SUCCESS row.</li>
     *   <li><b>The park artefacts are consumed:</b> park table, sidecar and the branch commit log are
     *       gone, and a second drain refuses because the batch is no longer parked.</li>
     * </ul>
     */
    @Test
    void reEnablingTheStepAndDrainingCompletesTheParkedConsignment(@TempDir Path dir) throws Exception {
        Path toon = dir.resolve("park_pipeline.toon");
        Files.writeString(toon, parkFixture(dir, true));

        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Path inbox = Files.createDirectories(Path.of(cfg.dirs().poll()));
        Files.writeString(inbox.resolve("feed.csv"),
                "ID,AMT,EVENT_DATE\nE1,1.0,2020-04-03\nA2,2.0,2020-04-03\nX3,3.0,2020-04-03\n");
        CollectorProcessor.run(cfg);

        String batchId = parkedBatchId(dir);
        // Refusal: the step is still disabled — config is the truth about that, never the manifest.
        IllegalStateException stillOff = assertThrows(IllegalStateException.class,
                () -> DrainCommand.run(toon.toString(), batchId));
        assertTrue(stillOff.getMessage().contains("still listed in processing.disabled_steps"),
                stillOff.getMessage());

        // The operator re-enables the step, then drains.
        Files.writeString(toon, parkFixture(dir, false));
        DrainCommand.Result r = DrainCommand.run(toon.toString(), batchId);
        assertEquals(List.of("sink__d1"), r.drainedBranches());

        // Output parity: E1 on the emea branch, A2 + X3 (via default:) on the apac branch — every input row.
        assertEquals(1, dataLines(dir.resolve("db_emea")).size(), "emea keeps the row it committed at park");
        assertEquals(2, dataLines(dir.resolve("db_apac")).size(), "the drained branch wrote its rows");

        // One commit tail for the WHOLE batch: both branches' outputs in the manifest, no parked state left.
        String mf = Files.readString(manifestOf(dir, batchId));
        assertFalse(mf.contains("\"parkedAt\""), "the parked record is superseded by the commit manifest");
        assertTrue(mf.contains("db_emea") && mf.contains("db_apac"),
                "the manifest lists BOTH branches' outputs — the pre-park ones came from the sidecar: " + mf);
        assertTrue(mf.contains("\"SUCCESS\""), mf);

        // The original completed the normal sequence: out of the park home, into backup.
        Path parkHome = dir.resolve("backup").resolve("parked");
        assertTrue(Files.exists(dir.resolve("backup").resolve("feed.csv")), "original backed up as committed");
        assertFalse(Files.exists(parkHome.resolve("feed.csv")), "original left the park home");
        assertFalse(Files.exists(inbox.resolve("feed.csv")), "and did not stay in the inbox");

        // The park artefacts and the resume record are consumed.
        try (Stream<Path> w = Files.walk(parkHome)) {
            assertTrue(w.noneMatch(p -> p.getFileName().toString().endsWith(".parquet")
                            || p.getFileName().toString().endsWith("__pending.json")),
                    "park table and sidecar deleted once the batch is whole");
        }
        try (Stream<Path> w = Files.walk(dir.resolve("temp"))) {
            assertTrue(w.noneMatch(p -> p.getFileName().toString().startsWith("branch_commit_")),
                    "the commit log is deleted — a fully committed batch never replays");
        }
        // ⚠ Every PipelineConfig.load stamps a NEW run timestamp, and the batches ledger is named
        // `_batches_<runTimestamp>.csv` — so the drain writes its row to a DIFFERENT file than the park
        // run, unless both happen inside the same second. Scan them all; a findFirst() here passes or
        // fails on which side of a second boundary the test lands.
        assertTrue(ledgerContains(cfg, "SUCCESS"), "the drain writes its terminal audit row");

        // Draining twice is refused, not half-repeated.
        IllegalStateException again = assertThrows(IllegalStateException.class,
                () -> DrainCommand.run(toon.toString(), batchId));
        assertTrue(again.getMessage().contains("not a PARKED Consignment"), again.getMessage());

        // And the drained batch does not come back on the next poll.
        CollectorProcessor.run(cfg);
        assertEquals(3, dataLines(dir.resolve("db_emea")).size() + dataLines(dir.resolve("db_apac")).size(),
                "no re-ingest after the drain");
    }

    /**
     * ROUTED-WRITE-COUNTS-PER-BRANCH-1: the workbench TEST RUN of a routed pipeline routes, exactly as
     * the production lane above does — the same rows to the same branches, and one reported row per
     * input row.
     *
     * <p>🔴 What this pins is a silent DEGRADATION, not a miscount. Branch↔sink pairing is by the
     * branch's declared {@code database}; {@code forScratchRun} re-rooted the sinks under the scratch
     * root and left the branches naming production directories, so {@code PipelineLift} paired nothing,
     * wired a plain data edge per destination, and the run fanned every row out to every sink. The
     * reported {@code rowsWritten} was then rows × branches (3 × 2 = 6 here; 9 → 18 on the shipped
     * {@code route_step}, 12 → 36 on {@code premed_events}) — the branch-count factor that named the
     * defect. ⚠ The counts were the symptom; the real damage was the CONTENT, because a builder testing
     * a route saw the whole feed under every branch and had no way to tell the routing had not run.
     *
     * <p>Assert the per-sink CONTENT, never just the total: a fan-out and a correct route agree on
     * {@code outputs().size()}, and would agree on the total too for any predicate that matched
     * everything.
     */
    @Test
    void aTestRunOfARoutedPipelineRoutesRatherThanFanningOut(@TempDir Path dir) throws Exception {
        Path toon = dir.resolve("route_pipeline.toon");
        Files.writeString(toon, routeFixture(dir));
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Path feed = Files.createDirectories(Path.of(cfg.dirs().poll())).resolve("feed.csv");
        Files.writeString(feed,
                "ID,AMT,EVENT_DATE\nE1,1.0,2020-04-03\nA2,2.0,2020-04-03\nX3,3.0,2020-04-03\n");

        Path scratch = dir.resolve("scratch");
        PipelineTestRun.Result r = PipelineTestRun.run(cfg, List.of(feed), scratch);

        assertEquals("SUCCESS", r.status(), r.error());
        assertEquals(3, r.totalInputRows());
        assertEquals(3, r.rowsWritten(),
                "one reported row per input row — not one per input row PER BRANCH");

        // The content half: each scratch destination holds ITS branch's rows and no others.
        List<String> emea = dataLines(scratch.resolve("database/sink0"));
        List<String> apac = dataLines(scratch.resolve("database/sink1"));
        assertEquals(List.of("E1,1.0,2020-04-03"), emea, "the emea branch got exactly the E row");
        assertEquals(List.of("A2,2.0,2020-04-03", "X3,3.0,2020-04-03"), apac,
                "apac got its own row AND the unmatched row via default:");
    }

    // ── X5 L3: Step Receipts on the graph fork, park and drain ──────────────────

    /**
     * 🔴 The failure a receipt exists for, on the ingest graph fork: a branch Step throws mid-walk. The Consignment
     * still fails exactly as before; its trail names the Step — the phases and every Step above it OK, its own
     * FAILED with a code, every sink below it NOT_REACHED — and its {@code pipeline.batch.failed} Signal carries
     * the same {@code failedStep} and {@code code} (plan §3.4).
     */
    @Test
    void aStepThatThrowsOnTheGraphForkIsNamedOnItsReceiptAndOnTheFailedSignal(@TempDir Path dir) throws Exception {
        String d = dir.toString().replace("\\", "/");
        Path schema = dir.resolve("mini_schema.toon");
        Files.writeString(schema, com.gamma.etl.PipelineConfigBatchTest.miniSchema());
        Map<String, Object> cfgMap = new java.util.LinkedHashMap<>();
        cfgMap.put("name", "ROUTE_BOOM");
        cfgMap.put("active", true);
        cfgMap.put("dirs", new java.util.LinkedHashMap<>(Map.of(
                "poll", d + "/inbox", "database", d + "/db", "backup", d + "/backup", "temp", d + "/temp",
                "quarantine", d + "/quarantine", "markers", d + "/markers", "status_dir", d + "/status")));
        cfgMap.put("output", Map.of("format", "CSV"));
        cfgMap.put("sinks", List.of(Map.of("database", d + "/db_emea", "format", "CSV"),
                Map.of("database", d + "/db_apac", "format", "CSV")));
        Map<String, Object> emea = new java.util.LinkedHashMap<>(Map.of("key", "emea", "where", "ID LIKE 'E%'",
                "database", d + "/db_emea"));
        // 'E1' cannot be an INTEGER: the branch's sql Step fails at run time, on the walk, never at load.
        emea.put("steps", List.of(Map.of("sql", Map.of("sql", "SELECT *, CAST(ID AS INTEGER) AS N FROM input"))));
        cfgMap.put("route", new java.util.LinkedHashMap<>(Map.of("mode", "case", "default", "apac",
                "branches", List.of(emea, Map.of("key", "apac", "where", "ID LIKE 'A%'", "database", d + "/db_apac")))));
        cfgMap.put("processing", new java.util.LinkedHashMap<>(Map.of("threads", 1,
                "schema_file", schema.toString().replace("\\", "/"),
                "csv_settings", new java.util.LinkedHashMap<>(Map.of("delimiter", ",", "skip_header_lines", 0,
                        "date_formats", List.of("%Y-%m-%d"), "timestamp_formats", List.of("%Y-%m-%d"))))));
        Path toon = dir.resolve("route_boom_pipeline.toon");
        Files.writeString(toon, com.gamma.config.io.ConfigCodec.toToon(cfgMap));
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Files.writeString(Files.createDirectories(Path.of(cfg.dirs().poll())).resolve("feed.csv"),
                "ID,AMT,EVENT_DATE\nE1,1.0,2020-04-03\nA2,2.0,2020-04-03\n");

        withSpaceAndRegistry((log, registry) -> {
            CollectorProcessor.run(cfg);

            List<com.gamma.signal.Signal> failed = com.gamma.signal.Signals.query(log.store(), "pipeline.batch.failed",
                    null, null, null, null, 10);
            assertEquals(1, failed.size(), "the Consignment fails as it always did");
            String batchId = failed.get(0).correlationId();

            List<StepReceipt> trail = registry.steps(batchId);
            assertEquals(StepReceipt.Outcome.OK, receiptOf(trail, "parse").outcome(), trail.toString());
            assertEquals(StepReceipt.Outcome.OK, receiptOf(trail, "transform").outcome());
            assertEquals(StepReceipt.Outcome.OK, receiptOf(trail, "route").outcome(), "the Step above it ran");
            StepReceipt boom = receiptOf(trail, "sql__emea");
            assertEquals(StepReceipt.Outcome.FAILED, boom.outcome(), trail.toString());
            assertEquals(StepReceipt.LANE_INGEST_GRAPH, boom.lane());
            assertNotNull(boom.diag().code(), "a FAILED receipt carries its code");
            assertEquals(1L, boom.in(), "it was handed the emea branch's one row");
            assertEquals(StepReceipt.Outcome.NOT_REACHED, receiptOf(trail, "sink__d0").outcome());
            assertEquals(StepReceipt.Outcome.NOT_REACHED, receiptOf(trail, "sink__d1").outcome());

            Map<String, Object> payload = failed.get(0).payload();
            assertEquals("sql__emea", payload.get("failedStep"), "the Signal names the Step: " + payload);
            assertEquals(boom.diag().code(), payload.get("code"));
        });
    }

    /**
     * A parked Consignment's trail shows the parked sink PARKED, with the rows it was handed and the park pointer;
     * after the drain (the body of {@code POST /runs/{name}/drain}) the trail gains a {@code drain} receipt for that
     * sink, under the drain's own Run, pointing at the outputs it wrote.
     */
    @Test
    void aParkedConsignmentsTrailShowsTheParkThenTheDrain(@TempDir Path dir) throws Exception {
        Path toon = dir.resolve("park_pipeline.toon");
        Files.writeString(toon, parkFixture(dir, true));
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Files.writeString(Files.createDirectories(Path.of(cfg.dirs().poll())).resolve("feed.csv"),
                "ID,AMT,EVENT_DATE\nE1,1.0,2020-04-03\nA2,2.0,2020-04-03\nX3,3.0,2020-04-03\n");

        withSpaceAndRegistry((log, registry) -> {
            CollectorProcessor.run(cfg);
            String batchId = parkedBatchId(dir);

            List<StepReceipt> trail = registry.steps(batchId);
            StepReceipt parked = receiptOf(trail, "sink__d1");
            assertEquals(StepReceipt.Outcome.PARKED, parked.outcome(), trail.toString());
            assertEquals(2L, parked.in(), "A2 + X3 (via default:) were parked");
            assertNotNull(parked.ref(), "the park pointer");
            assertEquals(StepReceipt.Pointer.PARK, parked.ref().kind());
            assertEquals("parked/" + batchId + "__sink__d1.parquet", parked.ref().path());
            StepReceipt live = receiptOf(trail, "sink__d0");
            assertEquals(StepReceipt.Outcome.OK, live.outcome(), "the live branch committed");
            assertEquals(1L, live.in());
            assertEquals(StepReceipt.Pointer.OUTPUTS, live.ref().kind());
            assertTrue(trail.stream().noneMatch(r -> StepReceipt.LANE_DRAIN.equals(r.lane())), "nothing drained yet");

            Files.writeString(toon, parkFixture(dir, false));
            DrainCommand.run(toon.toString(), batchId);

            List<StepReceipt> drained = registry.steps(batchId).stream()
                    .filter(r -> StepReceipt.LANE_DRAIN.equals(r.lane())).toList();
            assertEquals(1, drained.size(), "one drain receipt per parked sink: " + drained);
            StepReceipt dr = drained.get(0);
            assertEquals("sink__d1", dr.step());
            assertEquals(StepReceipt.Outcome.OK, dr.outcome());
            assertEquals(2L, dr.in(), "the drain wrote the parked rows");
            assertFalse(dr.runId().isEmpty(), "under the drain's own Run");
            assertEquals(StepReceipt.Pointer.OUTPUTS, dr.ref().kind());
            assertTrue(dr.ref().files() > 0, dr.toString());
            assertEquals(StepReceipt.Outcome.PARKED, receiptOf(registry.steps(batchId), "sink__d1").outcome(),
                    "a receipt is never rewritten: the park stays on the trail");
        });
    }

    @FunctionalInterface
    private interface SpaceBody {
        void run(com.gamma.event.EventLog log, com.gamma.consignment.DbConsignmentOutputStore registry) throws Exception;
    }

    /** Runs {@code body} in a fresh space with its own Signal ledger and an in-memory Consignment registry. */
    private static void withSpaceAndRegistry(SpaceBody body) throws Exception {
        String space = "x5-l3-" + java.util.UUID.randomUUID();
        com.gamma.event.EventLog log = com.gamma.event.EventLog.create();
        com.gamma.event.EventLog.register(space, log);
        org.slf4j.MDC.put(com.gamma.event.EventLog.SPACE_MDC_KEY, space);
        try (var registry = com.gamma.consignment.DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            com.gamma.consignment.ConsignmentOutputStores.use(registry);
            body.run(log, registry);
        } finally {
            com.gamma.consignment.ConsignmentOutputStores.use(null);
            org.slf4j.MDC.remove(com.gamma.event.EventLog.SPACE_MDC_KEY);
            com.gamma.event.EventLog.unregister(space);
        }
    }

    private static StepReceipt receiptOf(List<StepReceipt> trail, String step) {
        return trail.stream().filter(r -> r.step().equals(step)).findFirst()
                .orElseThrow(() -> new AssertionError("no receipt for " + step + " in " + trail));
    }

    /** The routed fixture of {@link #aRoutePipelineWritesEachBranchToItsPairedDestination}, as a string. */
    private static String routeFixture(Path dir) throws Exception {
        String d = dir.toString().replace("\\", "/");
        Path schema = dir.resolve("mini_schema.toon");
        if (!Files.exists(schema))
            Files.writeString(schema, com.gamma.etl.PipelineConfigBatchTest.miniSchema());
        return """
            name: ROUTE_TESTRUN
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
            """.formatted(d, schema.toString().replace("\\", "/"));
    }

    /** The park fixture of {@link #aDisabledBranchSinkParksTheConsignment}, with the disable toggleable. */
    private static String parkFixture(Path dir, boolean disabled) throws Exception {
        String d = dir.toString().replace("\\", "/");
        Path schema = dir.resolve("mini_schema.toon");
        if (!Files.exists(schema))
            Files.writeString(schema, com.gamma.etl.PipelineConfigBatchTest.miniSchema());
        return """
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
            %3$s  csv_settings:
                delimiter: ","
                skip_header_lines: 0
                date_formats[1]: "%%Y-%%m-%%d"
                timestamp_formats[1]: "%%Y-%%m-%%d"
            """.formatted(d, schema.toString().replace("\\", "/"),
                    disabled ? "  disabled_steps[1]: sink__d1\n" : "");
    }

    private static Path manifestOf(Path dir, String batchId) throws Exception {
        try (Stream<Path> w = Files.walk(dir.resolve("status"))) {
            return w.filter(p -> p.getFileName().toString().equals(batchId + ".json"))
                    .findFirst().orElseThrow(() -> new AssertionError("manifest missing for " + batchId));
        }
    }

    private static String parkedBatchId(Path dir) throws Exception {
        try (Stream<Path> w = Files.walk(dir.resolve("status"))) {
            String name = w.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .findFirst().orElseThrow(() -> new AssertionError("parked manifest missing"))
                    .getFileName().toString();
            return name.substring(0, name.length() - ".json".length());
        }
    }

    /** Whether ANY of the run-timestamped `_batches_` ledgers holds {@code status} — see the note at
     *  the drain assertion for why one file is not enough. */
    private static boolean ledgerContains(PipelineConfig cfg, String status) throws Exception {
        try (Stream<Path> w = Files.walk(Path.of(cfg.dirs().statusFilePath()).getParent())) {
            return w.filter(p -> p.getFileName().toString().contains("_batches_")).anyMatch(p -> {
                try { return Files.readString(p).contains(status); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
        }
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
