package com.gamma.query;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bound-parameter path through {@link QueryExecutor} — the half of Rule Template execution that
 * {@link RuleTemplateTest} cannot prove, because compiling {@code :name} → {@code ?} is only useful if the
 * driver actually binds it. Runs real SQL in the sandbox with no dataset relation, so these tests exercise
 * the {@link java.sql.PreparedStatement} branch and nothing else.
 *
 * <p>Also pins the property the whole design rests on: a bind value is data, never statement text.
 */
class QueryExecutorBindsTest {

    private static QueryExecutor.Result run(String sql, List<String> binds) throws Exception {
        return QueryExecutor.run(new QueryExecutor.Request(null, null, sql, 100, 0, List.of(), List.of(), binds));
    }

    @Test
    void bindsAreSetPositionally() throws Exception {
        QueryExecutor.Result r = run("SELECT ? AS a, ? AS b", List.of("first", "second"));

        assertEquals(1, r.rowCount());
        Map<String, Object> row = r.rows().get(0);
        assertEquals("first", String.valueOf(row.get("a")));
        assertEquals("second", String.valueOf(row.get("b")));
    }

    @Test
    void aBindValueIsDataAndCanNeverBecomeStatementText() throws Exception {
        // The classic injection payload. Interpolated it would terminate the literal and append a clause;
        // bound, it is just a five-word string. This is the whole reason `:name` binds rather than resolves.
        String payload = "x' OR 1=1 --";
        QueryExecutor.Result r = run("SELECT ? AS v", List.of(payload));

        assertEquals(1, r.rowCount());
        assertEquals(payload, String.valueOf(r.rows().get(0).get("v")));
    }

    @Test
    void aBoundValueFiltersRowsAndDuckDbCoercesItAgainstTheColumnType() throws Exception {
        // Bound as a string; DuckDB widens it against the integer column — the same coercion the old
        // inline-literal path depended on, so a template behaves identically however it is executed.
        String sql = "SELECT * FROM (SELECT 1 AS n UNION ALL SELECT 5 UNION ALL SELECT 9) t WHERE n > ?";

        assertEquals(2, run(sql, List.of("4")).rowCount());
        assertEquals(0, run(sql, List.of("100")).rowCount());
    }

    @Test
    void theNoBindPathStillUsesAPlainStatement() throws Exception {
        // Regression guard for the 7-arg compatibility constructor: existing callers pass no binds and
        // must keep taking the Statement branch.
        QueryExecutor.Request req = new QueryExecutor.Request(null, null, "SELECT 7 AS v", 10, 0,
                List.of(), List.of());
        assertTrue(req.binds().isEmpty());

        QueryExecutor.Result r = QueryExecutor.run(req);
        assertEquals(1, r.rowCount());
        assertEquals(7, ((Number) r.rows().get(0).get("v")).intValue());
    }
}
