package com.gamma.query;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Condition Language extensions (Decision Kernel step 2): {@code negate} groups, field-to-field
 * ({@code valueField}), {@code ignoreCase}, {@code matches}. Every feature is proved through BOTH backends —
 * {@link ConditionTree} in memory and {@link ConditionSql} executed by DuckDB — and must select the same
 * row ids. Hostile inputs prove the SQL stays a single quoted predicate (DECISION-RULE-SQL-GUARD-1).
 */
class ConditionExtensionsTest {

    private static Connection conn;
    private static File db;

    private static final String ODD = "q\"x"; // a column whose NAME contains a double quote

    private static final List<Map<String, Object>> ROWS = new ArrayList<>();

    private static void add(int id, String a, String b, Integer n1, Integer n2, String d1, String d2, String tag) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id);
        r.put("a", a);
        r.put("b", b);
        r.put("n1", n1);
        r.put("n2", n2);
        r.put("d1", d1);
        r.put("d2", d2);
        r.put("tag", tag);
        r.put(ODD, b);
        ROWS.add(r);
    }

    static {
        add(1, "Alpha", "alpha", 5, 5, "2026-07-01", "2026-07-01", "x-1");
        add(2, "Beta", "Gamma", 10, 3, "2026-07-02", "2026-07-05", "y-2");
        add(3, "Gamma", "gam", 2, 9, "2026-07-09", "2026-07-03", null);
        add(4, null, "z", null, 4, null, "2026-01-01", "x-3");
        add(5, "", "", 7, 7, "2026-07-01T10:00:00", "2026-07-01T10:00:00", "x-77");
    }

    private static String sqlVal(Object v) {
        if (v == null) return "NULL";
        if (v instanceof Integer) return v.toString();
        return "'" + v.toString().replace("'", "''") + "'";
    }

    @BeforeAll
    static void openDb() throws Exception {
        DuckDbUtil.loadDriver();
        db = DuckDbUtil.tempDbFile("condition_ext_");
        conn = DuckDbUtil.openConnection(db);
        try (Statement st = conn.createStatement()) {
            StringBuilder sql = new StringBuilder("CREATE TABLE t AS SELECT * FROM (VALUES ");
            for (int i = 0; i < ROWS.size(); i++) {
                sql.append(i > 0 ? ", (" : "(");
                int k = 0;
                for (Object v : ROWS.get(i).values()) sql.append(k++ > 0 ? ", " : "").append(sqlVal(v));
                sql.append(")");
            }
            sql.append(") v(id, a, b, n1, n2, d1, d2, tag, \"q\"\"x\")");
            st.execute(sql.toString());
        }
    }

    @AfterAll
    static void closeDb() throws Exception {
        if (conn != null) conn.close();
        if (db != null) DuckDbUtil.deleteTempDb(db);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static Map<String, Object> cond(String field, String operator, String value) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("kind", "condition");
        c.put("field", field);
        c.put("operator", operator);
        if (value != null) c.put("value", value);
        return c;
    }

    private static Map<String, Object> with(Map<String, Object> c, String key, Object v) {
        c.put(key, v);
        return c;
    }

    private static Map<String, Object> ff(String field, String operator, String valueField) {
        return with(cond(field, operator, null), "valueField", valueField);
    }

    private static Map<String, Object> ic(Map<String, Object> c) {
        return with(c, "ignoreCase", true);
    }

    private static Map<String, Object> group(String op, Object... items) {
        return new LinkedHashMap<>(Map.of("kind", "group", "op", op, "items", List.of(items)));
    }

    private static Map<String, Object> not(Map<String, Object> g) {
        g.put("negate", true);
        return g;
    }

    private static List<Object> memoryIds(Object when) {
        List<Object> ids = new ArrayList<>();
        for (Map<String, Object> r : ConditionTree.filter(when, ROWS)) ids.add(r.get("id"));
        return ids;
    }

    private static List<Object> sqlIds(Object when) throws SQLException {
        List<Object> ids = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM t WHERE " + ConditionSql.predicate(when) + " ORDER BY id")) {
            while (rs.next()) ids.add(rs.getInt(1));
        }
        return ids;
    }

    /** Both backends select exactly {@code expected} ids. */
    private static void parity(Object when, Integer... expected) throws SQLException {
        List<Object> want = List.of((Object[]) expected);
        assertEquals(want, memoryIds(when), "ConditionTree ids");
        assertEquals(want, sqlIds(when), "SQL ids for: " + ConditionSql.predicate(when));
    }

    private static long rowCount() throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM t")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // ── not ─────────────────────────────────────────────────────────────────────

    @Test
    void negatedGroupInvertsItsResult() throws Exception {
        parity(group("AND", cond("n1", ">", "3")), 1, 2, 5);
        // NOT (n1 > 3): the NULL-n1 row (4) is "not > 3", so it IS selected — in SQL via COALESCE
        parity(group("AND", not(group("AND", cond("n1", ">", "3")))), 3, 4);
    }

    @Test
    void negatedOrIsNor() throws Exception {
        parity(not(group("OR", cond("a", "=", "Alpha"), cond("n2", ">", "8"))), 2, 4, 5);
    }

    @Test
    void negationNests() throws Exception {
        Object inner = not(group("AND", cond("a", "=", "Alpha")));
        parity(group("AND", not(group("OR", inner))), 1);   // NOT NOT (a = Alpha)
    }

    @Test
    void emptyNegatedGroupStillImposesNoConstraint() throws Exception {
        parity(not(group("AND")), 1, 2, 3, 4, 5);
        parity(group("AND", not(group("AND", cond("a", "=", "")))), 1, 2, 3, 4, 5); // incomplete leaf ⇒ no constraint
    }

    @Test
    void negateFalseAndAbsentAreIdentical() throws Exception {
        Map<String, Object> plain = group("AND", cond("n1", ">", "3"));
        Map<String, Object> off = group("AND", cond("n1", ">", "3"));
        off.put("negate", false);
        assertEquals(memoryIds(plain), memoryIds(off));
        assertEquals(ConditionSql.predicate(plain), ConditionSql.predicate(off));
    }

    // ── field-to-field ──────────────────────────────────────────────────────────

    @Test
    void fieldToFieldNumbersCompareAsNumbers() throws Exception {
        parity(group("AND", ff("n1", ">", "n2")), 2);           // 10 > 3 (as text '10' < '3')
        parity(group("AND", ff("n1", "=", "n2")), 1, 5);
        parity(group("AND", ff("n1", "<=", "n2")), 1, 3, 5);
        parity(group("AND", ff("n1", "!=", "n2")), 2, 3);       // NULL n1 (row 4) never matches
    }

    @Test
    void fieldToFieldDatesCompareAsInstants() throws Exception {
        parity(group("AND", ff("d1", "<", "d2")), 2);
        parity(group("AND", ff("d1", "=", "d2")), 1, 5);        // date vs datetime of the same instant, per row
    }

    @Test
    void fieldToFieldStringsAreCaseSensitiveUnlessIgnoreCase() throws Exception {
        parity(group("AND", ff("a", "=", "b")), 5);
        parity(group("AND", ic(ff("a", "=", "b"))), 1, 5);
        parity(group("AND", ff("a", "<", "b")), 1, 2, 3);
    }

    @Test
    void fieldToFieldSubstringOps() throws Exception {
        parity(group("AND", ff("a", "contains", "b")), 1, 3, 5);
        parity(group("AND", ff("a", "startsWith", "b")), 1, 3, 5);
        parity(group("AND", ff("a", "endsWith", "b")), 1, 5);
    }

    @Test
    void fieldToFieldReadsAColumnWhoseNameHoldsAQuote() throws Exception {
        parity(group("AND", ff("b", "=", ODD)), 1, 2, 3, 4, 5);
        assertTrue(ConditionSql.predicate(group("AND", ff("b", "=", ODD))).contains("\"q\"\"x\""));
    }

    @Test
    void hostileValueFieldStaysAQuotedIdentifier() throws Exception {
        String evil = "a\" OR \"1\"=\"1";
        Object when = group("AND", ff("n1", "=", evil));
        String pred = ConditionSql.predicate(when);
        assertTrue(pred.contains("\"a\"\" OR \"\"1\"\"=\"\"1\""), pred);
        // it names a column that does not exist ⇒ DuckDB refuses; it never widens to a tautology
        assertThrows(SQLException.class, () -> sqlIds(when));
        assertEquals(List.of(), memoryIds(when));
        // semicolon / comment in the name: same
        assertThrows(SQLException.class, () -> sqlIds(group("AND", ff("n1", "=", "x\"; DROP TABLE t; --"))));
        assertEquals(5, rowCount());
    }

    // ── ignoreCase ──────────────────────────────────────────────────────────────

    @Test
    void ignoreCaseEqualityNotEqualityAndIn() throws Exception {
        parity(group("AND", cond("a", "=", "ALPHA")));
        parity(group("AND", ic(cond("a", "=", "ALPHA"))), 1);
        parity(group("AND", ic(cond("a", "!=", "alpha"))), 2, 3, 5);
        parity(group("AND", ic(cond("a", "in", "ALPHA, beta"))), 1, 2);
        parity(group("AND", cond("a", "in", "ALPHA, beta")));
    }

    @Test
    void ignoreCaseOnSubstringOpsIsAlreadyTheDefault() throws Exception {
        parity(group("AND", ic(cond("a", "contains", "ALP"))), 1);
        parity(group("AND", cond("a", "contains", "ALP")), 1);
    }

    @Test
    void ignoreCaseDoesNotChangeANumericCompare() throws Exception {
        parity(group("AND", ic(cond("n1", "=", "5"))), 1);
    }

    @Test
    void hostileLiteralsUnderIgnoreCase() throws Exception {
        for (String evil : new String[]{"O'Brien", "'; DROP TABLE t; --", "x' OR '1'='1", "a\\b", "100%", "a_c", "/* c */ -- x"}) {
            parity(group("AND", ic(cond("a", "=", evil))));
            parity(group("AND", ic(cond("a", "in", evil + ", " + evil.toUpperCase()))));
            parity(group("AND", ic(cond("a", "!=", evil))), 1, 2, 3, 5);
        }
        assertEquals(5, rowCount());
    }

    // ── matches ─────────────────────────────────────────────────────────────────

    @Test
    void matchesIsAPartialMatch() throws Exception {
        parity(group("AND", cond("a", "matches", "mm")), 3);              // not anchored
        parity(group("AND", cond("a", "matches", "^[A-Z]l")), 1);
        parity(group("AND", cond("tag", "matches", "^x-\\d+$")), 1, 4, 5);
        parity(group("AND", cond("n1", "matches", "^1")), 2);            // a number is matched as its text
    }

    @Test
    void matchesIgnoreCaseAndNegation() throws Exception {
        parity(group("AND", cond("a", "matches", "^alpha$")));
        parity(group("AND", ic(cond("a", "matches", "^alpha$"))), 1);
        parity(group("AND", not(group("AND", cond("tag", "matches", "^x-")))), 2, 3);
    }

    @Test
    void hostilePatternsStayInsideTheirQuotes() throws Exception {
        for (String evil : new String[]{"'; DROP TABLE t; --", "it's", "x' OR '1'='1", "\\\\", "/* c */ -- x", "a\\'b"}) {
            Object when = group("AND", cond("a", "matches", evil));
            assertEquals(memoryIds(when), sqlIds(when), evil);
            assertTrue(ConditionSql.predicate(when).startsWith("(regexp_matches(CAST(\"a\" AS VARCHAR), '"), evil);
        }
        assertEquals(5, rowCount());
    }

    @Test
    void matchesRejectsBadPatternsWithAClearError() {
        assertMsg("invalid regular expression", group("AND", cond("a", "matches", "([")));
        assertMsg("invalid regular expression", group("AND", cond("a", "matches", "a\\")));
        assertMsg("longer than " + ConditionTree.MAX_PATTERN_LENGTH, group("AND", cond("a", "matches", "a".repeat(ConditionTree.MAX_PATTERN_LENGTH + 1))));
        assertMsg("RE2", group("AND", cond("a", "matches", "(?=a)b")));
        assertMsg("RE2", group("AND", cond("a", "matches", "(a)\\1")));
        assertMsg("RE2", group("AND", cond("a", "matches", "a*+")));
        // at the cap is fine
        ConditionTree.requireGroupRoot(group("AND", cond("a", "matches", "a".repeat(ConditionTree.MAX_PATTERN_LENGTH))));
        // a nested bad pattern is found too
        assertMsg("invalid regular expression", group("AND", group("OR", cond("a", "matches", "("))));
        // both backends refuse (neither evaluates a bad tree)
        assertThrows(IllegalArgumentException.class, () -> ConditionSql.predicate(group("AND", cond("a", "matches", "(["))));
        assertThrows(IllegalArgumentException.class, () -> ConditionTree.filter(group("AND", cond("a", "matches", "([")), ROWS));
    }

    // ── shape validation ────────────────────────────────────────────────────────

    @Test
    void extensionKeysOnUnsupportedOperatorsAreRefused() {
        assertMsg("valueField is only valid", group("AND", ff("n1", "in", "n2")));
        assertMsg("valueField is only valid", group("AND", ff("n1", "between", "n2")));
        assertMsg("valueField is only valid", group("AND", ff("n1", "matches", "n2")));
        assertMsg("not both", group("AND", with(ff("n1", ">", "n2"), "value", "3")));
        assertMsg("ignoreCase is only valid", group("AND", ic(cond("n1", "<", "3"))));
        assertMsg("ignoreCase is only valid", group("AND", ic(cond("n1", "isNull", null))));
    }

    private static void assertMsg(String fragment, Object tree) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ConditionTree.requireGroupRoot(tree));
        assertTrue(e.getMessage().contains(fragment), e.getMessage());
    }

    // ── '' is null for isNull (settled, pinned) ──────────────────────────────────

    @Test
    void emptyStringCountsAsNullForIsNullInBothBackends() throws Exception {
        // row 4 is NULL, row 5 is ''. The tree treats them alike; the inspecto-util Conditions text notation
        // does not — it distinguishes them. Deliberate, documented in the Decision Kernel step 2 notes.
        parity(group("AND", cond("a", "isNull", null)), 4, 5);
        parity(group("AND", cond("a", "isNotNull", null)), 1, 2, 3);
        assertFalse(memoryIds(group("AND", cond("a", "isNull", null))).isEmpty());
    }
}
