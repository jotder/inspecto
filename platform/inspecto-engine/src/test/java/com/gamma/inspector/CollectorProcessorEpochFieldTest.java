package com.gamma.inspector;

import com.gamma.etl.PipelineConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MAPPING-EPOCH-TIMESTAMP-1, end to end: a CSV feed carrying a 10-digit epoch (with {@code 0} for none — the
 * MFS demo's {@code last_date_balance} shape) is loaded, parsed, typed and written to Parquet by real poll
 * cycles ({@link CollectorProcessor#run}) with the schema field declared {@code TIMESTAMP} +
 * {@code format: epoch} — no {@code sql} step.
 *
 * <p>The pipeline also declares {@code source_timezone: Asia/Tokyo}. A zone says how a wall clock was
 * written; an epoch has none, so applying it would move {@code 1699920000} (2023-11-14 00:00 UTC) to
 * 2023-11-13 15:00 and into the {@code day=13} folder. It must land at 00:00 in {@code day=14}.
 */
class CollectorProcessorEpochFieldTest {

    @Test
    void anEpochFieldLandsAsAUtcTimestampWithZeroAsNullAndCutsItsPartition(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = pipeline(dir);
        Path inbox = Files.createDirectories(Path.of(cfg.dirs().poll()));
        Files.writeString(inbox.resolve("balances.csv"), "a,1699920000\nb,0\nc,1699999999\n");

        CollectorProcessor.run(cfg);

        assertEquals(List.of("a|TIMESTAMP|2023-11-14 00:00:00|14",
                        "b|TIMESTAMP|null|null",
                        "c|TIMESTAMP|2023-11-14 22:13:19|14"),
                rows(cfg));
    }

    private static PipelineConfig pipeline(Path dir) throws Exception {
        String d = dir.toString().replace('\\', '/');
        Path schema = dir.resolve("epoch_schema.toon");
        Files.writeString(schema, """
                partitions[1]{column,source,type}:
                  day,TS,DATE_DAY
                raw:
                  name: epoch_feed
                  format: CSV
                  fields[2]{name,selector,type,format}:
                    ID,"0",VARCHAR,""
                    TS,"1",TIMESTAMP,epoch
                mapping:
                  canonicalName: epoch_feed
                  rawName: epoch_feed
                  fields[2]:
                    - name: ID
                      from: ID
                      fn: keep
                    - name: TS
                      from: TS
                      fn: keep
                """);
        Path p = dir.resolve("epoch_pipeline.toon");
        Files.writeString(p, """
                name: EPOCH_FEED
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
                  csv_settings:
                    delimiter: ","
                    has_header: false
                    skip_header_lines: 0
                    source_timezone: Asia/Tokyo
                    date_formats[1]: "%%Y-%%m-%%d"
                    timestamp_formats[1]: "%%Y-%%m-%%d %%H:%%M:%%S"
                """.formatted(d, schema.toString().replace('\\', '/')));
        return PipelineConfig.load(p.toString());
    }

    /** {@code ID|typeof(TS)|TS|day} of every committed sink row, ordered by ID. */
    private static List<String> rows(PipelineConfig cfg) throws Exception {
        List<String> files;
        try (var w = Files.walk(Path.of(cfg.dirs().database()))) {
            files = w.filter(f -> f.toString().endsWith(".parquet") && !f.toString().contains(".staging"))
                    .map(f -> "'" + f.toAbsolutePath().toString().replace('\\', '/') + "'").sorted().toList();
        }
        assertFalse(files.isEmpty(), "no sink output under " + cfg.dirs().database());
        List<String> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT ID, typeof(TS), TS::VARCHAR, day::VARCHAR FROM read_parquet(["
                     + String.join(",", files) + "], hive_partitioning=true) ORDER BY ID")) {
            while (rs.next())
                out.add(rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3) + "|" + rs.getString(4));
        }
        return out;
    }
}
