package com.gamma.sql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code SQLGUARD-COMMA-RELATION-1} — the second layer of {@link SqlGuard}: DuckDB's own parse tree
 * ({@code json_serialize_sql}) judges every relation wherever it sits, so a file literal is refused after a
 * FROM-list comma, inside a subquery, a CTE body or a set-operation branch — places the lexical scan (which
 * reads only the token after {@code FROM}/{@code JOIN}) cannot see. The {@code parseTreeViolations} cases
 * call layer two directly, proving it stands on its own and not only behind the lexical layer.
 */
class SqlGuardParseTreeTest {

    private static boolean rejects(String sql) {
        return !SqlGuard.check(sql).isEmpty();
    }

    private static boolean treeRejects(String sql) {
        return !SqlGuard.parseTreeViolations(sql, null).isEmpty();
    }

    /** The reported probe: guard findings were [] and the file's rows came back. */
    @Test
    void aFileLiteralAfterAFromListCommaIsRefused() {
        String sql = "SELECT b.secret FROM (SELECT 1) a, 'C:/any/secret.parquet' b";
        assertTrue(rejects(sql), "the comma probe must be refused");
        assertTrue(SqlGuard.check(sql).get(0).message().contains("secret.parquet"),
                "the finding names the file: " + SqlGuard.check(sql));
    }

    @Test
    void aFileRelationIsRefusedWhereverItSits() {
        for (String sql : List.of(
                "SELECT * FROM (SELECT 1) a, \"x.csv\" b",
                "SELECT * FROM (SELECT 1) a, (SELECT 2) b, 'x.parquet' c",
                "SELECT * FROM t, '/etc/passwd' p",
                "SELECT * FROM t WHERE a IN (SELECT b FROM t, 'x.csv')",
                "SELECT (SELECT max(x) FROM t, 'x.csv')",
                "WITH c AS (SELECT * FROM t, 'x.csv') SELECT * FROM c",
                "SELECT * FROM t UNION ALL SELECT * FROM u, 'x.csv'",
                "SELECT * FROM t, LATERAL (SELECT * FROM u, 'x.csv') l",
                "SELECT list_transform([1], x -> (SELECT count(*) FROM t, 'x.csv'))",
                "SELECT * FROM t, 's3://bucket/k.parquet'",
                "SELECT * FROM t, 'data/*.parquet'",
                "SELECT * FROM \"c:\\x\".main.t"))
            assertTrue(rejects(sql), "must be refused: " + sql);
    }

    @Test
    void layerTwoRefusesOnItsOwn() {
        for (String sql : List.of(
                "FROM 'x.parquet'",
                "SELECT * FROM 'x.csv'",
                "SELECT * FROM \"read_csv\"('x')",
                "SELECT * FROM t, LATERAL read_csv(t.p)",
                "SELECT * FROM glob('*')",
                "SELECT getenv('HOME')",
                "SELECT 1; SELECT 2",
                "INSERT INTO t VALUES (1)",
                "EXPLAIN SELECT 1",
                "PIVOT t ON a USING sum(b)",
                "SELECT * FROM (DESCRIBE 'x.csv')",
                "SELECT * FROM (SUMMARIZE SELECT 1)",
                "SELECT * FROM duckdb_databases",
                "SELECT FROM WHERE"))
            assertTrue(treeRejects(sql), "layer two alone must refuse: " + sql);
    }

    @Test
    void theTrustedRelationIsExemptOnlyAsAnExactBareName() {
        String trusted = "mule_transfers/database";
        assertTrue(SqlGuard.check("SELECT count(*) FROM \"mule_transfers/database\"", trusted).isEmpty());
        assertFalse(SqlGuard.check("SELECT * FROM \"mule_transfers/database\" a, 'x.csv' b", trusted).isEmpty(),
                "trusting one relation must not admit a file literal beside it");
        assertFalse(SqlGuard.check("SELECT * FROM t, \"other/database\"", trusted).isEmpty());
    }

    /** Legitimate SQL shaped like what SqlGuard's callers send: none of it may be refused. */
    @Test
    void legitimateReadOnlySqlStillPasses() {
        for (String sql : List.of(
                "SELECT cell, COUNT(*) AS calls FROM input GROUP BY cell",
                "SELECT * FROM \"input\" WHERE amount > 100 AND status = 'open'",
                "WITH daily AS (SELECT day, cell, COUNT(*) c FROM input GROUP BY day, cell) "
                        + "SELECT day, SUM(c) FROM daily GROUP BY day",
                "WITH RECURSIVE r(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM r WHERE n < 3) SELECT * FROM r",
                "SELECT a FROM t UNION SELECT a FROM u EXCEPT SELECT a FROM w",
                "SELECT t.a, u.b FROM t JOIN u ON t.id = u.id LEFT JOIN w USING (id)",
                "SELECT * FROM t, u WHERE t.id = u.id",
                "SELECT * FROM main.t",
                "SELECT * FROM t ASOF JOIN u ON t.ts >= u.ts",
                "SELECT * FROM t, LATERAL (SELECT t.a * 2 AS b) l",
                "SELECT * FROM (VALUES (1, 'a'), (2, 'b')) v(id, name)",
                "SELECT * FROM range(10) r",
                "SELECT * FROM generate_series(1, 5)",
                "SELECT unnest([1, 2, 3]) AS x",
                "SELECT * FROM t UNPIVOT (v FOR k IN (a, b))",
                "SELECT 'x.csv' AS file_name, 'C:/a/b' AS p FROM t",
                "SELECT * FROM t WHERE path LIKE '%/%.csv'",
                "SELECT strftime(ts, '%Y-%m') m, regexp_matches(a, 'a.b') FROM t",
                "SELECT a, sum(b) OVER (PARTITION BY c ORDER BY d) FROM t QUALIFY row_number() OVER () = 1",
                "SELECT CAST(a AS DATE), a::VARCHAR, {'k': 1} AS s FROM t",
                "SELECT * FROM t WHERE a = ? AND b = $1",
                "SELECT count(*) FROM __registered_read",
                "SELECT * FROM t TABLESAMPLE 10%",
                "SELECT 1 AS x;"))
            assertEquals(List.of(), SqlGuard.check(sql), "must pass: " + sql);
    }

    /** Per-call cost on a warm connection; printed for the record (2026-09-24: ~0.2 ms). */
    @Test
    void perCallOverheadIsSmall() {
        String sql = "WITH x AS (SELECT a, sum(b) s FROM t JOIN u USING (id) WHERE c > 1 GROUP BY a) "
                + "SELECT * FROM x ORDER BY s DESC LIMIT 10";
        SqlGuard.parseTreeViolations(sql, null); // warm: opens the parser connection
        int n = 500;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) assertEquals(List.of(), SqlGuard.parseTreeViolations(sql + " -- " + i, null));
        double avgMs = (System.nanoTime() - t0) / 1e6 / n;
        System.out.printf("SqlGuard parse-tree layer: %.3f ms/call over %d calls%n", avgMs, n);
        assertTrue(avgMs < 20, "the parse-tree layer must not reopen a connection per call: " + avgMs + " ms");
    }
}
