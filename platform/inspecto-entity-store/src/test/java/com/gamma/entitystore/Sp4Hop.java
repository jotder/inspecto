package com.gamma.entitystore;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SP4 (roadmap Data preparation) shared SQL: one traversal hop that drops Entity List members BEFORE they count toward
 * degree or budget. Used by {@link ListPruningSp4Test} (correctness, small) and {@link ListPruningSp4Bench} (cost, gated).
 *
 * <p>The hop: {@code frontier} nodes that are list members are dropped first (they spend nothing), then every edge
 * {@code src = frontier node} whose {@code dst} is a list member is dropped, and the rest are counted per {@code dst}
 * (degree); {@code sum(c)} is the budget the hop spends.
 */
final class Sp4Hop {

    private Sp4Hop() {}

    /** How the member set reaches the query. */
    enum Form {
        /** No pruning: the baseline hop. */
        NONE,
        /** {@code NOT IN ('a','b',...)} literal list. */
        IN_LIST,
        /** A raw table loaded from the sidecar rows (all columns), filtered at query time (list_id, match, expiry). */
        LIST_TABLE,
        /** The derived {@code list_member} shape: DISTINCT live key entries of the chosen lists, built once. */
        LIST_MEMBER,
        /** Straight over the sidecar Parquet (the SQL-facing form of D-M8), filtered at query time. */
        SIDECAR
    }

    static String liveKeys(String rel, String listIds) {
        return "SELECT entry FROM " + rel + " WHERE match = 'key' AND list_id IN (" + listIds
                + ") AND (expires_at IS NULL OR expires_at > now())";
    }

    /**
     * The hop as SQL. {@code edges} / {@code frontier} are relation expressions ({@code src,dst} / {@code k});
     * {@code memberRel} the relation the {@link Form} reads (table, view or {@code read_parquet(...)}); {@code listIds} a quoted,
     * comma-separated list of ids; {@code inLiterals} the quoted keys for {@link Form#IN_LIST}.
     */
    static String sql(Form form, String edges, String frontier, String memberRel, String listIds, String inLiterals) {
        String members = switch (form) {
            case NONE, IN_LIST -> null;
            case LIST_TABLE, SIDECAR -> liveKeys(memberRel, listIds);
            case LIST_MEMBER -> "SELECT entry FROM " + memberRel;
        };
        String pre = members == null ? "" : "WITH m AS MATERIALIZED (" + members + ") ";
        String frontierDrop = switch (form) {
            case NONE -> "SELECT k FROM " + frontier;
            case IN_LIST -> "SELECT k FROM " + frontier + " WHERE k NOT IN (" + inLiterals + ")";
            default -> "SELECT k FROM " + frontier + " f WHERE NOT EXISTS (SELECT 1 FROM m WHERE m.entry = f.k)";
        };
        String dstDrop = switch (form) {
            case NONE -> "";
            case IN_LIST -> " WHERE e.dst NOT IN (" + inLiterals + ")";
            default -> " WHERE NOT EXISTS (SELECT 1 FROM m WHERE m.entry = e.dst)";
        };
        return pre + "SELECT e.dst, count(*) c FROM " + edges + " e JOIN (" + frontierDrop + ") pf ON e.src = pf.k"
                + dstDrop + " GROUP BY e.dst";
    }

    /** The whole-relation degree count (no frontier): the worst case for the anti-join. */
    static String degreeSql(Form form, String edges, String memberRel, String listIds, String inLiterals) {
        return switch (form) {
            case NONE -> "SELECT e.dst, count(*) c FROM " + edges + " e GROUP BY e.dst";
            case IN_LIST -> "SELECT e.dst, count(*) c FROM " + edges + " e WHERE e.dst NOT IN (" + inLiterals + ") GROUP BY e.dst";
            default -> "WITH m AS MATERIALIZED (" + (form == Form.LIST_MEMBER ? "SELECT entry FROM " + memberRel
                    : liveKeys(memberRel, listIds)) + ") SELECT e.dst, count(*) c FROM " + edges
                    + " e WHERE NOT EXISTS (SELECT 1 FROM m WHERE m.entry = e.dst) GROUP BY e.dst";
        };
    }

    /** {@code dst -> degree} of a hop query (small results only). */
    static Map<String, Long> run(Connection c, String sql) throws SQLException {
        Map<String, Long> out = new LinkedHashMap<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql + " ORDER BY 1")) {
            while (rs.next()) out.put(rs.getString(1), rs.getLong(2));
        }
        return out;
    }

    /** {@code {groups, budget}} of a hop query without materialising it in Java (the bench path). */
    static long[] consume(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*), coalesce(sum(c), 0)::BIGINT FROM (" + sql + ")")) {
            rs.next();
            return new long[]{rs.getLong(1), rs.getLong(2)};
        }
    }
}
