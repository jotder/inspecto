package com.gamma.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ExpressionGuard} (DAT-5): the accept set (arithmetic, whitelisted scalar functions, CASE flow,
 * cast with whitelisted types) and — the part that matters — the reject set from the design doc's threat
 * cases: scalar-subquery smuggling, file-function calls, statement escapes, comment/quote tricks.
 */
class ExpressionGuardTest {

    private static void ok(String expr) {
        assertEquals(expr.trim(), ExpressionGuard.check(expr), expr);
    }

    private static void bad(String expr, String why) {
        assertThrows(IllegalArgumentException.class, () -> ExpressionGuard.check(expr), why + ": " + expr);
    }

    @Test
    void acceptsRowLevelArithmeticFunctionsAndCase() {
        ok("amount * 1.2");
        ok("round(amount / 60.0, 2)");
        ok("coalesce(region, 'UNKNOWN')");
        ok("upper(region) || '-' || cast(amount AS varchar)");
        ok("CASE WHEN amount > 100 THEN 'big' WHEN amount > 10 THEN 'mid' ELSE 'small' END");
        ok("try_cast(raw_ts AS timestamp)");
        ok("nullif(trim(code), '')");
        ok("amount IS NOT NULL AND region IN ('EU', 'US')");
    }

    @Test
    void killsScalarSubquerySmuggling() {
        bad("(SELECT secret FROM t)", "SELECT is denied even as a bare word");
        bad("1 + (select 1)", "case-insensitive");
        bad("amount FROM t", "FROM denied");
        bad("a UNION ALL b", "UNION denied");
    }

    @Test
    void killsFileAndUnknownFunctionCalls() {
        bad("read_parquet('/etc/passwd')", "file function by name");
        bad("read_csv('x')", "file function by name");
        bad("glob('*')", "glob is not whitelisted");
        bad("my_udf(amount)", "unknown function");
        bad("sum(amount)", "aggregates are the Measure layer's job, not row-level");
    }

    /** The vetted date/string scalars (2026-09-25) — the derived fields a text-typed source needs. */
    @Test
    void acceptsTheVettedDateAndStringScalars() {
        ok("cast(strptime(DATE, '%B %d,%Y') AS date)");
        ok("monthname(strptime(DATE, '%B %d,%Y'))");
        ok("try_strptime(raw, '%Y-%m-%d')");
        ok("strftime(ts, '%Y-%m')");
        ok("date_trunc('month', ts)");
        ok("date_part('year', ts) + datepart('month', ts)");
        ok("year(d) * 100 + month(d) + day(d) + week(d) + quarter(d)");
        ok("dayname(d)");
        ok("trim(split_part(VENUE, ',', 2))");
        ok("starts_with(code, 'A') OR ends_with(code, 'Z') OR contains(code, 'Q')");
        ok("regexp_matches(code, '^[0-9]+$')");
        ok("regexp_extract(code, '([0-9]+)', 1)");
        ok("regexp_replace(code, '[^0-9]', '', 'g')");
        ok("left(code, 2) || right(code, 2)");
        ok("lpad(cast(n AS varchar), 4, '0') || rpad(code, 6, '_')");
        ok("if(WB_RUNS IS NOT NULL, 'Batting first', 'Chasing')");
    }

    /** Widening the whitelist must not open the file/settings/environment/clock surface. */
    @Test
    void stillKillsFileSettingsEnvironmentAndClockFunctions() {
        bad("read_csv('/etc/passwd')", "file reader");
        bad("read_csv_auto('x')", "file reader");
        bad("read_text('x')", "file reader");
        bad("getenv('HOME')", "environment");
        bad("current_setting('home_directory')", "settings");
        bad("now()", "clock — non-deterministic");
        bad("random()", "randomness — non-deterministic");
        bad("strptime(read_csv('x'), '%Y')", "a file call nested inside an allowed one");
    }

    @Test
    void acceptsWindowFunctionsWithAnOverClause() {
        ok("sum(amount) OVER (PARTITION BY region ORDER BY day)");
        ok("row_number() OVER (ORDER BY amount DESC)");
        ok("avg(amount) OVER (PARTITION BY region)");
        ok("rank() OVER (ORDER BY amount DESC NULLS LAST)");
        ok("count(*) OVER ()");
        ok("lag(amount) OVER (ORDER BY day) - amount");            // window expr then arithmetic
        ok("sum(amount) OVER (ORDER BY day) / 100");
        ok("sum(round(amount, 2)) OVER (PARTITION BY region)");    // scalar fn inside the window arg
    }

    @Test
    void killsBareAggregatesAndMalformedWindowClauses() {
        bad("sum(amount)", "windowed aggregate used bare (no OVER)");
        bad("avg(amount)", "windowed aggregate used bare (no OVER)");
        bad("count(*)", "count(*) used bare (no OVER)");
        bad("row_number()", "window function used bare (no OVER)");
        bad("sum(amount) OVER region", "OVER must be followed by '('");
        bad("sum(amount) over_by (ORDER BY day)", "the token after a window call must be exactly OVER");
        bad("over(x)", "'over' is not a callable function");
        bad("sum(amount) OVER (ORDER BY (select 1))", "DENIED words are rejected even inside the OVER clause");
    }

    @Test
    void killsStatementAndLexicalEscapes() {
        bad("1; DROP TABLE t", "semicolon never lexes");
        bad("amount -- comment", "line comment never lexes");
        bad("amount /* c */", "block comment never lexes");
        bad("\"quoted\"", "double-quoted identifiers are out of the v1 alphabet");
        bad("'unterminated", "broken string literal");
        bad("cast(x AS blob)", "non-whitelisted cast target");
        bad("a + )", "unbalanced parens (close)");
        bad("(a + 1", "unbalanced parens (open)");
        bad("", "empty expression");
        bad("a".repeat(501), "length cap");
    }

    /** EXPRESSION-GUARD-ESTRING-1: DuckDB reads a string with a letter/digit prefix as another literal kind. */
    @Test
    void killsPrefixedStringLiteralsDuckDbLexesDifferently() {
        bad("E'\\''' || ') IS NOT NULL UNION ALL SELECT secret FROM s --'", "escape string smuggling a statement");
        bad("e'\\'''", "escape string, lower case");
        bad("E'x'", "any escape string");
        bad("X'41'", "hex/blob literal");
        bad("B'101'", "bit-string literal");
        bad("N'x'", "national string literal");
        bad("1e'\\'''", "DuckDB re-lexes the 'e' after a number as an escape-string prefix");
        bad("amount_e'x'", "any identifier character before the quote");
        bad("code = E'\\'' OR 1 = 1", "prefixed literal mid-expression");
    }

    /** DuckDB reads a number glued to a word as the number then a token (1e1from = 1e1 FROM): a denied keyword
     *  would reach DuckDB unseen, so a number touching a letter, digit or _ never lexes. */
    @Test
    void killsNumbersGluedToAWord() {
        bad("trim(1e1from y)", "denied keyword hidden behind a number");
        bad("1from", "denied keyword glued to an integer");
        bad("0x1F", "hex number");
        bad("1_000", "digit-group separator");
        bad("1.5e3", "exponent form");
        ok("round(amount * 1.5, 2) + 10");
        ok("col_2 + x1");
    }

    /** The fix refuses only the prefixed form: a standard string keeps backslashes and '' as text, as DuckDB does. */
    @Test
    void stillAcceptsStandardStringsWithBackslashesAndDoubledQuotes() {
        ok("regexp_matches(code, '\\d+')");
        ok("replace(path, '\\', '/')");
        ok("code = 'it''s' OR code = ''''");
        ok("coalesce(region,'UNKNOWN')");
        ok("upper(region)||'-'");
        ok("CAST('2026-01-01' AS date)");
        ok("date '2026-01-01'");
    }
}
