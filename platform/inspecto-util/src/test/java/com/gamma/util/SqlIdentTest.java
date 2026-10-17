package com.gamma.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the exact semantics of the one SQL-quoting primitive (SQLIDENT-NINE-COPIES-1).
 *
 * <p>This is a security boundary, not a formatting helper: every historical copy of this escape was
 * byte-identical, so nothing here is arbitrary — a change to any assertion below is a change to
 * whether a crafted identifier can break out of the quotes in generated SQL.
 */
class SqlIdentTest {

    @Test
    void quotesAPlainIdentifier() {
        assertEquals("\"col\"", SqlIdent.q("col"));
    }

    @Test
    void doublesEmbeddedQuotes() {
        assertEquals("\"a\"\"b\"", SqlIdent.q("a\"b"));
    }

    /** The breakout attempt the javadoc calls "an injection": the payload must stay inside one identifier. */
    @Test
    void anInjectionPayloadStaysInsideTheQuotes() {
        assertEquals("\"x\"\"; DROP TABLE t; --\"", SqlIdent.q("x\"; DROP TABLE t; --"));
    }

    /** An already-doubled quote is data, not markup — it doubles again, to four. */
    @Test
    void anAlreadyDoubledQuoteDoublesAgain() {
        assertEquals("\"a\"\"\"\"b\"", SqlIdent.q("a\"\"b"));
    }

    @Test
    void anEmptyIdentifierBecomesAnEmptyQuotedIdentifier() {
        assertEquals("\"\"", SqlIdent.q(""));
    }

    /** Non-ASCII passes through untouched — only the quote character is special. */
    @Test
    void nonAsciiIdentifiersArePassedThrough() {
        assertEquals("\"Ärger\"", SqlIdent.q("Ärger"));
        assertEquals("\"日付\"", SqlIdent.q("日付"));
        assertEquals("\"naïve\"\"col\"", SqlIdent.q("naïve\"col"));
    }

    /** Null has no correct quoting — the as-built behaviour is to fail loudly, not to emit {@code ""}. */
    @Test
    void nullThrowsRatherThanQuotingNothing() {
        assertThrows(NullPointerException.class, () -> SqlIdent.q(null));
        assertThrows(NullPointerException.class, () -> SqlIdent.sqlStr(null));
    }

    @Test
    void sqlStrDoublesEmbeddedSingleQuotes() {
        assertEquals("'a''b'", SqlIdent.sqlStr("a'b"));
        assertEquals("''", SqlIdent.sqlStr(""));
        assertEquals("'x'' OR 1=1 --'", SqlIdent.sqlStr("x' OR 1=1 --"));
    }

    /** {@code SqlBuilder.quoteIdent} is the pre-existing public spelling; it must be the same function. */
    @Test
    void sqlBuilderQuoteIdentDelegates() {
        for (String s : new String[]{"col", "a\"b", "", "Ärger", "x\"; DROP TABLE t; --"})
            assertEquals(SqlIdent.q(s), SqlBuilder.quoteIdent(s));
    }

    /** A path renders absolute, forward-slashed and quote-doubled — byte-identical to the private copies it replaced. */
    @Test
    void pathStrIsAbsoluteForwardSlashedAndQuoteDoubled() {
        java.nio.file.Path p = java.nio.file.Path.of("dir", "O'Brien", "f.parquet");
        String abs = p.toAbsolutePath().toString();
        assertEquals("'" + abs.replace('\\', '/').replace("'", "''") + "'", SqlIdent.pathStr(p));
        org.junit.jupiter.api.Assertions.assertFalse(SqlIdent.pathStr(p).contains("\\"));
        org.junit.jupiter.api.Assertions.assertTrue(SqlIdent.pathStr(p).contains("O''Brien/f.parquet'"));
        java.nio.file.Path dir = java.nio.file.Path.of("d'x");
        assertEquals("'" + dir.toAbsolutePath().toString().replace('\\', '/').replace("'", "''") + "/*/*.parquet'",
                SqlIdent.pathStr(dir, "/*/*.parquet"));
    }

    /** With a parameter list the value is BOUND (never inlined); without one it is the escaped literal. */
    @Test
    void stringOperandBindsWhenParametersAreCollected() {
        java.util.List<Object> params = new java.util.ArrayList<>();
        assertEquals("CAST(? AS VARCHAR)", SqlBuilder.stringOperand("x' OR 1=1 --", params));
        assertEquals(java.util.List.of("x' OR 1=1 --"), params);
        assertEquals("'x'' OR 1=1 --'", SqlBuilder.stringOperand("x' OR 1=1 --", null));
    }
}
