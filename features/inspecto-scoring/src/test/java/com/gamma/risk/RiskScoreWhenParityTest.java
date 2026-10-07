package com.gamma.risk;

import com.gamma.mask.EvidenceMasker;
import com.gamma.query.DatasetRelation;
import com.gamma.query.MeasureCompiler;
import com.gamma.query.QueryExecutor;
import com.gamma.sql.SqlSandboxPolicy;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * STEP 5 parity corpus (MODULE-REORG-P7-KERNEL): a Risk factor's flat {@code filters} and the same predicate as a
 * {@code when} condition tree are run through DuckDB over one seeded Parquet table. Where WHERE semantics are meant
 * to match, entity ids, counts and Risk scores must be identical; the deliberate differences (typing by OPERAND
 * TEXT, '' read as null, LIKE escaping, comma-split {@code in}) are PINNED so nobody "fixes" one side to match
 * the other.
 */
class RiskScoreWhenParityTest {

    @TempDir static Path data;
    private static Function<String, String> rel;

    private static final EvidenceMasker NO_MASK = EvidenceMasker.of(
            new com.gamma.pipeline.ComponentStore(Path.of("does-not-exist", "registry")), Path.of("does-not-exist", "cfg"),
            List.of("d"));

    @BeforeAll
    static void seed() throws Exception {
        DuckDbUtil.loadDriver();
        Path dir = Files.createDirectories(data.resolve("ev"));
        String file = dir.resolve("data.parquet").toString().replace('\\', '/');
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("SET TimeZone='UTC'");
            st.execute("COPY (SELECT ent, status, pid, amt, CAST(d AS DATE) AS d, CAST(ts AS TIMESTAMPTZ) AS ts, note, tag FROM (VALUES "
                    + "('e1','FAILED','007','5','2026-07-01','2026-07-01 10:00:00+00','it''s','a,b'),"
                    + "('e1','FAILED','7','50','2026-07-02','2026-07-02 10:00:00+00','50%','c'),"
                    + "('e2',NULL,'07','500','2026-07-03','2026-07-03 10:00:00+00','500','a'),"
                    + "('e2','','x1','7','2026-07-04','2026-07-04 10:00:00+00','a\\b','b'),"
                    + "('e3','OK',NULL,'7','2026-07-05','2026-07-05 10:00:00+00','x;--','x;--'),"
                    + "('e4','FAILED','007','9','2026-07-06','2026-07-06 10:00:00+00','x'' OR ''1''=''1','c%'),"
                    + "('e5','ok','00','0','2026-07-07','2026-07-07 10:00:00+00','a_b','100')"
                    + ") AS v(ent, status, pid, amt, d, ts, note, tag)) TO '" + file + "' (FORMAT PARQUET)");
        }
        rel = id -> DatasetRelation.relationSql(Map.of("physicalRef", id), data, null);
    }

    private static Map<String, Object> flt(String field, String op, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("field", field);
        m.put("op", op);
        m.put("value", value);
        return m;
    }

    private static Map<String, Object> leaf(String field, String operator, String value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "condition");
        m.put("field", field);
        m.put("operator", operator);
        m.put("value", value);
        return m;
    }

    private static Map<String, Object> and(List<Map<String, Object>> items) {
        return Map.of("kind", "group", "op", "AND", "items", items);
    }

    private static Map<String, Object> one(String field, String operator, String value) {
        return and(List.of(leaf(field, operator, value)));
    }

    private static RiskScoreModel model(List<Map<String, Object>> filters, Map<String, Object> when) {
        Map<String, Object> f = new LinkedHashMap<>(Map.of("id", "f", "dataset", "ev", "key", "ent", "measure", "count", "weight", 3));
        if (filters != null) f.put("filters", filters);
        if (when != null) f.put("when", when);
        return RiskScoreModel.fromMap("m", Map.of("entityType", "subscriber", "highThreshold", 50, "factors", List.of(f)));
    }

    /** entity to count, run in UTC so TIMESTAMPTZ text is deterministic. */
    private static Map<String, Double> values(RiskScoreModel m) throws Exception {
        MeasureCompiler.Spec spec = m.factors().get(0).valueSpec(100);
        QueryExecutor.Result r = QueryExecutor.run(new QueryExecutor.Request("ev", rel.apply("ev"),
                MeasureCompiler.compile(spec), 100, 0, List.of(), List.of()), SqlSandboxPolicy.defaultPolicy(), ZoneId.of("UTC"));
        Map<String, Double> out = new TreeMap<>();
        for (Map<String, Object> row : r.rows())
            out.put(String.valueOf(row.get("ent")), ((Number) row.get(spec.measures().get(0).id())).doubleValue());
        return out;
    }

    private static Map<String, Double> scores(RiskScoreModel m) throws Exception {
        Map<String, Double> out = new TreeMap<>();
        RiskScoreEvaluator.evaluate(m, rel, NO_MASK).scored().forEach(s -> out.put(s.entityKey(), s.score()));
        return out;
    }

    private static void same(String what, List<Map<String, Object>> flat, Map<String, Object> when, Map<String, Double> expected)
            throws Exception {
        Map<String, Double> a = values(model(flat, null)), b = values(model(null, when));
        assertEquals(expected, a, what + ": flat filters");
        assertEquals(a, b, what + ": the same predicate as a when tree gives identical entity ids and counts");
        assertEquals(scores(model(flat, null)), scores(model(null, when)), what + ": identical Risk scores");
    }

    @Test
    void equalityOnTextAndNullsMatches() throws Exception {
        same("status = FAILED", List.of(flt("status", "=", "FAILED")), one("status", "=", "FAILED"), Map.of("e1", 2.0, "e4", 1.0));
        // NULL status never satisfies <>; '' does ('' <> 'FAILED') on both paths.
        same("status <> FAILED", List.of(flt("status", "!=", "FAILED")), one("status", "!=", "FAILED"),
                Map.of("e2", 1.0, "e3", 1.0, "e5", 1.0));
    }

    @Test
    void dateAndTimestampTzComparisonsMatch() throws Exception {
        same("DATE >=", List.of(flt("d", ">=", "2026-07-05")), one("d", ">=", "2026-07-05"),
                Map.of("e3", 1.0, "e4", 1.0, "e5", 1.0));
        same("TIMESTAMPTZ >= (session UTC)", List.of(flt("ts", ">=", "2026-07-05 00:00:00")),
                one("ts", ">=", "2026-07-05T00:00:00"), Map.of("e3", 1.0, "e4", 1.0, "e5", 1.0));
    }

    @Test
    void hostileTextValuesAreLiteralsOnBothPaths() throws Exception {
        same("quote in value", List.of(flt("note", "=", "x' OR '1'='1")), one("note", "=", "x' OR '1'='1"), Map.of("e4", 1.0));
        same("backslash", List.of(flt("note", "=", "a\\b")), one("note", "=", "a\\b"), Map.of("e2", 1.0));
        same("semicolon and comment", List.of(flt("tag", "=", "x;--")), one("tag", "=", "x;--"), Map.of("e3", 1.0));
        same("underscore is not a wildcard in =", List.of(flt("note", "=", "a_b")), one("note", "=", "a_b"), Map.of("e5", 1.0));
    }

    @Test
    void deliberateDifferenceTypingByOperandTextNotByColumn() throws Exception {
        // pid is zero-padded TEXT. Flat '007' is a string literal => exact text. A when operand '007' LOOKS numeric =>
        // TRY_CAST(pid AS DOUBLE) = 7.0 => '7', '07' and '007' all match. PINNED: do not "fix" either side.
        assertEquals(Map.of("e1", 1.0, "e4", 1.0), values(model(List.of(flt("pid", "=", "007")), null)));
        assertEquals(Map.of("e1", 2.0, "e2", 1.0, "e4", 1.0), values(model(null, one("pid", "=", "007"))));
    }

    @Test
    void deliberateDifferenceEmptyStringIsNullOnlyInWhen() throws Exception {
        // e2 has one true NULL status and one '' status.
        assertEquals(Map.of("e2", 1.0), values(model(List.of(flt("status", "isNull", null)), null)));
        assertEquals(Map.of("e2", 2.0), values(model(null, one("status", "isNull", ""))));
        assertEquals(Map.of("e1", 2.0, "e2", 1.0, "e3", 1.0, "e4", 1.0, "e5", 1.0),
                values(model(List.of(flt("status", "notNull", null)), null)));
        assertEquals(Map.of("e1", 2.0, "e3", 1.0, "e4", 1.0, "e5", 1.0), values(model(null, one("status", "isNotNull", ""))));
    }

    @Test
    void deliberateDifferenceLikeEscapingAndCommaIn() throws Exception {
        // flat like passes the author's pattern verbatim (% and _ are wildcards); when contains escapes them.
        assertEquals(Map.of("e1", 1.0, "e2", 1.0), values(model(List.of(flt("note", "like", "50%")), null)),
                "flat like: % is a wildcard so '50%' matches '50%' and '500'");
        assertEquals(Map.of("e1", 1.0), values(model(null, one("note", "contains", "50%"))), "when contains: % is a literal");
        // flat in takes a list so a value may hold a comma; when in splits one string on commas.
        assertEquals(Map.of("e1", 1.0), values(model(List.of(flt("tag", "in", List.of("a,b"))), null)));
        assertEquals(Map.of("e2", 2.0), values(model(null, one("tag", "in", "a,b"))),
                "when in 'a,b' means the two values a and b, never the single value 'a,b'");
    }

    @Test
    void forEntityStillAppendsTheFlatKeyFilterAndKeepsWhen() throws Exception {
        RiskScoreModel m = model(List.of(), one("status", "=", "FAILED"));
        assertEquals(Map.of("e1", 2.0), values(m.forEntity("e1")));
        assertNotNull(m.forEntity("e1").factors().get(0).when());
        assertEquals(Map.of("e4", 1.0), values(m.forEntity("e4")));
        assertTrue(values(m.forEntity("e2")).isEmpty(), "e2 has no FAILED row: the when tree still narrows under the entity filter");
    }

    @Test
    void referencedColumnsWalkTheTreeIncludingNestedGroupsAndValueField() {
        Map<String, Object> valueField = new LinkedHashMap<>(Map.of("kind", "condition", "field", "amt", "operator", "=", "valueField", "pid"));
        Map<String, Object> inner = Map.of("kind", "group", "op", "OR", "items", List.of(leaf("tag", "=", "a"), valueField));
        RiskScoreModel m = model(List.of(flt("status", "=", "FAILED")), and(List.of(leaf("note", "=", "x"), inner)));
        assertEquals(Set.of("ent", "status", "note", "tag", "amt", "pid"), m.referencedColumns().get("ev"));
    }

    @Test
    void invalidWhenIsRefusedAtSave() {
        assertThrows(IllegalArgumentException.class, () -> model(null, leaf("status", "=", "x")),
                "a bare leaf root would fail open: refused");
        assertThrows(IllegalArgumentException.class, () -> RiskScoreModel.fromMap("m", Map.of("entityType", "subscriber",
                "highThreshold", 50, "factors", List.of(Map.of("id", "f", "dataset", "ev", "key", "ent", "measure", "count",
                        "weight", 1, "when", "status = FAILED")))));
        assertThrows(IllegalArgumentException.class, () -> model(null, one("status", "matches", "(?=x)")),
                "a lookahead regex is refused by ConditionTree.validate");
    }

    @Test
    void hostileColumnNamesAreQuotedNotInterpreted() throws Exception {
        String evil = "status\"; DROP TABLE x; --";
        RiskScoreModel m = model(null, one(evil, "=", "FAILED"));
        assertThrows(Exception.class, () -> values(m), "no such column: a binder error, never an injection");
        assertEquals(Map.of("e1", 2.0, "e4", 1.0), values(model(null, one("status", "=", "FAILED"))), "the corpus is intact afterwards");
        // The flat path is stricter still: an unsafe identifier is refused at parse.
        assertThrows(IllegalArgumentException.class, () -> model(List.of(flt(evil, "=", "FAILED")), null));
    }
}
