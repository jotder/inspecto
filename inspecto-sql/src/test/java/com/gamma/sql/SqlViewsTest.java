package com.gamma.sql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the rendering rules of the only place in the product where a filesystem path becomes SQL text.
 *
 * <p>Every store glob is <b>interpolated</b>, never bound as a parameter — DuckDB's table functions take a
 * literal — so the single-quote doubling here is load-bearing and deliberately unobvious. It was documented
 * but untested; ⛔ do not "simplify" the {@code replace("'", "''")} away.
 */
class SqlViewsTest {

    @Test
    void quotesInAPathAreDoubledSoTheyCannotEndTheLiteral() {
        String sql = SqlViews.reader("PARQUET", "/data/O'Brien/orders/**/*.parquet", false);
        assertTrue(sql.contains("'/data/O''Brien/orders/**/*.parquet'"), sql);
        assertFalse(sql.contains("O'Brien"), "a single quote left undoubled would terminate the literal early");
    }

    @Test
    void quotesAreDoubledInAFileListToo() {
        String list = SqlViews.pathList(List.of("/d/a'b.parquet", "/d/c.parquet"));
        assertEquals("['/d/a''b.parquet', '/d/c.parquet']", list);
    }

    @Test
    void backslashesBecomeForwardSlashesSoAWindowsPathIsReadable() {
        assertTrue(SqlViews.reader("PARQUET", "C:\\data\\orders\\*.parquet", false)
                .contains("'C:/data/orders/*.parquet'"));
    }

    @Test
    void anEmptySelectionRendersAGlobThatMatchesNothingRatherThanAnEmptyList() {
        assertEquals("'__no_readable_files__/*'", SqlViews.pathList(List.of()));
        assertEquals("'__no_readable_files__/*'", SqlViews.pathList(null));
    }

    @Test
    void aGlobbedReadAndASingleFileListReadDifferOnlyInTheirSource() {
        String globbed = SqlViews.reader("PARQUET", "/d/*.parquet", false);
        String listed = SqlViews.reader("PARQUET", List.of("/d/*.parquet"), false);
        assertEquals(globbed.replace("'/d/*.parquet'", "X"), listed.replace("['/d/*.parquet']", "X"),
                "the read OPTIONS must not depend on how the source was chosen");
    }

    @Test
    void extDefaultsToCsvForAnythingThatIsNotParquet() {
        assertEquals("parquet", SqlViews.ext("PARQUET"));
        assertEquals("csv", SqlViews.ext("CSV"));
        assertEquals("csv", SqlViews.ext(null));
    }
}
