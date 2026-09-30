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
            s.execute("COPY (SELECT range AS id FROM range(" + rows + ")) TO '" + f + "' (FORMAT parquet)");
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
}
