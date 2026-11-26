package com.gamma.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.util.JdbcDrivers;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EXPRESSION-GUARD-ESTRING-1: {@link ExpressionGuard} must lex a fragment the way DuckDB 2.0.1.0 does, or text the
 * guard took for a string literal runs as SQL once a caller splices the fragment ({@code WHERE (<filter>)},
 * {@code (<expr>) AS "col"}). Checked against the real DuckDB parser, in memory, with nothing persisted.
 */
class ExpressionGuardDuckDbTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** DuckDB reads {@code E'\'''} as an escape string still open after {@code \'} and {@code ''}; the guard used to
     *  read it as one closed literal followed by {@code || '<more literal>'}. */
    static final String ESTRING_PAYLOAD = "E'\\''' || ') IS NOT NULL UNION ALL SELECT secret FROM s --'";

    @Test
    void anEscapeStringPayloadThatRunsSmuggledSqlIsRefused() throws Exception {
        // DuckDB's own reading, spliced exactly as ReconService.whereFilter splices a side filter: the SELECT the
        // guard denies by keyword runs, and the secret of another table comes back as a row of t
        try (Connection conn = JdbcDrivers.connect("jdbc:duckdb:"); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE t(v VARCHAR)");
            st.execute("CREATE TABLE s(secret VARCHAR)");
            st.execute("INSERT INTO s VALUES ('top-secret')");
            List<String> got = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SELECT v FROM t WHERE (" + ESTRING_PAYLOAD + ") LIMIT 10")) {
                while (rs.next()) got.add(rs.getString(1));
            }
            assertEquals(List.of("top-secret"), got, "DuckDB runs the text after the escape string as SQL");
        }
        assertThrows(IllegalArgumentException.class, () -> ExpressionGuard.check(ESTRING_PAYLOAD));
    }

    /**
     * The differential: every fragment the guard accepts, spliced as {@code SELECT (<expr>) AS v, 4242 AS sentinel
     * FROM t}, must parse (if DuckDB parses it at all) as exactly that one SELECT — two select items, the sentinel
     * intact, one base table, no subquery, no set operation. A literal the guard and DuckDB read with different
     * extents shows up as a statement of another shape. The corpus crosses every literal prefix DuckDB knows with
     * escape-sensitive bodies and smuggling tails, plus seeded random fragments.
     */
    @Test
    void everyAcceptedFragmentParsesAsTheOneSelectItWasSplicedInto() throws Exception {
        List<String> corpus = new ArrayList<>();
        String[] prefixes = {"", "E", "e", "X", "x", "B", "b", "N", "n", "1e", "1E", "a", "amount ", "U&", "r", "date "};
        String[] bodies = {"'\\'''", "'\\''", "'a\\'", "'\\\\'", "''''", "'$$'", "'\\n'", "'it''s'", "'\\d+'"};
        String[] tails = {"", " || ') OR 1 = 1 --'", " || ') IS NOT NULL UNION ALL SELECT 1 --'", " = 'x'",
                "\n'y'", " || '\\' || '", " || ' ) , 1 AS sentinel FROM t --'"};
        for (String p : prefixes) for (String b : bodies) for (String t : tails) corpus.add(p + b + t);
        String[] frags = {"E'", "e'", "'", "\\'", "''", "\\", " ", "||", "--", "x", "1", "e", "$$", "\"", ")", "(",
                "\n", " OR ", "/*", "*/", "'a'", "N'", "X'", ",", "=", "*"};
        Random rnd = new Random(20261126L);
        for (int i = 0; i < 20_000; i++) {
            StringBuilder sb = new StringBuilder();
            for (int n = 1 + rnd.nextInt(8); n > 0; n--) sb.append(frags[rnd.nextInt(frags.length)]);
            corpus.add(sb.toString());
        }

        int accepted = 0, parsed = 0;
        try (Connection conn = JdbcDrivers.connect("jdbc:duckdb:");
             PreparedStatement ps = conn.prepareStatement("SELECT json_serialize_sql(?::VARCHAR)")) {
            for (String expr : corpus) {
                String checked;
                try {
                    checked = ExpressionGuard.check(expr);
                } catch (IllegalArgumentException refused) {
                    continue;
                }
                accepted++;
                ps.setString(1, "SELECT (" + checked + ") AS v, 4242 AS sentinel FROM t");
                JsonNode tree;
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    tree = JSON.readTree(rs.getString(1));
                }
                if (tree.path("error").asBoolean(false)) continue;   // DuckDB refuses it: fails closed, not smuggled
                parsed++;
                assertOneSelectWithSentinel(expr, tree);
            }
        }
        assertTrue(accepted > 100 && parsed > 50, "the corpus must exercise the accept path: accepted " + accepted
                + ", parsed " + parsed);
    }

    private static void assertOneSelectWithSentinel(String expr, JsonNode tree) {
        String why = "guard-accepted " + JSON.valueToTree(expr) + " changed the statement: " + tree;
        JsonNode stmts = tree.path("statements");
        assertEquals(1, stmts.size(), why);
        JsonNode node = stmts.get(0).path("node");
        assertEquals("SELECT_NODE", node.path("type").asText(), why);
        JsonNode items = node.path("select_list");
        assertEquals(2, items.size(), why);
        assertEquals("v", items.get(0).path("alias").asText(), why);
        assertEquals("sentinel", items.get(1).path("alias").asText(), why);
        assertEquals("BASE_TABLE", node.path("from_table").path("type").asText(), why);
        assertEquals("t", node.path("from_table").path("table_name").asText(), why);
        assertTrue(node.path("where_clause").isMissingNode() || node.path("where_clause").isNull(), why);
        assertFalse(tree.toString().contains("\"SUBQUERY\""), why);
    }
}
