package com.gamma.pipeline.exec;

import com.gamma.etl.DuckDbExtension;
import com.gamma.etl.ExcelExtension;
import com.gamma.pipeline.BuiltinNodeType;
import com.gamma.pipeline.PipelineNode;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code sink.excel} executor on the real engine path: a DuckDB relation in, a multi-sheet {@code .xlsx}
 * read back out. Every negative probe below sits beside a positive one on the SAME fixture, so a refusal is
 * the config's doing and not a broken fixture. The round trips SKIP (never pass) on a host with no loadable
 * {@code excel} binary — except {@link #noExcelExtensionFailsClosed}, which needs none.
 */
class ExcelSinkTest {

    @TempDir Path data;

    private static void requireExcel() throws Exception {
        boolean[] loaded = {false};
        try (Connection c = DuckDbUtil.openInMemory(null, List.of(), conn -> loaded[0] = ExcelExtension.tryLoad(conn))) {
            Assumptions.assumeTrue(loaded[0], "no excel extension loadable on this host");
        }
    }

    private static Connection batch() throws Exception {
        Connection c = DuckDbUtil.openInMemory(null, List.of());
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE rows_in (id BIGINT, region VARCHAR, amount DOUBLE, note VARCHAR)");
            st.execute("INSERT INTO rows_in VALUES (1, 'EU', 10.5, 'ok'), (2, 'EU', 4.5, "
                    + "'=HYPERLINK(\"http://evil.example\",\"x\")'), (3, 'US', 7.0, '@SUM(1)')");
        }
        return c;
    }

    private static PipelineNode node(Map<String, Object> cfg) {
        return PipelineNode.of("excel", BuiltinNodeType.SINK_EXCEL.type(), new LinkedHashMap<>(cfg));
    }

    private static Map<String, Object> cfg(String path, Object sheets) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", path);
        m.put("sheets", sheets);
        return m;
    }

    private static final List<Map<String, Object>> TWO_SHEETS = List.of(
            Map.of("name", "Orders"),
            Map.of("name", "By region", "sql", "SELECT region, count(*) AS orders FROM input GROUP BY region ORDER BY region"));

    private static List<List<String>> readSheet(Path xlsx, String sheet) throws Exception {
        List<List<String>> out = new ArrayList<>();
        try (Connection c = DuckDbUtil.openInMemory(null, List.of(xlsx.getParent()), ExcelExtension::ensureLoaded);
             Statement st = c.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT * FROM read_xlsx('" + xlsx.toString().replace('\\', '/')
                    + "', sheet = '" + sheet.replace("'", "''") + "', header = false, all_varchar = true)");
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= n; i++) row.add(rs.getString(i));
                out.add(row);
            }
        }
        return out;
    }

    private static List<String> sheetNames(Path xlsx) throws Exception {
        try (ZipFile z = new ZipFile(xlsx.toFile())) {
            String wb = new String(z.getInputStream(z.getEntry("xl/workbook.xml")).readAllBytes(), StandardCharsets.UTF_8);
            List<String> names = new ArrayList<>();
            for (Matcher m = Pattern.compile("<sheet\\b[^>]*\\bname=\"([^\"]*)\"").matcher(wb); m.find(); )
                names.add(m.group(1));
            return names;
        }
    }

    private static String allSheetXml(Path xlsx) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (ZipFile z = new ZipFile(xlsx.toFile())) {
            var e = z.entries();
            while (e.hasMoreElements()) {
                var entry = e.nextElement();
                if (entry.getName().startsWith("xl/worksheets/"))
                    sb.append(new String(z.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return sb.toString();
    }

    @Test
    void writesOneWorkbookWithANamedSheetPerEntry() throws Exception {
        requireExcel();
        try (Connection c = batch()) {
            Path out = ExcelSink.write(c, node(cfg("reports/orders.xlsx", TWO_SHEETS)), "rows_in", data);
            assertEquals(data.resolve("reports/orders.xlsx").toAbsolutePath().normalize(), out.normalize());
            assertEquals(List.of("Orders", "By region"), sheetNames(out), "both sheets, named, in order");

            List<List<String>> orders = readSheet(out, "Orders");
            assertEquals(List.of("id", "region", "amount", "note"), orders.get(0));
            assertEquals(4, orders.size(), "header + every row");
            List<List<String>> byRegion = readSheet(out, "By region");
            assertEquals(List.of("region", "orders"), byRegion.get(0));
            assertEquals("EU", byRegion.get(1).get(0));
            assertEquals(2.0, Double.parseDouble(byRegion.get(1).get(1)));
            assertEquals("US", byRegion.get(2).get(0));
            // no part file or staging directory is left beside the workbook
            try (var ls = Files.list(out.getParent())) {
                assertEquals(List.of("orders.xlsx"), ls.map(p -> p.getFileName().toString()).toList());
            }
        }
    }

    /** Negative probe: a cell starting with '=' (or '@') stays inert text — the report job's neutraliser. */
    @Test
    void aFormulaLookingCellIsWrittenInert() throws Exception {
        requireExcel();
        try (Connection c = batch()) {
            Path out = ExcelSink.write(c, node(cfg("inj.xlsx", List.of(Map.of("name", "Orders")))), "rows_in", data);
            List<List<String>> rows = readSheet(out, "Orders");
            assertEquals("ok", rows.get(1).get(3), "an ordinary value is untouched (the probe would otherwise succeed)");
            assertEquals("'=HYPERLINK(\"http://evil.example\",\"x\")", rows.get(2).get(3));
            assertEquals("'@SUM(1)", rows.get(3).get(3));
            String xml = allSheetXml(out);
            assertFalse(xml.contains("<f>") || xml.contains("<f "), "no cell is stored as a formula");
        }
    }

    @Test
    void aPathThatLeavesTheDataRootIsRefused() throws Exception {
        try (Connection c = batch()) {
            for (String escape : List.of("../outside.xlsx", "reports/../../outside.xlsx", "/tmp/outside.xlsx",
                    "C:/outside.xlsx")) {
                var e = assertThrows(IllegalStateException.class,
                        () -> ExcelSink.write(c, node(cfg(escape, TWO_SHEETS)), "rows_in", data), escape);
                assertTrue(e.getMessage().contains("excel.path"), e.getMessage());
            }
            assertFalse(Files.exists(data.getParent().resolve("outside.xlsx")));
        }
    }

    /** The write-time jail: a directory under the data root that is a link OUT of it is refused by PathJail. */
    @Test
    void aSymlinkOutOfTheDataRootIsRefusedAtWriteTime(@TempDir Path elsewhere) throws Exception {
        Path link = data.resolve("linked");
        try {
            Files.createSymbolicLink(link, elsewhere);
        } catch (Exception noLinks) {
            Assumptions.abort("cannot create a symlink on this host: " + noLinks);
        }
        var plan = ExcelSink.plan(node(cfg("linked/out.xlsx", TWO_SHEETS)));
        assertThrows(com.gamma.config.safety.PathJail.Escape.class, () -> ExcelSink.target(plan, data));
        assertNotNull(ExcelSink.target(ExcelSink.plan(node(cfg("plain/out.xlsx", TWO_SHEETS))), data),
                "the same shape without the link resolves");
    }

    @Test
    void aSheetOverTheRowCapFailsAndWritesNothing() throws Exception {
        try (Connection c = batch()) {
            Map<String, Object> capped = cfg("capped.xlsx", TWO_SHEETS);
            capped.put("max_rows", 2);
            var e = assertThrows(IllegalStateException.class, () -> ExcelSink.write(c, node(capped), "rows_in", data));
            assertTrue(e.getMessage().contains("max_rows (2)") && e.getMessage().contains("Orders"), e.getMessage());
            assertFalse(Files.exists(data.resolve("capped.xlsx")), "refused, never truncated");
        }
        requireExcel();
        try (Connection c = batch()) {   // at exactly the cap the same sheet is written
            Map<String, Object> exact = cfg("exact.xlsx", TWO_SHEETS);
            exact.put("max_rows", 3);
            assertEquals(4, readSheet(ExcelSink.write(c, node(exact), "rows_in", data), "Orders").size());
        }
    }

    @Test
    void aSheetWhoseSqlIsNotOneReadOnlySelectIsRefused() throws Exception {
        try (Connection c = batch()) {
            for (String sql : List.of("DROP TABLE rows_in", "SELECT 1; DROP TABLE rows_in",
                    "SELECT * FROM read_csv('/etc/passwd')")) {
                var e = assertThrows(IllegalStateException.class, () -> ExcelSink.write(c,
                        node(cfg("x.xlsx", List.of(Map.of("name", "Bad", "sql", sql)))), "rows_in", data), sql);
                assertTrue(e.getMessage().contains("sheet 'Bad' refused"), e.getMessage());
            }
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT count(*) FROM rows_in")) {
                rs.next();
                assertEquals(3, rs.getLong(1), "nothing ran");
            }
        }
    }

    /** Fail closed: with extensions staged ONLY from an empty directory, the write throws and leaves no file. */
    @Test
    void noExcelExtensionFailsClosed(@TempDir Path emptyExtDir) throws Exception {
        String prior = System.getProperty(DuckDbExtension.DIR_PROPERTY);
        System.setProperty(DuckDbExtension.DIR_PROPERTY, emptyExtDir.toString());
        try (Connection c = batch()) {
            Exception e = assertThrows(Exception.class,
                    () -> ExcelSink.write(c, node(cfg("none.xlsx", TWO_SHEETS)), "rows_in", data));
            String all = e + " " + e.getCause();
            assertTrue(all.contains("excel"), all);
            assertFalse(Files.exists(data.resolve("none.xlsx")));
            try (var ls = Files.list(data)) {
                assertEquals(0, ls.count(), "no workbook and no leftover parts directory");
            }
        } finally {
            if (prior == null) System.clearProperty(DuckDbExtension.DIR_PROPERTY);
            else System.setProperty(DuckDbExtension.DIR_PROPERTY, prior);
        }
    }

    /** A dry run refuses what the write would refuse, and otherwise writes nothing. */
    @Test
    void aDryRunChecksTheBlockAndWritesNothing() throws Exception {
        try (Connection c = batch()) {
            DryRunSinkWriter dry = new DryRunSinkWriter(c, null, null, null);
            dry.write(node(cfg("dry.xlsx", TWO_SHEETS)), "rows_in");
            assertFalse(Files.exists(data.resolve("dry.xlsx")));
            assertThrows(IllegalStateException.class, () -> dry.write(node(cfg("dry.xlsx",
                    List.of(Map.of("name", "A", "sql", "DROP TABLE rows_in")))), "rows_in"));
        }
    }

    @Test
    void theSinkWriterDispatchesToTheWorkbookAndRegistersNoStore() throws Exception {
        requireExcel();
        try (Connection c = batch()) {
            PartitionSinkWriter w = new PartitionSinkWriter(c, data.toString(), "b", null);
            w.write(node(cfg("via-writer.xlsx", TWO_SHEETS)), "rows_in");
            assertTrue(Files.isRegularFile(data.resolve("via-writer.xlsx")));
            assertEquals(0, w.totalRows(), "a report file is not a resting store");
        }
    }
}
