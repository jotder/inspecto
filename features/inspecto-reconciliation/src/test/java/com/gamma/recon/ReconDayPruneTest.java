package com.gamma.recon;

import com.gamma.query.DatasetRelation;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RECON-PERF-RESIDUALS-1 (1): a day reads only the files whose Parquet statistics can hold it
 * ({@link ReconDay#dayFiles}) — a Hive {@code year=/month=/day=} store prunes to its day's folder without
 * {@code hive_partitioning}, and every case it cannot prove reads the whole store.
 */
class ReconDayPruneTest {

    private static final String D1 = "2026-09-25", D2 = "2026-09-26";

    @BeforeEach
    void clear() { ReconDay.Cache.clear(); }

    /** One Parquet file at {@code data/<store>/<sub>/data.parquet} of {@code (msisdn, active, event_date <type>)}. */
    static void write(Path data, String store, String sub, String values, String dateType, String extraCol) throws Exception {
        Path dir = data.resolve(store).resolve(sub);
        Files.createDirectories(dir);
        String f = dir.resolve("data.parquet").toString().replace(File.separatorChar, '/');
        DuckDbUtil.loadDriver();
        File db = DuckDbUtil.tempDbFile("recon_prune_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT msisdn, active, CAST(event_date AS " + dateType + ") AS event_date"
                    + (extraCol == null ? "" : ", 1 AS " + extraCol)
                    + " FROM (VALUES " + values + ") t(msisdn, active, event_date)) TO '" + f + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    static String rows(String day, String... keys) {
        StringBuilder sb = new StringBuilder();
        for (String k : keys) sb.append(sb.isEmpty() ? "" : ",").append("('").append(k).append("',1,'").append(day).append("')");
        return sb.toString();
    }

    private static ReconDay.Scoped resolve(Path data, String day) throws Exception {
        Map<String, Object> cfg = Map.of("datasets", List.of("a", "b"), "keyColumns", List.of("msisdn"),
                "compareColumns", List.of(Map.of("column", "active")));
        return ReconDay.resolve(cfg, id -> ds(id), id -> DatasetRelation.relationSql(ds(id), data, null), data, day);
    }

    private static Map<String, Object> ds(String id) {
        return Map.of("physicalRef", id, "dateField", "event_date");
    }

    private static long groups(ReconDay.Scoped s) throws Exception {
        return ReconService.dayRun(s.spec(), 0, 0, 0, 50, 1000).day().groups();
    }

    @Test
    void aHiveDayStoreReadsOnlyThatDaysFolder(@TempDir Path data) throws Exception {
        for (String side : List.of("a", "b")) {
            write(data, side, "year=2026/month=09/day=25", rows(D1, "m1", "m2", "m3"), "DATE", null);
            write(data, side, "year=2026/month=09/day=26", rows(D2, "m4", "m5"), "DATE", null);
        }
        ReconDay.Scoped s = resolve(data, D2);
        String rel = s.spec().sides().get(0).relationSql();
        assertTrue(rel.contains("day=26/data.parquet"), rel);
        assertFalse(rel.contains("day=25"), "D1's folder is not read: " + rel);
        assertEquals(2, groups(s), "D2's keys, exactly");
        ReconDay.Scoped s1 = resolve(data, D1);
        assertFalse(s1.spec().sides().get(1).relationSql().contains("day=26"));
        assertEquals(3, groups(s1));
        // the latest day (no day asked) prunes too
        assertEquals(D2, resolve(data, null).day());
    }

    @Test
    void aFileThatStraddlesTheDayIsKeptAndRowsAreStillFilteredExactly(@TempDir Path data) throws Exception {
        // a folder named for D1 that ALSO holds a D2 row (cut from another column): statistics, not names, decide
        for (String side : List.of("a", "b")) {
            write(data, side, "day=25", rows(D1, "m1") + "," + rows(D2, "m9"), "DATE", null);
            write(data, side, "day=24", rows("2026-09-24", "m0"), "DATE", null);
        }
        ReconDay.Scoped s = resolve(data, D2);
        String rel = s.spec().sides().get(0).relationSql();
        assertTrue(rel.contains("day=25"), "the straddling file is read: " + rel);
        assertFalse(rel.contains("day=24"), rel);
        assertEquals(1, groups(s), "only m9 is on D2");
    }

    @Test
    void aTimestampColumnPrunesOnItsCalendarDay(@TempDir Path data) throws Exception {
        for (String side : List.of("a", "b")) {
            write(data, side, "d1", rows(D1 + " 23:59:59", "m1"), "TIMESTAMP", null);
            write(data, side, "d2", rows(D2 + " 00:00:00", "m2"), "TIMESTAMP", null);
        }
        ReconDay.Scoped s = resolve(data, D2);
        String rel = s.spec().sides().get(0).relationSql();
        assertFalse(rel.contains("/d1/"), rel);
        assertTrue(rel.contains("/d2/"), "pruned to D2's file: " + rel);
        assertEquals(1, groups(s));
    }

    @Test
    void nothingIsPrunedWhereItCannotBeProven(@TempDir Path data) throws Exception {
        // a text day column: no comparable statistics -> the whole store
        for (String side : List.of("a", "b")) {
            write(data, side, "d1", rows(D1, "m1"), "VARCHAR", null);
            write(data, side, "d2", rows(D2, "m2"), "VARCHAR", null);
        }
        ReconDay.Scoped s = resolve(data, D2);
        assertTrue(s.spec().sides().get(0).relationSql().contains(DatasetRelation.relationSql(ds("a"), data, null)),
                "a text column is not pruned: " + s.spec().sides().get(0).relationSql());
        assertEquals(1, groups(s));
    }

    @Test
    void aColumnAddedMidLifeKeepsTheWholeStore(@TempDir Path data) throws Exception {
        // D2's file has a column D1's lacks; pruning to D1 alone would drop it from the relation -> not pruned
        for (String side : List.of("a", "b")) {
            write(data, side, "d1", rows(D1, "m1"), "DATE", null);
            write(data, side, "d2", rows(D2, "m2"), "DATE", "fee");
        }
        ReconDay.Scoped s1 = resolve(data, D1);
        String rel = s1.spec().sides().get(0).relationSql();
        assertTrue(rel.contains(DatasetRelation.relationSql(ds("a"), data, null)), "the whole store is read: " + rel);
        assertEquals(1, groups(s1));
        // D2's files hold every column, so D2 still prunes
        assertFalse(resolve(data, D2).spec().sides().get(0).relationSql().contains("/d1/"));
    }
}
