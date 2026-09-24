package com.gamma.sql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code SQLGUARD-PARQUET-METADATA-1}: {@link SqlGuard} must refuse every DuckDB function that can take a
 * path or URL — not just the {@code read_*}/{@code *_scan} family. {@code parquet_metadata('<any file>')}
 * passed the guard and returned a file's schema, row counts and min/max statistics to any caller that
 * relies on the guard alone on an unsealed connection ({@code QueryExecutor}'s documented posture).
 */
class SqlGuardFileFunctionContractTest {

    @TempDir Path outside;

    /** The exposure is REAL on an unsealed connection — and the guard is what must stop it. */
    @Test
    void parquetMetadataOnAFileOutsideTheDataRootIsRefused() throws Exception {
        String file = outside.resolve("secret.parquet").toString().replace('\\', '/');
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT 42 AS salary) TO '" + file + "' (FORMAT parquet)");
            String sql = "SELECT path_in_schema, stats_min, stats_max, num_values FROM parquet_metadata('" + file + "')";
            try (ResultSet rs = st.executeQuery(sql)) {
                assertTrue(rs.next(), "precondition: an unsealed connection really does expose the file's stats");
                assertEquals("42", rs.getString("stats_max"));
            }
            assertFalse(SqlGuard.check(sql).isEmpty(), "SqlGuard must refuse parquet_metadata on an arbitrary file");
        }
    }

    /**
     * The contract, DERIVED from the DuckDB actually on the classpath: every table function (and table
     * macro) {@code duckdb_functions()} lists is refused unless {@link SqlGuard#SAFE_TABLE_FUNCTIONS} names
     * it. A DuckDB upgrade that adds one fails here until it is classified. Extensions installed on this
     * machine but not loaded are LOADed best-effort first so their surfaces are covered too.
     */
    @Test
    void everyDuckDbTableFunctionIsRefusedUnlessExplicitlySafe() throws Exception {
        Set<String> tableFunctions = new TreeSet<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            loadInstalledExtensions(st);
            try (ResultSet rs = st.executeQuery("SELECT DISTINCT function_name FROM duckdb_functions() "
                    + "WHERE function_type IN ('table', 'table_macro')")) {
                while (rs.next()) tableFunctions.add(rs.getString(1).toLowerCase(Locale.ROOT));
            }
        }
        assertTrue(tableFunctions.size() > 50, "precondition: duckdb_functions() listed the table functions");
        assertTrue(tableFunctions.containsAll(Set.of("parquet_metadata", "read_text", "glob")), "precondition");

        List<String> unrefused = new ArrayList<>();
        for (String fn : tableFunctions) {
            if (SqlGuard.SAFE_TABLE_FUNCTIONS.contains(fn)) continue;
            if (!refusesNaming(fn, "SELECT * FROM " + fn + "('x')")) unrefused.add(fn);
        }
        assertEquals(List.of(), unrefused, "table functions SqlGuard lets through — refuse them, or add a "
                + "path-free generator to SqlGuard.SAFE_TABLE_FUNCTIONS");

        for (String safe : SqlGuard.SAFE_TABLE_FUNCTIONS) {
            assertTrue(tableFunctions.contains(safe), "stale SAFE_TABLE_FUNCTIONS entry: " + safe);
            assertTrue(SqlGuard.check("SELECT * FROM " + safe + "(3)").isEmpty(), safe + " must stay usable");
        }
    }

    /**
     * Scalar/aggregate functions whose parameter is NAMED like a path. Today's only ones are the string
     * splitters {@code parse_path}/{@code parse_dirname}/{@code parse_dirpath}/{@code parse_filename}, which
     * never touch the file system; any other one a DuckDB upgrade adds must be refused.
     */
    @Test
    void everyScalarWithAPathParameterIsRefusedUnlessItIsAStringSplitter() throws Exception {
        Set<String> stringOnly = Set.of("parse_path", "parse_dirname", "parse_dirpath", "parse_filename");
        Set<String> pathScalars = new TreeSet<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            loadInstalledExtensions(st);
            try (ResultSet rs = st.executeQuery("SELECT DISTINCT function_name FROM duckdb_functions() "
                    + "WHERE function_type NOT IN ('table', 'table_macro') AND len(list_filter(parameters, "
                    + "p -> lower(p) SIMILAR TO '.*(path|file|url|uri|location|directory).*')) > 0")) {
                while (rs.next()) pathScalars.add(rs.getString(1).toLowerCase(Locale.ROOT));
            }
        }
        assertTrue(pathScalars.contains("parse_path"), "precondition: the parameter-name query found the known ones");
        List<String> unrefused = new ArrayList<>();
        for (String fn : pathScalars)
            if (!stringOnly.contains(fn) && !refusesNaming(fn, "SELECT " + fn + "('x')")) unrefused.add(fn);
        assertEquals(List.of(), unrefused, "path-taking scalar functions SqlGuard lets through");
    }

    /**
     * Official extensions DuckDB AUTOLOADS on first use on an unsealed connection, which are not installed
     * offline so {@code duckdb_functions()} cannot list them here. Hand-kept on purpose: the derived test
     * above covers every extension that IS installed.
     */
    @Test
    void autoloadableExtensionFunctionsAreRefused() {
        for (String fn : List.of("iceberg_scan", "iceberg_metadata", "iceberg_snapshots", "delta_scan",
                "st_read", "st_read_meta", "st_readosm", "read_xlsx", "read_vortex", "mysql_query",
                "mysql_execute", "odbc_query", "postgres_query", "sqlite_attach", "ducklake_list_files",
                "load_aws_credentials", "start_ui", "dbgen", "dsdgen", "md_run_query"))
            assertTrue(refusesNaming(fn, "SELECT * FROM " + fn + "('x')"), fn + " must be refused");
    }

    /** DuckDB resolves a double-quoted function name — so a quoted name must not dodge the guard. */
    @Test
    void aQuotedFunctionNameIsStillRefused() throws Exception {
        String file = outside.resolve("q.parquet").toString().replace('\\', '/');
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT 1 AS x) TO '" + file + "' (FORMAT parquet)");
            String sql = "SELECT * FROM \"PARQUET_SCHEMA\"('" + file + "')";
            try (ResultSet rs = st.executeQuery(sql)) {
                assertTrue(rs.next(), "precondition: DuckDB resolves the quoted, upper-cased name");
            }
            assertTrue(refusesNaming("parquet_schema", sql));
            assertTrue(refusesNaming("read_csv", "SELECT * FROM \"read_csv\"('" + file + "')"));
        }
    }

    @Test
    void generatorsAndOrdinaryScalarsStillPass() {
        assertTrue(SqlGuard.isReadOnly("SELECT * FROM range(10) r(i)"));
        assertTrue(SqlGuard.isReadOnly("SELECT g FROM generate_series(1, 5) t(g)"));
        assertTrue(SqlGuard.isReadOnly("SELECT repeat('ab', 3), upper(cell), COUNT(*) FROM input GROUP BY cell"));
        assertTrue(SqlGuard.isReadOnly("SELECT unnest([1, 2, 3]) AS v"));
        assertTrue(SqlGuard.isReadOnly("SELECT parse_filename(name) FROM input"));
        assertTrue(SqlGuard.isReadOnly("SELECT md5(cell), date_trunc('day', ts) FROM input"));
    }

    private static boolean refusesNaming(String fn, String sql) {
        return SqlGuard.check(sql).stream().anyMatch(f -> f.message().contains("'" + fn + "(...)'"));
    }

    private static void loadInstalledExtensions(Statement st) throws Exception {
        List<String> installed = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(
                "SELECT extension_name FROM duckdb_extensions() WHERE installed AND NOT loaded")) {
            while (rs.next()) installed.add(rs.getString(1));
        }
        for (String ext : installed) {
            try {
                st.execute("LOAD \"" + ext + "\"");
            } catch (Exception ignored) {
                // best-effort: an installed-but-unloadable extension cannot be called either
            }
        }
    }
}
