package com.gamma.query;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code MEASURE-SQL-BIND-1}: {@link MeasureCompiler#compile} binds every {@code filters} and {@code when}
 * operand as a JDBC parameter. Proved against the real sandbox: (1) the bound statement returns exactly what
 * the old literal rendering ({@link MeasureCompiler#render}) returns, across operators and column types; (2) a
 * value shaped like an injection arrives intact as data and never reaches the statement text.
 */
class MeasureCompilerBindTest {

    private static final String HOSTILE = "x' OR '1'='1'; DROP TABLE t; -- \"q\" /* c */";

    /** Mixed column types, with one row whose name IS the hostile value. */
    private static final String RELATION = """
            SELECT * FROM (VALUES
              ('alpha', 1, 1.5, 2.50::DECIMAL(9,2), TRUE,  TIMESTAMP '2026-06-01 10:00:00'),
              ('beta',  5, 5.5, 5.00::DECIMAL(9,2), FALSE, TIMESTAMP '2026-06-15 10:00:00'),
              ('gamma', 6, 6.0, 6.25::DECIMAL(9,2), TRUE,  TIMESTAMP '2026-07-01 10:00:00'),
              ('o''brien', 9, 9.9, 9.99::DECIMAL(9,2), FALSE, TIMESTAMP '2026-07-15 10:00:00'),
              ('x'' OR ''1''=''1''; DROP TABLE t; -- "q" /* c */', 3, 3.0, 3.00::DECIMAL(9,2), TRUE, TIMESTAMP '2026-08-01 00:00:00')
            ) AS v(name, n, d, dec, flag, ts)""";

    private static MeasureCompiler.Spec spec(List<Map<String, Object>> filters, Object when) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dataset", "t");
        body.put("groupBy", List.of("name"));
        body.put("orderBy", List.of(Map.of("field", "name", "dir", "asc")));
        body.put("filters", filters);
        if (when != null) body.put("when", when);
        return MeasureCompiler.parse(body, 100, 100);
    }

    private static Map<String, Object> f(String field, String op, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("field", field);
        m.put("op", op);
        if (value != null) m.put("value", value);
        return m;
    }

    private static Map<String, Object> leaf(String field, String op, Object value) {
        return Map.of("kind", "group", "op", "AND",
                "items", List.of(Map.of("kind", "condition", "field", field, "operator", op, "value", value)));
    }

    private static List<String> bound(MeasureCompiler.Spec s) throws Exception {
        MeasureCompiler.Compiled c = MeasureCompiler.compile(s);
        return names(QueryExecutor.run(new QueryExecutor.Request("t", RELATION, c.sql(), 100, 0,
                List.of(), List.of(), c.params())));
    }

    private static List<String> rendered(MeasureCompiler.Spec s) throws Exception {
        return names(QueryExecutor.run(new QueryExecutor.Request("t", RELATION, MeasureCompiler.render(s), 100, 0,
                List.of(), List.of())));
    }

    private static List<String> names(QueryExecutor.Result r) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> row : r.rows()) out.add(String.valueOf(row.get("name")));
        return out;
    }

    @Test
    void boundStatementReturnsWhatTheLiteralRenderingReturned() throws Exception {
        List<MeasureCompiler.Spec> cases = List.of(
                spec(List.of(f("name", "=", "beta")), null),
                spec(List.of(f("name", "!=", "o'brien")), null),
                spec(List.of(f("name", "like", "%a%")), null),
                spec(List.of(f("name", "in", List.of("alpha", "o'brien"))), null),
                spec(List.of(f("n", ">", 5)), null),
                spec(List.of(f("n", ">=", 5L)), null),
                spec(List.of(f("n", "<", 5.5)), null),          // a fraction against an INTEGER column: not rounded
                spec(List.of(f("n", "<=", 6.0)), null),
                spec(List.of(f("n", "in", List.of(1, 9))), null),
                spec(List.of(f("n", "=", "5")), null),          // a string against an INTEGER column
                spec(List.of(f("d", ">", 5)), null),
                spec(List.of(f("d", "<", 5.5)), null),
                spec(List.of(f("dec", ">=", 5)), null),
                spec(List.of(f("dec", "<", 6.25)), null),
                spec(List.of(f("flag", "=", true)), null),
                spec(List.of(f("flag", "!=", false)), null),
                spec(List.of(f("ts", ">=", "2026-07-01")), null),
                spec(List.of(f("ts", "<", "2026-06-15 10:00:00")), null),
                spec(List.of(f("name", "isNull", null)), null),
                spec(List.of(f("name", "notNull", null), f("n", ">", 2)), leaf("name", "contains", "a")),
                spec(List.of(), leaf("n", ">", "5")),
                spec(List.of(), leaf("name", "=", "o'brien")),
                spec(List.of(), leaf("ts", ">=", "2026-07-01")),
                spec(List.of(), leaf("name", "matches", "^(al|ga)")));
        for (MeasureCompiler.Spec s : cases)
            assertEquals(rendered(s), bound(s), () -> MeasureCompiler.render(s));
    }

    @Test
    void anInjectionShapedValueArrivesIntactAndIsNeverExecuted() throws Exception {
        List<MeasureCompiler.Spec> cases = List.of(
                spec(List.of(f("name", "=", HOSTILE)), null),
                spec(List.of(f("name", "in", List.of(HOSTILE, "nope"))), null),
                spec(List.of(f("name", "like", HOSTILE)), null),
                spec(List.of(), leaf("name", "=", HOSTILE)));
        for (MeasureCompiler.Spec s : cases) {
            MeasureCompiler.Compiled c = MeasureCompiler.compile(s);
            assertFalse(c.sql().contains("DROP") || c.sql().contains("--") || c.sql().contains("/*")
                    || c.sql().contains("1'='1"), () -> "the value reached the statement text: " + c.sql());
            assertTrue(c.params().contains(HOSTILE), () -> "the value must be bound verbatim: " + c.params());
            assertEquals(List.of(HOSTILE), bound(s), "exactly the one row whose cell IS the value — nothing more");
        }
        // The table the value names a DROP for is still there: a second read sees every row.
        assertEquals(5, bound(spec(List.of(), null)).size());
    }

    @Test
    void anIntegralNumberIsBoundAsBigintAndAFractionAsDouble() {
        MeasureCompiler.Compiled c = MeasureCompiler.compile(
                spec(List.of(f("n", ">", 5), f("d", "<", 5.5), f("flag", "=", true), f("name", "=", "b")), null));
        assertTrue(c.sql().contains("\"n\" > CAST(? AS BIGINT) AND \"d\" < CAST(? AS DOUBLE)"
                + " AND \"flag\" = CAST(? AS BOOLEAN) AND \"name\" = ?"), c.sql());
        assertEquals(List.of(5L, 5.5, true, "b"), c.params());
    }

    @Test
    void anUnboundableSiteRefusesASpecThatCarriesValues() {
        assertEquals(MeasureCompiler.render(spec(List.of(), null)), MeasureCompiler.compile(spec(List.of(), null)).unboundSql());
        assertThrows(IllegalStateException.class,
                () -> MeasureCompiler.compile(spec(List.of(f("name", "=", "a")), null)).unboundSql());
    }
}
