package com.gamma.consignment;

import com.gamma.consignment.ConsignmentOutput.State;
import com.gamma.sql.SqlViews;
import com.gamma.util.JdbcDrivers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ConsignmentSelector} (consignment addressing §7-A): the catalog subtracts from the glob, it never
 * replaces it. Two properties carry the whole design and every test here is one of them —
 * <b>unknown files stay in</b>, and <b>nothing to exclude means the caller's own expression back, unchanged</b>.
 */
class ConsignmentSelectorTest {

    @AfterEach
    void clearRegistry() {
        ConsignmentOutputStores.use(null);   // process-wide static — never leak into another test
    }

    // ── fixture ──────────────────────────────────────────────────────────────

    /** A one-row Parquet file at {@code dir/name.parquet} carrying {@code id}, and its path as written. */
    private static String parquet(Connection conn, Path dir, String name, int id) throws Exception {
        String path = dir.resolve(name + ".parquet").toString().replace("\\", "/");
        try (Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT " + id + " AS id) TO '" + path + "' (FORMAT PARQUET)");
        }
        return path;
    }

    private static ConsignmentOutput row(String path, State state) {
        return new ConsignmentOutput("c-" + path.hashCode(), "run-1", "cdr", "", null, path,
                1, 100, "2026-08-10T10:00:00Z", 0, state);
    }

    private static String glob(Path dir) {
        return dir.toString().replace("\\", "/") + "/*.parquet";
    }

    /** The ids the expression actually reads — the only assertion that proves the SQL is usable, not just shaped right. */
    private static List<Integer> idsFrom(Connection conn, String readExpr) throws Exception {
        List<Integer> ids = new java.util.ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM " + readExpr + " ORDER BY id")) {
            while (rs.next()) ids.add(rs.getInt(1));
        }
        return ids;
    }

    // ── the no-op path: absence and emptiness both hand back the caller's own glob ──

    @Test
    void withNoRegistryTheCallerGetsItsOwnGlobBack(@TempDir Path dir) throws Exception {
        try (Connection conn = JdbcDrivers.connect("jdbc:duckdb:")) {
            parquet(conn, dir, "a", 1);
            assertNull(ConsignmentOutputStores.shared(), "precondition: no registry for this space");

            assertEquals(SqlViews.reader("PARQUET", glob(dir), true),
                    ConsignmentSelector.resolve(conn, "PARQUET", glob(dir), true),
                    "byte-for-byte the unfiltered expression — not merely an equivalent one");
        }
    }

    @Test
    void withNothingToExcludeTheCallerGetsItsOwnGlobBack(@TempDir Path dir) throws Exception {
        try (Connection conn = JdbcDrivers.connect("jdbc:duckdb:");
             DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            String a = parquet(conn, dir, "a", 1);
            db.record(List.of(row(a, State.LIVE)));
            ConsignmentOutputStores.use(db);

            assertEquals(SqlViews.reader("PARQUET", glob(dir), true),
                    ConsignmentSelector.resolve(conn, "PARQUET", glob(dir), true));
        }
    }

    /** The property that makes this safe to switch on everywhere: a file the catalog has never heard of is
     *  unknown, not absent, so an install whose registry postdates its data reads exactly what it always did. */
    @Test
    void filesWithNoCatalogRowStayIn(@TempDir Path dir) throws Exception {
        try (Connection conn = JdbcDrivers.connect("jdbc:duckdb:");
             DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            String a = parquet(conn, dir, "a", 1);
            parquet(conn, dir, "unknown", 2);            // never registered
            db.record(List.of(row(a, State.SUPERSEDED)));
            ConsignmentOutputStores.use(db);

            assertEquals(List.of(2), idsFrom(conn, ConsignmentSelector.resolve(conn, "PARQUET", glob(dir), true)),
                    "the superseded file goes, the unregistered one stays");
        }
    }

    // ── the subtraction ──────────────────────────────────────────────────────

    @Test
    void supersededAndCompactedAwayAreBothSubtracted(@TempDir Path dir) throws Exception {
        try (Connection conn = JdbcDrivers.connect("jdbc:duckdb:");
             DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            String live = parquet(conn, dir, "live", 1);
            String old = parquet(conn, dir, "old", 2);
            String merged = parquet(conn, dir, "merged", 3);
            db.record(List.of(row(live, State.LIVE), row(old, State.SUPERSEDED),
                    row(merged, State.COMPACTED_AWAY)));
            ConsignmentOutputStores.use(db);

            String expr = ConsignmentSelector.resolve(conn, "PARQUET", glob(dir), true);
            assertEquals(List.of(1), idsFrom(conn, expr));
            assertTrue(expr.startsWith("read_parquet(["), "an explicit list once there is anything to leave out");
            assertTrue(expr.contains("union_by_name=true") && expr.contains("hive_partitioning=true"),
                    "and every other read option identical to the globbed form");
        }
    }

    /**
     * The trap. Output naming is not one-file-per-Consignment (§2.4): a full recompute rewrites a stable path
     * in place, so one path legitimately owns an old SUPERSEDED row and a current LIVE one. Excluding it
     * because a dead row mentions it would drop live data from every read.
     */
    @Test
    void aPathKeepsItsPlaceWhenAnyRowForItIsStillLive(@TempDir Path dir) throws Exception {
        try (Connection conn = JdbcDrivers.connect("jdbc:duckdb:");
             DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            String rewritten = parquet(conn, dir, "rewritten", 1);
            db.record(List.of(row(rewritten, State.SUPERSEDED)));   // the first write, later reprocessed
            db.record(List.of(row(rewritten, State.LIVE)));         // the recompute, same path

            assertTrue(db.unreadablePaths().isEmpty(), "readability is per path, not per registration");
            ConsignmentOutputStores.use(db);
            assertEquals(SqlViews.reader("PARQUET", glob(dir), true),
                    ConsignmentSelector.resolve(conn, "PARQUET", glob(dir), true));
        }
    }

    /**
     * The registry stores the writer's own spelling, which may be relative, while {@code glob()} answers in
     * DuckDB's. Comparing raw would match nothing and report success — the silent failure this whole
     * subtraction would otherwise inherit.
     */
    @Test
    void aRelativelySpelledRowStillExcludesTheFile(@TempDir Path dir) throws Exception {
        try (Connection conn = JdbcDrivers.connect("jdbc:duckdb:");
             DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            String absolute = parquet(conn, dir, "old", 2);
            parquet(conn, dir, "live", 1);
            String relative = Path.of("").toAbsolutePath().relativize(Path.of(absolute)).toString();
            db.record(List.of(row(relative, State.SUPERSEDED)));
            ConsignmentOutputStores.use(db);

            assertEquals(List.of(1), idsFrom(conn, ConsignmentSelector.resolve(conn, "PARQUET", glob(dir), true)),
                    "a row stored relative must still exclude the file glob() named absolutely");
        }
    }

    /** Every file excluded is not a new state — a glob over an empty store already fails — so it is made to
     *  fail the same way rather than emitting {@code read_parquet([])}. */
    @Test
    void excludingEverythingFailsLikeAnEmptyStore(@TempDir Path dir) throws Exception {
        try (Connection conn = JdbcDrivers.connect("jdbc:duckdb:");
             DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            String only = parquet(conn, dir, "only", 1);
            db.record(List.of(row(only, State.SUPERSEDED)));
            ConsignmentOutputStores.use(db);

            String expr = ConsignmentSelector.resolve(conn, "PARQUET", glob(dir), true);
            assertFalse(expr.contains("["), "no empty list literal reaches DuckDB");
            assertThrows(Exception.class, () -> idsFrom(conn, expr),
                    "reads fail with 'no files', exactly as they do over an empty store today");
        }
    }

    // ── the connection-free enumerator (DatasetRelation's path) ──────────────

    @Test
    void sourceLiteralIsThePlainQuotedGlobWhenThereIsNothingToExclude(@TempDir Path dir) throws Exception {
        try (Connection conn = JdbcDrivers.connect("jdbc:duckdb:");
             DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            String a = parquet(conn, dir, "a", 1);
            db.record(List.of(row(a, State.LIVE)));
            ConsignmentOutputStores.use(db);

            String root = dir.toString().replace("\\", "/");
            assertEquals("'" + root + "/**/*.parquet'", ConsignmentSelector.sourceLiteral(root, "parquet"));
        }
    }

    @Test
    void theWalkSubtractsTheSameFilesTheConnectionFormWould(@TempDir Path dir) throws Exception {
        try (Connection conn = JdbcDrivers.connect("jdbc:duckdb:");
             DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            String live = parquet(conn, dir, "live", 1);
            String old = parquet(conn, dir, "old", 2);
            db.record(List.of(row(live, State.LIVE), row(old, State.SUPERSEDED)));
            ConsignmentOutputStores.use(db);

            String root = dir.toString().replace("\\", "/");
            String literal = ConsignmentSelector.sourceLiteral(root, "parquet");
            assertEquals(List.of(1), idsFrom(conn, "read_parquet(" + literal + ")"));
        }
    }

    /**
     * {@code PartitionCompactor}'s safety model depends on its intermediates being invisible to readers'
     * globs. A walk that picked up a hidden tree DuckDB would not have matched would make data appear in
     * reads that was never there — the walk's one way to be more permissive than the glob it stands in for.
     */
    @Test
    void theWalkSkipsHiddenSegmentsJustAsAGlobDoes(@TempDir Path dir) throws Exception {
        try (Connection conn = JdbcDrivers.connect("jdbc:duckdb:");
             DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            Path hidden = dir.resolve(".staging");
            java.nio.file.Files.createDirectories(hidden);
            parquet(conn, hidden, "invisible", 99);
            String live = parquet(conn, dir, "live", 1);
            String old = parquet(conn, dir, "old", 2);
            db.record(List.of(row(live, State.LIVE), row(old, State.SUPERSEDED)));
            ConsignmentOutputStores.use(db);

            String root = dir.toString().replace("\\", "/");
            String literal = ConsignmentSelector.sourceLiteral(root, "parquet");
            assertFalse(literal.contains(".staging"), "a hidden tree must not be promoted into an explicit list");
            assertEquals(List.of(1), idsFrom(conn, "read_parquet(" + literal + ")"));
        }
    }

    /** Fail-open: a registry that cannot be consulted must cost a warning, never a read. */
    @Test
    void anUnusableConnectionFallsBackToTheGlob(@TempDir Path dir) throws Exception {
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            Connection conn = JdbcDrivers.connect("jdbc:duckdb:");
            String a = parquet(conn, dir, "a", 1);
            conn.close();
            db.record(List.of(row(a, State.SUPERSEDED)));
            ConsignmentOutputStores.use(db);

            assertEquals(SqlViews.reader("PARQUET", glob(dir), true),
                    ConsignmentSelector.resolve(conn, "PARQUET", glob(dir), true));
        }
    }
}
