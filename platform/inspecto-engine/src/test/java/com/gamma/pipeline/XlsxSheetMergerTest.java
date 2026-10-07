package com.gamma.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The zip-level sheet merger behind the Pipeline Document workbook ({@code XLSX-EXPORT-LAST-SECTION-ONLY-1}).
 *
 * <p>⛔ UNGATED: the parts are hand-built in the exact shape DuckDB 1.5.2's {@code excel} writer emits
 * (inline strings, empty {@code sst}, one sheet at {@code rId3}), so this runs on CI where the extension
 * binary is absent and the real write in {@link PipelineDocumentXlsxTest} skips.
 */
class XlsxSheetMergerTest {

    private static final String NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String STYLES = "<?xml version=\"1.0\"?><styleSheet xmlns=\"" + NS + "\"/>";

    /** A one-sheet workbook shaped like the 1.5.2 writer's output; {@code cellXml} is the {@code <c>} body. */
    static Path part(Path dir, String file, String sheetName, String cellXml, String styles) throws Exception {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("xl/worksheets/sheet1.xml", "<?xml version=\"1.0\"?><worksheet xmlns=\"" + NS + "\"><sheetData>"
                + "<row r=\"1\">" + cellXml + "</row></sheetData></worksheet>");
        e.put("[Content_Types].xml", "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>");
        e.put("xl/workbook.xml", "<?xml version=\"1.0\"?><workbook xmlns=\"" + NS + "\" xmlns:r=\"" + R_NS + "\"><workbookPr/>"
                + "<sheets><sheet name=\"" + sheetName + "\" state=\"visible\" sheetId=\"1\" r:id=\"rId3\"/></sheets><definedNames/><calcPr/></workbook>");
        e.put("xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"" + R_NS + "/styles\" Target=\"styles.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"" + R_NS + "/sharedStrings\" Target=\"sharedStrings.xml\"/>"
                + "<Relationship Id=\"rId3\" Type=\"" + R_NS + "/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>");
        e.put("xl/styles.xml", styles);
        e.put("xl/sharedStrings.xml", "<?xml version=\"1.0\"?><sst xmlns=\"" + NS + "\" count=\"0\" uniqueCount=\"0\"/>");
        Path p = dir.resolve(file);
        try (OutputStream out = Files.newOutputStream(p); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> x : e.entrySet()) {
                zip.putNextEntry(new ZipEntry(x.getKey()));
                zip.write(x.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return p;
    }

    static String inline(String text) {
        return "<c r=\"A1\" t=\"inlineStr\"><is><t>" + text + "</t></is></c>";
    }

    /** The workbook's sheets in order, each mapped to every {@code <t>} text in it — resolved via the rels. */
    static LinkedHashMap<String, List<String>> sheets(Path xlsx) throws Exception {
        LinkedHashMap<String, List<String>> out = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(xlsx.toFile())) {
            Map<String, String> targets = new HashMap<>();
            NodeList rels = parse(zip, "xl/_rels/workbook.xml.rels").getElementsByTagNameNS("*", "Relationship");
            for (int i = 0; i < rels.getLength(); i++) {
                Element r = (Element) rels.item(i);
                targets.put(r.getAttribute("Id"), r.getAttribute("Target"));
            }
            NodeList sheets = parse(zip, "xl/workbook.xml").getElementsByTagNameNS(NS, "sheet");
            for (int i = 0; i < sheets.getLength(); i++) {
                Element s = (Element) sheets.item(i);
                String target = targets.get(s.getAttributeNS(R_NS, "id"));
                assertNotNull(target, "sheet " + s.getAttribute("name") + " has no relationship");
                assertNotNull(zip.getEntry("xl/" + target), "sheet part missing: xl/" + target);
                NodeList ts = parse(zip, "xl/" + target).getElementsByTagNameNS(NS, "t");
                List<String> texts = new ArrayList<>();
                for (int j = 0; j < ts.getLength(); j++) texts.add(ts.item(j).getTextContent());
                assertNull(out.put(s.getAttribute("name"), texts), "duplicate sheet name " + s.getAttribute("name"));
            }
            String types = new String(zip.getInputStream(zip.getEntry("[Content_Types].xml")).readAllBytes(), StandardCharsets.UTF_8);
            for (String t : targets.values())
                if (t.startsWith("worksheets/"))
                    assertTrue(types.contains("PartName=\"/xl/" + t + "\""), "no content-type Override for " + t);
        }
        return out;
    }

    private static Document parse(ZipFile zip, String name) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(zip.getInputStream(zip.getEntry(name)).readAllBytes()));
    }

    @Test
    void everyPartBecomesItsOwnSheetInOrder(@TempDir Path dir) throws Exception {
        List<Path> parts = List.of(
                part(dir, "a.xlsx", "x", inline("Steps"), STYLES),
                part(dir, "b.xlsx", "x", inline("1. Collect"), STYLES),
                part(dir, "c.xlsx", "x", inline("Guarantees &amp; more"), STYLES));
        Path out = dir.resolve("merged.xlsx");

        XlsxSheetMerger.merge(parts, List.of("Steps", "1. Collect", "Guarantees & <more>"), out);

        LinkedHashMap<String, List<String>> sheets = sheets(out);
        assertEquals(List.of("Steps", "1. Collect", "Guarantees & <more>"), List.copyOf(sheets.keySet()),
                "one sheet per part, in order, names XML-escaped and round-tripped");
        assertEquals(List.of("Steps"), sheets.get("Steps"));
        assertEquals(List.of("1. Collect"), sheets.get("1. Collect"));
        assertEquals(List.of("Guarantees & more"), sheets.get("Guarantees & <more>"));
    }

    @Test
    void aSinglePartIsStillAValidOneSheetWorkbook(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("merged.xlsx");
        XlsxSheetMerger.merge(List.of(part(dir, "a.xlsx", "x", inline("only"), STYLES)), List.of("Pipeline"), out);
        assertEquals(Map.of("Pipeline", List.of("only")), sheets(out));
    }

    /** ⛔ A later sheet pointing into ITS OWN shared-string table would show the base's strings — refuse. */
    @Test
    void aSharedStringSheetIsRefusedNotMisRendered(@TempDir Path dir) throws Exception {
        List<Path> parts = List.of(
                part(dir, "a.xlsx", "x", inline("a"), STYLES),
                part(dir, "b.xlsx", "x", "<c r=\"A1\" t=\"s\"><v>0</v></c>", STYLES));
        Path out = dir.resolve("merged.xlsx");

        Exception e = assertThrows(Exception.class, () -> XlsxSheetMerger.merge(parts, List.of("A", "B"), out));
        assertTrue(e.getMessage().contains("shared-string"), e.getMessage());
    }

    /** ⛔ Style indices are only meaningful against their own styles.xml — a differing one is refused. */
    @Test
    void aDifferingStylesheetIsRefused(@TempDir Path dir) throws Exception {
        List<Path> parts = List.of(
                part(dir, "a.xlsx", "x", inline("a"), STYLES),
                part(dir, "b.xlsx", "x", inline("b"), STYLES.replace("/>", "><fonts count=\"2\"/></styleSheet>")));
        Path out = dir.resolve("merged.xlsx");

        Exception e = assertThrows(Exception.class, () -> XlsxSheetMerger.merge(parts, List.of("A", "B"), out));
        assertTrue(e.getMessage().contains("styles.xml"), e.getMessage());
    }
}
