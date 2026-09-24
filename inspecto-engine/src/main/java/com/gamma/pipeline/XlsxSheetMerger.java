package com.gamma.pipeline;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Assembles several one-sheet workbooks into ONE workbook with a sheet per input
 * ({@code XLSX-EXPORT-LAST-SECTION-ONLY-1}). ⛔ {@code java.util.zip} only — no POI.
 *
 * <p>Why it exists: DuckDB 1.5.2's {@code excel} writer accepts {@code COPY … (APPEND true)} without error
 * but rewrites the file, so one COPY per sheet kept only the last sheet. Each section is therefore written
 * to its own workbook and the sheets are stitched here.
 *
 * <p>The first input is the base (its styles, docProps, sharedStrings). For every further input its
 * {@code xl/worksheets/sheet1.xml} becomes {@code sheetN.xml}, with a {@code <sheet>} in
 * {@code xl/workbook.xml}, a Relationship in {@code xl/_rels/workbook.xml.rels} and an Override in
 * {@code [Content_Types].xml}.
 *
 * <p>⛔ <b>Fail closed on what it does not re-map.</b> The 1.5.2 writer emits inline strings
 * ({@code t="inlineStr"}, empty {@code sst}) and a byte-identical {@code styles.xml} for every workbook
 * (inspected 2026-09-24). A later sheet that references the shared-string table ({@code t="s"}) or carries a
 * different {@code styles.xml} would silently show the wrong text or formats after a merge, so either one
 * throws instead — a writer upgrade that changes this must fail the export, not corrupt it.
 */
final class XlsxSheetMerger {

    private XlsxSheetMerger() {}

    static final String SHEET1 = "xl/worksheets/sheet1.xml";
    private static final String WORKBOOK = "xl/workbook.xml";
    private static final String RELS = "xl/_rels/workbook.xml.rels";
    private static final String TYPES = "[Content_Types].xml";
    private static final String STYLES = "xl/styles.xml";
    private static final String WORKSHEET_TYPE =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet";
    private static final String WORKSHEET_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml";

    private static final Pattern SHEETS = Pattern.compile("<sheets>.*?</sheets>|<sheets/>", Pattern.DOTALL);
    private static final Pattern SHEET_RID = Pattern.compile("<sheet\\b[^>]*\\br:id=\"([^\"]+)\"");
    private static final Pattern REL_ID = Pattern.compile("\\bId=\"rId(\\d+)\"");
    private static final Pattern SHARED_STRING_CELL = Pattern.compile("<c\\b[^>]*\\bt=\"s\"");

    /** Merge {@code parts} (one-sheet workbooks) into {@code target}, naming sheet i {@code names.get(i)}. */
    static void merge(List<Path> parts, List<String> names, Path target) throws IOException {
        if (parts.isEmpty() || parts.size() != names.size())
            throw new IllegalArgumentException("need one name per part: " + parts.size() + " vs " + names.size());

        Map<String, byte[]> base = read(parts.get(0));
        for (String required : List.of(WORKBOOK, RELS, TYPES, SHEET1))
            if (!base.containsKey(required)) throw new IOException("not a one-sheet workbook, no " + required);

        String workbook = text(base, WORKBOOK);
        Matcher firstSheet = SHEET_RID.matcher(workbook);
        if (!firstSheet.find() || firstSheet.find())
            throw new IOException("the base workbook must hold exactly one sheet: " + workbook);
        firstSheet.reset().find();
        String firstRid = firstSheet.group(1);

        String rels = text(base, RELS);
        int nextRid = 1;
        for (Matcher m = REL_ID.matcher(rels); m.find(); ) nextRid = Math.max(nextRid, Integer.parseInt(m.group(1)) + 1);

        StringBuilder sheets = new StringBuilder("<sheets>");
        StringBuilder newRels = new StringBuilder();
        StringBuilder newTypes = new StringBuilder();
        Map<String, byte[]> added = new LinkedHashMap<>();
        for (int i = 0; i < parts.size(); i++) {
            String rid = i == 0 ? firstRid : "rId" + nextRid++;
            sheets.append("<sheet name=\"").append(escape(names.get(i))).append("\" sheetId=\"").append(i + 1)
                    .append("\" r:id=\"").append(rid).append("\"/>");
            if (i == 0) continue;

            Map<String, byte[]> part = read(parts.get(i));
            byte[] sheet = part.get(SHEET1);
            if (sheet == null) throw new IOException("part " + (i + 1) + " has no " + SHEET1);
            if (SHARED_STRING_CELL.matcher(new String(sheet, StandardCharsets.UTF_8)).find())
                throw new IOException("part " + (i + 1) + " uses the shared-string table, which this merger does not re-map");
            if (!Arrays.equals(base.get(STYLES), part.get(STYLES)))
                throw new IOException("part " + (i + 1) + " carries a different " + STYLES + ", which this merger does not re-map");

            String file = "sheet" + (i + 1) + ".xml";
            added.put("xl/worksheets/" + file, sheet);
            newRels.append("<Relationship Id=\"").append(rid).append("\" Type=\"").append(WORKSHEET_TYPE)
                    .append("\" Target=\"worksheets/").append(file).append("\"/>");
            newTypes.append("<Override PartName=\"/xl/worksheets/").append(file).append("\" ContentType=\"")
                    .append(WORKSHEET_CONTENT_TYPE).append("\"/>");
        }
        sheets.append("</sheets>");

        base.put(WORKBOOK, utf8(SHEETS.matcher(workbook).replaceFirst(Matcher.quoteReplacement(sheets.toString()))));
        base.put(RELS, utf8(insertBefore(rels, "</Relationships>", newRels)));
        base.put(TYPES, utf8(insertBefore(text(base, TYPES), "</Types>", newTypes)));
        base.putAll(added);

        try (OutputStream out = Files.newOutputStream(target); ZipOutputStream zip = new ZipOutputStream(out)) {
            byte[] types = base.remove(TYPES);
            put(zip, TYPES, types);   // conventional first entry
            for (Map.Entry<String, byte[]> e : base.entrySet()) put(zip, e.getKey(), e.getValue());
        }
    }

    private static Map<String, byte[]> read(Path xlsx) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(xlsx.toFile())) {
            for (ZipEntry e : java.util.Collections.list(zip.entries()))
                if (!e.isDirectory()) entries.put(e.getName(), zip.getInputStream(e).readAllBytes());
        }
        return entries;
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static String insertBefore(String xml, String close, CharSequence insert) throws IOException {
        int at = xml.lastIndexOf(close);
        if (at < 0) throw new IOException("no " + close + " to extend");
        return xml.substring(0, at) + insert + xml.substring(at);
    }

    private static String text(Map<String, byte[]> entries, String name) {
        return new String(entries.get(name), StandardCharsets.UTF_8);
    }

    private static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
