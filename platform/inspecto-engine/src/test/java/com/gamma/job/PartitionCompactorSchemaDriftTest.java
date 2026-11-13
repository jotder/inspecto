package com.gamma.job;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compaction reads a partition's files BY NAME (operator call on ANALYTICS-CORE-1, 2026-11-13): a store that gained a
 * column mid-life holds files of two schemas in one partition. Without {@code union_by_name} the merge either fails
 * (the wider file listed first) or keeps only the first file's columns and silently drops the newer ones (the narrower
 * file first, the case here); the merged file must keep every column, each value under its own name.
 */
class PartitionCompactorSchemaDriftTest {

    /** An aged Parquet file written from {@code select}, old enough to be a compaction candidate. */
    private static void aged(Path dir, String name, String select) throws Exception {
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        File db = DuckDbUtil.tempDbFile("compact_drift_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement()) {
            st.execute("COPY (" + select + ") TO '" + file.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(10, ChronoUnit.DAYS)));
    }

    @Test
    void filesWithReorderedAndAddedColumnsMergeByName(@TempDir Path root) throws Exception {
        Path part = root.resolve("dt=2026-08-04");
        aged(part, "c1_out.parquet", "SELECT 1 AS id, 'old' AS label");
        aged(part, "c2_out.parquet", "SELECT 'new' AS label, 2 AS id, 9.5 AS amount");   // reordered + one more column

        JobResult result = PartitionCompactor.run(new JobConfig("compact_job", "compact", null, null, true, false,
                Map.of("dir", root.toString(), "min_age_days", "1", "min_files", "2"), null, null));
        assertTrue(result.success(), result.message());
        assertTrue(result.message().contains("merged 2 file(s)"), result.message());

        Path merged;
        try (Stream<Path> files = Files.list(part)) {
            List<Path> parquet = files.filter(p -> p.getFileName().toString().endsWith(".parquet")).toList();
            assertEquals(1, parquet.size(), "the two files became one: " + parquet);
            merged = parquet.get(0);
        }
        List<String> rows = new ArrayList<>();
        File db = DuckDbUtil.tempDbFile("compact_drift_read_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, label, amount FROM read_parquet('"
                     + merged.toString().replace('\\', '/') + "') ORDER BY id")) {
            while (rs.next()) rows.add(rs.getInt(1) + "|" + rs.getString(2) + "|" + rs.getString(3));
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
        assertEquals(List.of("1|old|null", "2|new|9.5"), rows, "every value stays under its own column name");
    }
}
