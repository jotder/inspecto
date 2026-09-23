package com.gamma.acquire.connectors;

import com.gamma.acquire.AcquisitionLedger;
import com.gamma.acquire.AcquisitionLedgers;
import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.ConnectionRegistry;
import com.gamma.acquire.InMemoryAcquisitionLedger;
import com.gamma.etl.PipelineConfig;
import com.gamma.inspector.CollectorProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KAFKA-OFFSET-REKEY-1 (design slice 0, stream-consumer-design §2.2), DB-export half: the REAL
 * {@link DbExportConnector} over an embedded DuckDB, driven through REAL poll cycles ({@link CollectorProcessor#run}:
 * discover, fetch to STAGING, land into the INBOX, ingest, {@code ConsignmentIngestor} commit). {@code fetchTo}
 * stashes the new max watermark keyed by the staging path it is handed; the commit takes it by the landed inbox
 * path. {@code DbExportConnectorTest} cannot see a mismatch — it takes the stash by the same path it wrote.
 * The export name carries a millisecond token, so every cycle's slice is a new name (name dedup cannot mask it).
 */
class DbExportWatermarkCycleTest {

    @Test
    void committedExportAdvancesTheWatermarkAndLaterCyclesIngestOnlyNewRows(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("export_src.duckdb");
        String url = "jdbc:duckdb:" + db.toString().replace("\\", "/");
        sql(url, "CREATE TABLE events(ID VARCHAR, AMT DOUBLE, EVENT_DATE DATE, UPDATED_AT BIGINT)");
        sql(url, "INSERT INTO events VALUES ('r1',1,DATE '2020-04-03',1),('r2',1,DATE '2020-04-03',2),"
                + "('r3',1,DATE '2020-04-03',3)");
        String id = "rekey-db";
        ConnectionRegistry.register(new ConnectionProfile(id, "db", null, 0, null, null, null, null,
                Map.of("jdbc_url", url, "export_name", "events_{HHmmssSSS}.csv",
                        "query", "SELECT ID, AMT, EVENT_DATE, UPDATED_AT FROM events WHERE UPDATED_AT > :watermark ORDER BY UPDATED_AT",
                        "watermark_column", "UPDATED_AT", "watermark_type", "long", "watermark_initial", "0"), null));
        AcquisitionLedger original = AcquisitionLedgers.shared();
        InMemoryAcquisitionLedger ledger = new InMemoryAcquisitionLedger();
        AcquisitionLedgers.use(ledger);
        try {
            PipelineConfig cfg = load(dir, "REKEY_DB_PIPE", "collector:\n  connector: db\n  connection: " + id + "\n");

            CollectorProcessor.run(cfg);
            long rows1 = rows(cfg);
            String wm1 = ledger.dbWatermark(id).orElse("<none>");

            Thread.sleep(20);                                // a new export name for the next slice
            CollectorProcessor.run(cfg);                     // no new rows at the source
            long rows2 = rows(cfg);
            String wm2 = ledger.dbWatermark(id).orElse("<none>");

            sql(url, "INSERT INTO events VALUES ('r4',1,DATE '2020-04-03',4)");
            Thread.sleep(20);
            CollectorProcessor.run(cfg);                     // one new row at the source
            long rows3 = rows(cfg);
            String wm3 = ledger.dbWatermark(id).orElse("<none>");

            assertAll(
                () -> assertEquals(3, rows1, "cycle 1 ingests r1..r3"),
                () -> assertEquals("3", wm1, "the cycle-1 commit must advance the watermark to 3"),
                () -> assertEquals(3, rows2, "cycle 2 (no new source rows) ingests nothing"),
                () -> assertEquals("3", wm2, "watermark after cycle 2"),
                () -> assertEquals(4, rows3, "cycle 3 adds only r4 — every row exactly once"),
                () -> assertEquals("4", wm3, "watermark after cycle 3"));
        } finally {
            AcquisitionLedgers.use(original);
            ConnectionRegistry.remove(id);
        }
    }

    private static void sql(String url, String stmt) throws Exception {
        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement()) { st.execute(stmt); }
    }

    private static PipelineConfig load(Path dir, String name, String collectorBlock) throws Exception {
        String d = dir.toString().replace("\\", "/");
        Path schema = dir.resolve("mini_schema.toon");
        Files.writeString(schema, """
            partitionKey: EVENT_DATE
            raw:
              name: mini
              format: CSV
              fields[4]{name,selector,type}:
                ID,"0",VARCHAR
                AMT,"1",DOUBLE
                EVENT_DATE,"2",DATE
                UPDATED_AT,"3",BIGINT
            mapping:
              canonicalName: mini
              rawName: mini
              rules[4]{targetColumn,sourceExpression,transformType}:
                ID,ID,DIRECT
                AMT,AMT,DIRECT
                EVENT_DATE,EVENT_DATE,DIRECT
                UPDATED_AT,UPDATED_AT,DIRECT
            """);
        String toon = "name: " + name + "\nversion: 1\n" + """
            dirs:
              poll: %1$s/inbox
              database: %1$s/db
              backup: %1$s/backup
              temp: %1$s/temp
              errors: %1$s/errors
              quarantine: %1$s/quarantine
              markers: %1$s/markers
              status_dir: %1$s/status
              log_dir: %1$s/logs
            output:
              format: PARQUET
            processing:
              threads: 1
              file_pattern: "glob:**/*.csv"
              duplicate_check:
                enabled: true
                marker_extension: .processed
              schema_file: "%2$s"
              batch:
                max_files: 100
                max_bytes: 268435456
              csv_settings:
                delimiter: ","
                skip_header_lines: 0
                skip_junk_lines: 0
                skip_tail_lines: 0
                date_formats[1]: "%%Y-%%m-%%d"
                timestamp_formats[1]: "%%Y-%%m-%%d"
            """.formatted(d, schema.toString().replace("\\", "/")) + collectorBlock;
        Path p = dir.resolve(name.toLowerCase() + "_pipeline.toon");
        Files.writeString(p, toon);
        return PipelineConfig.load(p.toString());
    }

    /** Rows across the sink's committed Parquet files (never the {@code .staging} scratch). */
    private static long rows(PipelineConfig cfg) throws Exception {
        Path db = Path.of(cfg.dirs().database());
        if (!Files.isDirectory(db)) return 0;
        List<String> f;
        try (var w = Files.walk(db)) {
            f = w.filter(p -> p.toString().endsWith(".parquet") && !p.toString().contains(".staging"))
                    .map(p -> "'" + p.toAbsolutePath().toString().replace("\\", "/") + "'").sorted().toList();
        }
        if (f.isEmpty()) return 0;
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM read_parquet([" + String.join(",", f) + "])")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
