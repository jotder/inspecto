package com.gamma.query;

import com.gamma.etl.DuckDbExtension;
import com.gamma.sql.SqlSandbox;
import com.gamma.sql.SqlSandboxPolicy;
import com.gamma.util.LakehouseCatalog;

import com.gamma.util.SqlIdent;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Runs a resolved, {@code SqlGuard}-checked query against a space's data in an ephemeral DuckDB
 * sandbox and returns a typed {@link Result} (W4; design §6.2). Mirrors {@code ViewQuery}: the sandbox
 * is opened <b>unsealed</b> because the dataset relation legitimately reads Parquet by absolute path;
 * the safety boundary is that the caller-authored query {@code sql} has already passed {@code SqlGuard}
 * (single read-only SELECT, no file/extension functions) and only the trusted, server-built
 * {@code relationSql} is registered as the dataset view.
 */
public final class QueryExecutor {

    private QueryExecutor() {}

    private static final String DEFAULT_ALIAS = "__q";
    private static final Pattern SAFE_COL = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** One ORDER BY term. */
    public record Sort(String field, boolean descending) {}

    /**
     * @param datasetName the logical name the {@code sql} references (registered as a view); {@code null} if none
     * @param relationSql trusted relation SQL for the dataset ({@code CREATE VIEW datasetName AS relationSql}); {@code null} if none
     * @param sql         the resolved, SqlGuard-checked query text
     * @param limit       max rows to return (a further row is read to detect truncation)
     * @param offset      rows to skip
     * @param projection  output columns, or empty for all
     * @param sort        ORDER BY terms, or empty
     */
    public record Request(String datasetName, String relationSql, String sql,
                          int limit, int offset, List<String> projection, List<Sort> sort,
                          List<String> binds) {

        /** Existing callers: no bound parameters. Keeps the 7-arg shape source-compatible. */
        public Request(String datasetName, String relationSql, String sql,
                       int limit, int offset, List<String> projection, List<Sort> sort) {
            this(datasetName, relationSql, sql, limit, offset, projection, sort, List.of());
        }

        public Request {
            binds = binds == null ? List.of() : List.copyOf(binds);
        }
    }

    /** The typed, bounded result. */
    public record Result(List<ResultSetDescriptor.Column> columns, List<Map<String, Object>> rows,
                         int rowCount, boolean truncated, long elapsedMs) {}

    /**
     * Attach the deployment's shared DuckLake catalog as {@code lake}, when one is configured
     * (scale-out phase C, §5.4 bullet 5, D4/D4a).
     *
     * <p><b>What this buys.</b> The Parquet a query reads is normally located by absolute path, which
     * means a node can only read what is on its own disk. Attaching the shared catalog lets any node read
     * every slice, including ones another node ingested — the plan's difference between a platform and N
     * isolated islands. Visibility stays the catalog commit: a file appears here exactly when its
     * registering transaction committed, never earlier and never half-written.
     *
     * <p>⛔ <b>It must run BEFORE {@code seal()}, and there is no way around that.</b> Measured 2026-09-14:
     * a sealed sandbox refuses with <i>"Attaching Postgres databases is disabled through configuration"</i>,
     * because sealing sets {@code enable_external_access=false}. This method therefore sits in the trusted
     * registration phase beside the dataset view — the same place, for the same reason, and the sandbox's
     * safety story is unchanged: the caller's SQL was {@code SqlGuard}-checked upstream and cannot itself
     * attach anything.
     *
     * <p>⚠ <b>{@code SqlGuard} blocks {@code ATTACH} in USER sql and does not apply here.</b> That is not a
     * loophole being exploited — the guard is a text filter over caller-authored SQL at named call sites,
     * never a connection-wide policy, and this statement is framework-built from a {@code -D} property the
     * operator set. It is the same trust boundary the {@code CREATE VIEW} below already relies on.
     *
     * <p>⚠ <b>Unconfigured is the common case and must stay free.</b> Personal and single-node Standard
     * have no shared catalog; this is then a no-op and reads behave exactly as before.
     *
     * <p>⛔ <b>A configured-but-unreachable catalog FAILS the query rather than silently returning less.</b>
     * Swallowing the error would hand back a result set that is short by however much another node wrote,
     * with nothing to say so — the read-side twin of the invisible-output problem D10 refuses on the write
     * side. ⚠ {@code autoload_known_extensions=false} in the sandbox does NOT block this: measured, the
     * attach still loads {@code postgres_scanner} — provided it is installed.
     *
     * <p>🔴 <b>"Provided it is installed" was doing far more work than it looked.</b> Autoload treats only
     * DuckDB's own {@code extension_directory} as installed, so the file {@code package.ps1} stages under
     * {@code -Dduckdb.extension.dir} did NOT satisfy it and an air-gapped read reached for a network
     * {@code INSTALL} anyway ({@code AIRGAP-PGSCANNER-LOAD-1}). The scanner is now loaded BY NAME below,
     * through the same cached → staged-file → network ladder as {@code ducklake}.
     */
    private static void attachSharedCatalog(Connection conn) throws SQLException {
        String sql = attachSql();
        if (sql == null) return;
        DuckDbExtension.ensureLoaded(conn, "ducklake", "-D" + LakehouseCatalog.CATALOG_PROPERTY);
        // AIRGAP-PGSCANNER-LOAD-1 (2026-09-14): see attachBackendExtension — the ATTACH autoloads the
        // backend scanner, and autoload cannot see the file package.ps1 stages, so it must be loaded by
        // name or an air-gapped read reaches for a network INSTALL.
        String backend = attachBackendExtension();
        if (backend != null) {
            DuckDbExtension.ensureLoaded(conn, backend, "-D" + LakehouseCatalog.CATALOG_PROPERTY);
        }
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    /**
     * The backend scanner extension this node's {@code ATTACH} needs, or {@code null} when no shared
     * catalog is configured or it is a local file.
     *
     * <p>Package-private for the same reason {@link #attachSql()} is: the DECISION is then directly
     * testable without a live Postgres, which would make the test SKIP on every machine without one —
     * and this repository has already had a skipping test hide a broken classpath for months.
     *
     * <p>🔴 The javadoc above claimed {@code autoload_known_extensions=false} "does NOT block this ...
     * provided it is installed, which is what AIRGAP-EXTENSIONS-CI-1 is about". That was right about
     * autoload and wrong about what installed means: the staged file is NOT installed as far as autoload
     * is concerned, because autoload reads DuckDB's {@code extension_directory} and never
     * {@code -Dduckdb.extension.dir}.
     */
    static String attachBackendExtension() {
        LakehouseCatalog.Catalog cat = LakehouseCatalog.configured();
        return cat == null ? null : LakehouseCatalog.backendExtension(cat.url());
    }

    /**
     * The {@code ATTACH} this node would issue, or {@code null} when no shared catalog is configured.
     *
     * <p>Package-private so the DECISION is directly testable, the same idiom
     * {@code DuckLakeRegistrar.onRegistrationFailure} and {@code attachOptions} already use on the write
     * side. ⛔ The alternative was a test gated on a live Postgres, which would SKIP on every machine
     * without one — and this repo has already had a skipping test hide a broken classpath for months. What
     * is left untested here is one {@code st.execute} of a string this method returns; the string itself,
     * and every branch choosing it, are pinned.
     */
    static String attachSql() {
        LakehouseCatalog.Catalog cat = LakehouseCatalog.configured();
        if (cat == null) return null;
        LakehouseCatalog.requireShared(cat.url(), "-D" + LakehouseCatalog.CATALOG_PROPERTY);
        return "ATTACH 'ducklake:" + cat.url() + "' AS " + SHARED_CATALOG_ALIAS
                + " (DATA_PATH '" + cat.dataPath().replace("\\", "/") + "')";
    }

    /** The schema name a query uses to reach the shared lakehouse, e.g. {@code lake.main.my_table}. */
    static final String SHARED_CATALOG_ALIAS = "lake";

    public static Result run(Request req) throws SQLException, IOException {
        return run(req, SqlSandboxPolicy.defaultPolicy());
    }

    /**
     * {@link #run(Request)} under an explicit sandbox policy — for a caller that must fence its own statement
     * tighter than the JVM-wide default (LA-11's traversal carries a per-route query timeout).
     */
    public static Result run(Request req, SqlSandboxPolicy policy) throws SQLException, IOException {
        long t0 = System.nanoTime();
        try (SqlSandbox sandbox = SqlSandbox.open(policy)) {
            Connection conn = sandbox.connection();
            // Trusted registration: the ONLY place file-reading SQL runs (unsealed). The user query below
            // was SqlGuard-checked upstream, so it cannot itself read files.
            attachSharedCatalog(conn);
            if (req.datasetName() != null && req.relationSql() != null) {
                try (Statement st = conn.createStatement()) {
                    st.execute("CREATE VIEW " + q(req.datasetName()) + " AS " + req.relationSql());
                }
            }
            String wrapped = wrap(req);
            // A Rule Template's `:name` holes arrive here already rewritten to positional `?` with their
            // values in `binds` (RuleTemplate.compile). They are bound, never interpolated — so a bind
            // value can never alter the statement's shape, whatever it contains.
            try (Statement st = req.binds().isEmpty()
                    ? sandbox.statement()
                    : prepared(sandbox, wrapped, req.binds());
                 ResultSet rs = req.binds().isEmpty()
                    ? st.executeQuery(wrapped)
                    : ((java.sql.PreparedStatement) st).executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                List<String> names = new ArrayList<>(n);
                List<Integer> types = new ArrayList<>(n);
                for (int c = 1; c <= n; c++) {
                    names.add(md.getColumnLabel(c));
                    types.add(md.getColumnType(c));
                }
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int c = 1; c <= n; c++) row.put(md.getColumnLabel(c), wireValue(rs.getObject(c)));
                    rows.add(row);
                }
                boolean truncated = rows.size() > req.limit();
                if (truncated) rows = rows.subList(0, req.limit());
                List<ResultSetDescriptor.Column> columns = ResultSetDescriptor.describe(names, types, rows);
                return new Result(columns, rows, rows.size(), truncated, (System.nanoTime() - t0) / 1_000_000);
            }
        }
    }

    /**
     * A {@link java.sql.PreparedStatement} over {@code sql} with {@code binds} set positionally. Values are
     * set as strings and left for DuckDB to coerce against the column's own type — the same widening the
     * inline-literal path relied on, so a template's behaviour does not change with how it is executed.
     */
    private static java.sql.PreparedStatement prepared(SqlSandbox sandbox, String sql, List<String> binds)
            throws SQLException {
        java.sql.PreparedStatement ps = sandbox.preparedStatement(sql);
        try {
            for (int i = 0; i < binds.size(); i++) ps.setString(i + 1, binds.get(i));
        } catch (SQLException e) {
            ps.close();
            throw e;
        }
        return ps;
    }

    /**
     * Normalise a raw JDBC cell for the JSON wire: DuckDB returns DATE/TIMESTAMP/TIME columns as
     * {@code java.time} values, but the control-plane {@code ApiContext.JSON} mapper carries no
     * {@code jsr310} module by design (every wire record uses ISO-8601 strings), so serialising a
     * {@link java.time.temporal.Temporal} throws {@code InvalidDefinitionException}. Their
     * {@code toString()} is already ISO-8601, so we coerce here at the extraction point — the shared
     * read path for {@code /bi/query} and {@code /queries/{id}/run}. All other value types pass through.
     */
    private static Object wireValue(Object v) {
        return v instanceof java.time.temporal.Temporal ? v.toString() : v;
    }

    /** Wrap the user query with server-built projection / ORDER BY / LIMIT+1 / OFFSET (all identifier-safe). */
    private static String wrap(Request req) {
        String projection = (req.projection() == null || req.projection().isEmpty())
                ? "*"
                : req.projection().stream().map(QueryExecutor::safeCol).map(QueryExecutor::q)
                    .reduce((a, b) -> a + ", " + b).orElse("*");
        StringBuilder sb = new StringBuilder("SELECT ").append(projection)
                .append(" FROM (").append(req.sql()).append(") AS ").append(q(DEFAULT_ALIAS));
        if (req.sort() != null && !req.sort().isEmpty()) {
            sb.append(" ORDER BY ");
            for (int i = 0; i < req.sort().size(); i++) {
                Sort s = req.sort().get(i);
                if (i > 0) sb.append(", ");
                sb.append(q(safeCol(s.field()))).append(s.descending() ? " DESC" : " ASC");
            }
        }
        sb.append(" LIMIT ").append(Math.max(0, req.limit()) + 1)
          .append(" OFFSET ").append(Math.max(0, req.offset()));
        return sb.toString();
    }

    /** Validate a caller-supplied column identifier (projection/sort) — rejects anything but a plain identifier. */
    private static String safeCol(String col) {
        if (col == null || !SAFE_COL.matcher(col).matches())
            throw new IllegalArgumentException("unsafe column identifier '" + col + "'");
        return col;
    }

    private static String q(String ident) {
        return SqlIdent.q(ident);
    }
}
