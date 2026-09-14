package com.gamma.util;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A read-only browse seam over a DB-backed operational store's <b>live</b> JDBC connection — the Phase-2
 * hook the raw table browser ({@code DbBrowserRoutes}) uses to page through control-plane tables
 * (design {@code docs/superpower/db-browser-design.md}).
 *
 * <p><b>Why the store's own source.</b> Each operational store owns a single-writer-locked DuckDB file; a
 * second connection to it would fail to acquire the lock. So a browse read must run on the store's own
 * {@link #browseSource()} rather than opening its own connection.
 *
 * <p>⚠ <b>This used to hand back the live {@link Connection} and lock {@code browseMonitor()}</b> — the
 * store's own monitor — so a browse read could not race the store's writes. Both are gone with the
 * borrow-scoped {@link ConnectionSource} ({@code OPS-03}): there is no single long-lived connection for a
 * pooled store to return, and exclusion now lives in the source. For DuckDB the source IS one connection
 * behind one monitor, so browse reads still serialise against the store's writes exactly as before; for
 * Postgres each borrow gets its own connection and MVCC does the isolating.
 *
 * <p>Reads are strictly bounded (server-added {@code LIMIT n+1} truncation probe) and read-only. Ad-hoc
 * SQL passed to {@link #browseQuery} must already have cleared {@code SqlGuard} at the call site. A store
 * whose {@code close()} is not itself synchronised can be torn down mid-read at space shutdown; the
 * resulting {@link SQLException} surfaces to the caller rather than corrupting state (browse is best-effort).
 */
public interface BrowsableStore {

    /** Stable capability id for the browser catalog group, e.g. {@code "objects"}, {@code "jobs"}. */
    String browseId();

    /** Human label for the catalog group, e.g. {@code "Objects"}, {@code "Job Runs"}. */
    String browseLabel();

    /** This store's own table name(s) — its compile-time constants, not JDBC metadata discovery. */
    List<String> browseTables();

    /** The store's own connection source. A browse read borrows from it like any other operation. */
    ConnectionSource browseSource();

    /** One page of rows: the {@code truncation} flag is set when more than {@code limit} rows existed. */
    record Column(String name, String type) {}
    record Page(List<Column> columns, List<Map<String, Object>> rows, boolean truncated) {}

    /** {@code "postgres"} or {@code "duckdb"} — for the catalog engine label. Best-effort. */
    default String browseEngine() {
        return browseSource().isPostgres() ? "postgres" : "duckdb";
    }

    /** Paginated {@code SELECT *} over one of this store's own tables (server-built SQL). */
    default Page browseTable(String table, int limit, int offset) throws SQLException {
        if (!browseTables().contains(table))
            throw new IllegalArgumentException("unknown table '" + table + "'");
        return exec("SELECT * FROM " + quoteIdent(table), limit, offset);
    }

    /** Ad-hoc read-only SQL (already {@code SqlGuard}-checked by the caller) over the live connection. */
    default Page browseQuery(String sql, int limit, int offset) throws SQLException {
        return exec(sql, limit, offset);
    }

    /** Wrap the (trusted or guarded) inner SQL with a server-built {@code LIMIT n+1 / OFFSET}, run it under
     *  the store monitor, and materialise typed columns + rows. */
    private Page exec(String innerSql, int limit, int offset) throws SQLException {
        int lim = Math.max(0, limit);
        String wrapped = "SELECT * FROM (" + innerSql + ") AS __q LIMIT " + (lim + 1)
                + " OFFSET " + Math.max(0, offset);
        return browseSource().with(conn -> {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(wrapped)) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                List<Column> columns = new ArrayList<>(n);
                for (int c = 1; c <= n; c++) columns.add(new Column(md.getColumnLabel(c), md.getColumnTypeName(c)));
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int c = 1; c <= n; c++) row.put(md.getColumnLabel(c), wireValue(rs.getObject(c)));
                    rows.add(row);
                }
                boolean truncated = rows.size() > lim;
                // ⛔ Materialise before returning: the connection goes back to the pool at the end of
                // this callback, so a lazily-read ResultSet would be dead by the time the caller looks.
                if (truncated) rows = new ArrayList<>(rows.subList(0, lim));
                return new Page(columns, rows, truncated);
            }
        });
    }

    /** Coerce {@code java.time} temporals to their ISO string (the control-plane JSON mapper carries no
     *  jsr310 module) — matches {@code QueryExecutor.wireValue}. All other values pass through. */
    private static Object wireValue(Object v) {
        return v instanceof java.time.temporal.Temporal ? v.toString() : v;
    }

    /** Double-quote an identifier for the {@code FROM} clause (table names are validated store constants). */
    private static String quoteIdent(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }
}
