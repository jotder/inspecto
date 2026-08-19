package com.gamma.etl;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.*;
import java.sql.*;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class PartitionWriterTest {

    @Test
    void writesPartitionsAndExcludesSrcId(@TempDir Path dir) throws Exception {
        File db = DuckDbUtil.tempDbFile("test_");
        String dbDir = dir.resolve("out").toString();
        try (Connection conn = DuckDbUtil.openConnection(db);
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE transformed AS SELECT * FROM (VALUES " +
                    "('a', '2020', '04', '03', 0)," +
                    "('b', '2020', '04', '03', 1)," +
                    "('c', '2020', '01', '01', 0)) " +
                    "t(ID, year, month, day, __src_id)");

            List<PartitionOutput> outs = PartitionWriter.write(
                    conn, "transformed", dbDir, "CSV", null, "B1");

            assertEquals(2, outs.size());                       // two partitions
            for (PartitionOutput o : outs) {
                assertTrue(o.outputFile().endsWith("B1_out.csv"));
                String content = Files.readString(Path.of(o.outputFile()));
                assertFalse(content.contains("__src_id"));      // excluded
                assertTrue(content.contains("ID") || content.contains("a") || content.contains("c"));
            }
            try (Stream<Path> w = Files.walk(Path.of(dbDir))) {
                assertTrue(w.anyMatch(p -> p.toString().replace('\\','/').contains("year=2020/month=04/day=03")));
            }
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** E1: an EMPTY partition list writes ONE flat file — same staging + atomic reveal, no sentinel. */
    @Test
    void emptyPartitionListWritesOneFlatFile(@TempDir Path dir) throws Exception {
        File db = DuckDbUtil.tempDbFile("test_flat_");
        try (Connection conn = DuckDbUtil.openConnection(db);
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE transformed AS SELECT * FROM (VALUES " +
                    "('a', 0), ('b', 1), ('c', 0)) t(ID, __src_id)");

            for (String fmt : List.of("CSV", "PARQUET")) {
                String out = dir.resolve("out_" + fmt).toString();
                List<PartitionOutput> outs = PartitionWriter.write(
                        conn, "transformed", out, fmt, null, "FLAT", List.of());

                assertEquals(1, outs.size(), fmt + ": one unpartitioned file");
                assertEquals("", outs.get(0).partition(), fmt + ": no partition path");
                Path file = Path.of(outs.get(0).outputFile());
                assertEquals(Path.of(out), file.getParent(), fmt + ": lands at the store root");
                assertTrue(file.getFileName().toString().startsWith("FLAT_out"), file.toString());
                assertTrue(Files.size(file) > 0);
                // No sentinel directory, no leftover staging.
                try (Stream<Path> w = Files.walk(Path.of(out))) {
                    assertTrue(w.noneMatch(p -> p.toString().contains("year=1900")
                            || p.toString().endsWith(".tmp")));
                }
                // All three rows survive, __src_id stays excluded.
                try (ResultSet rs = st.executeQuery("CSV".equals(fmt)
                        ? "SELECT count(*) FROM read_csv('" + outs.get(0).outputFile().replace("\\", "/") + "')"
                        : "SELECT count(*) FROM read_parquet('" + outs.get(0).outputFile().replace("\\", "/") + "')")) {
                    assertTrue(rs.next());
                    assertEquals(3, rs.getLong(1));
                }
            }
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** B4: filename_column translates __src_id into per-row source filenames (both formats). */
    @Test
    void filenameColumnTranslatesSrcIdIntoSourceFilenames(@TempDir Path dir) throws Exception {
        File db = DuckDbUtil.tempDbFile("test_fn_");
        String dbDir = dir.resolve("out").toString();
        try (Connection conn = DuckDbUtil.openConnection(db);
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE transformed AS SELECT * FROM (VALUES " +
                    "('a', '2020', '04', '03', 0)," +
                    "('b', '2020', '04', '03', 1)," +
                    "('c', '2020', '01', '01', 0)) " +
                    "t(ID, year, month, day, __src_id)");

            List<PartitionOutput> outs = PartitionWriter.write(
                    conn, "transformed", dbDir, "CSV", null, "F1",
                    List.of("year", "month", "day"), "src_file",
                    java.util.Map.of(0, "inbox/one.csv", 1, "inbox/two.csv"));

            assertEquals(2, outs.size());
            String april = Files.readString(outs.stream()
                    .filter(o -> o.partition().contains("month=04")).findFirst().orElseThrow()
                    .outputFile().transform(Path::of));
            assertTrue(april.contains("src_file"), "the declared column is present:\n" + april);
            assertTrue(april.contains("inbox/one.csv") && april.contains("inbox/two.csv"),
                    "per-member values on a multi-file batch:\n" + april);
            assertFalse(april.contains("__src_id"), "the internal tag itself stays excluded");

            // Parquet path: the column survives with correct values there too.
            String pqDir = dir.resolve("outp").toString();
            List<PartitionOutput> pq = PartitionWriter.write(
                    conn, "transformed", pqDir, "PARQUET", null, "F2",
                    List.of("year", "month", "day"), "src_file",
                    java.util.Map.of(0, "inbox/one.csv", 1, "inbox/two.csv"));
            try (ResultSet rs = st.executeQuery("SELECT DISTINCT src_file FROM read_parquet('"
                    + pq.get(0).outputFile().replace("\\", "/") + "') ORDER BY 1")) {
                assertTrue(rs.next());
            }
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** B4: a null filename_column through the new overload stays byte-identical to the plain write. */
    @Test
    void nullFilenameColumnWritesNoExtraColumn(@TempDir Path dir) throws Exception {
        File db = DuckDbUtil.tempDbFile("test_fn0_");
        String dbDir = dir.resolve("out").toString();
        try (Connection conn = DuckDbUtil.openConnection(db);
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE transformed AS SELECT * FROM (VALUES " +
                    "('a', '2020', '04', '03', 0)) t(ID, year, month, day, __src_id)");
            List<PartitionOutput> outs = PartitionWriter.write(
                    conn, "transformed", dbDir, "CSV", null, "N1",
                    List.of("year", "month", "day"), null, java.util.Map.of());
            String content = Files.readString(Path.of(outs.get(0).outputFile()));
            assertFalse(content.contains("src_file"));
            assertFalse(content.contains("__src_id"));
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** B4: a relation with no __src_id cannot honestly carry a filename column — hard failure. */
    @Test
    void filenameColumnWithoutLineageTagFails(@TempDir Path dir) throws Exception {
        File db = DuckDbUtil.tempDbFile("test_fnx_");
        try (Connection conn = DuckDbUtil.openConnection(db);
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE t AS SELECT 'a' AS ID, '2020' AS year, '01' AS month, '01' AS day");
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> PartitionWriter.write(conn, "t", dir.resolve("o").toString(), "CSV", null,
                            "X", List.of("year", "month", "day"), "src_file", java.util.Map.of()));
            assertTrue(e.getMessage().contains("__src_id"), e.getMessage());
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    @Test
    void revealsManyPartitionsInParallelWithoutLoss(@TempDir Path dir) throws Exception {
        // 40 distinct day partitions exceeds REVEAL_PARALLEL_THRESHOLD, so the reveal
        // fans out across the common pool. Every partition must still be revealed under
        // its stable name with no collisions or lost rows.
        File db = DuckDbUtil.tempDbFile("test_par_");
        String dbDir = dir.resolve("out").toString();
        try (Connection conn = DuckDbUtil.openConnection(db);
             Statement st = conn.createStatement()) {
            // 40 rows, each its own day → 40 partitions; row id == day so we can verify.
            st.execute("CREATE TABLE transformed AS SELECT " +
                    "  'row_' || d AS ID, '2020' AS year, '01' AS month, " +
                    "  lpad(CAST(d AS VARCHAR), 2, '0') AS day, 0 AS __src_id " +
                    "FROM range(1, 41) t(d)");

            List<PartitionOutput> outs = PartitionWriter.write(
                    conn, "transformed", dbDir, "CSV", null, "P");

            assertEquals(40, outs.size(), "one output per day partition");
            // Distinct partition paths (no two staged files collapsed onto one name).
            long distinctPartitions = outs.stream().map(PartitionOutput::partition).distinct().count();
            assertEquals(40, distinctPartitions);
            for (PartitionOutput o : outs) {
                assertTrue(o.outputFile().endsWith("P_out.csv"), o.outputFile());
                assertTrue(o.bytes() > 0, "non-empty: " + o.outputFile());
            }
            // No leftover .tmp files from the two-step reveal.
            try (Stream<Path> w = Files.walk(Path.of(dbDir))) {
                assertFalse(w.anyMatch(p -> p.toString().endsWith(".tmp")), "no stray temp files");
            }
            // Every revealed file holds exactly its one row; 40 rows total survive.
            int total = 0;
            for (PartitionOutput o : outs)
                total += Files.readAllLines(Path.of(o.outputFile())).size() - 1; // minus header
            assertEquals(40, total, "all rows conserved across parallel reveal");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }
}
