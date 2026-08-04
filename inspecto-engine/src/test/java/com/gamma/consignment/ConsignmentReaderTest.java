package com.gamma.consignment;

import com.gamma.consignment.ConsignmentOutput.State;
import com.gamma.util.DuckDbUtil;
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
 * §14.4 step 2 — {@link ConsignmentReader}. The acceptance criterion the plan states for this step is
 * <b>"a write attempt through {@code read()} fails"</b>, so the refusals are the point of this class and are
 * asserted first; the happy path only establishes that the relations really are readable.
 */
class ConsignmentReaderTest {

    /** A Hive-partitioned Parquet file holding {@code n} rows, exactly as {@code PartitionWriter} reveals one. */
    private static Path writeParquet(Path root, String partition, int n) throws Exception {
        Path dir = root.resolve(partition);
        Files.createDirectories(dir);
        Path file = dir.resolve("b1_out.parquet");
        File db = DuckDbUtil.tempDbFile("cr_seed_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT i AS id, 'v' || i AS name FROM range(" + n + ") t(i)) TO '"
                    + file.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
        return file;
    }

    private static ConsignmentOutput out(Path path, String table, long rows, State state) {
        return new ConsignmentOutput("c1", null, table, "year=2026/month=07/day=01", "2026-07-01",
                path.toString(), rows, 100L, "2026-08-04T10:00:00Z", 0, state);
    }

    private static ConsignmentReader readerOver(Path root, String partition, int n) throws Exception {
        Path f = writeParquet(root, partition, n);
        return SandboxConsignmentReader.over(List.of(out(f, "cdr", n, State.LIVE)));
    }

    // ── the refusals (§14.4 step 2's acceptance criterion) ───────────────────────

    /** The invariant this seam protects is §5.1 append-only: no write may be expressible through it. */
    @Test
    void refusesEveryWriteAttempt(@TempDir Path dir) throws Exception {
        try (ConsignmentReader reader = readerOver(dir, "year=2026/month=07/day=01", 3)) {
            for (String write : List.of(
                    "CREATE TABLE evil AS SELECT 1",
                    "INSERT INTO cdr VALUES (99, 'x')",
                    "UPDATE cdr SET name = 'x'",
                    "DELETE FROM cdr",
                    "DROP VIEW cdr",
                    "ALTER VIEW cdr RENAME TO other",
                    "COPY (SELECT * FROM cdr) TO '" + dir.resolve("leak.csv").toString().replace('\\', '/')
                            + "' (FORMAT CSV)")) {
                IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                        () -> reader.query(write), "must refuse: " + write);
                assertTrue(ex.getMessage().contains("read-only"), ex.getMessage());
            }
            // and the refusal is not merely advisory — nothing was written
            assertFalse(Files.exists(dir.resolve("leak.csv")), "the refused COPY must not have written");
        }
    }

    /** The file/extension surface stays out: a processor must not reach past its own Consignment. */
    @Test
    void refusesFileAndExtensionEscapes(@TempDir Path dir) throws Exception {
        try (ConsignmentReader reader = readerOver(dir, "year=2026/month=07/day=01", 3)) {
            for (String escape : List.of(
                    "SELECT * FROM read_csv('/etc/passwd')",
                    "SELECT * FROM read_parquet('/somewhere/else/*.parquet')",
                    "ATTACH 'other.db' AS other",
                    "INSTALL httpfs",
                    "LOAD httpfs",
                    "SET enable_external_access=true",
                    "SELECT getenv('PATH')",
                    "SELECT * FROM cdr; DROP VIEW cdr")) {
                assertThrows(IllegalArgumentException.class, () -> reader.query(escape),
                        "must refuse: " + escape);
            }
        }
    }

    // ── the happy path (that the relations are genuinely readable) ───────────────

    @Test
    void readsTheConsignmentsOwnRowsAndPartitionColumns(@TempDir Path dir) throws Exception {
        try (ConsignmentReader reader = readerOver(dir, "year=2026/month=07/day=01", 3)) {
            assertEquals(List.of("cdr"), reader.relations(), "one relation per target written");

            List<Map<String, Object>> rows = reader.query("SELECT COUNT(*) AS n FROM cdr");
            assertEquals(1, rows.size());
            assertEquals(3L, ((Number) rows.get(0).get("n")).longValue());

            // hive_partitioning is on, so the partition columns are queryable alongside the payload
            List<Map<String, Object>> parts = reader.query(
                    "SELECT DISTINCT year, month, day FROM cdr ORDER BY 1");
            assertEquals(1, parts.size());
            assertEquals("2026", String.valueOf(parts.get(0).get("year")));
            assertEquals("07", String.valueOf(parts.get(0).get("month")),
                    "hive_types_autocast=0 keeps the zero-padded segment a VARCHAR");
        }
    }

    /** Several files of one target become one relation, aligned BY NAME rather than by position. */
    @Test
    void unionsEveryPartitionOfATargetIntoOneRelation(@TempDir Path dir) throws Exception {
        Path a = writeParquet(dir, "year=2026/month=07/day=01", 2);
        Path b = writeParquet(dir, "year=2026/month=07/day=02", 5);
        try (ConsignmentReader reader = SandboxConsignmentReader.over(List.of(
                out(a, "cdr", 2, State.LIVE), out(b, "cdr", 5, State.LIVE)))) {

            assertEquals(List.of("cdr"), reader.relations());
            assertEquals(7L, ((Number) reader.query("SELECT COUNT(*) AS n FROM cdr")
                    .get(0).get("n")).longValue(), "both partitions readable through one relation");
        }
    }

    @Test
    void separateTargetsBecomeSeparateRelations(@TempDir Path dir) throws Exception {
        Path a = writeParquet(dir.resolve("cdr"), "year=2026/month=07/day=01", 2);
        Path b = writeParquet(dir.resolve("sms"), "year=2026/month=07/day=01", 4);
        try (ConsignmentReader reader = SandboxConsignmentReader.over(List.of(
                out(a, "cdr", 2, State.LIVE), out(b, "sms", 4, State.LIVE)))) {

            assertEquals(List.of("cdr", "sms"), reader.relations());
            assertEquals(4L, ((Number) reader.query("SELECT COUNT(*) AS n FROM sms")
                    .get(0).get("n")).longValue());
        }
    }

    /**
     * A compacted-away file's rows are held by something else now (§6.3), and the file may be gone — reading it
     * as part of this Consignment would double-count or fail.
     */
    @Test
    void excludesNonLiveOutputs(@TempDir Path dir) throws Exception {
        Path live = writeParquet(dir.resolve("a"), "year=2026/month=07/day=01", 2);
        Path gone = writeParquet(dir.resolve("b"), "year=2026/month=07/day=01", 9);
        try (ConsignmentReader reader = SandboxConsignmentReader.over(List.of(
                out(live, "cdr", 2, State.LIVE),
                out(gone, "compacted", 9, State.COMPACTED_AWAY),
                out(gone, "superseded", 9, State.SUPERSEDED)))) {

            assertEquals(List.of("cdr"), reader.relations(), "only LIVE outputs are readable");
        }
    }

    /** A LIVE row whose file has vanished must not make its whole target unreadable. */
    @Test
    void skipsLiveRowsWhoseFileIsMissing(@TempDir Path dir) throws Exception {
        Path present = writeParquet(dir, "year=2026/month=07/day=01", 2);
        ConsignmentOutput missing = out(dir.resolve("nope/gone_out.parquet"), "cdr", 4, State.LIVE);
        try (ConsignmentReader reader = SandboxConsignmentReader.over(
                List.of(out(present, "cdr", 2, State.LIVE), missing))) {

            assertEquals(2L, ((Number) reader.query("SELECT COUNT(*) AS n FROM cdr")
                    .get(0).get("n")).longValue(), "the surviving file still reads");
        }
    }

    /** Default-off registry ⇒ no outputs ⇒ no relations, and querying one is a plain failure, not a crash. */
    @Test
    void noOutputsYieldsNoRelations() throws Exception {
        try (ConsignmentReader reader = SandboxConsignmentReader.over(List.of())) {
            assertEquals(List.of(), reader.relations());
            assertThrows(Exception.class, () -> reader.query("SELECT * FROM cdr"));
        }
    }
}
