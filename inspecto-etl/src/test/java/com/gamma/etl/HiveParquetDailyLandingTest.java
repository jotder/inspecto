package com.gamma.etl;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LA-DAILY-INGEST-1 T1 (D-ING5): does Hive-style Parquet in DAILY partition directories land through
 * {@code parsing.frontend: parquet}? SYNTHETIC corpus only, generated per test with {@code COPY ... (FORMAT
 * PARQUET)} (deterministic, nothing committed). The stage chain mirrors {@code NativeCsvStreamingEngine.streamUnit}:
 * {@code createRawInputView} -> {@code DataTransformer.materialize} -> {@code PartitionWriter.write}.
 *
 * <p>The behaviour tests OBSERVE and pin what the platform does today, including the surprising parts
 * (partition-value typing, re-delivery, late partition); partition-replace SEMANTICS belong to T4, so a test
 * here that pins "accumulates" is a record of today, not an endorsement. The throughput test is gated on
 * {@code -Dbench.run=true} (modest default size, {@code -Dbench.rows=}); findings are in
 * {@code docs/superpower/link-analysis-roadmap.md} "T1 findings".
 */
class HiveParquetDailyLandingTest {

    /** {@code dateSelector} is where EVENT_DATE comes from: the hive dir level {@code date} or an in-file column. */
    private static String schema(String dateSelector, String dateType) {
        return """
                partitionKey: EVENT_DATE
                raw:
                  name: xdr
                  format: CSV
                  fields[4]{name,selector,type}:
                    MSISDN_A,"msisdn_a",VARCHAR
                    MSISDN_B,"msisdn_b",VARCHAR
                    DURATION_S,"duration_s",BIGINT
                    EVENT_DATE,"%s",%s
                mapping:
                  canonicalName: xdr
                  rawName: xdr
                  rules[4]{targetColumn,sourceExpression,transformType}:
                    MSISDN_A,MSISDN_A,DIRECT
                    MSISDN_B,MSISDN_B,DIRECT
                    DURATION_S,DURATION_S,DIRECT
                    EVENT_DATE,EVENT_DATE,DIRECT
                """.formatted(dateSelector, dateType);
    }

    // ── (a) how the partition value reaches the Dataset ─────────────────────────────

    @Test
    void dateDirLevelReachesTheDatasetWhenHivePartitioningIsOn(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir, "a1", schema("date", "DATE"), true);
        try (Connection conn = DuckDbUtil.openConnection(DuckDbUtil.tempDbFile("hv_"))) {
            File f = writeDay(conn, dir, "date=2026-03-05", "part-00000.parquet", 100, 0, false);
            DuckDbCsvIngester.ingest(f, conn, cfg.schemas().single(), cfg, "raw_f0");
            // the path-derived value is auto-typed by DuckDB as DATE, then CAST to VARCHAR at the raw lane
            assertEquals(List.of("2026-03-05"), distinct(conn, "raw_f0", "EVENT_DATE"));
        }
    }

    @Test
    void dirLevelIsAutoDetectedEvenWithoutTheHivePartitioningOption(@TempDir Path dir) throws Exception {
        // OBSERVED on duckdb_jdbc 1.5.x: key=value dir levels are auto-detected, so the option is not
        // strictly required for a Hive layout. It is still the explicit, version-proof way to say it.
        PipelineConfig cfg = load(dir, "a2", schema("date", "DATE"), false);
        try (Connection conn = DuckDbUtil.openConnection(DuckDbUtil.tempDbFile("hv_"))) {
            File f = writeDay(conn, dir, "date=2026-03-05", "part-00000.parquet", 10, 0, false);
            DuckDbCsvIngester.ingest(f, conn, cfg.schemas().single(), cfg, "raw_f0");
            assertEquals(List.of("2026-03-05"), distinct(conn, "raw_f0", "EVENT_DATE"));
        }
    }

    @Test
    void yearMonthDayDirLevelsKeepTheirTextOnTheVarcharLane(@TempDir Path dir) throws Exception {
        Path schemaFile = dir.resolve("schema_a3.toon");
        Files.writeString(schemaFile, schema("month", "VARCHAR"), StandardCharsets.UTF_8);
        PipelineConfig cfg = loadWithSchema(dir, "a3", schemaFile, true);
        try (Connection conn = DuckDbUtil.openConnection(DuckDbUtil.tempDbFile("hv_"))) {
            for (String m : List.of("03", "12", "7")) {
                File f = writeDay(conn, dir, "year=2026/month=" + m + "/day=05", "part-00000.parquet", 10, 0, false);
                DuckDbCsvIngester.ingest(f, conn, cfg.schemas().single(), cfg, "raw_" + m);
                System.out.println("[T1] month=" + m + " -> raw VARCHAR " + distinct(conn, "raw_" + m, "EVENT_DATE"));
            }
            // zero padding survives when the directory wrote it (so 'month=03' stays '03'), and an unpadded
            // 'month=7' stays '7': the VARCHAR is the directory text, so a zero-padded layout is required
            // for a lexical year-month-day to sort and to parse as a date.
            assertEquals(List.of("03"), distinct(conn, "raw_03", "EVENT_DATE"));
            assertEquals(List.of("7"), distinct(conn, "raw_7", "EVENT_DATE"));
        }
    }

    @Test
    void aDateColumnPresentBothInTheFileAndInThePathIsObserved(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir, "a4", schema("date", "DATE"), true);
        try (Connection conn = DuckDbUtil.openConnection(DuckDbUtil.tempDbFile("hv_"))) {
            // the file physically carries a `date` column holding a DIFFERENT value than the directory says
            File f = writeDay(conn, dir, "date=2026-03-05", "part-00000.parquet", 10, 0, true);
            DuckDbCsvIngester.ingest(f, conn, cfg.schemas().single(), cfg, "raw_f0");
            // OBSERVED: the directory value wins and the in-file column is silently shadowed (no error, no
            // warning), so a feed whose file disagrees with its folder lands on the FOLDER's date.
            assertEquals(List.of("2026-03-05"), distinct(conn, "raw_f0", "EVENT_DATE"));
        }
    }

    // ── (c) day re-delivered / late earlier partition / new day, through the full stage chain ──

    @Test
    void redeliveredLateAndNewDayBehaviourIsRecorded(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir, "c", schema("date", "DATE"), true);
        Path db = dir.resolve("db");
        try (Connection conn = DuckDbUtil.openConnection(DuckDbUtil.tempDbFile("hv_"))) {
            // day 1 and day 2 land
            land(conn, cfg, writeDay(conn, dir, "date=2026-03-05", "part-00000.parquet", 100, 0, false), db);
            land(conn, cfg, writeDay(conn, dir, "date=2026-03-06", "part-00000.parquet", 100, 0, false), db);
            assertEquals(Map.of("2026-03-05", 100L, "2026-03-06", 100L), perDay(conn, db));
            assertEquals(2, outputFiles(db).size(), "one output file per day-partition");

            // new day: only adds
            land(conn, cfg, writeDay(conn, dir, "date=2026-03-07", "part-00000.parquet", 100, 0, false), db);
            assertEquals(3, perDay(conn, db).size());

            // same file name re-delivered with a CORRECTED (different size) day: the revealed file is replaced
            File corrected = writeDay(conn, dir, "date=2026-03-06", "part-00000.parquet", 150, 1, false);
            land(conn, cfg, corrected, db);
            assertEquals(150L, perDay(conn, db).get("2026-03-06"),
                    "same output file name => REPLACE_EXISTING on reveal: the day is replaced, not doubled");

            // a re-delivery under a DIFFERENT file name (a Hive re-export names its parts differently)
            land(conn, cfg, writeDay(conn, dir, "date=2026-03-06", "part-00001.parquet", 150, 1, false), db);
            assertEquals(300L, perDay(conn, db).get("2026-03-06"),
                    "different file name => a second file in the same partition: the day is DOUBLED (T4 owns this)");

            // a late earlier-date partition lands in its own event-date partition
            land(conn, cfg, writeDay(conn, dir, "date=2026-03-01", "part-00000.parquet", 40, 2, false), db);
            assertEquals(40L, perDay(conn, db).get("2026-03-01"));
            System.out.println("[T1] after scenarios, files=" + outputFiles(db).stream()
                    .map(p -> db.relativize(p).toString().replace('\\', '/')).sorted().toList());
        }
    }

    // ── (b)/(d) retype cost and day budget: gated, modest size ──────────────────────

    @Test
    void retypeCostAndDayBudgetMeasurement(@TempDir Path dir) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("bench.run"),
                "pass -Dbench.run=true to run the T1 measurement");
        int rows = Integer.getInteger("bench.rows", 2_000_000);
        int parts = Integer.getInteger("bench.parts", 4);
        PipelineConfig cfg = load(dir, "bm", schema("date", "DATE"), true);
        Map<String, Object> schema = cfg.schemas().single();
        List<String> partCols = PartitionDef.columnNames(PartitionDef.fromSchema(schema));
        Path db = dir.resolve("db");

        try (Connection conn = DuckDbUtil.openConnection(DuckDbUtil.tempDbFile("hv_"))) {
            List<File> files = new ArrayList<>();
            long t = System.nanoTime();
            for (int p = 0; p < parts; p++)
                files.add(writeDay(conn, dir, "date=2026-03-05", "part-" + String.format("%05d", p) + ".parquet",
                        rows / parts, p, false));
            double gen = secs(t);
            long bytes = files.stream().mapToLong(File::length).sum();
            String glob = fwd(dir.resolve("hive/date=2026-03-05")) + "/*.parquet";

            // baseline: typed read straight through, no VARCHAR round trip (3 warm repeats, best kept)
            double typedCtas = best(3, () -> {
                exec(conn, "DROP TABLE IF EXISTS t_typed");
                exec(conn, "CREATE TABLE t_typed AS SELECT msisdn_a AS MSISDN_A, msisdn_b AS MSISDN_B, "
                        + "duration_s AS DURATION_S, CAST(date AS DATE) AS EVENT_DATE "
                        + "FROM read_parquet('" + glob + "', hive_partitioning=true)");
            });
            // the product lane: CAST AS VARCHAR at read, then the mapping casts back
            double viaVarchar = best(3, () -> {
                exec(conn, "DROP TABLE IF EXISTS transformed");
                exec(conn, "DROP VIEW IF EXISTS raw_input");
                for (int p = 0; p < files.size(); p++) {
                    DuckDbCsvIngester.createRawInputView(files.get(p), conn, schema, cfg, "raw_m" + p, p);
                }
                exec(conn, "CREATE VIEW raw_input AS " + files.stream().map(f -> "SELECT * FROM raw_m"
                        + files.indexOf(f)).collect(Collectors.joining(" UNION ALL ")));
                DataTransformer.materialize(conn, schema, cfg, "raw_input", "transformed");
                for (int p = 0; p < files.size(); p++) exec(conn, "DROP VIEW IF EXISTS raw_m" + p);
            });
            exec(conn, "DROP VIEW IF EXISTS raw_input");
            long n = scalar(conn, "SELECT count(*) FROM transformed");

            t = System.nanoTime();
            List<PartitionOutput> outs = PartitionWriter.write(conn, "transformed", fwd(db), "PARQUET", "snappy",
                    "hv", partCols);
            double write = secs(t);

            // whole day end to end, cold, as the engine runs it (per-file view -> transform -> write)
            Path db2 = dir.resolve("db2");
            t = System.nanoTime();
            for (File f : files) land(conn, cfg, f, db2);
            double wholeDay = secs(t);

            System.out.printf("[T1-BENCH] rows=%,d parts=%d parquetIn=%.1fMB gen=%.2fs%n", n, parts, bytes / 1048576.0, gen);
            System.out.printf("[T1-BENCH] typed CTAS (no retype)      %.2fs%n", typedCtas);
            System.out.printf("[T1-BENCH] VARCHAR lane + typed CTAS   %.2fs  retype overhead=%.2fs (%.0f%%)%n",
                    viaVarchar, viaVarchar - typedCtas, 100.0 * (viaVarchar - typedCtas) / typedCtas);
            System.out.printf("[T1-BENCH] partitioned write           %.2fs  files=%d%n", write, outs.size());
            System.out.printf("[T1-BENCH] whole day end to end        %.2fs  (%,.0f rows/s)%n", wholeDay, n / wholeDay);
            assertEquals(rows, n);
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────

    private static void land(Connection conn, PipelineConfig cfg, File f, Path db) throws Exception {
        Map<String, Object> schema = cfg.schemas().single();
        List<String> partCols = PartitionDef.columnNames(PartitionDef.fromSchema(schema));
        exec(conn, "DROP TABLE IF EXISTS transformed");
        DuckDbCsvIngester.createRawInputView(f, conn, schema, cfg, "raw_input", 0);
        DataTransformer.materialize(conn, schema, cfg, "raw_input", "transformed");
        PartitionWriter.write(conn, "transformed", fwd(db), "PARQUET", "snappy",
                FileNames.outputStem(cfg, f.getName()), partCols);
        exec(conn, "DROP VIEW IF EXISTS raw_input");
        exec(conn, "DROP TABLE IF EXISTS transformed");
    }

    /**
     * Deterministic synthetic XDR rows (no randomness; {@code seed} only shifts values). Written to
     * {@code <dir>/hive/<partitionDir>/<name>}. {@code withDateInFile} adds a physical {@code date} column
     * whose value differs from the directory's, to observe the file-vs-path collision.
     */
    static File writeDay(Connection conn, Path dir, String partitionDir, String name, int rows, int seed,
                         boolean withDateInFile) throws Exception {
        Path d = dir.resolve("hive").resolve(partitionDir);
        Files.createDirectories(d);
        File f = d.resolve(name).toFile();
        String extra = withDateInFile ? ", DATE '1999-01-01' AS date" : "";
        exec(conn, "COPY (SELECT printf('9%09d', (i * 7919 + " + seed + ") % 1000000) AS msisdn_a,"
                + " printf('8%09d', (i * 104729 + " + seed + ") % 5000000) AS msisdn_b,"
                + " CAST((i * 31 + " + seed + ") % 3600 AS BIGINT) AS duration_s" + extra
                + " FROM range(" + rows + ") t(i)) TO '" + fwd(f).replace("'", "''") + "' (FORMAT PARQUET)");
        return f;
    }

    /** day -> row count, read back from the written Dataset (year/month/day dirs re-assembled from the data). */
    private static Map<String, Long> perDay(Connection conn, Path db) throws Exception {
        Map<String, Long> m = new java.util.TreeMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT CAST(EVENT_DATE AS VARCHAR), count(*) FROM read_parquet('"
                     + fwd(db) + "/**/*.parquet', hive_partitioning=true) GROUP BY 1 ORDER BY 1")) {
            while (rs.next()) m.put(rs.getString(1), rs.getLong(2));
        }
        return m;
    }

    private static List<Path> outputFiles(Path db) throws Exception {
        try (Stream<Path> s = Files.walk(db)) {
            return s.filter(p -> p.toString().endsWith(".parquet") && !p.toString().contains(".staging"))
                    .collect(Collectors.toList());
        }
    }

    private static List<String> distinct(Connection conn, String table, String c) throws Exception {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT DISTINCT \"" + c + "\" FROM \"" + table + "\" ORDER BY 1")) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private static long scalar(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void exec(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement()) { st.execute(sql); }
    }

    private interface Body { void run() throws Exception; }

    private static double best(int reps, Body b) throws Exception {
        double best = Double.MAX_VALUE;
        for (int i = 0; i < reps; i++) {
            long t = System.nanoTime();
            b.run();
            best = Math.min(best, secs(t));
        }
        return best;
    }

    private static double secs(long start) { return (System.nanoTime() - start) / 1e9; }

    private static String fwd(Path p) { return p.toString().replace('\\', '/'); }

    private static String fwd(File f) { return f.getAbsolutePath().replace('\\', '/'); }

    private static PipelineConfig load(Path dir, String tag, String schemaText, boolean hive) throws Exception {
        Path schema = dir.resolve("schema_" + tag + ".toon");
        Files.writeString(schema, schemaText, StandardCharsets.UTF_8);
        return loadWithSchema(dir, tag, schema, hive);
    }

    private static PipelineConfig loadWithSchema(Path dir, String tag, Path schema, boolean hive) throws Exception {
        String d = fwd(dir);
        String pipe =
                "name: HV_" + tag + "\n" +
                "version: 1\n" +
                "dirs:\n" +
                "  poll: " + d + "/hive\n" +
                "  database: " + d + "/db\n" +
                "  backup: " + d + "/backup\n" +
                "  temp: " + d + "/temp\n" +
                "  errors: " + d + "/errors\n" +
                "  quarantine: " + d + "/quarantine\n" +
                "  status_dir: " + d + "/status\n" +
                "output:\n" +
                "  format: PARQUET\n" +
                "processing:\n" +
                "  threads: 1\n" +
                "  file_pattern: \"glob:**/*.parquet\"\n" +
                "  schema_file: " + fwd(schema) + "\n" +
                "  csv_settings:\n" +
                "    date_formats[1]: \"%Y-%m-%d\"\n" +
                "    timestamp_formats[1]: \"%Y-%m-%d\"\n" +
                "parsing:\n  frontend: parquet\n" +
                (hive ? "  parquet:\n    hive_partitioning: true\n" : "");
        Path p = dir.resolve("hv_" + tag + "_pipeline.toon");
        Files.writeString(p, pipe, StandardCharsets.UTF_8);
        return PipelineConfig.load(p.toString());
    }
}
