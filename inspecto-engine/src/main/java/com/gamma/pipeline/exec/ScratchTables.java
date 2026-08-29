package com.gamma.pipeline.exec;

import com.gamma.util.JdbcRows;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Small helpers for seeding sample rows into a throwaway DuckDB table and reading relations back — shared by
 * the dry-run / preview paths ({@link ComponentPreview}, {@link PipelineDryRun}). Sample values seed as
 * {@code VARCHAR} columns (the union of the rows' keys), exactly as the preview contract specifies; operator
 * SQL casts as needed, just as in production.
 */
final class ScratchTables {

    private ScratchTables() {}

    /** The ordered union of keys across the sample rows — the seeded table's columns. */
    static List<String> columnsOf(List<Map<String, Object>> rows) {
        LinkedHashSet<String> cols = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) cols.addAll(r.keySet());
        return new ArrayList<>(cols);
    }

    /** Create {@code table} (all VARCHAR over {@code columns}) and insert the sample rows. */
    static void seed(Connection conn, String table, List<String> columns,
                     List<Map<String, Object>> rows) throws SQLException {
        StringBuilder create = new StringBuilder("CREATE TABLE ").append(q(table)).append(" (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) create.append(", ");
            create.append(q(columns.get(i))).append(" VARCHAR");
        }
        create.append(")");
        try (Statement st = conn.createStatement()) {
            st.execute(create.toString());
        }
        StringBuilder ins = new StringBuilder("INSERT INTO ").append(q(table)).append(" VALUES (");
        for (int i = 0; i < columns.size(); i++) ins.append(i > 0 ? ",?" : "?");
        ins.append(")");
        try (PreparedStatement ps = conn.prepareStatement(ins.toString())) {
            for (Map<String, Object> row : rows) {
                for (int i = 0; i < columns.size(); i++) {
                    Object v = row.get(columns.get(i));
                    ps.setString(i + 1, v == null ? null : v.toString());
                }
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** Row count of {@code table}. */
    static int count(Connection conn, String table) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM " + q(table))) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** Read up to {@code cap} rows of {@code table} as ordered column→value maps. */
    static List<Map<String, Object>> readRows(Connection conn, String table, int cap) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM " + q(table) + " LIMIT " + Math.max(0, cap))) {
            return JdbcRows.toMaps(rs);
        }
    }

    /** The column names of {@code table}, in declared order (works even when the table is empty). */
    static List<String> columnNames(Connection conn, String table) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM " + q(table) + " LIMIT 0")) {
            return JdbcRows.columnLabels(rs);
        }
    }

    /**
     * The columns of {@code table} as ordered {@code {name, type}} pairs, from DuckDB's own
     * {@code DESCRIBE} — <b>the derived output schema</b>.
     *
     * <p>DuckDB is the type authority here, and the answer is production-faithful for the delimited
     * path for a specific reason: {@link #seed} creates the scratch table <b>all VARCHAR</b>, which is
     * exactly the shape production's {@code read_csv columns={…VARCHAR…}} produces, so an expression
     * infers against the same input typing it will meet in a real batch. ⚠ It is <b>not</b> faithful
     * for the typed plugin-ingester path, where raw fields carry their declared types.
     *
     * <p>Works on an empty table — {@code DESCRIBE} reads the catalog, not the rows.
     */
    static List<Map<String, String>> columnTypes(Connection conn, String table) throws SQLException {
        List<Map<String, String>> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT column_name, column_type FROM (DESCRIBE " + q(table) + ")")) {
            while (rs.next())
                out.add(Map.of("name", rs.getString(1), "type", rs.getString(2)));
        }
        return out;
    }

    /** Quote a SQL identifier. */
    static String q(String ident) {
        return SqlIdent.q(ident);
    }

    /** A single-quoted SQL string literal (escaping embedded quotes). */
    static String sqlStr(String s) {
        return SqlIdent.sqlStr(s);
    }
}
