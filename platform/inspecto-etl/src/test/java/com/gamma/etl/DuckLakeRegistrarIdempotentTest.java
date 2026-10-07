package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * DUCKLAKE-REGISTER-NOT-IDEMPOTENT-1. {@code registerOnce} is plain SQL on a connection where the catalog is
 * attached as {@code lake}; here {@code lake} is a native DuckDB file, which runs the same statements and
 * transactions. ⚠ NOT verified against a real DuckLake ATTACH (needs the extension, which the offline reactor
 * cannot load); only the SQL and transaction logic are.
 */
class DuckLakeRegistrarIdempotentTest {

    private static long count(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); var rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static Connection lake(Path dir) throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement s = c.createStatement()) {
            s.execute("ATTACH '" + dir.resolve("lake.duckdb").toString().replace("\\", "/") + "' AS lake");
        }
        return c;
    }

    private static String parquet(Connection c, Path dir, String name, int rows) throws SQLException {
        String f = dir.resolve(name).toString().replace("\\", "/");
        try (Statement s = c.createStatement()) {
            s.execute("COPY (SELECT range AS id FROM range(" + rows + ")) TO '" + f.replace("'", "''") + "' (FORMAT parquet)");
        }
        return f;
    }

    @Test
    void registeringTheSameConsignmentTwiceInsertsOnce(@TempDir Path dir) throws Exception {
        try (Connection c = lake(dir)) {
            List<String> files = List.of(parquet(c, dir, "a.parquet", 5));
            DuckLakeRegistrar.registerOnce(c, files, "t", "main", "batch-1");
            DuckLakeRegistrar.registerOnce(c, files, "t", "main", "batch-1");
            assertEquals(5, count(c, "SELECT count(*) FROM lake.main.t"), "second registration must be a no-op");
            assertEquals(1, count(c, "SELECT count(*) FROM lake.main." + DuckLakeRegistrar.RECORD_TABLE));
        }
    }

    @Test
    void aDifferentConsignmentIsStillRegistered(@TempDir Path dir) throws Exception {
        try (Connection c = lake(dir)) {
            List<String> files = List.of(parquet(c, dir, "a.parquet", 5));
            DuckLakeRegistrar.registerOnce(c, files, "t", "main", "batch-1");
            DuckLakeRegistrar.registerOnce(c, files, "t", "main", "batch-2");
            assertEquals(10, count(c, "SELECT count(*) FROM lake.main.t"));
        }
    }

    @Test
    void aFailedRecordRollsTheInsertBackSoNothingIsRegisteredWithoutARecord(@TempDir Path dir) throws Exception {
        try (Connection c = lake(dir)) {
            List<String> files = List.of(parquet(c, dir, "a.parquet", 5));
            // A record table of the wrong shape makes the record INSERT fail after the data INSERT succeeded.
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE lake.main." + DuckLakeRegistrar.RECORD_TABLE + " (target_table VARCHAR, consignment_id VARCHAR, registered_at TIMESTAMP, extra VARCHAR)");
            }
            assertThrows(SQLException.class,
                    () -> DuckLakeRegistrar.registerOnce(c, files, "t", "main", "batch-1"));
            assertEquals(0, count(c, "SELECT count(*) FROM lake.main.t"), "data insert must roll back");
        }
    }

    @Test
    void noIdDerivesTheKeyFromTheFileSet(@TempDir Path dir) throws Exception {
        assertEquals(DuckLakeRegistrar.derivedKey(List.of("/x/b", "/x/a")),
                DuckLakeRegistrar.derivedKey(List.of("/x/a", "/x/b")), "order-independent");
        assertNotEquals(DuckLakeRegistrar.derivedKey(List.of("/x/a")), DuckLakeRegistrar.derivedKey(List.of("/x/b")));
        try (Connection c = lake(dir)) {
            List<String> files = List.of(parquet(c, dir, "a.parquet", 3));
            DuckLakeRegistrar.registerOnce(c, files, "t", "main", null);
            DuckLakeRegistrar.registerOnce(c, files, "t", "main", null);
            assertEquals(3, count(c, "SELECT count(*) FROM lake.main.t"));
        }
    }

    /** Overwrite a file's rows in place, as a full recompute does (same path, new content). */
    private static void rewrite(Connection c, String file, int rows, String col) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("COPY (SELECT range + " + col + " AS id FROM range(" + rows + ")) TO '" + file
                    + "' (FORMAT parquet)");
        }
    }

    @Test
    void anOverwrittenFileWithNoIdIsRegisteredAgain(@TempDir Path dir) throws Exception {
        try (Connection c = lake(dir)) {
            String f = parquet(c, dir, "a.parquet", 5);
            DuckLakeRegistrar.registerOnce(c, List.of(f), "t", "main", null);
            rewrite(c, f, 7, "100");   // same path, new content and size
            DuckLakeRegistrar.registerOnce(c, List.of(f), "t", "main", null);
            assertEquals(12, count(c, "SELECT count(*) FROM lake.main.t"),
                    "a rewritten file must not be skipped as already registered");
        }
    }

    @Test
    void aRunIdRegistersARerunOfTheSamePathsButDedupesARetryOfTheSameRun(@TempDir Path dir) throws Exception {
        try (Connection c = lake(dir)) {
            String f = parquet(c, dir, "a.parquet", 5);
            DuckLakeRegistrar.registerOnce(c, List.of(f), "t", "main", "run-1");
            DuckLakeRegistrar.registerOnce(c, List.of(f), "t", "main", "run-1");
            rewrite(c, f, 5, "100");
            DuckLakeRegistrar.registerOnce(c, List.of(f), "t", "main", "run-2");
            assertEquals(10, count(c, "SELECT count(*) FROM lake.main.t"));
        }
    }

    @Test
    void twoSinksSharingOneLakeAndTableBothRegisterAndAWholeRetryAddsNothing(@TempDir Path dir) throws Exception {
        try (Connection c = lake(dir)) {
            String a = parquet(c, dir, "a.parquet", 5);
            String b = parquet(c, dir, "b.parquet", 3);
            for (int attempt = 0; attempt < 2; attempt++) {
                DuckLakeRegistrar.registerOnce(c, List.of(a), "t", "main", DuckLakeRegistrar.sinkKey("batch-1", "/db/A"));
                DuckLakeRegistrar.registerOnce(c, List.of(b), "t", "main", DuckLakeRegistrar.sinkKey("batch-1", "/db/B"));
            }
            assertEquals(8, count(c, "SELECT count(*) FROM lake.main.t"), "A's rows plus B's, once");
        }
    }

    /** Pins the documented residual (CONSIGNMENT-ID-DETERMINISTIC-1): same id, different content, is skipped. */
    @Test
    void aSameIdEditOfDifferentContentIsSkippedByDesign(@TempDir Path dir) throws Exception {
        try (Connection c = lake(dir)) {
            String f = parquet(c, dir, "a.parquet", 5);
            DuckLakeRegistrar.registerOnce(c, List.of(f), "t", "main", "same-id");
            rewrite(c, f, 5, "100");
            DuckLakeRegistrar.registerOnce(c, List.of(f), "t", "main", "same-id");
            assertEquals(5, count(c, "SELECT count(*) FROM lake.main.t"));
            assertEquals(0, count(c, "SELECT count(*) FROM lake.main.t WHERE id >= 100"), "old rows kept");
        }
    }

    @Test
    void anErrorMidRegistrationLeavesNoOpenTransaction(@TempDir Path dir) throws Exception {
        try (Connection real = lake(dir)) {
            String f = parquet(real, dir, "a.parquet", 5);
            Connection failing = (Connection) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{Connection.class}, (p, m, args) -> {
                        if (m.getName().equals("prepareStatement") && String.valueOf(args[0]).startsWith("INSERT"))
                            throw new AssertionError("injected");
                        try { return m.invoke(real, args); } catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                    });
            assertThrows(AssertionError.class,
                    () -> DuckLakeRegistrar.registerOnce(failing, List.of(f), "t", "main", "b"));
            try (Statement s = real.createStatement()) {
                s.execute("BEGIN");   // fails with "cannot start a transaction within a transaction" if left open
                s.execute("ROLLBACK");
            }
            assertEquals(0, count(real, "SELECT count(*) FROM lake.main.t"));
        }
    }

    @Test
    void quotesInATableNameAndAPathAreEscaped(@TempDir Path dir) throws Exception {
        try (Connection c = lake(dir)) {
            java.nio.file.Files.createDirectories(dir.resolve("it's"));
            String f = parquet(c, dir, "it's/a.parquet", 4);
            DuckLakeRegistrar.registerOnce(c, List.of(f), "we\"ird", "main", "b");
            assertEquals(4, count(c, "SELECT count(*) FROM lake.main.\"we\"\"ird\""));
        }
    }

    /** Runs against a REAL DuckLake catalog when the extension is already cached (LOAD only, never INSTALL). */
    @Test
    void realDuckLakeRegistersOnce(@TempDir Path dir) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            try (Statement s = c.createStatement()) {
                s.execute("LOAD ducklake");
            } catch (SQLException e) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false, "ducklake extension not cached (" + e.getMessage() + "): UNVERIFIED here -- BEGIN/COMMIT with a data INSERT and a catalog INSERT, and CREATE TABLE IF NOT EXISTS, against a real DuckLake");
            }
            String d = dir.toString().replace("\\", "/");
            try (Statement s = c.createStatement()) {
                s.execute("ATTACH 'ducklake:" + d + "/cat.ducklake' AS lake (DATA_PATH '" + d + "/data')");
            }
            List<String> files = List.of(parquet(c, dir, "a.parquet", 5));
            DuckLakeRegistrar.registerOnce(c, files, "t", "main", "batch-1");
            DuckLakeRegistrar.registerOnce(c, files, "t", "main", "batch-1");
            assertEquals(5, count(c, "SELECT count(*) FROM lake.main.t"));
        }
    }
}
