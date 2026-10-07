package com.gamma.inspector;

import com.gamma.enrich.EnrichmentConfig;
import com.gamma.enrich.ReferenceReader;
import com.gamma.etl.PipelineConfig;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code LA-DAILY-INGEST-1} T6 - a daily-updated REFERENCE data file (a subscriber dimension) landed to a Reference Dataset:
 * what {@code reference.load: replace} and {@code upsert} do when the next day's file arrives, and that the new value is
 * visible at read time to a join from a fact store with no re-ingest of the facts.
 *
 * <p>The seam is the one {@code FlatVsGraphLaneParityTest} uses: {@link ConsignmentIngestStrategy#writeAndTrace} writes the
 * parsed day (the parse stage is not under test) and {@link ReferenceReader#sqlFor} is the ONE reader a join and an enrichment
 * share. A day's FILE NAME is the output stem ({@code FileNames.outputStem}), which is what decides replace behaviour.
 */
class ReferenceDailyFileLoadTest {

    private static PipelineConfig dim(Path dir, String load) throws Exception {
        Files.createDirectories(dir);
        Map<String, Object> ref = "replace".equals(load)
                ? Map.of("load", "replace", "refresh_seconds", 86400)
                : Map.of("load", load, "key", List.of("SUBSCRIBER_ID"), "refresh_seconds", 86400,
                        "delete", Map.of("column", "STATUS", "values", List.of("DEL")));
        return PipelineConfig.fromMap(Map.of(
                "name", "SUBSCRIBER_DIM", "produces", "reference", "reference", ref,
                "dirs", Map.of("poll", dir.resolve("in").toString(), "database", dir.resolve("db").toString(),
                        "temp", dir.resolve("temp").toString()),
                "output", Map.of("format", "PARQUET"),
                "processing", Map.of("threads", 1)));
    }

    /** Land one daily file: rows are {SUBSCRIBER_ID, SEGMENT, STATUS}; {@code stem} is the output stem of the file name. */
    private static void land(PipelineConfig cfg, String stem, String batchId, String[]... rows) throws Exception {
        StringBuilder v = new StringBuilder();
        for (String[] r : rows)
            v.append(v.isEmpty() ? "" : ", ").append("('").append(r[0]).append("', '").append(r[1]).append("', '").append(r[2]).append("', 0)");
        File tmp = ConsignmentIngestStrategy.openTempDb(cfg, "ref_daily_");
        try (Connection c = DuckDbUtil.openConnection(tmp); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE transformed AS SELECT * FROM (VALUES " + v + ") AS t(SUBSCRIBER_ID, SEGMENT, STATUS, __src_id)");
            ConsignmentIngestStrategy.writeAndTrace(c, "transformed", List.of(), cfg, cfg.dirs().database(), stem, batchId,
                    Map.of(0, stem + ".csv"), "");
        } finally {
            DuckDbUtil.deleteTempDb(tmp);
        }
    }

    private static EnrichmentConfig.Reference binding(PipelineConfig cfg) {
        return new EnrichmentConfig.Reference("dim", null, null, cfg.identity().pipelineName());
    }

    /** The Reference Dataset as a reader sees it: subscriber -> segment (a duplicate key would show as a count mismatch). */
    private static Map<String, String> current(PipelineConfig cfg) throws Exception {
        Map<String, String> m = new TreeMap<>();
        int rows = 0;
        File tmp = DuckDbUtil.tempDbFile("ref_read_");
        try (Connection c = DuckDbUtil.openConnection(tmp); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT SUBSCRIBER_ID, SEGMENT FROM " + ReferenceReader.sqlFor(binding(cfg), List.of(cfg)))) {
            while (rs.next()) {
                m.put(rs.getString(1), rs.getString(2));
                rows++;
            }
        } finally {
            DuckDbUtil.deleteTempDb(tmp);
        }
        assertEquals(m.size(), rows, "no key is read twice: " + m);
        return m;
    }

    private static long parquetFiles(Path root) throws Exception {
        try (Stream<Path> w = Files.walk(root)) {
            return w.filter(p -> p.toString().endsWith(".parquet")).count();
        }
    }

    @Test
    void refreshSecondsIsCarriedButNothingElseReadsItHere(@TempDir Path dir) throws Exception {
        assertEquals(86400, dim(dir.resolve("a"), "replace").reference().refreshSeconds());
        assertEquals(86400, dim(dir.resolve("b"), "upsert").reference().refreshSeconds());
    }

    @Test
    void replaceWithAStableFileNameRewritesTheStore(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = dim(dir, "replace");
        land(cfg, "subscribers", "b1", new String[]{"S1", "GOLD", "A"}, new String[]{"S2", "SILVER", "A"}, new String[]{"S3", "GOLD", "A"});
        assertEquals(Map.of("S1", "GOLD", "S2", "SILVER", "S3", "GOLD"), current(cfg));

        // day 2, SAME file name: S1 changed, S3 REMOVED from the file, S4 new
        land(cfg, "subscribers", "b2", new String[]{"S1", "PLATINUM", "A"}, new String[]{"S2", "SILVER", "A"}, new String[]{"S4", "BRONZE", "A"});
        assertEquals(Map.of("S1", "PLATINUM", "S2", "SILVER", "S4", "BRONZE"), current(cfg),
                "changed value wins, new key added, the key absent from the new file is GONE");
        assertEquals(1, parquetFiles(dir.resolve("db")), "overwritten in place");
    }

    /**
     * THE TRAP: 'replace' is an OVERWRITE OF THE SAME OUTPUT FILE, not 'delete what was there'. A daily file whose NAME carries
     * the date gets a different output stem, so yesterday's file stays and the Reference Dataset reads BOTH days.
     */
    @Test
    void replaceWithADatedFileNameKeepsYesterdaysFileAndReadsDuplicateKeys(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = dim(dir, "replace");
        land(cfg, "subscribers_2026-10-05", "b1", new String[]{"S1", "GOLD", "A"}, new String[]{"S3", "GOLD", "A"});
        land(cfg, "subscribers_2026-10-06", "b2", new String[]{"S1", "PLATINUM", "A"});
        assertEquals(2, parquetFiles(dir.resolve("db")), "both days' files are on disk");
        int rows = 0;
        File tmp = DuckDbUtil.tempDbFile("ref_dup_");
        try (Connection c = DuckDbUtil.openConnection(tmp); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM " + ReferenceReader.sqlFor(binding(cfg), List.of(cfg)))) {
            rs.next();
            rows = rs.getInt(1);
        } finally {
            DuckDbUtil.deleteTempDb(tmp);
        }
        assertEquals(3, rows, "S1 is read twice (GOLD and PLATINUM) and the removed S3 is still there");
    }

    @Test
    void upsertChangesTheValueAddsTheKeyAndKeepsAKeyAbsentFromTheNewFile(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = dim(dir, "upsert");
        land(cfg, "subscribers_2026-10-05", "b1", new String[]{"S1", "GOLD", "A"}, new String[]{"S2", "SILVER", "A"}, new String[]{"S3", "GOLD", "A"});
        // day 2 (a different file name, as a dated daily file has): S1 changed, S2 re-delivered unchanged, S4 new, S3 NOT in the file
        land(cfg, "subscribers_2026-10-06", "b2", new String[]{"S1", "PLATINUM", "A"}, new String[]{"S2", "SILVER", "A"}, new String[]{"S4", "BRONZE", "A"});
        assertEquals(Map.of("S1", "PLATINUM", "S2", "SILVER", "S3", "GOLD", "S4", "BRONZE"), current(cfg),
                "latest version per key; a key absent from the new file is KEPT (upsert never infers a delete)");
        assertEquals(2, parquetFiles(dir.resolve("db")), "append-only: one version file per batch");
    }

    @Test
    void upsertRemovesAKeyOnlyThroughAnExplicitDeleteMarker(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = dim(dir, "upsert");
        land(cfg, "subscribers_2026-10-05", "b1", new String[]{"S1", "GOLD", "A"}, new String[]{"S3", "GOLD", "A"});
        land(cfg, "subscribers_2026-10-06", "b2", new String[]{"S3", "GOLD", "DEL"});
        assertEquals(Map.of("S1", "GOLD"), current(cfg), "reference.delete {column: STATUS, values: [DEL]} tombstones S3");
    }

    @Test
    void aDimensionChangeIsVisibleToAFactJoinWithoutTouchingTheFactFiles(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = dim(dir, "upsert");
        land(cfg, "subscribers_2026-10-05", "b1", new String[]{"S1", "GOLD", "A"}, new String[]{"S2", "SILVER", "A"});

        // the fact store: written once, never again
        Path facts = Files.createDirectories(dir.resolve("facts"));
        File tmp = DuckDbUtil.tempDbFile("ref_facts_");
        try (Connection c = DuckDbUtil.openConnection(tmp); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES (1, 'S1'), (2, 'S2'), (3, 'S1')) t(CALL_ID, SUBSCRIBER_ID)) TO '"
                    + facts.resolve("day1.parquet").toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(tmp);
        }
        Path factFile = facts.resolve("day1.parquet");
        long size = Files.size(factFile), mtime = Files.getLastModifiedTime(factFile).toMillis();

        String join = "SELECT f.CALL_ID, d.SEGMENT FROM read_parquet('" + factFile.toString().replace('\\', '/') + "') f JOIN (SELECT * FROM "
                + ReferenceReader.sqlFor(binding(cfg), List.of(cfg)) + ") d USING (SUBSCRIBER_ID) ORDER BY f.CALL_ID";
        assertEquals("1:GOLD,2:SILVER,3:GOLD", segments(join));

        land(cfg, "subscribers_2026-10-06", "b2", new String[]{"S1", "PLATINUM", "A"});          // the daily dimension file
        assertEquals("1:PLATINUM,2:SILVER,3:PLATINUM", segments(join), "the same fact rows now see the new segment");
        assertEquals(size, Files.size(factFile));
        assertEquals(mtime, Files.getLastModifiedTime(factFile).toMillis(), "the fact file was not rewritten");
        assertTrue(Files.exists(factFile));
    }

    private static String segments(String sql) throws Exception {
        StringBuilder out = new StringBuilder();
        File tmp = DuckDbUtil.tempDbFile("ref_join_");
        try (Connection c = DuckDbUtil.openConnection(tmp); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.append(out.isEmpty() ? "" : ",").append(rs.getInt(1)).append(':').append(rs.getString(2));
        } finally {
            DuckDbUtil.deleteTempDb(tmp);
        }
        return out.toString();
    }
}
