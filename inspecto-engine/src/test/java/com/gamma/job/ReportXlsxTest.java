package com.gamma.job;

import com.gamma.etl.ExcelExtension;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ASSURE-XLSX-ATTACHMENTS-1: {@code format: xlsx} through the sealed DuckDB {@code excel} path, and the
 * formula-injection neutraliser shared with CSV. The round-trip SKIPS (never passes) when no excel binary
 * can be loaded, the same rule as {@code PipelineDocumentXlsxTest}.
 */
class ReportXlsxTest {

    @Test
    void neutralisesEveryFormulaLead() {
        for (String s : List.of("=1+1", "+1", "-1", "@SUM(A1)", "\tx", "\rx", "\n=x", "  =x", " \r\n\t@x",
                "\uFF1D1+1", "\uFF0B1", "\uFF0D1", "\uFF20SUM(A1)", "|calc", "%x", " |calc", "\n\n+1"))
            assertEquals("'" + s, ReportXlsx.neutralise(s), s);
        for (String s : List.of("plain", "a=b", "1-2", "", "'quoted", "   ", " a|b", "50%", "\nplain"))
            assertEquals(s, ReportXlsx.neutralise(s), s);
        assertNull(ReportXlsx.neutralise(null));
    }

    private static void requireExcel() throws Exception {
        boolean[] loaded = {false};
        try (Connection c = DuckDbUtil.openInMemory(null, List.of(), conn -> loaded[0] = ExcelExtension.tryLoad(conn))) {
            Assumptions.assumeTrue(loaded[0], "no excel extension loadable on this host");
        }
    }

    private static List<List<String>> readBack(Path xlsx) throws Exception {
        List<List<String>> out = new ArrayList<>();
        try (Connection c = DuckDbUtil.openInMemory(null, List.of(xlsx.getParent()), ExcelExtension::ensureLoaded);
             Statement st = c.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT * FROM read_xlsx('"
                    + xlsx.toString().replace('\\', '/') + "', header = false, all_varchar = true)");
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= n; i++) row.add(rs.getString(i));
                out.add(row);
            }
        }
        return out;
    }

    @Test
    void roundTripsHeaderRowsAndNumbers(@TempDir Path dir) throws Exception {
        requireExcel();
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("region", "EU", "amount", 40.5, "count", 2L));
        rows.add(row("region", "US", "amount", -5.0, "count", 1L));
        Path out = dir.resolve("weekly.xlsx");
        ReportXlsx.write("weekly", rows, out);

        List<List<String>> got = readBack(out);
        assertEquals(List.of("region", "amount", "count"), got.get(0));
        assertEquals("EU", got.get(1).get(0));
        assertEquals(40.5, Double.parseDouble(got.get(1).get(1)));
        assertEquals(2.0, Double.parseDouble(got.get(1).get(2)));
        assertEquals(-5.0, Double.parseDouble(got.get(2).get(1)), "a negative NUMBER is a value, never prefixed");
        assertEquals(3, got.size());
    }

    @Test
    void formulaTextIsWrittenNeutralised(@TempDir Path dir) throws Exception {
        requireExcel();
        List<Map<String, Object>> rows = List.of(
                row("name", "=HYPERLINK(\"http://evil.example\",\"click\")", "note", "@SUM(1)"),
                row("name", "+1", "note", "-2"),
                row("name", "safe", "note", null));
        Path out = dir.resolve("inj.xlsx");
        ReportXlsx.write("inj", rows, out);

        List<List<String>> got = readBack(out);
        assertEquals("'=HYPERLINK(\"http://evil.example\",\"click\")", got.get(1).get(0));
        assertEquals("'@SUM(1)", got.get(1).get(1));
        assertEquals("'+1", got.get(2).get(0));
        assertEquals("'-2", got.get(2).get(1), "text '-2' is text, so it IS prefixed");
        assertEquals("safe", got.get(3).get(0));
        // and no cell anywhere in the workbook is stored as a formula
        assertFalse(sheetXml(out).contains("<f>") || sheetXml(out).contains("<f "), "no <f> formula element");
    }

    @Test
    void aHeaderThatLooksLikeAFormulaIsNeutralisedToo(@TempDir Path dir) throws Exception {
        requireExcel();
        Path out = dir.resolve("hdr.xlsx");
        ReportXlsx.write("hdr", List.of(row("=cmd", "x")), out);
        assertEquals("'=cmd", readBack(out).get(0).get(0));
    }

    @Test
    void anEmptyResultStillWritesAWorkbook(@TempDir Path dir) throws Exception {
        requireExcel();
        Path out = dir.resolve("empty.xlsx");
        ReportXlsx.write("empty", List.of(), out);
        assertTrue(Files.size(out) > 0);
    }

    private static String sheetXml(Path xlsx) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (var zip = new java.util.zip.ZipFile(xlsx.toFile())) {
            var e = zip.entries();
            while (e.hasMoreElements()) {
                var entry = e.nextElement();
                if (entry.getName().startsWith("xl/worksheets/"))
                    sb.append(new String(zip.getInputStream(entry).readAllBytes()));
            }
        }
        return sb.toString();
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
