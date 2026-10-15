package com.gamma.query;

import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.util.JdbcDrivers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-DATA-TRUST-POLISH-1 #6: {@link DatasetMeasureProbe#dataAsOf} answers from a cache keyed on the Dataset's input-file stamp
 * (relation SQL + path/size/mtime of every file), and must always give the parquet maximum: the same answer as a cold read, a
 * new commit moves it at once, and a Dataset whose files cannot be stamped is read every time.
 */
class DatasetMeasureProbeDataAsOfCacheTest {

    private static long dayEnd(String day) {
        return java.time.LocalDate.parse(day).plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    private static Path parquet(Path dir, String file, String day) throws Exception {
        Files.createDirectories(dir);
        Path p = dir.resolve(file);
        try (Connection c = JdbcDrivers.connect("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT DATE '" + day + "' AS EVENT_DATE, 1 AS n) TO '" + p.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        }
        return p;
    }

    private static DatasetMeasureProbe probe(Path write, Path data) {
        return new DatasetMeasureProbe(() -> write, () -> data);
    }

    private static void dataset(Path write, String id, Map<String, Object> extra) throws Exception {
        Map<String, Object> ds = new HashMap<>(extra);
        ds.put("eventDate", "EVENT_DATE");
        new ComponentStore(write.resolve("registry")).write("dataset", id, ds);
    }

    @Test
    void theCachedAnswerIsTheParquetMaximumAndANewCommitMovesItAtOnce(@TempDir Path write, @TempDir Path data) throws Exception {
        parquet(data.resolve("feed"), "a.parquet", "2026-09-01");
        parquet(data.resolve("feed"), "b.parquet", "2026-09-03");
        dataset(write, "feed", Map.of("physicalRef", "feed"));
        DatasetMeasureProbe p = probe(write, data);

        assertEquals(OptionalLong.of(dayEnd("2026-09-03")), p.dataAsOf("feed", "EVENT_DATE"), "cold read = the parquet max");
        assertEquals(OptionalLong.of(dayEnd("2026-09-03")), p.dataAsOf("feed", "EVENT_DATE"), "warm read = the same answer");

        parquet(data.resolve("feed"), "c.parquet", "2026-09-07");            // a new commit adds a File
        assertEquals(OptionalLong.of(dayEnd("2026-09-07")), p.dataAsOf("feed", "EVENT_DATE"), "never staler than the disk");

        Files.delete(data.resolve("feed").resolve("c.parquet"));             // ...and a removed File lowers it again
        assertEquals(OptionalLong.of(dayEnd("2026-09-03")), p.dataAsOf("feed", "EVENT_DATE"), "never FRESHER than the disk");
    }

    @Test
    void anUnchangedStampIsServedFromTheCacheAndAChangedMtimeIsReread(@TempDir Path write, @TempDir Path data) throws Exception {
        Path f = parquet(data.resolve("feed"), "a.parquet", "2026-09-01");
        dataset(write, "feed", Map.of("physicalRef", "feed"));
        DatasetMeasureProbe p = probe(write, data);
        assertEquals(OptionalLong.of(dayEnd("2026-09-01")), p.dataAsOf("feed", "EVENT_DATE"));

        // Same-length content under the SAME size and mtime: only a cache can still answer with the old day.
        FileTime stamp = Files.getLastModifiedTime(f);
        long size = Files.size(f);
        Path other = parquet(data.resolve("scratch"), "x.parquet", "2026-09-02");
        assertEquals(size, Files.size(other), "fixture: the two days encode to the same size");
        Files.copy(other, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.setLastModifiedTime(f, stamp);
        assertEquals(OptionalLong.of(dayEnd("2026-09-01")), p.dataAsOf("feed", "EVENT_DATE"), "stamp unchanged: the cache answers");

        Files.setLastModifiedTime(f, FileTime.fromMillis(stamp.toMillis() + 5_000));
        assertEquals(OptionalLong.of(dayEnd("2026-09-02")), p.dataAsOf("feed", "EVENT_DATE"), "stamp moved: re-read");
    }

    @Test
    void aDatasetWithoutEnumerableFilesIsReadEveryTime(@TempDir Path write, @TempDir Path data) throws Exception {
        ViewStore views = new ViewStore(write.resolve("views"));
        views.write(new ViewDefinition("v", "p", List.of(), "SELECT DATE '2026-09-01' AS EVENT_DATE", "2026-10-14T00:00:00Z"));
        dataset(write, "virt", Map.of("view", "v"));
        DatasetMeasureProbe p = probe(write, data);
        assertEquals(OptionalLong.of(dayEnd("2026-09-01")), p.dataAsOf("virt", "EVENT_DATE"));

        views.write(new ViewDefinition("v", "p", List.of(), "SELECT DATE '2026-09-09' AS EVENT_DATE", "2026-10-14T00:00:00Z"));
        assertEquals(OptionalLong.of(dayEnd("2026-09-09")), p.dataAsOf("virt", "EVENT_DATE"), "no file stamp: fall back to reading");
    }

    @Test
    void anEmptyStoreIsNotCachedAsAnAnswer(@TempDir Path write, @TempDir Path data) throws Exception {
        Files.createDirectories(data.resolve("feed"));
        dataset(write, "feed", Map.of("physicalRef", "feed"));
        DatasetMeasureProbe p = probe(write, data);
        assertTrue(p.dataAsOf("feed", "EVENT_DATE").isEmpty());
        parquet(data.resolve("feed"), "a.parquet", "2026-09-05");
        assertEquals(OptionalLong.of(dayEnd("2026-09-05")), p.dataAsOf("feed", "EVENT_DATE"));
    }
}
