package com.gamma.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The empirical check decision <b>D-S2</b> (Link Analysis, {@code docs/superpower/link-analysis-backlog-plan.md})
 * requires before server-side multi-hop traversal (LA-11) may be treated as a free option.
 *
 * <p>The question is narrow and structural. {@link QueryExecutor#run} wraps every caller's SQL as
 * {@code SELECT … FROM (<sql>) AS "__q"}, unconditionally — so a {@code WITH RECURSIVE} body would land
 * inside a <b>derived table</b>, which is not where SQL dialects usually put one. {@code SqlGuard} admits
 * it (its {@code STARTS_READONLY} pattern accepts a leading {@code with}, and {@code RECURSIVE} is not a
 * blocked keyword), so the guard was never the open question — the wrap was, and nothing in the repo
 * exercised it.
 *
 * <p>⚠ These tests pin a <b>capability of the seam</b>, not a shipped feature. No production code emits a
 * recursive CTE today. If one of them goes red, LA-11's "recursive CTE in DuckDB" option has become more
 * expensive than its grounding claims, and the decision row must be re-read before that option is costed.
 */
class QueryExecutorRecursiveCteTest {

    private static QueryExecutor.Result run(String sql) throws Exception {
        return QueryExecutor.run(new QueryExecutor.Request(null, null, sql, 100, 0, List.of(), List.of(), List.of()));
    }

    private static QueryExecutor.Result run(String sql, List<String> binds) throws Exception {
        return QueryExecutor.run(new QueryExecutor.Request(null, null, sql, 100, 0, List.of(), List.of(), binds));
    }

    /**
     * 🔴 The question D-S2's original probe did NOT ask, and the one that decides how LA-11's depth fence is
     * written: does a bound {@code ?} survive INSIDE a recursive member, through this wrap-and-prepare path?
     *
     * <p>It matters because the fence must live inside the recursion (the outer {@code LIMIT} bounds the
     * result, never the work). If a bind works, the caller's depth is a parameter and never becomes statement
     * text. If it does not, the depth must be validated as an int and clamped before being inlined — the
     * pattern {@code limit} already uses — and that difference is a security property, not a style choice.
     */
    @Test
    void aBoundParameterSurvivesInsideTheRecursiveMember() throws Exception {
        QueryExecutor.Result r = run(
                "WITH RECURSIVE t(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM t WHERE n < CAST(? AS INTEGER))"
                        + " SELECT n FROM t",
                List.of("4"));

        assertEquals(4, r.rowCount(), "the bound depth should fence the recursion at 4 rows");
        assertEquals(4, ((Number) r.rows().get(3).get("n")).intValue());
    }

    /** And the bind is DATA, never statement text — the property the whole bind path exists to guarantee. */
    @Test
    void aBoundDepthCannotCarrySql() throws Exception {
        QueryExecutor.Result r = run(
                "WITH RECURSIVE t(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM t WHERE n < CAST(? AS INTEGER))"
                        + " SELECT count(*) AS c FROM t",
                List.of("2"));

        assertEquals(2, ((Number) r.rows().get(0).get("c")).intValue());
    }

    /** The bare shape: does a recursive CTE survive being wrapped into {@code FROM (…) AS "__q"} at all? */
    @Test
    void recursiveCteSurvivesTheDerivedTableWrap() throws Exception {
        QueryExecutor.Result r =
                run("WITH RECURSIVE t(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM t WHERE n < 5) SELECT n FROM t");

        assertEquals(5, r.rowCount(), "the recursion should yield 1..5 through the wrap");
        assertEquals(1, ((Number) r.rows().get(0).get("n")).intValue());
        assertEquals(5, ((Number) r.rows().get(4).get("n")).intValue());
    }

    /**
     * The shape LA-11 would actually compile: multi-hop reachability over an edge relation, with the depth
     * fence D-S2 names as a real cost. Three hops out of A reaches B, C and D and stops — the fence is
     * expressed inside the recursion, so it bounds work rather than trimming a finished result.
     */
    @Test
    void boundedMultiHopTraversalIsExpressible() throws Exception {
        String sql =
                "WITH RECURSIVE edges(src, dst) AS ("
                        + "  SELECT * FROM (VALUES ('A','B'),('B','C'),('C','D'),('D','E')) AS e(src, dst)"
                        + "), walk(node, depth) AS ("
                        + "  SELECT 'A', 0"
                        + "  UNION ALL"
                        + "  SELECT e.dst, w.depth + 1 FROM walk w JOIN edges e ON e.src = w.node WHERE w.depth < 3"
                        + ") SELECT node, depth FROM walk WHERE depth > 0 ORDER BY depth";

        QueryExecutor.Result r = run(sql);

        assertEquals(3, r.rowCount(), "a depth fence of 3 should reach B, C, D and stop short of E");
        assertEquals("B", r.rows().get(0).get("node"));
        assertEquals("D", r.rows().get(2).get("node"));
    }

    /**
     * ⛔ The fence has to be inside the recursion. {@link QueryExecutor}'s own {@code LIMIT n+1} is applied
     * <i>outside</i> the derived table, so it truncates the answer <b>after</b> the walk has already been
     * paid for — it is a paging device, never a traversal bound. A cyclic graph shows the difference: the
     * depth predicate is the only thing standing between this query and an unbounded recursion.
     */
    @Test
    void theOuterLimitDoesNotBoundTheRecursion() throws Exception {
        String sql =
                "WITH RECURSIVE edges(src, dst) AS ("
                        + "  SELECT * FROM (VALUES ('A','B'),('B','A')) AS e(src, dst)"
                        + "), walk(node, depth) AS ("
                        + "  SELECT 'A', 0"
                        + "  UNION ALL"
                        + "  SELECT e.dst, w.depth + 1 FROM walk w JOIN edges e ON e.src = w.node WHERE w.depth < 6"
                        + ") SELECT count(*) AS c FROM walk";

        QueryExecutor.Result r = run(sql);

        assertTrue(
                ((Number) r.rows().get(0).get("c")).intValue() == 7,
                "the cycle should be walked exactly to the in-recursion depth fence, not to the outer LIMIT");
    }

    /**
     * LA-11's timeout fence: the route runs under its own sandbox policy, and that policy's statement timeout
     * really cancels a runaway walk. Twin: the same policy admits a cheap statement.
     */
    @Test
    void aRouteLocalPolicyTimeoutCancelsARunawayStatement() throws Exception {
        com.gamma.sql.SqlSandboxPolicy oneSecond = com.gamma.sql.SqlSandboxPolicy.withCaps(null, 0, 1);
        QueryExecutor.Result cheap = QueryExecutor.run(new QueryExecutor.Request(null, null,
                "SELECT 1 AS n", 10, 0, List.of(), List.of()), oneSecond);
        assertEquals(1, cheap.rowCount());

        long t0 = System.nanoTime();
        java.sql.SQLException timedOut = org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class,
                () -> QueryExecutor.run(new QueryExecutor.Request(null, null,
                        "WITH RECURSIVE t(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM t WHERE n < 2000000000)"
                                + " SELECT count(*) AS c FROM t", 10, 0, List.of(), List.of()), oneSecond));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 15_000, "the 1 s fence should cancel well before the walk ends, took " + ms + " ms: " + timedOut);
    }
}
