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
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The derived-table seam: a post-sync step creating a new table from the base table, per Consignment.
 *
 * <p>The refusals come first, because they are what keep the seam honest — a derived table's SQL runs on
 * the same unsealed sandbox {@link ConsignmentReader} uses, so anything the reader refuses this must
 * refuse too, or the seam is a hole straight through the read-only invariant.
 */
class DerivedTableWriterTest {

    /** A Parquet file holding {@code n} rows with a partitionable {@code grp} column. */
    private static Path seed(Path root, int n) throws Exception {
        Files.createDirectories(root);
        Path file = root.resolve("base.parquet");
        File db = DuckDbUtil.tempDbFile("dtw_seed_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT i AS id, CASE WHEN i % 2 = 0 THEN 'even' ELSE 'odd' END AS grp, "
                    + "i * 10 AS amt FROM range(" + n + ") t(i)) TO '"
                    + file.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
        return file;
    }

    private static ConsignmentReader readerOver(Path file, long rows) throws Exception {
        return SandboxConsignmentReader.over(List.of(new ConsignmentOutput(
                "c1", null, "base", "", null, file.toString(), rows, 1L, "2026-08-29T00:00:00Z",
                0, State.LIVE, null, null, "sync")));
    }

    // ── the guard ─────────────────────────────────────────────────────────────

    /**
     * The INVARIANT property (⛔ not a security boundary — a processor is arbitrary Java on the classpath).
     * A derived table's SQL must clear the same {@code SqlGuard} allow-list {@code ConsignmentReader.query}
     * does, because a {@code COPY … TO} writes a file the registry never learns about and everything
     * downstream — the Selector, retention, compaction, the next step's {@code outputs()} — keys off
     * registered outputs. Shape-checking for a leading SELECT lets every one of these through.
     */
    @Test
    void authorSqlIsHeldToTheSameAllowListAsAQuery() {
        for (String hostile : List.of(
                "COPY (SELECT 1) TO '/tmp/x.parquet' (FORMAT PARQUET)",
                "SELECT * FROM read_csv('/etc/passwd')",
                "SELECT 1; DROP TABLE base",
                "ATTACH '/tmp/other.db' AS other")) {
            List<String> violations =
                    GuardedDerivedTableEmitter.check(new DerivedTable("t", hostile), new LinkedHashSet<>());
            assertFalse(violations.isEmpty(), "should be refused: " + hostile);
        }
        // ...and an ordinary projection is admitted, so the guard is not simply refusing everything.
        assertTrue(GuardedDerivedTableEmitter.check(
                new DerivedTable("t", "SELECT * FROM base"), new LinkedHashSet<>()).isEmpty());
    }

    /** A name becomes a DIRECTORY, so it is jailed rather than escaped — the summary tier's rule verbatim. */
    @Test
    void unsafeNamesAndPartitionColumnsAreRefused() {
        var taken = new LinkedHashSet<String>();
        assertFalse(GuardedDerivedTableEmitter.check(new DerivedTable("../escape", "SELECT 1"), taken).isEmpty());
        assertFalse(GuardedDerivedTableEmitter.check(new DerivedTable("has space", "SELECT 1"), taken).isEmpty());
        assertFalse(GuardedDerivedTableEmitter.check(
                new DerivedTable("ok", "SELECT 1", "bad-col"), taken).isEmpty());
        assertFalse(GuardedDerivedTableEmitter.check(new DerivedTable("ok", "   "), taken).isEmpty());
    }

    /** Two emissions for one name would race for one path; picking a winner silently loses the other. */
    @Test
    void aDuplicateNameInOneRunIsRefused() {
        GuardedDerivedTableEmitter e = new GuardedDerivedTableEmitter();
        e.emit(new DerivedTable("totals", "SELECT 1 AS n"));
        assertThrows(IllegalArgumentException.class, () -> e.emit(new DerivedTable("totals", "SELECT 2 AS n")));
        assertEquals(1, e.emitted().size());
    }

    /** Every violation at once — a refusal should take one repair round, not several. */
    @Test
    void theRefusalNamesEveryViolation() {
        List<String> v = GuardedDerivedTableEmitter.check(
                new DerivedTable("bad name", "DROP TABLE x", "bad col"), new LinkedHashSet<>());
        assertTrue(v.size() >= 3, "expected name + sql + partitionBy violations, got: " + v);
    }

    // ── the writer ────────────────────────────────────────────────────────────

    /** A flat derived table: one file, the right rows, and a registry row that fills every field. */
    @Test
    void writesAFlatTableAndFillsTheRegistryRow(@TempDir Path tmp) throws Exception {
        Path base = seed(tmp.resolve("base"), 6);
        Path derived = tmp.resolve("_derived");
        try (ConsignmentReader r = readerOver(base, 6)) {
            List<ConsignmentOutput> rows = DerivedTableWriter.write(r, derived.toString(), "c1",
                    List.of(new DerivedTable("big", "SELECT id, amt FROM base WHERE amt >= 30")), "step_a");

            assertEquals(1, rows.size());
            ConsignmentOutput o = rows.get(0);
            assertEquals("c1", o.consignmentId(), "registered onto the SAME Consignment — that is the chain");
            assertEquals("big" + DerivedTableWriter.DERIVED_SUFFIX, o.tableName(), "namespaced");
            assertEquals("step_a", o.producer(), "attributable");
            assertEquals(3, o.rows(), "ids 3,4,5");
            assertEquals(State.LIVE, o.state());
            assertTrue(o.bytes() > 0);
            assertEquals("id:BIGINT|amt:BIGINT", o.schemaFingerprint(), "schema DERIVED, never authored");
            assertTrue(Files.exists(Path.of(o.path())), "the file exists before the row describes it");
            assertTrue(o.path().endsWith(".parquet"), o.path());
        }
    }

    /** Partitioning is what `compact` merges on and the Selector prunes with — one file per value. */
    @Test
    void writesOneFilePerPartitionValue(@TempDir Path tmp) throws Exception {
        Path base = seed(tmp.resolve("base"), 6);
        Path derived = tmp.resolve("_derived");
        try (ConsignmentReader r = readerOver(base, 6)) {
            List<ConsignmentOutput> rows = DerivedTableWriter.write(r, derived.toString(), "c1",
                    List.of(new DerivedTable("by_grp", "SELECT grp, amt FROM base", "grp")), "step_a");

            assertEquals(2, rows.size(), "one per distinct grp");
            assertEquals(List.of("even", "odd"), rows.stream().map(ConsignmentOutput::partitionKey).sorted().toList());
            for (ConsignmentOutput o : rows) {
                assertEquals(3, o.rows());
                assertTrue(Files.exists(Path.of(o.path())));
                assertTrue(o.path().replace('\\', '/').contains("grp=" + o.partitionKey()), o.path());
            }
        }
    }

    /** ⚠ A partition VALUE also reaches a path — refused, not escaped, exactly as a name is. */
    @Test
    void anUnsafePartitionValueIsRefusedRatherThanEscaped(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("base");
        Files.createDirectories(dir);
        Path file = dir.resolve("base.parquet");
        File db = DuckDbUtil.tempDbFile("dtw_bad_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT 1 AS id, '../escape' AS grp) TO '"
                    + file.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
        try (ConsignmentReader r = readerOver(file, 1)) {
            Exception e = assertThrows(IllegalArgumentException.class, () -> DerivedTableWriter.write(
                    r, tmp.resolve("_derived").toString(), "c1",
                    List.of(new DerivedTable("t", "SELECT * FROM base", "grp")), "step_a"));
            assertTrue(e.getMessage().contains("safe directory name"), e.getMessage());
        }
    }

    /** The writer needs the framework's own reader — a foreign one carries none of the Consignment's views. */
    @Test
    void aForeignReaderIsRefused() {
        ConsignmentReader foreign = new ConsignmentReader() {
            @Override public List<java.util.Map<String, Object>> query(String sql) { return List.of(); }
            @Override public List<String> relations() { return List.of(); }
            @Override public void close() { }
        };
        Exception e = assertThrows(IllegalArgumentException.class,
                () -> DerivedTableWriter.write(foreign, "x", "c1",
                        List.of(new DerivedTable("t", "SELECT 1")), "p"));
        assertTrue(e.getMessage().contains("framework's own reader"), e.getMessage());
    }
}
