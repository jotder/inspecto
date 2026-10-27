package com.gamma.sql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code SQLGUARD-QUOTED-TEXT-1}: the keyword and statement checks read real SQL tokens, not quoted data.
 * A {@code sql.template} Job with {@code WHERE record_kind = 'CALL'} was refused for the value CALL. Each allow
 * case here would be refused by the old raw-text scan; each refuse case must stay refused.
 */
class SqlGuardQuotedTextTest {

    private static void passes(String sql) {
        var f = SqlGuard.check(sql);
        assertTrue(f.isEmpty(), "expected pass but got: " + f);
    }

    private static void refuses(String sql) {
        assertFalse(SqlGuard.check(sql).isEmpty(), "expected refusal: " + sql);
    }

    @Test
    void keywordAsStringValuePasses() {
        passes("SELECT * FROM input WHERE record_kind = 'CALL'");
        passes("SELECT * FROM input WHERE op IN ('set', 'DROP', 'copy')");
        passes("SELECT 'a;b' AS x FROM input");
    }

    @Test
    void keywordAsQuotedIdentifierPasses() {
        passes("SELECT \"call\", \"set\" FROM input");
        passes("SELECT \"it's\" AS x FROM input WHERE y = 'CALL'");
    }

    @Test
    void keywordInCommentPasses() {
        passes("SELECT 1 FROM input -- CALL the numbers; DROP\n");
        passes("SELECT 1 /* CALL */ FROM input");
    }

    @Test
    void nestedCommentsPass() {
        passes("SELECT 1 /* outer /* inner CALL */ still comment DROP */ FROM input");
    }

    @Test
    void escapedQuoteStaysInsideTheString() {
        passes("SELECT * FROM input WHERE note = 'it''s CALL'");
    }

    @Test
    void dollarQuotedStringPasses() {
        passes("SELECT * FROM input WHERE record_kind = $$CALL; DROP$$");
        passes("SELECT * FROM input WHERE record_kind = $t$CALL$t$");
    }

    @Test
    void stringThatClosesEarlyAndSmugglesAStatementIsRefused() {
        refuses("SELECT * FROM input WHERE a = 'x'; DROP TABLE t; --'");
        refuses("SELECT * FROM input WHERE a = 'x'' OR ''1''=''1'; DROP TABLE t");
    }

    @Test
    void backslashIsNotAnEscapeSoEscapeStringSmugglingIsScannedAsCode() {
        // DuckDB reads E'..\'..' as one string; the guard reads more code, never less.
        refuses("SELECT * FROM input WHERE a = E'x\'; DROP TABLE t; --'");
    }

    @Test
    void keywordAsRealStatementOrClauseIsStillRefused() {
        refuses("CALL pragma_version()");
        refuses("SELECT 1; CALL foo()");
        refuses("SELECT 1 FROM input WHERE x = 'a' SET y = 2");
        refuses("PRAGMA database_list");
        refuses("COPY input TO 'x.csv'");
        refuses("ATTACH 'x.db'");
        refuses("DROP TABLE input");
        assertTrue(SqlGuard.check("SELECT 1 FROM input WHERE x = 'a' DELETE").get(0).message().contains("as SQL"),
                "the message says the keyword was found as SQL, not in text");
    }

    @Test
    void unterminatedQuoteOrDollarStringIsRefused() {
        refuses("SELECT * FROM input WHERE a = 'x; DROP TABLE t");
        refuses("SELECT * FROM input WHERE a = $$x; DROP TABLE t");
    }

    @Test
    void commentMarkerInsideAQuotedIdentifierDoesNotHideCode() {
        refuses("SELECT \"a--b\" FROM input; DROP TABLE t");
    }

    @Test
    void fileFunctionAndReplacementScanStayRefused() {
        refuses("SELECT * FROM read_csv('x.csv') WHERE k = 'CALL'");
        refuses("SELECT * FROM 'C:/x.csv' WHERE k = 'CALL'");
    }

    @Test
    void duckDbNestsBlockCommentsSoTheTokenizerAgrees() throws Exception {
        // If DuckDB did not nest, "/* /* */ DROP TABLE t */" would hide a real DROP from the tokenizer.
        com.gamma.util.DuckDbUtil.loadDriver();
        try (var c = com.gamma.util.DuckDbUtil.openInMemory(null);
             var st = c.createStatement();
             var rs = st.executeQuery("SELECT 1 /* a /* b */ DROP TABLE t */ AS x")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }
}
