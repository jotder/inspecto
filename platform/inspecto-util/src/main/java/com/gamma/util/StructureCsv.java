package com.gamma.util;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.gamma.util.Values.strOrEmpty;

/**
 * The Schema <em>structure</em> CSV (ELT final amendment §3.2, first table; STRUCTURE-CSV-1, 2026-09-06): a
 * flat table of a schema's {@code raw.fields} — one row per field — persisted as the sibling
 * {@code <name>_structure.csv} of a schema file, with exactly the split-read/split-write idiom of
 * {@link MappingCsv}: the engine's config parser and the control plane's {@code GET /config/schema/<name>}
 * merge a present sibling back over the TOON's inline {@code raw.fields}; {@code /config/write} and
 * {@code /config/patch} write the sibling and strip the fields from the TOON.
 *
 * <p>Canonical header {@code field,type,selector,unit,description,classification} (the plan's spelling;
 * {@code name} is accepted as a read alias of {@code field}). Any column order. {@code field} and
 * {@code type} are required per row; a blank {@code selector}/{@code unit}/{@code description}/
 * {@code classification} cell is <b>omitted</b> from the decoded field map rather than carried as
 * {@code ""} — the majority shipped shape is {@code {name,selector,type}}.
 *
 * <p>⚠ A field may legitimately carry keys the CSV has no column for ({@code timezone},
 * {@code timezone_column}, {@code partitions}, …). {@link #splittable} says whether a field list can
 * travel in this shape losslessly; a writer must keep such a list inline in the TOON.
 */
public final class StructureCsv {

    private StructureCsv() {}

    /** The row keys this shape can carry — anything else on a field keeps the list inline. */
    public static final Set<String> COLUMNS =
            Set.of("name", "type", "selector", "unit", "description", "classification");

    /** Sibling naming: {@code x_schema.toon → x_structure.csv}; otherwise {@code <stem>_structure.csv}. */
    public static Path siblingFor(Path schemaFile) {
        String fn = schemaFile.getFileName().toString();
        String base = fn.endsWith("_schema.toon") ? fn.substring(0, fn.length() - "_schema.toon".length())
                : fn.endsWith(".toon")            ? fn.substring(0, fn.length() - ".toon".length())
                : fn;
        Path parent = schemaFile.getParent();
        String sibling = base + "_structure.csv";
        return parent == null ? Paths.get(sibling) : parent.resolve(sibling);
    }

    /** {@code true} when every field is a map whose keys all have a CSV column (lossless split). */
    public static boolean splittable(List<?> fields) {
        if (fields == null || fields.isEmpty()) return false;
        for (Object f : fields) {
            if (!(f instanceof Map<?, ?> m)) return false;
            for (Object k : m.keySet()) if (!COLUMNS.contains(String.valueOf(k))) return false;
            if (m.get("name") == null || m.get("type") == null) return false;
        }
        return true;
    }

    /**
     * Parse structure-CSV text into the {@code raw.fields} list shape. {@code sourceLabel} names the
     * file in error messages (fail-fast).
     */
    public static List<Map<String, String>> parse(String text, String sourceLabel) {
        try (CSVReader csv = Csv.reader(new StringReader(text))) {
            String[] header = csv.readNext();
            while (header != null && (header.length == 0 || String.join("", header).isBlank()))
                header = csv.readNext();
            if (header == null)
                throw new IllegalArgumentException("Structure CSV is empty: " + sourceLabel);
            List<String> h = Arrays.stream(header).map(String::trim).toList();
            int nIdx = headerIndex(h, "field", "name");
            int tIdx = h.indexOf("type");
            if (nIdx < 0 || tIdx < 0)
                throw new IllegalArgumentException("Structure CSV " + sourceLabel + " header must name "
                        + "field (or name) and type (optional selector/unit/description/classification); got: " + h);
            int sIdx = h.indexOf("selector"), uIdx = h.indexOf("unit"),
                dIdx = h.indexOf("description"), cIdx = h.indexOf("classification");
            List<Map<String, String>> fields = new ArrayList<>();
            String[] row;
            while ((row = csv.readNext()) != null) {
                if (row.length == 0 || String.join("", row).isBlank()) continue;
                if (row.length <= Math.max(nIdx, tIdx))
                    throw new IllegalArgumentException("Structure CSV " + sourceLabel + " line "
                            + csv.getLinesRead() + ": too few columns");
                String name = row[nIdx].trim(), type = row[tIdx].trim();
                if (name.isEmpty() || type.isEmpty())
                    throw new IllegalArgumentException("Structure CSV " + sourceLabel + " line "
                            + csv.getLinesRead() + ": field and type are required");
                Map<String, String> field = new LinkedHashMap<>();
                field.put("name", name);
                putIfPresent(field, "selector", row, sIdx);
                field.put("type", type);
                putIfPresent(field, "description", row, dIdx);
                putIfPresent(field, "unit", row, uIdx);
                putIfPresent(field, "classification", row, cIdx);
                fields.add(field);
            }
            if (fields.isEmpty())
                throw new IllegalArgumentException("Structure CSV has a header but no fields: " + sourceLabel);
            return fields;
        } catch (IOException | CsvValidationException e) {
            throw new IllegalArgumentException(
                    "Structure CSV " + sourceLabel + " does not parse: " + e.getMessage(), e);
        }
    }

    /** Encode a {@code raw.fields} list as structure-CSV text (canonical header, trailing newline). */
    public static String encode(List<? extends Map<String, ?>> fields) {
        StringBuilder sb = new StringBuilder("field,type,selector,unit,description,classification\n");
        for (Map<String, ?> f : fields) {
            sb.append(quote(strOrEmpty(f.get("name")))).append(',')
              .append(quote(strOrEmpty(f.get("type")))).append(',')
              .append(quote(strOrEmpty(f.get("selector")))).append(',')
              .append(quote(strOrEmpty(f.get("unit")))).append(',')
              .append(quote(strOrEmpty(f.get("description")))).append(',')
              .append(quote(strOrEmpty(f.get("classification")))).append('\n');
        }
        return sb.toString();
    }

    private static void putIfPresent(Map<String, String> field, String key, String[] row, int idx) {
        if (idx < 0 || idx >= row.length) return;
        String v = row[idx].trim();
        if (!v.isEmpty()) field.put(key, v);
    }

    private static int headerIndex(List<String> header, String canonical, String alias) {
        int i = header.indexOf(canonical);
        return i >= 0 ? i : header.indexOf(alias);
    }

    /** RFC4180 quoting, applied only when the cell needs it (comma, quote, or edge whitespace). */
    private static String quote(String cell) {
        if (cell.indexOf(',') < 0 && cell.indexOf('"') < 0 && cell.equals(cell.trim())) return cell;
        return '"' + cell.replace("\"", "\"\"") + '"';
    }
}
