package com.gamma.parse;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SampleProfiler#dialect} ({@code BASIC-ELT-PROFILE-1} BE-3): DuckDB's own dialect answer for a sample file, as
 * grammar option names, unshaped. Real DuckDB over temp files. Both callers' answers are pinned end to end in
 * {@code com.gamma.control.DialectSniffGoldenTest}.
 */
class SampleProfilerTest {

    private static SampleProfiler.Dialect sniff(Path dir, String text, String encoding) throws Exception {
        return sniff(dir, text.getBytes(StandardCharsets.UTF_8), encoding);
    }

    private static SampleProfiler.Dialect sniff(Path dir, byte[] bytes, String encoding) throws Exception {
        DuckDbUtil.loadDriver();
        Path f = dir.resolve("sample.csv");
        Files.write(f, bytes);
        try (Connection conn = DuckDbUtil.openInMemory(null, List.of(dir), null)) {
            return SampleProfiler.dialect(conn, f, encoding);
        }
    }

    @Test
    void reportsEveryOptionUnderItsGrammarNameInSniffOrder(@TempDir Path dir) throws Exception {
        SampleProfiler.Dialect d = sniff(dir, "id,day\n1,2026-07-15\n2,2026-07-16\n", null);
        assertEquals("{delimiter=,, quote=(empty), escape=(empty), comment=(empty), skip_header_lines=0, has_header=true, "
                + "date_format=%Y-%m-%d}", d.options().toString(), "DuckDB's '(empty)' is passed through, not shaped");
        assertEquals(",", d.delimiter());
        assertTrue(d.header());
    }

    @Test
    void findsEachCommonDelimiter(@TempDir Path dir) throws Exception {
        for (String delim : new String[] {",", ";", "\t", "|"}) {
            String text = "id" + delim + "name" + delim + "city\n1" + delim + "Ann" + delim + "Oslo\n2" + delim + "Bob" + delim + "Rome\n";
            assertEquals(delim, sniff(dir, text, null).delimiter(), "delimiter " + delim);
        }
    }

    @Test
    void findsTheQuoteAndANoHeaderFile(@TempDir Path dir) throws Exception {
        SampleProfiler.Dialect quoted = sniff(dir, "id,name\n1,\"Smith, Ann\"\n2,\"Lee, Bob\"\n", null);
        assertEquals("\"", quoted.quote());
        assertTrue(quoted.header());
        assertFalse(sniff(dir, "1,EMEA,2024-01-10\n2,APAC,2024-01-11\n3,AMER,2024-02-05\n", null).header());
    }

    /** The encoding reaches {@code sniff_csv}: a Latin-1 file is unreadable as UTF-8 and sniffs once it is named. */
    @Test
    void sniffsInTheEncodingItIsGiven(@TempDir Path dir) throws Exception {
        byte[] latin1 = "id;name\n1;José\n2;Renée\n".getBytes(StandardCharsets.ISO_8859_1);
        assertThrows(SQLException.class, () -> sniff(dir, latin1, null));
        assertEquals(";", sniff(dir, latin1, "latin-1").delimiter());
    }

    /** An unsettled dialect is the caller's call: the parse preview shows nothing, Add data falls back to its own guess. */
    @Test
    void anUnsettledDialectThrows(@TempDir Path dir) throws Exception {
        SQLException e = assertThrows(SQLException.class, () -> sniff(dir, "a,b\r\n1,2\n3,4\r5,6\n", null));
        assertTrue(e.getMessage().contains("Error when sniffing file"), e.getMessage());
    }

    /** BE-3 slice 2: the lines the sniff skips before the header, as a number Add data reads; 0 when none. */
    @Test
    void reportsTheLinesItSkipsBeforeTheHeader(@TempDir Path dir) throws Exception {
        assertEquals(1, sniff(dir, "# exported\na,b\n1,2\n3,4\n", null).skip());
        assertEquals(0, sniff(dir, "a,b\n1,2\n3,4\n", null).skip());
        assertEquals(0, new SampleProfiler.Dialect(Map.of()).skip(), "nothing reported is nothing skipped");
    }

    @Test
    void theOptionsCannotBeChanged(@TempDir Path dir) throws Exception {
        Map<String, String> options = sniff(dir, "a,b\n1,2\n", null).options();
        assertThrows(UnsupportedOperationException.class, () -> options.put("delimiter", ";"));
    }
}
