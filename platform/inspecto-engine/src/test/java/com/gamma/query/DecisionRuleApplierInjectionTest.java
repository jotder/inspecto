package com.gamma.query;

import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.DecisionRules;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code DECISION-RULE-SQL-GUARD-1}: tries to break out of the write statements
 * {@link DecisionRuleApplier} builds ({@code DELETE} / {@code UPDATE} / {@code CREATE TABLE AS} /
 * {@code COPY}) through everything a rule author controls — operand values, field names, the tag value,
 * the rule name and the quarantine path. Each payload row is ALSO present in the data, so a payload that
 * stays a literal matches exactly that one row; a payload that escaped would match more, fewer, or drop
 * the {@code canary} table. Finding (2026-10-09, before the fix): nothing escaped — the quote-doubling
 * held — so the fix is defence in depth: operand values are now bound parameters and never reach the
 * statement text ({@link #noRuleValueReachesTheStatementText}).
 */
class DecisionRuleApplierInjectionTest {

    private static final List<String> PAYLOADS = List.of(
            "x' OR '1'='1",
            "'; DROP TABLE canary; --",
            "x'/* c */ OR TRUE --",
            "x\\'); DROP TABLE canary; --",
            "$$; DROP TABLE canary; $$",
            "x\" OR \"1\"=\"1");

    @AfterEach
    void clearRegistry() {
        DecisionRules.clear();
    }

    // ── harness ─────────────────────────────────────────────────────────────────

    private record Run(Connection conn, File db) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            conn.close();
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** Table {@code t}: one row per payload (name = payload) plus two ordinary rows; and a {@code canary}. */
    private Run open() throws Exception {
        DuckDbUtil.loadDriver();
        File db = DuckDbUtil.tempDbFile("dr_inject_");
        Connection conn = DuckDbUtil.openConnection(db);
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE t (name VARCHAR, cost DOUBLE)");
            st.execute("INSERT INTO t VALUES ('alice', 250.0), ('bob', 50.0)");
            st.execute("CREATE TABLE canary AS SELECT 42 AS v");
        }
        try (var ps = conn.prepareStatement("INSERT INTO t VALUES (?, 1.0)")) {
            for (String p : PAYLOADS) {
                ps.setString(1, p);
                ps.executeUpdate();
            }
        }
        return new Run(conn, db);
    }

    private void rule(Path dir, String name, Map<String, Object> when, List<Map<String, Object>> consequences)
            throws Exception {
        // a fresh registry per rule: ComponentStore persists, so a shared one would accumulate rules
        Path registry = Files.createTempDirectory(dir, "cfg").resolve("registry");   // read as <config>/registry
        new ComponentStore(registry).write("decision-rule", name, Map.of(
                "name", name, "targetType", "job", "target", "j1", "when", when,
                "priority", 10, "enabled", true, "consequences", consequences));
        DecisionRules.register("default", registry);
    }

    private static Map<String, Object> leaf(String field, String operator, String value) {
        return Map.of("kind", "group", "op", "AND", "items", List.of(
                Map.of("kind", "condition", "field", field, "operator", operator, "value", value)));
    }

    private static final List<Map<String, Object>> DROP = List.of(Map.of("action", "drop", "destination", ""));

    private static DecisionRuleApplier.Result apply(Connection conn, Path dir) {
        return DecisionRuleApplier.apply(conn, "t", DecisionRuleApplier.Subject.job("j1", "r1"),
                dir.resolve("quarantine").toString(), "base", (c, routed, dest) -> DecisionRuleApplier.Result.NONE);
    }

    private static long count(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void assertCanaryAlive(Connection conn) throws Exception {
        assertEquals(42, count(conn, "SELECT v FROM canary"), "canary table survived");
    }

    // ── operand values ──────────────────────────────────────────────────────────

    @Test
    void equalsPayloadDropsExactlyItsOwnRow(@TempDir Path dir) throws Exception {
        for (String p : PAYLOADS) {
            DecisionRules.clear();
            rule(dir, "eq", leaf("name", "=", p), DROP);
            try (Run r = open()) {
                apply(r.conn(), dir);
                assertEquals(PAYLOADS.size() + 1, count(r.conn(), "SELECT COUNT(*) FROM t"), "only the row named " + p);
                assertCanaryAlive(r.conn());
            }
        }
    }

    @Test
    void substringAndRegexAndInPayloadsStayLiterals(@TempDir Path dir) throws Exception {
        for (String op : List.of("contains", "startsWith", "endsWith", "in")) {
            for (String p : PAYLOADS) {
                if (op.equals("in") && p.contains(",")) continue;
                DecisionRules.clear();
                rule(dir, "op", leaf("name", op, p), DROP);
                try (Run r = open()) {
                    apply(r.conn(), dir);
                    long left = count(r.conn(), "SELECT COUNT(*) FROM t");
                    assertTrue(left >= 2 && left <= PAYLOADS.size() + 1, op + " " + p + " left " + left);
                    assertEquals(2, count(r.conn(), "SELECT COUNT(*) FROM t WHERE name IN ('alice','bob')"),
                            op + " " + p + " touched an ordinary row");
                    assertCanaryAlive(r.conn());
                }
            }
        }
        DecisionRules.clear();
        rule(dir, "rx", leaf("name", "matches", "^x' OR '1'='1$"), DROP);
        try (Run r = open()) {
            apply(r.conn(), dir);
            assertEquals(PAYLOADS.size() + 1, count(r.conn(), "SELECT COUNT(*) FROM t"));
            assertCanaryAlive(r.conn());
        }
    }

    @Test
    void numericLookingPayloadsDoNotRenderAsSqlTokens(@TempDir Path dir) throws Exception {
        // Double.parseDouble accepts these; rendered inline they became the bare words NaN / Infinity
        // (a DuckDB column reference: binder error, the rule silently skipped).
        rule(dir, "inf", leaf("cost", "<", "Infinity"), DROP);
        try (Run r = open()) {
            apply(r.conn(), dir);
            assertEquals(0, count(r.conn(), "SELECT COUNT(*) FROM t"), "every finite cost < Infinity");
            assertCanaryAlive(r.conn());
        }
    }

    // ── identifiers ─────────────────────────────────────────────────────────────

    @Test
    void hostileFieldNamesNeverEscapeTheQuotedIdentifier(@TempDir Path dir) throws Exception {
        List<String> fields = List.of(
                "name\" = \"name",
                "name\"; DROP TABLE canary; --",
                "name\" OR 1=1 --",
                "na\u0000me",
                "name\n; DROP TABLE canary");
        for (String f : fields) {
            DecisionRules.clear();
            rule(dir, "f", leaf(f, "=", "alice"), DROP);
            try (Run r = open()) {
                apply(r.conn(), dir);   // unknown column: the rule fails and is skipped, never throws
                assertEquals(PAYLOADS.size() + 2, count(r.conn(), "SELECT COUNT(*) FROM t"), "field " + f);
                assertCanaryAlive(r.conn());
            }
        }
    }

    // ── tag value, rule name, quarantine path ───────────────────────────────────

    @Test
    void tagValueIsStoredVerbatim(@TempDir Path dir) throws Exception {
        String tag = "t'); DROP TABLE canary; --";
        rule(dir, "tg", leaf("name", "=", "alice"), List.of(Map.of("action", "tag", "destination", tag)));
        try (Run r = open()) {
            apply(r.conn(), dir);
            try (var ps = r.conn().prepareStatement("SELECT COUNT(*) FROM t WHERE __tags = ?")) {
                ps.setString(1, tag);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertEquals(1, rs.getLong(1));
                }
            }
            assertCanaryAlive(r.conn());
        }
    }

    @Test
    void quarantineCopyLandsInsideItsDirectoryWhateverTheNames(@TempDir Path dir) throws Exception {
        Path root = dir.resolve("q'uote");
        String ruleName = "../../x'; DROP TABLE canary; --";
        new ComponentStore(dir.resolve("registry")).write("decision-rule", "qr", Map.of(
                "name", ruleName, "targetType", "job", "target", "j1", "when", leaf("name", "=", PAYLOADS.get(0)),
                "priority", 10, "enabled", true,
                "consequences", List.of(Map.of("action", "quarantine", "destination", ""))));
        DecisionRules.register("default", dir.resolve("registry"));
        try (Run r = open()) {
            DecisionRuleApplier.apply(r.conn(), "t", DecisionRuleApplier.Subject.job("j1", "r1"),
                    root.toString(), "b'ase", (c, routed, dest) -> DecisionRuleApplier.Result.NONE);
            List<Path> files;
            try (Stream<Path> s = Files.walk(dir)) {
                files = new ArrayList<>(s.filter(p -> p.toString().endsWith(".parquet")).toList());
            }
            assertEquals(1, files.size(), "one quarantine file: " + files);
            assertTrue(files.get(0).startsWith(root.resolve("records")), files.get(0) + " under " + root);
            assertEquals(PAYLOADS.size() + 1, count(r.conn(), "SELECT COUNT(*) FROM t"));
            assertCanaryAlive(r.conn());
        }
    }

    // ── the fix: values are bound, never statement text ─────────────────────────


    // ── the fix: values are bound, never statement text ─────────────────────────

    @Test
    void noRuleValueReachesTheStatementText() {
        for (String p : PAYLOADS) {
            for (String op : List.of("=", "!=", "contains", "startsWith", "endsWith", "in", "<")) {
                ConditionSql.Bound b = ConditionSql.predicateBound(leaf("name", op, p));
                assertFalse(b.sql().contains("'1'") || b.sql().contains("DROP") || b.sql().contains("$$"),
                        op + " " + p + " rendered " + b.sql());
                assertFalse(b.params().isEmpty(), op + " " + p + " bound nothing");
            }
        }
        ConditionSql.Bound rx = ConditionSql.predicateBound(leaf("name", "matches", "^x' OR '1'='1$"));
        assertFalse(rx.sql().contains("'1'"), rx.sql());
        assertEquals(List.of("^x' OR '1'='1$"), rx.params());
        ConditionSql.Bound n = ConditionSql.predicateBound(leaf("cost", ">", "500"));
        assertFalse(n.sql().contains("500"), n.sql());
        assertEquals(List.of(500.0), n.params());
    }
}
