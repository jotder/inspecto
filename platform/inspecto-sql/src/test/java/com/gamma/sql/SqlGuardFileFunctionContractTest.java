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
     * Non-table functions whose parameter is NAMED like a path, reviewed one by one (DuckDB 2.0,
     * {@code DUCKDB-2-MIGRATION-1}). Each takes a STRING and never opens a file, URL or glob:
     * <ul>
     *   <li>string splitters / joiners: {@code parse_path}/{@code parse_dirname}/{@code parse_dirpath}/
     *       {@code parse_filename}, {@code path_join} (string concatenation, no file-system lookup);</li>
     *   <li>JSON-path extractors (2.0 names the parameter {@code path}; 1.5 left it unnamed): the
     *       {@code ->>} operator and {@code json_extract*}/{@code json_value}/{@code json_exists}/
     *       {@code json_type}/{@code json_keys}/{@code json_array_length}/{@code json_set}/
     *       {@code json_insert}/{@code json_replace}/{@code json_remove} — the argument is a JSON Pointer
     *       or {@code $.a.b} expression evaluated against the in-memory JSON value;</li>
     *   <li>VARIANT-path extractors {@code variant_*} — the same, over a VARIANT value;</li>
     *   <li>{@code index_key} — navigates a STRUCT by field name.</li>
     * </ul>
     * Anything else a DuckDB upgrade gives a path-like parameter must be refused by {@link SqlGuard} or
     * reviewed and added HERE — never assumed safe. {@code import_database} (2.0 lists it as a pragma with
     * a {@code path}) is deliberately NOT here: it reads a database directory, so SqlGuard refuses it.
     */
    static final Set<String> REVIEWED_PATH_PARAMETER_FUNCTIONS_NOT_TOUCHING_FILES = Set.of(
            "parse_path", "parse_dirname", "parse_dirpath", "parse_filename", "path_join",
            "->>", "json_array_length", "json_exists", "json_extract", "json_extract_path",
            "json_extract_path_text", "json_extract_string", "json_insert", "json_keys", "json_remove",
            "json_replace", "json_set", "json_type", "json_value",
            "variant_array_length", "variant_exists", "variant_extract_string", "variant_keys",
            "variant_type", "index_key");

    @Test
    void everyFunctionWithAPathParameterIsRefusedUnlessReviewedAsNotTouchingFiles() throws Exception {
        Set<String> reviewed = REVIEWED_PATH_PARAMETER_FUNCTIONS_NOT_TOUCHING_FILES;
        Set<String> pathScalars = new TreeSet<>(), allNonTable = new TreeSet<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            loadInstalledExtensions(st);
            try (ResultSet rs = st.executeQuery("SELECT DISTINCT function_name FROM duckdb_functions() "
                    + "WHERE function_type NOT IN ('table', 'table_macro') AND len(list_filter(parameters, "
                    + "lambda p: lower(p) SIMILAR TO '.*(path|file|url|uri|location|directory).*')) > 0")) {
                while (rs.next()) pathScalars.add(rs.getString(1).toLowerCase(Locale.ROOT));
            }
            try (ResultSet rs = st.executeQuery("SELECT DISTINCT function_name FROM duckdb_functions() "
                    + "WHERE function_type NOT IN ('table', 'table_macro')")) {
                while (rs.next()) allNonTable.add(rs.getString(1).toLowerCase(Locale.ROOT));
            }
        }
        assertTrue(pathScalars.contains("parse_path"), "precondition: the parameter-name query found the known ones");
        List<String> unrefused = new ArrayList<>();
        for (String fn : pathScalars)
            if (!reviewed.contains(fn) && !refusesNaming(fn, "SELECT " + fn + "('x')")) unrefused.add(fn);
        assertEquals(List.of(), unrefused, "path-taking functions SqlGuard lets through - refuse them in "
                + "SqlGuard.BLOCKED_FUNCTION, or review them and add them to the reviewed set above");

        List<String> stale = new ArrayList<>();
        for (String fn : reviewed) if (!allNonTable.contains(fn)) stale.add(fn);
        assertEquals(List.of(), stale, "stale reviewed entries (no longer a function in this DuckDB)");
    }

    /** The reviewed JSON / VARIANT path functions stay usable — a KPI may read a JSON column. */
    @Test
    void reviewedJsonAndVariantPathFunctionsStillPass() {
        assertTrue(SqlGuard.isReadOnly("SELECT payload->>'$.a', json_extract_string(payload, '$.b.c') FROM input"));
        assertTrue(SqlGuard.isReadOnly("SELECT json_keys(payload, '$.a'), json_type(payload, '$.a') FROM input"));
        assertTrue(SqlGuard.isReadOnly("SELECT variant_extract_string(v, 'a'), variant_type(v, 'a') FROM input"));
        assertTrue(SqlGuard.isReadOnly("SELECT path_join('a', 'b'), parse_path('/a/b')"));
    }

    /** DuckDB 2.0 additions that touch resources, files or catalog state — refused by NAME, not by luck. */
    @Test
    void duckDb2ResourceAndStateFunctionsAreRefusedByName() {
        for (String fn : List.of("create_external_resource", "register_external_resource",
                "register_external_resource_type", "deregister_external_resource", "destroy_external_resource"))
            assertTrue(refusesNaming(fn, "SELECT * FROM " + fn + "('x')"), fn + " must be refused");
        assertTrue(refusesNaming("setval", "SELECT setval('s', 1)"), "setval mutates a sequence");
        assertTrue(refusesNaming("nextval", "SELECT nextval('s')"), "nextval advances a sequence");
        assertTrue(refusesNaming("sleep_ms", "SELECT sleep_ms(600000)"), "sleep_ms stalls a connection");
        assertTrue(refusesNaming("pg_sleep", "SELECT pg_sleep(600)"), "pg_sleep stalls a connection");
        assertTrue(refusesNaming("import_database", "SELECT import_database('C:/x')"), "import_database reads a directory");
        assertTrue(refusesNaming("export_database", "SELECT export_database('C:/x')"), "export_database writes a directory");
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
