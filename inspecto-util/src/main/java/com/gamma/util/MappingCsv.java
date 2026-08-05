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

/**
 * The Mapping CSV component format (ELT final amendment §3.2, Phase 1): a flat table of mapping
 * rules — one row per output column — shared by the config parser's sibling dual-read (slice 1),
 * the {@code /config/write} split-write (slice 2), and the {@code mapping} component kind (slice 3).
 *
 * <p>Canonical header {@code targetColumn,sourceExpression,transformType} (the exact rule keys
 * {@code DataTransformer} consumes); the short spellings {@code target,source,kind} from the plan's
 * §3.2 example are accepted as read aliases. Any column order; a blank kind means DIRECT
 * (matching {@code TransformCompiler}); quoted cells carry commas — how EXPR expressions travel.
 * Reads go through {@link Csv} (the platform's single RFC4180 reader — backslashes stay literal,
 * which matters for Windows paths inside expressions).
 */
public final class MappingCsv {

    private MappingCsv() {}

    /** Sibling naming: {@code x_schema.toon → x_mapping.csv}; otherwise {@code <stem>_mapping.csv}. */
    public static Path siblingFor(Path schemaFile) {
        String fn = schemaFile.getFileName().toString();
        String base = fn.endsWith("_schema.toon") ? fn.substring(0, fn.length() - "_schema.toon".length())
                : fn.endsWith(".toon")            ? fn.substring(0, fn.length() - ".toon".length())
                : fn;
        Path parent = schemaFile.getParent();
        String sibling = base + "_mapping.csv";
        return parent == null ? Paths.get(sibling) : parent.resolve(sibling);
    }

    /**
     * Parse mapping-CSV text into the {@code mapping.rules} list shape. {@code sourceLabel} names
     * the file in error messages (fail-fast).
     */
    public static List<Map<String, String>> parse(String text, String sourceLabel) {
        try (CSVReader csv = Csv.reader(new StringReader(text))) {
            String[] header = csv.readNext();
            while (header != null && (header.length == 0 || String.join("", header).isBlank()))
                header = csv.readNext();
            if (header == null)
                throw new IllegalArgumentException("Mapping CSV is empty: " + sourceLabel);
            List<String> h = Arrays.stream(header).map(String::trim).toList();
            int tIdx = headerIndex(h, "targetColumn", "target");
            int sIdx = headerIndex(h, "sourceExpression", "source");
            int kIdx = headerIndex(h, "transformType", "kind");
            if (tIdx < 0 || sIdx < 0)
                throw new IllegalArgumentException("Mapping CSV " + sourceLabel + " header must name "
                        + "targetColumn and sourceExpression (or target/source; optional "
                        + "transformType/kind); got: " + h);
            List<Map<String, String>> rules = new ArrayList<>();
            String[] row;
            while ((row = csv.readNext()) != null) {
                if (row.length == 0 || String.join("", row).isBlank()) continue;
                if (row.length <= Math.max(tIdx, sIdx))
                    throw new IllegalArgumentException("Mapping CSV " + sourceLabel + " line "
                            + csv.getLinesRead() + ": too few columns");
                Map<String, String> rule = new LinkedHashMap<>();
                rule.put("targetColumn", row[tIdx].trim());
                rule.put("sourceExpression", row[sIdx]);
                String kind = kIdx >= 0 && kIdx < row.length ? row[kIdx].trim() : "";
                rule.put("transformType", kind);
                rules.add(rule);
            }
            if (rules.isEmpty())
                throw new IllegalArgumentException("Mapping CSV has a header but no rules: " + sourceLabel);
            return rules;
        } catch (IOException | CsvValidationException e) {
            throw new IllegalArgumentException(
                    "Mapping CSV " + sourceLabel + " does not parse: " + e.getMessage(), e);
        }
    }

    /** Encode a {@code mapping.rules} list as mapping-CSV text (canonical header, trailing newline). */
    public static String encode(List<? extends Map<String, ?>> rules) {
        StringBuilder sb = new StringBuilder("targetColumn,sourceExpression,transformType\n");
        for (Map<String, ?> rule : rules) {
            sb.append(quote(str(rule.get("targetColumn")))).append(',')
              .append(quote(str(rule.get("sourceExpression")))).append(',')
              .append(quote(str(rule.get("transformType")))).append('\n');
        }
        return sb.toString();
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
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
