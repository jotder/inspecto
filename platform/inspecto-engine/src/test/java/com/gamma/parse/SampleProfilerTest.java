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

    /**
     * BE-3 slice 2, {@link SampleProfiler#types}: DuckDB's own type beside the vote, per column, in the relation's order;
     * the vote reads the first {@code limit} rows only and leaves no table behind. All three callers' answers are pinned
     * end to end in {@code com.gamma.control.TypeInferenceGoldenTest}.
     */
    @Test
    void typesGiveDuckDbsTypeBesideTheVote(@TempDir Path dir) throws Exception {
        DuckDbUtil.loadDriver();
        Path f = dir.resolve("types.csv");
        Files.writeString(f, "n,d,t,w\n1,2026-01-02,09:30:00,\"March 22, 2025\"\n2,2026-03-04,17:05:00,\"April 1, 2025\"\n3.5,x,x,x\n");
        String sniffed = "read_csv(" + com.gamma.util.SqlIdent.pathStr(f) + ", auto_detect = true)";
        try (Connection conn = DuckDbUtil.openInMemory(null, List.of(dir), null)) {
            List<SampleProfiler.ColumnType> two = SampleProfiler.types(conn, sniffed, 2);
            assertEquals(List.of(
                    // the vote reads the values as the relation renders them: DuckDB's DOUBLE 1 is the text '1.0'
                    new SampleProfiler.ColumnType("n", "DOUBLE", "DOUBLE", null, null, 2, 2, null),
                    new SampleProfiler.ColumnType("d", "VARCHAR", "DATE", "%Y-%m-%d", null, 2, 2, null),
                    new SampleProfiler.ColumnType("t", "VARCHAR", "TIME", null, null, 2, 2, null),
                    new SampleProfiler.ColumnType("w", "VARCHAR", "DATE", "%B %d, %Y", null, 2, 2, null)), two,
                    "DuckDB types the whole sample; the vote reads the first two rows");
            assertEquals("VARCHAR", SampleProfiler.types(conn, sniffed, 0).get(1).type(), "every row: 'x' is not a date");
            try (var st = conn.createStatement();
                 var rs = st.executeQuery("SELECT count(*) FROM duckdb_tables() WHERE table_name = 'sample_profiler_values'")) {
                rs.next();
                assertEquals(0, rs.getLong(1), "the vote's scratch table is dropped");
            }
        }
    }

    /**
     * The operator's rulings (2026-11-19) in the vote itself: a number written with the decimal mark it is given is a
     * DOUBLE carrying the mark (and text without it); almost all dates are a date that names its one odd value; almost
     * all numbers stay text.
     */
    @Test
    void theVoteFollowsTheRulings(@TempDir Path dir) throws Exception {
        DuckDbUtil.loadDriver();
        StringBuilder csv = new StringBuilder("amount;day;mostly\n");
        for (int i = 1; i <= 20; i++)
            csv.append(i).append(",5;").append(i == 3 ? "soon" : "2025-03-" + (10 + i)).append(';').append(i == 4 ? "n/a" : i + ".5").append("\n");
        Path f = dir.resolve("r.csv");
        Files.writeString(f, csv.toString());
        String rel = "read_csv(" + com.gamma.util.SqlIdent.pathStr(f) + ", delim = ';', all_varchar = true)";
        try (Connection conn = DuckDbUtil.openInMemory(null, List.of(dir), null)) {
            List<SampleProfiler.ColumnType> comma = SampleProfiler.types(conn, rel, 0, ",");
            assertEquals(new SampleProfiler.ColumnType("amount", "VARCHAR", "DOUBLE", null, ",", 20, 20, null), comma.get(0));
            assertEquals(new SampleProfiler.ColumnType("day", "VARCHAR", "DATE", "%Y-%m-%d", null, 19, 20, "soon"), comma.get(1));
            assertEquals("VARCHAR", comma.get(2).type(), "19 of 20 numbers is text");
            assertEquals("VARCHAR", SampleProfiler.types(conn, rel, 0).get(0).type(), "no mark: '1,5' is text");
            // under a comma mark the import reads '3.5' as 35: such a column is no number (verification finding 2)
            String points = "(SELECT * FROM (VALUES ('3.5'), ('47.00'), ('12')) t(x))";
            assertEquals("VARCHAR", SampleProfiler.types(conn, points, 0, ",").get(0).type());
            assertEquals("DOUBLE", SampleProfiler.types(conn, points, 0, ".").get(0).type());
            assertEquals("BIGINT", SampleProfiler.types(conn, "(SELECT * FROM (VALUES ('12'), ('7')) t(x))", 0, ",").get(0).type());
        }
    }

    @Test
    void theOptionsCannotBeChanged(@TempDir Path dir) throws Exception {
        Map<String, String> options = sniff(dir, "a,b\n1,2\n", null).options();
        assertThrows(UnsupportedOperationException.class, () -> options.put("delimiter", ";"));
    }
}
